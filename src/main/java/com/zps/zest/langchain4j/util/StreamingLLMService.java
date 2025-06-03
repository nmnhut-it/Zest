package com.zps.zest.langchain4j.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streaming version of the LLM service that supports real-time response streaming.
 */
@Service(Service.Level.PROJECT)
public final class StreamingLLMService {
    
    private static final Logger LOG = Logger.getInstance(StreamingLLMService.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final ConfigurationManager config;
    
    // Configuration constants
    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 300_000; // Longer for streaming
    
    public StreamingLLMService(@NotNull Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
        LOG.info("Initialized StreamingLLMService for project: " + project.getName());
    }
    
    /**
     * Streams response from LLM, calling the consumer for each chunk.
     * 
     * @param prompt The prompt to send
     * @param chunkConsumer Consumer called for each response chunk
     * @return CompletableFuture that completes when streaming is done
     */
    @NotNull
    public CompletableFuture<String> streamQuery(@NotNull String prompt, 
                                                  @NotNull Consumer<String> chunkConsumer) {
        return streamQuery(prompt, config.getCodeModel(), chunkConsumer);
    }
    
    /**
     * Streams response from LLM with specific model.
     * 
     * @param prompt The prompt to send
     * @param model The model to use
     * @param chunkConsumer Consumer called for each response chunk
     * @return CompletableFuture with the complete response
     */
    @NotNull
    public CompletableFuture<String> streamQuery(@NotNull String prompt, 
                                                  @NotNull String model,
                                                  @NotNull Consumer<String> chunkConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeStreamingQuery(prompt, model, chunkConsumer);
            } catch (Exception e) {
                LOG.error("Streaming query failed", e);
                throw new RuntimeException("Streaming query failed", e);
            }
        });
    }
    
    /**
     * Streaming query with cancellation support.
     */
    @NotNull
    public StreamingSession createStreamingSession(@NotNull String prompt) {
        return new StreamingSession(prompt, config.getCodeModel());
    }
    
    /**
     * Executes the streaming query.
     */
    private String executeStreamingQuery(String prompt, String model, Consumer<String> chunkConsumer) 
            throws IOException {
        
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();
        
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("LLM API URL not configured");
        }
        
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            // Setup connection
            setupConnection(connection, authToken);
            
            // Prepare and send request
            String requestBody = createStreamingRequestBody(apiUrl, model, prompt);
            sendRequest(connection, requestBody);
            
            // Check response
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(connection);
                throw new IOException("API returned error code " + responseCode + ": " + error);
            }
            
            // Process streaming response
            return processStreamingResponse(connection, apiUrl, chunkConsumer);
            
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * Sets up the HTTP connection for streaming.
     */
    private void setupConnection(HttpURLConnection connection, String authToken) throws IOException {
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        
        if (authToken != null && !authToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
    }
    
    /**
     * Sends the request to the server.
     */
    private void sendRequest(HttpURLConnection connection, String requestBody) throws IOException {
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    }
    
    /**
     * Creates streaming request body.
     */
    private String createStreamingRequestBody(String apiUrl, String model, String prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", true); // Enable streaming
        
        if (isOpenWebUIApi(apiUrl)) {
            root.addProperty("custom_tool", "Zest|STREAMING_LLM_SERVICE");
            
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", prompt);
            
            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            messages.add(message);
            root.add("messages", messages);
        } else {
            // Ollama format
            root.addProperty("prompt", prompt);
            
            JsonObject options = new JsonObject();
            options.addProperty("num_predict", 32000);
            root.add("options", options);
        }
        
        return GSON.toJson(root);
    }
    
    /**
     * Processes the streaming response.
     */
    private String processStreamingResponse(HttpURLConnection connection, String apiUrl, 
                                          Consumer<String> chunkConsumer) throws IOException {
        
        StringBuilder fullResponse = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                String chunk = parseStreamingChunk(line, apiUrl);
                if (chunk != null) {
                    if (chunk.equals("[DONE]")) {
                        break; // End of stream
                    }
                    fullResponse.append(chunk);
                    chunkConsumer.accept(chunk);
                }
            }
        }
        
        return fullResponse.toString();
    }
    
    /**
     * Parses a single streaming chunk.
     */
    private String parseStreamingChunk(String line, String apiUrl) {
        try {
            if (isOpenWebUIApi(apiUrl)) {
                // OpenWebUI SSE format
                if (line.startsWith("data: ")) {
                    String jsonData = line.substring(6).trim();
                    
                    if (jsonData.equals("[DONE]")) {
                        return "[DONE]";
                    }
                    
                    JsonObject data = JsonParser.parseString(jsonData).getAsJsonObject();
                    if (data.has("choices") && data.getAsJsonArray("choices").size() > 0) {
                        JsonObject choice = data.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("delta")) {
                            JsonObject delta = choice.getAsJsonObject("delta");
                            if (delta.has("content")) {
                                return delta.get("content").getAsString();
                            }
                        }
                    }
                }
            } else {
                // Ollama format - direct JSON lines
                JsonObject data = JsonParser.parseString(line).getAsJsonObject();
                if (data.has("response")) {
                    return data.get("response").getAsString();
                }
                if (data.has("done") && data.get("done").getAsBoolean()) {
                    return "[DONE]";
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse streaming chunk: " + line, e);
        }
        
        return null;
    }
    
    /**
     * Reads error response.
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
     * Checks if the API URL is for OpenWebUI/Zingplay.
     */
    private boolean isOpenWebUIApi(String apiUrl) {
        return apiUrl.contains("openwebui") || 
               apiUrl.contains("chat.zingplay") || 
               apiUrl.contains("talk.zingplay");
    }
    
    /**
     * A streaming session that can be cancelled.
     */
    public class StreamingSession {
        private final String prompt;
        private final String model;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final StringBuilder response = new StringBuilder();
        
        private StreamingSession(String prompt, String model) {
            this.prompt = prompt;
            this.model = model;
        }
        
        /**
         * Starts streaming with the given chunk consumer.
         */
        public CompletableFuture<String> start(@NotNull Consumer<String> chunkConsumer) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return executeStreamingQueryWithCancellation(prompt, model, chunk -> {
                        if (!cancelled.get()) {
                            response.append(chunk);
                            chunkConsumer.accept(chunk);
                        }
                    }, cancelled);
                } catch (Exception e) {
                    LOG.error("Streaming session failed", e);
                    throw new RuntimeException("Streaming session failed", e);
                }
            });
        }
        
        /**
         * Cancels the streaming session.
         */
        public void cancel() {
            cancelled.set(true);
            LOG.info("Streaming session cancelled");
        }
        
        /**
         * Gets the response collected so far.
         */
        public String getPartialResponse() {
            return response.toString();
        }
        
        /**
         * Checks if the session was cancelled.
         */
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
    
    /**
     * Executes streaming query with cancellation support.
     */
    private String executeStreamingQueryWithCancellation(String prompt, String model, 
                                                       Consumer<String> chunkConsumer,
                                                       AtomicBoolean cancelled) throws IOException {
        
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();
        
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("LLM API URL not configured");
        }
        
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            setupConnection(connection, authToken);
            
            String requestBody = createStreamingRequestBody(apiUrl, model, prompt);
            sendRequest(connection, requestBody);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(connection);
                throw new IOException("API returned error code " + responseCode + ": " + error);
            }
            
            // Process with cancellation check
            StringBuilder fullResponse = new StringBuilder();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && !cancelled.get()) {
                    if (line.trim().isEmpty()) continue;
                    
                    String chunk = parseStreamingChunk(line, apiUrl);
                    if (chunk != null) {
                        if (chunk.equals("[DONE]")) {
                            break;
                        }
                        fullResponse.append(chunk);
                        chunkConsumer.accept(chunk);
                    }
                }
            }
            
            return fullResponse.toString();
            
        } finally {
            connection.disconnect();
        }
    }
}
