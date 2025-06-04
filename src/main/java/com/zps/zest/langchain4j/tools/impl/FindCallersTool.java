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
            "REQUIRED FORMAT: 'ClassName#methodName' (use # as separator). " +
            "Examples: " +
            "- find_callers({\"methodId\": \"UserService#save\"}) - finds all callers of save() in UserService " +
            "- find_callers({\"methodId\": \"com.example.UserService#save\"}) - with full package name " +
            "Note: For overloaded methods, this finds callers of ALL overloads. " +
            "Params: methodId (string, required)");
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
        try {
            methodId = validateAndCorrectMethodId(methodId);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        
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
                "methodId cannot be empty. Required format: 'ClassName#methodName'. " +
                "Examples: 'UserService#save', 'com.example.UserService#save'"
            );
        }
        
        methodId = methodId.trim();
        
        // Check if already in correct format
        if (methodId.contains("#")) {
            return methodId;
        }
        
        // If using dot notation and looks like a method call
        if (methodId.contains(".")) {
            int lastDot = methodId.lastIndexOf('.');
            String possibleMethod = methodId.substring(lastDot + 1);
            
            // Remove any parentheses
            possibleMethod = possibleMethod.replace("()", "");
            
            // Check if it looks like a method name (starts with lowercase or common prefixes)
            if (possibleMethod.length() > 0 && 
                (Character.isLowerCase(possibleMethod.charAt(0)) ||
                 possibleMethod.matches("^(get|set|is|has|add|remove|find|create|update|delete|save|load|execute|validate|check|test).*"))) {
                String corrected = methodId.substring(0, lastDot) + "#" + possibleMethod;
                throw new IllegalArgumentException(
                    "Invalid format: '" + methodId + "'. " +
                    "Use '#' separator instead of '.'. " +
                    "Did you mean: '" + corrected + "'?"
                );
            }
        }
        
        // Other wrong separators
        if (methodId.contains("::") || methodId.contains("->")) {
            String corrected = methodId.replace("::", "#").replace("->", "#");
            throw new IllegalArgumentException(
                "Invalid format: '" + methodId + "'. " +
                "Use '#' as separator. " +
                "Did you mean: '" + corrected + "'?"
            );
        }
        
        // No separator found
        throw new IllegalArgumentException(
            "Invalid format: '" + methodId + "'. " +
            "Must use 'ClassName#methodName' format. " +
            "Examples: 'UserService#save', 'ArrayList#add', 'com.example.Service#process'"
        );
    }
}
