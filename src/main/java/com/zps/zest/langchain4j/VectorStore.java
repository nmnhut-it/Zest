package com.zps.zest.langchain4j;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;
import java.util.Map;

/**
 * Interface for vector storage and similarity search.
 * Provides abstraction over different vector databases.
 */
public interface VectorStore {
    
    /**
     * Stores a single embedding with associated metadata.
     * 
     * @param id Unique identifier for the embedding
     * @param embedding The embedding vector
     * @param textSegment The original text segment
     * @param metadata Additional metadata
     */
    void store(String id, float[] embedding, TextSegment textSegment, Map<String, Object> metadata);
    
    /**
     * Stores multiple embeddings in batch.
     * 
     * @param embeddings List of embeddings to store
     */
    void storeBatch(List<EmbeddingEntry> embeddings);
    
    /**
     * Searches for similar embeddings using cosine similarity.
     * 
     * @param queryEmbedding The query embedding vector
     * @param limit Maximum number of results to return
     * @return List of search results sorted by relevance
     */
    List<SearchResult> search(float[] queryEmbedding, int limit);
    
    /**
     * Searches with additional metadata filtering.
     * 
     * @param queryEmbedding The query embedding vector
     * @param limit Maximum number of results
     * @param metadataFilter Metadata constraints (e.g., "type" = "code")
     * @return Filtered search results
     */
    List<SearchResult> searchWithFilter(float[] queryEmbedding, int limit, Map<String, Object> metadataFilter);
    
    /**
     * Hybrid search combining vector similarity and keyword matching.
     * 
     * @param queryEmbedding The query embedding vector
     * @param keywords Keywords to match
     * @param limit Maximum number of results
     * @param vectorWeight Weight for vector similarity (0-1)
     * @return Combined search results
     */
    List<SearchResult> hybridSearch(float[] queryEmbedding, List<String> keywords, int limit, double vectorWeight);
    
    /**
     * Retrieves an embedding by ID.
     * 
     * @param id The embedding ID
     * @return The embedding entry or null if not found
     */
    EmbeddingEntry get(String id);
    
    /**
     * Deletes an embedding by ID.
     * 
     * @param id The embedding ID to delete
     * @return true if deleted, false if not found
     */
    boolean delete(String id);
    
    /**
     * Deletes all embeddings matching the metadata filter.
     * 
     * @param metadataFilter Metadata constraints
     * @return Number of embeddings deleted
     */
    int deleteByMetadata(Map<String, Object> metadataFilter);
    
    /**
     * Gets the total number of stored embeddings.
     * 
     * @return The count of embeddings
     */
    int size();
    
    /**
     * Clears all embeddings from the store.
     */
    void clear();
    
    /**
     * Persists the vector store to disk (if applicable).
     */
    void persist();
    
    /**
     * Represents an embedding with its associated data.
     */
    class EmbeddingEntry {
        private final String id;
        private final float[] embedding;
        private final TextSegment textSegment;
        private final Map<String, Object> metadata;
        
        public EmbeddingEntry(String id, float[] embedding, TextSegment textSegment, Map<String, Object> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.textSegment = textSegment;
            this.metadata = metadata;
        }
        
        public String getId() { return id; }
        public float[] getEmbedding() { return embedding; }
        public TextSegment getTextSegment() { return textSegment; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    /**
     * Represents a search result with similarity score.
     */
    class SearchResult {
        private final String id;
        private final TextSegment textSegment;
        private final Map<String, Object> metadata;
        private final double score;
        
        public SearchResult(String id, TextSegment textSegment, Map<String, Object> metadata, double score) {
            this.id = id;
            this.textSegment = textSegment;
            this.metadata = metadata;
            this.score = score;
        }
        
        public String getId() { return id; }
        public TextSegment getTextSegment() { return textSegment; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
    }
}
