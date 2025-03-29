package com.zps.zest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets; /**
 * Stage for making the API call to the LLM.
 */
public class LlmApiCallStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        // Run the API call in a background task
        Project project = context.getProject();

        Task.WithResult<String, Exception> task = new Task.WithResult<String, Exception>(
                project, "Calling LLM API", true) {
            @Override
            protected String compute(@NotNull ProgressIndicator indicator) throws Exception {
                indicator.setIndeterminate(false);
                indicator.setText("Calling LLM API...");
                indicator.setFraction(0.5);

                // Try up to 3 times
                ConfigurationManager config = context.getConfig();
                String response = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        response = callLlmApi(
                                config.getApiUrl(),
                                config.getModel(),
                                config.getAuthToken(),
                                context.getPrompt()
                        );
                        if (response != null) {
                            System.out.println(response);
                            indicator.setText("LLM API call successful");
                            indicator.setFraction(1.0);
                            break;
                        }
                    } catch (IOException e) {
                        if (i == 2) {
                            throw e; // Rethrow on last attempt
                        }
                        indicator.setText("Calling LLM API: FAILED - retry " + (i + 1));
                    }
                }

                return response;
            }
        };

        try {
            String response = ProgressManager.getInstance().run(task);
            if (response == null || response.isEmpty()) {
                throw new PipelineExecutionException("Failed to generate test code");
            }
            context.setApiResponse(response);
        } catch (Exception e) {
            throw new PipelineExecutionException("API call failed: " + e.getMessage(), e);
        }
    }

    private String callLlmApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        // Determine which API format to use based on URL
        if (apiUrl.contains("openwebui") || apiUrl.contains("zingplay")) {
            return callOpenWebUIApi(apiUrl, model, authToken, prompt);
        } else {
            return callOllamaApi(apiUrl, model, authToken, prompt);
        }
    }

    private String callOpenWebUIApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            connection.setConnectTimeout(480_0000);
            connection.setReadTimeout(120_0000);
            connection.setDoOutput(true);

            // OpenWebUI uses a different payload format
            String payload = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
                    model,
                    escapeJson(prompt)
            );

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

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
                    System.err.println("API Error: " + response);
                }
                return null;
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String callOllamaApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", authToken);
            }
            connection.setConnectTimeout(480_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);

            String payload = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"num_predict\":%d}}",
                    model,
                    escapeJson(prompt),
                    32000
            );

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

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
                    System.err.println("API Error: " + response);
                }
                return null;
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
