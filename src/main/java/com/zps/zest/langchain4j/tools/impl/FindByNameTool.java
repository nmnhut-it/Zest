package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.NameIndex;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for finding code elements by name using the NameIndex.
 */
public class FindByNameTool extends ThreadSafeIndexTool {
    
    private final HybridIndexManager indexManager;
    
    public FindByNameTool(@NotNull Project project) {
        super(project, "find_by_name", 
            "Find code elements by name (CASE-SENSITIVE partial match supported). " +
            "Examples: " +
            "- find_by_name({\"name\": \"UserService\"}) - finds UserService, UserServiceImpl, etc. " +
            "- find_by_name({\"name\": \"save\"}) - finds all methods/classes containing 'save' " +
            "- find_by_name({\"name\": \"user\", \"maxResults\": 20}) - finds up to 20 items with 'user' " +
            "NOTE: Search is case-sensitive! 'userService' won't find 'UserService'. " +
            "Params: name (string, required), maxResults (int, optional, default 10)");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Name or partial name to search for (case-sensitive)");
        name.addProperty("minLength", 1);
        properties.add("name", name);
        
        JsonObject maxResults = new JsonObject();
        maxResults.addProperty("type", "integer");
        maxResults.addProperty("description", "Maximum number of results (default: 10, range: 1-50)");
        maxResults.addProperty("default", 10);
        maxResults.addProperty("minimum", 1);
        maxResults.addProperty("maximum", 50);
        properties.add("maxResults", maxResults);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("name");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String name = getRequiredString(parameters, "name");
        int maxResults = getOptionalInt(parameters, "maxResults", 10);
        
        // Validate maxResults range
        if (maxResults < 1 || maxResults > 50) {
            return ToolResult.error("maxResults must be between 1 and 50, got: " + maxResults);
        }
        
        return executeWithIndices(() -> {
            NameIndex nameIndex = indexManager.getNameIndex();
            List<NameIndex.SearchResult> results = nameIndex.search(name, maxResults);
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("searchName", name);
            metadata.addProperty("resultCount", results.size());
            
            if (results.isEmpty()) {
                content.append("No code elements found with name matching: ").append(name);
            } else {
                content.append("Found ").append(results.size()).append(" elements matching '").append(name).append("':\n\n");
                
                for (NameIndex.SearchResult result : results) {
                    content.append("- **").append(result.getId()).append("**\n");
                    content.append("  Type: ").append(result.getType()).append("\n");
                    content.append("  File: ").append(result.getFilePath()).append("\n");
                    content.append("  Score: ").append(String.format("%.3f", result.getScore())).append("\n");
                    content.append("  Signature: `").append(result.getSignature()).append("`\n");
                    
                    // Package info is already in the file path
                    
                    content.append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
        });
    }
}
