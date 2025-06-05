package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.langchain4j.VectorStore;
import com.zps.zest.langchain4j.EmbeddingService;
import com.zps.zest.langchain4j.InMemoryVectorStore;
import com.zps.zest.langchain4j.LocalEmbeddingService;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic index using vector embeddings for similarity search.
 * Stores rich embeddings that capture code semantics, context, and documentation.
 */
public class SemanticIndex {
    private static final Logger LOG = Logger.getInstance(SemanticIndex.class);
    
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    
    // Cache for embeddings to avoid recomputation
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    
    // Configuration
    private static final int DEFAULT_EMBEDDING_DIM = 384;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    
    public SemanticIndex() {
        this.embeddingService = new LocalEmbeddingService();
        this.vectorStore = new InMemoryVectorStore(embeddingService);
        LOG.info("Initialized SemanticIndex with embedding dimension: " + embeddingService.getDimension());
    }
    
    /**
     * Indexes a code element with semantic embedding.
     * 
     * @param id Unique identifier
     * @param content Rich content for embedding (includes code, docs, context)
     * @param metadata Additional metadata for filtering and ranking
     */
    public void indexElement(String id, String content, Map<String, Object> metadata) {
        try {
            // Generate embedding
            float[] embedding = generateEmbedding(content);
            
            // Cache the embedding
            embeddingCache.put(id, embedding);
            
            // Create text segment
            TextSegment segment = TextSegment.from(content);
            
            // Ensure metadata includes essential fields
            Map<String, Object> enrichedMetadata = new HashMap<>(metadata);
            enrichedMetadata.put("indexed_at", System.currentTimeMillis());
            enrichedMetadata.put("content_length", content.length());
            
            // Store in vector store
            vectorStore.store(id, embedding, segment, enrichedMetadata);
            
            LOG.debug("Indexed element in semantic index: " + id);
            
        } catch (Exception e) {
            LOG.error("Failed to index element: " + id, e);
        }
    }
    
