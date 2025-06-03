package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.CodeSearchUtility;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tool for searching code using the hybrid search system.
 */
public class SearchCodeTool extends ThreadSafeCodeExplorationTool {
    
    private final CodeSearchUtility searchUtility;
    
    public SearchCodeTool(@NotNull Project project) {
        super(project, "search_code", 
            "Search for code using natural language queries across the entire codebase. " +
            "Example: search_code({\"query\": \"user authentication logic\", \"maxResults\": 5}) - finds code related to authentication. " +
            "Params: query (string, required), maxResults (int, optional, default 10, range 1-50)");
        this.searchUtility = project.getService(CodeSearchUtility.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Natural language search query");
        properties.add("query", query);
        
        JsonObject maxResults = new JsonObject();
        maxResults.addProperty("type", "integer");
        maxResults.addProperty("description", "Maximum number of results (default: 10, range: 1-50)");
        maxResults.addProperty("default", 10);
        maxResults.addProperty("minimum", 1);
        maxResults.addProperty("maximum", 50);
        properties.add("maxResults", maxResults);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        // Search itself doesn't require read action, but might access indices
        return false;
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String query = getRequiredString(parameters, "query");
        int maxResults = getOptionalInt(parameters, "maxResults", 10);
        
        // Validate maxResults range
        if (maxResults < 1 || maxResults > 50) {
            return ToolResult.error("maxResults must be between 1 and 50, got: " + maxResults);
        }
        
        try {
            // Execute search asynchronously
            CompletableFuture<List<CodeSearchUtility.EnrichedSearchResult>> future = 
                searchUtility.searchRelatedCode(query, maxResults);
            
            // Wait for results with timeout
            List<CodeSearchUtility.EnrichedSearchResult> results = 
                future.get(300, TimeUnit.SECONDS);
            
            // Format results
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("query", query);
            metadata.addProperty("resultCount", results.size());
            
            if (results.isEmpty()) {
                content.append("No results found for query: ").append(query);
                content.append("\n\nTry:\n");
                content.append("- Using different keywords\n");
                content.append("- Being more specific (e.g., 'validate user email' instead of 'validation')\n");
                content.append("- Using technical terms (e.g., 'authentication' instead of 'login')\n");
            } else {
                content.append("Found ").append(results.size()).append(" results for query: \"").append(query).append("\"\n\n");
                
                for (int i = 0; i < results.size(); i++) {
                    CodeSearchUtility.EnrichedSearchResult result = results.get(i);
                    content.append("### Result ").append(i + 1).append(": ").append(result.getId()).append("\n");
                    content.append("Score: ").append(String.format("%.3f", result.getScore())).append("\n");
                    content.append("File: ").append(result.getFilePath()).append("\n");
                    content.append("Sources: ").append(result.getSources()).append("\n");
                    content.append("Content:\n```java\n").append(result.getContent()).append("\n```\n");
                    
                    // Add structural relationships if any
                    if (!result.getStructuralRelationships().isEmpty()) {
                        content.append("Relationships:\n");
                        result.getStructuralRelationships().forEach((type, ids) -> {
                            if (!ids.isEmpty()) {
                                content.append("- ").append(type).append(": ").append(String.join(", ", ids)).append("\n");
                            }
                        });
                    }
                    
                    // Add similar code if any
                    if (!result.getSimilarCode().isEmpty()) {
                        content.append("Similar code:\n");
                        for (CodeSearchUtility.SimilarCode similar : result.getSimilarCode()) {
                            content.append("- ").append(similar.getId())
                                   .append(" (similarity: ").append(String.format("%.3f", similar.getSimilarity()))
                                   .append(")\n");
                        }
                    }
                    
                    content.append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }
}
