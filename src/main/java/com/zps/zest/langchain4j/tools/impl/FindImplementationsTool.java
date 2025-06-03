package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for finding implementations of interfaces or abstract methods.
 */
public class FindImplementationsTool extends BaseCodeExplorationTool {
    
    private final HybridIndexManager indexManager;
    
    public FindImplementationsTool(@NotNull Project project) {
        super(project, "find_implementations", "Find all implementations of an interface or abstract method");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject elementId = new JsonObject();
        elementId.addProperty("type", "string");
        elementId.addProperty("description", "ID of the interface or abstract method to find implementations for");
        properties.add("elementId", elementId);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"elementId\"]");
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String elementId = getRequiredString(parameters, "elementId");
        
        try {
            StructuralIndex structuralIndex = indexManager.getStructuralIndex();
            List<String> implementations = structuralIndex.findImplementations(elementId);
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("elementId", elementId);
            metadata.addProperty("implementationCount", implementations.size());
            
            content.append("Implementations of '").append(elementId).append("':\n\n");
            
            if (implementations.isEmpty()) {
                content.append("No implementations found. This could mean:\n");
                content.append("- It's a concrete class/method (not an interface/abstract)\n");
                content.append("- No implementations exist yet\n");
                content.append("- The implementations are in external libraries\n");
            } else {
                content.append("Found ").append(implementations.size()).append(" implementation(s):\n\n");
                
                for (String impl : implementations) {
                    content.append("- **").append(impl).append("**\n");
                    
                    // Extract additional info if available
                    int hashIndex = impl.indexOf('#');
                    if (hashIndex > 0) {
                        String className = impl.substring(0, hashIndex);
                        String methodName = impl.substring(hashIndex + 1);
                        content.append("  Class: `").append(className).append("`\n");
                        content.append("  Method: `").append(methodName).append("`\n");
                    }
                }
                
                // Also check for subclasses if it's a class
                List<String> subclasses = structuralIndex.findSubclasses(elementId);
                if (!subclasses.isEmpty()) {
                    content.append("\n**Subclasses** (").append(subclasses.size()).append(" found):\n");
                    for (String subclass : subclasses) {
                        content.append("- ").append(subclass).append("\n");
                    }
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to find implementations: " + e.getMessage());
        }
    }
}