    /**
     * Searches for semantically similar code elements.
     * 
     * @param query Search query
     * @param maxResults Maximum number of results
     * @param filters Optional metadata filters
     * @return List of search results ranked by similarity
     */
    public List<SearchResult> search(String query, int maxResults, Map<String, Object> filters) {
        try {
            // Generate query embedding
            float[] queryEmbedding = generateEmbedding(query);
            
            // Search with filters if provided
            List<VectorStore.SearchResult> results;
            if (filters != null && !filters.isEmpty()) {
                results = vectorStore.searchWithFilter(queryEmbedding, maxResults * 2, filters);
            } else {
                results = vectorStore.search(queryEmbedding, maxResults * 2);
            }
            
            // Convert and filter results
            List<SearchResult> semanticResults = new ArrayList<>();
            
            for (VectorStore.SearchResult result : results) {
                if (result.getScore() >= DEFAULT_SIMILARITY_THRESHOLD) {
                    semanticResults.add(new SearchResult(
                        result.getId(),
                        result.getTextSegment().text(),
                        result.getMetadata(),
                        result.getScore(),
                        calculateSemanticFeatures(query, result)
                    ));
                }
            }
            
            // Sort by score and limit
            semanticResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            
            return semanticResults.stream()
                .limit(maxResults)
                .toList();
            
        } catch (Exception e) {
            LOG.error("Search failed for query: " + query, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Performs hybrid search combining vector similarity with keyword matching.
     */
    public List<SearchResult> hybridSearch(String query, List<String> keywords, 
                                          int maxResults, double vectorWeight) {
        try {
            float[] queryEmbedding = generateEmbedding(query);
            
            List<VectorStore.SearchResult> results = vectorStore.hybridSearch(
                queryEmbedding, keywords, maxResults * 2, vectorWeight
            );
            
            List<SearchResult> semanticResults = new ArrayList<>();
            
            for (VectorStore.SearchResult result : results) {
                semanticResults.add(new SearchResult(
                    result.getId(),
                    result.getTextSegment().text(),
                    result.getMetadata(),
                    result.getScore(),
                    calculateSemanticFeatures(query, result)
                ));
            }
            
            return semanticResults.stream()
                .limit(maxResults)
                .toList();
                
        } catch (Exception e) {
            LOG.error("Hybrid search failed for query: " + query, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Finds elements similar to a given element ID.
     */
    public List<SearchResult> findSimilar(String elementId, int maxResults) {
        try {
            // Get cached embedding or retrieve from store
            float[] embedding = embeddingCache.get(elementId);
            if (embedding == null) {
                VectorStore.EmbeddingEntry entry = vectorStore.get(elementId);
                if (entry != null) {
                    embedding = entry.getEmbedding();
                    embeddingCache.put(elementId, embedding);
                } else {
                    LOG.warn("Element not found in index: " + elementId);
                    return Collections.emptyList();
                }
            }
            
            // Search for similar
            List<VectorStore.SearchResult> results = vectorStore.search(embedding, maxResults + 1);
            
            List<SearchResult> similarResults = new ArrayList<>();
            
            for (VectorStore.SearchResult result : results) {
                // Skip self
                if (!result.getId().equals(elementId)) {
                    similarResults.add(new SearchResult(
                        result.getId(),
                        result.getTextSegment().text(),
                        result.getMetadata(),
                        result.getScore(),
                        Collections.emptyMap()
                    ));
                }
            }
            
            return similarResults.stream()
                .limit(maxResults)
                .toList();
                
        } catch (Exception e) {
            LOG.error("Find similar failed for: " + elementId, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Updates an existing element's embedding.
     */
    public void updateElement(String id, String newContent, Map<String, Object> metadata) {
        // Remove from cache
        embeddingCache.remove(id);
        
        // Re-index
        indexElement(id, newContent, metadata);
    }
    
    /**
     * Removes an element from the index.
     */
    public boolean removeElement(String id) {
        embeddingCache.remove(id);
        return vectorStore.delete(id);
    }
    
    /**
     * Generates embedding for content.
     */
    private float[] generateEmbedding(String content) {
        // Check if content is too short
        if (content == null || content.trim().length() < 3) {
            // Return zero vector for very short content
            return new float[embeddingService.getDimension()];
        }
        
        return embeddingService.embed(content);
    }
    
    /**
     * Calculates additional semantic features for ranking.
     */
    private Map<String, Object> calculateSemanticFeatures(String query, VectorStore.SearchResult result) {
        Map<String, Object> features = new HashMap<>();
        
        // Calculate query-document overlap
        String content = result.getTextSegment().text().toLowerCase();
        String[] queryWords = query.toLowerCase().split("\\s+");
        
        int exactMatches = 0;
        int partialMatches = 0;
        
        for (String word : queryWords) {
            if (content.contains(" " + word + " ") || 
                content.startsWith(word + " ") || 
                content.endsWith(" " + word)) {
                exactMatches++;
            } else if (content.contains(word)) {
                partialMatches++;
            }
        }
        
        features.put("exact_matches", exactMatches);
        features.put("partial_matches", partialMatches);
        features.put("query_coverage", (double)(exactMatches + partialMatches * 0.5) / queryWords.length);
        
        // Content length feature
        features.put("content_length", content.length());
        
        // Type-specific features
        Object type = result.getMetadata().get("type");
        if (type != null) {
            features.put("is_method", "method".equals(type));
            features.put("is_class", "class".equals(type) || "interface".equals(type));
        }
        
        return features;
    }
    
    /**
     * Gets statistics about the semantic index.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_embeddings", vectorStore.size());
        stats.put("embedding_dimension", embeddingService.getDimension());
        stats.put("embedding_model", embeddingService.getModelName());
        stats.put("cached_embeddings", embeddingCache.size());
        stats.put("similarity_threshold", DEFAULT_SIMILARITY_THRESHOLD);
        return stats;
    }
    
    /**
     * Clears the entire index.
     */
    public void clear() {
        embeddingCache.clear();
        vectorStore.clear();
        LOG.info("Cleared semantic index");
    }
    
    /**
     * Search result from semantic index.
     */
    public static class SearchResult {
        private final String id;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        private final Map<String, Object> semanticFeatures;
        
        public SearchResult(String id, String content, Map<String, Object> metadata, 
                           double score, Map<String, Object> semanticFeatures) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
            this.semanticFeatures = semanticFeatures;
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
        public Map<String, Object> getSemanticFeatures() { return semanticFeatures; }
    }
}
