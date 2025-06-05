package com.zps.zest.langchain4j.agent;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive report containing all discovered code and relationships.
 */
public class CodeExplorationReport {
    private String originalQuery;
    private Date timestamp;
    private List<String> discoveredElements;
    private List<CodePiece> codePieces;
    private Map<String, List<String>> relationships;
    private String structuredContext;
    private String explorationSummary;
    private String codingContext;
    
    // Getters and setters
    public String getOriginalQuery() {
        return originalQuery;
    }
    
    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }
    
    public Date getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
    
    public List<String> getDiscoveredElements() {
        return discoveredElements;
    }
    
    public void setDiscoveredElements(List<String> discoveredElements) {
        this.discoveredElements = discoveredElements;
    }
    
    public List<CodePiece> getCodePieces() {
        return codePieces;
    }
    
    public void setCodePieces(List<CodePiece> codePieces) {
        this.codePieces = codePieces;
    }
    
    public Map<String, List<String>> getRelationships() {
        return relationships;
    }
    
    public void setRelationships(Map<String, List<String>> relationships) {
        this.relationships = relationships;
    }
    
    public String getStructuredContext() {
        return structuredContext;
    }
    
    public void setStructuredContext(String structuredContext) {
        this.structuredContext = structuredContext;
    }
    
    public String getExplorationSummary() {
        return explorationSummary;
    }
    
    public void setExplorationSummary(String explorationSummary) {
        this.explorationSummary = explorationSummary;
    }
    
    public String getCodingContext() {
        return codingContext;
    }
    
    public void setCodingContext(String codingContext) {
        this.codingContext = codingContext;
    }
    
    /**
     * Gets the total size of all code pieces.
     */
    public int getTotalCodeSize() {
        return codePieces.stream()
            .mapToInt(p -> p.getContent() != null ? p.getContent().length() : 0)
            .sum();
    }
    
    /**
     * Gets a summary of the report.
     */
    public String getSummary() {
        return String.format(
            "Code Exploration Report\n" +
            "Query: %s\n" +
            "Timestamp: %s\n" +
            "Discovered Elements: %d\n" +
            "Code Pieces: %d\n" +
            "Total Code Size: %d characters\n" +
            "Relationships: %d\n",
            originalQuery,
            timestamp,
            discoveredElements.size(),
            codePieces.size(),
            getTotalCodeSize(),
            relationships.size()
        );
    }
    
    /**
     * Represents a piece of code discovered during exploration.
     */
    public static class CodePiece {
        private String id;
        private String type; // file, class, method, interface, etc.
        private String filePath;
        private String className;
        private String content;
        private String language;
        private Map<String, Object> metadata;
        
        // Getters and setters
        public String getId() {
            return id;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getFilePath() {
            return filePath;
        }
        
        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
        
        public String getClassName() {
            return className;
        }
        
        public void setClassName(String className) {
            this.className = className;
        }
        
        public String getContent() {
            return content;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
        
        public String getLanguage() {
            return language;
        }
        
        public void setLanguage(String language) {
            this.language = language;
        }
        
        public Map<String, Object> getMetadata() {
            return metadata;
        }
        
        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }
}
