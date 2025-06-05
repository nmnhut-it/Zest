package com.zps.zest.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter that wraps LangChain4j's InMemoryEmbeddingStore to implement our VectorStore interface.
 * This allows us to use the official LangChain4j implementation while maintaining our abstraction.
 */
public class LangChain4jVectorStore implements VectorStore {
    private static final Logger LOG = Logger.getInstance(LangChain4jVectorStore.class);
    
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingService embeddingService;
    private final Map<String, StoredEntry> entryMap;
    
    public LangChain4jVectorStore(EmbeddingService embeddingService) {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.embeddingService = embeddingService;
        this.entryMap = new HashMap<>();
    }
    
    @Override
    public void store(String id, float[] embedding, TextSegment textSegment, Map<String, Object> metadata) {
        if (id == null || embedding == null || textSegment == null) {
            throw new IllegalArgumentException("ID, embedding, and text segment cannot be null");
        }
        
        // Store in LangChain4j store
        Embedding emb = new Embedding(embedding);
        embeddingStore.add(emb, textSegment);
        
        // Store our metadata separately
        StoredEntry entry = new StoredEntry(id, textSegment, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        entryMap.put(id, entry);
        
        LOG.debug("Stored embedding with ID: " + id);
    }
    
    @Override
    public void storeBatch(List<EmbeddingEntry> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return;
        }
        
        List<Embedding> embList = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        
        for (EmbeddingEntry entry : embeddings) {
            embList.add(new Embedding(entry.getEmbedding()));
            segments.add(entry.getTextSegment());
            
            // Store metadata
            StoredEntry stored = new StoredEntry(
                entry.getId(), 
                entry.getTextSegment(), 
                entry.getMetadata()
            );
            entryMap.put(entry.getId(), stored);
        }
        
        // Batch add to LangChain4j store
        embeddingStore.addAll(embList, segments);
        
        LOG.info("Stored batch of " + embeddings.size() + " embeddings");
    }
    
    @Override
    public List<SearchResult> search(float[] queryEmbedding, int limit) {
        if (queryEmbedding == null || limit <= 0) {
            return new ArrayList<>();
        }
        
        // Use LangChain4j's search
        Embedding queryEmb = new Embedding(queryEmbedding);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmb)
            .maxResults(limit)
            .build();
            
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
        
        // Convert results
        return searchResult.matches().stream()
            .map(this::convertMatch)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> searchWithFilter(float[] queryEmbedding, int limit, Map<String, Object> metadataFilter) {
        // First get all results
        List<SearchResult> allResults = search(queryEmbedding, limit * 3); // Get more to filter
        
        // Then filter by metadata
        return allResults.stream()
            .filter(result -> matchesMetadata(result.getMetadata(), metadataFilter))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> hybridSearch(float[] queryEmbedding, List<String> keywords, int limit, double vectorWeight) {
        if (queryEmbedding == null || limit <= 0) {
            return new ArrayList<>();
        }
        
        // Get vector search results
        List<SearchResult> vectorResults = search(queryEmbedding, limit * 2);
        
        // If no keywords, return vector results
        if (keywords == null || keywords.isEmpty()) {
            return vectorResults.stream().limit(limit).collect(Collectors.toList());
        }
        
        // Score based on keyword matching
        final List<String> lowerKeywords = keywords.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toList());
            
        final double keywordWeight = 1 - vectorWeight;
        
        // Rescore results
        return vectorResults.stream()
            .map(result -> {
                String lowerText = result.getTextSegment().text().toLowerCase();
                long matchCount = lowerKeywords.stream()
                    .filter(lowerText::contains)
                    .count();
                double keywordScore = (double) matchCount / lowerKeywords.size();
                
                // Combine scores
                double combinedScore = (result.getScore() * vectorWeight) + (keywordScore * keywordWeight);
                
                return new SearchResult(
                    findIdForSegment(result.getTextSegment()),
                    result.getTextSegment(),
                    result.getMetadata(),
                    combinedScore
                );
            })
            .filter(result -> result.getScore() > 0)
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public EmbeddingEntry get(String id) {
        StoredEntry stored = entryMap.get(id);
        if (stored == null) return null;
        
        // We don't store the embedding vector in our map, so we can't return it
        // This is a limitation of using the LangChain4j store
        return new EmbeddingEntry(id, null, stored.textSegment, stored.metadata);
    }
    
    @Override
    public boolean delete(String id) {
        // LangChain4j's InMemoryEmbeddingStore doesn't support deletion by ID
        // We can only remove from our metadata map
        return entryMap.remove(id) != null;
    }
    
    @Override
    public int deleteByMetadata(Map<String, Object> metadataFilter) {
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            return 0;
        }
        
        List<String> toDelete = entryMap.entrySet().stream()
            .filter(entry -> matchesMetadata(entry.getValue().metadata, metadataFilter))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        toDelete.forEach(entryMap::remove);
        
        LOG.info("Deleted " + toDelete.size() + " entries from metadata (embeddings remain in store)");
        return toDelete.size();
    }
    
    @Override
    public int size() {
        return entryMap.size();
    }
    
    @Override
    public void clear() {
        // LangChain4j's InMemoryEmbeddingStore doesn't have a clear method
        // We need to create a new instance (this is a limitation)
        entryMap.clear();
        LOG.info("Cleared metadata map (embeddings remain in store - create new instance to fully clear)");
    }
    
    @Override
    public void persist() {
        // In-memory store doesn't persist
        LOG.debug("Persist called on in-memory store (no-op)");
    }
    
    private SearchResult convertMatch(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        String id = findIdForSegment(segment);
        
        Map<String, Object> metadata = new HashMap<>();
        if (id != null) {
            StoredEntry stored = entryMap.get(id);
            if (stored != null) {
                metadata = stored.metadata;
            }
        }
        
        return new SearchResult(id, segment, metadata, match.score());
    }
    
    private String findIdForSegment(TextSegment segment) {
        // Find ID by matching text segment
        for (Map.Entry<String, StoredEntry> entry : entryMap.entrySet()) {
            if (entry.getValue().textSegment.equals(segment)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
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
    
    /**
     * Internal class to store metadata alongside segments.
     */
    private static class StoredEntry {
        final String id;
        final TextSegment textSegment;
        final Map<String, Object> metadata;
        
        StoredEntry(String id, TextSegment textSegment, Map<String, Object> metadata) {
            this.id = id;
            this.textSegment = textSegment;
            this.metadata = metadata;
        }
    }
}
