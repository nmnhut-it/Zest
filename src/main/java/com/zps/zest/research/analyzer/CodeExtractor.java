package com.zps.zest.research.analyzer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.research.context.ResearchContext;
import com.zps.zest.research.context.ResearchIteration;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Extracts and consolidates relevant code pieces based on research results and user query.
 */
public class CodeExtractor {
    private static final Logger LOG = Logger.getInstance(CodeExtractor.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final ConfigurationManager configManager;
    private final HttpClient httpClient;
    
    public CodeExtractor(@NotNull Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
    
    /**
     * Extracts all relevant code pieces that help answer the user's query.
     */
    public CompletableFuture<JsonObject> extractRelevantCode(
            String userQuery,
            ResearchContext researchContext) {
        
        LOG.info("=== Code Extraction Started ===");
        LOG.info("Extracting relevant code for query: " + userQuery);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build extraction prompt
                String prompt = buildExtractionPrompt(userQuery, researchContext);
                
                LOG.info("Extraction prompt length: " + prompt.length() + " chars");
                
                // Call LLM API for intelligent extraction
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getLiteModel());
                requestBody.addProperty("temperature", 0.1); // Lower temperature for precise extraction
                requestBody.addProperty("max_tokens", 2000);
                
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);
                
                String apiUrl = configManager.getApiUrl();
                LOG.info("Calling LLM API for code extraction");
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + configManager.getAuthToken())
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonObject extractedCode = parseExtractionResponse(response.body());
                    LOG.info("Successfully extracted relevant code pieces");
                    return extractedCode;
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    return performDefaultExtraction(userQuery, researchContext);
                }
                
            } catch (Exception e) {
                LOG.error("Error during code extraction", e);
                return performDefaultExtraction(userQuery, researchContext);
            }
        });
    }
    
    /**
     * Builds the prompt for code extraction.
     */
    private String buildExtractionPrompt(String userQuery, ResearchContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are extracting relevant code pieces to answer a user's query.\n");
        prompt.append("Your goal is to identify and extract ONLY the code that directly helps answer the query.\n\n");
        
        prompt.append("USER QUERY: ").append(userQuery).append("\n\n");
        
        // Add discovered context
        prompt.append("DISCOVERED CONTEXT:\n");
        prompt.append("- Total keywords explored: ").append(context.getAllDiscoveredKeywords().size()).append("\n");
        prompt.append("- Total results found: ").append(context.getTotalResultsFound()).append("\n");
        prompt.append("- Keywords: ").append(String.join(", ", context.getAllDiscoveredKeywords())).append("\n\n");
        
        // Add all found code
        prompt.append("ALL DISCOVERED CODE:\n");
        appendAllDiscoveredCode(prompt, context);
        
        prompt.append("\n\nEXTRACTION TASK:\n");
        prompt.append("1. Identify which code pieces directly answer the user's query\n");
        prompt.append("2. Group related code pieces together\n");
        prompt.append("3. Include necessary context (imports, class definitions, etc.)\n");
        prompt.append("4. Prioritize by relevance to the query\n");
        prompt.append("5. Include usage examples if found\n\n");
        
        prompt.append("EXTRACTION CRITERIA:\n");
        prompt.append("- MUST be directly relevant to answering the query\n");
        prompt.append("- Include complete implementations (not just signatures)\n");
        prompt.append("- Include related utility functions if they're used\n");
        prompt.append("- Include configuration or initialization code if relevant\n");
        prompt.append("- Exclude unrelated code even if it was found\n\n");
        
        prompt.append("FORMAT YOUR RESPONSE AS JSON:\n");
        prompt.append("{\n");
        prompt.append("  \"summary\": \"Brief summary of what was found\",\n");
        prompt.append("  \"main_components\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"name\": \"Component/Function name\",\n");
        prompt.append("      \"file\": \"File path\",\n");
        prompt.append("      \"purpose\": \"What this does\",\n");
        prompt.append("      \"code\": \"Complete code implementation\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"utilities\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"name\": \"Utility name\",\n");
        prompt.append("      \"file\": \"File path\",\n");
        prompt.append("      \"code\": \"Utility code\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"usage_examples\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"description\": \"Example description\",\n");
        prompt.append("      \"code\": \"Example code\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"configuration\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"type\": \"Config type\",\n");
        prompt.append("      \"code\": \"Configuration code\"\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        
        return prompt.toString();
    }
    
    /**
     * Appends all discovered code from research iterations.
     */
    private void appendAllDiscoveredCode(StringBuilder prompt, ResearchContext context) {
        for (ResearchIteration iteration : context.getIterations()) {
            prompt.append("\n--- Iteration ").append(iteration.getIterationNumber() + 1).append(" ---\n");
            
            // Append project results (functions and text matches)
            JsonArray projectResults = iteration.getSearchResults().getProjectResults();
            for (int i = 0; i < projectResults.size(); i++) {
                JsonObject result = projectResults.get(i).getAsJsonObject();
                String type = result.get("type").getAsString();
                String keyword = result.get("keyword").getAsString();
                
                prompt.append("\nKeyword '").append(keyword).append("' (").append(type).append("):\n");
                
                JsonArray matches = result.getAsJsonArray("matches");
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    String file = match.get("file").getAsString();
                    
                    // Include full content if available
                    if (match.has("fullContent")) {
                        prompt.append("\n=== FULL FILE: ").append(file).append(" ===\n");
                        prompt.append(match.get("fullContent").getAsString());
                        prompt.append("\n=== END FILE ===\n");
                    } else if (match.has("functions")) {
                        // Include function implementations
                        JsonArray functions = match.getAsJsonArray("functions");
                        for (int k = 0; k < functions.size(); k++) {
                            JsonObject func = functions.get(k).getAsJsonObject();
                            if (func.has("implementation")) {
                                prompt.append("\nFunction: ").append(func.get("name").getAsString());
                                prompt.append(" in ").append(file).append("\n");
                                prompt.append(func.get("implementation").getAsString()).append("\n");
                            }
                        }
                    } else if (match.has("matches")) {
                        // Include text matches with containing functions
                        JsonArray textMatches = match.getAsJsonArray("matches");
                        for (int k = 0; k < textMatches.size(); k++) {
                            JsonObject textMatch = textMatches.get(k).getAsJsonObject();
                            if (textMatch.has("containingFunction")) {
                                JsonObject containingFunc = textMatch.getAsJsonObject("containingFunction");
                                if (containingFunc.has("implementation")) {
                                    prompt.append("\nContaining function: ");
                                    prompt.append(containingFunc.get("name").getAsString());
                                    prompt.append(" in ").append(file).append("\n");
                                    prompt.append(containingFunc.get("implementation").getAsString()).append("\n");
                                }
                            }
                        }
                    }
                }
            }
            
            // Include relevant git results if they show recent changes
            if (iteration.getSearchResults().getGitResults().size() > 0) {
                prompt.append("\nRecent Git changes:\n");
                JsonArray gitResults = iteration.getSearchResults().getGitResults();
                for (int i = 0; i < Math.min(3, gitResults.size()); i++) {
                    JsonObject result = gitResults.get(i).getAsJsonObject();
                    JsonArray commits = result.getAsJsonArray("commits");
                    for (int j = 0; j < Math.min(2, commits.size()); j++) {
                        JsonObject commit = commits.get(j).getAsJsonObject();
                        prompt.append("- ").append(commit.get("message").getAsString()).append("\n");
                    }
                }
            }
        }
    }
    
    /**
     * Parses the extraction response from LLM.
     */
    private JsonObject parseExtractionResponse(String responseBody) {
        try {
            JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = response.getAsJsonArray("choices");
            
            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                
                // Parse JSON response
                return GSON.fromJson(content, JsonObject.class);
            }
        } catch (Exception e) {
            LOG.warn("Error parsing extraction response", e);
        }
        
        return new JsonObject();
    }
    
    /**
     * Performs default extraction when LLM is unavailable.
     */
    private JsonObject performDefaultExtraction(String userQuery, ResearchContext context) {
        LOG.info("Performing default code extraction");
        
        JsonObject extracted = new JsonObject();
        extracted.addProperty("summary", "Extracted code related to: " + userQuery);
        
        JsonArray mainComponents = new JsonArray();
        JsonArray utilities = new JsonArray();
        JsonArray examples = new JsonArray();
        JsonArray configs = new JsonArray();
        
        // Extract from all iterations
        for (ResearchIteration iteration : context.getIterations()) {
            JsonArray projectResults = iteration.getSearchResults().getProjectResults();
            
            for (int i = 0; i < projectResults.size(); i++) {
                JsonObject result = projectResults.get(i).getAsJsonObject();
                JsonArray matches = result.getAsJsonArray("matches");
                
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    
                    // Extract functions
                    if (match.has("functions")) {
                        JsonArray functions = match.getAsJsonArray("functions");
                        for (int k = 0; k < functions.size(); k++) {
                            JsonObject func = functions.get(k).getAsJsonObject();
                            if (func.has("implementation")) {
                                JsonObject component = new JsonObject();
                                component.addProperty("name", func.get("name").getAsString());
                                component.addProperty("file", match.get("file").getAsString());
                                component.addProperty("purpose", "Function implementation");
                                component.addProperty("code", func.get("implementation").getAsString());
                                
                                // Categorize based on name
                                String name = func.get("name").getAsString().toLowerCase();
                                if (name.contains("util") || name.contains("helper")) {
                                    utilities.add(component);
                                } else if (name.contains("test") || name.contains("spec")) {
                                    examples.add(component);
                                } else if (name.contains("config") || name.contains("init")) {
                                    configs.add(component);
                                } else {
                                    mainComponents.add(component);
                                }
                            }
                        }
                    }
                    
                    // Extract full files if small
                    if (match.has("fullContent") && match.has("totalLines")) {
                        if (match.get("totalLines").getAsInt() < 100) {
                            JsonObject component = new JsonObject();
                            component.addProperty("name", match.get("file").getAsString());
                            component.addProperty("file", match.get("file").getAsString());
                            component.addProperty("purpose", "Complete file");
                            component.addProperty("code", match.get("fullContent").getAsString());
                            mainComponents.add(component);
                        }
                    }
                }
            }
        }
        
        extracted.add("main_components", mainComponents);
        extracted.add("utilities", utilities);
        extracted.add("usage_examples", examples);
        extracted.add("configuration", configs);
        
        return extracted;
    }
}