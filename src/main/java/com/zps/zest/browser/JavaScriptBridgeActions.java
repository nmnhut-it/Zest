package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.git.GitService;
import com.zps.zest.langchain4j.agent.network.ProjectProxyManager;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import org.jetbrains.annotations.NotNull;

/**
 * Handles routing of JavaScript bridge actions to appropriate service classes.
 * This class centralizes action dispatch and reduces the complexity of the main JavaScriptBridge.
 * Enhanced version with panel loading state checks.
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
    private final ZestLangChain4jService langChainService;

    public JavaScriptBridgeActions(@NotNull Project project) {
        this.project = project;
        this.editorService = new EditorService(project);
        this.codeService = new CodeService(project);
        this.dialogService = new DialogService(project);
        this.fileService = new FileService(project);
        this.chatResponseService = new ChatResponseService(project);
        this.gitService = new GitService(project);
        this.langChainService = project.getService(ZestLangChain4jService.class);
    }

    /**
     * Checks if the browser panel is ready for JavaScript execution.
     * @return true if panel is loaded and ready, false otherwise
     */
    private boolean isPanelReady() {
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService == null) {
                LOG.debug("Browser service not available");
                return false;
            }

            WebBrowserPanel browserPanel = browserService.getBrowserPanel();
            if (browserPanel == null) {
                LOG.debug("Browser panel not initialized");
                return false;
            }

            JCEFBrowserManager browserManager = browserPanel.getBrowserManager();
            if (browserManager == null) {
                LOG.debug("Browser manager not available");
                return false;
            }

            String currentUrl = browserPanel.getCurrentUrl();
            if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) {
                LOG.debug("Browser panel has no valid URL loaded");
                return false;
            }

            // Check if the page is actually loaded (simplified check since tool window is removed)
            // Page is considered loaded if we have a valid URL and browser manager
            if (currentUrl.contains("about:blank") || currentUrl.contains("chrome-error://")) {
                LOG.debug("Browser page not fully loaded yet: " + currentUrl);
                return false;
            }

            LOG.debug("Browser panel is ready for JavaScript execution");
            return true;
        } catch (Exception e) {
            LOG.warn("Error checking panel readiness", e);
            return false;
        }
    }

    /**
     * Creates a standard "panel not ready" error response.
     */
    private JsonObject createPanelNotReadyResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", "Browser panel is not ready. Please wait for the page to load completely.");
        response.addProperty("errorType", "PANEL_NOT_READY");
        return response;
    }

    /**
     * Checks if an action requires the panel to be fully loaded.
     * @param action The action name
     * @return true if the action requires a loaded panel
     */
    private boolean requiresPanelLoaded(String action) {
        // Actions that require JavaScript execution in the browser
        return switch (action) {
            case "insertText",
                 "extractCodeFromResponse",
                 "showCodeDiffAndReplace",
                 "replaceInFile",
                 "batchReplaceInFile",
                 "notifyChatResponse",
                 "indexProject",
                 "knowledgeApiResult",
                 "startExploration",
                 "getExplorationStatus",
                 "getExplorationContext" -> true;
            default -> false;
        };
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
            JsonObject data = request.has("data") ? request.get("data").getAsJsonObject() : new JsonObject();

            LOG.info("Handling action: " + action);

            // Check if we're in agent mode for actions that require it
            WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
            WebBrowserPanel.BrowserMode currentMode = null;
            boolean isNotAgentMode = false;
            
            if (browserPanel != null) {
                currentMode = browserPanel.getCurrentMode();
                isNotAgentMode = currentMode == null || !"Agent Mode".equals(currentMode.getName());
                LOG.debug("Browser panel found, current mode: " + currentMode.getName());
            } else {
                // Fallback behavior when WebBrowserPanel is not available (e.g., in chat UI)
                LOG.info("WebBrowserPanel not available, defaulting to Agent Mode behavior for compatibility");
                isNotAgentMode = false; // Default to allowing actions (Agent Mode behavior)
            }

            // Check if panel is ready for actions that require it
            boolean panelNotReady = requiresPanelLoaded(action) && !isPanelReady();

            JsonObject response = new JsonObject();

            switch (action) {
                // Editor-related actions (don't require panel loading)
                case "getSelectedText":
                    return editorService.getSelectedText();

                case "insertText":
                    if (isNotAgentMode) break;
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
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
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    return codeService.handleExtractedCode(data);

                case "showCodeDiffAndReplace":
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    LOG.info("Processing showCodeDiffAndReplace action with data: " + data.toString());
                    return codeService.handleShowCodeDiffAndReplace(data);

                // Dialog actions (don't require panel loading)
                case "showDialog":
                    return dialogService.showDialog(data);

                // File operations
                case "replaceInFile":
                    if (isNotAgentMode) break;
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    return fileService.replaceInFile(data);

                case "batchReplaceInFile":
                    if (isNotAgentMode) break;
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    return fileService.batchReplaceInFile(data);

                // Project info (synchronous - don't require panel loading)
                case "getProjectInfo":
                    return editorService.getProjectInfo();


//                    return getProjectIndexStatus();

                case "getToolServers":
                    return getToolServers();

                case "auth":
                    String authToken = data.getAsJsonPrimitive("token").getAsString();
                    ConfigurationManager.getInstance(project).setAuthToken(authToken);
                    LOG.info("Auth token saved successfully");
                    response.addProperty("success", true);
                    return gson.toJson(response);

                // Chat response handling
                case "notifyChatResponse":
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    return chatResponseService.notifyChatResponse(data);
                    
                // Enhanced chat with LangChain retrieval
                case "enhancedChatMessage":
                    if (isNotAgentMode) break;
                    if (panelNotReady) return gson.toJson(createPanelNotReadyResponse());
                    return handleEnhancedChatMessage(data);

                // Git commit operations (don't require panel loading)
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
                    
                case "getBatchFileDiffs":
                    return gitService.getBatchFileDiffs(data);

                case "openFileDiffInIDE":
                    return gitService.openFileDiffInIDE(data);

                case "getGitStatus":
                    return gitService.getGitStatus();
                    
                case "getFileContent":
                    return gitService.getFileContent(data);
                    
                case "triggerCodeGuardianAnalysis":
                    return gitService.triggerCodeGuardianAnalysis(data);

                case "generateCommitMessage":
                    return gitService.generateCommitMessage(data);
                    
                case "streamCommitMessage":
                    return gitService.streamCommitMessage(data);

                // Content update handling (marks page as loaded)
                case "contentUpdated":
                    return handleContentUpdated(data);


                // Configuration actions (don't require panel loading)

                case "getCommitPromptTemplate":
                    return getCommitPromptTemplate();

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

        // Page loading state tracking - simplified since WebBrowserToolWindow was removed
        ApplicationManager.getApplication().invokeLater(() -> {
            LOG.debug("Page content updated for: " + pageUrl);
            // Note: Page loading state tracking removed with WebBrowserToolWindow migration
        });

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        return gson.toJson(response);
    }


    /**
     * Handles enhanced chat messages with LangChain retrieval for better context.
     */
    private String handleEnhancedChatMessage(JsonObject data) {
        try {
            String message = data.get("message").getAsString();
            
            // Get conversation history if provided
            String[] history = new String[0];
            if (data.has("history") && data.get("history").isJsonArray()) {
                history = gson.fromJson(data.get("history"), String[].class);
            }
            
            LOG.info("Processing enhanced chat message with LangChain retrieval");
            
            // Use LangChain service to process the message with context
            String enhancedResponse = langChainService.chatWithContext(
                message, 
                java.util.List.of(history)
            ).get(30, java.util.concurrent.TimeUnit.SECONDS);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("response", enhancedResponse);
            response.addProperty("enhanced", true);
            response.addProperty("timestamp", System.currentTimeMillis());
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error processing enhanced chat message", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", "Enhanced chat failed: " + e.getMessage());
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


    /**
     * Gets the commit prompt template from configuration.
     */
    private String getCommitPromptTemplate() {
        JsonObject response = new JsonObject();
        try {
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            String template = config.getCommitPromptTemplate();
            response.addProperty("success", true);
            response.addProperty("template", template);
        } catch (Exception e) {
            LOG.error("Error getting commit prompt template", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }

    /**
     * Converts a string to camelCase format.
     */
    private String toCamelCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // Replace non-alphanumeric characters with spaces
        String cleaned = input.replaceAll("[^a-zA-Z0-9]", " ");
        
        // Split by spaces and process
        String[] words = cleaned.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            String word = words[i].trim();
            if (!word.isEmpty()) {
                if (i == 0) {
                    // First word is lowercase
                    result.append(word.substring(0, 1).toLowerCase());
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                } else {
                    // Subsequent words have first letter uppercase
                    result.append(word.substring(0, 1).toUpperCase());
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                }
            }
        }
        
        return result.toString();
    }

    /**
     * Gets the tool servers configuration for dynamic injection.
     */
    private String getToolServers() {
        JsonObject response = new JsonObject();
        try {
            // First try to get project-specific proxy URL
            String proxyUrl = ProjectProxyManager.getInstance().getProxyUrlForProject(project);
            
            // Fall back to system property if no project-specific proxy
            if (proxyUrl == null || proxyUrl.isEmpty()) {
                proxyUrl = System.getProperty("zest.agent.proxy.url");
            }
            
            if (proxyUrl != null && !proxyUrl.isEmpty()) {
                try {
                    // Fetch the OpenAPI spec from the proxy server
                    java.net.URL url = new java.net.URL(proxyUrl + "/zest/openapi.json");
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode == 200) {
                        // Read the OpenAPI spec
                        java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream())
                        );
                        String inputLine;
                        StringBuilder content = new StringBuilder();
                        while ((inputLine = in.readLine()) != null) {
                            content.append(inputLine);
                        }
                        in.close();
                        
                        // Parse the OpenAPI spec
                        JsonObject openApiSpec = JsonParser.parseString(content.toString()).getAsJsonObject();
                        
                        // Extract tools from paths
                        JsonObject paths = openApiSpec.getAsJsonObject("paths");
                        com.google.gson.JsonArray specs = new com.google.gson.JsonArray();
                        
                        // Convert OpenAPI paths to tool specs for OpenWebUI
                        for (String path : paths.keySet()) {
                            if (path.startsWith("/zest/") || path.equals("/explore_code") || path.equals("/list_tools")) {
                                JsonObject pathObj = paths.getAsJsonObject(path).getAsJsonObject("post");
                                if (pathObj != null) {
                                    JsonObject spec = new JsonObject();
                                    spec.addProperty("type", "function");
                                    spec.addProperty("name", pathObj.get("operationId").getAsString());
                                    spec.addProperty("description", pathObj.get("summary").getAsString());
                                    
                                    // Extract parameters from requestBody schema
                                    if (pathObj.has("requestBody")) {
                                        JsonObject requestBody = pathObj.getAsJsonObject("requestBody");
                                        JsonObject requestBodyContent = requestBody.getAsJsonObject("content");
                                        if (requestBodyContent != null && requestBodyContent.has("application/json")) {
                                            JsonObject jsonContent = requestBodyContent.getAsJsonObject("application/json");
                                            JsonObject schema = jsonContent.getAsJsonObject("schema");
                                            if (schema != null && schema.has("$ref")) {
                                                String ref = schema.get("$ref").getAsString();
                                                String schemaName = ref.substring(ref.lastIndexOf("/") + 1);
                                                
                                                // Get the actual schema definition
                                                JsonObject components = openApiSpec.getAsJsonObject("components");
                                                JsonObject schemas = components.getAsJsonObject("schemas");
                                                JsonObject paramSchema = schemas.getAsJsonObject(schemaName);
                                                
                                                if (paramSchema != null) {
                                                    spec.add("parameters", paramSchema);
                                                }
                                            }
                                        }
                                    } else {
                                        // For endpoints with no parameters
                                        JsonObject emptyParams = new JsonObject();
                                        emptyParams.addProperty("type", "object");
                                        emptyParams.add("properties", new JsonObject());
                                        emptyParams.add("required", new com.google.gson.JsonArray());
                                        spec.add("parameters", emptyParams);
                                    }
                                    
                                    specs.add(spec);
                                }
                            }
                        }
                        
                        // Create the tool server object in OpenWebUI settings format
                        JsonObject toolServer = new JsonObject();
                        toolServer.addProperty("url", proxyUrl + "/zest");
                        toolServer.addProperty("path", "openapi.json");
                        toolServer.addProperty("auth_type", "bearer");
                        toolServer.addProperty("key", "");
                        
                        // Add config object - ENABLE by default
                        JsonObject config = new JsonObject();
                        config.addProperty("enable", true);  // Enable by default
                        config.add("access_control", new JsonObject());
                        toolServer.add("config", config);
                        
                        // Add info object from OpenAPI spec with project-specific info
                        JsonObject info = new JsonObject();
                        String projectName = project.getName();
                        String projectNameCamelCase = toCamelCase(projectName);
                        
                        if (openApiSpec.has("info")) {
                            JsonObject specInfo = openApiSpec.getAsJsonObject("info");
                            // Include project name in the tool name
                            String baseName = specInfo.has("title") ? specInfo.get("title").getAsString() : "Zest Code Explorer";
                            info.addProperty("name", baseName + " - " + projectName);
                            
                            // Include project context in description
                            String baseDesc = specInfo.has("description") ? specInfo.get("description").getAsString() : "Code exploration tools from IDE";
                            info.addProperty("description", baseDesc + " for project: " + projectName);
                        } else {
                            info.addProperty("name", "Zest Code Explorer - " + projectName);
                            info.addProperty("description", "Code exploration tools from IDE for project: " + projectName);
                        }
                        
                        // Add project metadata for validation
                        info.addProperty("projectName", projectName);
                        info.addProperty("projectNameCamelCase", projectNameCamelCase);
                        
                        toolServer.add("info", info);
                        
                        com.google.gson.JsonArray toolServers = new com.google.gson.JsonArray();
                        toolServers.add(toolServer);
                        
                        response.addProperty("success", true);
                        response.add("servers", toolServers);
                        LOG.info("Successfully fetched and converted OpenAPI spec from: " + proxyUrl);
                    } else {
                        response.addProperty("success", false);
                        response.addProperty("error", "Failed to fetch OpenAPI spec: HTTP " + responseCode);
                    }
                } catch (Exception e) {
                    LOG.error("Error fetching OpenAPI spec from proxy", e);
                    response.addProperty("success", false);
                    response.addProperty("error", "Failed to fetch OpenAPI spec: " + e.getMessage());
                }
            } else {
                response.addProperty("success", false);
                response.addProperty("error", "No active tool server");
            }
        } catch (Exception e) {
            LOG.error("Error getting tool servers", e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return gson.toJson(response);
    }
}