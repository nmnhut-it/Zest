package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.ArrayList;

/**
 * Grep search tool using native ripgrep for blazing fast code search.
 * Wraps the RipgrepCodeTool to provide CodeExplorationTool interface.
 */
public class GrepSearchTool extends BaseCodeExplorationTool {
    
    public GrepSearchTool(@NotNull Project project) {
        super(project, "grep_search", 
            "Search for patterns in code using ripgrep (blazing fast regex search). " +
            "Examples: " +
            "- grep_search({\"query\": \"class.*Service\"}) - find all Service classes " +
            "- grep_search({\"query\": \"@Test\", \"filePattern\": \"*.java\"}) - find test annotations in Java files " +
            "- grep_search({\"query\": \"TODO\", \"contextLines\": 2}) - find TODOs with 2 lines of context " +
            "- grep_search({\"query\": \"import.*Logger\", \"excludePattern\": \"*.test.java\"}) - find Logger imports excluding tests " +
            "Params: query (required), filePattern (glob), excludePattern (glob), contextLines (int), caseSensitive (bool)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        // Required: query
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "The regex pattern or text to search for");
        properties.add("query", query);
        
        // Optional: filePattern
        JsonObject filePattern = new JsonObject();
        filePattern.addProperty("type", "string");
        filePattern.addProperty("description", "Glob pattern for files to include (e.g., '*.java', '**/*.ts')");
        properties.add("filePattern", filePattern);
        
        // Optional: excludePattern
        JsonObject excludePattern = new JsonObject();
        excludePattern.addProperty("type", "string");
        excludePattern.addProperty("description", "Glob pattern for files to exclude (e.g., '*.test.java', 'node_modules/**')");
        properties.add("excludePattern", excludePattern);
        
        // Optional: contextLines
        JsonObject contextLines = new JsonObject();
        contextLines.addProperty("type", "integer");
        contextLines.addProperty("description", "Number of context lines to show before and after matches (0-5)");
        contextLines.addProperty("default", 0);
        contextLines.addProperty("minimum", 0);
        contextLines.addProperty("maximum", 5);
        properties.add("contextLines", contextLines);
        
        // Optional: caseSensitive
        JsonObject caseSensitive = new JsonObject();
        caseSensitive.addProperty("type", "boolean");
        caseSensitive.addProperty("description", "Whether the search should be case-sensitive");
        caseSensitive.addProperty("default", false);
        properties.add("caseSensitive", caseSensitive);
        
        // Optional: beforeLines
        JsonObject beforeLines = new JsonObject();
        beforeLines.addProperty("type", "integer");
        beforeLines.addProperty("description", "Number of lines to show before matches (overrides contextLines)");
        beforeLines.addProperty("default", 0);
        beforeLines.addProperty("minimum", 0);
        beforeLines.addProperty("maximum", 5);
        properties.add("beforeLines", beforeLines);
        
        // Optional: afterLines
        JsonObject afterLines = new JsonObject();
        afterLines.addProperty("type", "integer");
        afterLines.addProperty("description", "Number of lines to show after matches (overrides contextLines)");
        afterLines.addProperty("default", 0);
        afterLines.addProperty("minimum", 0);
        afterLines.addProperty("maximum", 5);
        properties.add("afterLines", afterLines);
        
        schema.add("properties", properties);
        
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String query = getRequiredString(parameters, "query");
        String filePattern = getOptionalString(parameters, "filePattern", null);
        String excludePattern = getOptionalString(parameters, "excludePattern", null);
        int contextLines = parameters.has("contextLines") ? parameters.get("contextLines").getAsInt() : 0;
        boolean caseSensitive = parameters.has("caseSensitive") && parameters.get("caseSensitive").getAsBoolean();
        
        // Check for specific before/after lines
        int beforeLines = parameters.has("beforeLines") ? parameters.get("beforeLines").getAsInt() : contextLines;
        int afterLines = parameters.has("afterLines") ? parameters.get("afterLines").getAsInt() : contextLines;
        
        try {
            // Create RipgrepCodeTool instance
            RipgrepCodeTool ripgrep = new RipgrepCodeTool(
                project, 
                new HashSet<>(),  // relatedFiles - will be populated by search
                new ArrayList<>() // usagePatterns - will be populated by search
            );
            
            String result;
            
            // Use appropriate search method based on context requirements
            if (beforeLines > 0 && afterLines > 0) {
                // Both before and after context
                result = ripgrep.searchCodeWithContext(query, filePattern, excludePattern, 
                    Math.max(beforeLines, afterLines));
            } else if (beforeLines > 0) {
                // Only before context
                result = ripgrep.searchWithBeforeContext(query, filePattern, excludePattern, beforeLines);
            } else if (afterLines > 0) {
                // Only after context
                result = ripgrep.searchWithAfterContext(query, filePattern, excludePattern, afterLines);
            } else if (contextLines > 0) {
                // Symmetric context
                result = ripgrep.searchCodeWithContext(query, filePattern, excludePattern, contextLines);
            } else {
                // No context
                result = ripgrep.searchCode(query, filePattern, excludePattern);
            }
            
            // Create metadata
            JsonObject metadata = createMetadata();
            metadata.addProperty("query", query);
            if (filePattern != null) {
                metadata.addProperty("filePattern", filePattern);
            }
            if (excludePattern != null) {
                metadata.addProperty("excludePattern", excludePattern);
            }
            metadata.addProperty("caseSensitive", caseSensitive);
            if (contextLines > 0) {
                metadata.addProperty("contextLines", contextLines);
            }
            
            // Check if ripgrep is not available
            if (result.contains("❌ Ripgrep not available")) {
                // Return error with installation instructions
                return ToolResult.error(result);
            }
            
            return ToolResult.success(result, metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to perform grep search: " + e.getMessage());
        }
    }
}