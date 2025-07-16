package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.browser.JCEFBrowserManager;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.settings.ZestGlobalSettings;
import com.zps.zest.settings.ZestProjectSettings;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Configuration manager that bridges the old API with new IntelliJ settings.
 * This class maintains backward compatibility while delegating to the new settings system.
 */
public class ConfigurationManager {
    private static final Logger LOG = Logger.getInstance(ConfigurationManager.class);
    public static final String CODE_EXPERT = "code-expert";
    private static final String DEFAULT_API_URL = "https://chat.zingplay.com/api/chat/completions";
    private static final String DEFAULT_API_URL_2 = "https://talk.zingplay.com/api/chat/completions";
    private static final int CONNECTION_TIMEOUT = 3000; // 3 seconds

    // Default system prompts - reference from ZestGlobalSettings to avoid duplication
    public static final String DEFAULT_SYSTEM_PROMPT = ZestGlobalSettings.DEFAULT_SYSTEM_PROMPT;
    public static final String DEFAULT_CODE_SYSTEM_PROMPT = ZestGlobalSettings.DEFAULT_CODE_SYSTEM_PROMPT;
    public static final String DEFAULT_COMMIT_PROMPT_TEMPLATE = ZestGlobalSettings.DEFAULT_COMMIT_PROMPT_TEMPLATE;
    
    // Static cache to store configuration managers by project
    private static final Map<Project, ConfigurationManager> INSTANCES = new ConcurrentHashMap<>();

    // Register project listener to clean up closed projects
    static {
        ApplicationManager.getApplication().getMessageBus().connect().subscribe(
                ProjectManager.TOPIC, 
                new ProjectManagerListener() {
                    @Override
                    public void projectClosed(Project project) {
                        disposeInstance(project);
                    }
                }
        );
    }

    private final Project project;
    private final ZestGlobalSettings globalSettings;
    private final ZestProjectSettings projectSettings;

    /**
     * Private constructor to enforce singleton pattern per project.
     *
     * @param project The project to create a configuration manager for
     */
    private ConfigurationManager(Project project) {
        this.project = project;
        this.globalSettings = ZestGlobalSettings.getInstance();
        this.projectSettings = ZestProjectSettings.getInstance(project);
        
        // Check if API URL needs to be updated on first run
        checkAndUpdateDefaultApiUrl();
    }

    /**
     * Gets or creates a ConfigurationManager instance for the specified project.
     *
     * @param project The project to get a configuration manager for
     * @return The configuration manager instance for the project
     */
    public static ConfigurationManager getInstance(Project project) {
        return INSTANCES.computeIfAbsent(project, ConfigurationManager::new);
    }

    /**
     * Removes the configuration manager instance for a project.
     * Called when a project is closed.
     *
     * @param project The project to remove the configuration manager for
     */
    public static void disposeInstance(Project project) {
        INSTANCES.remove(project);
    }

    // Delegated getters that use the new settings system
    public String getApiUrl() {
        return globalSettings.apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        globalSettings.apiUrl = apiUrl;
    }

    public String getTestModel() {
        return globalSettings.testModel;
    }

    public void setTestModel(String testModel) {
        globalSettings.testModel = testModel;
    }

    public String getCodeModel() {
        return globalSettings.codeModel;
    }

    public void setCodeModel(String codeModel) {
        globalSettings.codeModel = codeModel;
    }

    public int getMaxIterations() {
        return projectSettings.maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        projectSettings.maxIterations = maxIterations;
    }

    /**
     * Gets the current auth token or prompts the user to enter one if none exists.
     *
     * @return The current auth token, or null if the user cancels the prompt
     */
    public String getAuthToken() {
        if (globalSettings.authToken == null || globalSettings.authToken.trim().isEmpty()) {
            // Token is not set, prompt user to provide one
            return promptForAuthToken(globalSettings.apiUrl);
        }
        return globalSettings.authToken;
    }

    /**
     * Gets the current auth token without prompting the user.
     * Use this method when checking if a token exists.
     *
     * @return The current auth token or empty string if none exists
     */
    public String getAuthTokenNoPrompt() {
        return globalSettings.authToken;
    }

