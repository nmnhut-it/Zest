package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for finding all methods that call a specific method.
 */
public class FindCallersTool extends BaseCodeExplorationTool {
    
    private final HybridIndexManager indexManager;
    
    public FindCallersTool(@NotNull Project project) {
        super(project, "find_callers", "Find all methods that call a specific method");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject methodId = new JsonObject();
        methodId.addProperty("type", "string");
        methodId.addProperty("description", "ID of the method to find callers for (e.g., 'ClassName#methodName')");
        properties.add("methodId", methodId);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"methodId\"]");
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String methodId = getRequiredString(parameters, "methodId");
        
        try {
            StructuralIndex structuralIndex = indexManager.getStructuralIndex();
            List<String> callers = structuralIndex.findCallers(methodId);
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("methodId", methodId);
            metadata.addProperty("callerCount", callers.size());
            
            content.append("Callers of '").append(methodId).append("':\n\n");
            
            if (callers.isEmpty()) {
                content.append("No callers found. This method may be:\n");
                content.append("- An entry point (main method, event handler, etc.)\n");
                content.append("- Called via reflection\n");
                content.append("- Unused code\n");
                content.append("- Part of a public API\n");
            } else {
                content.append("Found ").append(callers.size()).append(" caller(s):\n\n");
                
                // Group callers by class for better organization
                for (String caller : callers) {
                    content.append("- **").append(caller).append("**\n");
                    
                    // Try to extract class information
                    int hashIndex = caller.indexOf('#');
                    if (hashIndex > 0) {
                        String className = caller.substring(0, hashIndex);
                        String methodName = caller.substring(hashIndex + 1);
                        content.append("  Class: `").append(className).append("`\n");
                        content.append("  Method: `").append(methodName).append("`\n");
                    }
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to find callers: " + e.getMessage());
        }
    }
}
