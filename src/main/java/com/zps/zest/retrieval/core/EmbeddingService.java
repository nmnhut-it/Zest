package com.zps.zest.retrieval.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding service that extends LLMService to provide vector embeddings
 * for semantic search. Supports caching and batch processing.
 */
@Service(Service.Level.PROJECT)
public final class EmbeddingService {
    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final LLMService llmService;
    private final ConfigurationManager config;
    private final Map<String, float[]> embeddingCache;
    private final Object cacheLock = new Object();
    private final HttpClient httpClient;
    
    // Rate limiting components
    private final Semaphore requestSemaphore = new Semaphore(3); // Max 3 concurrent requests
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final long MIN_REQUEST_INTERVAL_MS = 200; // Minimum 200ms between requests
    private final int MAX_RETRIES = 2; // Maximum number of retries for failed requests
    
    // Configuration constants
    private static final String DEFAULT_EMBEDDING_MODEL = "Qwen3-Embedding-0.6B";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 768; // Qwen3-Embedding-0.6B dimensions
    private static final int MAX_CACHE_SIZE = 10000;
    private static final int BATCH_SIZE = 100; // Max texts per batch request
    private static final int MAX_TEXT_LENGTH = 8000; // Approximate token limit
    
    public EmbeddingService(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        this.config = ConfigurationManager.getInstance(project);
        this.embeddingCache = new ConcurrentHashMap<>();
        
        // Initialize HTTP client for embedding requests with rate limiting optimizations
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1) // Use HTTP/1.1 to avoid GOAWAY issues with HTTP/2
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        
        LOG.info("EmbeddingService initialized for project: " + project.getName());
    }
    
    /**
     * Generate embedding for a single text
     */
    public CompletableFuture<float[]> generateEmbedding(@NotNull String text) {
        return generateEmbedding(text, DEFAULT_EMBEDDING_MODEL);
    }
    
    /**
     * Generate embedding for a single text with specific model
     */
    public CompletableFuture<float[]> generateEmbedding(@NotNull String text, @NotNull String model) {
        // Check cache first
        String cacheKey = createCacheKey(text, model);
        synchronized (cacheLock) {
            float[] cached = embeddingCache.get(cacheKey);
            if (cached != null) {
                LOG.debug("Cache hit for embedding: " + text.substring(0, Math.min(50, text.length())));
                return CompletableFuture.completedFuture(cached);
            }
        }
        
        // Truncate text if too long
        String processedText = truncateText(text);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                float[] embedding = generateEmbeddingSync(processedText, model);
                
                // Cache the result
                synchronized (cacheLock) {
                    if (embeddingCache.size() >= MAX_CACHE_SIZE) {
                        // Simple LRU: remove oldest entries
                        Iterator<String> iterator = embeddingCache.keySet().iterator();
                        for (int i = 0; i < MAX_CACHE_SIZE / 4 && iterator.hasNext(); i++) {
                            iterator.next();
                            iterator.remove();
                        }
                    }
                    embeddingCache.put(cacheKey, embedding);
                }
                
                LOG.debug("Generated embedding for text: " + text.substring(0, Math.min(50, text.length())));
                return embedding;
            } catch (Exception e) {
                LOG.error("Failed to generate embedding for text: " + text.substring(0, Math.min(50, text.length())), e);
                throw new RuntimeException("Embedding generation failed", e);
            }
        });
    }
    
    /**
     * Generate embeddings for multiple texts in batches
     */
    public CompletableFuture<List<float[]>> generateBatchEmbeddings(@NotNull List<String> texts) {
        return generateBatchEmbeddings(texts, DEFAULT_EMBEDDING_MODEL);
    }
    
    /**
     * Generate embeddings for multiple texts in batches with specific model
     */
    public CompletableFuture<List<float[]>> generateBatchEmbeddings(@NotNull List<String> texts, @NotNull String model) {
        if (texts.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            List<float[]> results = new ArrayList<>();
            List<String> uncachedTexts = new ArrayList<>();
            List<Integer> uncachedIndices = new ArrayList<>();
            
            // Check cache for each text
            synchronized (cacheLock) {
                for (int i = 0; i < texts.size(); i++) {
                    String text = texts.get(i);
                    String cacheKey = createCacheKey(text, model);
                    float[] cached = embeddingCache.get(cacheKey);
                    
                    if (cached != null) {
                        results.add(cached);
                    } else {
                        results.add(null); // Placeholder
                        uncachedTexts.add(truncateText(text));
                        uncachedIndices.add(i);
                    }
                }
            }
            
            if (uncachedTexts.isEmpty()) {
                LOG.debug("All embeddings found in cache");
                return results;
            }
            
            // Process uncached texts in batches
            List<float[]> batchResults = new ArrayList<>();
            for (int i = 0; i < uncachedTexts.size(); i += BATCH_SIZE) {
                List<String> batch = uncachedTexts.subList(i, Math.min(i + BATCH_SIZE, uncachedTexts.size()));
                try {
                    List<float[]> batchEmbeddings = generateBatchEmbeddingsSync(batch, model);
                    batchResults.addAll(batchEmbeddings);
                } catch (Exception e) {
                    LOG.error("Failed to generate batch embeddings", e);
                    // Add null placeholders for failed batch
                    for (int j = 0; j < batch.size(); j++) {
                        batchResults.add(null);
                    }
                }
            }
            
            // Fill in the results and update cache
            synchronized (cacheLock) {
                for (int i = 0; i < uncachedIndices.size(); i++) {
                    int originalIndex = uncachedIndices.get(i);
                    float[] embedding = i < batchResults.size() ? batchResults.get(i) : null;
                    
                    if (embedding != null) {
                        results.set(originalIndex, embedding);
                        
                        // Cache the result
                        String text = texts.get(originalIndex);
                        String cacheKey = createCacheKey(text, model);
                        if (embeddingCache.size() < MAX_CACHE_SIZE) {
                            embeddingCache.put(cacheKey, embedding);
                        }
                    } else {
                        LOG.warn("Failed to generate embedding for text at index: " + originalIndex);
                    }
                }
            }
            
            LOG.info("Generated " + uncachedTexts.size() + " new embeddings, " + 
                    (results.size() - uncachedTexts.size()) + " from cache");
            
            return results;
        });
    }
    
    /**
     * Get cache statistics
     */
    public EmbeddingCacheStats getCacheStats() {
        synchronized (cacheLock) {
            return new EmbeddingCacheStats(
                embeddingCache.size(),
                MAX_CACHE_SIZE,
                calculateCacheSize()
            );
        }
    }
    
    /**
     * Clear the embedding cache
     */
    public void clearCache() {
        synchronized (cacheLock) {
            embeddingCache.clear();
            LOG.info("Embedding cache cleared");
        }
    }
    
    /**
     * Generate embedding synchronously using LLMService
     */
    private float[] generateEmbeddingSync(@NotNull String text, @NotNull String model) throws IOException {
        // Create embedding request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("input", text);
        
        // Note: dimensions parameter removed as litellm doesn't support it for Qwen models
        // The API will use the model's default dimensions automatically
        
        // Make HTTP request to embedding endpoint
        String response = makeEmbeddingRequest(requestBody.toString());
        
        // Parse response
        return parseEmbeddingResponse(response);
    }
    
    /**
     * Generate batch embeddings synchronously
     */
    private List<float[]> generateBatchEmbeddingsSync(@NotNull List<String> texts, @NotNull String model) throws IOException {
        // Create batch embedding request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        requestBody.add("input", inputArray);
        
        // Note: dimensions parameter removed as litellm doesn't support it for Qwen models
        // The API will use the model's default dimensions automatically
        
        // Make HTTP request
        String response = makeEmbeddingRequest(requestBody.toString());
        
        // Parse batch response
        return parseBatchEmbeddingResponse(response);
    }
    
    /**
     * Make HTTP request to embedding endpoint using LLMService infrastructure
     */
    private String makeEmbeddingRequest(@NotNull String requestBody) throws IOException {
        // Create custom LLM query parameters for embedding endpoint
        LLMService.LLMQueryParams params = new LLMService.LLMQueryParams("")
            .withModel(DEFAULT_EMBEDDING_MODEL)
            .withMaxTokens(0)
            .withTimeout(30000);
        
        // Use LLMService to make request to /v1/embeddings endpoint
        // Note: This requires modifying LLMService to support custom endpoints
        // For now, we'll use a simplified approach
        
        try {
            // This is a placeholder - in practice, you'd need to modify LLMService
            // to support the /v1/embeddings endpoint specifically
            String embeddingEndpoint = getEmbeddingEndpoint();
            return makeDirectEmbeddingRequest(embeddingEndpoint, requestBody);
        } catch (Exception e) {
            throw new IOException("Failed to make embedding request", e);
        }
    }
    
    /**
     * Get the embedding endpoint URL based on LLMService configuration
     */
    private String getEmbeddingEndpoint() {
        // First, try local Ollama embedding server
        if (isLocalOllamaAvailable()) {
            String localUrl = config.getGlobalSettings().localEmbeddingUrl;
            LOG.info("Using local Ollama embedding server: " + localUrl);
            return localUrl;
        }
        
        // Get base URL from configuration
        String baseUrl = config.getApiUrl();
        
        // Special handling for ZingPlay endpoints - use litellm during office hours, regular endpoint otherwise
        if (baseUrl.contains("chat.zingplay.com") || baseUrl.contains("openwebui.zingplay.com")) {
            if (isWithinOfficeHours()) {
                LOG.info("Office hours: redirecting to litellm for embeddings");
                return "https://litellm.zingplay.com/v1/embeddings";
            } else {
                LOG.info("Outside office hours: using regular endpoint for embeddings");
                // Use the regular OpenAI-compatible endpoint
            }
        }
        
        // Special handling for talk.zingplay.com - use litellm-internal during office hours, regular endpoint otherwise
        if (baseUrl.contains("talk.zingplay.com")) {
            if (isWithinOfficeHours()) {
                LOG.info("Office hours: redirecting to litellm-internal for embeddings");
                return "https://litellm-internal.zingplay.com/v1/embeddings";
            } else {
                LOG.info("Outside office hours: using regular endpoint for embeddings");
                // Use the regular OpenAI-compatible endpoint
            }
        }
        
        // Extract base URL and construct embedding endpoint
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getScheme() + "://" + uri.getAuthority();
            String path = uri.getPath();
            
            // For ZingPlay endpoints with /api path, preserve it
            if (path != null && path.startsWith("/api")) {
                return host + "/api/v1/embeddings";
            }
            
            // Use the OpenAI-compatible /v1/embeddings endpoint
            return host + "/v1/embeddings";
        } catch (Exception e) {
            LOG.warn("Failed to parse base URL, using fallback: " + e.getMessage());
            
            // Fallback: For standard OpenAI-compatible endpoints
            if (baseUrl.contains("/v1/chat/completions")) {
                return baseUrl.replace("/v1/chat/completions", "/v1/embeddings");
            } else if (baseUrl.contains("/api/chat/completions")) {
                return baseUrl.replace("/api/chat/completions", "/api/v1/embeddings");
            }
            
            // Default to appending /v1/embeddings
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            return baseUrl + "v1/embeddings";
        }
    }
    
    /**
     * Convert OpenAI format request body to Ollama format
     * OpenAI: {"model": "text-embedding-ada-002", "input": ["text1", "text2"]}
     * Ollama: {"model": "all-minilm", "prompt": "text1"}
     */
    private String convertToOllamaFormat(@NotNull String openaiRequestBody) {
        try {
            JsonObject openaiRequest = JsonParser.parseString(openaiRequestBody).getAsJsonObject();
            
            if (openaiRequest.has("input")) {
                String textToEmbed = null;
                
                // Handle both string and array input formats
                if (openaiRequest.get("input").isJsonArray()) {
                    // Array format: {"input": ["text1", "text2"]}
                    JsonArray inputArray = openaiRequest.getAsJsonArray("input");
                    if (inputArray.size() > 0) {
                        textToEmbed = inputArray.get(0).getAsString();
                    }
                } else {
                    // String format: {"input": "text"}
                    textToEmbed = openaiRequest.get("input").getAsString();
                }
                
                if (textToEmbed != null && !textToEmbed.isEmpty()) {
                    JsonObject ollamaRequest = new JsonObject();
                    ollamaRequest.addProperty("model", "all-minilm");
                    ollamaRequest.addProperty("prompt", textToEmbed);
                    
                    LOG.debug("Converted to Ollama format: model=all-minilm, prompt length=" + textToEmbed.length());
                    return GSON.toJson(ollamaRequest);
                }
            }
            
            // Fallback to original format if conversion fails
            LOG.warn("Could not convert to Ollama format, using original");
            return openaiRequestBody;
            
        } catch (Exception e) {
            LOG.warn("Error converting to Ollama format: " + e.getMessage());
            return openaiRequestBody;
        }
    }
    
    /**
     * Apply rate limiting before making requests
     */
    private void applyRateLimit() throws InterruptedException {
        // Ensure minimum time interval between requests
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime.get();
        
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
            long sleepTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;
            LOG.debug("Rate limiting: sleeping for " + sleepTime + "ms");
            Thread.sleep(sleepTime);
        }
        
        lastRequestTime.set(System.currentTimeMillis());
    }

    /**
     * Make direct HTTP request to embedding endpoint
     */
    private String makeDirectEmbeddingRequest(@NotNull String endpoint, @NotNull String requestBody) throws IOException {
        // Acquire semaphore permit for concurrent request limiting
        try {
            requestSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted while waiting for rate limit", e);
        }
        
        try {
            // Apply rate limiting
            applyRateLimit();
            
            // Log the endpoint being used
            LOG.info("Making embedding request to: " + endpoint);
            
            // Handle Ollama format conversion if needed
            if (endpoint.equals(config.getGlobalSettings().localEmbeddingUrl)) {
                requestBody = convertToOllamaFormat(requestBody);
                LOG.debug("Converted request body for Ollama format");
            }
            
            // Get API token from configuration
            String apiToken = config.getAuthTokenNoPrompt();
            
            // Handle special LiteLLM endpoints with custom tokens (only during office hours)
            if (endpoint.contains("litellm.zingplay.com") || endpoint.contains("litellm-internal.zingplay.com")) {
                apiToken = "sk-0c1l7KCScBLmcYDN-Oszmg"; // Use embedding service token for LiteLLM
            }
            // For local Ollama, no auth token needed
            else if (endpoint.equals(config.getGlobalSettings().localEmbeddingUrl)) {
                apiToken = null;
            }
            // For regular endpoints (chat/talk/openwebui), use the configured auth token from ConfigurationManager
            
            // Build HTTP request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            
            if (apiToken != null && !apiToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiToken);
            }
            
            HttpRequest request = requestBuilder.build();
            
            // Log request details
            LOG.debug("Request method: " + request.method());
            LOG.debug("Request URI: " + request.uri());
            
            // Send request with retry logic
            HttpResponse<String> response = sendRequestWithRetry(request);
            
            // Check response status
            if (response.statusCode() != 200) {
                LOG.error("Embedding request failed with status " + response.statusCode() + ": " + response.body());
                LOG.error("Endpoint was: " + endpoint);
                LOG.error("Request method was: " + request.method());
                throw new IOException("Embedding request failed: " + response.statusCode());
            }
            
            LOG.debug("Successfully received embedding response from " + endpoint);
            return response.body();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding request interrupted", e);
        } catch (Exception e) {
            LOG.error("Failed to make embedding request to " + endpoint, e);
            throw new IOException("Failed to make embedding request", e);
        } finally {
            // Always release the semaphore permit
            requestSemaphore.release();
        }
    }
    
    /**
     * Send HTTP request with retry logic for handling GOAWAY and other transient errors
     */
    private HttpResponse<String> sendRequestWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastException = null;
        
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    // Exponential backoff: 1s, 2s, 4s, etc.
                    long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
                    LOG.info("Retrying embedding request (attempt " + (attempt + 1) + "/" + (MAX_RETRIES + 1) + ") after " + backoffMs + "ms");
                    Thread.sleep(backoffMs);
                }
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (attempt > 0) {
                    LOG.info("Embedding request succeeded on retry attempt " + (attempt + 1));
                }
                
                return response;
                
            } catch (IOException e) {
                lastException = e;
                String errorMsg = e.getMessage().toLowerCase();
                
                // Check if this is a retryable error (GOAWAY, connection reset, timeout, etc.)
                boolean isRetryable = errorMsg.contains("goaway") || 
                                    errorMsg.contains("connection reset") ||
                                    errorMsg.contains("connection refused") ||
                                    errorMsg.contains("timeout") ||
                                    errorMsg.contains("stream closed");
                
                if (!isRetryable || attempt >= MAX_RETRIES) {
                    LOG.error("Embedding request failed permanently: " + e.getMessage());
                    throw e;
                }
                
                LOG.warn("Retryable error on embedding request (attempt " + (attempt + 1) + "/" + (MAX_RETRIES + 1) + "): " + e.getMessage());
            }
        }
        
        // This should never be reached due to the logic above, but just in case
        throw lastException != null ? lastException : new IOException("All retry attempts failed");
    }
    
    /**
     * Parse embedding response JSON
     */
    private float[] parseEmbeddingResponse(@NotNull String response) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            
            // Try Ollama format first ({"embedding": [0.1, 0.2, ...]})
            if (jsonResponse.has("embedding")) {
                JsonArray embeddingArray = jsonResponse.getAsJsonArray("embedding");
                float[] embedding = new float[embeddingArray.size()];
                for (int i = 0; i < embeddingArray.size(); i++) {
                    embedding[i] = embeddingArray.get(i).getAsFloat();
                }
                LOG.debug("Parsed Ollama format embedding with " + embedding.length + " dimensions");
                return embedding;
            }
            
            // Try OpenAI format ({"data": [{"embedding": [0.1, 0.2, ...]}]})
            if (jsonResponse.has("data")) {
                JsonArray dataArray = jsonResponse.getAsJsonArray("data");
                if (dataArray.size() == 0) {
                    throw new RuntimeException("No embedding data in response");
                }
                
                JsonObject firstEmbedding = dataArray.get(0).getAsJsonObject();
                JsonArray embeddingArray = firstEmbedding.getAsJsonArray("embedding");
                
                float[] embedding = new float[embeddingArray.size()];
                for (int i = 0; i < embeddingArray.size(); i++) {
                    embedding[i] = embeddingArray.get(i).getAsFloat();
                }
                LOG.debug("Parsed OpenAI format embedding with " + embedding.length + " dimensions");
                return embedding;
            }
            
            throw new RuntimeException("Unknown embedding response format");
            
        } catch (Exception e) {
            LOG.error("Failed to parse embedding response: " + response, e);
            throw new RuntimeException("Failed to parse embedding response", e);
        }
    }
    
    /**
     * Parse batch embedding response JSON
     */
    private List<float[]> parseBatchEmbeddingResponse(@NotNull String response) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            JsonArray dataArray = jsonResponse.getAsJsonArray("data");
            
            List<float[]> embeddings = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                JsonObject embeddingObj = dataArray.get(i).getAsJsonObject();
                JsonArray embeddingArray = embeddingObj.getAsJsonArray("embedding");
                
                float[] embedding = new float[embeddingArray.size()];
                for (int j = 0; j < embeddingArray.size(); j++) {
                    embedding[j] = embeddingArray.get(j).getAsFloat();
                }
                embeddings.add(embedding);
            }
            
            return embeddings;
        } catch (Exception e) {
            LOG.error("Failed to parse batch embedding response: " + response, e);
            throw new RuntimeException("Failed to parse batch embedding response", e);
        }
    }
    
    /**
     * Check if current time is within office hours (8:30 - 17:30)
     */
    private boolean isWithinOfficeHours() {
        LocalTime now = LocalTime.now();
        LocalTime startTime = LocalTime.of(8, 30); // 8:30 AM
        LocalTime endTime = LocalTime.of(17, 30);  // 5:30 PM
        
        boolean withinHours = !now.isBefore(startTime) && !now.isAfter(endTime);
        LOG.debug("Current time: " + now + ", Office hours: " + startTime + " - " + endTime + ", Within hours: " + withinHours);
        
        return withinHours;
    }
    
    /**
     * Check if local Ollama embedding server is available and preferred
     */
    private boolean isLocalOllamaAvailable() {
        // Check if local embeddings are preferred in settings
        if (!config.getGlobalSettings().preferLocalEmbeddings) {
            LOG.debug("Local embeddings disabled in settings");
            return false;
        }
        
        try {
            // Extract URL for health check (replace /api/embeddings with /api/version)
            String healthUrl = config.getGlobalSettings().localEmbeddingUrl
                .replace("/api/embeddings", "/api/version");
            
            // Quick health check to Ollama's version endpoint
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
                
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            boolean available = response.statusCode() == 200;
            if (available) {
                LOG.debug("Local Ollama embedding server detected and available");
            } else {
                LOG.debug("Local Ollama embedding server not available (status: " + response.statusCode() + ")");
            }
            
            return available;
        } catch (Exception e) {
            LOG.debug("Local Ollama embedding server not available: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Create cache key for text and model combination
     */
    private String createCacheKey(@NotNull String text, @NotNull String model) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String combined = model + ":" + text;
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return (model + ":" + text).hashCode() + "";
        }
    }
    
    /**
     * Truncate text to fit within token limits
     */
    private String truncateText(@NotNull String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        
        // Simple truncation - in practice, you might want more sophisticated tokenization
        String truncated = text.substring(0, MAX_TEXT_LENGTH);
        LOG.debug("Truncated text from " + text.length() + " to " + truncated.length() + " characters");
        return truncated;
    }
    
    /**
     * Calculate approximate cache size in MB
     */
    private double calculateCacheSize() {
        // Rough estimate: each float is 4 bytes, 768 dimensions per embedding for Qwen3
        long totalFloats = (long) embeddingCache.size() * DEFAULT_EMBEDDING_DIMENSIONS;
        double sizeBytes = totalFloats * 4.0; // 4 bytes per float
        return sizeBytes / (1024.0 * 1024.0); // Convert to MB
    }
    
    /**
     * Create mock embedding response for testing
     */
    private String createMockEmbeddingResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("object", "list");
        response.addProperty("model", DEFAULT_EMBEDDING_MODEL);
        
        JsonArray dataArray = new JsonArray();
        JsonObject embeddingObj = new JsonObject();
        embeddingObj.addProperty("object", "embedding");
        embeddingObj.addProperty("index", 0);
        
        // Create mock embedding vector
        JsonArray embeddingArray = new JsonArray();
        Random random = new Random();
        for (int i = 0; i < DEFAULT_EMBEDDING_DIMENSIONS; i++) {
            embeddingArray.add(random.nextGaussian() * 0.1); // Small random values
        }
        embeddingObj.add("embedding", embeddingArray);
        
        dataArray.add(embeddingObj);
        response.add("data", dataArray);
        
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("total_tokens", 10);
        response.add("usage", usage);
        
        return GSON.toJson(response);
    }
    
    /**
     * Embedding cache statistics
     */
    public static class EmbeddingCacheStats {
        private final int cacheSize;
        private final int maxCacheSize;
        private final double cacheSizeMB;
        
        public EmbeddingCacheStats(int cacheSize, int maxCacheSize, double cacheSizeMB) {
            this.cacheSize = cacheSize;
            this.maxCacheSize = maxCacheSize;
            this.cacheSizeMB = cacheSizeMB;
        }
        
        public int getCacheSize() { return cacheSize; }
        public int getMaxCacheSize() { return maxCacheSize; }
        public double getCacheSizeMB() { return cacheSizeMB; }
        public double getCacheUtilization() { return (double) cacheSize / maxCacheSize; }
        
        @Override
        public String toString() {
            return String.format("EmbeddingCacheStats{size=%d/%d (%.1f%%), sizeMB=%.2f}", 
                cacheSize, maxCacheSize, getCacheUtilization() * 100, cacheSizeMB);
        }
    }
}