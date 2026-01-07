package com.zps.zest.langchain4j.naive_service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.settings.ConfigurationManager;
import com.zps.zest.util.EnvLoader;
import com.zps.zest.util.LLMUsage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple utility service for making LLM API calls.
 * Supports connection pooling and HTTP/2 for better performance.
 */
@Service(Service.Level.PROJECT)
public final class NaiveLLMService implements Disposable {

    private static final Logger LOG = Logger.getInstance(NaiveLLMService.class);
    public static final Gson GSON = new Gson();

    private final Project project;
    private final ConfigurationManager config;
    private final HttpClient httpClient;
    private final ExecutorService executorService;
    private final ConnectionStats connectionStats = new ConnectionStats();

    private volatile boolean cancelled = false;
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> activeFutures = ConcurrentHashMap.newKeySet();

    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_MAX_TOKENS = 8148;
    private static final int THREAD_POOL_SIZE = 5;

    private boolean debugMode = false;

    public NaiveLLMService(@NotNull Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);

        if (project.getBasePath() != null) {
            EnvLoader.loadEnv(project.getBasePath());
        }

        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "LLMService-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });

        this.httpClient = HttpClient.newBuilder()
            .executor(executorService)
            .connectTimeout(Duration.ofMillis(CONNECTION_TIMEOUT_MS))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        LOG.info("Initialized NaiveLLMService for project: " + project.getName());
        fetchAndStoreUsername(project);
    }

    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }
    public boolean isDebugMode() { return debugMode; }
    public Project getProject() { return project; }

    @Nullable
    public String query(@NotNull String prompt, LLMUsage usage) {
        return query(prompt + "\n/no_think", "local-model-mini", usage);
    }

    @Nullable
    public String query(@NotNull String prompt, @NotNull String model, LLMUsage usage) {
        LLMQueryParams params = new LLMQueryParams(prompt)
                .withModel(model)
                .withMaxTokens(DEFAULT_MAX_TOKENS);
        return queryWithParams(params, usage);
    }

    @NotNull
    public CompletableFuture<String> queryAsync(@NotNull String prompt) {
        return queryAsync(prompt, config.getCodeModel());
    }

    @NotNull
    public CompletableFuture<String> queryAsync(@NotNull String prompt, @NotNull String model) {
        LLMQueryParams params = new LLMQueryParams(prompt)
                .withModel(model)
                .withMaxTokens(DEFAULT_MAX_TOKENS);
        return queryWithParamsAsync(params, LLMUsage.EXPLORE_TOOL);
    }

    @NotNull
    public CompletableFuture<String> queryWithParamsAsync(@NotNull LLMQueryParams params, LLMUsage usage) {
        String[] urlAndToken = resolveApiUrlAndToken(params);
        String apiUrl = urlAndToken[0];
        String authToken = urlAndToken[1];

        if (apiUrl == null || apiUrl.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LLM API URL not configured"));
        }

        return executeQueryWithRetryAsync(apiUrl, authToken, params, usage, 1);
    }

    @Nullable
    public String queryWithParams(@NotNull LLMQueryParams params, LLMUsage usage) {
        try {
            return queryWithParamsAsync(params, usage).get(params.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.error("Failed to query LLM", e);
            return null;
        }
    }

    private String[] resolveApiUrlAndToken(LLMQueryParams params) {
        String envApiKey = EnvLoader.getEnv("OPENAI_API_KEY", null);
        String envModel = EnvLoader.getEnv("OPENAI_MODEL", null);
        String envBaseUrl = EnvLoader.getEnv("OPENAI_BASE_URL", "https://api.openai.com/v1");

        String apiUrl, authToken;
        if (envApiKey != null && !envApiKey.isEmpty()) {
            apiUrl = envBaseUrl;
            if (!apiUrl.endsWith("/chat/completions")) {
                if (!apiUrl.endsWith("/")) apiUrl += "/";
                apiUrl += "chat/completions";
            }
            authToken = envApiKey;
            if (envModel != null && !envModel.isEmpty() && params.getModel() == null) {
                params.withModel(envModel);
            }
        } else {
            apiUrl = config.getApiUrl();
            authToken = config.getAuthToken();
        }
        return new String[]{apiUrl, authToken};
    }

    private CompletableFuture<String> executeQueryWithRetryAsync(
            String apiUrl, String authToken, LLMQueryParams params, LLMUsage usage, int attempt) {

        if (cancelled) {
            return CompletableFuture.failedFuture(new IOException("LLM service cancelled"));
        }

        CompletableFuture<String> future = executeQueryAsync(apiUrl, authToken, params, usage);
        activeFutures.add(future);
        future.whenComplete((r, e) -> activeFutures.remove(future));

        return future.exceptionally(throwable -> {
            LOG.warn("LLM query attempt " + attempt + " failed: " + throwable.getMessage());
            if (attempt < params.getMaxRetries()) {
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
                if (params.isLiteModel) params.withModel("local-model-mini");
                return executeQueryWithRetryAsync(apiUrl, authToken, params, usage, attempt + 1).join();
            }
            throw new CompletionException("All retry attempts failed", throwable);
        });
    }

    private CompletableFuture<String> executeQueryAsync(
            String apiUrl, String authToken, LLMQueryParams params, LLMUsage usage) {

        long startTime = System.currentTimeMillis();
        connectionStats.incrementRequests();

        String requestBody = createRequestBody(apiUrl, params, usage);
        if (debugMode) {
            System.out.println("DEBUG: URL: " + apiUrl);
            System.out.println("DEBUG: Body: " + requestBody);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (authToken != null && !authToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + authToken);
        }

        String finalApiUrl = apiUrl;
        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                connectionStats.recordLatency(System.currentTimeMillis() - startTime);
                if (response.statusCode() != 200) {
                    throw new CompletionException(new IOException(
                        "API error " + response.statusCode() + ": " + response.body()));
                }
                try { return parseResponse(response.body(), finalApiUrl); }
                catch (IOException e) { throw new CompletionException(e); }
            });
    }

    private String createRequestBody(String apiUrl, LLMQueryParams params, LLMUsage usage) {
        return isOpenWebUIApi(apiUrl) ? createOpenWebUIRequestBody(params, usage) : createOllamaRequestBody(params);
    }

    private String createOpenWebUIRequestBody(LLMQueryParams params, LLMUsage usage) {
        JsonObject root = new JsonObject();
        root.addProperty("model", params.getModel());
        root.addProperty("stream", false);
        root.addProperty("custom_tool", "Zest|" + usage.getValue());
        root.addProperty("max_completion_tokens", params.getMaxTokens());

        String username = config.getUsername();
        if (username != null && !username.isEmpty()) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("user", username);
            metadata.addProperty("usage", usage.getValue());
            metadata.addProperty("tool", "Zest");
            root.add("metadata", metadata);
        }

        JsonObject paramsObj = new JsonObject();
        paramsObj.addProperty("max_tokens", params.getMaxTokens());
        paramsObj.addProperty("temperature", params.getTemperature());
        root.add("params", paramsObj);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        if (params.getSystemPrompt() != null && !params.getSystemPrompt().isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", params.getSystemPrompt());
            messages.add(sysMsg);
        }
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", params.getPrompt());
        messages.add(userMsg);
        root.add("messages", messages);

        if (!params.getStopSequences().isEmpty()) {
            com.google.gson.JsonArray stopArr = new com.google.gson.JsonArray();
            for (String s : params.getStopSequences()) stopArr.add(s);
            root.add("stop", stopArr);
        }

        root.add("tool_servers", new com.google.gson.JsonArray());
        JsonObject features = new JsonObject();
        features.addProperty("image_generation", false);
        features.addProperty("code_interpreter", false);
        features.addProperty("web_search", false);
        root.add("features", features);

        return GSON.toJson(root);
    }

    private String createOllamaRequestBody(LLMQueryParams params) {
        JsonObject root = new JsonObject();
        root.addProperty("model", params.getModel());
        root.addProperty("prompt", params.getPrompt());
        root.addProperty("stream", false);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", params.getTemperature());
        if (!params.getStopSequences().isEmpty()) {
            com.google.gson.JsonArray stopArr = new com.google.gson.JsonArray();
            for (String s : params.getStopSequences()) stopArr.add(s);
            options.add("stop", stopArr);
        }
        root.add("options", options);
        return GSON.toJson(root);
    }

    private String parseResponse(String jsonResponse, String apiUrl) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (isOpenWebUIApi(apiUrl)) {
                if (response.has("choices") && response.getAsJsonArray("choices").size() > 0) {
                    JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                        return choice.getAsJsonObject("message").get("content").getAsString();
                    }
                }
            } else if (response.has("response")) {
                return response.get("response").getAsString();
            }
            throw new IOException("Unexpected response format");
        } catch (Exception e) {
            throw new IOException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    private boolean isOpenWebUIApi(String apiUrl) {
        return apiUrl.contains("openwebui") || apiUrl.contains("chat.zingplay") ||
               apiUrl.contains("litellm.zingplay") || apiUrl.contains("talk.zingplay");
    }

    public boolean isConfigured() {
        String apiUrl = config.getApiUrl();
        return apiUrl != null && !apiUrl.isEmpty();
    }

    public ConnectionStats getConnectionStats() { return connectionStats; }

    public static void fetchAndStoreUsername(Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        if (config.getUsername() != null && !config.getUsername().isEmpty()) return;

        String authToken = config.getAuthTokenNoPrompt();
        if (authToken == null || authToken.isEmpty()) return;

        String apiUrl = config.getApiUrl();
        String authEndpoint = null;
        if (apiUrl != null && apiUrl.contains("chat.zingplay.com")) {
            authEndpoint = "https://chat.zingplay.com/api/v1/auths/";
        } else if (apiUrl != null && apiUrl.contains("talk.zingplay.com")) {
            authEndpoint = "https://talk.zingplay.com/api/v1/auths/";
        }
        if (authEndpoint == null) return;

        final String endpoint = authEndpoint;
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) response.append(line);
                        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                        config.setUsername(json.get("email").getAsString());
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                LOG.warn("Failed to fetch username: " + e.getMessage());
            }
        });
    }

    public void cancelAll() {
        cancelled = true;
        for (CompletableFuture<?> f : activeFutures) if (!f.isDone()) f.cancel(true);
        activeFutures.clear();
        for (HttpURLConnection c : activeConnections) try { c.disconnect(); } catch (Exception ignored) {}
        activeConnections.clear();
    }

    public void reset() { cancelled = false; activeConnections.clear(); activeFutures.clear(); }
    public boolean isCancelled() { return cancelled; }

    @Override
    public void dispose() {
        cancelAll();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class LLMQueryParams {
        private final String prompt;
        private String systemPrompt;
        private String model;
        private int maxRetries = MAX_RETRY_ATTEMPTS;
        private long timeoutMs = READ_TIMEOUT_MS;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private double temperature = 0.7;
        private java.util.List<String> stopSequences = new java.util.ArrayList<>();
        boolean isLiteModel = false;

        public LLMQueryParams(@NotNull String prompt) { this.prompt = prompt; }

        public LLMQueryParams withSystemPrompt(@NotNull String s) { this.systemPrompt = s; return this; }
        public LLMQueryParams withModel(@NotNull String m) { this.model = m; return this; }
        public LLMQueryParams withMaxRetries(int r) { this.maxRetries = r; return this; }
        public LLMQueryParams withTimeout(long t) { this.timeoutMs = t; return this; }
        public LLMQueryParams withMaxTokens(int t) { this.maxTokens = t; return this; }
        public LLMQueryParams withTemperature(double t) { this.temperature = t; return this; }
        public LLMQueryParams withStopSequences(java.util.List<String> s) {
            this.stopSequences = new java.util.ArrayList<>(s); return this;
        }
        public LLMQueryParams withStopSequence(String s) { this.stopSequences.add(s); return this; }

        public String getPrompt() { return prompt + (isLiteModel ? "\n/no_think" : ""); }
        public String getSystemPrompt() { return systemPrompt; }
        public String getModel() { return model; }
        public int getMaxRetries() { return maxRetries; }
        public long getTimeoutMs() { return timeoutMs; }
        public int getMaxTokens() { return maxTokens; }
        public double getTemperature() { return temperature; }
        public java.util.List<String> getStopSequences() { return stopSequences; }

        public LLMQueryParams useLiteCodeModel() {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.DayOfWeek day = now.getDayOfWeek();
            java.time.LocalTime time = now.toLocalTime();
            java.time.LocalTime start = java.time.LocalTime.of(8, 30);
            java.time.LocalTime end = java.time.LocalTime.of(17, 30);

            if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
                this.model = "local-model-mini";
            } else if (time.isAfter(start) && time.isBefore(end)) {
                this.model = "local-model";
            } else {
                this.model = "local-model-mini";
            }
            this.isLiteModel = true;
            return this;
        }
    }

    public static class ConnectionStats {
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatency = new AtomicLong(0);

        public void incrementRequests() { totalRequests.incrementAndGet(); }
        public void recordLatency(long ms) {
            totalLatency.addAndGet(ms);
            minLatency.updateAndGet(cur -> Math.min(cur, ms));
            maxLatency.updateAndGet(cur -> Math.max(cur, ms));
        }
        public long getTotalRequests() { return totalRequests.get(); }
        public long getAverageLatency() {
            long r = totalRequests.get();
            return r > 0 ? totalLatency.get() / r : 0;
        }
        public long getMinLatency() {
            long m = minLatency.get();
            return m == Long.MAX_VALUE ? 0 : m;
        }
        public long getMaxLatency() { return maxLatency.get(); }
    }
}
