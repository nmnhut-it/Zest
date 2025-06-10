package com.zps.zest.langchain4j.agent.network;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;

/**
 * Generates OpenAPI specification for the code exploration tools.
 */
public class OpenApiGenerator {
    
    private final Project project;
    private final int port;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public OpenApiGenerator(Project project, int port) {
        this.project = project;
        this.port = port;
    }
    
    public JsonObject generateOpenApiSpec() {
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.1.0");
        
        // Info section
        JsonObject info = new JsonObject();
        info.addProperty("title", "Zest Code Explorer");
        info.addProperty("description", "Zest Code Explorer MCP Server");
        info.addProperty("version", "1.1.0");
        spec.add("info", info);
        
        // Servers
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", "/zest");
        servers.add(server);
        spec.add("servers", servers);
        
        // Paths and Components
        JsonObject paths = new JsonObject();
        JsonObject components = createComponents();
        JsonObject schemas = components.getAsJsonObject("schemas");
        
        CodeExplorationToolRegistry registry = project.getService(CodeExplorationToolRegistry.class);
        
        for (CodeExplorationTool tool : registry.getAllTools()) {
            // Create path
            JsonObject path = createPathForTool(tool, schemas);
            paths.add("/" + tool.getName(), path);
            
            // Create schema for this tool's form model
            String formModelName = tool.getName() + "_form_model";
            JsonObject formSchema = createFormSchema(tool);
            schemas.add(formModelName, formSchema);
        }
        
        spec.add("paths", paths);
        spec.add("components", components);
        
        return spec;
    }
    
    private JsonObject createPathForTool(CodeExplorationTool tool, JsonObject schemas) {
        JsonObject path = new JsonObject();
        JsonObject post = new JsonObject();
        
        // Extract clean description
        String cleanDescription = extractCleanDescription(tool.getDescription());
        
        post.addProperty("summary", cleanDescription);
        post.addProperty("operationId", "tool_" + tool.getName() + "_post");
        
        // Request body (only if tool has parameters)
        JsonObject toolSchema = tool.getParameterSchema();
        if (toolSchema.has("properties") && 
            toolSchema.getAsJsonObject("properties").size() > 0) {
            JsonObject requestBody = new JsonObject();
            JsonObject content = new JsonObject();
            JsonObject jsonContent = new JsonObject();
            JsonObject schema = new JsonObject();
            schema.addProperty("$ref", "#/components/schemas/" + tool.getName() + "_form_model");
            jsonContent.add("schema", schema);
            content.add("application/json", jsonContent);
            requestBody.add("content", content);
            requestBody.addProperty("required", true);
            post.add("requestBody", requestBody);
        }
        
        // Responses
        JsonObject responses = new JsonObject();
        
        // 200 response
        JsonObject success = new JsonObject();
        success.addProperty("description", "Successful Response");
        JsonObject successContent = new JsonObject();
        JsonObject successJson = new JsonObject();
        JsonObject responseSchema = new JsonObject();
        responseSchema.addProperty("title", "Response Tool " + formatTitle(tool.getName()) + " Post");
        successJson.add("schema", responseSchema);
        successContent.add("application/json", successJson);
        success.add("content", successContent);
        responses.add("200", success);
        
        // 422 response (only if has parameters)
        if (toolSchema.has("properties") && 
            toolSchema.getAsJsonObject("properties").size() > 0) {
            JsonObject validationError = new JsonObject();
            validationError.addProperty("description", "Validation Error");
            JsonObject errorContent = new JsonObject();
            JsonObject errorJson = new JsonObject();
            JsonObject errorSchema = new JsonObject();
            errorSchema.addProperty("$ref", "#/components/schemas/HTTPValidationError");
            errorJson.add("schema", errorSchema);
            errorContent.add("application/json", errorJson);
            validationError.add("content", errorContent);
            responses.add("422", validationError);
        }
        
        post.add("responses", responses);
        path.add("post", post);
        
        return path;
    }
    
