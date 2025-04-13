package com.zps.zest;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.FollowUpQuestionTool;
import com.zps.zest.tools.XmlRpcUtils;
import org.apache.commons.lang.StringEscapeUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles execution of tools based on JSON-RPC style invocations.
 */
public class ToolExecutor {
    private static final Logger LOG = Logger.getInstance(ToolExecutor.class);
    
    // Pattern for JSON-RPC style tool invocation
    private static final Pattern JSON_TOOL_PATTERN = Pattern.compile("(```)?.*?(\n)?<TOOL>(.*?)</TOOL>(```)?(\n)?", Pattern.DOTALL);

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
            String invocation = matcher.group(3);
            
            // Execute the tool
            ToolExecutionResponse toolOutputObject = executeXmlToolInvocation(invocation);
            String toolOutput = toolOutputObject.result;
            if (!toolOutputObject.methodName.equals(FollowUpQuestionTool.NAME)) {


                // Format the tool output with a clear header
                processedResponse.append("\n\n### Tool Result\n");
                processedResponse.append("#### Call: \n```xml\n" + (invocation) + "\n```\n\n");
                processedResponse.append("#### Result: \n\n");

                if (!toolOutput.startsWith("```")) {
                    processedResponse.append("\n\n```\n");
                }

                processedResponse.append(toolOutput);

                if (!toolOutput.startsWith("```")) {
                    processedResponse.append("\n```\n");
                }

                if (!toolOutput.endsWith("\n")) {
                    processedResponse.append("\n");
                }

                lastEnd = matcher.end();
            }
            else {
                processedResponse.append(toolOutput);
                lastEnd = matcher.end();

            }
        }

        // Add any remaining text
        if (lastEnd < response.length()) {
            processedResponse.append(response.substring(lastEnd));
        }

        return processedResponse.toString();
    }
    /**
     * Executes a tool invocation from XML format and returns a structured response.
     *
     * @param xmlText The XML representation of the tool invocation
     * @return A ToolExecutionResponse containing method name, invocation details, result or error
     */
    public ToolExecutionResponse executeXmlToolInvocation(String xmlText) {
        String methodName = "";
        JsonObject invocationJson = null;

        try {
            // Convert XML to JSON
            invocationJson = XmlRpcUtils.convertXmlToJson("<TOOL>" + xmlText + "</TOOL>");
             // Extract tool name and parameters
            if (!invocationJson.has("toolName")) {
                return ToolExecutionResponse.error(methodName, invocationJson,
                        "Invalid tool invocation - missing toolName");
            }

            String toolName = invocationJson.get("toolName").getAsString();
            methodName = toolName;
            // Get the tool and execute it
            AgentTool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return ToolExecutionResponse.error(methodName, invocationJson,
                        "Unknown tool: " + toolName);
            }

            // Execute tool and capture result
            String result = tool.execute(invocationJson);

            // Return success response
            return ToolExecutionResponse.success(methodName, invocationJson, result);

        } catch (Exception e) {
            LOG.error("Error executing XML tool: " + e.getMessage(), e);
            return ToolExecutionResponse.error(methodName, invocationJson,
                    "Error executing XML tool: " + e.getMessage());
        }
    }
    /**
     * A class representing the response from a tool execution.
     * This encapsulates all information about the execution, including
     * the method called, the parameters used, any errors, and the result.
     */
    public static class ToolExecutionResponse {
        private String methodName;
        private JsonObject invocationJson;
        private String error;
        private String result;

        /**
         * Default constructor
         */
        public ToolExecutionResponse() {
            this.methodName = null;
            this.invocationJson = null;
            this.error = null;
            this.result = null;
        }

        /**
         * Constructor with all parameters
         *
         * @param methodName The name of the method that was executed
         * @param invocationJson The JSON representation of the invocation parameters
         * @param error Any error message (null if execution was successful)
         * @param result The execution result (null if there was an error)
         */
        public ToolExecutionResponse(String methodName, JsonObject invocationJson, String error, String result) {
            this.methodName = methodName;
            this.invocationJson = invocationJson;
            this.error = error;
            this.result = result;
        }

        /**
         * Static factory method to create a success response
         *
         * @param methodName The name of the method that was executed
         * @param invocationJson The JSON representation of the invocation parameters
         * @param result The execution result
         * @return A ToolExecutionResponse indicating successful execution
         */
        public static ToolExecutionResponse success(String methodName, JsonObject invocationJson, String result) {
            return new ToolExecutionResponse(methodName, invocationJson, null, result);
        }

        /**
         * Static factory method to create an error response
         *
         * @param methodName The name of the method that was executed
         * @param invocationJson The JSON representation of the invocation parameters
         * @param error The error message
         * @return A ToolExecutionResponse indicating execution failure
         */
        public static ToolExecutionResponse error(String methodName, JsonObject invocationJson, String error) {
            return new ToolExecutionResponse(methodName, invocationJson, error, null);
        }

        // Getters and setters

        public String getMethodName() {
            return methodName;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        public JsonObject getInvocationJson() {
            return invocationJson;
        }

        public void setInvocationJson(JsonObject invocationJson) {
            this.invocationJson = invocationJson;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        /**
         * Determines if the execution was successful (no error)
         *
         * @return true if execution was successful, false otherwise
         */
        public boolean isSuccess() {
            return error == null;
        }

        /**
         * Converts this response to a JsonObject representation
         *
         * @return A JsonObject representing this response
         */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();

            if (methodName != null) {
                json.addProperty("methodName", methodName);
            }

            if (invocationJson != null) {
                json.add("invocationJson", invocationJson);
            }

            if (error != null) {
                json.addProperty("error", error);
            } else {
                json.addProperty("error", (String) null);
            }

            if (result != null) {
                json.addProperty("result", result);
            } else {
                json.addProperty("result", (String) null);
            }

            return json;
        }

        /**
         * Creates a string representation of this response
         *
         * @return A string representation
         */
        @Override
        public String toString() {
            return toJson().toString();
        }
    }
}