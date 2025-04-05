package com.zps.zest.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Base implementation of AgentTool to simplify creating new tools.
 */
public abstract class BaseAgentTool implements AgentTool {
    private static final Logger LOG = Logger.getInstance(BaseAgentTool.class);
    
    private final String name;
    private final String description;
    
    public BaseAgentTool(String name, String description) {
        this.name = name;
        this.description = description;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public String execute(JsonObject params) throws Exception {
        try {
            return doExecute(params);
        } catch (Exception e) {
            LOG.error("Error executing tool " + name + ": " + e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Implementation method to be overridden by subclasses.
     */
    protected abstract String doExecute(JsonObject params) throws Exception;
    
    /**
     * Helper method to safely get a string parameter.
     */
    protected String getStringParam(JsonObject params, String paramName, String defaultValue) {
        if (params != null && params.has(paramName)) {
            JsonElement element = params.get(paramName);
            if (!element.isJsonNull() && element.isJsonPrimitive()) {
                return element.getAsString();
            }
        }
        return defaultValue;
    }
    
    /**
     * Helper method to safely get a boolean parameter.
     */
    protected boolean getBooleanParam(JsonObject params, String paramName, boolean defaultValue) {
        if (params != null && params.has(paramName)) {
            JsonElement element = params.get(paramName);
            if (!element.isJsonNull() && element.isJsonPrimitive()) {
                return element.getAsBoolean();
            }
        }
        return defaultValue;
    }
}