package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.tools.RipgrepCodeTool;
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
            "Powerful code search with blazing-fast ripgrep. Supports regex with | (OR) operator, file filtering, and context lines. " +
            "PATTERNS: " +
            "- Usage: \"new ClassName\\(\" (instantiation), \"ClassName\\.\" (static calls), \"\\bClassName\\b\" (any reference) " +
            "- Callers: \"methodName\\(\" (all calls), \"\\.methodName\\(\" (instance calls), \"(save|update|delete)\\(\" (multiple methods) " +
            "- Case-sensitive: \"[A-Z][a-z]+[A-Z]\" (camelCase), \"[a-z]+_[a-z]+\" (snake_case), \"[A-Z_]+\" (CONSTANTS) " +
            "EXAMPLES: " +
            "- grep_search({\"query\": \"new (User|Admin)Service\\(\", \"filePattern\": \"*.java\", \"beforeLines\": 2, \"afterLines\": 2}) " +
            "- grep_search({\"query\": \"@(Test|ParameterizedTest)\", \"filePattern\": \"**/*Test.java\"}) " +
            "- grep_search({\"query\": \"(getUserById|findUserById)\\(\", \"excludePattern\": \"*test*\", \"contextLines\": 3})");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        // Required: query
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "The regex pattern or text to search for. Supports | for OR (e.g., 'save|update|delete')");
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
        contextLines.addProperty("description", "Number of context lines to show before AND after matches (0-5). Use beforeLines/afterLines for different values");
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

        // Optional: multiline
        JsonObject multiline = new JsonObject();
        multiline.addProperty("type", "boolean");
        multiline.addProperty("description", "Enable multiline matching for patterns spanning lines (default: false). Use true for patterns like 'private void.*?\\}' that match across newlines");
        multiline.addProperty("default", false);
        properties.add("multiline", multiline);

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
        boolean multiline = parameters.has("multiline") && parameters.get("multiline").getAsBoolean();

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
            
            // Determine context lines to use
            int finalBeforeLines = beforeLines;
            int finalAfterLines = afterLines;

            // If contextLines is specified and before/after weren't explicitly set, use contextLines for both
            if (contextLines > 0 && parameters.has("contextLines") &&
                !parameters.has("beforeLines") && !parameters.has("afterLines")) {
                finalBeforeLines = contextLines;
                finalAfterLines = contextLines;
            }

            // Use unified searchCode method with before/after and multiline parameters
            String result = ripgrep.searchCode(query, filePattern, excludePattern,
                                              finalBeforeLines, finalAfterLines, multiline);
            
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
            if (finalBeforeLines > 0) {
                metadata.addProperty("beforeLines", finalBeforeLines);
            }
            if (finalAfterLines > 0) {
                metadata.addProperty("afterLines", finalAfterLines);
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