    private JsonObject createFormSchema(CodeExplorationTool tool) {
        JsonObject schema = new JsonObject();
        JsonObject toolSchema = tool.getParameterSchema();
        
        // Copy properties from tool schema
        if (toolSchema.has("properties")) {
            JsonObject properties = new JsonObject();
            JsonObject toolProps = toolSchema.getAsJsonObject("properties");
            
            for (String propName : toolProps.keySet()) {
                JsonObject prop = toolProps.getAsJsonObject(propName);
                JsonObject newProp = new JsonObject();
                
                // Copy type
                if (prop.has("type")) {
                    newProp.addProperty("type", prop.get("type").getAsString());
                }
                
                // Add title (capitalized property name)
                newProp.addProperty("title", capitalize(propName));
                
                // Copy description (cleaned)
                if (prop.has("description")) {
                    String desc = prop.get("description").getAsString();
                    // Clean up parameter descriptions too
                    int parenIndex = desc.indexOf("(default:");
                    if (parenIndex > 0) {
                        desc = desc.substring(0, parenIndex).trim();
                    }
                    newProp.addProperty("description", desc);
                }
                
                // Copy default if exists
                if (prop.has("default")) {
                    newProp.add("default", prop.get("default"));
                }
                
                // Copy other constraints
                if (prop.has("minimum")) {
                    newProp.add("minimum", prop.get("minimum"));
                }
                if (prop.has("maximum")) {
                    newProp.add("maximum", prop.get("maximum"));
                }
                
                properties.add(propName, newProp);
            }
            
            schema.add("properties", properties);
        }
        
        schema.addProperty("type", "object");
        
        // Copy required array
        if (toolSchema.has("required")) {
            schema.add("required", toolSchema.get("required"));
        }
        
        schema.addProperty("title", tool.getName() + "_form_model");
        
        return schema;
    }
    
    private JsonObject createComponents() {
        JsonObject components = new JsonObject();
        JsonObject schemas = new JsonObject();
        
        // HTTPValidationError schema
        JsonObject validationError = new JsonObject();
        JsonObject validationProps = new JsonObject();
        JsonObject detail = new JsonObject();
        JsonObject items = new JsonObject();
        items.addProperty("$ref", "#/components/schemas/ValidationError");
        detail.add("items", items);
        detail.addProperty("type", "array");
        detail.addProperty("title", "Detail");
        validationProps.add("detail", detail);
        validationError.add("properties", validationProps);
        validationError.addProperty("type", "object");
        validationError.addProperty("title", "HTTPValidationError");
        schemas.add("HTTPValidationError", validationError);
        
        // ValidationError schema
        JsonObject valError = new JsonObject();
        JsonObject valProps = new JsonObject();
        
        // loc property
        JsonObject loc = new JsonObject();
        JsonObject locItems = new JsonObject();
        JsonArray anyOf = new JsonArray();
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");
        JsonObject intType = new JsonObject();
        intType.addProperty("type", "integer");
        anyOf.add(stringType);
        anyOf.add(intType);
        locItems.add("anyOf", anyOf);
        loc.add("items", locItems);
        loc.addProperty("type", "array");
        loc.addProperty("title", "Location");
        valProps.add("loc", loc);
        
        // msg property
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "string");
        msg.addProperty("title", "Message");
        valProps.add("msg", msg);
        
        // type property
        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        type.addProperty("title", "Error Type");
        valProps.add("type", type);
        
        valError.add("properties", valProps);
        valError.addProperty("type", "object");
        JsonArray required = new JsonArray();
        required.add("loc");
        required.add("msg");
        required.add("type");
        valError.add("required", required);
        valError.addProperty("title", "ValidationError");
        schemas.add("ValidationError", valError);
        
        components.add("schemas", schemas);
        return components;
    }
    
    private String extractCleanDescription(String fullDescription) {
        String cleanDesc = fullDescription;
        
        // Find where tips/examples/params start
        int tipsIndex = fullDescription.indexOf("Tips for");
        int examplesIndex = fullDescription.indexOf("Examples:");
        int paramsIndex = fullDescription.indexOf("Params:");
        
        // Find the earliest occurrence
        int cutoffIndex = fullDescription.length();
        if (tipsIndex > 0) cutoffIndex = Math.min(cutoffIndex, tipsIndex);
        if (examplesIndex > 0) cutoffIndex = Math.min(cutoffIndex, examplesIndex);
        if (paramsIndex > 0) cutoffIndex = Math.min(cutoffIndex, paramsIndex);
        
        // Extract clean description
        cleanDesc = fullDescription.substring(0, cutoffIndex).trim();
        
        // Remove trailing periods and whitespace
        cleanDesc = cleanDesc.replaceAll("\\.\\s*$", "").trim();
        
        return cleanDesc;
    }
    
    private String formatTitle(String toolName) {
        // Convert snake_case to Title Case
        String[] parts = toolName.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            result.append(capitalize(part)).append(" ");
        }
        return result.toString().trim();
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}
