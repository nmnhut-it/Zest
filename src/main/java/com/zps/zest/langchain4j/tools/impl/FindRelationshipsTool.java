package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
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
        super(project, "find_relationships", 
            "Find structural relationships (calls, inheritance, implementations) for a code element. " +
            "Examples: " +
            "- find_relationships({\"elementId\": \"UserService\"}) - finds ALL relationships " +
            "- find_relationships({\"elementId\": \"UserService\", \"relationType\": \"CALLED_BY\"}) - who calls UserService " +
            "- find_relationships({\"elementId\": \"Repository\", \"relationType\": \"IMPLEMENTED_BY\"}) - implementations " +
            "Valid relation types: CALLS, CALLED_BY, EXTENDS, EXTENDED_BY, IMPLEMENTS, IMPLEMENTED_BY, USES, USED_BY " +
            "Params: elementId (string, required), relationType (string, optional - if omitted, returns all)");
        this.indexManager = project.getService(HybridIndexManager.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject elementId = new JsonObject();
        elementId.addProperty("type", "string");
        elementId.addProperty("description", "ID of the code element (e.g., 'UserService' or 'UserService#save')");
        properties.add("elementId", elementId);
        
        JsonObject relationType = new JsonObject();
        relationType.addProperty("type", "string");
        relationType.addProperty("description", "Specific relationship type to find (optional, returns all if omitted)");
        JsonArray enumValues = new JsonArray();
        enumValues.add("CALLS");
        enumValues.add("CALLED_BY");
        enumValues.add("EXTENDS");
        enumValues.add("EXTENDED_BY");
        enumValues.add("IMPLEMENTS");
        enumValues.add("IMPLEMENTED_BY");
        enumValues.add("USES");
        enumValues.add("USED_BY");
        relationType.add("enum", enumValues);
        properties.add("relationType", relationType);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("elementId");
        schema.add("required", required);
        
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
                // Validate relation type
                StructuralIndex.RelationType type;
                try {
                    type = StructuralIndex.RelationType.valueOf(relationType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Provide helpful error with all valid options
                    return ToolResult.error(
                        "Invalid relationType: '" + relationType + "'\n\n" +
                        "Valid relation types are:\n" +
                        "- CALLS: methods called by this element\n" +
                        "- CALLED_BY: methods that call this element\n" +
                        "- EXTENDS: classes extended by this class\n" +
                        "- EXTENDED_BY: classes that extend this class\n" +
                        "- IMPLEMENTS: interfaces implemented by this class\n" +
                        "- IMPLEMENTED_BY: classes that implement this interface\n" +
                        "- USES: types used by this element\n" +
                        "- USED_BY: elements that use this type\n\n" +
                        "Example: find_relationships({\"elementId\": \"UserService\", \"relationType\": \"CALLED_BY\"})"
                    );
                }
                
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
