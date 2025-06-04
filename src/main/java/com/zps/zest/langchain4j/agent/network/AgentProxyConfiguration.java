package com.zps.zest.langchain4j.agent.network;

import com.google.gson.JsonObject;

/**
 * Configuration for the Agent Proxy Server with limits optimized for augmentation use case.
 */
public class AgentProxyConfiguration {
    
    // Tool call limits - reduced for faster augmentation
    private int maxToolCalls = 10;  // Reduced from 20
    private int maxRounds = 3;      // Reduced from 5
    private int toolsPerRound = 3;  // Reduced from 5
    
    // Search limits
    private int maxSearchResults = 10;  // Limit search results
    private int maxFileReads = 5;       // Limit file reads
    
    // Timeout settings
    private int timeoutSeconds = 30000;    // Overall timeout
    
    // Content limits
    private int maxContentLength = 1000;  // Max length per tool result
    private boolean includeTests = false; // Skip tests for faster results
    
    // Exploration depth
    private boolean deepExploration = false;  // Shallow exploration by default
    private boolean includeRelationships = true;
    
    public AgentProxyConfiguration() {
    }
    
    /**
     * Creates a default configuration optimized for query augmentation.
     */
    public static AgentProxyConfiguration getDefault() {
        return new AgentProxyConfiguration();
    }
    
    /**
     * Creates a configuration for deep exploration (slower but more thorough).
     */
    public static AgentProxyConfiguration getDeepExploration() {
        AgentProxyConfiguration config = new AgentProxyConfiguration();
        config.maxToolCalls = 20;
        config.maxRounds = 5;
        config.toolsPerRound = 5;
        config.maxSearchResults = 20;
        config.maxFileReads = 10;
        config.timeoutSeconds = 60;
        config.includeTests = true;
        config.deepExploration = true;
        return config;
    }
    
    /**
     * Creates a configuration for quick augmentation (faster but less thorough).
     */
    public static AgentProxyConfiguration getQuickAugmentation() {
        AgentProxyConfiguration config = new AgentProxyConfiguration();
        config.maxToolCalls = 5;
        config.maxRounds = 2;
        config.toolsPerRound = 2;
        config.maxSearchResults = 5;
        config.maxFileReads = 3;
        config.timeoutSeconds = 15;
        config.includeTests = false;
        config.deepExploration = false;
        config.includeRelationships = false;
        return config;
    }
    
    /**
     * Converts configuration to JSON.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("maxToolCalls", maxToolCalls);
        json.addProperty("maxRounds", maxRounds);
        json.addProperty("toolsPerRound", toolsPerRound);
        json.addProperty("maxSearchResults", maxSearchResults);
        json.addProperty("maxFileReads", maxFileReads);
        json.addProperty("timeoutSeconds", timeoutSeconds);
        json.addProperty("maxContentLength", maxContentLength);
        json.addProperty("includeTests", includeTests);
        json.addProperty("deepExploration", deepExploration);
        json.addProperty("includeRelationships", includeRelationships);
        return json;
    }
    
    /**
     * Updates configuration from JSON.
     */
    public void updateFromJson(JsonObject json) {
        if (json.has("maxToolCalls")) {
            maxToolCalls = json.get("maxToolCalls").getAsInt();
        }
        if (json.has("maxRounds")) {
            maxRounds = json.get("maxRounds").getAsInt();
        }
        if (json.has("toolsPerRound")) {
            toolsPerRound = json.get("toolsPerRound").getAsInt();
        }
        if (json.has("maxSearchResults")) {
            maxSearchResults = json.get("maxSearchResults").getAsInt();
        }
        if (json.has("maxFileReads")) {
            maxFileReads = json.get("maxFileReads").getAsInt();
        }
        if (json.has("timeoutSeconds")) {
            timeoutSeconds = json.get("timeoutSeconds").getAsInt();
        }
        if (json.has("maxContentLength")) {
            maxContentLength = json.get("maxContentLength").getAsInt();
        }
        if (json.has("includeTests")) {
            includeTests = json.get("includeTests").getAsBoolean();
        }
        if (json.has("deepExploration")) {
            deepExploration = json.get("deepExploration").getAsBoolean();
        }
        if (json.has("includeRelationships")) {
            includeRelationships = json.get("includeRelationships").getAsBoolean();
        }
    }
    
    /**
     * Creates a copy of this configuration.
     */
    public AgentProxyConfiguration copy() {
        AgentProxyConfiguration copy = new AgentProxyConfiguration();
        copy.maxToolCalls = this.maxToolCalls;
        copy.maxRounds = this.maxRounds;
        copy.toolsPerRound = this.toolsPerRound;
        copy.maxSearchResults = this.maxSearchResults;
        copy.maxFileReads = this.maxFileReads;
        copy.timeoutSeconds = this.timeoutSeconds;
        copy.maxContentLength = this.maxContentLength;
        copy.includeTests = this.includeTests;
        copy.deepExploration = this.deepExploration;
        copy.includeRelationships = this.includeRelationships;
        return copy;
    }
    
    // Getters and setters
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    
    public int getToolsPerRound() { return toolsPerRound; }
    public void setToolsPerRound(int toolsPerRound) { this.toolsPerRound = toolsPerRound; }
    
    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
    
    public int getMaxFileReads() { return maxFileReads; }
    public void setMaxFileReads(int maxFileReads) { this.maxFileReads = maxFileReads; }
    
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    
    public int getMaxContentLength() { return maxContentLength; }
    public void setMaxContentLength(int maxContentLength) { this.maxContentLength = maxContentLength; }
    
    public boolean isIncludeTests() { return includeTests; }
    public void setIncludeTests(boolean includeTests) { this.includeTests = includeTests; }
    
    public boolean isDeepExploration() { return deepExploration; }
    public void setDeepExploration(boolean deepExploration) { this.deepExploration = deepExploration; }
    
    public boolean isIncludeRelationships() { return includeRelationships; }
    public void setIncludeRelationships(boolean includeRelationships) { this.includeRelationships = includeRelationships; }
}
