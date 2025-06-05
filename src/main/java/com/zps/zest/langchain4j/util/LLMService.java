package com.zps.zest.langchain4j.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Simple utility service for making LLM API calls.
 * This is a lightweight alternative to LlmApiCallStage that doesn't require CodeContext.
 */
@Service(Service.Level.PROJECT)
public final class LLMService {
    
    private static final Logger LOG = Logger.getInstance(LLMService.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final ConfigurationManager config;
    
    // Configuration constants
    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    public LLMService(@NotNull Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
        LOG.info("Initialized LLMService for project: " + project.getName());
    }

    /**
     * Simple synchronous call to LLM with a prompt.
     * 
     * @param prompt The prompt to send to the LLM
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String query(@NotNull String prompt) {
        return query(prompt, "qwen3:32b");
    }
    
    /**
     * Simple synchronous call to LLM with a prompt and specific model.
     * 
     * @param prompt The prompt to send to the LLM
     * @param model The model to use (overrides config)
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String query(@NotNull String prompt, @NotNull String model) {
        try {
            return queryWithRetry(prompt, model, MAX_RETRY_ATTEMPTS);
        } catch (Exception e) {
            LOG.error("Failed to query LLM", e);
            return null;
        }
    }
    
    /**
     * Asynchronous call to LLM.
     * 
     * @param prompt The prompt to send to the LLM
     * @return CompletableFuture with the response
     */
    @NotNull
    public CompletableFuture<String> queryAsync(@NotNull String prompt) {
        return queryAsync(prompt, config.getCodeModel());
    }
    
    /**
     * Asynchronous call to LLM with specific model.
     * 
     * @param prompt The prompt to send to the LLM
     * @param model The model to use
     * @return CompletableFuture with the response
     */
    @NotNull
    public CompletableFuture<String> queryAsync(@NotNull String prompt, @NotNull String model) {
        return CompletableFuture.supplyAsync(() -> {
            String result = query(prompt, model);
            if (result == null) {
                throw new CompletionException(new RuntimeException("LLM query failed"));
            }
            return result;
        });
    }
    
    /**
     * Query with custom parameters.
     * 
     * @param params The query parameters
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String queryWithParams(@NotNull LLMQueryParams params) {
        try {
            return queryWithRetry(params.getPrompt(), params.getModel(), params.getMaxRetries());
        } catch (Exception e) {
            LOG.error("Failed to query LLM with params", e);
            return null;
        }
    }
    
    /**
     * Internal method to query with retry logic.
     */
    private String queryWithRetry(String prompt, String model, int maxRetries) throws IOException {
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();
        
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("LLM API URL not configured");
        }
        
        IOException lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return executeQuery(apiUrl, model, authToken, prompt);
            } catch (IOException e) {
                lastException = e;
                LOG.warn("LLM query attempt " + attempt + " failed: " + e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }
        }
        
