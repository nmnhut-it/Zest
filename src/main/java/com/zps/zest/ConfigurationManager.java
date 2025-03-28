package com.zps.zest;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages; /**
 * Configuration manager for loading and accessing plugin settings.
 */
public class ConfigurationManager {
    private static final String CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String DEFAULT_API_URL = "https://ollama.zingplay.com/api/generate";
    private static final String DEFAULT_MODEL = "deepseek-r1:32b";
    private static final int DEFAULT_MAX_ITERATIONS = 3;

    private String apiUrl;
    private String model;
    private int maxIterations;
    private String authToken;
    private Project project;

    public ConfigurationManager(Project project) {
        this.project = project;
        loadConfig();
    }

    public void loadConfig() {
        // Default values
        apiUrl = DEFAULT_API_URL;
        model = DEFAULT_MODEL;
        maxIterations = DEFAULT_MAX_ITERATIONS;
        authToken = "";

        // Try to load from config file
        try {
            java.io.File configFile = new java.io.File(project.getBasePath(), CONFIG_FILE_NAME);
            if (configFile.exists()) {
                java.util.Properties props = new java.util.Properties();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                    props.load(fis);
                }

                apiUrl = props.getProperty("apiUrl", DEFAULT_API_URL);
                model = props.getProperty("model", DEFAULT_MODEL);
                authToken = props.getProperty("authToken", "");

                try {
                    maxIterations = Integer.parseInt(props.getProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS)));
                } catch (NumberFormatException e) {
                    maxIterations = DEFAULT_MAX_ITERATIONS;
                }
            } else {
                createDefaultConfigFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createDefaultConfigFile() {
        try {
            java.io.File configFile = new java.io.File(project.getBasePath(), CONFIG_FILE_NAME);
            java.util.Properties props = new java.util.Properties();
            props.setProperty("apiUrl", DEFAULT_API_URL);
            props.setProperty("model", DEFAULT_MODEL);
            props.setProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS));
            props.setProperty("authToken", "");

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Ollama Test Generator Plugin Configuration");
            }

            Messages.showInfoMessage("Created default configuration file at: " + configFile.getPath() +
                            "\nPlease update with your API authorization if needed.",
                    "Configuration Created");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Getters
    public String getApiUrl() { return apiUrl; }
    public String getModel() { return model; }
    public int getMaxIterations() { return maxIterations; }
    public String getAuthToken() { return authToken; }
}
