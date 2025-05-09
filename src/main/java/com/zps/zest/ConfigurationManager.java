package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;

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
    private static final String CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String CONFIG_FILE_NAME_2 = "zest-plugin.properties";
    private static final String DEFAULT_API_URL = "https://chat.zingplay.com/api/chat/completions";
    private static final String DEFAULT_API_URL_2 = "https://talk.zingplay.com/api/chat/completions";
    private static final String DEFAULT_TEST_WRITING_MODEL = "unit_test_generator";
    private static final String DEFAULT_CODE_MODEL = "qwen3-32b";
    private static final String DEFAULT_MCP_SERVER_URI = "http://localhost:8080/mcp";
    private static final int DEFAULT_MAX_ITERATIONS = 3;
    private static final int CONNECTION_TIMEOUT = 3000; // 3 seconds

    // Static cache to store configuration managers by project
    private static final Map<Project, ConfigurationManager> INSTANCES = new ConcurrentHashMap<>();

    // Register project listener to clean up closed projects
    static {
        ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerListener() {
            @Override
            public void projectClosed(Project project) {
                disposeInstance(project);
            }
        });
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

                String ragEnabledStr = props.getProperty("ragEnabled");
                if (ragEnabledStr != null) {
                    ragEnabled = Boolean.parseBoolean(ragEnabledStr);
                }

                String mcpEnabledStr = props.getProperty("mcpEnabled");
                if (mcpEnabledStr != null) {
                    mcpEnabled = Boolean.parseBoolean(mcpEnabledStr);
                }

                try {
                    maxIterations = Integer.parseInt(props.getProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS)));
                } catch (NumberFormatException e) {
                    maxIterations = DEFAULT_MAX_ITERATIONS;
                }

                // If auth token is empty, prompt the user to enter one
                if (authToken == null || authToken.trim().isEmpty()) {
                    promptForAuthToken(configFile, apiUrl);
                }
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
     *
     * @param configFile The configuration file
     * @param apiUrl     The API URL that requires authentication
     * @return The authentication token
     */
    private String promptForAuthToken(File configFile, String apiUrl) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                // Prompt the user to enter an auth token
                String authToken = Messages.showInputDialog(
                        project,
                        "Enter your API authorization token for " + apiUrl,
                        "API Authorization Required",
                        Messages.getQuestionIcon()
                );

                // Update the token in properties if the user provided one
                if (authToken != null && !authToken.trim().isEmpty()) {
                    Properties props = new Properties();

                    // Load existing properties first
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                        props.load(fis);
                    }

                    props.setProperty("authToken", authToken.trim());

                    // Save the updated properties
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                        props.store(fos, "Ollama Test Generator Plugin Configuration");
                    }

                    // Update the current authToken value
                    this.authToken = authToken.trim();

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
        return this.authToken;
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
            props.setProperty("authToken", "");
            props.setProperty("ragEnabled", "false");
            props.setProperty("mcpEnabled", "false");
            props.setProperty("mcpServerUri", DEFAULT_MCP_SERVER_URI);

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Ollama Test Generator Plugin Configuration");
            }

            // Prompt the user to enter an auth token
            String authToken = promptForAuthToken(configFile, defaultApiUrl);

            // Update the token in properties if the user provided one
            if (authToken != null && !authToken.trim().isEmpty()) {
                props.setProperty("authToken", authToken.trim());
                // Save the updated properties
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                    props.store(fos, "Zest Plugin Configuration");
                }
                Messages.showInfoMessage(
                        "Configuration created successfully with your authorization token.",
                        "Configuration Created"
                );
            } else {
                Messages.showInfoMessage(
                        "Created default configuration file at: " + configFile.getPath() +
                                "\nNo authorization token was provided. You may need to update this later.",
                        "Configuration Created"
                );
            }
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

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getOpenWebUISystemPrompt() {
        return "You are an assistant that verifies understanding before solving problems effectively.\n" +
                "\n" +
                "CORE APPROACH:\n" +
                "\n" +
                "1. VERIFY FIRST\n" +
                "   - Always ask clarifying questions before tackling complex requests\n" +
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
                "First verify understanding through questions, then solve problems step-by-step with clear reasoning.\n";
    }
    public String getOpenWebUISystemPromptForCode() {
      return "You are an expert programming assistant with a sophisticated problem-solving framework modeled after elite software engineers. Your approach combines technical expertise with human-like cognitive strategies.\n" +
              "\n" +
              "CORE CODING METHODOLOGY:\n" +
              "\n" +
              "1. REQUIREMENT ANALYSIS\n" +
              "   - Begin by fully understanding the programming task and its context\n" +
              "   - Identify explicit requirements and implicit constraints\n" +
              "   - Consider performance needs, scalability concerns, and maintenance implications\n" +
              "   - Ask clarifying questions when specifications are ambiguous or incomplete\n" +
              "\n" +
              "2. ARCHITECTURAL THINKING\n" +
              "   - Break complex systems into logical components with clear responsibilities\n" +
              "   - Consider appropriate design patterns and architectural approaches\n" +
              "   - Balance immediate implementation with long-term maintainability\n" +
              "   - Evaluate tradeoffs between different technical approaches transparently\n" +
              "\n" +
              "3. IMPLEMENTATION STRATEGY\n" +
              "   - Start with simple working solutions before introducing complexity\n" +
              "   - Apply appropriate algorithms and data structures based on problem characteristics\n" +
              "   - Consider both time and space complexity in your solutions\n" +
              "   - Write code that is readable, maintainable, and follows language conventions\n" +
              "\n" +
              "4. DEBUGGING MINDSET\n" +
              "   - Approach errors systematically rather than through random changes\n" +
              "   - Form and test hypotheses about the root causes of issues\n" +
              "   - Suggest effective debugging strategies and techniques\n" +
              "   - Look beyond symptoms to identify underlying problems\n" +
              "\n" +
              "5. CONTINUOUS IMPROVEMENT\n" +
              "   - Identify opportunities for refactoring and optimization\n" +
              "   - Suggest tests to verify correctness and prevent regressions\n" +
              "   - Consider edge cases and potential failure modes\n" +
              "   - Balance theoretical best practices with practical implementation\n" +
              "\n" +
              "6. KNOWLEDGE INTEGRATION\n" +
              "   - Draw connections to relevant libraries, frameworks, and tools\n" +
              "   - Recognize patterns across different programming domains\n" +
              "   - Adapt solutions from one technology stack to another when appropriate\n" +
              "   - Stay aware of language-specific idioms and best practices\n" +
              "\n" +
              "When helping users, feel free to ask questions to clarify requirements, challenge assumptions when beneficial, and explain your reasoning process. Think step-by-step while maintaining awareness of the entire system. Provide code examples that demonstrate concepts clearly, and explain not just what the code does but why specific approaches were chosen.\n" +
              "\n" +
              "Your goal is to empower users by combining practical solutions with knowledge transfer, helping them become better programmers through each interaction.";
    }
}