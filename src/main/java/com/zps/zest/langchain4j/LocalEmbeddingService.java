package com.zps.zest.langchain4j;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Embedding service that uses an API endpoint instead of local ONNX models.
 * Compatible with OpenAI-style /api/embeddings endpoints.
 */
@Service(Service.Level.PROJECT)
public final class LocalEmbeddingService implements EmbeddingService, Disposable {

    private static final Logger LOG = Logger.getInstance(LocalEmbeddingService.class);

    private final ConfigurationManager config;
    private final ExecutorService executorService;
    private final String modelName;
    private final int dimension;

    public LocalEmbeddingService(@NotNull ConfigurationManager config) {
        this.config = config;
        this.executorService = Executors.newFixedThreadPool(
                Math.min(4, Runtime.getRuntime().availableProcessors())
        );
        this.modelName = "api-embedding-model"; // Can be overridden by config
        this.dimension = 1536; // Default dimension, or can parse from API response
    }

    public LocalEmbeddingService(Project project) {
        this(ConfigurationManager.getInstance(project)); // Assume project is passed if needed
        LOG.info("Initialized API-based LocalEmbeddingService");
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return new float[dimension];
        }

        try {
            String response = sendEmbeddingRequest(Collections.singletonList(text));
            return parseEmbeddingFromResponse(response).get(0);
        } catch (Exception e) {
            LOG.error("Failed to embed text via API", e);
            return new float[dimension];
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        List<CompletableFuture<float[]>> futures = texts.stream()
                .map(text -> CompletableFuture.supplyAsync(
                        () -> embed(text),
                        executorService
                ))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Override
    public List<Embedding> embedSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> texts = segments.stream()
                .map(s -> s.text())
                .collect(Collectors.toList());

        List<float[]> embeddings = embedBatch(texts);

        List<Embedding> result = new ArrayList<>();
        for (float[] embedding : embeddings) {
            result.add(new Embedding(embedding));
        }

        return result;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public double cosineSimilarity(float[] embedding1, float[] embedding2) {
        if (embedding1.length != embedding2.length) {
            throw new IllegalArgumentException("Embeddings must have the same dimension. Got " +
                    embedding1.length + " and " + embedding2.length);
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private String sendEmbeddingRequest(List<String> texts) throws IOException {
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();

        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("API URL not configured for embeddings.");
        }

        URL url = new URL(apiUrl + "/api/embeddings");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);

            String jsonBody = buildRequestJson(texts);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(connection);
                throw new IOException("API returned error: " + responseCode + " - " + error);
            }

            return readResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    private String buildRequestJson(List<String> texts) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelName);
        root.add("input", com.zps.zest.langchain4j.util.LLMService.GSON.toJsonTree(texts));
        return root.toString();
    }

    private List<float[]> parseEmbeddingFromResponse(String jsonResponse) {
        JsonObject response = com.google.gson.JsonParser.parseString(jsonResponse).getAsJsonObject();

        if (!response.has("data")) {
            throw new RuntimeException("No embedding found in API response: " + jsonResponse);
        }

        JsonArray dataArray = response.getAsJsonArray("data");

        List<float[]> embeddings = new ArrayList<>();
        for (int i = 0; i < dataArray.size(); i++) {
            JsonArray embeddingArray = dataArray.get(i).getAsJsonObject().getAsJsonArray("embedding");
            float[] vector = new float[embeddingArray.size()];
            for (int j = 0; j < embeddingArray.size(); j++) {
                vector[j] = embeddingArray.get(j).getAsFloat();
            }
            embeddings.add(vector);
        }

        return embeddings;
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private String readErrorResponse(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
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

    @Override
    public void dispose() {
        executorService.shutdown();
        LOG.info("API-based LocalEmbeddingService disposed");
    }

    public enum ModelType {
        API_MODEL
    }
}