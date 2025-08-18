package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonObject;

/**
 * Interface for code exploration tools used by the autonomous agent.
 * Each tool is responsible for defining its own behavior, description, and usage guidelines.
 */
public interface CodeExplorationTool {
    /**
     * Gets the tool name (used in tool calls).
     */
    String getName();
    
    /**
     * Gets a human-readable description of what the tool does.
     */
    String getDescription();
    
    /**
     * Gets the JSON schema for the tool's parameters.
     */
    JsonObject getParameterSchema();
    
    /**
     * Executes the tool with the given parameters.
     */
    ToolResult execute(JsonObject parameters);
    
    /**
     * Gets the summary for OpenAPI documentation.
     * Default implementation uses the first line of description.
     */
    default String getSummary() {
        String desc = getDescription();
        if (desc == null) return getName();
        
        // Get first sentence/line as summary
        int firstPeriod = desc.indexOf('.');
        int firstNewline = desc.indexOf('\n');
        
        int cutoff = -1;
        if (firstPeriod > 0 && firstNewline > 0) {
            cutoff = Math.min(firstPeriod, firstNewline);
        } else if (firstPeriod > 0) {
            cutoff = firstPeriod;
        } else if (firstNewline > 0) {
            cutoff = firstNewline;
        }
        
        if (cutoff > 0 && cutoff < 150) {  // Reasonable summary length
            return desc.substring(0, cutoff).trim();
        }
        
        // Fallback: truncate long descriptions
        if (desc.length() > 150) {
            return desc.substring(0, 147).trim() + "...";
        }
        
        return desc;
    }
    
    /**
     * Gets exploration guidelines specific to this tool.
     * Tools should override this to provide AI with specific guidance on effective usage.
     */
    default String getExplorationGuidelines() {
        return "Use this tool as part of systematic codebase exploration. " +
               "Consider running multiple related searches in parallel for comprehensive results.";
    }
    
    /**
     * Gets the tool category for organization and documentation.
     */
    default ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }
    
    /**
     * Categories for organizing tools in documentation and UI.
     */
    enum ToolCategory {
        SEARCH("Search & Discovery", "Tools for finding code, files, and patterns"),
        FILE_OPS("File Operations", "Tools for reading, writing, and manipulating files"),
        PROJECT("Project Structure", "Tools for understanding project layout and organization"),
        UTILITY("Utility", "General-purpose utility tools"),
        AI("AI", "Tools for AI to use");

        private final String displayName;
        private final String description;
        
        ToolCategory(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Result of a tool execution.
     */
    class ToolResult {
        private final String content;
        private final JsonObject metadata;
        private final boolean success;
        private final String error;
        
        private ToolResult(String content, JsonObject metadata, boolean success, String error) {
            this.content = content;
            this.metadata = metadata;
            this.success = success;
            this.error = error;
        }
        
        public static ToolResult success(String content, JsonObject metadata) {
            return new ToolResult(content, metadata, true, null);
        }
        
        public static ToolResult error(String error) {
            return new ToolResult(null, new JsonObject(), false, error);
        }
        
        public String getContent() { return content; }
        public JsonObject getMetadata() { return metadata; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }
}
