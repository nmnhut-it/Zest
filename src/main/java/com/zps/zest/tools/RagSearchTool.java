package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

public class RagSearchTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(RagSearchTool.class);
    private final String apiUrl;
    private final String authToken;
    
    public RagSearchTool(Project project, String apiUrl, String authToken) {
        super("rag_search", "Searches through the knowledge base for relevant information");
        this.apiUrl = apiUrl;
        this.authToken = authToken;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String query = getStringParam(params, "query", "");
        if (query.isEmpty()) {
            return "Error: Search query is required";
        }
        
        int topK = getIntParam(params, "top_k", 3);
        
        return searchKnowledgeBase(query, topK);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("query", "How does the ToolParser work?");
        params.addProperty("top_k", 3);
        return params;
    }
    
    private int getIntParam(JsonObject params, String paramName, int defaultValue) {
        if (params != null && params.has(paramName)) {
            try {
                return params.get(paramName).getAsInt();
            } catch (Exception e) {
                LOG.warn("Failed to parse int parameter: " + paramName);
            }
        }
        return defaultValue;
    }

    private String searchKnowledgeBase(String query, int topK) {
        try {
            URL url = new URL(apiUrl + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setDoOutput(true);
            
            // Create the request payload
            JsonObject payload = new JsonObject();
            payload.addProperty("model", "gpt-4-turbo"); // Use appropriate model
            
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", query);
            messages.add(message);
            payload.add("messages", messages);
            
            // Add search parameters
            JsonArray files = new JsonArray();
            // You can reference a specific collection
            JsonObject collection = new JsonObject();
            collection.addProperty("type", "collection");
            collection.addProperty("id", "your-collection-id"); // Replace with actual collection ID
            files.add(collection);
            payload.add("files", files);
            
            // Write the payload
            try (var os = conn.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // Process the response
            StringBuilder responseBuilder = new StringBuilder();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        responseBuilder.append(responseLine);
                    }
                }
                
                // Extract text from response
                String responseText = extractContentFromResponse(responseBuilder.toString());
                
                return "### Information from Knowledge Base\n" + responseText;
            } else {
                return "Error searching knowledge base: " + conn.getResponseCode();
            }
        } catch (IOException e) {
            LOG.error("Error searching knowledge base", e);
            return "Error searching knowledge base: " + e.getMessage();
        }
    }
    
    private String extractContentFromResponse(String jsonResponse) {
        try {
            JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (jsonObject.has("choices") && jsonObject.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = jsonObject.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                    return choice.getAsJsonObject("message").get("content").getAsString();
                }
            }
            return "No content found in response";
        } catch (Exception e) {
            LOG.error("Error parsing response", e);
            return "Error parsing response: " + e.getMessage();
        }
    }
}