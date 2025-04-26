package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Configuration manager for loading and accessing plugin settings.
 */
public class ConfigurationManager {
    private static final String CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String CONFIG_FILE_NAME_2 = "zest-plugin.properties";
    private static final String DEFAULT_API_URL = "https://chat.zingplay.com/api/chat/completions";
    private static final String DEFAULT_API_URL_2 = "https://talk.zingplay.com/api/chat/completions";
    private static final String DEFAULT_TEST_WRITING_MODEL = "unit_test_generator";
    private static final String DEFAULT_CODE_MODEL = "qwen25-coder-custom";
    private static final String DEFAULT_MCP_SERVER_URI = "http://localhost:8080/mcp";
    private static final int DEFAULT_MAX_ITERATIONS = 3;
    private static final int CONNECTION_TIMEOUT = 3000; // 3 seconds

    private static String apiUrl;
    private static String testModel;
    private static String codeModel;
    private static int maxIterations;
    private static String authToken;
    private static Project project;
    // Configuration flags
    private static boolean ragEnabled = false;
    private static String mcpServerUri = DEFAULT_MCP_SERVER_URI;
    private static boolean mcpEnabled = false;

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
    
    public ConfigurationManager(Project project) {
        this.project = project;
        loadConfig();
    }
    
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
            java.io.File configFile = new java.io.File(project.getBasePath(), CONFIG_FILE_NAME);
            if (!configFile.exists()){
                configFile =  new java.io.File(project.getBasePath(), CONFIG_FILE_NAME_2);
            }
            if (configFile.exists()) {
                configExists = true;
                java.util.Properties props = new java.util.Properties();
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
            java.io.File configFile = new java.io.File(project.getBasePath(), CONFIG_FILE_NAME_2);
            java.util.Properties props = new java.util.Properties();
            
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
     * @return
     */
    private String promptForAuthToken(File configFile, String apiUrl) {
        ApplicationManager.getApplication().invokeAndWait(()->{
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
                    java.util.Properties props = new java.util.Properties();

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

    private void createDefaultConfigFile(String defaultApiUrl) {
        try {
            java.io.File configFile = new java.io.File(project.getBasePath(), CONFIG_FILE_NAME_2);
            java.util.Properties props = new java.util.Properties();
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
    public String getApiUrl() { return apiUrl; }
    public String getTestModel() { return testModel; }
    public String getCodeModel() { return codeModel; }
    public int getMaxIterations() { return maxIterations; }
    public String getAuthToken() { return authToken; }
}