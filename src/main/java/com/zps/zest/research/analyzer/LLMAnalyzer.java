package com.zps.zest.research.analyzer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Analyzes search results using LLM to build comprehensive context for better code understanding.
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
     * Analyzes search results to build comprehensive context.
     */
    public CompletableFuture<AnalysisResult> analyzeResults(
            String originalQuery,
            int iteration,
            List<String> currentKeywords,
            SearchResults results,
            ResearchContext previousContext) {

        LOG.info("=== Context Analysis Started ===");
        LOG.info("Iteration: " + iteration);
        LOG.info("Current keywords: " + currentKeywords);
        LOG.info("Total results to analyze: " + results.getTotalResults());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build context-focused analysis prompt
                String prompt = buildContextAnalysisPrompt(
                        originalQuery, iteration, currentKeywords, results, previousContext
                );

                LOG.info("Analysis prompt length: " + prompt.length() + " chars");

                // Call LLM API
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getLiteModel());
                requestBody.addProperty("temperature", 0.3);
                requestBody.addProperty("max_tokens", 1500); // Increased for richer analysis

                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);

                String apiUrl = configManager.getApiUrl();
                LOG.info("Calling LLM API for context analysis");

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
                    AnalysisResult analysis = parseContextAnalysisResponse(response.body(), iteration);
                    LOG.info("Analysis confidence: " + analysis.getConfidenceLevel() +
                            ", next searches: " + analysis.getNextKeywords().size());
                    return analysis;
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    return createDefaultContextAnalysis(results, iteration);
                }

            } catch (Exception e) {
                LOG.error("Error in LLM analysis", e);
                return createDefaultContextAnalysis(results, iteration);
            }
        });
    }

    /**
     * Builds the context-focused analysis prompt.
     */
    private String buildContextAnalysisPrompt(
            String originalQuery,
            int iteration,
            List<String> currentKeywords,
            SearchResults results,
            ResearchContext previousContext) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("You are analyzing code search results to build context for an LLM assistant.\n\n");

        prompt.append("GOAL: Create a comprehensive understanding of the codebase around the user's query.\n\n");

        prompt.append("USER QUERY: ").append(originalQuery).append("\n\n");
        prompt.append("ITERATION: ").append(iteration + 1).append(" of 5\n");
        prompt.append("SEARCH KEYWORDS USED: ").append(String.join(", ", currentKeywords)).append("\n\n");

        // Add iteration-specific focus
        appendIterationFocus(prompt, iteration);

        // Add previous discoveries if available
        if (previousContext != null && !previousContext.getAllDiscoveredKeywords().isEmpty()) {
            prompt.append("ALREADY EXPLORED: ");
            prompt.append(String.join(", ", previousContext.getAllDiscoveredKeywords()));
            prompt.append("\n\n");
        }

        // Add current findings
        prompt.append("CURRENT FINDINGS:\n");
        appendDetailedFindings(prompt, results);

        // Instructions for analysis
        prompt.append("\n\nANALYZE AND PROVIDE:\n\n");
        prompt.append("1. CODE RELATIONSHIPS:\n");
        prompt.append("   - Key classes/functions and how they interact\n");
        prompt.append("   - Dependencies between components\n");
        prompt.append("   - Common patterns observed\n\n");

        prompt.append("2. CONTEXT GAPS:\n");
        prompt.append("   - What related code should we look for?\n");
        prompt.append("   - What dependencies are we missing?\n");
        prompt.append("   - What test files might be relevant?\n\n");

        prompt.append("3. SEMANTIC INSIGHTS:\n");
        prompt.append("   - What does this code actually do?\n");
        prompt.append("   - What problem domain does it address?\n");
        prompt.append("   - What are the key abstractions?\n\n");

        prompt.append("4. NEXT SEARCH DIRECTIONS:\n");
        prompt.append("   - Specific class/method names to explore\n");
        prompt.append("   - Related patterns to search for\n");
        prompt.append("   - Test files or documentation to find\n\n");

        prompt.append("FORMAT YOUR RESPONSE EXACTLY AS:\n");
        prompt.append("INSIGHTS: [2-3 sentences about what we've learned]\n");
        prompt.append("RELATIONSHIPS: [key relationships found, semicolon-separated]\n");
        prompt.append("MISSING_CONTEXT: [what we still need to find, semicolon-separated]\n");
        prompt.append("NEXT_SEARCHES: [specific things to search for, comma-separated]\n");
        prompt.append("CONFIDENCE: [LOW/MEDIUM/HIGH - how well we understand the context]\n");

        return prompt.toString();
    }

    /**
     * Adds iteration-specific focus to guide the analysis.
     */
    private void appendIterationFocus(StringBuilder prompt, int iteration) {
        prompt.append("ITERATION FOCUS: ");
        switch (iteration) {
            case 0:
                prompt.append("Initial exploration - identify main components and entry points\n");
                break;
            case 1:
                prompt.append("Find related components, dependencies, and integrations\n");
                break;
            case 2:
                prompt.append("Locate tests, usage examples, and error handling\n");
                break;
            case 3:
                prompt.append("Find configuration, documentation, and edge cases\n");
                break;
            default:
                prompt.append("Fill specific gaps and complete the context picture\n");
                break;
        }
        prompt.append("\n");
    }

    /**
     * Appends detailed findings from search results.
     */
    private void appendDetailedFindings(StringBuilder prompt, SearchResults results) {
        // Git results - temporal context
        if (results.getGitResults().size() > 0) {
            prompt.append("\n=== RECENT CHANGES (Git History) ===\n");
            appendGitFindings(prompt, results.getGitResults());
        }

        // Unstaged changes - current work context
        if (results.getUnstagedResults().size() > 0) {
            prompt.append("\n=== CURRENT WORK (Unstaged Changes) ===\n");
            appendUnstagedFindings(prompt, results.getUnstagedResults());
        }

        // Project results - codebase structure
        if (results.getProjectResults().size() > 0) {
            prompt.append("\n=== CODEBASE STRUCTURE (Project Files) ===\n");
            appendProjectFindings(prompt, results.getProjectResults());
        }
    }

    /**
     * Appends git findings with focus on understanding changes.
     */
    private void appendGitFindings(StringBuilder prompt, JsonArray gitResults) {
        for (int i = 0; i < gitResults.size(); i++) {
            JsonObject result = gitResults.get(i).getAsJsonObject();
            String keyword = result.get("keyword").getAsString();
            JsonArray commits = result.getAsJsonArray("commits");

            prompt.append("\nKeyword '").append(keyword).append("' - Recent commits:\n");

            for (int j = 0; j < Math.min(5, commits.size()); j++) {
                JsonObject commit = commits.get(j).getAsJsonObject();
                String message = commit.get("message").getAsString();
                String hash = commit.get("hash").getAsString();

                prompt.append("- [").append(hash, 0, Math.min(8, hash.length())).append("] ");
                prompt.append(message).append("\n");

                // Include changed files if available
                if (commit.has("files")) {
                    JsonArray files = commit.getAsJsonArray("files");
                    prompt.append("  Files: ");
                    for (int k = 0; k < Math.min(3, files.size()); k++) {
                        if (k > 0) prompt.append(", ");
                        prompt.append(files.get(k).getAsString());
                    }
                    prompt.append("\n");
                }
            }
        }
    }

    /**
     * Appends unstaged findings with focus on current development.
     */
    private void appendUnstagedFindings(StringBuilder prompt, JsonArray unstagedResults) {
        Map<String, List<String>> fileToFunctions = new HashMap<>();

        // Group by file for better context
        for (int i = 0; i < unstagedResults.size(); i++) {
            JsonObject result = unstagedResults.get(i).getAsJsonObject();
            String file = result.get("file").getAsString();

            if (result.has("analysis")) {
                JsonObject analysis = result.getAsJsonObject("analysis");
                if (analysis.has("changedFunctions")) {
                    JsonArray functions = analysis.getAsJsonArray("changedFunctions");
                    List<String> funcNames = new ArrayList<>();

                    for (int j = 0; j < functions.size(); j++) {
                        JsonObject func = functions.get(j).getAsJsonObject();
                        if (func != null && func.has("name")) {
                            funcNames.add(func.get("name").getAsString());
                        }
                    }

                    fileToFunctions.put(file, funcNames);
                }
            }
        }

        // Output grouped findings
        for (Map.Entry<String, List<String>> entry : fileToFunctions.entrySet()) {
            prompt.append("\nFile: ").append(entry.getKey()).append("\n");
            prompt.append("Modified functions: ").append(String.join(", ", entry.getValue())).append("\n");
        }
    }

    /**
     * Appends project findings with focus on code structure.
     */
    private void appendProjectFindings(StringBuilder prompt, JsonArray projectResults) {
        // Group by type for better organization
        List<JsonObject> functionResults = new ArrayList<>();
        List<JsonObject> textResults = new ArrayList<>();

        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            String type = result.get("type").getAsString();

            if ("function".equals(type)) {
                functionResults.add(result);
            } else {
                textResults.add(result);
            }
        }

        // Output function findings
        if (!functionResults.isEmpty()) {
            prompt.append("\nFUNCTION/METHOD MATCHES:\n");
            for (JsonObject result : functionResults) {
                String keyword = result.get("keyword").getAsString();
                JsonArray matches = result.getAsJsonArray("matches");

                prompt.append("\nKeyword '").append(keyword).append("':\n");
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    String funcName = match.get("name").getAsString();
                    String file = match.get("file").getAsString();

                    prompt.append("- ").append(funcName).append(" in ").append(file);

                    // Include implementation preview if available
                    if (match.has("implementation")) {
                        String impl = match.get("implementation").getAsString();
                        String preview = impl.substring(0, Math.min(100, impl.length()));
                        if (preview.contains("\n")) {
                            preview = preview.substring(0, preview.indexOf("\n"));
                        }
                        prompt.append("\n  Preview: ").append(preview).append("...");
                    }
                    prompt.append("\n");
                }
            }
        }

        // Output text findings
        if (!textResults.isEmpty()) {
            prompt.append("\nTEXT MATCHES:\n");
            for (JsonObject result : textResults) {
                String keyword = result.get("keyword").getAsString();
                JsonArray matches = result.getAsJsonArray("matches");

                Set<String> files = new HashSet<>();
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    files.add(match.get("file").getAsString());
                }

                prompt.append("\nKeyword '").append(keyword).append("' found in: ");
                prompt.append(String.join(", ", files)).append("\n");
            }
        }
    }

    /**
     * Parses the context-focused analysis response.
     */
    private AnalysisResult parseContextAnalysisResponse(String responseBody, int iteration) {
        LOG.info("Parsing context analysis response");

        String insights = "";
        String relationships = "";
        String missingContext = "";
        List<String> nextSearches = new ArrayList<>();
        String confidence = "LOW";

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

                    if (line.startsWith("INSIGHTS:")) {
                        insights = line.substring("INSIGHTS:".length()).trim();
                    } else if (line.startsWith("RELATIONSHIPS:")) {
                        relationships = line.substring("RELATIONSHIPS:".length()).trim();
                    } else if (line.startsWith("MISSING_CONTEXT:")) {
                        missingContext = line.substring("MISSING_CONTEXT:".length()).trim();
                    } else if (line.startsWith("NEXT_SEARCHES:")) {
                        String searches = line.substring("NEXT_SEARCHES:".length()).trim();
                        if (!searches.equalsIgnoreCase("NONE") && !searches.isEmpty()) {
                            String[] searchArray = searches.split(",");
                            for (String search : searchArray) {
                                search = search.trim();
                                if (!search.isEmpty() && search.length() > 2) {
                                    nextSearches.add(search);
                                }
                            }
                        }
                    } else if (line.startsWith("CONFIDENCE:")) {
                        confidence = line.substring("CONFIDENCE:".length()).trim().toUpperCase();
                    }
                }

                LOG.info("Parsed analysis - Confidence: " + confidence + ", Next searches: " + nextSearches.size());
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }

        // Build comprehensive summary
        String summary = buildComprehensiveSummary(insights, relationships, missingContext);

        // Determine if we should continue based on confidence and iteration
        boolean searchComplete = shouldCompleteSearch(confidence, iteration, missingContext);

        // Create enhanced analysis result
        AnalysisResult result = new AnalysisResult(summary, nextSearches, searchComplete, responseBody);
        result.setInsights(insights);
        result.setRelationships(parseRelationships(relationships));
        result.setMissingContext(parseMissingContext(missingContext));
        result.setConfidenceLevel(confidence);

        return result;
    }

    /**
     * Builds a comprehensive summary from analysis components.
     */
    private String buildComprehensiveSummary(String insights, String relationships, String missingContext) {
        StringBuilder summary = new StringBuilder();

        if (!insights.isEmpty()) {
            summary.append(insights).append(" ");
        }

        if (!relationships.isEmpty()) {
            summary.append("Key relationships: ").append(relationships).append(" ");
        }

        if (!missingContext.isEmpty()) {
            summary.append("Still need: ").append(missingContext);
        }

        return summary.toString().trim();
    }

    /**
     * Determines if search should be completed based on confidence and iteration.
     */
    private boolean shouldCompleteSearch(String confidence, int iteration, String missingContext) {
        // High confidence = likely complete
        if ("HIGH".equals(confidence)) {
            return true;
        }

        // After 5 iterations, complete unless confidence is very low
        if (iteration >= 4) {
            return !"LOW".equals(confidence);
        }

        // If no missing context identified, we're probably done
        if (missingContext == null || missingContext.trim().isEmpty() ||
                missingContext.equalsIgnoreCase("none")) {
            return true;
        }

        // Otherwise, continue searching
        return false;
    }

    /**
     * Parses relationships into a structured format.
     */
    private List<String> parseRelationships(String relationships) {
        if (relationships == null || relationships.trim().isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(relationships.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Parses missing context into a structured format.
     */
    private List<String> parseMissingContext(String missingContext) {
        if (missingContext == null || missingContext.trim().isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(missingContext.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Creates a default context analysis when LLM is unavailable.
     */
    private AnalysisResult createDefaultContextAnalysis(SearchResults results, int iteration) {
        LOG.info("Creating default context analysis");

        // Build insights based on what we found
        String insights = String.format("Found %d total results across git history, unstaged changes, and project files.",
                results.getTotalResults());

        // Extract some basic relationships
        List<String> relationships = extractBasicRelationships(results);

        // Determine what might be missing based on iteration
        List<String> missingContext = suggestMissingContext(iteration, results);

        // Generate next searches based on findings
        List<String> nextSearches = generateDefaultNextSearches(results, iteration);

        // Conservative confidence assessment
        String confidence = results.getTotalResults() > 10 ? "MEDIUM" : "LOW";
        boolean searchComplete = iteration >= 4 || results.getTotalResults() > 20;

        String summary = insights + " " +
                (relationships.isEmpty() ? "" : "Found relationships: " + String.join(", ", relationships)) + " " +
                (missingContext.isEmpty() ? "" : "Missing: " + String.join(", ", missingContext));

        AnalysisResult result = new AnalysisResult(summary.trim(), nextSearches, searchComplete, "");
        result.setInsights(insights);
        result.setRelationships(relationships);
        result.setMissingContext(missingContext);
        result.setConfidenceLevel(confidence);

        return result;
    }

    /**
     * Extracts basic relationships from search results.
     */
    private List<String> extractBasicRelationships(SearchResults results) {
        List<String> relationships = new ArrayList<>();

        // Look for file relationships in project results
        Set<String> files = new HashSet<>();
        JsonArray projectResults = results.getProjectResults();
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            JsonArray matches = result.getAsJsonArray("matches");
            for (int j = 0; j < matches.size(); j++) {
                JsonObject match = matches.get(j).getAsJsonObject();
                if (match.has("file")) {
                    files.add(match.get("file").getAsString());
                }
            }
        }

        if (files.size() > 1) {
            relationships.add(files.size() + " related files found");
        }

        return relationships;
    }

    /**
     * Suggests what context might be missing based on iteration.
     */
    private List<String> suggestMissingContext(int iteration, SearchResults results) {
        List<String> missing = new ArrayList<>();

        switch (iteration) {
            case 0:
                missing.add("Dependencies and related components");
                missing.add("Test files");
                break;
            case 1:
                missing.add("Configuration files");
                missing.add("Usage examples");
                break;
            case 2:
                missing.add("Documentation");
                missing.add("Error handling");
                break;
            default:
                if (results.getTotalResults() < 5) {
                    missing.add("More specific implementations");
                }
                break;
        }

        return missing;
    }

    /**
     * Generates default next searches based on findings.
     */
    private List<String> generateDefaultNextSearches(SearchResults results, int iteration) {
        List<String> searches = new ArrayList<>();

        // Extract class/method names from results
        Set<String> foundNames = new HashSet<>();

        // From project results
        JsonArray projectResults = results.getProjectResults();
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            if ("function".equals(result.get("type").getAsString())) {
                JsonArray matches = result.getAsJsonArray("matches");
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    if (match.has("name")) {
                        String name = match.get("name").getAsString();
                        // Look for related test names
                        searches.add(name + "Test");
                        searches.add(name + "Spec");
                    }
                }
            }
        }

        // Add iteration-specific searches
        switch (iteration) {
            case 0:
                searches.add("Service");
                searches.add("Controller");
                break;
            case 1:
                searches.add("Repository");
                searches.add("Config");
                break;
            case 2:
                searches.add("Exception");
                searches.add("Error");
                break;
        }

        return searches.stream()
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }
}