package com.zps.zest.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.List;
import java.util.Map;

/**
 * Simple OpenAPI 3.1.0 schema generator from langchain4j ToolSpecifications.
 * No frameworks - just Gson + manual JSON building.
 */
public class SimpleOpenAPIGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Convert camelCase to snake_case
     */
    private static String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Generate OpenAPI 3.1.0 JSON schema from tool specifications.
     */
    public static String generateSchema(String projectName, String projectPath, List<ToolSpecification> toolSpecs) {
        JsonObject openapi = new JsonObject();

        // OpenAPI version
        openapi.addProperty("openapi", "3.1.0");

        // Info
        JsonObject info = new JsonObject();
        info.addProperty("title", "Zest Code Tools - " + projectName);
        info.addProperty("version", "1.0.0");
        info.addProperty("description", "IntelliJ code exploration and modification tools for project: " + projectPath);
        openapi.add("info", info);

        // Servers
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", "http://localhost:8765");
        server.addProperty("description", "Local tool server");
        servers.add(server);
        openapi.add("servers", servers);

        // Paths - one for each tool (convert to snake_case)
        JsonObject paths = new JsonObject();
        for (ToolSpecification tool : toolSpecs) {
            String pathKey = "/" + toSnakeCase(tool.name());
            paths.add(pathKey, createPathItem(tool, projectPath));
        }
        openapi.add("paths", paths);

        return GSON.toJson(openapi);
    }

    /**
     * Create a path item (POST operation) for a tool.
     */
    private static JsonObject createPathItem(ToolSpecification tool, String projectPath) {
        JsonObject pathItem = new JsonObject();
        JsonObject post = new JsonObject();

        // Operation ID and description with project path prefix
        String descriptionWithProject = "[Project: " + projectPath + "] " + tool.description();
        post.addProperty("operationId", tool.name());
        post.addProperty("summary", descriptionWithProject);
        post.addProperty("description", descriptionWithProject);

        // Tags
        JsonArray tags = new JsonArray();
        tags.add("Tools");
        post.add("tags", tags);

        // Request body
        JsonObjectSchema parameters = tool.parameters();
        if (parameters != null) {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("required", true);

            JsonObject content = new JsonObject();
            JsonObject appJson = new JsonObject();
            JsonObject schema = convertToolParametersToJsonSchema(parameters);
            appJson.add("schema", schema);
            content.add("application/json", appJson);

            requestBody.add("content", content);
            post.add("requestBody", requestBody);
        }

        // Responses
        JsonObject responses = new JsonObject();
        JsonObject response200 = new JsonObject();
        response200.addProperty("description", "Successful operation");

        JsonObject responseContent = new JsonObject();
        JsonObject textPlain = new JsonObject();
        JsonObject responseSchema = new JsonObject();
        responseSchema.addProperty("type", "string");
        textPlain.add("schema", responseSchema);
        responseContent.add("text/plain", textPlain);

        response200.add("content", responseContent);
        responses.add("200", response200);
        post.add("responses", responses);

        pathItem.add("post", post);
        return pathItem;
    }

    /**
     * Convert langchain4j ToolParameters to JSON Schema.
     */
    private static JsonObject convertToolParametersToJsonSchema(JsonObjectSchema toolParameters) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        // Extract properties from JsonObjectSchema
        try {
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();

            // Get properties map
            Map<String, JsonSchemaElement> props = toolParameters.properties();
            if (props != null) {
                for (Map.Entry<String, JsonSchemaElement> entry : props.entrySet()) {
                    String propName = entry.getKey();
                    JsonSchemaElement propDef = entry.getValue();

                    JsonObject propSchema = convertPropertyToJsonSchema(propDef);
                    properties.add(propName, propSchema);

                    // Add to required if not explicitly optional
                    if (propDef != null) {
                        required.add(propName);
                    }
                }
            }

            schema.add("properties", properties);
            if (required.size() > 0) {
                schema.add("required", required);
            }
        } catch (Exception e) {
            // Fallback on error
            schema.add("properties", new JsonObject());
        }

        return schema;
    }

    /**
     * Convert a single property to JSON Schema.
     */
    private static JsonObject convertPropertyToJsonSchema(JsonSchemaElement prop) {
        JsonObject schema = new JsonObject();

        if (prop == null) {
            schema.addProperty("type", "string");
            return schema;
        }

        // Add description if available
        if (prop.description() != null) {
            schema.addProperty("description", prop.description());
        }

        // Handle different schema types
        if (prop instanceof JsonStringSchema stringSchema) {
            schema.addProperty("type", "string");
            // Add string-specific properties if available
        } else if (prop instanceof JsonIntegerSchema intSchema) {
            schema.addProperty("type", "integer");
            // Add number constraints if available
        } else if (prop instanceof JsonNumberSchema numberSchema) {
            schema.addProperty("type", "number");
            // Add number constraints if available
        } else if (prop instanceof JsonBooleanSchema boolSchema) {
            schema.addProperty("type", "boolean");
        } else if (prop instanceof JsonArraySchema arraySchema) {
            schema.addProperty("type", "array");
            // Add items schema
            JsonSchemaElement items = arraySchema.items();
            if (items != null) {
                schema.add("items", convertPropertyToJsonSchema(items));
            }
        } else if (prop instanceof JsonObjectSchema objectSchema) {
            schema.addProperty("type", "object");
            // Add nested properties
            Map<String, JsonSchemaElement> props = objectSchema.properties();
            if (props != null && !props.isEmpty()) {
                JsonObject properties = new JsonObject();
                for (Map.Entry<String, JsonSchemaElement> entry : props.entrySet()) {
                    properties.add(entry.getKey(), convertPropertyToJsonSchema(entry.getValue()));
                }
                schema.add("properties", properties);
            }
        } else if (prop instanceof JsonEnumSchema enumSchema) {
            // Handle enum - extract values
            schema.addProperty("type", "string");
            JsonArray enumValues = new JsonArray();
            for (Object value : enumSchema.enumValues()) {
                enumValues.add(value.toString());
            }
            schema.add("enum", enumValues);
        } else {
            // Default fallback
            schema.addProperty("type", "string");
        }

        return schema;
    }
}
