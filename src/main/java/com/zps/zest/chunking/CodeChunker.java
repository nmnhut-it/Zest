package com.zps.zest.chunking;

import java.util.List;

/**
 * Interface for code chunking strategies.
 * Provides a reusable abstraction for different chunking methods.
 */
public interface CodeChunker {
    
    /**
     * Chunk code content into semantic units
     * @param content The code content to chunk
     * @param filePath The file path for context
     * @param options Chunking options
     * @return List of code chunks
     */
    List<CodeChunk> chunk(String content, String filePath, ChunkingOptions options);
    
    /**
     * Get optimal chunk size for this chunker
     * @return Maximum chunk size in characters
     */
    int getOptimalChunkSize();
    
    /**
     * Check if this chunker supports the given file type
     * @param filePath File path to check
     * @return true if supported
     */
    boolean supports(String filePath);
}