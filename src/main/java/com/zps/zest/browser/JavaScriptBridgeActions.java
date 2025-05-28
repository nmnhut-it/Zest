package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
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
    
    public JavaScriptBridgeActions(@NotNull Project project) {
        this.project = project;
        this.editorService = new EditorService(project);
        this.codeService = new CodeService(project);
        this.dialogService = new DialogService(project);
        this.fileService = new FileService(project);
        this.chatResponseService = new ChatResponseService(project);
        this.gitService = new GitService(project);
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
                    
                case "getProjectPath":
                    JsonObject projectPathResponse = new JsonObject();
                    projectPathResponse.addProperty("success", true);
                    projectPathResponse.addProperty("path", project.getBasePath());
                    return gson.toJson(projectPathResponse);
                    
                // File operations for Research Agent
                case "listFiles":
                    return fileService.listFiles(data);
                    
                case "readFile":
                    return fileService.readFile(data);
                    
                case "searchInFiles":
                    return fileService.searchInFiles(data);
                    
                case "findFunctions":
                    return fileService.findFunctions(data);
                    
                case "getDirectoryTree":
                    return fileService.getDirectoryTree(data);
                    
                case "auth":
                    String authToken = data.getAsJsonPrimitive("token").getAsString();
                    ConfigurationManager.getInstance(project).setAuthToken(authToken);
                    LOG.info("Auth token saved successfully");
                    return "";
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
                
                // Agent-related actions
                case "agentAction":
                    return handleAgentAction(data);
                    
                case "createAgent":
                    return handleCreateAgent(data);
                    
                case "getAgentStatus":
                    return handleGetAgentStatus(data);
                    
                case "executeWorkflow":
                    return handleExecuteWorkflow(data);
                
                // Content update handling
                case "contentUpdated":
                    return handleContentUpdated(data);
                
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
     * Handles agent-related actions from the Agent Framework
     */
    private String handleAgentAction(JsonObject data) {
        try {
            String agentId = data.get("agentId").getAsString();
            String role = data.get("role").getAsString();
            JsonObject payload = data.get("payload").getAsJsonObject();
            
            LOG.info("Agent action from " + role + " (ID: " + agentId + ")");
            
            // Process the agent action based on the payload
            String action = payload.get("action").getAsString();
            JsonObject actionData = payload.get("data").getAsJsonObject();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            
            // Route to appropriate service based on action type
            switch (action) {
                case "generate":
                    // Use code service to generate code
                    String generatedCode = codeService.generateCode(actionData);
                    response.addProperty("code", generatedCode);
                    break;
                    
                case "review":
                    // Perform code review
                    JsonObject reviewResult = codeService.reviewCode(actionData);
                    response.add("result", reviewResult);
                    break;
                    
                case "create_tests":
                    // Generate tests
                    String tests = codeService.generateTests(actionData);
                    response.addProperty("tests", tests);
                    break;
                    
                default:
                    // Simulate processing for demo
                    response.addProperty("message", "Processed " + action + " for " + role);
                    break;
            }
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error handling agent action", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
    }
    
    /**
     * Handles creating a new agent
     */
    private String handleCreateAgent(JsonObject data) {
        try {
            String role = data.get("role").getAsString();
            JsonObject config = data.has("config") ? data.get("config").getAsJsonObject() : new JsonObject();
            
            LOG.info("Creating agent with role: " + role);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("agentId", "agent_" + System.currentTimeMillis());
            response.addProperty("message", "Agent created successfully");
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error creating agent", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
    }
    
    /**
     * Gets the status of all agents
     */
    private String handleGetAgentStatus(JsonObject data) {
        try {
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            
            // Return mock status for demo
            JsonArray agents = new JsonArray();
            response.add("agents", agents);
            response.addProperty("totalTasks", 0);
            response.addProperty("activeTasks", 0);
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error getting agent status", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
    }
    
    /**
     * Executes a workflow
     */
    private String handleExecuteWorkflow(JsonObject data) {
        try {
            String workflowId = data.get("workflowId").getAsString();
            JsonObject workflowData = data.get("data").getAsJsonObject();
            
            LOG.info("Executing workflow: " + workflowId);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("workflowId", workflowId);
            response.addProperty("status", "started");
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error executing workflow", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
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
    }
}
