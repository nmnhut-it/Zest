package com.zps.zest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool invocations from LLM responses.
 */
public class ToolParser {
    private static final Logger LOG = Logger.getInstance(ToolParser.class);
    
    // Pattern to match JSON-RPC style tool invocations
    private static final Pattern TOOL_PATTERN = Pattern.compile("<TOOL>(\\{.*?\\})</TOOL>", Pattern.DOTALL);
    
    /**
     * Parses a tool invocation from the given text.
     * 
     * @param toolText The JSON string containing the tool invocation
     * @return The parsed tool invocation, or null if parsing fails
     */
    public static ToolInvocation parse(String toolText) {
        try {
            JsonObject json = JsonParser.parseString(toolText).getAsJsonObject();
            
            if (!json.has("toolName")) {
                LOG.warn("Missing toolName in tool invocation");
                return null;
            }
            
            String toolName = json.get("toolName").getAsString();
            JsonObject params = json.has("arguments") ? 
                json.getAsJsonObject("arguments") : new JsonObject();
                
            return new ToolInvocation(toolName, params);
        } catch (JsonSyntaxException e) {
            LOG.error("Invalid JSON in tool invocation: " + e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.error("Error parsing tool invocation: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extracts all tool invocations from a response.
     * 
     * @param response The LLM response text
     * @return An array of tool invocation matches
     */
    public static ToolMatch[] extractToolInvocations(String response) {
        Matcher matcher = TOOL_PATTERN.matcher(response);
        java.util.List<ToolMatch> matches = new java.util.ArrayList<>();
        
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            String jsonText = matcher.group(1);
            ToolInvocation invocation = parse(jsonText);
            
            if (invocation != null) {
                matches.add(new ToolMatch(fullMatch, invocation));
            }
        }
        
        return matches.toArray(new ToolMatch[0]);
    }
    
    /**
     * Represents a parsed tool invocation.
     */
    public static class ToolInvocation {
        private final String toolName;
        private final JsonObject params;
        
        public ToolInvocation(String toolName, JsonObject params) {
            this.toolName = toolName;
            this.params = params;
        }
        
        public String getToolName() {
            return toolName;
        }
        
        public JsonObject getParams() {
            return params;
        }
    }
    
    /**
     * Represents a matched tool invocation in text.
     */
    public static class ToolMatch {
        private final String fullMatch;
        private final ToolInvocation invocation;
        
        public ToolMatch(String fullMatch, ToolInvocation invocation) {
            this.fullMatch = fullMatch;
            this.invocation = invocation;
        }
        
        public String getFullMatch() {
            return fullMatch;
        }
        
        public ToolInvocation getInvocation() {
            return invocation;
        }
    }
}