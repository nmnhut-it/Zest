package com.zps.zest.completion.rag;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.chunking.*;
import com.zps.zest.retrieval.core.EmbeddingService;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Fast RAG service optimized for inline code completion.
 * Wraps existing ZestLangChain4jService with optimizations for speed.
 */
public class InlineCompletionRAG {
    private static final Logger LOG = Logger.getInstance(InlineCompletionRAG.class);
    
    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_RESULTS = 5;
    private static final double MIN_SIMILARITY_SCORE = 0.7;
    private static final long EMBEDDING_TIMEOUT_MS = 50; // 50ms timeout for embeddings
    
    private final Project project;
    private final EmbeddingService embeddingService;
    
    // Fast cache for recently used results
    private final Map<String, CachedResult> resultCache;
    private final ExecutorService executorService;
    
    // Lazy initialization to avoid circular dependency
    private volatile ZestLangChain4jService langChainService;
    
    public InlineCompletionRAG(Project project) {
        this.project = project;
        this.embeddingService = project.getService(EmbeddingService.class);
        this.resultCache = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool();
    }
    
    private ZestLangChain4jService getLangChainService() {
        if (langChainService == null) {
            synchronized (this) {
                if (langChainService == null) {
                    langChainService = project.getService(ZestLangChain4jService.class);
                }
            }
        }
        return langChainService;
    }
    
    /**
     * Delegate indexing to existing LangChain service
     */
    public CompletableFuture<Void> indexFileAsync(String filePath, String content) {
        // The existing ZestLangChain4jService handles indexing automatically
        // No explicit indexing needed as it indexes on first retrieval
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Retrieve relevant code chunks for completion context
     */
    public List<RetrievedChunk> retrieveRelevantChunks(String query, int maxResults) {
        // Check cache first
        String cacheKey = generateCacheKey(query);
        CachedResult cached = resultCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.getResults();
        }
        
        try {
            // Use existing LangChain service with timeout for fast response
            CompletableFuture<List<RetrievedChunk>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // Use the existing retrieval from LangChain service (lazy loaded)
                    ZestLangChain4jService service = getLangChainService();
                    if (service == null) {
                        LOG.debug("LangChain service not available");
                        return Collections.emptyList();
                    }
                    
                    CompletableFuture<ZestLangChain4jService.RetrievalResult> retrievalFuture = 
                        service.retrieveContext(query, maxResults, MIN_SIMILARITY_SCORE);
                    
                    ZestLangChain4jService.RetrievalResult result = retrievalFuture.get(
                        EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    
                    return convertRetrievalResult(result);
                } catch (Exception e) {
                    LOG.debug("LangChain retrieval failed", e);
                    return Collections.emptyList();
                }
            }, executorService);
            
