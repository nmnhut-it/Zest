package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Enhances Agent Mode context by using Research Agent to find relevant code.
 */
public class AgentModeContextEnhancer {
    private static final Logger LOG = Logger.getInstance(AgentModeContextEnhancer.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final FileService fileService;
    private final GitService gitService;
    private final KeywordGeneratorService keywordGenerator;
    
    // Cache for research results (key -> result, timestamp)
    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    
    private static class CachedResult {
        final String result;
        final long timestamp;
        
        CachedResult(String result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
        }
    }
    
    public AgentModeContextEnhancer(@NotNull Project project) {
        this.project = project;
        this.fileService = new FileService(project);
        this.gitService = new GitService(project);
        this.keywordGenerator = new KeywordGeneratorService(project);
    }
    
    /**
     * Enhances the prompt with relevant context from the project.
     * This method is called during prompt building in Agent Mode.
     */
    public CompletableFuture<String> enhancePromptWithContext(String userQuery, String currentFileContext) {
        return keywordGenerator.generateKeywords(userQuery)
            .thenCompose(keywords -> {
                LOG.info("Generated keywords for context enhancement: " + keywords);
                
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        // 1. First, search recent git history for relevant changes
                        JsonArray gitResults = searchGitHistory(keywords);
                        
                        // 2. Then search the project for relevant code
                        JsonArray projectResults = searchProject(keywords);
                        
                        // 3. Combine and format results
                        JsonObject enhancedContext = new JsonObject();
                        enhancedContext.addProperty("userQuery", userQuery);
                        enhancedContext.addProperty("currentFile", currentFileContext);
                        enhancedContext.add("keywords", GSON.toJsonTree(keywords));
                        enhancedContext.add("recentChanges", gitResults);
                        enhancedContext.add("relatedCode", projectResults);
                        
                        return GSON.toJson(enhancedContext);
                        
                    } catch (Exception e) {
                        LOG.error("Error enhancing context", e);
                        return "{}"; // Return empty context on error
                    }
                });
            });
    }
    
    /**
     * Searches git history for relevant recent changes.
     */
    private JsonArray searchGitHistory(List<String> keywords) {
        JsonArray results = new JsonArray();
        
        try {
            for (String keyword : keywords) {
                String cacheKey = "git_" + keyword;
                CachedResult cached = resultCache.get(cacheKey);
                
                if (cached != null && !cached.isExpired()) {
                    results.add(GSON.fromJson(cached.result, JsonObject.class));
                } else {
                    // Search git history
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("text", keyword);
                    
                    String gitResult = gitService.findCommitByMessage(searchData);
                    JsonObject gitResponse = GSON.fromJson(gitResult, JsonObject.class);
                    
                    if (gitResponse.get("success").getAsBoolean() && 
                        gitResponse.has("commits")) {
                        JsonObject result = new JsonObject();
                        result.addProperty("keyword", keyword);
                        result.add("commits", gitResponse.get("commits"));
                        results.add(result);
                        
                        // Cache the result
                        resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                    }
                }
                
                // Limit to first 2 git results
                if (results.size() >= 2) break;
            }
        } catch (Exception e) {
            LOG.warn("Error searching git history", e);
        }
        
        return results;
    }
    
    /**
     * Searches the project for relevant code snippets and functions.
     */
    private JsonArray searchProject(List<String> keywords) {
        JsonArray results = new JsonArray();
        
        try {
            for (String keyword : keywords) {
                // Try to find functions first
                String cacheKey = "func_" + keyword;
                CachedResult cached = resultCache.get(cacheKey);
                
                if (cached != null && !cached.isExpired()) {
                    results.add(GSON.fromJson(cached.result, JsonObject.class));
                } else {
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("functionName", keyword);
                    searchData.addProperty("path", "/");
                    
                    String funcResult = fileService.findFunctions(searchData);
                    JsonObject funcResponse = GSON.fromJson(funcResult, JsonObject.class);
                    
                    if (funcResponse.get("success").getAsBoolean() && 
                        funcResponse.has("results")) {
                        JsonArray funcResults = funcResponse.getAsJsonArray("results");
                        if (funcResults.size() > 0) {
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "function");
                            result.addProperty("keyword", keyword);
                            result.add("matches", funcResults);
                            results.add(result);
                            
                            // Cache the result
                            resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                        }
                    }
                }
                
                // If no functions found, try text search
                if (results.size() < 3) {
                    cacheKey = "text_" + keyword;
                    cached = resultCache.get(cacheKey);
                    
                    if (cached != null && !cached.isExpired()) {
                        results.add(GSON.fromJson(cached.result, JsonObject.class));
                    } else {
                        JsonObject textSearchData = new JsonObject();
                        textSearchData.addProperty("searchText", keyword);
                        textSearchData.addProperty("path", "/");
                        textSearchData.addProperty("maxResults", 2);
                        
                        String textResult = fileService.searchInFiles(textSearchData);
                        JsonObject textResponse = GSON.fromJson(textResult, JsonObject.class);
                        
                        if (textResponse.get("success").getAsBoolean() && 
                            textResponse.has("results")) {
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "text");
                            result.addProperty("keyword", keyword);
                            result.add("matches", textResponse.get("results"));
                            results.add(result);
                            
                            // Cache the result
                            resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                        }
                    }
                }
                
                // Limit total results to 5
                if (results.size() >= 5) break;
            }
        } catch (Exception e) {
            LOG.warn("Error searching project", e);
        }
        
        return results;
    }
    
    /**
     * Extracts keywords from the query.
     * This is now replaced by LLM-based generation but kept as fallback.
     */
    private String[] extractKeywords(String query) {
        // Simple keyword extraction - now used as fallback
        String[] words = query.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s_]", " ")
            .split("\\s+");
        
        // Filter out common words and limit to 10
        return java.util.Arrays.stream(words)
            .filter(w -> w.length() > 3)
            .filter(w -> !isCommonWord(w))
            .distinct()
            .limit(10)
            .toArray(String[]::new);
    }
    
    private boolean isCommonWord(String word) {
        return java.util.Set.of("this", "that", "with", "from", "have", "been", 
            "will", "would", "could", "should", "make", "need", "want", "please",
            "help", "write", "create", "update", "modify", "change", "code").contains(word);
    }
    
    /**
     * Clears expired cache entries.
     */
    public void cleanCache() {
        resultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * Disposes resources.
     */
    public void dispose() {
        resultCache.clear();
        if (fileService != null) fileService.dispose();
        if (gitService != null) gitService.dispose();
    }
}
