package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for code exploration tools.
 */
public abstract class BaseCodeExplorationTool implements CodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(BaseCodeExplorationTool.class);
    
    protected final Project project;
    private final String name;
    private final String description;
    
    protected BaseCodeExplorationTool(@NotNull Project project, String name, String description) {
        this.project = project;
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
    public ToolResult execute(JsonObject parameters) {
        try {
            LOG.debug("Executing tool: " + name + " with parameters: " + parameters);
            return doExecute(parameters);
        } catch (Exception e) {
            LOG.error("Error executing tool " + name, e);
            return ToolResult.error("Error: " + e.getMessage());
        }
    }
    
    /**
     * Implementation method to be overridden by subclasses.
     */
    protected abstract ToolResult doExecute(JsonObject parameters);
    
    /**
     * Helper method to get a required string parameter.
     */
    protected String getRequiredString(JsonObject params, String paramName) {
        if (!params.has(paramName)) {
            throw new IllegalArgumentException("Missing required parameter: " + paramName);
        }
        JsonElement element = params.get(paramName);
        if (element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Parameter must be a string: " + paramName);
        }
        return element.getAsString();
    }
    
    /**
     * Helper method to get an optional string parameter.
     */
    protected String getOptionalString(JsonObject params, String paramName, String defaultValue) {
        if (!params.has(paramName)) {
            return defaultValue;
        }
        JsonElement element = params.get(paramName);
        if (element.isJsonNull() || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        return element.getAsString();
    }
    
    /**
     * Helper method to get an optional integer parameter.
     */
    protected int getOptionalInt(JsonObject params, String paramName, int defaultValue) {
        if (!params.has(paramName)) {
            return defaultValue;
        }
        JsonElement element = params.get(paramName);
        if (element.isJsonNull() || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        return element.getAsInt();
    }
    
    /**
     * Helper method to create metadata JSON object.
     */
    protected JsonObject createMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("tool", getName());
        metadata.addProperty("timestamp", System.currentTimeMillis());
        return metadata;
    }
}
