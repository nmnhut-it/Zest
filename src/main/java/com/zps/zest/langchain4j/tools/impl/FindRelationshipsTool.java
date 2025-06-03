package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool for finding structural relationships between code elements.
 */
public class FindRelationshipsTool extends BaseCodeExplorationTool {
    
    private final HybridIndexManager indexManager;
    
    public FindRelationshipsTool(@NotNull Project project) {
        super(project, "find_relationships", "Find structural relationships (calls, inheritance, implementations) for a code element");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject elementId = new JsonObject();
        elementId.addProperty("type", "string");
        elementId.addProperty("description", "ID of the code element to find relationships for");
        properties.add("elementId", elementId);
        
        JsonObject relationType = new JsonObject();
        relationType.addProperty("type", "string");
        relationType.addProperty("description", "Type of relationship to find (optional, defaults to all)");
        relationType.addProperty("enum", "[\"CALLS\", \"CALLED_BY\", \"EXTENDS\", \"EXTENDED_BY\", \"IMPLEMENTS\", \"IMPLEMENTED_BY\", \"USES\", \"USED_BY\"]");
        properties.add("relationType", relationType);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"elementId\"]");
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String elementId = getRequiredString(parameters, "elementId");
        String relationType = getOptionalString(parameters, "relationType", null);
        
        try {
            StructuralIndex structuralIndex = indexManager.getStructuralIndex();
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("elementId", elementId);
            
            content.append("Relationships for '").append(elementId).append("':\n\n");
            
            if (relationType != null) {
                // Find specific relationship type
                StructuralIndex.RelationType type = StructuralIndex.RelationType.valueOf(relationType);
                
                // Get all relationships and filter for the specific type
                Map<StructuralIndex.RelationType, List<String>> allRelationships = 
                    structuralIndex.findAllRelated(elementId);
                
                List<String> related = allRelationships.getOrDefault(type, new ArrayList<>());
                
                content.append("**").append(type).append("** (").append(related.size()).append(" found):\n");
                if (related.isEmpty()) {
                    content.append("- None found\n");
                } else {
                    for (String id : related) {
                        content.append("- ").append(id).append("\n");
                    }
                }
                
                metadata.addProperty("relationType", relationType);
                metadata.addProperty("resultCount", related.size());
                
            } else {
                // Find all relationships
                Map<StructuralIndex.RelationType, List<String>> allRelationships = 
                    structuralIndex.findAllRelated(elementId);
                
                int totalCount = 0;
                for (Map.Entry<StructuralIndex.RelationType, List<String>> entry : allRelationships.entrySet()) {
                    List<String> related = entry.getValue();
                    if (!related.isEmpty()) {
                        content.append("**").append(entry.getKey()).append("** (").append(related.size()).append(" found):\n");
                        for (String id : related) {
                            content.append("- ").append(id).append("\n");
                        }
                        content.append("\n");
                        totalCount += related.size();
                    }
                }
                
                if (totalCount == 0) {
                    content.append("No relationships found for this element.\n");
                }
                
                metadata.addProperty("totalRelationships", totalCount);
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Relationship search failed: " + e.getMessage());
        }
    }
}
