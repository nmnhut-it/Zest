package com.zps.zest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
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

/**
 * Enhanced stage for making API calls to the LLM with streaming support and background processing.
 */
public class LlmApiCallStage implements PipelineStage {
    private static final String NOTIFICATION_GROUP_ID = "Zest LLM";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int CONNECTION_TIMEOUT_MS = 480_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    // Streaming feedback settings
    private Notification activeNotification;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final StringBuilder streamedResponse = new StringBuilder();
    private long lastUpdateTimestamp = 0;
    private static final long UPDATE_THROTTLE_MS = 500; // Update HUD at most every 500ms

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        // Run the API call in a background task
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("No project available");
        }

        ConfigurationManager config = context.getConfig();
        if (config == null) {
            throw new PipelineExecutionException("No configuration available");
        }

        // Determine if streaming should be used (could be in context or config)
        boolean useStreaming = shouldUseStreaming(config);

        // Create a CompletableFuture to return the API response
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        // Reset state for new API call
        isCancelled.set(false);
        streamedResponse.setLength(0);

        // Show initial notification for streaming mode
        if (useStreaming) {
            showStreamingNotification(project, "Starting LLM request...");
        }

        // Run in a background task
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Calling LLM API", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Initializing LLM API call...");
                indicator.setFraction(0.1);

                // Try multiple times in case of failure
                String response = null;
                Exception lastException = null;

                for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                    if (indicator.isCanceled() || isCancelled.get()) {
                        responseFuture.completeExceptionally(new PipelineExecutionException("LLM API call cancelled"));
                        return;
                    }

                    try {
                        indicator.setText("Calling LLM API (attempt " + (attempt + 1) + ")...");
                        indicator.setFraction(0.2 + (0.6 * attempt / MAX_RETRY_ATTEMPTS));

                        String model = context.getModel(config);
                        if (useStreaming) {
                            // For streaming, we process chunks as they come in
                            streamLlmApi(
                                    config.getApiUrl(),
                                    model,
                                    config.getAuthToken(),
                                    context.getPrompt(),
                                    indicator,
                                    project
                            );

                            // If we get here without exception, streaming completed successfully
                            response = streamedResponse.toString();
                            break;
                        } else {
                            // For non-streaming, we make a regular API call
                            response = callLlmApi(
                                    config.getApiUrl(),
                                    model,
                                    config.getAuthToken(),
                                    context.getPrompt()
                            );

                            if (response != null && !response.isEmpty()) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        lastException = e;
                        if (indicator.isCanceled() || isCancelled.get()) {
                            responseFuture.completeExceptionally(new PipelineExecutionException("LLM API call cancelled"));
                            return;
                        }

                        if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                            indicator.setText("API call failed, retrying (" + (attempt + 2) + "/" + MAX_RETRY_ATTEMPTS + ")");
                        }
                    }
                }

                // Update progress based on result
                if (response != null && !response.isEmpty()) {
                    indicator.setText("LLM API call successful");
                    indicator.setFraction(1.0);
                    responseFuture.complete(response);

                    // Update notification for streaming mode
                    if (useStreaming) {
                        updateStreamingNotification(project, "LLM response complete", true);
                    }
                } else {
                    String errorMessage = "Failed to get response from LLM API";
                    if (lastException != null) {
                        errorMessage += ": " + lastException.getMessage();
                    }
                    indicator.setText(errorMessage);
                    responseFuture.completeExceptionally(new PipelineExecutionException(errorMessage, lastException));

                    // Update notification for streaming mode
                    if (useStreaming) {
                        updateStreamingNotification(project, "LLM API error: " + errorMessage, true);
                    }
                }
            }
        });

        try {
            String response = responseFuture.get();
            if (response == null || response.isEmpty()) {
                throw new PipelineExecutionException("Failed to generate response");
            }
            context.setApiResponse(response);
        } catch (Exception e) {
            throw new PipelineExecutionException("API call failed: " + e.getMessage(), e);
        } finally {
            // Ensure notification is closed if it was created
            if (activeNotification != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    activeNotification.expire();
                    activeNotification = null;
                });
            }
        }
    }

    /**
     * Makes a streaming API call to the LLM service.
     *
     * @param apiUrl The API URL
     * @param model The model to use
     * @param authToken The authentication token
     * @param prompt The prompt to send
     * @param indicator The progress indicator
     * @param project The current project
     * @throws IOException If an error occurs during the API call
     */
    private void streamLlmApi(String apiUrl, String model, String authToken, String prompt,
                              ProgressIndicator indicator, Project project) throws IOException {
        // Determine which API format to use based on URL
        if (apiUrl.contains("openwebui") || apiUrl.contains("chat.zingplay") || apiUrl.contains("talk.zingplay")) {
            streamOpenWebUIApi(apiUrl, model, authToken, prompt, indicator, project);
        } else {
            streamOllamaApi(apiUrl, model, authToken, prompt, indicator, project);
        }
    }

    /**
     * Streams from OpenWebUI/Zingplay compatible API.
     */
    private void streamOpenWebUIApi(String apiUrl, String model, String authToken, String prompt,
                                    ProgressIndicator indicator, Project project) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = setupConnection(url, authToken);

        // OpenWebUI uses a different payload format with streaming enabled
        String payload = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true}",
                model,
                escapeJson(prompt)
        );

        sendPayload(connection, payload);
        processStreamingResponse(connection, indicator, project, response -> {
            try {
                // Parse each chunk of streamed data
                if (response.startsWith("data: ")) {
                    String jsonData = response.substring(6).trim();
                    if (jsonData.equals("[DONE]")) {
                        return null; // End of stream
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
    }

    /**
     * Streams from Ollama compatible API.
     */
    private void streamOllamaApi(String apiUrl, String model, String authToken, String prompt,
                                 ProgressIndicator indicator, Project project) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = setupConnection(url, authToken);

        // Ollama streaming payload format
        String payload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":true,\"options\":{\"num_predict\":%d}}",
                model,
                escapeJson(prompt),
                32000
        );

        sendPayload(connection, payload);
        processStreamingResponse(connection, indicator, project, response -> {
            try {
                JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
                if (jsonObject.has("response")) {
                    return jsonObject.get("response").getAsString();
                }
                return "";
            } catch (Exception e) {
                return "";
            }
        });
    }

    /**
     * Helper method to set up an HTTP connection.
     */
    private HttpURLConnection setupConnection(URL url, String authToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        if (authToken != null && !authToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        return connection;
    }

    /**
     * Helper method to send the payload to the connection.
     */
    private void sendPayload(HttpURLConnection connection, String payload) throws IOException {
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = payload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    }

    /**
     * Functional interface for processing streamed chunks.
     */
    @FunctionalInterface
    private interface StreamChunkProcessor {
        String processChunk(String chunk);
    }

    /**
     * Processes a streaming response from the API.
     */
    private void processStreamingResponse(HttpURLConnection connection, ProgressIndicator indicator,
                                          Project project, StreamChunkProcessor processor) throws IOException {
        int responseCode = connection.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                int totalLines = 0;
                int progress = 0;

                indicator.setText("Receiving LLM response...");

                while ((line = reader.readLine()) != null) {
                    if (indicator.isCanceled() || isCancelled.get()) {
                        break;
                    }

                    // Process the chunk
                    String content = processor.processChunk(line);
                    if (content != null) {
                        streamedResponse.append(content);

                        // Update the notification periodically to avoid UI freezing
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTimestamp > UPDATE_THROTTLE_MS) {
                            updateStreamingNotification(project, getPreviewText(streamedResponse), false);
                            lastUpdateTimestamp = currentTime;
                        }

                        // Update progress occasionally
                        totalLines++;
                        if (totalLines % 10 == 0) {
                            progress = Math.min(progress + 1, 99);
                            indicator.setFraction(0.2 + (progress * 0.8 / 100.0));
                        }
                    }
                }

                indicator.setFraction(1.0);
            }
        } else {
            // Handle error response
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {

                StringBuilder errorResponse = new StringBuilder();
                String line;

                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }

                String errorMessage = "API Error: " + errorResponse;
                updateStreamingNotification(project, "Error: " + errorMessage, true);
                throw new IOException(errorMessage);
            }
        }
    }

    /**
     * Standard (non-streaming) API call method.
     */
    private String callLlmApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        // Determine which API format to use based on URL
        if (apiUrl.contains("openwebui") || apiUrl.contains("chat.zingplay") || apiUrl.contains("talk.zingplay")) {
            return callOpenWebUIApi(apiUrl, model, authToken, prompt);
        } else {
            return callOllamaApi(apiUrl, model, authToken, prompt);
        }
    }

    /**
     * Makes a non-streaming API call to the OpenWebUI/Zingplay compatible API.
     */
    private String callOpenWebUIApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = setupConnection(url, authToken);

            // OpenWebUI payload format without streaming
            String payload = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
                    model,
                    escapeJson(prompt)
            );

            sendPayload(connection, payload);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse the JSON response
                    JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                    if (jsonObject.has("choices") && jsonObject.getAsJsonArray("choices").size() > 0) {
                        JsonObject messageObject = jsonObject.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message");

                        if (messageObject.has("content")) {
                            return messageObject.get("content").getAsString();
                        }
                    }
                    return null;
                }
            } else {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {

                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    throw new IOException("API Error: " + response);
                }
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            throw new IOException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Makes a non-streaming API call to the Ollama compatible API.
     */
    private String callOllamaApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = setupConnection(url, authToken);

            // Ollama payload format without streaming
            String payload = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"num_predict\":%d}}",
                    model,
                    escapeJson(prompt),
                    32000
            );

            sendPayload(connection, payload);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse the JSON response
                    JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                    if (jsonObject.has("response")) {
                        return jsonObject.get("response").getAsString();
                    }
                    return null;
                }
            } else {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {

                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    throw new IOException("API Error: " + response);
                }
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            throw new IOException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Escapes JSON string values.
     */
    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Determines if streaming should be used based on configuration.
     */
    private boolean shouldUseStreaming(ConfigurationManager config) {
        // Use the configured value from the configuration
        return true;
    }

    /**
     * Shows the initial streaming notification.
     */
    private void showStreamingNotification(Project project, @NlsContexts.NotificationContent String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (activeNotification != null) {
                activeNotification.expire();
            }

            activeNotification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .setIcon(null)
                    .addAction(NotificationAction.create("Cancel", (e, notification) -> {
                        isCancelled.set(true);
                        notification.expire();
                    }));

            activeNotification.notify(project);
        });
    }

    /**
     * Updates the streaming notification with new content.
     */
    private void updateStreamingNotification(Project project,
                                             @NlsContexts.NotificationContent String message,
                                             boolean isComplete) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (activeNotification != null) {
                activeNotification.expire();
            }

            NotificationType type = isComplete ? NotificationType.INFORMATION : NotificationType.INFORMATION;

            activeNotification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, type)
                    .setIcon(null);

            if (!isComplete) {
                activeNotification.addAction(NotificationAction.create("Cancel", (e, notification) -> {
                    isCancelled.set(true);
                    notification.expire();
                }));
            }

            activeNotification.notify(project);
        });
    }

    /**
     * Gets a preview of the text for the notification.
     */
    private String getPreviewText(StringBuilder fullText) {
        // Limit the preview to avoid huge notifications
        int maxLength = 100;
        String text;

        if (fullText.length() > maxLength) {
            // Show the last maxLength characters instead of the first ones
            text = "..." + fullText.substring(fullText.length() - maxLength);
        } else {
            text = fullText.toString();
        }

        // Replace newlines with spaces for better display
        text = text.replace("\n", " ");

        return "Receiving: " + text;
    }
}