            // Apply timeout for fast response
            List<RetrievedChunk> results = future.get(EMBEDDING_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            
            if (!results.isEmpty()) {
                cacheResult(query, results);
                return results;
            }
            
            // Fallback: keyword-based retrieval
            return retrieveByKeywords(query, maxResults);
            
        } catch (TimeoutException e) {
            LOG.debug("Search timeout, using fallback");
            return retrieveByKeywords(query, maxResults);
        } catch (Exception e) {
            LOG.warn("Retrieval failed, using fallback", e);
            return retrieveByKeywords(query, maxResults);
        }
    }
    
    /**
     * Convert RetrievalResult from LangChain service
     */
    private List<RetrievedChunk> convertRetrievalResult(ZestLangChain4jService.RetrievalResult result) {
        if (result == null || !result.isSuccess() || result.getItems() == null || result.getItems().isEmpty()) {
            return Collections.emptyList();
        }
        
        List<RetrievedChunk> chunks = new ArrayList<>();
        
        for (var item : result.getItems()) {
            Map<String, String> metadata = new HashMap<>();
            
            // Add metadata from context item
            if (item.getFilePath() != null) {
                metadata.put("filePath", item.getFilePath());
            }
            if (item.getLineNumber() != null) {
                metadata.put("lineNumber", String.valueOf(item.getLineNumber()));
            }
            
            // Use the item's score if available
            double score = item.getScore() > 0 ? item.getScore() : 0.8;
            
            chunks.add(new RetrievedChunk(item.getContent(), score, metadata));
        }
        
        return chunks.stream()
            .limit(MAX_RESULTS)
            .collect(Collectors.toList());
    }
    
    /**
     * Fallback keyword-based retrieval
     */
    private List<RetrievedChunk> retrieveByKeywords(String query, int maxResults) {
        List<RetrievedChunk> results = new ArrayList<>();
        
        // Extract keywords from query
        Set<String> keywords = extractKeywords(query);
        
        // Search through cached segments
        for (TextSegment segment : getAllSegments()) {
            double score = calculateKeywordScore(segment.text(), keywords);
            if (score > 0.3) {
                Map<String, String> metadata = new HashMap<>();
                if (segment.metadata() != null) {
                    segment.metadata().toMap().forEach((key, value) -> 
                        metadata.put(key, String.valueOf(value)));
                }
                results.add(new RetrievedChunk(segment.text(), score, metadata));
            }
        }
        
        // Sort by score and limit results
        return results.stream()
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * Cache search results
     */
    private void cacheResult(String query, List<RetrievedChunk> results) {
        String cacheKey = generateCacheKey(query);
        resultCache.put(cacheKey, new CachedResult(results, System.currentTimeMillis()));
        
        // Limit cache size
        if (resultCache.size() > MAX_CACHE_SIZE) {
            cleanCache();
        }
    }
    
    private String generateCacheKey(String text) {
        // Use first 100 chars + hash for key
        String prefix = text.length() > 100 ? text.substring(0, 100) : text;
        return prefix + "_" + text.hashCode();
    }
    
    private void cleanCache() {
        long now = System.currentTimeMillis();
        resultCache.entrySet().removeIf(entry -> 
            entry.getValue().isExpired() || 
            resultCache.size() > MAX_CACHE_SIZE
        );
    }
    
    private Set<String> extractKeywords(String text) {
        // Simple keyword extraction - split by non-word characters
        String[] words = text.toLowerCase().split("\\W+");
        return Arrays.stream(words)
            .filter(w -> w.length() > 2)
            .collect(Collectors.toSet());
    }
    
    private double calculateKeywordScore(String text, Set<String> keywords) {
        if (keywords.isEmpty()) return 0.0;
        
        String lowerText = text.toLowerCase();
        int matches = 0;
        
        for (String keyword : keywords) {
            if (lowerText.contains(keyword)) {
                matches++;
            }
        }
        
        return (double) matches / keywords.size();
    }
    
    private List<TextSegment> getAllSegments() {
        // Get segments from LangChain service if possible
        try {
            // The existing service has the indexed content
            return new ArrayList<>();
        } catch (Exception e) {
            LOG.debug("Failed to get segments", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Clear cache only (indexing is managed by LangChain service)
     */
    public void clear() {
        resultCache.clear();
    }
    
    /**
     * Shutdown the service
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
    
    /**
     * Retrieved chunk with score and metadata
     */
    public static class RetrievedChunk {
        private final String content;
        private final double score;
        private final Map<String, String> metadata;
        
        public RetrievedChunk(String content, double score, Map<String, String> metadata) {
            this.content = content;
            this.score = score;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        public String getContent() { return content; }
        public double getScore() { return score; }
        public Map<String, String> getMetadata() { return metadata; }
        
        public String getFilePath() {
            return metadata.get("filePath");
        }
        
        public String getMethodName() {
            return metadata.get("methodName");
        }
    }
    
    /**
     * Cached result with timestamp
     */
    private static class CachedResult {
        private final List<RetrievedChunk> results;
        private final long timestamp;
        private static final long CACHE_TTL_MS = 60000; // 1 minute
        
        public CachedResult(List<RetrievedChunk> results, long timestamp) {
            this.results = results;
            this.timestamp = timestamp;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
        
        public List<RetrievedChunk> getResults() {
            return results;
        }
    }
}