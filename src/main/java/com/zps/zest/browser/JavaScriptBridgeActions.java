package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.rag.OpenWebUIRagAgent;
import com.zps.zest.rag.models.KnowledgeCollection;
import org.jetbrains.annotations.NotNull;

/**
 * Handles routing of JavaScript bridge actions to appropriate service classes.
 * This class centralizes action dispatch and reduces the complexity of the main JavaScriptBridge.
 */
public class JavaScriptBridgeActions {
    private static final Logger LOG = Logger.getInstance(JavaScriptBridgeActions.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    private final EditorService editorService;
    private final CodeService codeService;
    private final DialogService dialogService;
    private final FileService fileService;
    private final ChatResponseService chatResponseService;
    private final GitService gitService;
    private final ExplorationService explorationService;
    
    public JavaScriptBridgeActions(@NotNull Project project) {
        this.project = project;
        this.editorService = new EditorService(project);
        this.codeService = new CodeService(project);
        this.dialogService = new DialogService(project);
        this.fileService = new FileService(project);
        this.chatResponseService = new ChatResponseService(project);
        this.gitService = new GitService(project);
        this.explorationService = new ExplorationService(project);
    }
    
    /**
     * Handles an action from the assembled message.
     * @param assembledMessage The complete message after chunk reassembly
     * @return JSON response as string
     */
    public String handleAction(String assembledMessage) {
        try {
            JsonObject request = JsonParser.parseString(assembledMessage).getAsJsonObject();
            String action = request.get("action").getAsString();
            JsonObject data =   request.has("data") ? request.get("data").getAsJsonObject() : new JsonObject();
            
            LOG.info("Handling action: " + action);
            
            // Check if we're in agent mode for actions that require it
            WebBrowserPanel.BrowserMode currentMode = WebBrowserService.getInstance(project).getBrowserPanel().getCurrentMode();
            boolean isNotAgentMode = !"Agent Mode".equals(currentMode.getName());
            
            JsonObject response = new JsonObject();
            
            switch (action) {
                // Editor-related actions
                case "getSelectedText":
                    return editorService.getSelectedText();
                
                case "insertText":
                    if (isNotAgentMode) break;
                    return editorService.insertText(data);
                
                case "getCurrentFileName":
                    return editorService.getCurrentFileName();
                
                // Code-related actions
                case "codeCompleted":
                    break;
//                    if (isNotAgentMode) break;
//                    return codeService.handleCodeComplete(data);
                
                case "extractCodeFromResponse":
                    if (isNotAgentMode) break;
                    return codeService.handleExtractedCode(data);
                
                case "showCodeDiffAndReplace":
                    LOG.info("Processing showCodeDiffAndReplace action with data: " + data.toString());
                    return codeService.handleShowCodeDiffAndReplace(data);
                
                // Dialog actions
                case "showDialog":
                    return dialogService.showDialog(data);
                
                // File operations
                case "replaceInFile":
                    if (isNotAgentMode) break;
                    return fileService.replaceInFile(data);
                
                case "batchReplaceInFile":
                    if (isNotAgentMode) break;
                    return fileService.batchReplaceInFile(data);
                
                // Project info (synchronous)
                case "getProjectInfo":
                    return editorService.getProjectInfo();
                    
                case "getProjectKnowledgeId":
                    return getProjectKnowledgeId();
                    
                case "getProjectKnowledgeCollection":
                    return getProjectKnowledgeCollection();
                    
                case "projectIndexStatus":
                    return getProjectIndexStatus();
                    
                case "indexProject":
                    return indexProject(data);
                    
                case "knowledgeApiResult":
                    return handleKnowledgeApiResult(data);
                    
                case "auth":
                    String authToken = data.getAsJsonPrimitive("token").getAsString();
                    ConfigurationManager.getInstance(project).setAuthToken(authToken);
                    LOG.info("Auth token saved successfully");
                    response.addProperty("success", true);
                    return gson.toJson(response);
                // Chat response handling
                case "notifyChatResponse":
                    return chatResponseService.notifyChatResponse(data);
                
                // Git commit operations
                case "filesSelectedForCommit":
                case "filesSelectedForCommitAndPush":
                    boolean shouldPush = data.getAsJsonPrimitive("shouldPush").getAsBoolean();
                    return gitService.handleFilesSelected(data, shouldPush);
                
                case "commitWithMessage":
                    return gitService.handleCommitWithMessage(data);
                case "gitPush":
                    return gitService.handleGitPush();
                
                case "getFileDiff":
                    return gitService.getFileDiff(data);
                    
                case "openFileDiffInIDE":
                    return gitService.openFileDiffInIDE(data);
                    
                case "getGitStatus":
                    return gitService.getGitStatus();
                
                // Content update handling
                case "contentUpdated":
                    return handleContentUpdated(data);
                
                // Exploration actions
                case "startExploration":
                    return explorationService.startExploration(data);
                    
                case "getExplorationStatus":
                    return explorationService.getExplorationStatus(data);
                    
                case "getExplorationContext":
                    return explorationService.getExplorationContext(data);
                
                case "getButtonStates":
                    return getButtonStates();
                    
                case "setContextInjectionEnabled":
                    return setContextInjectionEnabled(data);
                    
                case "setProjectIndexEnabled":
                    return setProjectIndexEnabled(data);
                
                default:
                    LOG.warn("Unknown action: " + action);
                    response.addProperty("success", false);
                    response.addProperty("error", "Unknown action: " + action);
                    return gson.toJson(response);
            }
            
            // If we reach here, the action was skipped due to mode restrictions
            response.addProperty("success", false);
            response.addProperty("error", "Action not available in current mode");
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error handling action", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
    }

    /**
     * Handles content updated events - kept here as it's specific to browser integration.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private String handleContentUpdated(JsonObject data) {
        String pageUrl = data.has("url") ? data.get("url").getAsString() : "";
        
        LOG.info("Content updated notification received for: " + pageUrl);

        // Mark the page as loaded in WebBrowserToolWindow
        ApplicationManager.getApplication().invokeLater(() -> {
            // Get the key using the same format as in WebBrowserToolWindow
            String key = project.getName() + ":" + pageUrl;

            // Update the page loaded state
            WebBrowserToolWindow.pageLoadedState.put(key, true);

            // Complete any pending futures for this URL
            java.util.concurrent.CompletableFuture<Boolean> future = WebBrowserToolWindow.pageLoadedFutures.remove(key);
            if (future != null && !future.isDone()) {
                future.complete(true);
            }
        });
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        return gson.toJson(response);
    }

    /**
     * Gets the project knowledge ID from configuration.
     */
    private String getProjectKnowledgeId() {
        JsonObject response = new JsonObject();
        try {
            String knowledgeId = ConfigurationManager.getInstance(project).getKnowledgeId();
            if (knowledgeId != null && !knowledgeId.isEmpty()) {
                response.addProperty("success", true);
                response.addProperty("result", knowledgeId);
            } else {
                response.addProperty("success", false);
                response.addProperty("error", "No knowledge base configured. Please index your project first.");
            }
        } catch (Exception e) {
            LOG.error("Error getting knowledge ID", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }

    /**
     * Starts project indexing.
     */
    private String indexProject(JsonObject data) {
        JsonObject response = new JsonObject();
        try {
            boolean forceRefresh = data.has("forceRefresh") && data.get("forceRefresh").getAsBoolean();
            
            // Get the RAG agent and start indexing
            OpenWebUIRagAgent ragAgent = OpenWebUIRagAgent.getInstance(project);
            
            // Start indexing asynchronously
            ragAgent.indexProject(forceRefresh).thenAccept(success -> {
                if (success) {
                    LOG.info("Project indexing completed successfully");
                } else {
                    LOG.error("Project indexing failed");
                }
            });
            
            response.addProperty("success", true);
            response.addProperty("message", "Indexing started");
            
        } catch (Exception e) {
            LOG.error("Error starting project indexing", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
    
    /**
     * Gets the project knowledge collection from OpenWebUI.
     */
    private String getProjectKnowledgeCollection() {
        JsonObject response = new JsonObject();
        try {
            String knowledgeId = ConfigurationManager.getInstance(project).getKnowledgeId();
            if (knowledgeId != null && !knowledgeId.isEmpty()) {
                // Get the RAG agent instance
                OpenWebUIRagAgent openWebUIRagAgent = OpenWebUIRagAgent.getInstance(project);
                
                // Fetch the complete knowledge collection
                KnowledgeCollection collection = openWebUIRagAgent.getKnowledgeCollection(knowledgeId);
                
                if (collection != null) {
                    response.addProperty("success", true);
                    // Convert the collection to JSON
                    response.add("result", gson.toJsonTree(collection));
                } else {
                    response.addProperty("success", false);
                    response.addProperty("error", "Failed to fetch knowledge collection");
                }
            } else {
                response.addProperty("success", false);
                response.addProperty("error", "No knowledge base configured. Please index your project first.");
            }
        } catch (Exception e) {
            LOG.error("Error getting knowledge collection", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
    
    /**
     * Gets the project index status.
     */
    private String getProjectIndexStatus() {
        JsonObject response = new JsonObject();
        try {
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            String knowledgeId = config.getKnowledgeId();
            
            boolean isIndexed = knowledgeId != null && !knowledgeId.isEmpty();
            
            response.addProperty("success", true);
            response.addProperty("isIndexed", isIndexed);
            response.addProperty("knowledgeId", knowledgeId != null ? knowledgeId : "");
            
            // Check if indexing is in progress
            OpenWebUIRagAgent ragAgent = OpenWebUIRagAgent.getInstance(project);
            response.addProperty("isIndexing", ragAgent.isIndexing());
            
            LOG.info("Project index status: isIndexed=" + isIndexed + ", knowledgeId=" + knowledgeId + ", isIndexing=" + ragAgent.isIndexing());
            
        } catch (Exception e) {
            LOG.error("Error getting project index status", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        
        String result = gson.toJson(response);
        LOG.info("Returning project index status response: " + result);
        return result;
    }

    /**
     * Returns the chat response service for external access.
     */
    public ChatResponseService getChatResponseService() {
        return chatResponseService;
    }
    
    /**
     * Disposes of all service resources.
     */
    public void dispose() {
        if (editorService != null) editorService.dispose();
        if (codeService != null) codeService.dispose();
        if (dialogService != null) dialogService.dispose();
        if (fileService != null) fileService.dispose();
        if (chatResponseService != null) chatResponseService.dispose();
        if (gitService != null) gitService.dispose();
        if (explorationService != null) explorationService.dispose();
    }
    
    /**
     * Handles knowledge API results from JavaScript callbacks
     */
    private String handleKnowledgeApiResult(JsonObject data) {
        try {
            String callbackId = data.get("callbackId").getAsString();
            
            // Build the result object
            JsonObject result = new JsonObject();
            
            // Copy all fields from data except callbackId
            for (var entry : data.entrySet()) {
                if (!entry.getKey().equals("callbackId")) {
                    result.add(entry.getKey(), entry.getValue());
                }
            }
            
            // Pass to the JSBridgeKnowledgeClient
            com.zps.zest.rag.JSBridgeKnowledgeClient.handleCallback(callbackId, result);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling knowledge API result", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Gets the current button states from configuration.
     */
    private String getButtonStates() {
        JsonObject response = new JsonObject();
        try {
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            response.addProperty("success", true);
            response.addProperty("contextInjectionEnabled", config.isContextInjectionEnabled());
            response.addProperty("projectIndexEnabled", config.isProjectIndexEnabled());
        } catch (Exception e) {
            LOG.error("Error getting button states", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
    
    /**
     * Sets the context injection enabled state.
     */
    private String setContextInjectionEnabled(JsonObject data) {
        JsonObject response = new JsonObject();
        try {
            boolean enabled = data.get("enabled").getAsBoolean();
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            config.setContextInjectionEnabled(enabled);
            response.addProperty("success", true);
        } catch (Exception e) {
            LOG.error("Error setting context injection state", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
    
    /**
     * Sets the project index enabled state.
     */
    private String setProjectIndexEnabled(JsonObject data) {
        JsonObject response = new JsonObject();
        try {
            boolean enabled = data.get("enabled").getAsBoolean();
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            config.setProjectIndexEnabled(enabled);
            response.addProperty("success", true);
        } catch (Exception e) {
            LOG.error("Error setting project index state", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
}
