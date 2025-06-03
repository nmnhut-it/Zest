package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
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
        super(project, "find_callers", 
            "Find all methods that call a specific method. " +
            "Example: find_callers({\"methodId\": \"UserService#save\"}) - finds all methods calling UserService.save(). " +
            "Params: methodId (string, required, format: 'ClassName#methodName')");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject methodId = new JsonObject();
        methodId.addProperty("type", "string");
        methodId.addProperty("description", "ID of the method to find callers for (format: 'ClassName#methodName')");
        methodId.addProperty("pattern", "^[\\w\\.]+#\\w+$");
        properties.add("methodId", methodId);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("methodId");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String methodId = getRequiredString(parameters, "methodId");
        
        // Validate and auto-correct common format mistakes
        methodId = validateAndCorrectMethodId(methodId);
        
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
    
    private String validateAndCorrectMethodId(String methodId) {
        if (methodId == null || methodId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "methodId cannot be empty. Expected format: 'ClassName#methodName'. " +
                "Example: 'UserService#save'"
            );
        }
        
        methodId = methodId.trim();
        
        // Auto-correct common mistakes
        if (methodId.contains("::")) {
            methodId = methodId.replace("::", "#");
        } else if (methodId.contains("->")) {
            methodId = methodId.replace("->", "#");
        } else if (methodId.contains(".") && !methodId.contains("#")) {
            // Try to fix dot separator
            int lastDot = methodId.lastIndexOf('.');
            String possibleMethod = methodId.substring(lastDot + 1);
            
            if (possibleMethod.length() > 0 && 
                (Character.isLowerCase(possibleMethod.charAt(0)) ||
                 possibleMethod.matches("^(get|set|is|has|add|remove|find|create|update|delete).*"))) {
                methodId = methodId.substring(0, lastDot) + "#" + possibleMethod;
            }
        }
        
        // Final validation
        if (!methodId.contains("#")) {
            throw new IllegalArgumentException(
                "Invalid methodId format. Must use 'ClassName#methodName'. Got: '" + methodId + "'"
            );
        }
        
        return methodId;
    }
}
