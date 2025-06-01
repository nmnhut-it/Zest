package com.zps.zest.rag;

/**
 * Represents a code signature (class, method, or field).
 */
public class CodeSignature {
    private final String id;           // Unique identifier (e.g., "com.example.MyClass#myMethod")
    private final String signature;    // Human-readable signature
    private final String metadata;     // JSON metadata
    private final String filePath;     // Source file path
    
    public CodeSignature(String id, String signature, String metadata, String filePath) {
        this.id = id;
        this.signature = signature;
        this.metadata = metadata;
        this.filePath = filePath;
    }
    
    public String getId() {
        return id;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public String getMetadata() {
        return metadata;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    @Override
    public String toString() {
        return signature;
    }
}
