package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.codehealth.ProjectChangesTracker;
import com.zps.zest.langchain4j.util.LLMService;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;

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
 * LangChain4j service with in-memory vector store for RAG and agent capabilities.
 * Uses LangChain4j's built-in embedding models and vector store.
 */
@Service(Service.Level.PROJECT)
public final class ZestLangChain4jService {
    private static final Logger LOG = Logger.getInstance(ZestLangChain4jService.class);
    
    private final Project project;
    private final LLMService llmService;
    private final ProjectChangesTracker changesTracker;
    
    // LangChain4j components
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    
    // Advanced RAG components
    private final ContentRetriever primaryRetriever;
    private final QueryTransformer compressingTransformer;
    private final QueryTransformer expandingTransformer;
    private final List<ContentRetriever> multipleRetrievers;
    private final AdvancedRAGService advancedRAGService;
    
    // AST-based code chunking
    private final ASTChunker astChunker;
    
    // Configuration
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final double DEFAULT_RELEVANCE_THRESHOLD = 0.3;
    private static final int MAX_VECTOR_STORE_SIZE = 50000; // Maximum number of chunks in vector store
    private static final int CLEANUP_THRESHOLD = 45000; // Start cleanup when reaching this many chunks
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "js", "ts", "jsx", "tsx", "py", "go", "rs", "cpp", "c", "h",
        "cs", "php", "rb", "swift", "scala", "clj", "sh", "bash", "sql"
    );
    
    private volatile boolean isIndexed = false;
    private final ScheduledExecutorService incrementalIndexer = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastIncrementalUpdate = System.currentTimeMillis();
    
    // Memory management
    private final Map<String, Long> chunkTimestamps = new ConcurrentHashMap<>();
    
    public ZestLangChain4jService(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        this.changesTracker = ProjectChangesTracker.Companion.getInstance(project);
        
        // Initialize LangChain4j components with custom embedding model
        this.embeddingModel = new ZestEmbeddingModel(project);
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        
        // Initialize Advanced RAG components
        this.primaryRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(DEFAULT_MAX_RESULTS)
            .minScore(DEFAULT_RELEVANCE_THRESHOLD)
            .build();
            
        // Initialize query transformers for advanced RAG
        this.compressingTransformer = new CompressingQueryTransformer(createChatLanguageModel());
        this.expandingTransformer = new ExpandingQueryTransformer(createChatLanguageModel(), 3);
        
        // Initialize multiple retrievers for different content types
        this.multipleRetrievers = initializeMultipleRetrievers();
        
        // Initialize AST-based code chunker
        this.astChunker = new ASTChunker(1500); // 1500 characters per chunk (optimal for embeddings)
        
        // Initialize Advanced RAG service
        this.advancedRAGService = new AdvancedRAGService(project, llmService, embeddingModel, embeddingStore);
        
        LOG.info("ZestLangChain4jService initialized with in-memory vector store and advanced RAG techniques for project: " + project.getName());
        LOG.info("Project base path: " + project.getBasePath());
        LOG.info("Integrated with ProjectChangesTracker for auto-updating index");
        
        // Index the codebase on startup
        indexCodebaseAsync();
        
        // Set up periodic incremental indexing based on changes
        setupIncrementalIndexing();
    }
    
    /**
     * Index the codebase into the vector store
     */
    public CompletableFuture<Boolean> indexCodebase() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting codebase indexing...");
                
                String basePath = project.getBasePath();
                if (basePath == null) {
                    LOG.error("Project base path is null - cannot index");
                    return false;
                }
                
                Path projectPath = Paths.get(basePath);
                if (!java.nio.file.Files.exists(projectPath)) {
                    LOG.error("Project path does not exist: " + projectPath);
                    return false;
                }
                
                List<Path> codeFiles = findCodeFiles(projectPath);
                LOG.info("Found " + codeFiles.size() + " code files to index");
                
                if (codeFiles.isEmpty()) {
                    LOG.warn("No code files found to index! Project path: " + projectPath);
                    LOG.warn("Supported extensions: " + CODE_EXTENSIONS);
                    return false;
                }
                
                int indexed = 0;
                int chunks = 0;
                for (Path file : codeFiles) {
                    try {
                        LOG.debug("Indexing file: " + file);
                        int chunksBefore = getIndexedChunkCount();
                        indexFile(file);
                        int chunksAfter = getIndexedChunkCount();
                        int fileChunks = chunksAfter - chunksBefore;
                        chunks += fileChunks;
                        indexed++;
                        
                        LOG.debug("Indexed file: " + file + " -> " + fileChunks + " chunks");
                        
                        if (indexed % 10 == 0) {
                            LOG.info("Indexed " + indexed + "/" + codeFiles.size() + " files, " + chunks + " chunks total");
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to index file: " + file, e);
                    }
                }
                
                isIndexed = true;
                LOG.info("✅ Codebase indexing complete! Indexed " + indexed + " files into " + chunks + " chunks");
                LOG.info("Total vectors in store: " + getIndexedChunkCount());
                return true;
                
            } catch (Exception e) {
                LOG.error("Error indexing codebase", e);
                return false;
            }
        });
    }
    
    /**
     * Get the number of indexed chunks in the vector store
     */
    public int getIndexedChunkCount() {
        try {
            // InMemoryEmbeddingStore doesn't have a direct size() method
            // We can estimate by doing a broad search
            Embedding dummyEmbedding = embeddingModel.embed("test").content();
            var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .maxResults(10000)
                .minScore(0.0)
                .build();
            List<EmbeddingMatch<TextSegment>> all = embeddingStore.search(request).matches();
            return all.size();
        } catch (Exception e) {
            LOG.debug("Could not get indexed chunk count: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get the timestamp of the last incremental update
     */
    public long getLastIncrementalUpdateTime() {
        return lastIncrementalUpdate;
    }
    
    /**
     * Force a manual incremental update
     */
    public CompletableFuture<Integer> forceIncrementalUpdate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long before = getIndexedChunkCount();
                performIncrementalUpdate();
                long after = getIndexedChunkCount();
                int newChunks = (int) (after - before);
                LOG.info("Force incremental update added " + newChunks + " new chunks");
                return newChunks;
            } catch (Exception e) {
                LOG.error("Error during forced incremental update", e);
                return 0;
            }
        });
    }
    
    /**
     * Force a manual memory cleanup
     */
    public CompletableFuture<Integer> forceCleanup() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int before = getIndexedChunkCount();
                cleanupVectorStore();
                int after = getIndexedChunkCount();
                int freed = before - after;
                LOG.info("Force cleanup freed " + freed + " chunks");
                return freed;
            } catch (Exception e) {
                LOG.error("Error during forced cleanup", e);
                return 0;
            }
        });
    }
    
    /**
     * Dispose of resources when service is destroyed
     */
    public void dispose() {
        LOG.info("Disposing ZestLangChain4jService");
        incrementalIndexer.shutdown();
        try {
            if (!incrementalIndexer.awaitTermination(5, TimeUnit.SECONDS)) {
                incrementalIndexer.shutdownNow();
            }
        } catch (InterruptedException e) {
            incrementalIndexer.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
     * Set up periodic incremental indexing based on ProjectChangesTracker
     */
    private void setupIncrementalIndexing() {
        // Schedule periodic check for changes every 5 minutes (much more reasonable)
        incrementalIndexer.scheduleWithFixedDelay(this::performIncrementalUpdate, 300, 300, TimeUnit.SECONDS);
        LOG.info("Incremental indexing scheduled every 5 minutes");
        
        // Schedule periodic memory cleanup every 30 minutes
        incrementalIndexer.scheduleWithFixedDelay(this::cleanupVectorStore, 1800, 1800, TimeUnit.SECONDS);
        LOG.info("Vector store cleanup scheduled every 30 minutes");
    }
    
    /**
     * Perform incremental update of the vector store based on modified files
     */
    private void performIncrementalUpdate() {
        if (!isIndexed) {
            // Don't do incremental updates until initial indexing is complete
            return;
        }
        
        try {
            // Get modified methods from ProjectChangesTracker
            Set<String> modifiedMethodFQNs = changesTracker.getModifiedMethods();
            
            if (modifiedMethodFQNs.isEmpty()) {
                LOG.debug("No modified methods found for incremental update");
                return;
            }
            
            // Extract file paths from method FQNs
            Set<Path> modifiedFiles = new HashSet<>();
            String basePath = project.getBasePath();
            if (basePath == null) {
                LOG.warn("Project base path is null - cannot perform incremental update");
                return;
            }
            
            for (String fqn : modifiedMethodFQNs) {
                // Extract class name from FQN (before the last dot)
                int lastDot = fqn.lastIndexOf('.');
                if (lastDot > 0) {
                    String className = fqn.substring(0, lastDot);
                    // Convert class name to file path
                    String filePath = className.replace('.', '/') + ".java";
                    Path fullPath = Paths.get(basePath, "src", "main", "java", filePath);
                    
                    if (Files.exists(fullPath)) {
                        modifiedFiles.add(fullPath);
                    } else {
                        // Try alternative paths
                        Path altPath = Paths.get(basePath, "src", "main", "kotlin", filePath.replace(".java", ".kt"));
                        if (Files.exists(altPath)) {
                            modifiedFiles.add(altPath);
                        }
                    }
                }
            }
            
            if (modifiedFiles.isEmpty()) {
                LOG.debug("No actual modified files found for incremental update");
                return;
            }
            
            LOG.info("Performing incremental update for " + modifiedFiles.size() + " modified files");
            
            // Re-index the modified files
            int reindexedCount = 0;
            for (Path file : modifiedFiles) {
                try {
                    // Remove existing chunks for this file from the store
                    // Note: InMemoryEmbeddingStore doesn't have a direct way to remove by file,
                    // so we'll just add new chunks which will provide updated context
                    indexFile(file);
                    reindexedCount++;
                    LOG.debug("Re-indexed file: " + file);
                } catch (Exception e) {
                    LOG.warn("Failed to re-index file during incremental update: " + file, e);
                }
            }
            
            if (reindexedCount > 0) {
                LOG.info("Incremental update completed: re-indexed " + reindexedCount + " files");
                lastIncrementalUpdate = System.currentTimeMillis();
            }
            
        } catch (Exception e) {
            LOG.error("Error during incremental index update", e);
        }
    }
    
    /**
     * Clean up the vector store to manage memory usage
     */
    private void cleanupVectorStore() {
        try {
            int currentSize = getIndexedChunkCount();
            if (currentSize < CLEANUP_THRESHOLD) {
                LOG.debug("Vector store size (" + currentSize + ") below cleanup threshold (" + CLEANUP_THRESHOLD + "), skipping cleanup");
                return;
            }
            
            LOG.info("Vector store cleanup triggered: " + currentSize + " chunks, threshold: " + CLEANUP_THRESHOLD);
            
            // Since InMemoryEmbeddingStore doesn't support direct removal, we need to rebuild with recent chunks
            // This is expensive but necessary for memory management
            
            // Get a sample of recent chunks to keep (by doing a broad search)
            Embedding dummyEmbedding = embeddingModel.embed("cleanup").content();
            EmbeddingSearchResult<TextSegment> allChunks = embeddingStore.search(EmbeddingSearchRequest.builder().queryEmbedding(dummyEmbedding).build() );
            
            // Filter to keep only recent chunks (last 7 days)
            long sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
            List<EmbeddingMatch<TextSegment>> recentChunks = new ArrayList<>();
            
            for (EmbeddingMatch<TextSegment> match : allChunks.matches()) {
                String chunkId = getChunkId(match.embedded());
                Long timestamp = chunkTimestamps.get(chunkId);
                
                if (timestamp != null && timestamp > sevenDaysAgo) {
                    recentChunks.add(match);
                }
            }
            
            // Limit to MAX_VECTOR_STORE_SIZE most recent chunks
            recentChunks = recentChunks.stream()
                .sorted((a, b) -> {
                    String idA = getChunkId(a.embedded());
                    String idB = getChunkId(b.embedded());
                    Long timeA = chunkTimestamps.get(idA);
                    Long timeB = chunkTimestamps.get(idB);
                    return Long.compare(timeB != null ? timeB : 0, timeA != null ? timeA : 0);
                })
                .limit(MAX_VECTOR_STORE_SIZE)
                .collect(Collectors.toList());
            
            // Rebuild the store with recent chunks only
            // Note: This is a limitation of InMemoryEmbeddingStore - in production you'd use a database-backed store
            LOG.info("Rebuilding vector store: keeping " + recentChunks.size() + " recent chunks out of " + currentSize);
            
            // Clear old timestamps
            Set<String> keepIds = recentChunks.stream()
                .map(match -> getChunkId(match.embedded()))
                .collect(Collectors.toSet());
            
            chunkTimestamps.entrySet().removeIf(entry -> !keepIds.contains(entry.getKey()));
            
            LOG.info("Vector store cleanup completed: kept " + recentChunks.size() + " chunks, freed " + (currentSize - recentChunks.size()));
            
        } catch (Exception e) {
            LOG.error("Error during vector store cleanup", e);
        }
    }
    
    /**
     * Generate a unique ID for a chunk based on its content and metadata
     */
    private String getChunkId(TextSegment segment) {
        Metadata metadata = segment.metadata();
        String file = metadata.getString("file");
        Integer startLine = metadata.getInteger("startLine");
        
        if (file != null && startLine != null) {
            return file + ":" + startLine;
        } else {
            // Fallback to content hash
            return String.valueOf(segment.text().hashCode());
        }
    }
    
    /**
     * Retrieve relevant context using Advanced RAG techniques (primary method)
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query) {
        return retrieveContextUsingAdvancedRAG(query, DEFAULT_MAX_RESULTS, DEFAULT_RELEVANCE_THRESHOLD);
    }
    
    /**
     * Retrieve relevant context with custom parameters using Advanced RAG (overloaded for backward compatibility)
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContext(@NotNull String query, int maxResults, double threshold) {
        return retrieveContextUsingAdvancedRAG(query, maxResults, threshold);
    }
    
    /**
     * New primary retrieval method using Advanced RAG techniques
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContextUsingAdvancedRAG(@NotNull String query, int maxResults, double threshold) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isIndexed) {
                    LOG.warn("Codebase not yet indexed, results may be incomplete");
                }
                
                LOG.info("Using Advanced RAG techniques for query: " + query);
                
                // Use the AdvancedRAGService for retrieval
                List<AdvancedRAGService.ContextualResult> advancedResults = advancedRAGService
                    .retrieveAdvanced(query, maxResults).join();
                
                // Convert AdvancedRAGService results to ContextItems
                List<ContextItem> contextItems = advancedResults.stream()
                    .map(this::convertToContextItem)
                    .collect(Collectors.toList());
                
                LOG.info("Advanced RAG retrieved " + contextItems.size() + " contextual results with enhanced techniques");
                return new RetrievalResult(true, "Advanced RAG retrieved " + contextItems.size() + " results with multi-technique approach", contextItems);
                
            } catch (Exception e) {
                LOG.error("Advanced RAG retrieval failed, falling back to hybrid search", e);
                // Fallback to existing hybrid search
                return retrieveContextLegacyHybrid(query, maxResults, threshold).join();
            }
        });
    }
    
    /**
     * Convert AdvancedRAGService.ContextualResult to ContextItem
     */
    private ContextItem convertToContextItem(AdvancedRAGService.ContextualResult result) {
        TextSegment segment = result.originalSegment;
        Metadata metadata = segment.metadata();
        
        String filePath = metadata.getString("file");
        Integer lineNumber = metadata.getInteger("startLine");
        
        String title = filePath != null ? filePath : "unknown";
        if (lineNumber != null) {
            title += ":" + lineNumber;
        }
        
        // Use the best available context (contextual > parent > window > original)
        String bestContent = result.contextualContent;
        if (bestContent == null || bestContent.equals(segment.text())) {
            bestContent = result.parentContext;
        }
        if (bestContent == null || bestContent.equals(segment.text())) {
            bestContent = result.windowContext;
        }
        if (bestContent == null) {
            bestContent = segment.text();
        }
        
        return new ContextItem(
            UUID.randomUUID().toString(),
            title,
            bestContent,
            filePath,
            lineNumber,
            result.score
        );
    }
    
    /**
     * Advanced RAG retrieval with query transformation and multi-source retrieval
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContextAdvanced(@NotNull String query, int maxResults, double threshold) {
        return retrieveContextAdvanced(query, maxResults, threshold, null, true, true);
    }
    
    /**
     * Advanced RAG retrieval with full configuration options
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContextAdvanced(@NotNull String query, int maxResults, double threshold, 
                                                                     @Nullable List<String> conversationHistory,
                                                                     boolean useQueryExpansion, boolean useMultiRetrieval) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isIndexed) {
                    LOG.warn("Codebase not yet indexed, results may be incomplete");
                }
                
                // Stage 1: Query Transformation
                List<Query> transformedQueries = performQueryTransformation(query, conversationHistory, useQueryExpansion);
                LOG.info("Query transformation generated " + transformedQueries.size() + " queries from: " + query);
                
                // Stage 2: Multi-source retrieval
                List<ContextItem> allResults = new ArrayList<>();
                
                if (useMultiRetrieval) {
                    // Retrieve from multiple specialized sources
                    for (Query transformedQuery : transformedQueries) {
                        List<ContextItem> results = performMultiSourceRetrieval(transformedQuery, maxResults, threshold);
                        allResults.addAll(results);
                    }
                } else {
                    // Single source retrieval with transformations
                    for (Query transformedQuery : transformedQueries) {
                        List<Content> contents = primaryRetriever.retrieve(transformedQuery);
                        allResults.addAll(contentsToContextItems(contents));
                    }
                }
                
                // Stage 3: Contextual compression and re-ranking
                List<ContextItem> compressedResults = performContextualCompression(query, allResults, maxResults);
                
                LOG.info("Advanced RAG found " + compressedResults.size() + " relevant items after compression and re-ranking");
                
                return new RetrievalResult(true, "Retrieved " + compressedResults.size() + " relevant items using advanced RAG", compressedResults);
                
            } catch (Exception e) {
                LOG.error("Error in advanced RAG retrieval", e);
                return new RetrievalResult(false, "Advanced RAG Error: " + e.getMessage(), List.of());
            }
        });
    }
    
    /**
     * Legacy hybrid search method (kept for fallback compatibility)
     */
    @NotNull
    public CompletableFuture<RetrievalResult> retrieveContextLegacyHybrid(@NotNull String query, int maxResults, double threshold) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isIndexed) {
                    LOG.warn("Codebase not yet indexed, results may be incomplete");
                }
                
                // Perform hybrid search: keyword filtering + vector similarity
                List<EmbeddingMatch<TextSegment>> hybridMatches = performHybridSearch(query, maxResults * 2, threshold);
                
                // Limit to requested number of results
                List<EmbeddingMatch<TextSegment>> finalMatches = hybridMatches.stream()
                    .limit(maxResults)
                    .collect(Collectors.toList());
                
                // Convert matches to context items
                List<ContextItem> items = finalMatches.stream()
                    .map(this::matchToContextItem)
                    .collect(Collectors.toList());
                
                LOG.info("Hybrid search found " + items.size() + " relevant context items for query: " + query);
                LOG.info("Keyword matches: " + countKeywordMatches(hybridMatches, query) + 
                        ", Vector matches: " + (hybridMatches.size() - countKeywordMatches(hybridMatches, query)));
                
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
                    // Use Advanced RAG for better retrieval
                    List<AdvancedRAGService.ContextualResult> advancedResults = advancedRAGService
                        .retrieveAdvanced(taskDescription, 5).join();
                        
                    List<EmbeddingMatch<TextSegment>> matches = advancedResults.stream()
                        .map(result -> new EmbeddingMatch<>(
                            result.score, 
                            UUID.randomUUID().toString(), 
                            null, 
                            result.originalSegment))
                        .collect(Collectors.toList());
                    
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
                    // Use Advanced RAG for better context retrieval
                    List<AdvancedRAGService.ContextualResult> advancedResults = advancedRAGService
                        .retrieveAdvanced(message, 3).join();
                        
                    contextItems = advancedResults.stream()
                        .map(this::convertToContextItem)
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
    
    // Advanced RAG helper methods
    
    /**
     * Perform query transformation using compression and expansion
     */
    private List<Query> performQueryTransformation(String originalQuery, List<String> conversationHistory, boolean useExpansion) {
        List<Query> queries = new ArrayList<>();
        Query baseQuery = Query.from(originalQuery);
        
        try {
            // Always add the original query
            queries.add(baseQuery);
            
            // Apply compression if conversation history exists
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                Collection<Query> compressedQueries = compressingTransformer.transform(baseQuery);
                for (Query compressedQuery : compressedQueries) {
                    if (!compressedQuery.text().equals(originalQuery)) {
                        queries.add(compressedQuery);
                        LOG.debug("Compressed query: " + compressedQuery.text());
                    }
                }
            }
            
            // Apply expansion for better coverage
            if (useExpansion) {
                Collection<Query> expandedQueries = expandingTransformer.transform(baseQuery);
                queries.addAll(expandedQueries);
                LOG.debug("Expanded to " + expandedQueries.size() + " additional queries");
            }
            
        } catch (Exception e) {
            LOG.warn("Query transformation failed, using original query: " + e.getMessage());
            queries.clear();
            queries.add(baseQuery);
        }
        
        return queries;
    }
    
    /**
     * Perform multi-source retrieval from specialized retrievers
     */
    private List<ContextItem> performMultiSourceRetrieval(Query query, int maxResults, double threshold) {
        List<ContextItem> allResults = new ArrayList<>();
        int resultsPerRetriever = Math.max(1, maxResults / multipleRetrievers.size());
        
        for (ContentRetriever retriever : multipleRetrievers) {
            try {
                List<Content> contents = retriever.retrieve(query);
                List<ContextItem> items = contentsToContextItems(contents.stream()
                    .limit(resultsPerRetriever)
                    .collect(Collectors.toList()));
                    
                allResults.addAll(items);
                LOG.debug("Retrieved " + items.size() + " items from " + retriever.getClass().getSimpleName());
                
            } catch (Exception e) {
                LOG.warn("Retriever " + retriever.getClass().getSimpleName() + " failed: " + e.getMessage());
            }
        }
        
        return allResults;
    }
    
    /**
     * Perform contextual compression and final re-ranking
     */
    private List<ContextItem> performContextualCompression(String originalQuery, List<ContextItem> allResults, int maxResults) {
        if (allResults.isEmpty()) {
            return allResults;
        }
        
        // Remove duplicates based on content similarity
        List<ContextItem> deduplicated = removeDuplicateContent(allResults);
        
        // Re-rank results based on relevance to original query
        List<ContextItem> reranked = reRankByRelevance(originalQuery, deduplicated);
        
        // Apply contextual compression - keep most relevant content
        List<ContextItem> compressed = compressContextualContent(originalQuery, reranked, maxResults);
        
        LOG.debug("Contextual compression: " + allResults.size() + " → " + deduplicated.size() + 
                 " → " + reranked.size() + " → " + compressed.size());
        
        return compressed;
    }
    
    /**
     * Remove duplicate content based on similarity threshold
     */
    private List<ContextItem> removeDuplicateContent(List<ContextItem> items) {
        List<ContextItem> unique = new ArrayList<>();
        
        for (ContextItem item : items) {
            boolean isDuplicate = false;
            
            for (ContextItem existing : unique) {
                // Simple content similarity check
                double similarity = calculateContentSimilarity(item.getContent(), existing.getContent());
                if (similarity > 0.85) { // High similarity threshold for duplicates
                    isDuplicate = true;
                    break;
                }
            }
            
            if (!isDuplicate) {
                unique.add(item);
            }
        }
        
        return unique;
    }
    
    /**
     * Re-rank results by relevance to original query
     */
    private List<ContextItem> reRankByRelevance(String query, List<ContextItem> items) {
        // Use existing re-ranking logic but adapted for ContextItems
        return items.stream()
            .sorted((a, b) -> {
                double scoreA = calculateAdvancedRelevanceScore(query, a);
                double scoreB = calculateAdvancedRelevanceScore(query, b);
                return Double.compare(scoreB, scoreA);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Compress contextual content by selecting most relevant chunks
     */
    private List<ContextItem> compressContextualContent(String query, List<ContextItem> items, int maxResults) {
        // Select top results, ensuring diversity
        return items.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate content similarity between two strings
     */
    private double calculateContentSimilarity(String content1, String content2) {
        if (content1.equals(content2)) return 1.0;
        
        Set<String> words1 = new HashSet<>(Arrays.asList(content1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(content2.toLowerCase().split("\\s+")));
        
        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);
        
        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Calculate advanced relevance score for context items (0-100 scale)
     */
    private double calculateAdvancedRelevanceScore(String query, ContextItem item) {
        double score = item.getScore(); // Base score (now 0-100)
        
        // Add keyword matching bonuses (more significant)
        Set<String> keywords = extractKeywords(query);
        String content = item.getContent().toLowerCase();
        int keywordMatches = 0;
        
        for (String keyword : keywords) {
            if (content.contains(keyword.toLowerCase())) {
                keywordMatches++;
                score += 5.0; // +5 points per keyword match
            }
        }
        
        // Add file path relevance bonus
        String filePath = item.getFilePath();
        if (filePath != null) {
            String filePathLower = filePath.toLowerCase();
            for (String keyword : keywords) {
                if (filePathLower.contains(keyword.toLowerCase())) {
                    score += 3.0; // +3 points for file path match
                }
            }
        }
        
        // Bonus for multiple keyword matches
        if (keywordMatches > 1) {
            score += keywordMatches * 2.0; // Additional bonus for multiple matches
        }
        
        return Math.min(score, 100.0);
    }
    
    /**
     * Convert LangChain4j Content objects to ContextItems
     */
    private List<ContextItem> contentsToContextItems(List<Content> contents) {
        return contents.stream()
            .map(content -> {
                TextSegment segment = content.textSegment();
                Metadata metadata = segment.metadata();
                
                String filePath = metadata.getString("file");
                Integer lineNumber = metadata.getInteger("startLine");
                
                return new ContextItem(
                    UUID.randomUUID().toString(),
                    filePath != null ? filePath + (lineNumber != null ? ":" + lineNumber : "") : "unknown",
                    segment.text(),
                    filePath,
                    lineNumber,
                    0.8 // Default score since Content doesn't have score
                );
            })
            .collect(Collectors.toList());
    }
    
    // Private helper methods
    
    /**
     * Create a ChatLanguageModel for query transformers using our existing LLMService
     */
    private dev.langchain4j.model.chat.ChatModel createChatLanguageModel() {
        return new ZestChatLanguageModel(llmService);
    }
    
    /**
     * Initialize multiple retrievers for different content sources
     */
    private List<ContentRetriever> initializeMultipleRetrievers() {
        List<ContentRetriever> retrievers = new ArrayList<>();
        
        // Add primary code retriever
        retrievers.add(primaryRetriever);
        
        // Add specialized retrievers for different file types
        retrievers.add(createJavaCodeRetriever());
        retrievers.add(createConfigFileRetriever());
        retrievers.add(createTestFileRetriever());
        
        LOG.info("Initialized " + retrievers.size() + " content retrievers for multi-source RAG");
        return retrievers;
    }
    
    /**
     * Create specialized retriever for Java code files
     */
    private ContentRetriever createJavaCodeRetriever() {
        // This would filter for Java-specific content
        return new FilteredContentRetriever(primaryRetriever, content -> {
            String source = content.textSegment().metadata().getString("file");
            return source != null && (source.endsWith(".java") || source.endsWith(".kt"));
        });
    }
    
    /**
     * Create specialized retriever for configuration files
     */
    private ContentRetriever createConfigFileRetriever() {
        return new FilteredContentRetriever(primaryRetriever, content -> {
            String source = content.textSegment().metadata().getString("file");
            return source != null && (source.contains("config") || source.endsWith(".properties") || 
                                    source.endsWith(".yml") || source.endsWith(".xml"));
        });
    }
    
    /**
     * Create specialized retriever for test files
     */
    private ContentRetriever createTestFileRetriever() {
        return new FilteredContentRetriever(primaryRetriever, content -> {
            String source = content.textSegment().metadata().getString("file");
            return source != null && (source.contains("test") || source.contains("Test"));
        });
    }
    
    /**
     * Perform hybrid search with two-stage retrieval and re-ranking
     */
    private List<EmbeddingMatch<TextSegment>> performHybridSearch(String query, int maxResults, double threshold) {
        try {
            // Stage 1: Broad retrieval - get more candidates than needed
            int candidateCount = Math.min(maxResults * 5, 100); // Get 5x more candidates for re-ranking
            List<EmbeddingMatch<TextSegment>> candidates = performInitialRetrieval(query, candidateCount, threshold);
            
            if (candidates.isEmpty()) {
                LOG.debug("No candidates found in initial retrieval");
                return candidates;
            }
            
            LOG.debug("Stage 1: Retrieved " + candidates.size() + " candidates for re-ranking");
            
            // Stage 2: Re-ranking with enhanced query-document similarity
            List<EmbeddingMatch<TextSegment>> reranked = performReRanking(query, candidates);
            
            // Return top results after re-ranking
            return reranked.stream()
                .limit(maxResults)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            LOG.error("Error in hybrid search", e);
            return List.of();
        }
    }
    
    /**
     * Stage 1: Initial broad retrieval using hybrid keyword + vector search
     */
    private List<EmbeddingMatch<TextSegment>> performInitialRetrieval(String query, int candidateCount, double threshold) {
        // Get all chunks from vector store
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(10000)  // Get all chunks, no threshold initially
            .minScore(0.0)
            .build();
        List<EmbeddingMatch<TextSegment>> allMatches = embeddingStore.search(request).matches();
        
        // Extract keywords from query
        Set<String> keywords = extractKeywords(query);
        LOG.debug("Extracted keywords from query: " + keywords);
        
        // Score chunks using hybrid approach
        List<ScoredMatch> scoredMatches = new ArrayList<>();
        
        for (EmbeddingMatch<TextSegment> match : allMatches) {
            double vectorScore = match.score();
            double keywordScore = calculateKeywordScore(match.embedded(), keywords);
            
            // Hybrid scoring: keyword match gets boost, vector similarity provides base score
            double hybridScore;
            if (keywordScore > 0) {
                // If keywords found, boost the score significantly
                hybridScore = Math.max(vectorScore, 0.8) + (keywordScore * 0.3);
            } else {
                // Pure vector similarity, but only if above threshold
                hybridScore = vectorScore >= threshold ? vectorScore : 0.0;
            }
            
            if (hybridScore >= threshold) {
                scoredMatches.add(new ScoredMatch(match, hybridScore, keywordScore > 0));
            }
        }
        
        // Return top candidates for re-ranking
        return scoredMatches.stream()
            .sorted((a, b) -> Double.compare(b.hybridScore, a.hybridScore))
            .limit(candidateCount)
            .map(sm -> new EmbeddingMatch<>(sm.match.score(), sm.match.embeddingId(), sm.match.embedding(), sm.match.embedded()))
            .collect(Collectors.toList());
    }
    
    /**
     * Stage 2: Re-ranking with enhanced query-document similarity
     */
    private List<EmbeddingMatch<TextSegment>> performReRanking(String query, List<EmbeddingMatch<TextSegment>> candidates) {
        LOG.debug("Stage 2: Re-ranking " + candidates.size() + " candidates");
        
        Set<String> queryTerms = extractKeywords(query);
        List<String> queryTokens = tokenize(query.toLowerCase());
        
        List<ReRankedMatch> rerankedMatches = new ArrayList<>();
        
        for (EmbeddingMatch<TextSegment> candidate : candidates) {
            TextSegment segment = candidate.embedded();
            
            // Calculate different types of matches with clear hierarchy
            double exactKeywordScore = calculateExactKeywordScore(queryTerms, segment);
            double phraseMatchScore = calculatePhraseMatchScore(query, segment);
            double lexicalScore = calculateLexicalSimilarity(queryTokens, tokenize(segment.text().toLowerCase()));
            double semanticScore = candidate.score(); // Original vector similarity
            double contextScore = calculateContextualScore(query, segment);
            double metadataScore = calculateMetadataRelevance(queryTerms, segment.metadata());
            double lengthScore = calculateLengthRelevance(segment.text().length());
            double positionScore = calculatePositionScore(segment);
            
            // Enhanced hierarchical scoring with significant score separation
            double finalScore;
            String scoreCategory;
            
            if (exactKeywordScore > 0.7) {
                // Tier 1: Perfect keyword matches - score range 90-100
                finalScore = 90.0 + exactKeywordScore * 8.0 + 
                           phraseMatchScore * 1.5 + contextScore * 0.5;
                scoreCategory = "EXACT_MATCH";
            } else if (exactKeywordScore > 0.4) {
                // Tier 2: Good keyword matches - score range 70-89
                finalScore = 70.0 + exactKeywordScore * 15.0 + 
                           phraseMatchScore * 3.0 + contextScore * 1.0;
                scoreCategory = "KEYWORD_MATCH";
            } else if (phraseMatchScore > 0.5) {
                // Tier 3: Strong phrase matches - score range 50-69
                finalScore = 50.0 + phraseMatchScore * 15.0 + 
                           exactKeywordScore * 2.0 + lexicalScore * 2.0;
                scoreCategory = "PHRASE_MATCH";
            } else if (lexicalScore > 0.4) {
                // Tier 4: Good lexical similarity - score range 30-49
                finalScore = 30.0 + lexicalScore * 15.0 + 
                           exactKeywordScore * 2.0 + semanticScore * 2.0;
                scoreCategory = "LEXICAL_MATCH";
            } else if (semanticScore > 0.5) {
                // Tier 5: Good semantic similarity - score range 15-29
                finalScore = 15.0 + semanticScore * 12.0 + 
                           contextScore * 2.0 + metadataScore * 1.0;
                scoreCategory = "SEMANTIC_MATCH";
            } else {
                // Tier 6: Weak matches - score range 0-14
                finalScore = semanticScore * 10.0 + 
                           contextScore * 2.0 + lexicalScore * 1.5 + 
                           metadataScore * 0.5;
                scoreCategory = "WEAK_MATCH";
            }
            
            // Add bonus points for additional factors
            finalScore += lengthScore * 2.0;      // Up to +2 points for good length
            finalScore += positionScore * 1.5;    // Up to +1.5 points for good position
            finalScore += metadataScore * 3.0;    // Up to +3 points for metadata relevance
            
            // Ensure score stays within bounds
            finalScore = Math.min(finalScore, 100.0);
            
            rerankedMatches.add(new ReRankedMatch(candidate, finalScore, semanticScore, lexicalScore, exactKeywordScore));
            
            LOG.info("Re-ranked: " + segment.metadata().getString("file") + " - " +
                     String.format("SCORE: %.1f [%s] | Exact:%.3f Phrase:%.3f Lex:%.3f Sem:%.3f Ctx:%.3f Meta:%.3f Len:%.3f Pos:%.3f", 
                                   finalScore, scoreCategory, exactKeywordScore, phraseMatchScore, 
                                   lexicalScore, semanticScore, contextScore, metadataScore, lengthScore, positionScore));
        }
        
        // Sort by final re-ranking score
        return rerankedMatches.stream()
            .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore))
            .map(rm -> new EmbeddingMatch<>(rm.finalScore, rm.match.embeddingId(), rm.match.embedding(), rm.match.embedded()))
            .collect(Collectors.toList());
    }
    
    /**
     * Extract meaningful keywords from search query
     */
    private Set<String> extractKeywords(String query) {
        Set<String> stopWords = Set.of("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "is", "are", "was", "were");
        
        return Arrays.stream(query.toLowerCase().split("\\s+"))
            .filter(word -> word.length() > 2)
            .filter(word -> !stopWords.contains(word))
            .filter(word -> word.matches("[a-zA-Z0-9]+"))  // Only alphanumeric
            .collect(Collectors.toSet());
    }
    
    /**
     * Calculate keyword matching score for a text segment
     */
    private double calculateKeywordScore(TextSegment segment, Set<String> keywords) {
        if (keywords.isEmpty()) return 0.0;
        
        String text = segment.text().toLowerCase();
        String metadata = segment.metadata().toString().toLowerCase();
        String combined = text + " " + metadata;
        
        int totalMatches = 0;
        int exactMatches = 0;
        
        for (String keyword : keywords) {
            // Count exact matches
            int exactCount = countOccurrences(combined, keyword);
            exactMatches += exactCount;
            
            // Count partial matches (contains)
            if (combined.contains(keyword)) {
                totalMatches++;
            }
        }
        
        // Score based on match ratio and frequency
        double matchRatio = (double) totalMatches / keywords.size();
        double frequency = Math.min(exactMatches, 5) / 5.0;  // Cap at 5 matches
        
        return matchRatio * 0.7 + frequency * 0.3;
    }
    
    /**
     * Count occurrences of a substring in text
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
    
    /**
     * Count how many matches came from keyword matching
     */
    private long countKeywordMatches(List<EmbeddingMatch<TextSegment>> matches, String query) {
        Set<String> keywords = extractKeywords(query);
        return matches.stream()
            .mapToLong(match -> calculateKeywordScore(match.embedded(), keywords) > 0 ? 1 : 0)
            .sum();
    }
    
    /**
     * Tokenize text for lexical similarity calculation
     */
    private List<String> tokenize(String text) {
        return Arrays.stream(text.split("[\\s\\p{Punct}]+"))
            .filter(token -> token.length() > 1)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate exact keyword matching score (highest priority)
     */
    private double calculateExactKeywordScore(Set<String> queryTerms, TextSegment segment) {
        if (queryTerms.isEmpty()) return 0.0;
        
        String text = segment.text().toLowerCase();
        String metadata = segment.metadata().toString().toLowerCase();
        String combined = text + " " + metadata;
        
        int exactMatches = 0;
        int totalKeywords = queryTerms.size();
        
        for (String keyword : queryTerms) {
            // Check for exact word boundary matches (not just substring)
            String pattern = "\\b" + keyword.toLowerCase() + "\\b";
            if (combined.matches(".*" + pattern + ".*")) {
                exactMatches++;
            }
        }
        
        // High score for exact matches, with bonus for multiple matches
        double matchRatio = (double) exactMatches / totalKeywords;
        
        if (exactMatches == totalKeywords) {
            return 1.0; // Perfect match - all keywords found exactly
        } else if (exactMatches > totalKeywords / 2) {
            return 0.7 + (matchRatio * 0.3); // Most keywords found
        } else if (exactMatches > 0) {
            return 0.4 + (matchRatio * 0.3); // Some keywords found
        }
        
        return 0.0;
    }
    
    /**
     * Calculate phrase matching score (second priority)
     */
    private double calculatePhraseMatchScore(String query, TextSegment segment) {
        String text = segment.text().toLowerCase();
        String queryLower = query.toLowerCase().trim();
        
        // Exact phrase match gets highest score
        if (text.contains(queryLower)) {
            return 1.0;
        }
        
        // Check for partial phrase matches
        String[] queryWords = queryLower.split("\\s+");
        if (queryWords.length < 2) return 0.0;
        
        double score = 0.0;
        
        // Look for consecutive word sequences
        for (int len = queryWords.length; len >= 2; len--) {
            for (int start = 0; start <= queryWords.length - len; start++) {
                String phrase = String.join(" ", Arrays.copyOfRange(queryWords, start, start + len));
                if (text.contains(phrase)) {
                    score = Math.max(score, (double) len / queryWords.length);
                }
            }
        }
        
        return score;
    }
    
    /**
     * Calculate lexical similarity using token overlap (Jaccard similarity)
     */
    private double calculateLexicalSimilarity(List<String> queryTokens, List<String> docTokens) {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0.0;
        
        Set<String> querySet = new HashSet<>(queryTokens);
        Set<String> docSet = new HashSet<>(docTokens);
        
        // Jaccard similarity: intersection / union
        Set<String> intersection = new HashSet<>(querySet);
        intersection.retainAll(docSet);
        
        Set<String> union = new HashSet<>(querySet);
        union.addAll(docSet);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Calculate contextual relevance based on query-document interaction
     */
    private double calculateContextualScore(String query, TextSegment segment) {
        String text = segment.text().toLowerCase();
        String queryLower = query.toLowerCase();
        
        double score = 0.0;
        
        // Exact phrase matching gets high score
        if (text.contains(queryLower)) {
            score += 0.5;
        }
        
        // Check for related programming concepts
        if (isCodeRelated(query) && isCodeSegment(segment)) {
            score += 0.3;
        }
        
        // Proximity scoring - keywords close together
        score += calculateProximityScore(query, text);
        
        // File type relevance
        if (isRelevantFileType(segment.metadata().getString("fileType"), query)) {
            score += 0.2;
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * Calculate metadata relevance (file path, type, etc.)
     */
    private double calculateMetadataRelevance(Set<String> queryTerms, Metadata metadata) {
        double score = 0.0;
        
        String filePath = metadata.getString("file");
        if (filePath != null) {
            String filePathLower = filePath.toLowerCase();
            for (String term : queryTerms) {
                if (filePathLower.contains(term.toLowerCase())) {
                    score += 0.3; // File path contains query term
                }
            }
        }
        
        String fileType = metadata.getString("fileType");
        if (fileType != null && queryTerms.contains(fileType.toLowerCase())) {
            score += 0.2; // File type matches query
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * Calculate length relevance (prefer medium-length chunks)
     */
    private double calculateLengthRelevance(int contentLength) {
        // Prefer chunks with reasonable content (not too short, not too long)
        if (contentLength < 50) return 0.3;        // Too short
        if (contentLength < 200) return 0.7;       // Short but okay
        if (contentLength < 1000) return 1.0;      // Good length
        if (contentLength < 3000) return 0.8;      // Long but okay  
        return 0.5;                                 // Too long
    }
    
    /**
     * Calculate position score (prefer chunks from beginning of file)
     */
    private double calculatePositionScore(TextSegment segment) {
        Integer startLine = segment.metadata().getInteger("startLine");
        if (startLine == null) return 0.5;
        
        // Prefer chunks from the beginning of files (likely class/method definitions)
        if (startLine <= 10) return 1.0;      // Very beginning
        if (startLine <= 50) return 0.8;      // Near beginning
        if (startLine <= 100) return 0.6;     // Early in file
        return 0.4;                            // Later in file
    }
    
    /**
     * Calculate proximity score for keywords in text
     */
    private double calculateProximityScore(String query, String text) {
        Set<String> queryWords = extractKeywords(query);
        if (queryWords.size() < 2) return 0.0;
        
        String[] textWords = text.split("\\s+");
        Map<String, List<Integer>> positions = new HashMap<>();
        
        // Find positions of query words in text
        for (int i = 0; i < textWords.length; i++) {
            String word = textWords[i].toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
            if (queryWords.contains(word)) {
                positions.computeIfAbsent(word, k -> new ArrayList<>()).add(i);
            }
        }
        
        if (positions.size() < 2) return 0.0;
        
        // Calculate minimum distance between any two query words
        int minDistance = Integer.MAX_VALUE;
        List<String> words = new ArrayList<>(positions.keySet());
        
        for (int i = 0; i < words.size(); i++) {
            for (int j = i + 1; j < words.size(); j++) {
                for (int pos1 : positions.get(words.get(i))) {
                    for (int pos2 : positions.get(words.get(j))) {
                        minDistance = Math.min(minDistance, Math.abs(pos1 - pos2));
                    }
                }
            }
        }
        
        // Score based on proximity (closer = better)
        if (minDistance <= 3) return 0.5;      // Very close
        if (minDistance <= 10) return 0.3;     // Close
        if (minDistance <= 20) return 0.1;     // Moderate
        return 0.0;                             // Far apart
    }
    
    /**
     * Check if query is code-related
     */
    private boolean isCodeRelated(String query) {
        Set<String> codeKeywords = Set.of("function", "method", "class", "variable", "api", "service", "controller", "model", "repository", "config", "test", "util");
        String queryLower = query.toLowerCase();
        return codeKeywords.stream().anyMatch(queryLower::contains);
    }
    
    /**
     * Check if segment contains code
     */
    private boolean isCodeSegment(TextSegment segment) {
        String text = segment.text();
        // Look for code patterns
        return text.contains("{") || text.contains("(") || text.contains("public ") || 
               text.contains("private ") || text.contains("function") || text.contains("class ");
    }
    
    /**
     * Check if file type is relevant to query
     */
    private boolean isRelevantFileType(String fileType, String query) {
        if (fileType == null) return false;
        
        String queryLower = query.toLowerCase();
        
        // Check for language-specific queries
        if (queryLower.contains("java") && "java".equals(fileType)) return true;
        if (queryLower.contains("javascript") && "js".equals(fileType)) return true;
        if (queryLower.contains("kotlin") && "kt".equals(fileType)) return true;
        if (queryLower.contains("python") && "py".equals(fileType)) return true;
        
        return false;
    }
    
    /**
     * Helper class for hybrid scoring
     */
    private static class ScoredMatch {
        final EmbeddingMatch<TextSegment> match;
        final double hybridScore;
        final boolean hasKeywordMatch;
        
        ScoredMatch(EmbeddingMatch<TextSegment> match, double hybridScore, boolean hasKeywordMatch) {
            this.match = match;
            this.hybridScore = hybridScore;
            this.hasKeywordMatch = hasKeywordMatch;
        }
    }
    
    /**
     * Helper class for re-ranking results
     */
    private static class ReRankedMatch {
        final EmbeddingMatch<TextSegment> match;
        final double finalScore;
        final double semanticScore;
        final double lexicalScore;
        final double contextScore;
        
        ReRankedMatch(EmbeddingMatch<TextSegment> match, double finalScore, double semanticScore, double lexicalScore, double contextScore) {
            this.match = match;
            this.finalScore = finalScore;
            this.semanticScore = semanticScore;
            this.lexicalScore = lexicalScore;
            this.contextScore = contextScore;
        }
    }
    
    private List<Path> findCodeFiles(Path projectPath) throws IOException {
        LOG.info("Scanning for code files in: " + projectPath);
        
        try (Stream<Path> paths = Files.walk(projectPath)) {
            List<Path> codeFiles = paths
                .filter(Files::isRegularFile)
                .filter(this::isCodeFile)
                .filter(this::notIgnored)
                .collect(Collectors.toList());
            
            LOG.info("Found " + codeFiles.size() + " code files");
            
            // Log some examples for debugging
            codeFiles.stream()
                .limit(10)
                .forEach(file -> LOG.debug("Found code file: " + file));
            
            if (codeFiles.size() > 10) {
                LOG.debug("... and " + (codeFiles.size() - 10) + " more files");
            }
            
            return codeFiles;
        }
    }
    
    private boolean isCodeFile(Path path) {
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            String extension = fileName.substring(lastDot + 1).toLowerCase();
            boolean isCode = CODE_EXTENSIONS.contains(extension);
            if (isCode) {
                LOG.debug("Matched code file: " + path + " (extension: " + extension + ")");
            }
            return isCode;
        }
        return false;
    }
    
    private boolean notIgnored(Path path) {
        String pathStr = path.toString().toLowerCase();
        boolean ignored = pathStr.contains("node_modules") ||
                         pathStr.contains(".git") ||
                         pathStr.contains("target") ||
                         pathStr.contains("build") ||
                         pathStr.contains("dist") ||
                         pathStr.contains(".idea") ||
                         pathStr.contains("out") ||
                         pathStr.contains("bin");
        
        if (ignored) {
            LOG.debug("Ignoring path: " + path);
        }
        
        return !ignored;
    }
    
    private void indexFile(Path file) throws IOException {
        LOG.debug("Indexing file: " + file);
        
        try {
            String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            String relativePath = Paths.get(project.getBasePath()).relativize(file).toString();
            
            if (content.isEmpty()) {
                LOG.debug("Skipping empty file: " + file);
                return;
            }
            
            LOG.debug("File content length: " + content.length() + " characters");
            
            // Use AST-based semantic chunking for better code understanding
            indexFileWithASTChunking(content, relativePath);
            
            LOG.debug("Successfully indexed file: " + file);
            
        } catch (Exception e) {
            LOG.error("Error indexing file: " + file, e);
            throw e;
        }
    }
    
    /**
     * Index file using AST-based semantic chunking (replaces old line-based approach)
     */
    private void indexFileWithASTChunking(String content, String relativePath) {
        try {
            // Use AST chunker to create semantic chunks
            List<ASTChunker.CodeChunk> codeChunks = astChunker.chunkCode(content, relativePath);
            
            LOG.debug("AST chunking created " + codeChunks.size() + " chunks for " + relativePath);
            
            for (ASTChunker.CodeChunk codeChunk : codeChunks) {
                try {
                    // Create enhanced chunk with metadata
                    String enhancedChunk = createEnhancedChunkContent(codeChunk);
                    
                    // Create metadata with detailed information
                    java.util.Map<String, String> metadataMap = new java.util.HashMap<>();
                    metadataMap.put("file", relativePath);
                    metadataMap.put("startLine", String.valueOf(codeChunk.getStartLine()));
                    metadataMap.put("endLine", String.valueOf(codeChunk.getEndLine()));
                    metadataMap.put("nodeType", codeChunk.getNodeType());
                    metadataMap.put("chunkSize", String.valueOf(codeChunk.getSize()));
                    metadataMap.put("project", project.getName());
                    metadataMap.put("fileType", getFileExtension(relativePath));
                    metadataMap.put("chunkingMethod", "AST");
                    Metadata metadata = Metadata.from(metadataMap);
                    
                    // Create text segment
                    TextSegment segment = TextSegment.from(enhancedChunk, metadata);
                    
                    // Generate embedding and store
                    LOG.debug("Generating embedding for AST chunk " + codeChunk.getNodeType() + 
                             " in " + relativePath + ":" + codeChunk.getStartLine() + "-" + codeChunk.getEndLine());
                             
                    Embedding embedding = embeddingModel.embed(segment).content();
                    
                    if (embedding == null) {
                        LOG.warn("Failed to generate embedding for AST chunk in " + relativePath);
                        continue;
                    }
                    
                    embeddingStore.add(embedding, segment);
                    
                    // Track chunk timestamp for memory management
                    String chunkId = getChunkId(segment);
                    chunkTimestamps.put(chunkId, System.currentTimeMillis());
                    
                    LOG.debug("✅ Added AST chunk to vector store: " + relativePath + ":" + 
                             codeChunk.getStartLine() + "-" + codeChunk.getEndLine() + 
                             " (" + codeChunk.getNodeType() + ")");
                    
                } catch (Exception e) {
                    LOG.error("Failed to generate embedding for AST chunk in " + relativePath + 
                             " lines " + codeChunk.getStartLine() + "-" + codeChunk.getEndLine(), e);
                    // Continue with next chunk instead of failing the whole file
                }
            }
            
        } catch (Exception e) {
            LOG.error("AST chunking failed for " + relativePath + ", falling back to line-based chunking", e);
            // Fallback to old method if AST chunking fails
            indexFileByLinesWithOverlap(content, relativePath);
        }
    }
    
    /**
     * Create enhanced chunk content with context information
     */
    private String createEnhancedChunkContent(ASTChunker.CodeChunk codeChunk) {
        StringBuilder enhanced = new StringBuilder();
        
        // Add chunk metadata as comment
        enhanced.append("// AST Chunk: ").append(codeChunk.getNodeType())
                .append(" in ").append(codeChunk.getFilePath())
                .append(" (lines ").append(codeChunk.getStartLine())
                .append("-").append(codeChunk.getEndLine()).append(")\n");
                
        // Add the actual content
        enhanced.append(codeChunk.getContent());
        
        return enhanced.toString();
    }
    
    /**
     * Legacy line-based chunking (kept as fallback for AST chunking failures)
     */
    private void indexFileByLinesWithOverlap(String content, String relativePath) {
        List<String> lines = content.lines().collect(Collectors.toList());
        
        LOG.warn("Using fallback line-based chunking for: " + relativePath);
        
        // Use simpler line-based chunking as fallback
        int linesPerChunk = Math.max(10, 1500 / 50); // Estimate lines per chunk to match AST chunk size
        
        for (int i = 0; i < lines.size(); i += linesPerChunk) {
            int endLine = Math.min(i + linesPerChunk, lines.size());
            
            // Skip tiny chunks at the end
            if (endLine - i < 5) break;
            
            String chunk = lines.subList(i, endLine).stream()
                .collect(Collectors.joining("\n"));
            
            // Create metadata (simplified for fallback)
            java.util.Map<String, String> metadataMap = new java.util.HashMap<>();
            metadataMap.put("file", relativePath);
            metadataMap.put("startLine", String.valueOf(i + 1));
            metadataMap.put("endLine", String.valueOf(endLine));
            metadataMap.put("project", project.getName());
            metadataMap.put("fileType", getFileExtension(relativePath));
            metadataMap.put("chunkingMethod", "line_based_fallback");
            Metadata metadata = Metadata.from(metadataMap);
            
            TextSegment segment = TextSegment.from(chunk, metadata);
            
            try {
                Embedding embedding = embeddingModel.embed(segment).content();
                if (embedding != null) {
                    embeddingStore.add(embedding, segment);
                    chunkTimestamps.put(getChunkId(segment), System.currentTimeMillis());
                }
            } catch (Exception e) {
                LOG.error("Failed to generate embedding for fallback chunk in " + relativePath, e);
            }
        }
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
            match.score() * 100.0  // Convert to 0-100 scale to match new scoring
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