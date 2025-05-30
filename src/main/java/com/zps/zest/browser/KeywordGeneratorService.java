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
        LOG.info("=== Keyword Generation Started ===");
        LOG.info("Query: " + userQuery);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build prompt for keyword generation
                String prompt = buildKeywordPrompt(userQuery);
                LOG.info("Keyword prompt length: " + prompt.length() + " chars");
                
                // Call LLM API
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getLiteModel());
                requestBody.addProperty("temperature", 0.3); // Lower temperature for more focused results
                
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);
                
                String apiUrl = configManager.getApiUrl();
                LOG.info("Calling LLM API at: " + apiUrl);
                LOG.info("Using model: " + configManager.getLiteModel());
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configManager.getAuthToken())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                    .build();
                
                long startTime = System.currentTimeMillis();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - startTime;
                
                LOG.info("LLM API response received in " + duration + "ms, status: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    List<String> keywords = parseKeywordsFromResponse(response.body());
                    LOG.info("Successfully generated " + keywords.size() + " keywords: " + keywords);
                    return keywords;
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    LOG.info("Falling back to simple keyword extraction");
                    return getFallbackKeywords(userQuery);
                }
                
            } catch (Exception e) {
                LOG.error("Error generating keywords", e);
                LOG.info("Falling back to simple keyword extraction due to error");
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
        LOG.info("Parsing keywords from LLM response");
        List<String> keywords = new ArrayList<>();
        
        try {
            JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = response.getAsJsonArray("choices");
            
            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                
                LOG.info("LLM response content length: " + content.length());
                
                // Parse keywords from content (one per line)
                String[] lines = content.trim().split("\n");
                for (String line : lines) {
                    String keyword = line.trim();
                    if (!keyword.isEmpty() && keyword.length() > 2) {
                        keywords.add(keyword);
                        if (keywords.size() >= 10) break;
                    }
                }
                
                LOG.info("Parsed " + keywords.size() + " keywords from LLM response");
            } else {
                LOG.warn("No choices found in LLM response");
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }
        
        if (keywords.isEmpty()) {
            LOG.info("No keywords parsed, using fallback");
            return getFallbackKeywords("");
        }
        
        return keywords;
    }
    
    /**
     * Provides fallback keywords when LLM is unavailable.
     */
    private List<String> getFallbackKeywords(String userQuery) {
        LOG.info("Generating fallback keywords for: " + userQuery);
        
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
        
        List<String> result = keywords.stream().distinct().limit(10).toList();
        LOG.info("Generated " + result.size() + " fallback keywords: " + result);
        return result;
    }
}
