package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.util.LLMUsage;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Simplified LangChain4j service for test generation.
 * Provides basic RAG capabilities without complex dependencies.
 */
@Service(Service.Level.PROJECT)
public final class ZestLangChain4jService {
    private static final Logger LOG = Logger.getInstance(ZestLangChain4jService.class);

    private final Project project;
    private final NaiveLLMService naiveLlmService;

    // LangChain4j components
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ContentRetriever primaryRetriever;

    // Configuration
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final double DEFAULT_RELEVANCE_THRESHOLD = 0.3;
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "js", "ts", "jsx", "tsx", "py", "go", "rs", "cpp", "c", "h",
        "cs", "php", "rb", "swift", "scala", "clj", "sh", "bash", "sql"
    );

    private volatile boolean isIndexed = false;
    private final ScheduledExecutorService incrementalIndexer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Long> chunkTimestamps = new ConcurrentHashMap<>();

    public ZestLangChain4jService(@NotNull Project project) {
        this.project = project;
        this.naiveLlmService = project.getService(NaiveLLMService.class);

        // Use simple embedding model stub
        this.embeddingModel = new SimpleEmbeddingModel();
        this.embeddingStore = new InMemoryEmbeddingStore<>();

        // Initialize retriever
        this.primaryRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(DEFAULT_MAX_RESULTS)
            .minScore(DEFAULT_RELEVANCE_THRESHOLD)
            .build();

        LOG.info("ZestLangChain4jService initialized for project: " + project.getName());
    }

    /**
     * Retrieve relevant context
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query) {
        return retrieveContext(query, DEFAULT_MAX_RESULTS, DEFAULT_RELEVANCE_THRESHOLD);
    }

    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query, int maxResults, double threshold) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isIndexed) {
                    return new RetrievalResult(true, "Codebase not indexed", Collections.emptyList());
                }

                List<Content> contents = primaryRetriever.retrieve(Query.from(query));
                List<ContextItem> items = contents.stream()
                    .limit(maxResults)
                    .map(this::contentToContextItem)
                    .collect(Collectors.toList());

                return new RetrievalResult(true, "Found " + items.size() + " results", items);
            } catch (Exception e) {
                LOG.error("Error retrieving context", e);
                return new RetrievalResult(false, "Error: " + e.getMessage(), Collections.emptyList());
            }
        });
    }

    private ContextItem contentToContextItem(Content content) {
        TextSegment segment = content.textSegment();
        Metadata metadata = segment.metadata();
        String filePath = metadata.getString("file");
        Integer lineNumber = metadata.getInteger("startLine");

        return new ContextItem(
            UUID.randomUUID().toString(),
            filePath != null ? filePath : "unknown",
            segment.text(),
            filePath,
            lineNumber,
            0.8
        );
    }

    /**
     * Execute an LLM task
     */
    @NotNull
    public CompletableFuture<TaskResult> executeTask(@NotNull String taskDescription) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(taskDescription)
                    .withModel("local-model")
                    .withMaxTokens(8000)
                    .withTimeout(60000);

                String response = naiveLlmService.queryWithParams(params, LLMUsage.AGENT_TEST_WRITING);

                if (response == null) {
                    return new TaskResult(false, "Failed to execute task", null, Collections.emptyList());
                }

                return new TaskResult(true, "Task completed", response, Collections.emptyList());
            } catch (Exception e) {
                LOG.error("Error executing task", e);
                return new TaskResult(false, "Error: " + e.getMessage(), null, Collections.emptyList());
            }
        });
    }

    /**
     * Chat with context
     */
    @NotNull
    public CompletableFuture<String> chatWithContext(@NotNull String message, @NotNull List<String> conversationHistory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(message)
                    .withModel("local-model")
                    .withMaxTokens(4000)
                    .withTimeout(30000);

                String response = naiveLlmService.queryWithParams(params, LLMUsage.CHAT_CODE_REVIEW);
                return response != null ? response : "No response";
            } catch (Exception e) {
                LOG.error("Error in chat", e);
                return "Error: " + e.getMessage();
            }
        });
    }

    /**
     * Index codebase (simplified)
     */
    public CompletableFuture<Boolean> indexCodebase() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String basePath = project.getBasePath();
                if (basePath == null) {
                    return false;
                }

                Path projectPath = Paths.get(basePath);
                List<Path> codeFiles = findCodeFiles(projectPath);

                int indexed = 0;
                for (Path file : codeFiles) {
                    try {
                        indexFile(file);
                        indexed++;
                    } catch (Exception e) {
                        LOG.debug("Failed to index: " + file);
                    }
                }

                isIndexed = indexed > 0;
                LOG.info("Indexed " + indexed + " files");
                return isIndexed;
            } catch (Exception e) {
                LOG.error("Error indexing", e);
                return false;
            }
        });
    }

    private List<Path> findCodeFiles(Path projectPath) throws IOException {
        try (Stream<Path> paths = Files.walk(projectPath)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(this::isCodeFile)
                .filter(this::notIgnored)
                .collect(Collectors.toList());
        }
    }

    private boolean isCodeFile(Path path) {
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            String ext = fileName.substring(lastDot + 1).toLowerCase();
            return CODE_EXTENSIONS.contains(ext);
        }
        return false;
    }

    private boolean notIgnored(Path path) {
        String pathStr = path.toString().toLowerCase();
        return !pathStr.contains("node_modules") &&
               !pathStr.contains(".git") &&
               !pathStr.contains("target") &&
               !pathStr.contains("build") &&
               !pathStr.contains(".idea");
    }

    private void indexFile(Path file) throws IOException {
        String content = Files.readString(file);
        String relativePath = Paths.get(project.getBasePath()).relativize(file).toString();

        if (content.isEmpty()) return;

        // Simple chunking by lines
        List<String> lines = content.lines().collect(Collectors.toList());
        int chunkSize = 30;

        for (int i = 0; i < lines.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, lines.size());
            String chunk = String.join("\n", lines.subList(i, end));

            Map<String, String> meta = new HashMap<>();
            meta.put("file", relativePath);
            meta.put("startLine", String.valueOf(i + 1));
            meta.put("endLine", String.valueOf(end));
            Metadata metadata = Metadata.from(meta);

            TextSegment segment = TextSegment.from(chunk, metadata);
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }
    }

    public void dispose() {
        incrementalIndexer.shutdown();
    }

    public int getIndexedChunkCount() {
        return 0; // Simplified
    }

    // Data classes

    public static class RetrievalResult {
        private final boolean success;
        private final String message;
        private final List<ContextItem> items;

        public RetrievalResult(boolean success, String message, List<ContextItem> items) {
            this.success = success;
            this.message = message;
            this.items = items;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public List<ContextItem> getItems() { return items; }
    }

    public static class TaskResult {
        private final boolean success;
        private final String message;
        private final String result;
        private final List<String> steps;

        public TaskResult(boolean success, String message, String result, List<String> steps) {
            this.success = success;
            this.message = message;
            this.result = result;
            this.steps = steps;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getResult() { return result; }
        public List<String> getSteps() { return steps; }
    }

    public static class ContextItem {
        private final String id;
        private final String title;
        private final String content;
        private final String filePath;
        private final Integer lineNumber;
        private final double score;

        public ContextItem(String id, String title, String content, String filePath, Integer lineNumber, double score) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.score = score;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getFilePath() { return filePath; }
        public Integer getLineNumber() { return lineNumber; }
        public double getScore() { return score; }
    }

    /**
     * Simple embedding model that generates random embeddings
     */
    private static class SimpleEmbeddingModel implements EmbeddingModel {
        private static final int DIMENSION = 384;

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(generateEmbedding(text));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return Response.from(generateEmbedding(segment.text()));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = segments.stream()
                .map(s -> generateEmbedding(s.text()))
                .collect(Collectors.toList());
            return Response.from(embeddings);
        }

        private Embedding generateEmbedding(String text) {
            // Simple hash-based embedding for basic similarity
            float[] vector = new float[DIMENSION];
            int hash = text.hashCode();
            Random random = new Random(hash);
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] = random.nextFloat() * 2 - 1;
            }
            // Normalize
            float norm = 0;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            for (int i = 0; i < DIMENSION; i++) vector[i] /= norm;
            return Embedding.from(vector);
        }
    }
}
