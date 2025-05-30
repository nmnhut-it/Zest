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
        
        // Add enhanced context analysis guidelines
        prompt.append("\n\nCONTEXT ANALYSIS GUIDELINES:\n");
        prompt.append("- When FULL FILE CONTENT is provided, analyze the entire file structure and relationships\n");
        prompt.append("- Pay attention to imports, class hierarchies, and method interactions\n");
        prompt.append("- For containing functions, understand how the searched term is used within that context\n");
        prompt.append("- Identify patterns, dependencies, and architectural decisions\n");
        prompt.append("- Note any configuration, constants, or initialization code\n");
        prompt.append("- Look for error handling, edge cases, and business logic\n\n");
        
        prompt.append("FOCUS ON BUILDING A COMPLETE MENTAL MODEL:\n");
        prompt.append("- How do these components work together?\n");
        prompt.append("- What is the data flow?\n");
        prompt.append("- What are the key abstractions and their responsibilities?\n");
        prompt.append("- What external dependencies or frameworks are used?\n");
        prompt.append("- What testing strategies are employed?\n\n");

        // Instructions for analysis
        prompt.append("\n\nEXPLORATORY ANALYSIS - Ask yourself these questions:\n\n");
        
        prompt.append("1. FRAMEWORK & ARCHITECTURE DISCOVERY:\n");
        prompt.append("   - What frameworks/libraries are being used? (Spring, React, etc.)\n");
        prompt.append("   - What architectural patterns are evident? (MVC, microservices, etc.)\n");
        prompt.append("   - What design patterns are implemented? (Factory, Observer, etc.)\n");
        prompt.append("   - Are there any custom frameworks or abstractions?\n\n");
        
        prompt.append("2. UTILITY & HELPER DISCOVERY:\n");
        prompt.append("   - What utility classes/functions exist? (StringUtils, DateUtils, etc.)\n");
        prompt.append("   - What common helpers are used? (validators, formatters, converters)\n");
        prompt.append("   - Are there shared constants or configuration classes?\n");
        prompt.append("   - What base classes or interfaces are extended/implemented?\n\n");
        
        prompt.append("3. DEPENDENCY & INTEGRATION MAPPING:\n");
        prompt.append("   - What external services/APIs are integrated?\n");
        prompt.append("   - What database/persistence layers are used?\n");
        prompt.append("   - What messaging/event systems are in place?\n");
        prompt.append("   - How do different modules communicate?\n\n");
        
        prompt.append("4. TESTING & QUALITY INFRASTRUCTURE:\n");
        prompt.append("   - What testing frameworks are used? (JUnit, Jest, etc.)\n");
        prompt.append("   - Are there test utilities or fixtures?\n");
        prompt.append("   - What mocking/stubbing approaches are used?\n");
        prompt.append("   - Are there integration or E2E tests?\n\n");
        
        prompt.append("5. CRITICAL QUESTIONS TO ANSWER:\n");
        prompt.append("   - What don't we know yet that would help understand this code?\n");
        prompt.append("   - What assumptions are we making that need verification?\n");
        prompt.append("   - What related functionality likely exists but hasn't been found?\n");
        prompt.append("   - What would a developer need to know to work on this?\n\n");
        
        prompt.append("6. NEXT EXPLORATION TARGETS:\n");
        prompt.append("   - Specific utility/helper classes to find\n");
        prompt.append("   - Framework-specific files (configs, initializers)\n");
        prompt.append("   - Base classes or interfaces to explore\n");
        prompt.append("   - Test files that show usage examples\n");

        prompt.append("\n\nFORMAT YOUR RESPONSE EXACTLY AS:\n");
        prompt.append("INSIGHTS: [2-3 sentences about what we've learned]\n");
        prompt.append("FRAMEWORKS: [identified frameworks/libraries, comma-separated]\n");
        prompt.append("UTILITIES: [utility classes/helpers found, comma-separated]\n");
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
                prompt.append("Initial exploration - identify main components, frameworks, and utility classes\n");
                prompt.append("Questions to answer:\n");
                prompt.append("- What frameworks/libraries are in use?\n");
                prompt.append("- What are the main entry points?\n");
                prompt.append("- What utility/helper classes exist?\n");
                break;
            case 1:
                prompt.append("Deep dive into dependencies and integrations\n");
                prompt.append("Questions to answer:\n");
                prompt.append("- How do components communicate?\n");
                prompt.append("- What external services are integrated?\n");
                prompt.append("- What base classes/interfaces are used?\n");
                break;
            case 2:
                prompt.append("Explore testing infrastructure and usage patterns\n");
                prompt.append("Questions to answer:\n");
                prompt.append("- What testing frameworks and utilities exist?\n");
                prompt.append("- How is the code tested?\n");
                prompt.append("- What are the common usage patterns?\n");
                break;
            case 3:
                prompt.append("Find configuration, initialization, and edge cases\n");
                prompt.append("Questions to answer:\n");
                prompt.append("- How is the system configured?\n");
                prompt.append("- What initialization code exists?\n");
                prompt.append("- How are errors and edge cases handled?\n");
                break;
            default:
                prompt.append("Complete the context picture - fill critical gaps\n");
                prompt.append("Questions to answer:\n");
                prompt.append("- What crucial pieces are still missing?\n");
                prompt.append("- What assumptions need verification?\n");
                prompt.append("- What would surprise a new developer?\n");
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
     * Enhanced method to append project findings with full content awareness.
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
                    JsonObject fileMatch = matches.get(j).getAsJsonObject();
                    String file = fileMatch.get("file").getAsString();
                    
                    // Check if we have full content
                    if (fileMatch.has("hasFullContent") && fileMatch.get("hasFullContent").getAsBoolean()) {
                        prompt.append("\n=== FULL FILE CONTENT: ").append(file).append(" ===\n");
                        prompt.append("(").append(fileMatch.get("totalLines").getAsInt()).append(" lines)\n");
                        prompt.append(fileMatch.get("fullContent").getAsString());
                        prompt.append("\n=== END FILE ===\n");
                    } else {
                        // List functions found in this file
                        if (fileMatch.has("functions")) {
                            JsonArray functions = fileMatch.getAsJsonArray("functions");
                            prompt.append("\nIn file: ").append(file).append("\n");
                            
                            for (int k = 0; k < functions.size(); k++) {
                                JsonObject func = functions.get(k).getAsJsonObject();
                                String funcName = func.get("name").getAsString();
                                int line = func.get("line").getAsInt();
                                
                                prompt.append("- ").append(funcName).append(" (line ").append(line).append(")");
                                
                                // Include implementation if available
                                if (func.has("implementation")) {
                                    String impl = func.get("implementation").getAsString();
                                    prompt.append("\n  Implementation:\n");
                                    prompt.append(indentCode(impl, "  "));
                                }
                                prompt.append("\n");
                            }
                        }
                    }
                }
            }
        }
        
        // Output text findings with enhanced context
        if (!textResults.isEmpty()) {
            prompt.append("\nTEXT MATCHES:\n");
            for (JsonObject result : textResults) {
                String keyword = result.get("keyword").getAsString();
                JsonArray matches = result.getAsJsonArray("matches");
                
                prompt.append("\nKeyword '").append(keyword).append("':\n");
                
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject fileMatch = matches.get(j).getAsJsonObject();
                    String file = fileMatch.get("file").getAsString();
                    
                    // Check if we have full content
                    if (fileMatch.has("hasFullContent") && fileMatch.get("hasFullContent").getAsBoolean()) {
                        prompt.append("\n=== FULL FILE CONTENT: ").append(file).append(" ===\n");
                        prompt.append("(").append(fileMatch.get("totalLines").getAsInt()).append(" lines)\n");
                        prompt.append(fileMatch.get("fullContent").getAsString());
                        prompt.append("\n=== END FILE ===\n");
                    } else {
                        // Show matches with containing functions
                        prompt.append("\nIn file: ").append(file);
                        if (fileMatch.has("totalLines")) {
                            prompt.append(" (").append(fileMatch.get("totalLines").getAsInt()).append(" lines total)");
                        }
                        prompt.append("\n");
                        
                        if (fileMatch.has("matches")) {
                            JsonArray textMatches = fileMatch.getAsJsonArray("matches");
                            
                            for (int k = 0; k < textMatches.size(); k++) {
                                JsonObject match = textMatches.get(k).getAsJsonObject();
                                int line = match.get("line").getAsInt();
                                String text = match.get("text").getAsString();
                                
                                prompt.append("- Line ").append(line).append(": ").append(text).append("\n");
                                
                                // Include containing function if available
                                if (match.has("containingFunction")) {
                                    JsonObject containingFunc = match.getAsJsonObject("containingFunction");
                                    String funcName = containingFunc.get("name").getAsString();
                                    String funcType = containingFunc.get("type").getAsString();
                                    
                                    prompt.append("  Within ").append(funcType).append(": ").append(funcName).append("\n");
                                    
                                    if (containingFunc.has("implementation")) {
                                        String impl = containingFunc.get("implementation").getAsString();
                                        prompt.append("  Full function:\n");
                                        prompt.append(indentCode(impl, "    "));
                                        prompt.append("\n");
                                    }
                                } else if (match.has("context")) {
                                    // Show context if no containing function
                                    JsonObject context = match.getAsJsonObject("context");
                                    if (context.has("before")) {
                                        JsonArray before = context.getAsJsonArray("before");
                                        if (before.size() > 0) {
                                            prompt.append("  Context:\n");
                                            for (int l = 0; l < before.size(); l++) {
                                                prompt.append("    ").append(before.get(l).getAsString()).append("\n");
                                            }
                                            prompt.append("    > ").append(text).append("\n");
                                            if (context.has("after")) {
                                                JsonArray after = context.getAsJsonArray("after");
                                                for (int l = 0; l < Math.min(2, after.size()); l++) {
                                                    prompt.append("    ").append(after.get(l).getAsString()).append("\n");
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Helper method to indent code blocks.
     */
    private String indentCode(String code, String indent) {
        return Arrays.stream(code.split("\n"))
            .map(line -> indent + line)
            .collect(Collectors.joining("\n"));
    }

    /**
     * Parses the context-focused analysis response.
     */
    private AnalysisResult parseContextAnalysisResponse(String responseBody, int iteration) {
        LOG.info("Parsing context analysis response");

        String insights = "";
        String frameworks = "";
        String utilities = "";
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
                    } else if (line.startsWith("FRAMEWORKS:")) {
                        frameworks = line.substring("FRAMEWORKS:".length()).trim();
                    } else if (line.startsWith("UTILITIES:")) {
                        utilities = line.substring("UTILITIES:".length()).trim();
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
                
                // Process frameworks and utilities to generate additional searches
                if (!frameworks.isEmpty() && !frameworks.equalsIgnoreCase("none")) {
                    processFrameworksForSearches(frameworks, nextSearches);
                }
                if (!utilities.isEmpty() && !utilities.equalsIgnoreCase("none")) {
                    processUtilitiesForSearches(utilities, nextSearches);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }

        // Build comprehensive summary including frameworks and utilities
        String summary = buildEnhancedSummary(insights, frameworks, utilities, relationships, missingContext);

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
     * Builds an enhanced summary including frameworks and utilities.
     */
    private String buildEnhancedSummary(String insights, String frameworks, String utilities, 
                                       String relationships, String missingContext) {
        StringBuilder summary = new StringBuilder();

        if (!insights.isEmpty()) {
            summary.append(insights).append(" ");
        }

        if (!frameworks.isEmpty() && !frameworks.equalsIgnoreCase("none")) {
            summary.append("Frameworks: ").append(frameworks).append(". ");
        }

        if (!utilities.isEmpty() && !utilities.equalsIgnoreCase("none")) {
            summary.append("Utilities: ").append(utilities).append(". ");
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
     * Processes identified frameworks to generate additional search keywords.
     */
    private void processFrameworksForSearches(String frameworks, List<String> nextSearches) {
        String[] frameworkList = frameworks.split(",");
        for (String framework : frameworkList) {
            framework = framework.trim().toLowerCase();
            
            // Add framework-specific search terms
            if (framework.contains("spring")) {
                nextSearches.add("@Service");
                nextSearches.add("@Repository");
                nextSearches.add("@Configuration");
                nextSearches.add("ApplicationContext");
            } else if (framework.contains("react")) {
                nextSearches.add("useContext");
                nextSearches.add("Provider");
                nextSearches.add("hooks");
            } else if (framework.contains("junit")) {
                nextSearches.add("@BeforeEach");
                nextSearches.add("@Mock");
                nextSearches.add("TestUtils");
            } else if (framework.contains("angular")) {
                nextSearches.add("@Injectable");
                nextSearches.add("@Component");
                nextSearches.add("module");
            }
        }
    }
    
    /**
     * Processes identified utilities to generate additional search keywords.
     */
    private void processUtilitiesForSearches(String utilities, List<String> nextSearches) {
        String[] utilityList = utilities.split(",");
        for (String utility : utilityList) {
            utility = utility.trim();
            
            // Look for related utilities
            if (utility.endsWith("Utils")) {
                String base = utility.substring(0, utility.length() - 5);
                nextSearches.add(base + "Helper");
                nextSearches.add(base + "Service");
            } else if (utility.endsWith("Helper")) {
                String base = utility.substring(0, utility.length() - 6);
                nextSearches.add(base + "Utils");
                nextSearches.add(base + "Manager");
            }
            
            // Add test versions
            nextSearches.add(utility + "Test");
            nextSearches.add(utility + "Spec");
        }
    }

    /**
     * Determines if search should be completed based on confidence and iteration.
     */
    private boolean shouldCompleteSearch(String confidence, int iteration, String missingContext) {
        // High confidence = likely complete
        if ("HIGH".equals(confidence)) {
            LOG.info("High confidence achieved - search should complete");
            return true;
        }
        
        // Medium confidence after 3 iterations is usually enough
        if ("MEDIUM".equals(confidence) && iteration >= 2) {
            LOG.info("Medium confidence after " + (iteration + 1) + " iterations - search should complete");
            return true;
        }

        // After 5 iterations, complete unless confidence is very low
        if (iteration >= 4) {
            boolean shouldComplete = !"LOW".equals(confidence);
            LOG.info("Max iterations reached, confidence: " + confidence + ", completing: " + shouldComplete);
            return shouldComplete;
        }

        // If no missing context identified, we're probably done
        if (missingContext == null || missingContext.trim().isEmpty() ||
                missingContext.equalsIgnoreCase("none")) {
            LOG.info("No missing context identified - search should complete");
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
        Set<String> foundFiles = new HashSet<>();

        // From project results
        JsonArray projectResults = results.getProjectResults();
        for (int i = 0; i < projectResults.size(); i++) {
            JsonObject result = projectResults.get(i).getAsJsonObject();
            JsonArray matches = result.getAsJsonArray("matches");
            
            for (int j = 0; j < matches.size(); j++) {
                JsonObject match = matches.get(j).getAsJsonObject();
                
                // Collect file names to infer patterns
                if (match.has("file")) {
                    foundFiles.add(match.get("file").getAsString());
                }
                
                // For function results
                if ("function".equals(result.get("type").getAsString())) {
                    if (match.has("functions")) {
                        JsonArray functions = match.getAsJsonArray("functions");
                        for (int k = 0; k < functions.size(); k++) {
                            JsonObject func = functions.get(k).getAsJsonObject();
                            if (func.has("name")) {
                                String name = func.get("name").getAsString();
                                foundNames.add(name);
                                
                                // Look for related patterns
                                searches.add(name + "Test");
                                searches.add(name + "Spec");
                                searches.add(name + "Utils");
                                searches.add(name + "Helper");
                                searches.add(name + "Config");
                            }
                        }
                    }
                }
            }
        }

        // Infer frameworks and utilities from file names
        for (String file : foundFiles) {
            // Look for common utility patterns
            if (file.contains("Utils") || file.contains("Helper")) {
                String fileName = file.substring(file.lastIndexOf("/") + 1);
                searches.add(fileName.replace(".java", "").replace(".js", ""));
            }
            
            // Look for framework indicators
            if (file.contains("Controller")) searches.add("Service");
            if (file.contains("Service")) searches.add("Repository");
            if (file.contains("Component")) searches.add("Provider");
        }

        // Add iteration-specific exploratory searches
        switch (iteration) {
            case 0:
                // Look for frameworks and utilities
                searches.add("Utils");
                searches.add("Helper");
                searches.add("Config");
                searches.add("Constants");
                searches.add("Base");
                searches.add("Abstract");
                break;
            case 1:
                // Look for integrations and dependencies
                searches.add("Client");
                searches.add("Adapter");
                searches.add("Factory");
                searches.add("Manager");
                searches.add("Provider");
                searches.add("Interface");
                break;
            case 2:
                // Look for testing infrastructure
                searches.add("Mock");
                searches.add("Stub");
                searches.add("Fixture");
                searches.add("TestUtils");
                searches.add("TestHelper");
                searches.add("beforeEach");
                break;
            case 3:
                // Look for configuration and initialization
                searches.add("initialize");
                searches.add("setup");
                searches.add("configure");
                searches.add("bootstrap");
                searches.add("startup");
                break;
        }

        return searches.stream()
                .distinct()
                .limit(15) // Allow more searches for exploration
                .collect(Collectors.toList());
    }
}