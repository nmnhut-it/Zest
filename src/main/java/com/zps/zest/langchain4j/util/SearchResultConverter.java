package com.zps.zest.langchain4j.util;

import com.zps.zest.langchain4j.RagService;
import com.zps.zest.langchain4j.VectorStore;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import dev.langchain4j.data.segment.TextSegment;

import java.util.Map;

/**
 * Utility class for converting between different search result types.
 * Provides a consistent way to handle search results across different indices.
 */
public final class SearchResultConverter {
    
    private SearchResultConverter() {
        // Utility class, prevent instantiation
    }
    
    /**
     * Converts a VectorStore search result to a RagService search result.
     */
    public static RagService.SearchResult fromVectorStoreResult(VectorStore.SearchResult result) {
        return new RagService.SearchResult(
            result.getId(),
            result.getTextSegment().text(),
            result.getMetadata(),
            result.getScore()
        );
    }
    
    /**
     * Creates a generic search result from common fields.
     */
    public static GenericSearchResult createGenericResult(String id, String content, 
                                                         double score, Map<String, Object> metadata) {
        return new GenericSearchResult(id, content, score, metadata);
    }
    
    /**
     * Converts a NameIndex result to a generic result.
     */
    public static GenericSearchResult fromNameIndexResult(NameIndex.SearchResult result) {
        Map<String, Object> metadata = Map.of(
            "type", result.getType(),
            "file_path", result.getFilePath(),
            "source", "name_index"
        );
        
        return new GenericSearchResult(
            result.getId(),
            result.getSignature(),
            result.getScore(),
            metadata
        );
    }
    
    /**
     * Converts a SemanticIndex result to a generic result.
     */
    public static GenericSearchResult fromSemanticIndexResult(SemanticIndex.SearchResult result) {
        Map<String, Object> metadata = result.getMetadata();
        metadata.put("source", "semantic_index");
        
        return new GenericSearchResult(
            result.getId(),
            result.getContent(),
            result.getScore(),
            metadata
        );
    }
    
    /**
     * Generic search result that can be used across different search types.
     */
    public static class GenericSearchResult {
        private final String id;
        private final String content;
        private final double score;
        private final Map<String, Object> metadata;
        
        public GenericSearchResult(String id, String content, double score, Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.metadata = metadata;
        }
        
        public String getId() { return id; }
        public String getContent() { return content; }
        public double getScore() { return score; }
        public Map<String, Object> getMetadata() { return metadata; }
        
        /**
         * Gets a metadata value safely.
         */
        @SuppressWarnings("unchecked")
        public <T> T getMetadataValue(String key, Class<T> type, T defaultValue) {
            Object value = metadata.get(key);
            if (value != null && type.isInstance(value)) {
                return (T) value;
            }
            return defaultValue;
        }
        
        public String getFilePath() {
            return getMetadataValue("file_path", String.class, "");
        }
        
        public String getType() {
            return getMetadataValue("type", String.class, "unknown");
        }
    }
}
