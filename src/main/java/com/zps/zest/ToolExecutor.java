package com.zps.zest;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.XmlRpcUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles execution of tools based on JSON-RPC style invocations.
 */
public class ToolExecutor {
    private static final Logger LOG = Logger.getInstance(ToolExecutor.class);
    
    // Pattern for JSON-RPC style tool invocation
    private static final Pattern JSON_TOOL_PATTERN = Pattern.compile("<TOOL>(.*?)</TOOL>", Pattern.DOTALL);
    
    private final AgentToolRegistry toolRegistry;
    
    public ToolExecutor(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    /**
     * Processes a response by executing any tool invocations found.
     * 
     * @param response The response text that may contain tool invocations
     * @return The processed response with tool results
     */
    public String processToolInvocations(String response) {
        StringBuilder processedResponse = new StringBuilder();
        int lastEnd = 0;

        // Find all JSON-RPC tool invocations
        Matcher matcher = JSON_TOOL_PATTERN.matcher(response);
        
        while (matcher.find()) {
            // Add text before the tool invocation
            processedResponse.append(response, lastEnd, matcher.start());
            
            // Extract the JSON invocation
            String invocation = matcher.group(1);
            
            // Execute the tool
            String toolOutput = executeXmlToolInvocation(invocation);
            
            // Format the tool output with a clear header
            processedResponse.append("\n\n### Tool Result\n");
            processedResponse.append(toolOutput);
            processedResponse.append("---------------------------");
            
            lastEnd = matcher.end();
        }

        // Add any remaining text
        if (lastEnd < response.length()) {
            processedResponse.append(response.substring(lastEnd));
        }

        return processedResponse.toString();
    }

    public String executeXmlToolInvocation(String xmlText) {
        try {
            // Convert XML to JSON
            JsonObject jsonObject = XmlRpcUtils.convertXmlToJson("<TOOL>"+xmlText+"</TOOL>");

            // Extract tool name and parameters
            if (!jsonObject.has("toolName")) {
                return "Error: Invalid tool invocation - missing toolName";
            }

            String toolName = jsonObject.get("toolName").getAsString();
            JsonObject arguments =  jsonObject;
            // Get the tool and execute it
            AgentTool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return "Error: Unknown tool: " + toolName;
            }

            return tool.execute(arguments);
        } catch (Exception e) {
            LOG.error("Error executing XML tool: " + e.getMessage(), e);
            return "Error executing XML tool: " + e.getMessage();
        }
    }
}