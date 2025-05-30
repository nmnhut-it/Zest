package com.zps.zest.research.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of context-focused LLM analysis on search results.
 */
public class AnalysisResult {
    private final String summary;
    private final List<String> nextKeywords;
    private final boolean searchComplete;
    private final String rawResponse;

    // New fields for enhanced context analysis
    private String insights;
    private List<String> relationships;
    private List<String> missingContext;
    private String confidenceLevel;

    public AnalysisResult(String summary, List<String> nextKeywords,
                          boolean searchComplete, String rawResponse) {
        this.summary = summary;
        this.nextKeywords = nextKeywords;
        this.searchComplete = searchComplete;
        this.rawResponse = rawResponse;

        // Initialize new fields
        this.insights = "";
        this.relationships = new ArrayList<>();
        this.missingContext = new ArrayList<>();
        this.confidenceLevel = "LOW";
    }

    // Original getters
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

    // New getters and setters
    public String getInsights() {
        return insights;
    }

    public void setInsights(String insights) {
        this.insights = insights;
    }

    public List<String> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<String> relationships) {
        this.relationships = relationships;
    }

    public List<String> getMissingContext() {
        return missingContext;
    }

    public void setMissingContext(List<String> missingContext) {
        this.missingContext = missingContext;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(String confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "summary='" + summary + '\'' +
                ", nextKeywords=" + nextKeywords +
                ", searchComplete=" + searchComplete +
                ", confidenceLevel=" + confidenceLevel +
                ", relationships=" + relationships.size() + " items" +
                ", missingContext=" + missingContext.size() + " items" +
                '}';
    }
}