    public void setAuthToken(String authToken) {
        globalSettings.authToken = authToken;
        
        // Notify the browser to update its stored auth token
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService != null && browserService.getBrowserPanel() != null) {
                JCEFBrowserManager browserManager = browserService.getBrowserPanel().getBrowserManager();
                if (browserManager != null) {
                    browserManager.updateAuthTokenInBrowser(authToken);
                }
            }
        } catch (Exception e) {
            // Log but don't fail if browser update fails
            LOG.warn("Could not update auth token in browser", e);
        }
    }

    public boolean isRagEnabled() {
        return projectSettings.ragEnabled;
    }

    public void setRagEnabled(boolean value) {
        projectSettings.ragEnabled = value;
    }

    public boolean isMcpEnabled() {
        return projectSettings.mcpEnabled;
    }

    public void setMcpEnabled(boolean value) {
        projectSettings.mcpEnabled = value;
    }

    public String getMcpServerUri() {
        return projectSettings.mcpServerUri;
    }

    public void setMcpServerUri(String uri) {
        projectSettings.mcpServerUri = uri;
    }

    public String getSystemPrompt() {
        return globalSettings.systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        globalSettings.systemPrompt = systemPrompt;
    }

    public String getCodeSystemPrompt() {
        return globalSettings.codeSystemPrompt;
    }

    public void setCodeSystemPrompt(String codeSystemPrompt) {
        globalSettings.codeSystemPrompt = codeSystemPrompt;
    }

    public String getCommitPromptTemplate() {
        return globalSettings.commitPromptTemplate;
    }

    public void setCommitPromptTemplate(String commitPromptTemplate) {
        com.zps.zest.validation.CommitTemplateValidator.ValidationResult validation = 
            com.zps.zest.validation.CommitTemplateValidator.validate(commitPromptTemplate);
        
        if (!validation.isValid) {
            LOG.error("Invalid commit prompt template: " + validation.errorMessage);
            throw new IllegalArgumentException("Invalid template: " + validation.errorMessage);
        }
        
        globalSettings.commitPromptTemplate = commitPromptTemplate;
    }

    public String getKnowledgeId() {
        return projectSettings.knowledgeId.isEmpty() ? null : projectSettings.knowledgeId;
    }

    public void setKnowledgeId(String knowledgeId) {
        projectSettings.knowledgeId = knowledgeId != null ? knowledgeId : "";
    }

    public boolean isContextInjectionEnabled() {
        return projectSettings.contextInjectionEnabled;
    }

    public void setContextInjectionEnabled(boolean enabled) {
        projectSettings.contextInjectionEnabled = enabled;
        if (enabled && projectSettings.projectIndexEnabled) {
            projectSettings.projectIndexEnabled = false;
        }
    }

    public boolean isProjectIndexEnabled() {
        return projectSettings.projectIndexEnabled;
    }

    public void setProjectIndexEnabled(boolean enabled) {
        projectSettings.projectIndexEnabled = enabled;
        if (enabled && projectSettings.contextInjectionEnabled) {
            projectSettings.contextInjectionEnabled = false;
        }
    }

    public String getDocsPath() {
        return projectSettings.docsPath;
    }

    public void setDocsPath(String docsPath) {
        projectSettings.docsPath = docsPath;
    }

    public boolean isDocsSearchEnabled() {
        return projectSettings.docsSearchEnabled;
    }

    public void setDocsSearchEnabled(boolean docsSearchEnabled) {
        projectSettings.docsSearchEnabled = docsSearchEnabled;
    }

    public boolean isInlineCompletionEnabled() {
        return globalSettings.inlineCompletionEnabled;
    }

    public void setInlineCompletionEnabled(boolean enabled) {
        globalSettings.inlineCompletionEnabled = enabled;
        
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }

    public boolean isAutoTriggerEnabled() {
        return globalSettings.autoTriggerEnabled;
    }

    public void setAutoTriggerEnabled(boolean enabled) {
        globalSettings.autoTriggerEnabled = enabled;
        
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }

    public boolean isBackgroundContextEnabled() {
        return globalSettings.backgroundContextEnabled;
    }

    public void setBackgroundContextEnabled(boolean enabled) {
        globalSettings.backgroundContextEnabled = enabled;
        
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }

    public boolean isContinuousCompletionEnabled() {
        return globalSettings.continuousCompletionEnabled;
    }

    public void setContinuousCompletionEnabled(boolean enabled) {
        globalSettings.continuousCompletionEnabled = enabled;
        
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }

    // Compatibility methods
    public String getOpenWebUIRagEndpoint() {
        return globalSettings.apiUrl;
    }

    public String getOpenWebUISystemPrompt() {
        return globalSettings.systemPrompt;
    }

    public String getOpenWebUISystemPromptForCode() {
        return globalSettings.codeSystemPrompt;
    }

    public String getBossPrompt() {
        String s = "Bạn là sếp của tôi. Bạn nói chuyện ngắn gọn, không giải thích nhiều trừ khi cần thiết, và dùng nhiều câu trực tiếp mà thân thiện. \n" +
                "\n" +
                "Ví dụ: \n" +
                "Péo lắm đấy em\n" +
                "Anh qua em review Game design Match3 nha\n" +
                "Thực ra cái này có những điểm yếu như sau ...\n" +
                "Nếu mà em làm cái này thì sẽ có nguy cơ là, ....., em giải quyết như thế nào ...\n" +
                "-------\n" +
                "Bạn có những kỹ năng xuất sắc, bao gồm nhưng không giới hạn ở việc trả lời câu hỏi, tư duy phản biện, phân tích vấn đề. Bạn đã thông thạo các khung tư duy hiện có và các khung giải quyết vấn đề luôn tận dụng chúng, dùng SWOT Analysis để phân tích và cho lời khuyên. \n" +
                "\n" +
                "Bạn luôn tìm cách nắm trọn ý người nói muốn diễn đạt bằng cách đặt câu hỏi, sau đó phân tích rồi đưa ra nhận xét ngắn gọn dưới hình thức các câu hỏi để tôi tự trả lời.\n" +
                "---------\n" +
                "Bạn đang trong một cuộc họp. Bạn sẽ lắng nghe, đặt câu hỏi để làm rõ và thách thức tôi bằng các câu hỏi. Bạn hỏi tôi từng câu hỏi một để giúp tôi giải quyết vấn đề hoặc tìm ra điểm yếu, hoặc để đưa ra một ý tưởng mới hoặc giải quyết các vấn đề.";
        return s;
    }

    /**
     * Legacy method - no longer loads from file, just ensures defaults are set
     */
    public void loadConfig() {
        // This method is kept for compatibility but doesn't do anything
        // Settings are now loaded automatically by IntelliJ
    }

    /**
     * Legacy method - no longer saves to file
     */
    public void saveConfig() {
        // This method is kept for compatibility but doesn't do anything
        // Settings are now saved automatically by IntelliJ
    }

    /**
     * Prompts the user to enter an authentication token.
     * This method should be called when an API call requires a token and none is available.
     *
     * @param apiUrl The API URL that requires authentication
     * @return The authentication token
     */
    public String promptForAuthToken(String apiUrl) {
        if (globalSettings.authToken != null && !globalSettings.authToken.trim().isEmpty()) {
            return globalSettings.authToken; // Return existing token if available
        }

        final String[] resultToken = {null};

        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                // Prompt the user to enter an auth token
                String token = Messages.showInputDialog(
                        project,
                        "Enter your API authorization token for " + apiUrl,
                        "API Authorization Required",
                        Messages.getQuestionIcon()
                );

                // Update the token if the user provided one
                if (token != null && !token.trim().isEmpty()) {
                    resultToken[0] = token.trim();
                    globalSettings.authToken = token.trim();
                    
                    // Update the browser's auth token immediately
                    try {
                        WebBrowserService browserService = WebBrowserService.getInstance(project);
                        if (browserService != null && browserService.getBrowserPanel() != null) {
                            JCEFBrowserManager browserManager = browserService.getBrowserPanel().getBrowserManager();
                            if (browserManager != null) {
                                browserManager.updateAuthTokenInBrowser(globalSettings.authToken);
                            }
                        }
                    } catch (Exception ex) {
                        LOG.warn("Could not update auth token in browser", ex);
                    }

                    Messages.showInfoMessage(
                            "Authorization token has been saved.",
                            "Configuration Updated"
                    );
                }
            } catch (Exception e) {
                e.printStackTrace();
                Messages.showErrorDialog(
                        "Failed to save authorization token: " + e.getMessage(),
                        "Configuration Error"
                );
            }
        });

        return resultToken[0];
    }

    /**
     * Checks and updates the default API URL if needed.
     * This is called once when ConfigurationManager is created.
     */
    private void checkAndUpdateDefaultApiUrl() {
        // Only update if using the old default URL
        if (globalSettings == null)
            return;
        if (DEFAULT_API_URL.equals(globalSettings.apiUrl)) {
            String bestUrl = determineDefaultApiUrl();
            if (!bestUrl.equals(globalSettings.apiUrl)) {
                globalSettings.apiUrl = bestUrl;
            }
        }
    }

    /**
     * Determines the default API URL to use by pinging domains in order of preference.
     * First tries DEFAULT_API_URL_2 domain, then falls back to DEFAULT_API_URL.
     *
     * @return The best available API URL
     */
    private String determineDefaultApiUrl() {
        // Extract domain from DEFAULT_API_URL_2 without the API path
        String domain2 = extractDomain(DEFAULT_API_URL_2);

        // First check if DEFAULT_API_URL_2 domain is reachable
        if (domain2 != null && isDomainReachable(domain2)) {
            return DEFAULT_API_URL_2;
        }

        // If not reachable, use DEFAULT_API_URL
        return DEFAULT_API_URL;
    }

    /**
     * Extracts the domain part from a URL string (protocol + hostname).
     *
     * @param urlString The full URL string
     * @return The domain part or null if parsing fails
     */
    private String extractDomain(String urlString) {
        try {
            URL url = new URL(urlString);
            return url.getProtocol() + "://" + url.getHost();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Checks if a domain is reachable by making a HEAD request.
     *
     * @param domain The domain to check
     * @return true if the domain is reachable, false otherwise
     */
    private boolean isDomainReachable(String domain) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(domain);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(CONNECTION_TIMEOUT);
                connection.setReadTimeout(CONNECTION_TIMEOUT);
                int responseCode = connection.getResponseCode();
                return responseCode >= 200 && responseCode < 400;
            } catch (SocketTimeoutException e) {
                // Timeout occurred
                return false;
            } catch (IOException e) {
                // Connection failed
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });

        try {
            return future.get(CONNECTION_TIMEOUT + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // Failed to complete the check in time
            return false;
        }
    }
}
