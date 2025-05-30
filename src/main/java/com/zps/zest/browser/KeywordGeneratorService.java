package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service to generate keywords from user queries using LLM.
 */
public class KeywordGeneratorService {
    private static final Logger LOG = Logger.getInstance(KeywordGeneratorService.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final ConfigurationManager configManager;
    private final HttpClient httpClient;
    
    public KeywordGeneratorService(@NotNull Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
    
    /**
     * Generates keywords from a user query using the LLM.
     * Returns up to 10 keywords that represent functions, patterns, or concepts to search for.
     */
    public CompletableFuture<List<String>> generateKeywords(String userQuery) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build prompt for keyword generation
                String prompt = buildKeywordPrompt(userQuery);
                
                // Call LLM API
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getModel());
                requestBody.addProperty("temperature", 0.3); // Lower temperature for more focused results
                
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(configManager.getApiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configManager.getAuthToken())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    return parseKeywordsFromResponse(response.body());
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    return getFallbackKeywords(userQuery);
                }
                
            } catch (Exception e) {
                LOG.error("Error generating keywords", e);
                return getFallbackKeywords(userQuery);
            }
        });
    }
    
    /**
     * Builds the prompt for keyword generation.
     */
    private String buildKeywordPrompt(String userQuery) {
        return "Extract up to 10 keywords from this programming request. " +
               "Focus on: function names, class names, method names, patterns, or technical terms " +
               "that would help find similar code in the project.\n\n" +
               "Rules:\n" +
               "- Return ONLY the keywords, one per line\n" +
               "- No explanations or additional text\n" +
               "- Prioritize specific technical terms over generic words\n" +
               "- Include both exact matches and related concepts\n\n" +
               "Request: " + userQuery + "\n\n" +
               "Keywords:";
    }
    
    /**
     * Parses keywords from LLM response.
     */
    private List<String> parseKeywordsFromResponse(String responseBody) {
        List<String> keywords = new ArrayList<>();
        
        try {
            JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = response.getAsJsonArray("choices");
            
            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                
                // Parse keywords from content (one per line)
                String[] lines = content.trim().split("\n");
                for (String line : lines) {
                    String keyword = line.trim();
                    if (!keyword.isEmpty() && keyword.length() > 2) {
                        keywords.add(keyword);
                        if (keywords.size() >= 10) break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }
        
        return keywords.isEmpty() ? getFallbackKeywords("") : keywords;
    }
    
    /**
     * Provides fallback keywords when LLM is unavailable.
     */
    private List<String> getFallbackKeywords(String userQuery) {
        // Simple fallback: extract potential function/class names
        List<String> keywords = new ArrayList<>();
        
        String[] words = userQuery.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s_]", " ")
            .split("\\s+");
        
        for (String word : words) {
            // Look for camelCase or snake_case patterns
            if (word.length() > 3 && 
                (word.contains("_") || !word.equals(word.toLowerCase()))) {
                keywords.add(word);
            }
        }
        
        // Add some common patterns based on query words
        if (userQuery.toLowerCase().contains("button")) {
            keywords.add("onClick");
            keywords.add("handleClick");
        }
        if (userQuery.toLowerCase().contains("form")) {
            keywords.add("onSubmit");
            keywords.add("handleSubmit");
        }
        if (userQuery.toLowerCase().contains("api") || userQuery.toLowerCase().contains("fetch")) {
            keywords.add("fetch");
            keywords.add("axios");
            keywords.add("request");
        }
        
        return keywords.stream().distinct().limit(10).toList();
    }
}
