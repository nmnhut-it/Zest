package com.zps.zest.research.analyzer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.research.context.ResearchContext;
import com.zps.zest.research.search.SearchResults;
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
 * Analyzes search results using LLM to extract insights and generate new keywords.
 */
public class LLMAnalyzer {
    private static final Logger LOG = Logger.getInstance(LLMAnalyzer.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final ConfigurationManager configManager;
    private final HttpClient httpClient;
    
    public LLMAnalyzer(@NotNull Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
    
    /**
     * Analyzes search results and determines next steps.
     */
    public CompletableFuture<AnalysisResult> analyzeResults(
            String originalQuery,
            int iteration,
            List<String> currentKeywords,
            SearchResults results,
            ResearchContext previousContext) {
        
        LOG.info("=== LLM Analysis Started ===");
        LOG.info("Iteration: " + iteration);
        LOG.info("Current keywords: " + currentKeywords);
        LOG.info("Total results to analyze: " + results.getTotalResults());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build analysis prompt
                String prompt = buildAnalysisPrompt(
                    originalQuery, iteration, currentKeywords, results, previousContext
                );
                
                LOG.info("Analysis prompt length: " + prompt.length() + " chars");
                
                // Call LLM API
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getLiteModel());
                requestBody.addProperty("temperature", 0.3);
                requestBody.addProperty("max_tokens", 1000);
                
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);
                
                String apiUrl = configManager.getApiUrl();
                LOG.info("Calling LLM API for analysis");
                
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
                
                LOG.info("LLM analysis completed in " + duration + "ms, status: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    AnalysisResult analysis = parseAnalysisResponse(response.body());
                    LOG.info("Analysis complete: " + analysis.isSearchComplete() + 
                            ", new keywords: " + analysis.getNextKeywords().size());
                    return analysis;
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    return createDefaultAnalysis(results);
                }
                
            } catch (Exception e) {
                LOG.error("Error in LLM analysis", e);
                return createDefaultAnalysis(results);
            }
        });
    }
    
    /**
     * Builds the analysis prompt for the LLM.
     */
    private String buildAnalysisPrompt(
            String originalQuery,
            int iteration,
            List<String> currentKeywords,
            SearchResults results,
            ResearchContext previousContext) {
        
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are analyzing code search results to help find relevant code for a user query.\n");
        prompt.append("Your task is to:\n");
        prompt.append("1. Analyze the search results\n");
        prompt.append("2. Extract new keywords/class names/method names to search for\n");
        prompt.append("3. Determine if we have found enough relevant code\n");
        prompt.append("4. Provide a brief summary of findings\n\n");
        
        prompt.append("ORIGINAL USER QUERY: ").append(originalQuery).append("\n\n");
        prompt.append("ITERATION: ").append(iteration + 1).append("\n");
        prompt.append("CURRENT KEYWORDS: ").append(String.join(", ", currentKeywords)).append("\n\n");
        
        // Add previous keywords to avoid repetition
        if (previousContext != null && !previousContext.getAllDiscoveredKeywords().isEmpty()) {
            prompt.append("PREVIOUSLY SEARCHED KEYWORDS (avoid these): ");
            prompt.append(String.join(", ", previousContext.getAllDiscoveredKeywords()));
            prompt.append("\n\n");
        }
        
        // Add search results summary
        prompt.append("SEARCH RESULTS:\n");
        
        // Git results
        if (results.getGitResults().size() > 0) {
            prompt.append("\nGIT COMMITS (").append(results.getGitResults().size()).append(" results):\n");
            summarizeGitResults(prompt, results.getGitResults());
        }
        
        // Unstaged changes
        if (results.getUnstagedResults().size() > 0) {
            prompt.append("\nUNSTAGED CHANGES (").append(results.getUnstagedResults().size()).append(" results):\n");
            summarizeUnstagedResults(prompt, results.getUnstagedResults());
        }
        
        // Project results
        if (results.getProjectResults().size() > 0) {
            prompt.append("\nPROJECT CODE (").append(results.getProjectResults().size()).append(" results):\n");
            summarizeProjectResults(prompt, results.getProjectResults());
        }
        
        // Instructions for response format
        prompt.append("\n\nRESPOND IN THIS EXACT FORMAT:\n");
        prompt.append("SUMMARY: <brief summary of what was found>\n");
        prompt.append("COMPLETE: <YES or NO - whether we have found enough relevant code>\n");
        prompt.append("KEYWORDS: <comma-separated list of new keywords to search, or NONE>\n");
        prompt.append("\nRules:\n");
        prompt.append("- Set COMPLETE to YES if we found the main code the user is looking for\n");
        prompt.append("- Set COMPLETE to NO if we should search for more related code\n");
        prompt.append("- For KEYWORDS, suggest specific function/class/method names found in the results\n");
        prompt.append("- Avoid generic words, focus on specific code identifiers\n");
        prompt.append("- If COMPLETE is YES, set KEYWORDS to NONE\n");
        
        return prompt.toString();
    }
    
    /**
     * Summarizes git results for the prompt.
     */
    private void summarizeGitResults(StringBuilder prompt, JsonArray gitResults) {
        for (int i = 0; i < gitResults.size(); i++) {
            JsonObject result = gitResults.get(i).getAsJsonObject();
            String keyword = result.get("keyword").getAsString();
            JsonArray commits = result.getAsJsonArray("commits");
            
            prompt.append("- Keyword '").append(keyword).append("': ");
            prompt.append(commits.size()).append(" commits found\n");
            
            // Show first few commit messages
            for (int j = 0; j < Math.min(3, commits.size()); j++) {
                JsonObject commit = commits.get(j).getAsJsonObject();
                String message = commit.get("message").getAsString();
                if (message.length() > 80) {
                    message = message.substring(0, 77) + "...";
                }
                prompt.append("  - ").append(message).append("\n");
            }
        }
    }
    
    /**
     * Summarizes unstaged results for the prompt.
     */
    private void summarizeUnstagedResults(StringBuilder prompt, JsonArray unstagedResults) {
        for (int i = 0; i < unstagedResults.size(); i++) {
            JsonObject result = unstagedResults.get(i).getAsJsonObject();
            String file = result.get("file").getAsString();
            String keyword = result.get("keyword").getAsString();
            
            prompt.append("- File '").append(file).append("' (keyword: ").append(keyword).append(")\n");
            
            if (result.has("analysis")) {
                JsonObject analysis = result.getAsJsonObject("analysis");
                if (analysis.has("changedFunctions")) {
                    JsonArray functions = analysis.getAsJsonArray("changedFunctions");
                    prompt.append("  Changed functions: ");
                    for (int j = 0; j < functions.size(); j++) {
                        if (j > 0) prompt.append(", ");
                        JsonObject func = functions.get(j).getAsJsonObject();
                        prompt.append(func.get("name").getAsString());
                    }
                    prompt.append("\n");
                }
            }
        }
    }
    
    /**
     * Summarizes project results for the prompt.
     */
    private void summarizeProjectResults(StringBuilder prompt, JsonArray projectResults) {
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            String type = result.get("type").getAsString();
            String keyword = result.get("keyword").getAsString();
            JsonArray matches = result.getAsJsonArray("matches");
            
            prompt.append("- ").append(type.toUpperCase()).append(" matches for '");
            prompt.append(keyword).append("': ").append(matches.size()).append(" results\n");
            
            if ("function".equals(type)) {
                // List function names
                for (int j = 0; j < Math.min(5, matches.size()); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    String funcName = match.get("name").getAsString();
                    String file = match.get("file").getAsString();
                    prompt.append("  - ").append(funcName).append(" in ").append(file).append("\n");
                }
            } else if ("text".equals(type)) {
                // Show files with matches
                for (int j = 0; j < Math.min(3, matches.size()); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    String file = match.get("file").getAsString();
                    prompt.append("  - ").append(file).append("\n");
                }
            }
        }
    }
    
    /**
     * Parses the LLM response into an AnalysisResult.
     */
    private AnalysisResult parseAnalysisResponse(String responseBody) {
        LOG.info("Parsing LLM analysis response");
        
        String summary = "";
        boolean isComplete = false;
        List<String> keywords = new ArrayList<>();
        
        try {
            JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = response.getAsJsonArray("choices");
            
            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                
                LOG.info("LLM response content length: " + content.length());
                
                // Parse the structured response
                String[] lines = content.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    
                    if (line.startsWith("SUMMARY:")) {
                        summary = line.substring("SUMMARY:".length()).trim();
                    } else if (line.startsWith("COMPLETE:")) {
                        String completeValue = line.substring("COMPLETE:".length()).trim();
                        isComplete = "YES".equalsIgnoreCase(completeValue);
                    } else if (line.startsWith("KEYWORDS:")) {
                        String keywordsValue = line.substring("KEYWORDS:".length()).trim();
                        if (!"NONE".equalsIgnoreCase(keywordsValue) && !keywordsValue.isEmpty()) {
                            String[] keywordArray = keywordsValue.split(",");
                            for (String keyword : keywordArray) {
                                keyword = keyword.trim();
                                if (!keyword.isEmpty() && keyword.length() > 2) {
                                    keywords.add(keyword);
                                }
                            }
                        }
                    }
                }
                
                LOG.info("Parsed analysis - Complete: " + isComplete + ", Keywords: " + keywords.size());
                
            } else {
                LOG.warn("No choices found in LLM response");
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }
        
        // Ensure we have valid data
        if (summary.isEmpty()) {
            summary = "Analysis of search results for iteration " + 
                     (keywords.isEmpty() ? "(final)" : "(continuing)");
        }
        
        return new AnalysisResult(summary, keywords, isComplete, responseBody);
    }
    
    /**
     * Creates a default analysis when LLM is unavailable.
     */
    private AnalysisResult createDefaultAnalysis(SearchResults results) {
        LOG.info("Creating default analysis due to LLM unavailability");
        
        // Simple heuristic: if we have more than 5 total results, consider it complete
        boolean isComplete = results.getTotalResults() > 5;
        
        String summary = "Found " + results.getTotalResults() + " results: " +
                        results.getGitResults().size() + " git, " +
                        results.getUnstagedResults().size() + " unstaged, " +
                        results.getProjectResults().size() + " project files.";
        
        // No new keywords in default analysis
        List<String> keywords = new ArrayList<>();
        
        return new AnalysisResult(summary, keywords, isComplete, "");
    }
}