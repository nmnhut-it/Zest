package com.zps.zest.langchain4j.agent;

import com.google.gson.JsonObject;

/**
 * Represents a tool execution.
 */
public class ToolExecution {
    public final String toolName;
    public final JsonObject parameters;
    public final String result;
    public final boolean success;

    public ToolExecution(String toolName, JsonObject parameters, String result, boolean success) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.result = result;
        this.success = success;
    }

    // Getters
    public String getToolName() {
        return toolName;
    }

    public JsonObject getParameters() {
        return parameters;
    }

    public String getResult() {
        return result;
    }

    public boolean isSuccess() {
        return success;
    }
}
