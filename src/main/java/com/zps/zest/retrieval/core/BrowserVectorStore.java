package com.zps.zest.retrieval.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.browser.JCEFBrowserManager;
import com.zps.zest.browser.JavaScriptBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Browser-based vector store using JCEF and IndexedDB for local vector search.
 * Provides semantic similarity search for code chunks using cosine similarity.
 */
@Service(Service.Level.PROJECT)
public class BrowserVectorStore implements Disposable {
    private static final Logger LOG = Logger.getInstance(BrowserVectorStore.class);
    private static final Gson GSON = new Gson();
    private static final int INITIALIZATION_TIMEOUT_SECONDS = 30;
    
    private final Project project;
    private final JCEFBrowserManager browserManager;
    private final JavaScriptBridge jsBridge;
    private volatile boolean isInitialized = false;
    private CompletableFuture<Void> initializationFuture;
    
    public BrowserVectorStore(@NotNull Project project) {
        this.project = project;
        this.browserManager = project.getService(JCEFBrowserManager.class);
        this.jsBridge = new JavaScriptBridge();
        
        LOG.info("BrowserVectorStore created for project: " + project.getName());
    }
    
    /**
     * Initialize the vector store by loading the HTML page and setting up the database
     */
    public CompletableFuture<Boolean> initialize() {
        if (initializationFuture != null) {
            return initializationFuture.thenApply(v -> isInitialized);
        }
        
        initializationFuture = CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Initializing BrowserVectorStore...");
                
                // Load the vector search HTML page
                URL htmlUrl = getClass().getResource("/html/vector-search.html");
                if (htmlUrl == null) {
                    throw new RuntimeException("vector-search.html not found in resources");
                }
                
                // Load page in JCEF browser
                String htmlPath = htmlUrl.toString();
                browserManager.loadURL(htmlPath);
                
                // Wait for page to load and initialize
                Thread.sleep(2000); // Give the page time to load
                
                // Call JavaScript initialization
                CompletableFuture<String> initResult = jsBridge.callFunction("initializeVectorStore");
                String result = initResult.get(INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                boolean success = Boolean.TRUE.equals(resultMap.get("success"));
                
                if (success) {
                    isInitialized = true;
                    LOG.info("BrowserVectorStore initialized successfully");
                } else {
                    String error = (String) resultMap.get("error");
                    throw new RuntimeException("Failed to initialize vector store: " + error);
                }
                
                return null;
            } catch (Exception e) {
                LOG.error("Failed to initialize BrowserVectorStore", e);
                throw new RuntimeException("Vector store initialization failed", e);
            }
        }).thenApply(v -> isInitialized);
        
        return initializationFuture;
    }
    
    /**
     * Search for similar vectors using cosine similarity
     */
    public CompletableFuture<List<SimilarityResult>> search(@NotNull float[] embedding, int limit, double threshold) {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.failedFuture(new IllegalStateException("Vector store not initialized"));
            }
            
            Map<String, Object> params = new HashMap<>();
            params.put("embedding", embedding);
            params.put("limit", limit);
            params.put("threshold", threshold);
            
            return jsBridge.callFunction("vectorSearch", GSON.toJson(params))
                .thenApply(result -> {
                    try {
                        List<Map<String, Object>> resultList = GSON.fromJson(result, 
                            new TypeToken<List<Map<String, Object>>>(){}.getType());
                        
                        return resultList.stream()
                            .map(this::mapToSimilarityResult)
                            .filter(Objects::nonNull)
                            .toList();
                    } catch (Exception e) {
                        LOG.error("Error parsing search results", e);
                        return new ArrayList<>();
                    }
                });
        });
    }
    
    /**
     * Insert or update a vector with metadata
     */
    public CompletableFuture<Boolean> upsertVector(@NotNull String id, @NotNull float[] embedding, 
                                                  @NotNull String content, @Nullable Map<String, Object> metadata) {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.failedFuture(new IllegalStateException("Vector store not initialized"));
            }
            
            Map<String, Object> params = new HashMap<>();
            params.put("id", id);
            params.put("embedding", embedding);
            params.put("content", content);
            params.put("metadata", metadata != null ? metadata : new HashMap<>());
            
            return jsBridge.callFunction("upsertVector", GSON.toJson(params))
                .thenApply(result -> {
                    try {
                        Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                        return Boolean.TRUE.equals(resultMap.get("success"));
                    } catch (Exception e) {
                        LOG.error("Error parsing upsert result", e);
                        return false;
                    }
                });
        });
    }
    
    /**
     * Batch insert vectors for initial indexing
     */
    public CompletableFuture<IndexingResult> buildIndex(@NotNull List<CodeChunk> chunks) {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.failedFuture(new IllegalStateException("Vector store not initialized"));
            }
            
            LOG.info("Building index with " + chunks.size() + " chunks");
            
            return jsBridge.callFunction("buildIndex", GSON.toJson(chunks))
                .thenApply(result -> {
                    try {
                        Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                        boolean success = Boolean.TRUE.equals(resultMap.get("success"));
                        
                        if (success) {
                            Number processed = (Number) resultMap.get("processed");
                            Number errors = (Number) resultMap.get("errors");
                            Number total = (Number) resultMap.get("total");
                            
                            return new IndexingResult(
                                processed != null ? processed.intValue() : 0,
                                errors != null ? errors.intValue() : 0,
                                total != null ? total.intValue() : chunks.size()
                            );
                        } else {
                            String error = (String) resultMap.get("error");
                            throw new RuntimeException("Indexing failed: " + error);
                        }
                    } catch (Exception e) {
                        LOG.error("Error parsing indexing result", e);
                        return new IndexingResult(0, chunks.size(), chunks.size());
                    }
                });
        });
    }
    
    /**
     * Get database statistics
     */
    public CompletableFuture<VectorStoreStats> getStats() {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.completedFuture(new VectorStoreStats(0, false, 0));
            }
            
            return jsBridge.callFunction("getVectorStats")
                .thenApply(result -> {
                    try {
                        Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                        Number itemCount = (Number) resultMap.get("itemCount");
                        Boolean isInit = (Boolean) resultMap.get("isInitialized");
                        Number dbSize = (Number) resultMap.get("dbSize");
                        
                        return new VectorStoreStats(
                            itemCount != null ? itemCount.intValue() : 0,
                            isInit != null ? isInit : false,
                            dbSize != null ? dbSize.intValue() : 0
                        );
                    } catch (Exception e) {
                        LOG.error("Error parsing stats result", e);
                        return new VectorStoreStats(0, false, 0);
                    }
                });
        });
    }
    
    /**
     * Clear all vectors from the database
     */
    public CompletableFuture<Boolean> clearAll() {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.failedFuture(new IllegalStateException("Vector store not initialized"));
            }
            
            return jsBridge.callFunction("clearAllVectors")
                .thenApply(result -> {
                    try {
                        Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                        return Boolean.TRUE.equals(resultMap.get("success"));
                    } catch (Exception e) {
                        LOG.error("Error clearing vectors", e);
                        return false;
                    }
                });
        });
    }
    
    /**
     * Delete a specific vector by ID
     */
    public CompletableFuture<Boolean> deleteVector(@NotNull String id) {
        return ensureInitialized().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.failedFuture(new IllegalStateException("Vector store not initialized"));
            }
            
            return jsBridge.callFunction("deleteVector", id)
                .thenApply(result -> {
                    try {
                        Map<String, Object> resultMap = GSON.fromJson(result, Map.class);
                        return Boolean.TRUE.equals(resultMap.get("success"));
                    } catch (Exception e) {
                        LOG.error("Error deleting vector: " + id, e);
                        return false;
                    }
                });
        });
    }
    
    /**
     * Ensure the vector store is initialized
     */
    private CompletableFuture<Boolean> ensureInitialized() {
        if (isInitialized) {
            return CompletableFuture.completedFuture(true);
        }
        
        if (initializationFuture != null) {
            return initializationFuture;
        }
        
        return initialize();
    }
    
    /**
     * Convert JavaScript result to SimilarityResult
     */
    private SimilarityResult mapToSimilarityResult(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            Number scoreNum = (Number) map.get("score");
            String content = (String) map.get("content");
            Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
            
            if (id == null || scoreNum == null || content == null) {
                return null;
            }
            
            return new SimilarityResult(id, scoreNum.doubleValue(), content, metadata);
        } catch (Exception e) {
            LOG.warn("Error mapping similarity result", e);
            return null;
        }
    }
    
    @Override
    public void dispose() {
        LOG.info("Disposing BrowserVectorStore for project: " + project.getName());
        isInitialized = false;
        if (initializationFuture != null) {
            initializationFuture.cancel(true);
        }
    }
    
    /**
     * Result of a similarity search
     */
    public static class SimilarityResult {
        private final String id;
        private final double score;
        private final String content;
        private final Map<String, Object> metadata;
        
        public SimilarityResult(String id, double score, String content, Map<String, Object> metadata) {
            this.id = id;
            this.score = score;
            this.content = content;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        public String getId() { return id; }
        public double getScore() { return score; }
        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        
        @Override
        public String toString() {
            return String.format("SimilarityResult{id='%s', score=%.3f, content='%s'}", 
                id, score, content.substring(0, Math.min(50, content.length())));
        }
    }
    
    /**
     * Result of batch indexing operation
     */
    public static class IndexingResult {
        private final int processed;
        private final int errors;
        private final int total;
        
        public IndexingResult(int processed, int errors, int total) {
            this.processed = processed;
            this.errors = errors;
            this.total = total;
        }
        
        public int getProcessed() { return processed; }
        public int getErrors() { return errors; }
        public int getTotal() { return total; }
        public boolean isSuccessful() { return errors == 0; }
        
        @Override
        public String toString() {
            return String.format("IndexingResult{processed=%d, errors=%d, total=%d}", processed, errors, total);
        }
    }
    
    /**
     * Vector store statistics
     */
    public static class VectorStoreStats {
        private final int itemCount;
        private final boolean isInitialized;
        private final int dbSizeMB;
        
        public VectorStoreStats(int itemCount, boolean isInitialized, int dbSizeMB) {
            this.itemCount = itemCount;
            this.isInitialized = isInitialized;
            this.dbSizeMB = dbSizeMB;
        }
        
        public int getItemCount() { return itemCount; }
        public boolean isInitialized() { return isInitialized; }
        public int getDbSizeMB() { return dbSizeMB; }
        
        @Override
        public String toString() {
            return String.format("VectorStoreStats{items=%d, initialized=%s, size=%dMB}", 
                itemCount, isInitialized, dbSizeMB);
        }
    }
    
    /**
     * Code chunk for indexing
     */
    public static class CodeChunk {
        private final String id;
        private final float[] embedding;
        private final String content;
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final String type;
        
        public CodeChunk(String id, float[] embedding, String content, String filePath, 
                        int startLine, int endLine, String type) {
            this.id = id;
            this.embedding = embedding;
            this.content = content;
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.type = type;
        }
        
        // Getters
        public String getId() { return id; }
        public float[] getEmbedding() { return embedding; }
        public String getContent() { return content; }
        public String getFilePath() { return filePath; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public String getType() { return type; }
    }
}