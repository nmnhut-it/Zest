package com.zps.zest.langchain4j.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for extracting tool calls from LLM responses.
 */
public class ToolCallParser {
    private static final Logger LOG = Logger.getInstance(ToolCallParser.class);
    
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
        "```(?:json)?\\s*\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameters\"\\s*:\\s*\\{[^}]*\\}.*?\\}\\s*```",
        Pattern.DOTALL | Pattern.MULTILINE
    );
    
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
        "```(?:json)?\\s*(.+?)\\s*```",
        Pattern.DOTALL
    );
    
    private final Gson gson = new Gson();
    
    /**
     * Parses tool calls from LLM response text.
     */
    public List<ToolCall> parseToolCalls(String response) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        // First try to find explicit tool call JSON blocks
        Matcher matcher = TOOL_CALL_PATTERN.matcher(response);
        while (matcher.find()) {
            try {
                String jsonContent = matcher.group(0)
                    .replaceFirst("```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
                
                JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
                ToolCall toolCall = parseToolCall(json);
                if (toolCall != null) {
                    toolCalls.add(toolCall);
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse tool call JSON", e);
            }
        }
        
        // If no explicit tool calls found, try to find any JSON blocks
        if (toolCalls.isEmpty()) {
            Matcher jsonMatcher = JSON_BLOCK_PATTERN.matcher(response);
            while (jsonMatcher.find()) {
                try {
                    String jsonContent = jsonMatcher.group(1).trim();
                    JsonElement element = JsonParser.parseString(jsonContent);
                    
                    if (element.isJsonObject()) {
                        JsonObject json = element.getAsJsonObject();
                        if (json.has("tool") && json.has("parameters")) {
                            ToolCall toolCall = parseToolCall(json);
                            if (toolCall != null) {
                                toolCalls.add(toolCall);
                            }
                        }
                    } else if (element.isJsonArray()) {
                        JsonArray array = element.getAsJsonArray();
                        for (JsonElement item : array) {
                            if (item.isJsonObject()) {
                                JsonObject json = item.getAsJsonObject();
                                if (json.has("tool") && json.has("parameters")) {
                                    ToolCall toolCall = parseToolCall(json);
                                    if (toolCall != null) {
                                        toolCalls.add(toolCall);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to parse JSON block", e);
                }
            }
        }
        
        // Also check for inline tool calls in a structured format
        if (toolCalls.isEmpty()) {
            toolCalls.addAll(parseInlineToolCalls(response));
        }
        
        return toolCalls;
    }
    
    /**
     * Parses a single tool call from JSON.
     */
    private ToolCall parseToolCall(JsonObject json) {
        try {
            String toolName = json.get("tool").getAsString();
            JsonObject parameters = json.getAsJsonObject("parameters");
            
            String reasoning = null;
            if (json.has("reasoning")) {
                reasoning = json.get("reasoning").getAsString();
            }
            
            return new ToolCall(toolName, parameters, reasoning);
        } catch (Exception e) {
            LOG.warn("Invalid tool call format", e);
            return null;
        }
    }
    
    /**
     * Parses inline tool calls from structured text.
     */
    private List<ToolCall> parseInlineToolCalls(String response) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        // Pattern for inline tool calls like: Tool: find_by_name("ClassName")
        Pattern inlinePattern = Pattern.compile(
            "Tool:\\s*(\\w+)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = inlinePattern.matcher(response);
        while (matcher.find()) {
            try {
                String toolName = matcher.group(1);
                String paramsStr = matcher.group(2);
                
                // Parse parameters
                JsonObject parameters = parseInlineParameters(paramsStr);
                toolCalls.add(new ToolCall(toolName, parameters, null));
            } catch (Exception e) {
                LOG.debug("Failed to parse inline tool call", e);
            }
        }
        
        return toolCalls;
    }
    
    /**
     * Parses inline parameters from a string like "param1", "param2"
     */
    private JsonObject parseInlineParameters(String paramsStr) {
        JsonObject params = new JsonObject();
        
        // Simple parsing for quoted strings and key-value pairs
        String[] parts = paramsStr.split(",");
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            
            // Remove quotes
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            }
            
            // Check if it's a key-value pair
            if (part.contains("=")) {
                String[] kv = part.split("=", 2);
                String key = kv[0].trim();
                String value = kv[1].trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                params.addProperty(key, value);
            } else {
                // Default parameter names based on position
                if (i == 0) {
                    params.addProperty("query", part);
                } else if (i == 1) {
                    try {
                        params.addProperty("maxResults", Integer.parseInt(part));
                    } catch (NumberFormatException e) {
                        params.addProperty("param" + (i + 1), part);
                    }
                }
            }
        }
        
        return params;
    }
    
    /**
     * Represents a parsed tool call.
     */
    public static class ToolCall {
        private final String toolName;
        private final JsonObject parameters;
        private final String reasoning;
        
        public ToolCall(String toolName, JsonObject parameters, String reasoning) {
            this.toolName = toolName;
            this.parameters = parameters;
            this.reasoning = reasoning;
        }
        
        public String getToolName() { return toolName; }
        public JsonObject getParameters() { return parameters; }
        public String getReasoning() { return reasoning; }
        
        @Override
        public String toString() {
            return "ToolCall{" +
                   "toolName='" + toolName + '\'' +
                   ", parameters=" + parameters +
                   ", reasoning='" + reasoning + '\'' +
                   '}';
        }
    }
}
