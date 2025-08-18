package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for retrieving relevant code context using RAG (Retrieval-Augmented Generation).
 * Follows the CodeExplorationTool pattern.
 */
public class RetrievalTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(RetrievalTool.class);
    
    private final ZestLangChain4jService langChainService;
    
    public RetrievalTool(@NotNull Project project) {
        super(project, "retrieve_context", 
            "Retrieve relevant code context for a given query using semantic search and RAG. " +
            "This tool finds code sections that are semantically related to your query across the entire codebase.");
        this.langChainService = project.getService(ZestLangChain4jService.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // query parameter
        JsonObject queryParam = new JsonObject();
        queryParam.addProperty("type", "string");
        queryParam.addProperty("description", "The search query to find relevant code context");
        properties.add("query", queryParam);
        
        // max_results parameter (optional)
        JsonObject maxResultsParam = new JsonObject();
        maxResultsParam.addProperty("type", "integer");
        maxResultsParam.addProperty("description", "Maximum number of results to return (default: 10)");
        maxResultsParam.addProperty("default", 10);
        properties.add("max_results", maxResultsParam);
        
        // threshold parameter (optional)
        JsonObject thresholdParam = new JsonObject();
        thresholdParam.addProperty("type", "number");
        thresholdParam.addProperty("description", "Relevance threshold (0.0-1.0, default: 0.7)");
        thresholdParam.addProperty("default", 0.7);
        properties.add("threshold", thresholdParam);
        
        schema.add("properties", properties);
        
        // Required parameters
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        return false; // This tool makes LLM calls, doesn't need IntelliJ read access
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        try {
            // Extract parameters
            String query = getRequiredString(parameters, "query");
            int maxResults = getOptionalInt(parameters, "max_results", 10);
            double threshold = getOptionalDouble(parameters, "threshold", 0.7);
            
            LOG.info("Retrieving context for query: " + query);
            
            // Execute retrieval synchronously (since we need to return ToolResult)
            ZestLangChain4jService.RetrievalResult result = langChainService.retrieveContext(query, maxResults, threshold).join();
            
            JsonObject metadata = createMetadata();
            metadata.addProperty("query", query);
            metadata.addProperty("maxResults", maxResults);
            metadata.addProperty("threshold", threshold);
            metadata.addProperty("success", result.isSuccess());
            
            if (result.isSuccess()) {
                JsonObject response = new JsonObject();
                response.addProperty("message", result.getMessage());
                response.addProperty("count", result.getItems().size());
                
                JsonArray contextItems = new JsonArray();
                for (ZestLangChain4jService.ContextItem item : result.getItems()) {
                    contextItems.add(contextItemToJson(item));
                }
                response.add("context_items", contextItems);
                
                metadata.addProperty("itemCount", result.getItems().size());
                
                return ToolResult.success(response.toString(), metadata);
            } else {
                return ToolResult.error("Retrieval failed: " + result.getMessage());
            }
            
        } catch (Exception e) {
            LOG.error("Error in retrieve_context tool", e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
    
    private JsonObject contextItemToJson(ZestLangChain4jService.ContextItem item) {
        JsonObject json = new JsonObject();
        json.addProperty("id", item.getId());
        json.addProperty("title", item.getTitle());
        json.addProperty("content", item.getContent());
        json.addProperty("score", item.getScore());
        
        if (item.getFilePath() != null) {
            json.addProperty("file_path", item.getFilePath());
        }
        if (item.getLineNumber() != null) {
            json.addProperty("line_number", item.getLineNumber());
        }
        
        return json;
    }
    
    // Helper method for double parameters
    private double getOptionalDouble(JsonObject parameters, String key, double defaultValue) {
        if (!parameters.has(key) || parameters.get(key).isJsonNull()) {
            return defaultValue;
        }
        return parameters.get(key).getAsDouble();
    }
    
    @Override
    public ToolCategory getCategory() {
        return ToolCategory.SEARCH;
    }
}