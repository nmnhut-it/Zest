package com.zps.zest.research.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.research.search.strategies.GitSearchStrategy;
import com.zps.zest.research.search.strategies.EnhancedProjectSearchStrategy;
import com.zps.zest.research.search.strategies.SearchStrategy;
import com.zps.zest.research.search.strategies.UnstagedSearchStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates parallel searches across all search strategies.
 */
public class SearchCoordinator {
    private static final Logger LOG = Logger.getInstance(SearchCoordinator.class);
    
    private final Project project;
    private final List<SearchStrategy> strategies;
    
    public SearchCoordinator(@NotNull Project project) {
        this.project = project;
        this.strategies = new ArrayList<>();
        
        // Initialize search strategies
        strategies.add(new GitSearchStrategy(project));
        strategies.add(new UnstagedSearchStrategy(project));
        strategies.add(new EnhancedProjectSearchStrategy(project)); // Use enhanced strategy
        
        LOG.info("SearchCoordinator initialized with " + strategies.size() + " strategies (using enhanced project search)");
    }
    
    /**
     * Executes searches in parallel across all strategies.
     */
    public CompletableFuture<SearchResults> searchAll(List<String> keywords) {
        LOG.info("Starting parallel search with keywords: " + keywords);
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            // Create futures for each strategy
            List<CompletableFuture<JsonArray>> futures = new ArrayList<>();
            
            for (SearchStrategy strategy : strategies) {
                CompletableFuture<JsonArray> future = strategy.search(keywords)
                    .exceptionally(throwable -> {
                        LOG.error("Error in " + strategy.getSourceType() + " search", throwable);
                        return new JsonArray(); // Return empty array on error
                    });
                futures.add(future);
            }
            
            // Wait for all searches to complete
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            try {
                allFutures.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.error("Error waiting for search results", e);
            }
            
            // Collect results
            SearchResults results = new SearchResults();
            
            for (int i = 0; i < strategies.size(); i++) {
                SearchStrategy strategy = strategies.get(i);
                try {
                    JsonArray strategyResults = futures.get(i).getNow(new JsonArray());
                    
                    switch (strategy.getSourceType()) {
                        case "git":
                            results.setGitResults(strategyResults);
                            break;
                        case "unstaged":
                            results.setUnstagedResults(strategyResults);
                            break;
                        case "project":
                            results.setProjectResults(strategyResults);
                            
                            // Log enhanced content info
                            logEnhancedContentInfo(strategyResults);
                            break;
                    }
                    
                    LOG.info(strategy.getSourceType() + " search returned " + 
                            strategyResults.size() + " results");
                    
                } catch (Exception e) {
                    LOG.error("Error collecting results from " + strategy.getSourceType(), e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("Parallel search completed in " + duration + "ms");
            LOG.info("Total results: " + results.getTotalResults());
            
            return results;
        });
    }
    
    /**
     * Logs information about enhanced content in project results.
     */
    private void logEnhancedContentInfo(JsonArray projectResults) {
        int fullContentCount = 0;
        int containingFunctionCount = 0;
        
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            JsonArray matches = result.getAsJsonArray("matches");
            
            for (int j = 0; j < matches.size(); j++) {
                JsonObject match = matches.get(j).getAsJsonObject();
                
                if (match.has("hasFullContent") && match.get("hasFullContent").getAsBoolean()) {
                    fullContentCount++;
                } else if (match.has("matches")) {
                    // Check text matches for containing functions
                    JsonArray textMatches = match.getAsJsonArray("matches");
                    for (int k = 0; k < textMatches.size(); k++) {
                        JsonObject textMatch = textMatches.get(k).getAsJsonObject();
                        if (textMatch.has("containingFunction")) {
                            containingFunctionCount++;
                        }
                    }
                }
            }
        }
        
        if (fullContentCount > 0 || containingFunctionCount > 0) {
            LOG.info("Enhanced content: " + fullContentCount + " files with full content, " + 
                    containingFunctionCount + " containing functions extracted");
        }
    }
    
    /**
     * Disposes resources.
     */
    public void dispose() {
        for (SearchStrategy strategy : strategies) {
            try {
                strategy.dispose();
            } catch (Exception e) {
                LOG.error("Error disposing strategy: " + strategy.getSourceType(), e);
            }
        }
        strategies.clear();
    }
}
