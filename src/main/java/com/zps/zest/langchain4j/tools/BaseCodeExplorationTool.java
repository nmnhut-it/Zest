package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for code exploration tools.
 */
public abstract class BaseCodeExplorationTool implements CodeExplorationTool {
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
            return doExecute(parameters);
        } catch (Exception e) {
            // Log the error for debugging
            String errorMessage = String.format("Error executing tool '%s': %s", 
                name, 
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            
            // Include stack trace in development/debug mode
            if (project != null && project.isDefault()) {
                StringBuilder sb = new StringBuilder(errorMessage);
                sb.append("\nStack trace:\n");
                for (StackTraceElement element : e.getStackTrace()) {
                    sb.append("  at ").append(element.toString()).append("\n");
                    // Limit stack trace to first 5 elements to avoid too much noise
                    if (sb.length() > 1000) break;
                }
                errorMessage = sb.toString();
            }
            
            return ToolResult.error(errorMessage);
        }
    }

    /**
     * Executes the tool with the given parameters.
     */
    protected abstract ToolResult doExecute(JsonObject parameters);

    /**
     * Helper method to get required string parameter.
     */
    protected String getRequiredString(JsonObject parameters, String key) {
        if (!parameters.has(key) || parameters.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Required parameter '" + key + "' is missing");
        }
        return parameters.get(key).getAsString();
    }

    /**
     * Helper method to get optional string parameter.
     */
    protected String getOptionalString(JsonObject parameters, String key, String defaultValue) {
        if (!parameters.has(key) || parameters.get(key).isJsonNull()) {
            return defaultValue;
        }
        return parameters.get(key).getAsString();
    }

    /**
     * Helper method to get optional integer parameter.
     */
    protected int getOptionalInt(JsonObject parameters, String key, int defaultValue) {
        if (!parameters.has(key) || parameters.get(key).isJsonNull()) {
            return defaultValue;
        }
        return parameters.get(key).getAsInt();
    }

    /**
     * Helper method to get optional boolean parameter.
     */
    protected boolean getOptionalBoolean(JsonObject parameters, String key, boolean defaultValue) {
        if (!parameters.has(key) || parameters.get(key).isJsonNull()) {
            return defaultValue;
        }
        return parameters.get(key).getAsBoolean();
    }

    /**
     * Creates basic metadata object.
     */
    protected JsonObject createMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("toolName", name);
        metadata.addProperty("executionTime", System.currentTimeMillis());
        return metadata;
    }
}