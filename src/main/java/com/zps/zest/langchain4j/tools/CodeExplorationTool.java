package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonObject;

/**
 * Interface for code exploration tools used by the autonomous agent.
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
