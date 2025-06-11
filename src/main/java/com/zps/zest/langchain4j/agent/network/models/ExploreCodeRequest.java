package com.zps.zest.langchain4j.agent.network.models;

import com.google.gson.JsonObject;

/**
 * Request model for explore_code endpoint.
 * Similar to FastAPI's Pydantic models for type safety and validation.
 */
public class ExploreCodeRequest {
    private String query;
    private Boolean generateReport = false;
    private Config config;
    
    public static class Config {
        private Integer maxToolCalls;
        private Boolean includeTests;
        private Boolean deepExploration;
        
        // Getters and setters
        public Integer getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(Integer maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        
        public Boolean getIncludeTests() { return includeTests; }
        public void setIncludeTests(Boolean includeTests) { this.includeTests = includeTests; }
        
        public Boolean getDeepExploration() { return deepExploration; }
        public void setDeepExploration(Boolean deepExploration) { this.deepExploration = deepExploration; }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            if (maxToolCalls != null) json.addProperty("maxToolCalls", maxToolCalls);
            if (includeTests != null) json.addProperty("includeTests", includeTests);
            if (deepExploration != null) json.addProperty("deepExploration", deepExploration);
            return json;
        }
    }
    
    // Validation method
    public void validate() {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query is required");
        }
    }
    
    // Getters and setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    
    public Boolean getGenerateReport() { return generateReport; }
    public void setGenerateReport(Boolean generateReport) { this.generateReport = generateReport; }
    
    public Config getConfig() { return config; }
    public void setConfig(Config config) { this.config = config; }
}
