package com.zps.zest.tools;

import com.google.gson.JsonObject;

/**
 * Interface representing a tool that can be used by the AI agent.
 * Each tool provides a specific capability using JSON-RPC style invocation.
 */
public interface AgentTool {

    /**
     * Gets the name of the tool. Used for identification in the tool registry.
     */
    String getName();

    /**
     * Gets the description of what the tool does.
     */
    String getDescription();

    /**
     * Executes the tool with the given JSON parameters.
     *
     * @param params The JSON parameters for tool execution
     * @return The result of the tool execution
     */
    String execute(JsonObject params) throws Exception;

    /**
     * Gets an example of the tool's parameters in JSON format.
     */
    JsonObject getExampleParams();
}