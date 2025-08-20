package com.zps.zest.langchain4j;

import java.util.List;

/**
 * Interface for tracking progress during RAG retrieval operations.
 * Allows services to report progress to UI components.
 */
public interface RetrievalProgressListener {
    
    /**
     * Report a stage update during retrieval
     * @param stage Stage identifier (COMPRESS, SEARCH, BOOST, COMPLETE)
     * @param message Human-readable message describing current operation
     */
    void onStageUpdate(String stage, String message);
    
    /**
     * Report keywords extracted from the query
     * @param keywords List of keywords extracted by LLM or fallback method
     */
    void onKeywordsExtracted(List<String> keywords);
    
    /**
     * Report search completion with candidate count
     * @param candidatesFound Number of candidate matches found
     * @param filteredCount Number of matches after filtering
     */
    void onSearchComplete(int candidatesFound, int filteredCount);
    
    /**
     * Report final completion with result count
     * @param resultsCount Final number of results returned
     * @param totalTimeMs Total time taken for retrieval
     */
    void onComplete(int resultsCount, long totalTimeMs);
}