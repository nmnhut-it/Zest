package com.zps.zest.research.search.strategies;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.GitService;
import com.zps.zest.browser.utils.UnstagedChangesUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Search strategy for searching unstaged changes.
 */
public class UnstagedSearchStrategy implements SearchStrategy {
    private static final Logger LOG = Logger.getInstance(UnstagedSearchStrategy.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_UNSTAGED_RESULTS = 3;
    
    private final Project project;
    private final GitService gitService;
    
    public UnstagedSearchStrategy(@NotNull Project project) {
        this.project = project;
        this.gitService = new GitService(project);
    }
    
    @Override
    public CompletableFuture<JsonArray> search(List<String> keywords) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Unstaged changes search started with keywords: " + keywords);
            JsonArray results = new JsonArray();
            
            try {
                // Get unstaged changes
                String unstagedResult = gitService.getUnstagedChanges(new JsonObject());
                JsonObject unstagedData = GSON.fromJson(unstagedResult, JsonObject.class);
                
                if (unstagedData.get("success").getAsBoolean() && unstagedData.has("changes")) {
                    JsonArray changes = unstagedData.getAsJsonArray("changes");
                    LOG.info("Found " + changes.size() + " unstaged files");
                    
                    // Process each changed file
                    for (int i = 0; i < changes.size(); i++) {
                        JsonObject change = changes.get(i).getAsJsonObject();
                        String filePath = change.get("file").getAsString();
                        String status = change.get("status").getAsString();
                        
                        // Skip deleted files
                        if ("D".equals(status)) {
                            continue;
                        }
                        
                        // Check if this file matches any keyword
                        boolean fileMatches = false;
                        String matchedKeyword = null;
                        
                        for (String keyword : keywords) {
                            if (filePath.toLowerCase().contains(keyword.toLowerCase())) {
                                fileMatches = true;
                                matchedKeyword = keyword;
                                break;
                            }
                        }
                        
                        // Always analyze if we have keywords or if file matches
                        if (fileMatches || UnstagedChangesUtils.needsContentAnalysis(keywords)) {
                            // Get the diff for this file
                            JsonObject diffRequest = new JsonObject();
                            diffRequest.addProperty("filePath", filePath);
                            String diffResult = gitService.getDiffForFile(diffRequest);
                            JsonObject diffData = GSON.fromJson(diffResult, JsonObject.class);
                            
                            if (diffData.get("success").getAsBoolean() && diffData.has("diff")) {
                                String diff = diffData.get("diff").getAsString();
                                
                                // Analyze the file
                                JsonObject fileAnalysis = UnstagedChangesUtils.analyzeUnstagedFile(
                                    project, filePath, diff, keywords
                                );
                                
                                if (fileAnalysis != null && fileAnalysis.has("changedFunctions")) {
                                    JsonArray changedFunctions = fileAnalysis.getAsJsonArray("changedFunctions");
                                    if (changedFunctions.size() > 0) {
                                        JsonObject result = new JsonObject();
                                        result.addProperty("keyword", matchedKeyword != null ? matchedKeyword :
                                            (keywords.isEmpty() ? "all" : keywords.get(0)));
                                        result.addProperty("file", filePath);
                                        result.addProperty("status", status);
                                        result.add("analysis", fileAnalysis);
                                        
                                        // Add line statistics if available
                                        if (change.has("linesAdded")) {
                                            result.addProperty("linesAdded", change.get("linesAdded").getAsInt());
                                        }
                                        if (change.has("linesDeleted")) {
                                            result.addProperty("linesDeleted", change.get("linesDeleted").getAsInt());
                                        }
                                        
                                        results.add(result);
                                    }
                                }
                            } else {
                                LOG.warn("Could not get diff for file: " + filePath);
                            }
                        }
                        
                        // Limit results
                        if (results.size() >= MAX_UNSTAGED_RESULTS) {
                            LOG.info("Reached unstaged result limit (" + MAX_UNSTAGED_RESULTS + ")");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in unstaged changes search", e);
            }
            
            LOG.info("Unstaged changes search completed with " + results.size() + " results");
            return results;
        });
    }
    
    @Override
    public String getSourceType() {
        return "unstaged";
    }
    
    @Override
    public void dispose() {
        if (gitService != null) {
            gitService.dispose();
        }
    }
}
