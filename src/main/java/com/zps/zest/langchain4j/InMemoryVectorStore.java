package com.zps.zest.langchain4j;

import dev.langchain4j.data.segment.TextSegment;
import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of VectorStore.
 * 
 * NOTE: This is a reference implementation. The actual system uses LangChain4j's
 * built-in InMemoryEmbeddingStore through the LangChain4jVectorStore adapter.
 * 
 * This implementation is kept as a reference for:
 * - Understanding how vector stores work internally
 * - Creating custom vector store implementations
 * - Testing and debugging
 * 
 * For production use, consider using LangChain4j's official stores:
 * - InMemoryEmbeddingStore (for development/testing)
 * - ChromaEmbeddingStore (for persistent storage)
 * - PineconeEmbeddingStore (for cloud-based storage)
 * - WeaviateEmbeddingStore (for production-ready storage)
 */
public class InMemoryVectorStore implements VectorStore {
    private static final Logger LOG = Logger.getInstance(InMemoryVectorStore.class);
    
    private final Map<String, EmbeddingEntry> store;
    private final EmbeddingService embeddingService;
    
    public InMemoryVectorStore(EmbeddingService embeddingService) {
        this.store = new ConcurrentHashMap<>();
        this.embeddingService = embeddingService;
    }
    
    @Override
    public void store(String id, float[] embedding, TextSegment textSegment, Map<String, Object> metadata) {
        if (id == null || embedding == null || textSegment == null) {
            throw new IllegalArgumentException("ID, embedding, and text segment cannot be null");
        }
        
        EmbeddingEntry entry = new EmbeddingEntry(id, embedding, textSegment, 
            metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        store.put(id, entry);
        
        LOG.debug("Stored embedding with ID: " + id);
    }
    
    @Override
    public void storeBatch(List<EmbeddingEntry> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return;
        }
        
        for (EmbeddingEntry entry : embeddings) {
            store.put(entry.getId(), entry);
        }
        
        LOG.info("Stored batch of " + embeddings.size() + " embeddings");
    }
    
    @Override
    public List<SearchResult> search(float[] queryEmbedding, int limit) {
        if (queryEmbedding == null || limit <= 0) {
            return new ArrayList<>();
        }
        
        return store.values().parallelStream()
            .map(entry -> {
                double score = embeddingService.cosineSimilarity(queryEmbedding, entry.getEmbedding());
                return new SearchResult(entry.getId(), entry.getTextSegment(), entry.getMetadata(), score);
            })
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> searchWithFilter(float[] queryEmbedding, int limit, Map<String, Object> metadataFilter) {
        if (queryEmbedding == null || limit <= 0) {
            return new ArrayList<>();
        }
        
        return store.values().parallelStream()
            .filter(entry -> matchesMetadata(entry.getMetadata(), metadataFilter))
            .map(entry -> {
                double score = embeddingService.cosineSimilarity(queryEmbedding, entry.getEmbedding());
                return new SearchResult(entry.getId(), entry.getTextSegment(), entry.getMetadata(), score);
            })
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> hybridSearch(float[] queryEmbedding, List<String> keywords, int limit, double vectorWeight) {
        if (queryEmbedding == null || limit <= 0) {
            return new ArrayList<>();
        }
        
        // Ensure vectorWeight is between 0 and 1
        vectorWeight = Math.max(0, Math.min(1, vectorWeight));
        final double keywordWeight = 1 - vectorWeight;
        
        // Convert keywords to lowercase for case-insensitive matching
        final List<String> lowerKeywords = keywords != null ? 
            keywords.stream().map(String::toLowerCase).collect(Collectors.toList()) : 
            new ArrayList<>();
        
        return store.values().parallelStream()
            .map(entry -> {
                // Calculate vector similarity score
                double vectorScore = embeddingService.cosineSimilarity(queryEmbedding, entry.getEmbedding());
                
                // Calculate keyword matching score
                double keywordScore = 0.0;
                if (!lowerKeywords.isEmpty()) {
                    String lowerText = entry.getTextSegment().text().toLowerCase();
                    long matchCount = lowerKeywords.stream()
                        .filter(lowerText::contains)
                        .count();
                    keywordScore = (double) matchCount / lowerKeywords.size();
                }
                
                // Combine scores
                double combinedScore = (vectorScore * vectorWeight) + (keywordScore * keywordWeight);
                
                return new SearchResult(entry.getId(), entry.getTextSegment(), entry.getMetadata(), combinedScore);
            })
            .filter(result -> result.getScore() > 0) // Filter out zero scores
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public EmbeddingEntry get(String id) {
        return store.get(id);
    }
    
    @Override
    public boolean delete(String id) {
        return store.remove(id) != null;
    }
    
    @Override
    public int deleteByMetadata(Map<String, Object> metadataFilter) {
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            return 0;
        }
        
        List<String> toDelete = store.entrySet().stream()
            .filter(entry -> matchesMetadata(entry.getValue().getMetadata(), metadataFilter))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        toDelete.forEach(store::remove);
        
        LOG.info("Deleted " + toDelete.size() + " embeddings by metadata filter");
        return toDelete.size();
    }
    
    @Override
    public int size() {
        return store.size();
    }
    
    @Override
    public void clear() {
        store.clear();
        LOG.info("Cleared all embeddings from store");
    }
    
    @Override
    public void persist() {
        // In-memory store doesn't persist by default
        // Could be extended to save to disk if needed
        LOG.debug("Persist called on in-memory store (no-op)");
    }
    
    /**
     * Checks if the entry metadata matches all filter criteria.
     */
    private boolean matchesMetadata(Map<String, Object> entryMetadata, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        
        for (Map.Entry<String, Object> filterEntry : filter.entrySet()) {
            Object entryValue = entryMetadata.get(filterEntry.getKey());
            Object filterValue = filterEntry.getValue();
            
            if (filterValue == null) {
                if (entryValue != null) return false;
            } else {
                if (!filterValue.equals(entryValue)) return false;
            }
        }
        
        return true;
    }
}
