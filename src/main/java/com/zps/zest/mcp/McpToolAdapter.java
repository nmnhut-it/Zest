package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.AgentToolRegistry;
import com.zps.zest.tools.AgentTool;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter that converts Zest AgentTools to MCP tool definitions.
 * This class bridges the gap between Zest's existing tool system and the MCP protocol.
 */
public class McpToolAdapter {
    private static final Logger LOG = Logger.getInstance(McpToolAdapter.class);
    private final AgentToolRegistry toolRegistry;
    private final Gson gson = new Gson();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public McpToolAdapter(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    /**
     * Converts all registered Zest tools to MCP tool definitions.
     * 
     * @return A list of MCP tool definitions
     */
    public List<Tool> convertToolsToMcpDefinitions() {
        List<Tool> mcpTools = new ArrayList<>();
        
        for (String toolName : toolRegistry.getToolNames()) {
            AgentTool agentTool = toolRegistry.getTool(toolName);
            
            if (agentTool != null) {
                Tool toolDefinition = convertToMcpDefinition(agentTool);
                mcpTools.add(toolDefinition);
            }
        }
        
        return mcpTools;
    }
    
    /**
     * Converts a single Zest AgentTool to an MCP tool definition.
     * 
     * @param agentTool The Zest tool to convert
     * @return An MCP tool definition
     */
    private Tool convertToMcpDefinition(AgentTool agentTool) {
        // Create a simple JSON schema from the example parameters
        JsonObject exampleParams = agentTool.getExampleParams();
        String schemaJson = createJsonSchemaFromExample(exampleParams);
        
        return new Tool(
                agentTool.getName(),
                agentTool.getDescription(),
                schemaJson
        );
    }
    
    /**
     * Creates a JSON schema string from example parameters.
     * 
     * @param exampleParams Example parameters
     * @return JSON schema as a string
     */
    private String createJsonSchemaFromExample(JsonObject exampleParams) {
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        if (exampleParams != null) {
            for (Map.Entry<String, JsonElement> entry : exampleParams.entrySet()) {
                String paramName = entry.getKey();
                JsonElement value = entry.getValue();
                
                // Add to required list
                required.add(paramName);
                
                // Determine type
                Map<String, Object> propDef = new HashMap<>();
                
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isString()) {
                        propDef.put("type", "string");
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        propDef.put("type", "boolean");
                    } else if (value.getAsJsonPrimitive().isNumber()) {
                        propDef.put("type", "number");
                    }
                } else if (value.isJsonArray()) {
                    propDef.put("type", "array");
                } else if (value.isJsonObject()) {
                    propDef.put("type", "object");
                }
                
                properties.put(paramName, propDef);
            }
        }
        
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            LOG.error("Failed to create JSON schema", e);
            return "{}";
        }
    }
    
    /**
     * Executes an MCP tool using the existing Zest tool registry.
     * 
     * @param request The MCP tool execution request
     * @return The MCP tool execution result
     */
    public CallToolResult executeTool(CallToolRequest request) {
        AgentTool tool = toolRegistry.getTool(request.name());
        if (tool == null) {
            return new CallToolResult("Unknown tool: " + request.name(), true);
        }
        
        try {
            // Convert Map to JsonObject for Zest's tool API
            JsonObject jsonParams = new JsonObject();
            Map<String, Object> arguments = request.arguments();
            
            if (arguments != null) {
                for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    
                    if (value instanceof String) {
                        jsonParams.addProperty(key, (String) value);
                    } else if (value instanceof Number) {
                        jsonParams.addProperty(key, (Number) value);
                    } else if (value instanceof Boolean) {
                        jsonParams.addProperty(key, (Boolean) value);
                    } else if (value != null) {
                        String json = gson.toJson(value);
                        jsonParams.add(key, JsonParser.parseString(json));
                    }
                }
            }
            
            // Execute tool
            String result = tool.execute(jsonParams);
            
            // Return result
            return CallToolResult.builder()
                    .addTextContent(result)
                    .isError(false)
                    .build();
            
        } catch (Exception e) {
            LOG.error("Error executing tool: " + request.name(), e);
            return new CallToolResult("Error executing tool: " + e.getMessage(), true);
        }
    }
}