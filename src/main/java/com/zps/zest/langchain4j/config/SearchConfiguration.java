package com.zps.zest.langchain4j.config;

/**
 * Central configuration for search behavior across all search services.
 * This consolidates search-related configuration to avoid duplication.
 */
public class SearchConfiguration {
    
    // Hybrid search configuration
    private boolean useHybridSearch = true;
    private double hybridSearchVectorWeight = 0.7;
    
    // Index weights for CodeSearchUtility
    private double nameWeight = 2.0;
    private double semanticWeight = 1.5;
    private double structuralWeight = 1.0;
    
    // Related code configuration
    private int maxRelatedCodePerResult = 5;
    private double relevanceThreshold = 0.3;
    
    // Default search limits
    private int defaultSearchLimit = 10;
    private int maxSearchLimit = 100;
    
    // Constructor with defaults
    public SearchConfiguration() {
        // Default values already set
    }
    
    // Copy constructor
    public SearchConfiguration(SearchConfiguration other) {
        this.useHybridSearch = other.useHybridSearch;
        this.hybridSearchVectorWeight = other.hybridSearchVectorWeight;
        this.nameWeight = other.nameWeight;
        this.semanticWeight = other.semanticWeight;
        this.structuralWeight = other.structuralWeight;
        this.maxRelatedCodePerResult = other.maxRelatedCodePerResult;
        this.relevanceThreshold = other.relevanceThreshold;
        this.defaultSearchLimit = other.defaultSearchLimit;
        this.maxSearchLimit = other.maxSearchLimit;
    }
    
    // Validation methods
    public void validateAndNormalize() {
        // Ensure weights are within valid ranges
        hybridSearchVectorWeight = clamp(hybridSearchVectorWeight, 0.0, 1.0);
        nameWeight = Math.max(0.0, nameWeight);
        semanticWeight = Math.max(0.0, semanticWeight);
        structuralWeight = Math.max(0.0, structuralWeight);
        relevanceThreshold = clamp(relevanceThreshold, 0.0, 1.0);
        
        // Ensure limits are reasonable
        maxRelatedCodePerResult = clamp(maxRelatedCodePerResult, 1, 20);
        defaultSearchLimit = clamp(defaultSearchLimit, 1, maxSearchLimit);
        maxSearchLimit = Math.max(defaultSearchLimit, maxSearchLimit);
    }
    
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    
    // Getters and setters with validation
    public boolean isUseHybridSearch() {
        return useHybridSearch;
    }
    
    public void setUseHybridSearch(boolean useHybridSearch) {
        this.useHybridSearch = useHybridSearch;
    }
    
    public double getHybridSearchVectorWeight() {
        return hybridSearchVectorWeight;
    }
    
    public void setHybridSearchVectorWeight(double hybridSearchVectorWeight) {
        this.hybridSearchVectorWeight = clamp(hybridSearchVectorWeight, 0.0, 1.0);
    }
    
    public double getNameWeight() {
        return nameWeight;
    }
    
    public void setNameWeight(double nameWeight) {
        this.nameWeight = Math.max(0.0, nameWeight);
    }
    
    public double getSemanticWeight() {
        return semanticWeight;
    }
    
    public void setSemanticWeight(double semanticWeight) {
        this.semanticWeight = Math.max(0.0, semanticWeight);
    }
    
    public double getStructuralWeight() {
        return structuralWeight;
    }
    
    public void setStructuralWeight(double structuralWeight) {
        this.structuralWeight = Math.max(0.0, structuralWeight);
    }
    
    public int getMaxRelatedCodePerResult() {
        return maxRelatedCodePerResult;
    }
    
    public void setMaxRelatedCodePerResult(int maxRelatedCodePerResult) {
        this.maxRelatedCodePerResult = clamp(maxRelatedCodePerResult, 1, 20);
    }
    
    public double getRelevanceThreshold() {
        return relevanceThreshold;
    }
    
    public void setRelevanceThreshold(double relevanceThreshold) {
        this.relevanceThreshold = clamp(relevanceThreshold, 0.0, 1.0);
    }
    
    public int getDefaultSearchLimit() {
        return defaultSearchLimit;
    }
    
    public void setDefaultSearchLimit(int defaultSearchLimit) {
        this.defaultSearchLimit = clamp(defaultSearchLimit, 1, maxSearchLimit);
    }
    
    public int getMaxSearchLimit() {
        return maxSearchLimit;
    }
    
    public void setMaxSearchLimit(int maxSearchLimit) {
        this.maxSearchLimit = Math.max(defaultSearchLimit, maxSearchLimit);
    }
    
    @Override
    public String toString() {
        return "SearchConfiguration{" +
               "useHybridSearch=" + useHybridSearch +
               ", hybridSearchVectorWeight=" + hybridSearchVectorWeight +
               ", nameWeight=" + nameWeight +
               ", semanticWeight=" + semanticWeight +
               ", structuralWeight=" + structuralWeight +
               ", maxRelatedCodePerResult=" + maxRelatedCodePerResult +
               ", relevanceThreshold=" + relevanceThreshold +
               ", defaultSearchLimit=" + defaultSearchLimit +
               ", maxSearchLimit=" + maxSearchLimit +
               '}';
    }
}