        throw new IOException("All retry attempts failed", lastException);
    }
    
    /**
     * Executes the actual HTTP request to the LLM API.
     */
    private String executeQuery(String apiUrl, String model, String authToken, String prompt) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            // Setup connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            
            // Prepare request body
            String requestBody = createRequestBody(apiUrl, model, prompt);
            
            // Send request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // Check response code
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(connection);
                throw new IOException("API returned error code " + responseCode + ": " + error);
            }
            
            // Read response
            return readResponse(connection);
            
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * Creates the request body based on the API type.
     */
    private String createRequestBody(String apiUrl, String model, String prompt) {
        // Determine API type based on URL
        if (isOpenWebUIApi(apiUrl)) {
            return createOpenWebUIRequestBody(model, prompt);
        } else {
            return createOllamaRequestBody(model, prompt);
        }
    }
    
    /**
     * Creates request body for OpenWebUI/Zingplay API.
     */
    private String createOpenWebUIRequestBody(String model, String prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", false);
        root.addProperty("custom_tool", "Zest|LLM_SERVICE");
        
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        root.add("messages", messages);
        
        return GSON.toJson(root);
    }
    
    /**
     * Creates request body for Ollama API.
     */
    private String createOllamaRequestBody(String model, String prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("prompt", prompt);
        root.addProperty("stream", false);
        
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", 32000);
        root.add("options", options);
        
        return GSON.toJson(root);
    }
    
    /**
     * Reads the successful response from the connection.
     */
    private String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            String jsonResponse = response.toString();
            return parseResponse(jsonResponse, connection.getURL().toString());
        }
    }
    
    /**
     * Reads error response from the connection.
     */
    private String readErrorResponse(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
            
            StringBuilder error = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line);
            }
            return error.toString();
            
        } catch (IOException e) {
            return "Unable to read error response: " + e.getMessage();
        }
    }
    
    /**
     * Parses the response based on API type.
     */
    private String parseResponse(String jsonResponse, String apiUrl) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(jsonResponse).getAsJsonObject();
            
            if (isOpenWebUIApi(apiUrl)) {
                // OpenWebUI format
                if (response.has("choices") && response.getAsJsonArray("choices").size() > 0) {
                    JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject message = choice.getAsJsonObject("message");
                        if (message.has("content")) {
                            return message.get("content").getAsString();
                        }
                    }
                }
            } else {
                // Ollama format
                if (response.has("response")) {
                    return response.get("response").getAsString();
                }
            }
            
            throw new IOException("Unexpected response format: " + jsonResponse);
            
        } catch (Exception e) {
            throw new IOException("Failed to parse response: " + e.getMessage(), e);
        }
    }
    
    /**
     * Checks if the API URL is for OpenWebUI/Zingplay.
     */
    private boolean isOpenWebUIApi(String apiUrl) {
        return apiUrl.contains("openwebui") || 
               apiUrl.contains("chat.zingplay") || 
               apiUrl.contains("talk.zingplay");
    }
    
    /**
     * Checks if the LLM service is properly configured.
     */
    public boolean isConfigured() {
        String apiUrl = config.getApiUrl();
        return apiUrl != null && !apiUrl.isEmpty();
    }
    
    /**
     * Gets the current configuration status.
     */
    public LLMConfigStatus getConfigStatus() {
        LLMConfigStatus status = new LLMConfigStatus();
        status.setConfigured(isConfigured());
        status.setApiUrl(config.getApiUrl());
        status.setModel(config.getCodeModel());
        status.setHasAuthToken(config.getAuthToken() != null && !config.getAuthToken().isEmpty());
        return status;
    }
    
    /**
     * Parameters for LLM queries.
     */
    public static class LLMQueryParams {
        private final String prompt;
        private String model;
        private int maxRetries = MAX_RETRY_ATTEMPTS;
        private long timeoutMs = READ_TIMEOUT_MS;
        
        public LLMQueryParams(@NotNull String prompt) {
            this.prompt = prompt;
        }
        
        public LLMQueryParams withModel(@NotNull String model) {
            this.model = model;
            return this;
        }
        
        public LLMQueryParams withMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public LLMQueryParams withTimeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        
        // Getters
        public String getPrompt() { return prompt; }
        public String getModel() { return model; }
        public int getMaxRetries() { return maxRetries; }
        public long getTimeoutMs() { return timeoutMs; }
    }
    
    /**
     * Configuration status for the LLM service.
     */
    public static class LLMConfigStatus {
        private boolean configured;
        private String apiUrl;
        private String model;
        private boolean hasAuthToken;
        
        // Getters and setters
        public boolean isConfigured() { return configured; }
        public void setConfigured(boolean configured) { this.configured = configured; }
        
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public boolean hasAuthToken() { return hasAuthToken; }
        public void setHasAuthToken(boolean hasAuthToken) { this.hasAuthToken = hasAuthToken; }
    }
}
