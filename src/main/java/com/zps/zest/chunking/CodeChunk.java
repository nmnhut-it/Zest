package com.zps.zest.chunking;

import java.util.Map;
import java.util.HashMap;

/**
 * Represents a semantic chunk of code with metadata
 */
public class CodeChunk {
    private final String content;
    private final String filePath;
    private final int startLine;
    private final int endLine;
    private final ChunkType type;
    private final Map<String, Object> metadata;
    
    public enum ChunkType {
        METHOD,
        CLASS,
        FUNCTION,
        INTERFACE,
        IMPORT_BLOCK,
        FIELD_BLOCK,
        CONSTRUCTOR,
        ANONYMOUS_BLOCK,
        MODULE,
        COMPONENT,
        UNKNOWN
    }
    
    public CodeChunk(String content, String filePath, int startLine, int endLine, ChunkType type) {
        this.content = content;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.type = type;
        this.metadata = new HashMap<>();
    }
    
    public CodeChunk(String content, String filePath, int startLine, int endLine, 
                     ChunkType type, Map<String, Object> metadata) {
        this(content, filePath, startLine, endLine, type);
        this.metadata.putAll(metadata);
    }
    
    // Getters
    public String getContent() { return content; }
    public String getFilePath() { return filePath; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public ChunkType getType() { return type; }
    public Map<String, Object> getMetadata() { return metadata; }
    
    // Metadata helpers
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    public String getSignature() {
        return (String) metadata.get("signature");
    }
    
    public String getClassName() {
        return (String) metadata.get("className");
    }
    
    public String getMethodName() {
        return (String) metadata.get("methodName");
    }
    
    @Override
    public String toString() {
        return String.format("CodeChunk[%s:%d-%d, type=%s, size=%d]", 
            filePath, startLine, endLine, type, content.length());
    }
}