package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.browser.JCEFBrowserManager;
import com.zps.zest.browser.WebBrowserService;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * Configuration manager for loading and accessing plugin settings.
 * Implements a per-project cache to avoid reloading configuration multiple times.
 */
public class ConfigurationManager {
    private static final Logger LOG = Logger.getInstance(ConfigurationManager.class);
    public static final String CODE_EXPERT = "code-expert";
    private static final String CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String CONFIG_FILE_NAME_2 = "zest-plugin.properties";
    private static final String DEFAULT_API_URL = "https://chat.zingplay.com/api/chat/completions";
    private static final String DEFAULT_API_URL_2 = "https://talk.zingplay.com/api/chat/completions";
    private static final String DEFAULT_TEST_WRITING_MODEL = "unit_test_generator";
    private static final String DEFAULT_CODE_MODEL = CODE_EXPERT;
    private static final String DEFAULT_MCP_SERVER_URI = "http://localhost:8080/mcp";
    private static final int DEFAULT_MAX_ITERATIONS = 3;
    private static final int CONNECTION_TIMEOUT = 3000; // 3 seconds

    // Default system prompts
    public static final String DEFAULT_SYSTEM_PROMPT = "You are an assistant that verifies understanding before solving problems effectively.\n" +
            "\n" +
            "CORE APPROACH:\n" +
            "\n" +
            "1. VERIFY FIRST\n" +
            "   - Always ask clarifying questions one by one before tackling complex requests\n" +
            "   - Confirm your understanding explicitly before proceeding\n" +
            "\n" +
            "2. SOLVE METHODICALLY\n" +
            "   - Analyze problems from multiple perspectives\n" +
            "   - Break down complex issues while maintaining holistic awareness\n" +
            "   - Apply appropriate mental models (first principles, systems thinking)\n" +
            "   - Balance creativity with pragmatism in solutions\n" +
            "\n" +
            "3. COMMUNICATE EFFECTIVELY\n" +
            "   - Express ideas clearly and concisely\n" +
            "   - Show empathy by tailoring responses to users' needs\n" +
            "   - Explain reasoning to help users understand solutions\n" +
            "\n" +
            "First verify understanding through questions, then solve problems step-by-step with clear reasoning.\n/no_think\n";
    public static final String DEFAULT_CODE_SYSTEM_PROMPT = "You are an expert programming assistant with a sophisticated problem-solving framework modeled after elite software engineers.\n" +
            "\n" +
            "    CORE CODING METHODOLOGY:\n" +
            "\n" +
            "1. REQUIREMENT ANALYSIS\n" +
            "   - Understand the task completely before writing code\n" +
            "   - Identify explicit requirements and implicit constraints\n" +
            "\n" +
            "2. ARCHITECTURAL THINKING\n" +
            "   - Break complex systems into logical components\n" +
            "   - Consider appropriate design patterns\n" +
            "\n" +
            "3. IMPLEMENTATION STRATEGY\n" +
            "   - Apply appropriate algorithms and data structures\n" +
            "   - Write readable, maintainable code following conventions\n" +
            "\n" +
            "4. DEBUGGING MINDSET\n" +
            "   - Approach errors systematically\n" +
            "   - Look beyond symptoms to underlying problems\n" +
            "\n" +
            "5. CONTINUOUS IMPROVEMENT\n" +
            "   - Identify refactoring and optimization opportunities\n" +
            "   - Consider edge cases and failure modes\n" +
            "\n" +
            "6. KNOWLEDGE INTEGRATION\n" +
            "   - Leverage relevant libraries, frameworks, and tools\n" +
            "   - Apply language-specific best practices\n" +
            "\n" +
            "    CODE REPLACEMENT FORMAT:\n" +
            "When suggesting code changes in a file, you can use the following format to enable automatic code replacement:\n" +
            "\n" +
            "replace_in_file:absolute/path/to/file.ext\n" +
            "```language\n" +
            "code to be replaced\n" +
            "```\n" +
            "```language\n" +
            "replacement code\n" +
            "```\n" +
            "\n" +
            "You can include multiple replace_in_file blocks in your response. The system will automatically batch multiple replacements for the same file, showing a unified diff to the user.\n" +
            "\n" +
            "    TOOL USAGE:\n" +
            "- Check for available tools before suggesting manual operations\n" +
            "\n" +
            "    Ask questions to clarify requirements, explain reasoning, and think step-by-step while maintaining system awareness. Provide clear code examples with explanations./no_think";
    public static final String DEFAULT_COMMIT_PROMPT_TEMPLATE = "Generate a well-structured git commit message based on the changes below.\n\n" +
            "## Changed files:\n" +
            "{FILES_LIST}\n\n" +
            "## File changes:\n" +
            "{DIFFS}\n\n" +
            "## Instructions:\n" +
            "Please follow this structure for the commit message:\n\n" +
            "1. First line: Short summary (50-72 chars) following conventional commit format\n" +
            "   - format: <type>(<scope>): <subject>\n" +
            "   - example: feat(auth): implement OAuth2 login\n\n" +
            "2. Body: Detailed explanation of what changed and why\n" +
            "   - Separated from summary by a blank line\n" +
            "   - Explain what and why, not how\n" +
            "   - Wrap at 72 characters\n\n" +
            "3. Footer (optional):\n" +
            "   - Breaking changes (BREAKING CHANGE: description)\n\n" +
            "Example output:\n" +
            "feat(user-profile): implement password reset functionality\n\n" +
            "Add secure password reset flow with email verification and rate limiting.\n" +
            "This change improves security by requiring email confirmation before\n" +
            "allowing password changes.\n\n" +
            "- Added PasswordResetController with email verification\n" +
            "- Implemented rate limiting to prevent brute force attacks\n" +
            "- Added unit and integration tests\n\n" +
            "BREAKING CHANGE: Password reset API endpoint changed from /reset to /users/reset\n\n" +
            "Please provide ONLY the commit message, no additional explanation, no markdown formatting, no code blocks.";
    
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

