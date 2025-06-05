package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.agent.QueryAugmentationAgent;
import org.jetbrains.annotations.NotNull;

/**
 * Service wrapper for QueryAugmentationAgent that can be easily integrated
 * with the JavaScript bridge.
 */
@Service(Service.Level.PROJECT)
public final class QueryAugmentationService {
    
    private static final Logger LOG = Logger.getInstance(QueryAugmentationService.class);
    private final QueryAugmentationAgent agent;
    
    public QueryAugmentationService(@NotNull Project project) {
        this.agent = project.getService(QueryAugmentationAgent.class);
        LOG.info("Initialized QueryAugmentationService");
    }
    
    /**
     * Augments a user query with relevant project context.
     * This method is designed to be called from JavaScriptBridgeActions.
     * 
     * @param query The user's original query
     * @return Augmented context string, or empty string if augmentation fails
     */
    public String augmentQuery(String query) {
        try {
            if (query == null || query.trim().isEmpty()) {
                return "";
            }
            
            long startTime = System.currentTimeMillis();
            String augmented = agent.augmentQuery(query);
            long duration = System.currentTimeMillis() - startTime;
            
            LOG.info("Query augmentation completed in " + duration + "ms");
            return augmented;
            
        } catch (Exception e) {
            LOG.error("Failed to augment query: " + query, e);
            return "";
        }
    }
    
    /**
     * Gets just the current IDE context without performing search.
     * Useful for quick context checks.
     * 
     * @return Current IDE context
     */
    public String getCurrentContext() {
        try {
            return agent.getCurrentContext();
        } catch (Exception e) {
            LOG.error("Failed to get current context", e);
            return "";
        }
    }
    
    /**
     * Performs a targeted search for code matching specific patterns.
     * 
     * @param patterns Comma-separated list of patterns (e.g., "controller,service")
     * @param limit Maximum number of results
     * @return JSON string with search results
     */
    public String searchByPatterns(String patterns, int limit) {
        try {
            // This could be enhanced to return structured results
            String query = "Find " + patterns + " in the project";
            return augmentQuery(query);
        } catch (Exception e) {
            LOG.error("Failed to search by patterns: " + patterns, e);
            return "{}";
        }
    }
}
