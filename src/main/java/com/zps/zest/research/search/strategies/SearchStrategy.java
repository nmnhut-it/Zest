package com.zps.zest.research.search.strategies;

import com.google.gson.JsonArray;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for different search strategies.
 */
public interface SearchStrategy {
    /**
     * Executes a search with the given keywords.
     * 
     * @param keywords The keywords to search for
     * @return A future containing the search results as a JsonArray
     */
    CompletableFuture<JsonArray> search(List<String> keywords);
    
    /**
     * Gets the type of source this strategy searches.
     * 
     * @return The source type (e.g., "git", "unstaged", "project")
     */
    String getSourceType();
    
    /**
     * Disposes any resources used by this strategy.
     */
    default void dispose() {
        // Default empty implementation
    }
}
