package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.GitCommitMessageGeneratorAction;
import com.zps.zest.CodeContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling git commit operations from JavaScript bridge.
 * This manages git commit contexts and file selection workflows.
 */
public class GitService {
    private static final Logger LOG = Logger.getInstance(GitService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    // Context management
    private final ConcurrentHashMap<String, GitCommitContext> activeContexts = new ConcurrentHashMap<>();
    
    public GitService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Handles files selected for commit from JavaScript bridge.
     */
    public String handleFilesSelected(JsonObject data) {
        LOG.info("Processing files selected for commit: " + data.toString());
        
        try {
            // Parse selected files from JSON
            JsonArray selectedFilesArray = data.getAsJsonArray("selectedFiles");
            List<GitCommitContext.SelectedFile> selectedFiles = new ArrayList<>();
            
            for (int i = 0; i < selectedFilesArray.size(); i++) {
                JsonObject fileObj = selectedFilesArray.get(i).getAsJsonObject();
                String path = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();
                selectedFiles.add(new GitCommitContext.SelectedFile(path, status));
            }
            
            LOG.info("Parsed " + selectedFiles.size() + " selected files");
            
            // Find active context for this project
            GitCommitContext context = getActiveContext(project.getName());
            if (context == null) {
                LOG.error("No active git commit context found for project: " + project.getName());
                return createErrorResponse("No active commit context found");
            }
            
            // Update context with selected files
            context.setSelectedFiles(selectedFiles);
            
            // Continue the git commit pipeline directly
            continueGitCommitPipeline(context);
            
            // Clean up context
            removeActiveContext(project.getName());
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Files selected and commit pipeline continued");
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error handling files selected for commit", e);
            return createErrorResponse("Failed to process selected files: " + e.getMessage());
        }
    }

    /**
     * Continues the git commit pipeline with selected files
     */
    private void continueGitCommitPipeline(GitCommitContext context) {
        // Trigger the pipeline continuation in the GitCommitMessageGeneratorAction
        // We'll use a static method for this
        GitCommitMessageGeneratorAction.continueWithSelectedFiles(context);
    }
    
    /**
     * Registers a git commit context for the project.
     */
    public void registerContext(@NotNull GitCommitContext context) {
        String projectKey = project.getName();
        activeContexts.put(projectKey, context);
        LOG.info("Registered git commit context for project: " + projectKey);
    }
    
    /**
     * Gets the active context for a project.
     */
    public GitCommitContext getActiveContext(String projectName) {
        return activeContexts.get(projectName);
    }
    
    /**
     * Removes the active context for a project.
     */
    public void removeActiveContext(String projectName) {
        GitCommitContext removed = activeContexts.remove(projectName);
        if (removed != null) {
            LOG.info("Removed git commit context for project: " + projectName);
        }
    }
    
    /**
     * Gets all active contexts (for debugging/monitoring).
     */
    public int getActiveContextCount() {
        return activeContexts.size();
    }
    
    /**
     * Creates an error response in JSON format.
     */
    private String createErrorResponse(String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", errorMessage);
        return gson.toJson(response);
    }
    
    /**
     * Disposes of any resources and clears active contexts.
     */
    public void dispose() {
        LOG.info("Disposing GitService for project: " + project.getName());
        activeContexts.clear();
    }
}

