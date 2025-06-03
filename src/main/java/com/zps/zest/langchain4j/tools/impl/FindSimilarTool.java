package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for finding semantically similar code using vector embeddings.
 */
public class FindSimilarTool extends BaseCodeExplorationTool {
    
    private final HybridIndexManager indexManager;
    
    public FindSimilarTool(@NotNull Project project) {
        super(project, "find_similar", "Find semantically similar code using vector embeddings");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject elementId = new JsonObject();
        elementId.addProperty("type", "string");
        elementId.addProperty("description", "ID of the code element to find similar code for");
        properties.add("elementId", elementId);
        
        JsonObject maxResults = new JsonObject();
        maxResults.addProperty("type", "integer");
        maxResults.addProperty("description", "Maximum number of results (default: 5)");
        maxResults.addProperty("default", 5);
        properties.add("maxResults", maxResults);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"elementId\"]");
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String elementId = getRequiredString(parameters, "elementId");
        int maxResults = getOptionalInt(parameters, "maxResults", 5);
        
        try {
            SemanticIndex semanticIndex = indexManager.getSemanticIndex();
            List<SemanticIndex.SearchResult> results = semanticIndex.findSimilar(elementId, maxResults);
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("elementId", elementId);
            metadata.addProperty("resultCount", results.size());
            
            if (results.isEmpty()) {
                content.append("No similar code found for: ").append(elementId);
            } else {
                content.append("Found ").append(results.size()).append(" similar code elements to '").append(elementId).append("':\n\n");
                
                for (SemanticIndex.SearchResult result : results) {
                    content.append("### ").append(result.getId()).append("\n");
                    content.append("Similarity: ").append(String.format("%.3f", result.getScore())).append("\n");
                    
                    // Extract metadata
                    if (result.getMetadata() != null) {
                        String type = (String) result.getMetadata().get("type");
                        String filePath = (String) result.getMetadata().get("file_path");
                        
                        if (type != null) content.append("Type: ").append(type).append("\n");
                        if (filePath != null) content.append("File: ").append(filePath).append("\n");
                    }
                    
                    content.append("Content:\n```java\n").append(result.getContent()).append("\n```\n\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Similar code search failed: " + e.getMessage());
        }
    }
}
