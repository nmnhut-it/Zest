package com.zps.zest.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Registry for MCP tools with lazy loading support.
 *
 * Instead of registering all tools with MCP (consuming ~13,500 tokens),
 * this registry stores tool definitions and exposes them via 3 meta-tools:
 * - getAvailableTools: Returns compact index (~200 tokens)
 * - getToolSchema: Returns full schema for one tool on-demand
 * - callTool: Invokes any registered tool by name
 *
 * This achieves ~97% token savings for initial context.
 */
public class ToolRegistry {

    /** Tool categories for grouping */
    public enum Category {
        IDE("IDE tools for navigation and file operations"),
        TESTING("Test generation and validation tools"),
        COVERAGE("Code coverage analysis tools"),
        REFACTORING("Code refactoring and analysis tools"),
        BUILD("Build system and project tools"),
        INTERACTION("User interaction tools");

        private final String description;

        Category(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** Tool definition with metadata and handler */
    public record ToolDefinition(
        String name,
        Category category,
        String summary,
        String schema,
        BiFunction<Object, Map<String, Object>, McpSchema.CallToolResult> handler
    ) {}

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Register a tool with the registry.
     */
    public void register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Register a tool with all parameters.
     */
    public void register(
            String name,
            Category category,
            String summary,
            String schema,
            BiFunction<Object, Map<String, Object>, McpSchema.CallToolResult> handler
    ) {
        tools.put(name, new ToolDefinition(name, category, summary, schema, handler));
    }

    /**
     * Get compact list of available tools grouped by category.
     * Returns ~200 tokens instead of ~13,500 for full schemas.
     */
    public String getAvailableToolsJson() {
        JsonObject result = new JsonObject();

        // Group tools by category
        Map<Category, List<ToolDefinition>> grouped = new EnumMap<>(Category.class);
        for (ToolDefinition tool : tools.values()) {
            grouped.computeIfAbsent(tool.category(), k -> new ArrayList<>()).add(tool);
        }

        // Build JSON for each category
        for (Category category : Category.values()) {
            List<ToolDefinition> categoryTools = grouped.get(category);
            if (categoryTools != null && !categoryTools.isEmpty()) {
                JsonArray toolsArray = new JsonArray();
                for (ToolDefinition tool : categoryTools) {
                    JsonObject toolObj = new JsonObject();
                    toolObj.addProperty("name", tool.name());
                    toolObj.addProperty("summary", tool.summary());
                    toolsArray.add(toolObj);
                }
                result.add(category.name(), toolsArray);
            }
        }

        return gson.toJson(result);
    }

    /**
     * Get full schema for a specific tool.
     */
    public String getToolSchema(String toolName) {
        ToolDefinition tool = tools.get(toolName);
        if (tool == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Tool not found: " + toolName);
            error.add("availableTools", gson.toJsonTree(tools.keySet()));
            return gson.toJson(error);
        }

        JsonObject result = new JsonObject();
        result.addProperty("name", tool.name());
        result.addProperty("category", tool.category().name());
        result.addProperty("summary", tool.summary());
        result.add("inputSchema", gson.fromJson(tool.schema(), JsonObject.class));
        return gson.toJson(result);
    }

    /**
     * Get all tools in a category with their full schemas.
     */
    public String getToolsByCategory(String categoryName) {
        Category category;
        try {
            category = Category.valueOf(categoryName.toUpperCase());
        } catch (IllegalArgumentException e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Invalid category: " + categoryName);
            error.add("availableCategories", gson.toJsonTree(
                Arrays.stream(Category.values()).map(Enum::name).toList()
            ));
            return gson.toJson(error);
        }

        JsonObject result = new JsonObject();
        result.addProperty("category", category.name());
        result.addProperty("description", category.getDescription());

        JsonArray toolsArray = new JsonArray();
        for (ToolDefinition tool : tools.values()) {
            if (tool.category() == category) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("name", tool.name());
                toolObj.addProperty("summary", tool.summary());
                toolObj.add("inputSchema", gson.fromJson(tool.schema(), JsonObject.class));
                toolsArray.add(toolObj);
            }
        }
        result.add("tools", toolsArray);

        return gson.toJson(result);
    }

    /**
     * Invoke a tool by name.
     */
    public McpSchema.CallToolResult invoke(
            String toolName,
            Object exchange,
            Map<String, Object> arguments
    ) {
        ToolDefinition tool = tools.get(toolName);
        if (tool == null) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Tool not found: " + toolName +
                    ". Use getAvailableTools() to see available tools.")),
                true
            );
        }

        return tool.handler().apply(exchange, arguments);
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }

    /**
     * Get all tool names.
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }

    /**
     * Get tool count.
     */
    public int size() {
        return tools.size();
    }
}