    private String apiUrl;
    private String testModel;
    private String codeModel;
    private int maxIterations;
    private String authToken;
    private Project project;
    // Configuration flags
    private boolean ragEnabled = false;
    private String mcpServerUri = DEFAULT_MCP_SERVER_URI;
    private boolean mcpEnabled = false;
    // System prompts
    private String systemPrompt;
    private String codeSystemPrompt;
    private String commitPromptTemplate;
    // Knowledge base ID for code indexing
    private String knowledgeId = null;
    // Inline completion settings
    private boolean inlineCompletionEnabled = false;
    private boolean autoTriggerEnabled = false;
    private boolean backgroundContextEnabled = false;
    
    // Button states
    private boolean contextInjectionEnabled = false;
    private boolean projectIndexEnabled = false;
    
    // Documentation search configuration
    private String docsPath = "docs";
    private boolean docsSearchEnabled = false;

    /**
     * Private constructor to enforce singleton pattern per project.
     *
     * @param project The project to create a configuration manager for
     */
    private ConfigurationManager(Project project) {
        this.project = project;
        loadConfig();
    }

    /**
     * Gets or creates a ConfigurationManager instance for the specified project.
     *
     * @param project The project to get a configuration manager for
     * @return The configuration manager instance for the project
     */
    public static ConfigurationManager getInstance(Project project) {
        return INSTANCES.computeIfAbsent(project, p -> new ConfigurationManager(p));
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

    public boolean isRagEnabled() {
        return ragEnabled;
    }

    public void setRagEnabled(boolean value) {
        this.ragEnabled = value;
    }

    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    public void setMcpEnabled(boolean value) {
        this.mcpEnabled = value;
    }

    public String getOpenWebUIRagEndpoint() {
        return apiUrl;
    }

    /**
     * Gets the URI for the MCP server.
     */
    public String getMcpServerUri() {
        return mcpServerUri;
    }

    /**
     * Sets the URI for the MCP server.
     */
    public void setMcpServerUri(String uri) {
        mcpServerUri = uri;
    }

    /**
     * Loads the configuration from the properties file.
     */
    public void loadConfig() {
        // Default values - start with pinging domains to determine which API URL to use
        String defaultApiUrl = determineDefaultApiUrl();

        apiUrl = defaultApiUrl;
        testModel = DEFAULT_TEST_WRITING_MODEL;
        codeModel = DEFAULT_CODE_MODEL;
        maxIterations = DEFAULT_MAX_ITERATIONS;
        authToken = "";
        mcpServerUri = DEFAULT_MCP_SERVER_URI;
        mcpEnabled = false;
        systemPrompt = DEFAULT_SYSTEM_PROMPT;
        codeSystemPrompt = DEFAULT_CODE_SYSTEM_PROMPT;
        commitPromptTemplate = DEFAULT_COMMIT_PROMPT_TEMPLATE;
        knowledgeId = null;
        contextInjectionEnabled = false;
        projectIndexEnabled = false;
        docsPath = "docs";
        docsSearchEnabled = false;
        inlineCompletionEnabled = false;
        autoTriggerEnabled = false;
        backgroundContextEnabled = false;

        boolean configExists = false;

        // Try to load from config file
        try {
            File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME);
            if (!configFile.exists()) {
                configFile = new File(project.getBasePath(), CONFIG_FILE_NAME_2);
            }
            if (configFile.exists()) {
                configExists = true;
                Properties props = new Properties();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                    props.load(fis);
                }

                apiUrl = props.getProperty("apiUrl", defaultApiUrl);
                testModel = props.getProperty("testModel", DEFAULT_TEST_WRITING_MODEL);
                codeModel = props.getProperty("codeModel", DEFAULT_CODE_MODEL);
                authToken = props.getProperty("authToken", "");
                mcpServerUri = props.getProperty("mcpServerUri", DEFAULT_MCP_SERVER_URI);
                systemPrompt = unescapeFromProperties(props.getProperty("systemPrompt"));
                if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
                    systemPrompt = DEFAULT_SYSTEM_PROMPT;
                }
                
                codeSystemPrompt = unescapeFromProperties(props.getProperty("codeSystemPrompt"));
                if (codeSystemPrompt == null || codeSystemPrompt.trim().isEmpty()) {
                    codeSystemPrompt = DEFAULT_CODE_SYSTEM_PROMPT;
                }
                String loadedTemplate = unescapeFromProperties(props.getProperty("commitPromptTemplate"));
                // Validate and use default if invalid or missing
                if (loadedTemplate == null || loadedTemplate.trim().isEmpty()) {
                    commitPromptTemplate = DEFAULT_COMMIT_PROMPT_TEMPLATE;
                    LOG.info("Commit prompt template was empty, using default");
                    // Save the default template back to config for next time
                    saveConfig();
                } else {
                    // Validate template has required placeholders
                    com.zps.zest.validation.CommitTemplateValidator.ValidationResult validation = 
                        com.zps.zest.validation.CommitTemplateValidator.validate(loadedTemplate);
                    if (validation.isValid) {
                        commitPromptTemplate = loadedTemplate;
                    } else {
                        commitPromptTemplate = DEFAULT_COMMIT_PROMPT_TEMPLATE;
                        LOG.warn("Invalid commit prompt template: " + validation.errorMessage + ". Using default.");
                        // Save the default template back to config
                        saveConfig();
                    }
                }
                knowledgeId = props.getProperty("knowledgeId", null);
                
                // Load button states
                String contextInjectionStr = props.getProperty("contextInjectionEnabled");
                if (contextInjectionStr != null) {
                    contextInjectionEnabled = Boolean.parseBoolean(contextInjectionStr);
                }
                
                String projectIndexStr = props.getProperty("projectIndexEnabled");
                if (projectIndexStr != null) {
                    projectIndexEnabled = Boolean.parseBoolean(projectIndexStr);
                }
                
                // Enforce mutual exclusion on load
                if (contextInjectionEnabled && projectIndexEnabled) {
                    // Context injection takes priority
                    projectIndexEnabled = false;
                }

                String ragEnabledStr = props.getProperty("ragEnabled");
                if (ragEnabledStr != null) {
                    ragEnabled = Boolean.parseBoolean(ragEnabledStr);
                }

                String mcpEnabledStr = props.getProperty("mcpEnabled");
                if (mcpEnabledStr != null) {
                    mcpEnabled = Boolean.parseBoolean(mcpEnabledStr);
                }
                
                // Load documentation search configuration
                String docsPathStr = props.getProperty("docsPath");
                if (docsPathStr != null && !docsPathStr.trim().isEmpty()) {
                    docsPath = docsPathStr.trim();
                }
                
                String docsSearchEnabledStr = props.getProperty("docsSearchEnabled");
                if (docsSearchEnabledStr != null) {
                    docsSearchEnabled = Boolean.parseBoolean(docsSearchEnabledStr);
                }
                
                String inlineCompletionStr = props.getProperty("inlineCompletionEnabled");
                if (inlineCompletionStr != null) {
                    inlineCompletionEnabled = Boolean.parseBoolean(inlineCompletionStr);
                }

                String autoTriggerStr = props.getProperty("autoTriggerEnabled");
                if (autoTriggerStr != null) {
                    autoTriggerEnabled = Boolean.parseBoolean(autoTriggerStr);
                }

                String backgroundContextStr = props.getProperty("backgroundContextEnabled");
                if (backgroundContextStr != null) {
                    backgroundContextEnabled = Boolean.parseBoolean(backgroundContextStr);
                }

                try {
                    maxIterations = Integer.parseInt(props.getProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS)));
                } catch (NumberFormatException e) {
                    maxIterations = DEFAULT_MAX_ITERATIONS;
                }

                // We no longer automatically prompt for auth token during config loading
                // Token will be requested only when needed for API calls
            } else {
                createDefaultConfigFile(defaultApiUrl);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves the current configuration to the config file.
     */
    public void saveConfig() {
        try {
            File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME_2);
            Properties props = new Properties();

            // If the file exists, load the current properties first
            if (configFile.exists()) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                    props.load(fis);
                }
            }

            // Update properties with current values
            props.setProperty("apiUrl", apiUrl);
            props.setProperty("testModel", testModel);
            props.setProperty("codeModel", codeModel);
            props.setProperty("maxIterations", String.valueOf(maxIterations));
            props.setProperty("authToken", authToken);
            props.setProperty("ragEnabled", String.valueOf(ragEnabled));
            props.setProperty("mcpEnabled", String.valueOf(mcpEnabled));
            props.setProperty("mcpServerUri", mcpServerUri);
            props.setProperty("systemPrompt", escapeForProperties(systemPrompt));
            props.setProperty("codeSystemPrompt", escapeForProperties(codeSystemPrompt));
            // Save multi-line template with proper escaping
            props.setProperty("commitPromptTemplate", escapeForProperties(commitPromptTemplate));
            if (knowledgeId != null) {
                props.setProperty("knowledgeId", knowledgeId);
            }
            props.setProperty("contextInjectionEnabled", String.valueOf(contextInjectionEnabled));
            props.setProperty("projectIndexEnabled", String.valueOf(projectIndexEnabled));
            props.setProperty("docsPath", docsPath);
            props.setProperty("docsSearchEnabled", String.valueOf(docsSearchEnabled));
            props.setProperty("inlineCompletionEnabled", String.valueOf(inlineCompletionEnabled));
            props.setProperty("autoTriggerEnabled", String.valueOf(autoTriggerEnabled));
            props.setProperty("backgroundContextEnabled", String.valueOf(backgroundContextEnabled));

            // Save the properties
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Zest Plugin Configuration");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Prompts the user to enter an authentication token and saves it to the configuration file.
     * This method should be called when an API call requires a token and none is available.
     *
     * @param apiUrl The API URL that requires authentication
     * @return The authentication token
     */
    public String promptForAuthToken(String apiUrl) {
        if (authToken != null && !authToken.trim().isEmpty()) {
            return authToken; // Return existing token if available
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

                // Update the token in properties if the user provided one
                if (token != null && !token.trim().isEmpty()) {
                    resultToken[0] = token.trim();

                    // Update the current authToken value
                    this.authToken = token.trim();
                    
                    // Update the browser's auth token immediately
                    try {
                        WebBrowserService browserService = WebBrowserService.getInstance(project);
                        if (browserService != null && browserService.getBrowserPanel() != null) {
                            JCEFBrowserManager browserManager = browserService.getBrowserPanel().getBrowserManager();
                            if (browserManager != null) {
                                browserManager.updateAuthTokenInBrowser(this.authToken);
                            }
                        }
                    } catch (Exception ex) {
                        LOG.warn("Could not update auth token in browser", ex);
                    }

                    // Save the configuration with the new token
                    File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME_2);
                    Properties props = new Properties();

                    // Load existing properties first if the file exists
                    if (configFile.exists()) {
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                            props.load(fis);
                        }
                    }

                    props.setProperty("authToken", authToken.trim());

                    // Save the updated properties
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                        props.store(fos, "Zest Plugin Configuration");
                    }

                    Messages.showInfoMessage(
                            "Configuration updated successfully with your authorization token.",
                            "Configuration Updated"
                    );
                }
            } catch (Exception e) {
                e.printStackTrace();
                Messages.showErrorDialog(
                        "Failed to update configuration with authorization token: " + e.getMessage(),
                        "Configuration Error"
                );
            }
        });

        return resultToken[0];
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

    /**
     * Creates a default configuration file with initial settings.
     *
     * @param defaultApiUrl The default API URL to use
     */
    private void createDefaultConfigFile(String defaultApiUrl) {
        try {
            File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME_2);
            Properties props = new Properties();
            props.setProperty("apiUrl", defaultApiUrl);
            props.setProperty("testModel", DEFAULT_TEST_WRITING_MODEL);
            props.setProperty("codeModel", DEFAULT_CODE_MODEL);
            props.setProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS));
            props.setProperty("authToken", "");  // Empty by default, will be requested when needed
            props.setProperty("ragEnabled", "false");
            props.setProperty("mcpEnabled", "false");
            props.setProperty("mcpServerUri", DEFAULT_MCP_SERVER_URI);
            props.setProperty("systemPrompt", escapeForProperties(DEFAULT_SYSTEM_PROMPT));
            props.setProperty("codeSystemPrompt", escapeForProperties(DEFAULT_CODE_SYSTEM_PROMPT));
            props.setProperty("commitPromptTemplate", escapeForProperties(DEFAULT_COMMIT_PROMPT_TEMPLATE));
            props.setProperty("knowledgeId", ""); // Empty by default
            props.setProperty("contextInjectionEnabled", "false");
            props.setProperty("projectIndexEnabled", "false");
            props.setProperty("docsPath", "docs");
            props.setProperty("docsSearchEnabled", "false");
            props.setProperty("inlineCompletionEnabled", "false");
            props.setProperty("autoTriggerEnabled", "false");
            props.setProperty("backgroundContextEnabled", "false");

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Zest Plugin Configuration");
            }

            Messages.showInfoMessage(
                    "Created default configuration file at: " + configFile.getPath() +
                            "\nAPI token will be requested when needed.",
                    "Configuration Created"
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Getters
    public String getApiUrl() {
        return apiUrl;
    }

    // Setters for additional configuration options
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getTestModel() {
        return testModel;
    }

    public void setTestModel(String testModel) {
        this.testModel = testModel;
    }

    public String getCodeModel() {
        return codeModel;
    }

    public void setCodeModel(String codeModel) {
        this.codeModel = codeModel;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * Gets the current auth token or prompts the user to enter one if none exists.
     *
     * @return The current auth token, or null if the user cancels the prompt
     */
    public String getAuthToken() {
        if (authToken == null || authToken.trim().isEmpty()) {
            // Token is not set, prompt user to provide one
            return promptForAuthToken(apiUrl);
        }
        return authToken;
    }

    /**
     * Gets the current auth token without prompting the user.
     * Use this method when checking if a token exists.
     *
     * @return The current auth token or empty string if none exists
     */
    public String getAuthTokenNoPrompt() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
        
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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getCodeSystemPrompt() {
        return codeSystemPrompt;
    }

    public void setCodeSystemPrompt(String codeSystemPrompt) {
        this.codeSystemPrompt = codeSystemPrompt;
    }

    public String getOpenWebUISystemPrompt() {
        return systemPrompt;
    }

    public String getOpenWebUISystemPromptForCode() {
        return codeSystemPrompt;
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

    public String getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(String knowledgeId) {
        this.knowledgeId = knowledgeId;
    }
    
    public boolean isContextInjectionEnabled() {
        return contextInjectionEnabled;
    }
    
    public void setContextInjectionEnabled(boolean enabled) {
        this.contextInjectionEnabled = enabled;
        // Enforce mutual exclusion
        if (enabled && projectIndexEnabled) {
            projectIndexEnabled = false;
        }
        saveConfig();
    }
    
    public boolean isProjectIndexEnabled() {
        return projectIndexEnabled;
    }
    
    public void setProjectIndexEnabled(boolean enabled) {
        this.projectIndexEnabled = enabled;
        // Enforce mutual exclusion
        if (enabled && contextInjectionEnabled) {
            contextInjectionEnabled = false;
        }
        saveConfig();
    }
    
    public String getCommitPromptTemplate() {
        return commitPromptTemplate;
    }
    
    public void setCommitPromptTemplate(String commitPromptTemplate) {
        // Validate before setting
        com.zps.zest.validation.CommitTemplateValidator.ValidationResult validation = 
            com.zps.zest.validation.CommitTemplateValidator.validate(commitPromptTemplate);
        
        if (!validation.isValid) {
            LOG.error("Invalid commit prompt template: " + validation.errorMessage);
            throw new IllegalArgumentException("Invalid template: " + validation.errorMessage);
        }
        
        this.commitPromptTemplate = commitPromptTemplate;
        saveConfig();
    }
    
    public String getDocsPath() {
        return docsPath;
    }
    
    public void setDocsPath(String docsPath) {
        this.docsPath = docsPath;
        saveConfig();
    }
    
    public boolean isDocsSearchEnabled() {
        return docsSearchEnabled;
    }
    
    public void setDocsSearchEnabled(boolean docsSearchEnabled) {
        this.docsSearchEnabled = docsSearchEnabled;
        saveConfig();
    }
    
    public boolean isInlineCompletionEnabled() {
        return inlineCompletionEnabled;
    }

    public void setInlineCompletionEnabled(boolean enabled) {
        this.inlineCompletionEnabled = enabled;
        saveConfig();
        
        // Notify inline completion service of configuration change
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }

    public boolean isAutoTriggerEnabled() {
        return autoTriggerEnabled;
    }

    public void setAutoTriggerEnabled(boolean enabled) {
        this.autoTriggerEnabled = enabled;
        saveConfig();
        
        // Notify inline completion service of configuration change
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }
    
    public boolean isBackgroundContextEnabled() {
        return backgroundContextEnabled;
    }

    public void setBackgroundContextEnabled(boolean enabled) {
        this.backgroundContextEnabled = enabled;
        saveConfig();
        
        // Notify inline completion service of configuration change
        try {
            com.zps.zest.completion.ZestInlineCompletionService.Companion.notifyConfigurationChanged();
        } catch (Exception e) {
            LOG.warn("Failed to notify inline completion service of configuration change", e);
        }
    }
    
    /**
     * Escapes a string for safe storage in Properties file.
     * Handles newlines and other special characters.
     */
    private String escapeForProperties(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t")
                   .replace("\f", "\\f");
    }
    
    /**
     * Unescapes a string loaded from Properties file.
     */
    private String unescapeFromProperties(String input) {
        if (input == null) return "";
        return input.replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\t", "\t")
                   .replace("\\f", "\f")
                   .replace("\\\\", "\\");
    }
}