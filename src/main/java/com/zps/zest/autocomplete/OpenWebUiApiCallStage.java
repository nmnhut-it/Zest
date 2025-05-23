package com.zps.zest.autocomplete;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.LlmApiCallStage;
import com.zps.zest.PipelineExecutionException;

import java.io.IOException;

/**
 * LLM API call stage for OpenWebUI-compatible APIs, fully configured via constructor/builder.
 */
public class OpenWebUiApiCallStage extends LlmApiCallStage {

    public static final Gson GSON = new Gson();
    private static final int MAX_COMPLETION_TOKENS = 10;
    private static final double AUTOCOMPLETE_TEMPERATURE = -1;
    private final String model;
    private final String systemPrompt;
    private final boolean streaming;

    public OpenWebUiApiCallStage(Builder builder) {
        builder.build();
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.streaming = builder.streaming;
    }

    public static String buildOpenWebUiPayload(String model, String systemPrompt, String userPrompt, boolean stream, double autocompleteTemperature, int maxCompletionTokens) {
        Gson gson = GSON;
        JsonObject root = new JsonObject();

        // Top-level fields
        root.addProperty("model", model);
        root.addProperty("stream", stream);

        // Messages array
        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemPrompt);
            messages.add(systemMsg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        root.add("messages", messages);

        // Params object
        JsonObject params = new JsonObject();
        if (autocompleteTemperature > 0) {
            params.addProperty("temperature", autocompleteTemperature);
        }
        params.addProperty("max_tokens", maxCompletionTokens);
        root.add("params", params);

        return gson.toJson(root);
    }

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        Project project = context.getProject();
        if (project == null) throw new PipelineExecutionException("No project available");

        String apiUrl = context.getConfig().getApiUrl();
        String authToken = context.getConfig().getAuthToken();
        String userPrompt = context.getPrompt();

        String payload = buildOpenWebUiPayload(model, systemPrompt, userPrompt, streaming, AUTOCOMPLETE_TEMPERATURE, MAX_COMPLETION_TOKENS);

        try {
            String response;

            response = callOpenWebUiApi(apiUrl, authToken, payload);

            context.setApiResponse(response);
        } catch (Exception e) {
            throw new PipelineExecutionException("OpenWebUI API call failed: " + e.getMessage(), e);
        }
    }

    private String callOpenWebUiApi(String apiUrl, String authToken, String payload) throws IOException {
        java.net.URL url = new java.net.URL(apiUrl);
        java.net.HttpURLConnection connection = setupConnection(url, authToken);
        sendPayload(connection, payload);

        int responseCode = connection.getResponseCode();
        if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                if (jsonObject.has("choices") && jsonObject.getAsJsonArray("choices").size() > 0) {
                    JsonObject messageObject = jsonObject.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");

                    if (messageObject.has("content")) {
                        return messageObject.get("content").getAsString();
                    }
                }
                return null;
            }
        } else {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("API Error: " + response);
            }
        }
    }

    private String streamOpenWebUiApi(String apiUrl, String authToken, String payload, Project project, ProgressIndicator indicator) throws IOException {
        java.net.URL url = new java.net.URL(apiUrl);
        java.net.HttpURLConnection connection = setupConnection(url, authToken);
        sendPayload(connection, payload);

        this.streamedResponse.setLength(0);
        this.isCancelled.set(false);

        processStreamingResponse(connection, indicator, project, response -> {
            try {
                if (response.startsWith("data: ")) {
                    String jsonData = response.substring(6).trim();
                    if (jsonData.equals("[DONE]")) {
                        return null;
                    }
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    if (jsonObject.has("choices") && jsonObject.getAsJsonArray("choices").size() > 0) {
                        JsonObject choice = jsonObject.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("delta") && choice.getAsJsonObject("delta").has("content")) {
                            return choice.getAsJsonObject("delta").get("content").getAsString();
                        }
                    }
                }
                return "";
            } catch (Exception e) {
                return "";
            }
        });

        return this.streamedResponse.toString();
    }

    public static class Builder {
        private String model;
        private String systemPrompt;
        private boolean streaming = true;

        public Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public OpenWebUiApiCallStage build() {
            if (model == null || systemPrompt == null)
                throw new IllegalStateException("model and systemPrompt are required");
            return new OpenWebUiApiCallStage(this);
        }
    }
}