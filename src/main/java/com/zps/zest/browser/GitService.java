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
    
    // Static map to store contexts across all instances
    private static final ConcurrentHashMap<String, GitCommitContext> GLOBAL_CONTEXTS = new ConcurrentHashMap<>();
    
    public GitService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Handles files selected for commit from JavaScript bridge.
     */
    public String handleFilesSelected(JsonObject data, boolean shouldPush) {
        LOG.info("Processing files selected for commit: " + data.toString());
        
        try {
            // Parse selected files from JSON
            JsonArray selectedFilesArray = data.getAsJsonArray("selectedFiles");
            List<GitCommitContext.SelectedFile> selectedFiles = new ArrayList<>();
            
            LOG.info("Parsing " + selectedFilesArray.size() + " selected files from JavaScript:");
            
            for (int i = 0; i < selectedFilesArray.size(); i++) {
                JsonObject fileObj = selectedFilesArray.get(i).getAsJsonObject();
                String path = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();
                
                LOG.info("  File " + i + ": path='" + path + "', status='" + status + "'");
                selectedFiles.add(new GitCommitContext.SelectedFile(path, status));
            }
            
            LOG.info("Parsed " + selectedFiles.size() + " selected files");
            
            // Find active context for this project using static method
            GitCommitContext context = getActiveContextStatic(project.getName());
            if (context == null) {
                LOG.error("No active git commit context found for project: " + project.getName());
                LOG.info("Available contexts: " + GLOBAL_CONTEXTS.keySet());
                return createErrorResponse("No active commit context found");
            }
            
            // Log what paths git originally gave us
            LOG.info("Original git diff --name-status from context: " + context.getChangedFiles());
            
            // Update context with selected files
            context.setSelectedFiles(selectedFiles);
            
            // Continue the git commit pipeline directly
            GitCommitMessageGeneratorAction.continueWithSelectedFiles(context, shouldPush);
            
            // Clean up context
            removeActiveContextStatic(project.getName());
            
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
     * Registers a git commit context for the project using static storage.
     */
    public static void registerContextStatic(@NotNull GitCommitContext context) {
        String projectKey = context.getProject().getName();
        GLOBAL_CONTEXTS.put(projectKey, context);
        LOG.info("Registered git commit context for project: " + projectKey);
        LOG.info("Total active contexts: " + GLOBAL_CONTEXTS.size());
    }
    
    /**
     * Gets the active context for a project using static storage.
     */
    public static GitCommitContext getActiveContextStatic(String projectName) {
        return GLOBAL_CONTEXTS.get(projectName);
    }
    
    /**
     * Removes the active context for a project using static storage.
     */
    public static void removeActiveContextStatic(String projectName) {
        GitCommitContext removed = GLOBAL_CONTEXTS.remove(projectName);
        if (removed != null) {
            LOG.info("Removed git commit context for project: " + projectName);
        }
        LOG.info("Remaining active contexts: " + GLOBAL_CONTEXTS.size());
    }
    
    /**
     * Registers a git commit context for the project (instance method for backward compatibility).
     */
    public void registerContext(@NotNull GitCommitContext context) {
        registerContextStatic(context);
    }
    
    /**
     * Gets the active context for a project (instance method for backward compatibility).
     */
    public GitCommitContext getActiveContext(String projectName) {
        return getActiveContextStatic(projectName);
    }
    
    /**
     * Removes the active context for a project (instance method for backward compatibility).
     */
    public void removeActiveContext(String projectName) {
        removeActiveContextStatic(projectName);
    }
    
    /**
     * Gets all active contexts count (for debugging/monitoring).
     */
    public static int getActiveContextCount() {
        return GLOBAL_CONTEXTS.size();
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
        // Don't clear all contexts, just log
        LOG.info("Active contexts remaining: " + GLOBAL_CONTEXTS.size());
    }
}

