package com.zps.zest.research.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Holds search results from all search strategies.
 */
public class SearchResults {
    private JsonArray gitResults;
    private JsonArray unstagedResults;
    private JsonArray projectResults;
    
    public SearchResults() {
        this.gitResults = new JsonArray();
        this.unstagedResults = new JsonArray();
        this.projectResults = new JsonArray();
    }
    
    public JsonArray getGitResults() {
        return gitResults;
    }
    
    public void setGitResults(JsonArray gitResults) {
        this.gitResults = gitResults != null ? gitResults : new JsonArray();
    }
    
    public JsonArray getUnstagedResults() {
        return unstagedResults;
    }
    
    public void setUnstagedResults(JsonArray unstagedResults) {
        this.unstagedResults = unstagedResults != null ? unstagedResults : new JsonArray();
    }
    
    public JsonArray getProjectResults() {
        return projectResults;
    }
    
    public void setProjectResults(JsonArray projectResults) {
        this.projectResults = projectResults != null ? projectResults : new JsonArray();
    }
    
    /**
     * Gets the total number of results across all strategies.
     */
    public int getTotalResults() {
        return gitResults.size() + unstagedResults.size() + projectResults.size();
    }
    
    /**
     * Checks if there are any results.
     */
    public boolean hasResults() {
        return getTotalResults() > 0;
    }
    
    /**
     * Converts results to a JsonObject for serialization.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.add("gitResults", gitResults);
        json.add("unstagedResults", unstagedResults);
        json.add("projectResults", projectResults);
        json.addProperty("totalResults", getTotalResults());
        return json;
    }
    
    /**
     * Creates a combined array of all results.
     */
    public JsonArray getAllResults() {
        JsonArray combined = new JsonArray();
        
        // Add all git results
        for (int i = 0; i < gitResults.size(); i++) {
            JsonObject result = gitResults.get(i).getAsJsonObject().deepCopy();
            result.addProperty("source", "git");
            combined.add(result);
        }
        
        // Add all unstaged results
        for (int i = 0; i < unstagedResults.size(); i++) {
            JsonObject result = unstagedResults.get(i).getAsJsonObject().deepCopy();
            result.addProperty("source", "unstaged");
            combined.add(result);
        }
        
        // Add all project results
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject().deepCopy();
            result.addProperty("source", "project");
            combined.add(result);
        }
        
        return combined;
    }
}
