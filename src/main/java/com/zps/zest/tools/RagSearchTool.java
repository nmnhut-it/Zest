package com.zps.zest.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.PersistentRagManager;
import com.zps.zest.RagManagerProjectListener;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enhanced RAG Search Tool that works with the persistent tracking system.
 */
public class RagSearchTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(RagSearchTool.class);

    private final String apiUrl;
    private final String authToken;
    private final Project project;

    // Connection timeout values
    private static final int CONNECTION_TIMEOUT_MS = 10000; // 10 seconds
    private static final int READ_TIMEOUT_MS = 60000; // 60 seconds

    public RagSearchTool(Project project, String apiUrl, String authToken) {
        super("rag_search", "Searches through the knowledge base for relevant information");
        this.project = project;
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

        // Check if we have files in the knowledge base
        PersistentRagManager manager = RagManagerProjectListener.getManager(project);
        if (manager != null) {
            List<PersistentRagManager.TrackedFileInfo> trackedFiles = manager.getTrackedFiles();
            if (trackedFiles.isEmpty()) {
                return "No files found in the knowledge base. Please add files using the 'Manage RAG Knowledge Base' action.";
            }

            // Get file IDs for search
            List<String> fileIds = trackedFiles.stream()
                    .map(PersistentRagManager.TrackedFileInfo::getFileId)
                    .collect(Collectors.toList());

            return searchKnowledgeBase(query, topK, fileIds);
        } else {
            return "RAG system is not initialized. Please add files using the 'Manage RAG Knowledge Base' action.";
        }
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("query", "How does the ToolParser work?");
        params.addProperty("top_k", 3);
        return params;
    }

    /**
     * Retrieves information from the knowledge base based on the query.
     */
    private String searchKnowledgeBase(String query, int topK, List<String> fileIds) {
        try {
            HttpURLConnection conn = setupConnection(apiUrl + "/chat/completions");

            // Create the request payload
            JsonObject payload = buildRequestPayload(query, topK, fileIds);

            // Send the request
            sendRequest(conn, payload.toString());

            // Process the response
            String response = getResponse(conn);

            // Check for successful response
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String contentFromResponse = extractContentFromResponse(response);
                return formatSearchResults(contentFromResponse, query);
            } else {
                LOG.warn("Error searching knowledge base: " + conn.getResponseCode() + "\n" + response);
                return "Error searching knowledge base: " + conn.getResponseCode();
            }
        } catch (IOException e) {
            LOG.error("Error searching knowledge base", e);
            return "Error searching knowledge base: " + e.getMessage();
        }
    }

    /**
     * Sets up the HTTP connection with proper headers and timeouts.
     */
    @NotNull
    private HttpURLConnection setupConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        if (authToken != null && !authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        return conn;
    }

    /**
     * Builds the JSON payload for the RAG request.
     */
    private JsonObject buildRequestPayload(String query, int topK, List<String> fileIds) {
        JsonObject payload = new JsonObject();

        // Set the model - use GPT-4 or similar advanced model for RAG
        payload.addProperty("model", "gpt-4-turbo");

        // Create messages array with the user query
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");

        // Enhance the prompt to get better RAG results
        String enhancedPrompt = buildEnhancedPrompt(query);
        message.addProperty("content", enhancedPrompt);
        messages.add(message);

        payload.add("messages", messages);

        // Add search parameters
        if (topK > 0) {
            payload.addProperty("top_k", topK);
        }

        // Add file references
        if (fileIds != null && !fileIds.isEmpty()) {
            JsonArray files = new JsonArray();
            for (String fileId : fileIds) {
                JsonObject file = new JsonObject();
                file.addProperty("type", "file");
                file.addProperty("id", fileId);
                files.add(file);
            }
            payload.add("files", files);
        }

        return payload;
    }

    /**
     * Enhances the user's query to get better RAG results.
     */
    private String buildEnhancedPrompt(String query) {
        return "I need information from the project knowledge base about the following topic:\n\n" +
                query + "\n\n" +
                "Please provide detailed and accurate information based on the context from the codebase. " +
                "If the information isn't available in the context, please indicate that clearly. " +
                "Include relevant code examples when appropriate.";
    }

    /**
     * Sends the JSON request to the API endpoint.
     */
    private void sendRequest(HttpURLConnection conn, String jsonPayload) throws IOException {
        try (var os = conn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    }

    /**
     * Reads the response from the connection.
     */
    private String getResponse(HttpURLConnection conn) throws IOException {
        StringBuilder responseBuilder = new StringBuilder();

        try {
            // Try to get successful response
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        responseBuilder.append(responseLine);
                    }
                }
            } else {
                // Read error stream
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        responseBuilder.append(responseLine);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error reading response", e);
            return "Error reading response: " + e.getMessage();
        }

        return responseBuilder.toString();
    }

    /**
     * Extracts the content from the LLM response.
     */
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

    /**
     * Formats the search results for better readability.
     */
    private String formatSearchResults(String content, String originalQuery) {
        StringBuilder formatted = new StringBuilder();

        formatted.append("### Knowledge Base Results for: \"").append(originalQuery).append("\"\n\n");

        // Add the content
        formatted.append(content);

        // Add a note about the source
        formatted.append("\n\n---\n");
        formatted.append("*Note: This information was retrieved from your project's knowledge base.*");

        return formatted.toString();
    }

    /**
     * Gets an integer parameter with a default value.
     */
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
}