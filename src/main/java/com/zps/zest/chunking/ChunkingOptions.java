package com.zps.zest.chunking;

/**
 * Configuration options for code chunking
 */
public class ChunkingOptions {
    private final int maxChunkSize;
    private final int minChunkSize;
    private final boolean preserveSemanticBoundaries;
    private final boolean includeImports;
    private final boolean extractMetadata;
    
    private ChunkingOptions(Builder builder) {
        this.maxChunkSize = builder.maxChunkSize;
        this.minChunkSize = builder.minChunkSize;
        this.preserveSemanticBoundaries = builder.preserveSemanticBoundaries;
        this.includeImports = builder.includeImports;
        this.extractMetadata = builder.extractMetadata;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static ChunkingOptions defaultOptions() {
        return builder().build();
    }
    
    public static ChunkingOptions forInlineCompletion() {
        return builder()
            .maxChunkSize(500)  // Smaller chunks for faster retrieval
            .minChunkSize(50)
            .preserveSemanticBoundaries(true)
            .extractMetadata(true)
            .build();
    }
    
    public static ChunkingOptions forIndexing() {
        return builder()
            .maxChunkSize(2000)  // Larger chunks for comprehensive indexing
            .minChunkSize(100)
            .preserveSemanticBoundaries(true)
            .includeImports(true)
            .extractMetadata(true)
            .build();
    }
    
    // Getters
    public int getMaxChunkSize() { return maxChunkSize; }
    public int getMinChunkSize() { return minChunkSize; }
    public boolean isPreserveSemanticBoundaries() { return preserveSemanticBoundaries; }
    public boolean isIncludeImports() { return includeImports; }
    public boolean isExtractMetadata() { return extractMetadata; }
    
    public static class Builder {
        private int maxChunkSize = 1000;
        private int minChunkSize = 100;
        private boolean preserveSemanticBoundaries = true;
        private boolean includeImports = false;
        private boolean extractMetadata = true;
        
        public Builder maxChunkSize(int size) {
            this.maxChunkSize = size;
            return this;
        }
        
        public Builder minChunkSize(int size) {
            this.minChunkSize = size;
            return this;
        }
        
        public Builder preserveSemanticBoundaries(boolean preserve) {
            this.preserveSemanticBoundaries = preserve;
            return this;
        }
        
        public Builder includeImports(boolean include) {
            this.includeImports = include;
            return this;
        }
        
        public Builder extractMetadata(boolean extract) {
            this.extractMetadata = extract;
            return this;
        }
        
        public ChunkingOptions build() {
            return new ChunkingOptions(this);
        }
    }
}