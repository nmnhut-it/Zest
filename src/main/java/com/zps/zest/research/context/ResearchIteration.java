package com.zps.zest.research.context;

import com.zps.zest.research.analyzer.AnalysisResult;
import com.zps.zest.research.search.SearchResults;

import java.util.List;

/**
 * Represents a single iteration in the research process.
 */
public class ResearchIteration {
    private final int iterationNumber;
    private final List<String> keywords;
    private final SearchResults searchResults;
    private final AnalysisResult analysisResult;
    
    public ResearchIteration(int iterationNumber, List<String> keywords,
                           SearchResults searchResults, AnalysisResult analysisResult) {
        this.iterationNumber = iterationNumber;
        this.keywords = keywords;
        this.searchResults = searchResults;
        this.analysisResult = analysisResult;
    }
    
    public int getIterationNumber() {
        return iterationNumber;
    }
    
    public List<String> getKeywords() {
        return keywords;
    }
    
    public SearchResults getSearchResults() {
        return searchResults;
    }
    
    public AnalysisResult getAnalysisResult() {
        return analysisResult;
    }
    
    @Override
    public String toString() {
        return "ResearchIteration{" +
               "iterationNumber=" + iterationNumber +
               ", keywords=" + keywords +
               ", resultsFound=" + searchResults.getTotalResults() +
               ", searchComplete=" + analysisResult.isSearchComplete() +
               '}';
    }
}