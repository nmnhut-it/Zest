package com.zps.zest.research.search.strategies;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.GitService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Search strategy for searching git history.
 */
public class GitSearchStrategy implements SearchStrategy {
    private static final Logger LOG = Logger.getInstance(GitSearchStrategy.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_GIT_RESULTS = 3;
    
    private final Project project;
    private final GitService gitService;
    
    public GitSearchStrategy(@NotNull Project project) {
        this.project = project;
        this.gitService = new GitService(project);
    }
    
    @Override
    public CompletableFuture<JsonArray> search(List<String> keywords) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Git search started with keywords: " + keywords);
            JsonArray results = new JsonArray();
            
            try {
                for (String keyword : keywords) {
                    LOG.info("Searching git commits for keyword: " + keyword);
                    
                    // Search git history
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("text", keyword);
                    
                    String gitResult = gitService.findCommitByMessage(searchData);
                    JsonObject gitResponse = GSON.fromJson(gitResult, JsonObject.class);
                    
                    if (gitResponse.get("success").getAsBoolean() && 
                        gitResponse.has("commits")) {
                        JsonArray commits = gitResponse.getAsJsonArray("commits");
                        
                        if (commits.size() > 0) {
                            LOG.info("Found " + commits.size() + " commits for keyword: " + keyword);
                            
                            JsonObject result = new JsonObject();
                            result.addProperty("keyword", keyword);
                            result.add("commits", commits);
                            results.add(result);
                        } else {
                            LOG.info("No commits found for keyword: " + keyword);
                        }
                    } else {
                        LOG.warn("Git search failed for keyword: " + keyword);
                    }
                    
                    // Limit results
                    if (results.size() >= MAX_GIT_RESULTS) {
                        LOG.info("Reached git result limit (" + MAX_GIT_RESULTS + ")");
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in git search", e);
            }
            
            LOG.info("Git search completed with " + results.size() + " results");
            return results;
        });
    }
    
    @Override
    public String getSourceType() {
        return "git";
    }
    
    @Override
    public void dispose() {
        if (gitService != null) {
            gitService.dispose();
        }
    }
}
