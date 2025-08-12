package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.util.LLMService;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LangChain4j service with in-memory vector store for RAG and agent capabilities.
 * Uses LangChain4j's built-in embedding models and vector store.
 */
@Service(Service.Level.PROJECT)
public class ZestLangChain4jService {
    private static final Logger LOG = Logger.getInstance(ZestLangChain4jService.class);
    
    private final Project project;
    private final LLMService llmService;
    
    // LangChain4j components
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    
    // Configuration
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final double DEFAULT_RELEVANCE_THRESHOLD = 0.7;
    private static final int CHUNK_SIZE = 200; // lines per chunk (reduced for better granularity)
    private static final int CHUNK_OVERLAP = 50; // lines overlap between chunks
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "js", "ts", "jsx", "tsx", "py", "go", "rs", "cpp", "c", "h",
        "cs", "php", "rb", "swift", "scala", "clj", "sh", "bash", "sql"
    );
    
    private volatile boolean isIndexed = false;
    
    public ZestLangChain4jService(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        
        // Initialize LangChain4j components with custom embedding model
        this.embeddingModel = new ZestEmbeddingModel(project);
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        
        LOG.info("ZestLangChain4jService initialized with in-memory vector store and remote embeddings for project: " + project.getName());
        
        // Index the codebase on startup
        indexCodebaseAsync();
    }
    
    /**
     * Index the codebase into the vector store
     */
    public CompletableFuture<Boolean> indexCodebase() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting codebase indexing...");
                
                Path projectPath = Paths.get(project.getBasePath());
                List<Path> codeFiles = findCodeFiles(projectPath);
                
                LOG.info("Found " + codeFiles.size() + " code files to index");
                
                int indexed = 0;
                for (Path file : codeFiles) {
                    try {
                        indexFile(file);
                        indexed++;
                        
                        if (indexed % 50 == 0) {
                            LOG.info("Indexed " + indexed + "/" + codeFiles.size() + " files");
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to index file: " + file, e);
                    }
                }
                
                isIndexed = true;
                LOG.info("Codebase indexing complete. Indexed " + indexed + " files");
                return true;
                
            } catch (Exception e) {
                LOG.error("Error indexing codebase", e);
                return false;
            }
        });
    }
    
    /**
     * Index codebase asynchronously on startup
     */
    private void indexCodebaseAsync() {
        indexCodebase().thenAccept(success -> {
            if (success) {
                LOG.info("Background indexing completed successfully");
            } else {
                LOG.warn("Background indexing failed");
            }
        });
    }
    
    /**
     * Retrieve relevant context using LangChain4j vector store
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query) {
        return retrieveContext(query, DEFAULT_MAX_RESULTS, DEFAULT_RELEVANCE_THRESHOLD);
    }
    
    /**
     * Retrieve relevant context with custom parameters
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query, int maxResults, double threshold) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isIndexed) {
                    LOG.warn("Codebase not yet indexed, results may be incomplete");
                }
                
                // Generate embedding for the query
                Embedding queryEmbedding = embeddingModel.embed(query).content();
                
                // Search in vector store
                List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                    queryEmbedding, 
                    maxResults, 
                    threshold
                );
                
                // Convert matches to context items
                List<ContextItem> items = matches.stream()
                    .map(this::matchToContextItem)
                    .collect(Collectors.toList());
                
                LOG.info("Found " + items.size() + " relevant context items for query: " + query);
                
                return new RetrievalResult(true, "Retrieved " + items.size() + " relevant items", items);
                
            } catch (Exception e) {
                LOG.error("Error retrieving context", e);
                return new RetrievalResult(false, "Error: " + e.getMessage(), List.of());
            }
        });
    }
    
    /**
     * Execute an LLM task with retrieval from vector store
     */
    @NotNull
    public CompletableFuture<TaskResult> executeTask(@NotNull String taskDescription) {
        return executeTask(taskDescription, true, null);
    }
    
    /**
     * Execute an LLM task with custom parameters
     */
    @NotNull
    public CompletableFuture<TaskResult> executeTask(@NotNull String taskDescription, 
                                                    boolean useRetrieval, 
                                                    @Nullable String additionalContext) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String enhancedPrompt = taskDescription;
                
                // Add retrieval context if requested
                if (useRetrieval && isIndexed) {
                    // Use vector store for retrieval
                    Embedding queryEmbedding = embeddingModel.embed(taskDescription).content();
                    List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                        queryEmbedding, 5, 0.7
                    );
                    
                    if (!matches.isEmpty()) {
                        String contextString = formatMatchesForPrompt(matches);
                        enhancedPrompt = contextString + "\n\n" + taskDescription;
                    }
                }
                
                // Add additional context if provided
                if (additionalContext != null && !additionalContext.trim().isEmpty()) {
                    enhancedPrompt = additionalContext + "\n\n" + enhancedPrompt;
                }
                
                // Create task execution prompt
                String taskPrompt = buildTaskPrompt(enhancedPrompt);
                
                // Use existing LLMService to execute task
                LLMService.LLMQueryParams params = new LLMService.LLMQueryParams(taskPrompt)
                    .withModel("local-model")
                    .withMaxTokens(8000)
                    .withTimeout(60000);
                
                String response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);
                
                if (response == null) {
                    return new TaskResult(false, "Failed to execute task", null, List.of());
                }
                
                // Parse the response
                List<String> steps = extractStepsFromResponse(response);
                
                return new TaskResult(true, "Task completed successfully", response, steps);
                
            } catch (Exception e) {
                LOG.error("Error executing task", e);
                return new TaskResult(false, "Error: " + e.getMessage(), null, List.of());
            }
        });
    }
    
    /**
     * Execute multiple tasks in sequence (agent workflow)
     */
    @NotNull
    public CompletableFuture<WorkflowResult> executeWorkflow(@NotNull List<String> tasks) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<TaskResult> results = new ArrayList<>();
                StringBuilder cumulativeContext = new StringBuilder();
                
                for (int i = 0; i < tasks.size(); i++) {
                    String task = tasks.get(i);
                    
                    // Add context from previous tasks
                    String contextualTask = task;
                    if (cumulativeContext.length() > 0) {
                        contextualTask = "Previous context:\n" + cumulativeContext.toString() + "\n\nCurrent task: " + task;
                    }
                    
                    TaskResult result = executeTask(contextualTask, true, null).join();
                    results.add(result);
                    
                    // Add result to cumulative context for next tasks
                    if (result.isSuccess() && result.getResult() != null) {
                        cumulativeContext.append("Task ").append(i + 1).append(" result: ").append(result.getResult()).append("\n");
                    }
                    
                    // Stop workflow if task failed
                    if (!result.isSuccess()) {
                        break;
                    }
                }
                
                boolean allSuccessful = results.stream().allMatch(TaskResult::isSuccess);
                String summary = generateWorkflowSummary(results);
                
                return new WorkflowResult(allSuccessful, summary, results);
                
            } catch (Exception e) {
                LOG.error("Error executing workflow", e);
                return new WorkflowResult(false, "Workflow failed: " + e.getMessage(), List.of());
            }
        });
    }
    
    /**
     * Chat with context from vector store
     */
    @NotNull
    public CompletableFuture<String> chatWithContext(@NotNull String message, @NotNull List<String> conversationHistory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Retrieve relevant context from vector store
                List<ContextItem> contextItems = new ArrayList<>();
                
                if (isIndexed) {
                    Embedding queryEmbedding = embeddingModel.embed(message).content();
                    List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                        queryEmbedding, 3, 0.7
                    );
                    
                    contextItems = matches.stream()
                        .map(this::matchToContextItem)
                        .collect(Collectors.toList());
                }
                
                // Build chat prompt with context and history
                String chatPrompt = buildChatPrompt(message, conversationHistory, contextItems);
                
                // Use existing LLMService for chat
                LLMService.LLMQueryParams params = new LLMService.LLMQueryParams(chatPrompt)
                    .withModel("local-model")
                    .withMaxTokens(4000)
                    .withTimeout(30000);
                
                String response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CHAT_CODE_REVIEW);
                
                return response != null ? response : "Sorry, I couldn't process your request.";
                
            } catch (Exception e) {
                LOG.error("Error in chat with context", e);
                return "Error: " + e.getMessage();
            }
        });
    }
    
    // Private helper methods
    
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
            String extension = fileName.substring(lastDot + 1).toLowerCase();
            return CODE_EXTENSIONS.contains(extension);
        }
        return false;
    }
    
    private boolean notIgnored(Path path) {
        String pathStr = path.toString();
        return !pathStr.contains("node_modules") &&
               !pathStr.contains(".git") &&
               !pathStr.contains("target") &&
               !pathStr.contains("build") &&
               !pathStr.contains("dist");
    }
    
    private void indexFile(Path file) throws IOException {
        String content = Files.readString(file);
        String relativePath = Paths.get(project.getBasePath()).relativize(file).toString();
        
        // Use smarter chunking strategy based on file type
        if (isJavaFile(file)) {
            indexJavaFileByMethods(content, relativePath);
        } else {
            indexFileByLinesWithOverlap(content, relativePath);
        }
    }
    
    /**
     * Index Java files by methods and classes for better semantic chunks
     */
    private void indexJavaFileByMethods(String content, String relativePath) {
        List<String> lines = content.lines().collect(Collectors.toList());
        
        // For now, use overlapping line chunks but mark as Java
        indexFileByLinesWithOverlap(content, relativePath);
        
        // TODO: Add proper AST-based method extraction for better chunking
    }
    
    /**
     * Index files using overlapping line-based chunks
     */
    private void indexFileByLinesWithOverlap(String content, String relativePath) {
        List<String> lines = content.lines().collect(Collectors.toList());
        
        // Create overlapping chunks for better context continuity
        for (int i = 0; i < lines.size(); i += (CHUNK_SIZE - CHUNK_OVERLAP)) {
            int endLine = Math.min(i + CHUNK_SIZE, lines.size());
            
            // Skip tiny chunks at the end
            if (endLine - i < 10) break;
            
            String chunk = lines.subList(i, endLine).stream()
                .collect(Collectors.joining("\n"));
            
            // Add context about the chunk position and overlap
            String chunkInfo = String.format("// Chunk %d-%d of %s (total lines: %d)", 
                i + 1, endLine, relativePath, lines.size());
            String enhancedChunk = chunkInfo + "\n" + chunk;
            
            // Create metadata with more details
            java.util.Map<String, String> metadataMap = new java.util.HashMap<>();
            metadataMap.put("file", relativePath);
            metadataMap.put("startLine", String.valueOf(i + 1));
            metadataMap.put("endLine", String.valueOf(endLine));
            metadataMap.put("project", project.getName());
            metadataMap.put("fileType", getFileExtension(relativePath));
            metadataMap.put("chunkSize", String.valueOf(endLine - i));
            metadataMap.put("hasOverlap", String.valueOf(i > 0 && i < lines.size() - CHUNK_SIZE));
            Metadata metadata = Metadata.from(metadataMap);
            
            // Create text segment
            TextSegment segment = TextSegment.from(enhancedChunk, metadata);
            
            // Generate embedding and store
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }
    }
    
    private boolean isJavaFile(Path file) {
        return file.toString().endsWith(".java");
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }
    
    private ContextItem matchToContextItem(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();
        
        String filePath = metadata.getString("file") != null ? metadata.getString("file") : "unknown";
        Integer startLine = metadata.getInteger("startLine") != null ? metadata.getInteger("startLine") : null;
        Integer endLine = metadata.getInteger("endLine") != null ? metadata.getInteger("endLine") : null;
        
        String title = filePath;
        if (startLine != null) {
            title += ":" + startLine;
            if (endLine != null && !endLine.equals(startLine)) {
                title += "-" + endLine;
            }
        }
        
        return new ContextItem(
            UUID.randomUUID().toString(),
            title,
            segment.text(),
            filePath,
            startLine,
            match.score()
        );
    }
    
    private String formatMatchesForPrompt(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder context = new StringBuilder();
        context.append("## Relevant Code Context:\n\n");
        
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();
            
            context.append("### ").append(i + 1).append(". ");
            String file = metadata.getString("file");
            context.append(file != null ? file : "unknown");
            
            Integer startLineNum = metadata.getInteger("startLine");
            if (startLineNum != null) {
                context.append(":").append(startLineNum);
            }
            
            context.append("\n```\n");
            context.append(segment.text());
            context.append("\n```\n\n");
        }
        
        return context.toString();
    }
    
    private String buildTaskPrompt(@NotNull String taskDescription) {
        return String.format("""
            You are an AI assistant helping with software development tasks in the %s project.
            
            Task: %s
            
            Instructions:
            1. Analyze the task and break it down into steps if needed
            2. Use the provided context to inform your solution
            3. Provide clear, actionable guidance
            4. Include code examples when appropriate
            5. Format your response clearly with steps and final result
            
            Respond with your analysis and solution:
            """, project.getName(), taskDescription);
    }
    
    private String buildChatPrompt(@NotNull String message, @NotNull List<String> history, @NotNull List<ContextItem> context) {
        StringBuilder prompt = new StringBuilder();
        
        // Add relevant context
        if (!context.isEmpty()) {
            prompt.append("## Relevant Code Context:\n");
            for (ContextItem item : context) {
                prompt.append("- ").append(item.getTitle()).append(": ").append(item.getContent().substring(0, Math.min(200, item.getContent().length()))).append("...\n");
            }
            prompt.append("\n");
        }
        
        // Add conversation history
        if (!history.isEmpty()) {
            prompt.append("## Conversation History:\n");
            for (int i = Math.max(0, history.size() - 5); i < history.size(); i++) {
                prompt.append(history.get(i)).append("\n");
            }
            prompt.append("\n");
        }
        
        // Add current message
        prompt.append("## Current Message:\n");
        prompt.append(message).append("\n\n");
        
        prompt.append("Please respond helpfully based on the context and conversation history.");
        
        return prompt.toString();
    }
    
    private List<String> extractStepsFromResponse(@NotNull String response) {
        List<String> steps = new ArrayList<>();
        
        // Simple extraction - look for numbered steps
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.matches("^\\d+\\..*")) {
                steps.add(line.trim());
            }
        }
        
        return steps;
    }
    
    private String generateWorkflowSummary(@NotNull List<TaskResult> results) {
        int successful = (int) results.stream().filter(TaskResult::isSuccess).count();
        return String.format("Workflow completed: %d/%d tasks successful", successful, results.size());
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
    
    public static class WorkflowResult {
        private final boolean success;
        private final String summary;
        private final List<TaskResult> taskResults;
        
        public WorkflowResult(boolean success, String summary, List<TaskResult> taskResults) {
            this.success = success;
            this.summary = summary;
            this.taskResults = taskResults;
        }
        
        public boolean isSuccess() { return success; }
        public String getSummary() { return summary; }
        public List<TaskResult> getTaskResults() { return taskResults; }
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
}