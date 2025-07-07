package com.zps.zest.langchain4j.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple utility service for making LLM API calls.
 * Now with connection pooling and HTTP/2 support for better performance.
 * Connection keep-alive is handled automatically by the HttpClient.
 */
@Service(Service.Level.PROJECT)
public final class LLMService implements Disposable {

    private static final Logger LOG = Logger.getInstance(LLMService.class);
    public static final Gson GSON = new Gson();

    private final Project project;
    private final ConfigurationManager config;
    
    // HTTP client with connection pooling (new)
    private final HttpClient httpClient;
    private final ExecutorService executorService;
    
    // Connection statistics (new)
    private final ConnectionStats connectionStats = new ConnectionStats();

    // Configuration constants
    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_MAX_TOKENS = 8148;
    
    // Connection pool configuration (new)
    private static final int THREAD_POOL_SIZE = 5;

    // Debug flag
    private boolean debugMode = true;
    
    // Flag to use optimized HTTP client (new)
    private boolean useOptimizedClient = true;

    public LLMService(@NotNull Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
        
        // Create executor service for HTTP client (new)
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "LLMService-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });
        
        // Create HTTP client with connection pooling and HTTP/2 support (new)
        this.httpClient = HttpClient.newBuilder()
            .executor(executorService)
            .connectTimeout(Duration.ofMillis(CONNECTION_TIMEOUT_MS))
            .version(HttpClient.Version.HTTP_2) // Prefer HTTP/2
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
            
        LOG.info("Initialized LLMService with connection pooling for project: " + project.getName());
        
        // Warm up connections (new)
        warmUpConnections();
    }

    /**
     * Enable or disable debug mode
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        LOG.info("Debug mode set to: " + debugMode);
    }

    /**
     * Get current debug mode status
     */
    public boolean isDebugMode() {
        return debugMode;
    }
    
    /**
     * Enable or disable optimized HTTP client (new)
     */
    public void setUseOptimizedClient(boolean useOptimizedClient) {
        this.useOptimizedClient = useOptimizedClient;
        LOG.info("Optimized client set to: " + useOptimizedClient);
    }

    /**
     * Simple synchronous call to LLM with a prompt.
     *
     * @param prompt    The prompt to send to the LLM
     * @param enumUsage
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String query(@NotNull String prompt, ChatboxUtilities.EnumUsage enumUsage) {
        return query(prompt + "\n/no_think", "local-model-mini", enumUsage);
    }

    /**
     * Simple synchronous call to LLM with a prompt and specific model.
     *
     * @param prompt    The prompt to send to the LLM
     * @param model     The model to use (overrides config)
     * @param enumUsage
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String query(@NotNull String prompt, @NotNull String model, ChatboxUtilities.EnumUsage enumUsage) {
        LLMQueryParams params = new LLMQueryParams(prompt)
                .withModel(model)
                .withMaxTokens(DEFAULT_MAX_TOKENS);
        return queryWithParams(params, enumUsage);
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
     * @param model  The model to use
     * @return CompletableFuture with the response
     */
    @NotNull
    public CompletableFuture<String> queryAsync(@NotNull String prompt, @NotNull String model) {
        LLMQueryParams params = new LLMQueryParams(prompt)
                .withModel(model)
                .withMaxTokens(DEFAULT_MAX_TOKENS);
        return queryWithParamsAsync(params, ChatboxUtilities.EnumUsage.EXPLORE_TOOL);
    }
    
