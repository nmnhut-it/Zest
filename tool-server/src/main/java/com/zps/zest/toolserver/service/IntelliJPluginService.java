package com.zps.zest.toolserver.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Service for communicating with the IntelliJ Zest plugin's Tool API Server.
 * Handles HTTP communication and error handling for all tool requests.
 */
@Service
public class IntelliJPluginService {

    private static final Logger log = LoggerFactory.getLogger(IntelliJPluginService.class);
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String pluginBaseUrl;

    public IntelliJPluginService(
        @Value("${intellij.plugin.url:http://localhost:63342}") String pluginBaseUrl
    ) {
        this.pluginBaseUrl = pluginBaseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        log.info("Initialized IntelliJ Plugin Service with base URL: {}", pluginBaseUrl);
    }

    /**
     * Call a tool endpoint on the IntelliJ plugin.
     */
    public ToolResponse callTool(String endpoint, Object requestBody) {
        try {
            String url = pluginBaseUrl + endpoint;
            String jsonBody = GSON.toJson(requestBody);

            log.debug("Calling IntelliJ plugin: {} with body: {}", url, jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = GSON.fromJson(response.body(), JsonObject.class);
                boolean success = jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
                String result = jsonResponse.has("result") ? jsonResponse.get("result").getAsString() : null;
                String error = jsonResponse.has("error") ? jsonResponse.get("error").getAsString() : null;

                return new ToolResponse(success, result, error);
            } else {
                log.error("IntelliJ plugin returned status {}: {}", response.statusCode(), response.body());
                return new ToolResponse(false, null,
                    "Plugin returned status " + response.statusCode() + ": " + response.body());
            }

        } catch (java.net.ConnectException e) {
            log.error("Cannot connect to IntelliJ plugin at {}", pluginBaseUrl, e);
            return new ToolResponse(false, null,
                "Cannot connect to IntelliJ plugin. Make sure the plugin is running.");
        } catch (Exception e) {
            log.error("Error calling IntelliJ plugin", e);
            return new ToolResponse(false, null, "Error: " + e.getMessage());
        }
    }

    /**
     * Check if the IntelliJ plugin is reachable.
     */
    public boolean isPluginAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pluginBaseUrl + "/api/health"))
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Response from a tool call.
     */
    public record ToolResponse(boolean success, String result, String error) {
        public String getResultOrError() {
            return success ? result : ("❌ Error: " + error);
        }
    }
}
