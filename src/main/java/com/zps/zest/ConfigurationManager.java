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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public static final String CODE_EXPERT = "local-model";
    private static final String DEFAULT_API_URL = "https://chat.zingplay.com/api/chat/completions";
    private static final String DEFAULT_API_URL_2 = "https://talk.zingplay.com/api/chat/completions";
    private static final int CONNECTION_TIMEOUT = 3000; // 3 seconds
    
    // .zest folder constants
    private static final String ZEST_FOLDER = ".zest";
    private static final String RULES_FILE = "rules.md";
    private static final String LEGACY_RULES_FILE = "zest_rules.md";

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
    private   ZestGlobalSettings globalSettings;
    private   ZestProjectSettings projectSettings;

    /**
     * Private constructor to enforce singleton pattern per project.
     *
     * @param project The project to create a configuration manager for
     */
    private ConfigurationManager(Project project) {
        this.project = project;
        
        // Initialize settings with null checks
        try {
            this.globalSettings = ZestGlobalSettings.getInstance();
        } catch (Exception e) {
            LOG.error("Failed to get ZestGlobalSettings instance", e);
            // Create a default instance if getInstance fails
            this.globalSettings = new ZestGlobalSettings();
        }
        
        try {
            this.projectSettings = ZestProjectSettings.getInstance(project);
        } catch (Exception e) {
            LOG.error("Failed to get ZestProjectSettings instance", e);
            // Create a default instance if getInstance fails
            this.projectSettings = new ZestProjectSettings();
        }
        
        // Ensure globalSettings is never null
        if (this.globalSettings == null) {
            LOG.error("GlobalSettings is still null after initialization, creating default");
            this.globalSettings = new ZestGlobalSettings();
        }
        
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

    public String getUsername() {
        return globalSettings.username;
    }

    public void setUsername(String username) {
        globalSettings.username = username;
    }

    public ZestGlobalSettings getGlobalSettings() {
        return globalSettings;
    }

    // Removed unused RAG and MCP methods
    
    public boolean isProxyServerEnabled() {
        return projectSettings.proxyServerEnabled;
    }
    
    public void setProxyServerEnabled(boolean enabled) {
        projectSettings.proxyServerEnabled = enabled;
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
        // Always return the default template to ensure consistent constraints
        return ZestGlobalSettings.DEFAULT_COMMIT_PROMPT_TEMPLATE;
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

    // Removed unused context and documentation search methods

    public boolean isInlineCompletionEnabled() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for inlineCompletionEnabled");
            return true; // Default to enabled
        }
        return globalSettings.inlineCompletionEnabled;
    }

    public void setInlineCompletionEnabled(boolean enabled) {
        if (globalSettings == null) {
            LOG.error("Cannot set inlineCompletionEnabled - globalSettings is null");
            return;
        }
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

    // RAG-specific configurations for inline completion
    public boolean isInlineCompletionRagEnabled() {
        return globalSettings.inlineCompletionRagEnabled;
    }
    
    public void setInlineCompletionRagEnabled(boolean enabled) {
        globalSettings.inlineCompletionRagEnabled = enabled;
    }
    
    public boolean isAstPatternMatchingEnabled() {
        return globalSettings.astPatternMatchingEnabled;
    }
    
    public void setAstPatternMatchingEnabled(boolean enabled) {
        globalSettings.astPatternMatchingEnabled = enabled;
    }
    
    public int getMaxRagContextSize() {
        return globalSettings.maxRagContextSize;
    }
    
    public void setMaxRagContextSize(int size) {
        globalSettings.maxRagContextSize = size;
    }
    
    public int getEmbeddingCacheSize() {
        return globalSettings.embeddingCacheSize;
    }
    
    public void setEmbeddingCacheSize(int size) {
        globalSettings.embeddingCacheSize = size;
    }
    
    /**
     * Gets the relevance threshold for filtering context
     * @return The minimum relevance score (0.0 to 1.0)
     */
    public double getRelevanceThreshold() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for relevanceThreshold");
            return 0.3;
        }
        return globalSettings.relevanceThreshold;
    }
    
    /**
     * Gets the maximum number of relevant classes to include
     * @return The maximum number of classes
     */
    public int getMaxRelevantClasses() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for maxRelevantClasses");
            return 5;
        }
        return globalSettings.maxRelevantClasses;
    }
    
    /**
     * Gets the BM25 weight for hybrid scoring
     * @return The BM25 weight (0.0 to 1.0)
     */
    public double getBm25Weight() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for bm25Weight");
            return 0.3;
        }
        return globalSettings.bm25Weight;
    }
    
    /**
     * Gets the semantic weight for hybrid scoring
     * @return The semantic weight (0.0 to 1.0)
     */
    public double getSemanticWeight() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for semanticWeight");
            return 0.7;
        }
        return globalSettings.semanticWeight;
    }
    
    /**
     * Checks if relevance caching is enabled
     * @return true if relevance caching is enabled
     */
    public boolean isRelevanceCacheEnabled() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for enableRelevanceCache");
            return true;
        }
        return globalSettings.enableRelevanceCache;
    }
    
    /**
     * Checks if context collector blocking is disabled
     * @return true if all blocking delays should be disabled
     */
    public boolean isContextCollectorBlockingDisabled() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for disableContextCollectorBlocking");
            return false;
        }
        return globalSettings.disableContextCollectorBlocking;
    }
    
    /**
     * Sets whether context collector blocking should be disabled
     * @param disabled true to disable all blocking delays
     */
    public void setContextCollectorBlockingDisabled(boolean disabled) {
        if (globalSettings == null) {
            LOG.error("Cannot set disableContextCollectorBlocking - globalSettings is null");
            return;
        }
        globalSettings.disableContextCollectorBlocking = disabled;
    }
    
    /**
     * Checks if context collector delays should be minimized to 1ms
     * @return true if delays should be reduced to 1ms
     */
    public boolean isContextCollectorDelaysMinimized() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for minimizeContextCollectorDelays");
            return false;
        }
        return globalSettings.minimizeContextCollectorDelays;
    }
    
    /**
     * Sets whether context collector delays should be minimized to 1ms
     * @param minimized true to reduce delays to 1ms
     */
    public void setContextCollectorDelaysMinimized(boolean minimized) {
        if (globalSettings == null) {
            LOG.error("Cannot set minimizeContextCollectorDelays - globalSettings is null");
            return;
        }
        globalSettings.minimizeContextCollectorDelays = minimized;
    }
    
    /**
     * Checks if LLM RAG blocking requests are disabled
     * @return true if all LLM RAG requests should be non-blocking
     */
    public boolean isLLMRAGBlockingDisabled() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for disableLLMRAGBlocking");
            return false;
        }
        return globalSettings.disableLLMRAGBlocking;
    }
    
    /**
     * Sets whether LLM RAG blocking requests should be disabled
     * @param disabled true to make all LLM RAG requests non-blocking
     */
    public void setLLMRAGBlockingDisabled(boolean disabled) {
        if (globalSettings == null) {
            LOG.error("Cannot set disableLLMRAGBlocking - globalSettings is null");
            return;
        }
        globalSettings.disableLLMRAGBlocking = disabled;
    }
    
    /**
     * Checks if LLM RAG timeouts should be minimized
     * @return true if RAG timeouts should be reduced to minimum values
     */
    public boolean isLLMRAGTimeoutsMinimized() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for minimizeLLMRAGTimeouts");
            return false;
        }
        return globalSettings.minimizeLLMRAGTimeouts;
    }
    
    /**
     * Sets whether LLM RAG timeouts should be minimized
     * @param minimized true to reduce RAG timeouts to minimum values
     */
    public void setLLMRAGTimeoutsMinimized(boolean minimized) {
        if (globalSettings == null) {
            LOG.error("Cannot set minimizeLLMRAGTimeouts - globalSettings is null");
            return;
        }
        globalSettings.minimizeLLMRAGTimeouts = minimized;
    }
    
    /**
     * Gets the maximum timeout for RAG requests when timeouts are minimized
     * @return timeout in milliseconds
     */
    public int getRAGMaxTimeoutMs() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for ragMaxTimeoutMs");
            return 100;
        }
        return globalSettings.ragMaxTimeoutMs;
    }
    
    /**
     * Sets the maximum timeout for RAG requests when timeouts are minimized
     * @param timeoutMs timeout in milliseconds
     */
    public void setRAGMaxTimeoutMs(int timeoutMs) {
        if (globalSettings == null) {
            LOG.error("Cannot set ragMaxTimeoutMs - globalSettings is null");
            return;
        }
        globalSettings.ragMaxTimeoutMs = Math.max(1, Math.min(timeoutMs, 5000)); // Clamp between 1ms and 5s
    }
    
    /**
     * Checks if RAG request cancellation is enabled
     * @return true if RAG requests can be cancelled
     */
    public boolean isRAGRequestCancellationEnabled() {
        if (globalSettings == null) {
            LOG.warn("GlobalSettings is null, returning default value for enableRAGRequestCancellation");
            return true;
        }
        return globalSettings.enableRAGRequestCancellation;
    }
    
    /**
     * Sets whether RAG request cancellation is enabled
     * @param enabled true to allow cancelling RAG requests
     */
    public void setRAGRequestCancellationEnabled(boolean enabled) {
        if (globalSettings == null) {
            LOG.error("Cannot set enableRAGRequestCancellation - globalSettings is null");
            return;
        }
        globalSettings.enableRAGRequestCancellation = enabled;
    }

    /**
     * @deprecated Use IntelliJ settings directly
     */
    @Deprecated
    public void loadConfig() {
        // Settings are now loaded automatically by IntelliJ
    }

    /**
     * @deprecated Use IntelliJ settings directly
     */
    @Deprecated
    public void saveConfig() {
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
    
    // .zest folder management methods
    
    /**
     * Ensure .zest folder exists and is properly configured
     */
    public boolean ensureZestFolderExists() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) return false;
            
            Path zestPath = Paths.get(projectPath, ZEST_FOLDER);
            
            // Create directory if it doesn't exist
            if (!Files.exists(zestPath)) {
                Files.createDirectories(zestPath);
                LOG.info("Created .zest directory at: " + zestPath);
            }
            
            // Git ignore management has been removed
            
            // Migrate legacy rules if they exist
            migrateLegacyRules();
            
            return true;
        } catch (Exception e) {
            LOG.error("Failed to ensure .zest folder exists", e);
            return false;
        }
    }
    
    /**
     * Migrate legacy zest_rules.md to .zest/rules.md
     */
    private void migrateLegacyRules() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) return;
            
            Path legacyPath = Paths.get(projectPath, LEGACY_RULES_FILE);
            Path newPath = Paths.get(projectPath, ZEST_FOLDER, RULES_FILE);
            
            if (Files.exists(legacyPath) && !Files.exists(newPath)) {
                // Read legacy content
                String content = new String(Files.readAllBytes(legacyPath));
                
                // Write to new location
                Files.createDirectories(newPath.getParent());
                Files.write(newPath, content.getBytes());
                
                // Add deprecation notice to legacy file
                String deprecationNotice = "# DEPRECATED - This file has been moved!\n\n" +
                    "Your rules have been migrated to: .zest/rules.md\n\n" +
                    "This file is no longer used and can be safely deleted.\n" +
                    "The new location allows better organization of Zest configuration.\n\n" +
                    "---\n\n" + content;
                Files.write(legacyPath, deprecationNotice.getBytes());
                
                LOG.info("Migrated rules from " + LEGACY_RULES_FILE + " to " + ZEST_FOLDER + "/" + RULES_FILE);
            }
        } catch (Exception e) {
            LOG.error("Failed to migrate legacy rules", e);
        }
    }
    
    /**
     * Get path to a .zest configuration file
     */
    public Path getZestConfigFilePath(String filename) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return null;
        return Paths.get(projectPath, ZEST_FOLDER, filename);
    }
    
    /**
     * Read content from a .zest configuration file
     */
    public String readZestConfigFile(String filename) {
        try {
            Path path = getZestConfigFilePath(filename);
            if (path != null && Files.exists(path)) {
                return new String(Files.readAllBytes(path));
            }
        } catch (Exception e) {
            LOG.error("Failed to read " + filename, e);
        }
        return null;
    }
    
    /**
     * Write content to a .zest configuration file
     */
    public boolean writeZestConfigFile(String filename, String content) {
        try {
            Path path = getZestConfigFilePath(filename);
            if (path != null) {
                Files.createDirectories(path.getParent());
                Files.write(path, content.getBytes());
                return true;
            }
        } catch (Exception e) {
            LOG.error("Failed to write " + filename, e);
        }
        return false;
    }
}
