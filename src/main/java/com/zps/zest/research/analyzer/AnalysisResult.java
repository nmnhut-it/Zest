package com.zps.zest.research.analyzer;

import java.util.List;

/**
 * Represents the result of LLM analysis on search results.
 */
public class AnalysisResult {
    private final String summary;
    private final List<String> nextKeywords;
    private final boolean searchComplete;
    private final String rawResponse;
    
    public AnalysisResult(String summary, List<String> nextKeywords, 
                         boolean searchComplete, String rawResponse) {
        this.summary = summary;
        this.nextKeywords = nextKeywords;
        this.searchComplete = searchComplete;
        this.rawResponse = rawResponse;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public List<String> getNextKeywords() {
        return nextKeywords;
    }
    
    public boolean isSearchComplete() {
        return searchComplete;
    }
    
    public String getRawResponse() {
        return rawResponse;
    }
    
    @Override
    public String toString() {
        return "AnalysisResult{" +
               "summary='" + summary + '\'' +
               ", nextKeywords=" + nextKeywords +
               ", searchComplete=" + searchComplete +
               '}';
    }
}