    /**
     * Asynchronous call with custom parameters (new)
     */
    @NotNull
    public CompletableFuture<String> queryWithParamsAsync(@NotNull LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) {
        if (useOptimizedClient) {
            String apiUrl = config.getApiUrl();
            String authToken = config.getAuthToken();

            if (apiUrl == null || apiUrl.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("LLM API URL not configured"));
            }

            return executeQueryWithRetryAsync(apiUrl, authToken, params, enumUsage, 1);
        } else {
            // Fallback to old implementation
            return CompletableFuture.supplyAsync(() -> {
                String result = queryWithParams(params, enumUsage);
                if (result == null) {
                    throw new CompletionException(new RuntimeException("LLM query failed"));
                }
                return result;
            }, executorService);
        }
    }

    /**
     * Query with custom parameters.
     *
     * @param params    The query parameters
     * @param enumUsage
     * @return The response from the LLM, or null if failed
     */
    @Nullable
    public String queryWithParams(@NotNull LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) {
        try {
            if (useOptimizedClient) {
                // Use async implementation and wait for result
                return queryWithParamsAsync(params, enumUsage).get(params.getTimeoutMs(), TimeUnit.MILLISECONDS);
            } else {
                // Use original implementation
                return queryWithRetry(params, enumUsage);
            }
        } catch (Exception e) {
            LOG.error("Failed to query LLM with params", e);
            return null;
        }
    }

    /**
     * Internal method to query with retry logic (original implementation).
     */
    private String queryWithRetry(LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) throws IOException {
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();

        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("LLM API URL not configured");
        }

        IOException lastException = null;

        for (int attempt = 1; attempt <= params.getMaxRetries(); attempt++) {
            try {
                return executeQuery(apiUrl, authToken, params, enumUsage);
            } catch (IOException e) {
                lastException = e;
                LOG.warn("LLM query attempt " + attempt + " failed: " + e.getMessage());

                if (attempt < params.getMaxRetries()) {
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
     * Execute query with retry logic asynchronously (new)
     */
    private CompletableFuture<String> executeQueryWithRetryAsync(
            String apiUrl, 
            String authToken, 
            LLMQueryParams params, 
            ChatboxUtilities.EnumUsage enumUsage,
            int attempt) {
        
        return executeQueryAsync(apiUrl, authToken, params, enumUsage)
            .exceptionally(throwable -> {
                LOG.warn("LLM query attempt " + attempt + " failed: " + throwable.getMessage());
                
                if (attempt < params.getMaxRetries()) {
                    // Retry with exponential backoff
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    }
                    if (params.isLiteModel)
                        params.withModel("local-model-mini");
                    
                    return executeQueryWithRetryAsync(apiUrl, authToken, params, enumUsage, attempt + 1).join();
                } else {
                    throw new CompletionException("All retry attempts failed", throwable);
                }
            });
    }
    
    /**
     * Executes the actual HTTP request asynchronously using HttpClient (new)
     */
    private CompletableFuture<String> executeQueryAsync(
            String apiUrl, 
            String authToken, 
            LLMQueryParams params, 
            ChatboxUtilities.EnumUsage enumUsage) {
        
        long startTime = System.currentTimeMillis();
        connectionStats.incrementRequests();

        // Prepare request body
        String requestBody = createRequestBody(apiUrl, params, enumUsage);

        if (debugMode) {
            System.out.println("DEBUG: LLM Request URL: " + apiUrl);
            System.out.println("DEBUG: LLM Request Body: " + requestBody);
            System.out.println("DEBUG: LLM prompt: ");
            System.out.println(params.getPrompt());
        }

        // Build HTTP request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (authToken != null && !authToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = requestBuilder.build();

        // Send request asynchronously
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long elapsed = System.currentTimeMillis() - startTime;
                connectionStats.recordLatency(elapsed);
                
                System.out.println("Llm request time: " + elapsed);
                if (debugMode) {
                    System.out.println("DEBUG: HTTP version: " + response.version());
                    System.out.println("DEBUG: Status code: " + response.statusCode());
                }

                if (response.statusCode() != 200) {
                    throw new CompletionException(new IOException(
                        "API returned error code " + response.statusCode() + ": " + response.body()));
                }

                if (debugMode) {
                    System.out.println("DEBUG: LLM Response: " + response.body());
                }

                try {
                    return parseResponse(response.body(), apiUrl);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
    }

    /**
     * Executes the actual HTTP request to the LLM API (original implementation).
     */
    private String executeQuery(String apiUrl, String authToken, LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) throws IOException {
        URL url = new URL(apiUrl);
        long elapsed = System.currentTimeMillis();

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
            String requestBody = createRequestBody(apiUrl, params, enumUsage);

            if (debugMode) {
                System.out.println("DEBUG: LLM Request URL: " + apiUrl);
                System.out.println("DEBUG: LLM Request Body: " + requestBody);
                System.out.println("DEBUG: LLM prompt: ");
                System.out.println(params.prompt);
            }

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
            String response = readResponse(connection);
            elapsed = System.currentTimeMillis() - elapsed;
            
            // Track statistics (new)
            connectionStats.incrementRequests();
            connectionStats.recordLatency(elapsed);

            System.out.println("Llm request time: " + elapsed);
            if (debugMode) {
                System.out.println("DEBUG: LLM Response: " + response);
            }

            return response;

        } finally {
            connection.disconnect();
        }
    }

    /**
     * Creates the request body based on the API type.
     */
    private String createRequestBody(String apiUrl, LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) {
        // Determine API type based on URL
        if (isOpenWebUIApi(apiUrl)) {
            return createOpenWebUIRequestBody(params, enumUsage);
        } else {
            return createOllamaRequestBody(params);
        }
    }

    /**
     * Creates request body for OpenWebUI/Zingplay API.
     */
    private String createOpenWebUIRequestBody(LLMQueryParams params, ChatboxUtilities.EnumUsage enumUsage) {
        JsonObject root = new JsonObject();
        root.addProperty("model", params.getModel());
        root.addProperty("stream", false);
        String usage = enumUsage.name();
        root.addProperty("custom_tool", "Zest|" + usage);

        // Add params object with max_tokens, temperature, and stop sequences
        JsonObject paramsObj = new JsonObject();
        paramsObj.addProperty("max_tokens", params.getMaxTokens());
        paramsObj.addProperty("max_completion_tokens", params.getMaxTokens());
//        paramsObj.addProperty("num_predict", params.getMaxTokens());
        paramsObj.addProperty("temperature", params.getTemperature());

        // Add stop sequences if provided
        if (!params.getStopSequences().isEmpty()) {
            com.google.gson.JsonArray stopArray = new com.google.gson.JsonArray();
            for (String stop : params.getStopSequences()) {
                stopArray.add(stop);
            }
//            paramsObj.add("stop", stopArray);
        }

        root.add("params", paramsObj);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", params.getPrompt());

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        root.add("messages", messages);

        // Add empty arrays/objects for compatibility
        root.add("tool_servers", new com.google.gson.JsonArray());

        JsonObject features = new JsonObject();
        features.addProperty("image_generation", false);
        features.addProperty("code_interpreter", false);
        features.addProperty("web_search", false);
        features.addProperty("memory", false);
        root.add("features", features);

        return GSON.toJson(root);
    }

    /**
     * Creates request body for Ollama API.
     */
    private String createOllamaRequestBody(LLMQueryParams params) {
        JsonObject root = new JsonObject();
        root.addProperty("model", params.getModel());
        root.addProperty("prompt", params.getPrompt());
        root.addProperty("stream", false);

        JsonObject options = new JsonObject();
//        options.addProperty("num_predict", params.getMaxTokens());
        options.addProperty("temperature", params.getTemperature());

        // Add stop sequences if provided
        if (!params.getStopSequences().isEmpty()) {
            com.google.gson.JsonArray stopArray = new com.google.gson.JsonArray();
            for (String stop : params.getStopSequences()) {
                stopArray.add(stop);
            }
            options.add("stop", stopArray);
        }

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

            if (debugMode) {
                System.out.println("DEBUG: Raw JSON Response: " + jsonResponse);
            }

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
     * Get connection statistics for monitoring (new)
     */
    public ConnectionStats getConnectionStats() {
        return connectionStats;
    }
    
    /**
     * Preemptively establishes connections to the LLM API (new)
     * Call this during startup or before heavy usage.
     */
    public void warmUpConnections() {
        CompletableFuture.runAsync(() -> {
            try {
                // Send a minimal request to establish connection
                LLMQueryParams params = new LLMQueryParams("test")
                    .withMaxTokens(1)
                    .withMaxRetries(1);
                queryWithParamsAsync(params, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            LOG.debug("Failed to warm up connections", throwable);
                        } else {
                            LOG.info("Connection warmed up successfully");
                        }
                        return null;
                    });
            } catch (Exception e) {
                LOG.debug("Failed to warm up connection", e);
            }
        }, executorService);
    }
    
    @Override
    public void dispose() {
        // Shutdown executor service (new)
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("LLMService disposed");
    }

    /**
     * Parameters for LLM queries.
     */
    public static class LLMQueryParams {
        private final String prompt;
        private String model;
        private int maxRetries = MAX_RETRY_ATTEMPTS;
        private long timeoutMs = READ_TIMEOUT_MS;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private double temperature = 0.7; // Default temperature
        private java.util.List<String> stopSequences = new java.util.ArrayList<>(); // Stop sequences

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

        public LLMQueryParams withMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LLMQueryParams withTemperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public LLMQueryParams withStopSequences(java.util.List<String> stopSequences) {
            this.stopSequences = new java.util.ArrayList<>(stopSequences);
            return this;
        }

        public LLMQueryParams withStopSequence(String stopSequence) {
            this.stopSequences.add(stopSequence);
            return this;
        }

        // Getters
        public String getPrompt() {
            return prompt + (isLiteModel ? "\n/no_think" : "");
        }

        public String getModel() {
            return model;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public java.util.List<String> getStopSequences() {
            return stopSequences;
        }

        boolean isLiteModel = false;

        public LLMQueryParams useLiteCodeModel() {
            // Get current time in local timezone
            java.time.LocalTime currentTime = java.time.LocalTime.now();
            java.time.LocalTime officeStart = java.time.LocalTime.of(8, 30); // 8:30 AM
            java.time.LocalTime officeEnd = java.time.LocalTime.of(17, 30); // 5:30 PM

            // During office hours (8:30 AM - 5:30 PM), use local-model if available
            if (currentTime.isAfter(officeStart) && currentTime.isBefore(officeEnd)) {
                this.model = "local-model";
                LOG.info("Within office hours (" + currentTime + "), using local-model for lite mode");
            } else {
                // Outside office hours, use mini model
                this.model = "local-model-mini";
                LOG.info("Outside office hours (" + currentTime + "), using local-model-mini for lite mode");
            }

            this.isLiteModel = true;
            return this;
        }
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
        public boolean isConfigured() {
            return configured;
        }

        public void setConfigured(boolean configured) {
            this.configured = configured;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean hasAuthToken() {
            return hasAuthToken;
        }

        public void setHasAuthToken(boolean hasAuthToken) {
            this.hasAuthToken = hasAuthToken;
        }
    }
    
    /**
     * Connection statistics for monitoring (new)
     */
    public static class ConnectionStats {
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatency = new AtomicLong(0);
        
        public void incrementRequests() {
            totalRequests.incrementAndGet();
        }
        
        public void recordLatency(long latencyMs) {
            totalLatency.addAndGet(latencyMs);
            
            // Update min latency
            long currentMin;
            do {
                currentMin = minLatency.get();
                if (latencyMs >= currentMin) break;
            } while (!minLatency.compareAndSet(currentMin, latencyMs));
            
            // Update max latency
            long currentMax;
            do {
                currentMax = maxLatency.get();
                if (latencyMs <= currentMax) break;
            } while (!maxLatency.compareAndSet(currentMax, latencyMs));
        }
        
        public long getTotalRequests() {
            return totalRequests.get();
        }
        
        public long getAverageLatency() {
            long requests = totalRequests.get();
            return requests > 0 ? totalLatency.get() / requests : 0;
        }
        
        public long getMinLatency() {
            long min = minLatency.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }
        
        public long getMaxLatency() {
            return maxLatency.get();
        }
        
        @Override
        public String toString() {
            return String.format("ConnectionStats[requests=%d, avgLatency=%dms, minLatency=%dms, maxLatency=%dms]",
                getTotalRequests(), getAverageLatency(), getMinLatency(), getMaxLatency());
        }
    }
}