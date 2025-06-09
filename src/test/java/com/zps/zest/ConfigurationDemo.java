package com.zps.zest;

import com.intellij.openapi.project.Project;

/**
 * Demo class showing how to use ConfigurationManager programmatically.
 * This demonstrates all available configuration options.
 */
public class ConfigurationDemo {
    
    public static void demonstrateConfiguration(Project project) {
        // Get the configuration instance for the project
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        
        // API Configuration
        System.out.println("=== API Configuration ===");
        System.out.println("API URL: " + config.getApiUrl());
        System.out.println("Auth Token: " + (config.getAuthTokenNoPrompt().isEmpty() ? "Not set" : "****"));
        
        // Model Configuration
        System.out.println("\n=== Model Configuration ===");
        System.out.println("Test Model: " + config.getTestModel());
        System.out.println("Code Model: " + config.getCodeModel());
        System.out.println("Max Iterations: " + config.getMaxIterations());
        
        // Feature Flags
        System.out.println("\n=== Features ===");
        System.out.println("RAG Enabled: " + config.isRagEnabled());
        System.out.println("MCP Enabled: " + config.isMcpEnabled());
        if (config.isMcpEnabled()) {
            System.out.println("MCP Server URI: " + config.getMcpServerUri());
        }
        
        // Context Settings
        System.out.println("\n=== Context Settings ===");
        System.out.println("Context Injection: " + config.isContextInjectionEnabled());
        System.out.println("Project Index: " + config.isProjectIndexEnabled());
        if (config.isProjectIndexEnabled()) {
            System.out.println("Knowledge ID: " + config.getKnowledgeId());
        }
        
        // System Prompts
        System.out.println("\n=== System Prompts ===");
        System.out.println("System Prompt Length: " + config.getSystemPrompt().length() + " chars");
        System.out.println("Code System Prompt Length: " + config.getCodeSystemPrompt().length() + " chars");
        System.out.println("Commit Template Length: " + config.getCommitPromptTemplate().length() + " chars");
        
        // Example: Update a setting
        System.out.println("\n=== Updating Settings ===");
        
        // Change API URL
        config.setApiUrl("https://new-api.example.com/v1/chat");
        
        // Enable RAG
        config.setRagEnabled(true);
        
        // Set a custom commit template
        String customTemplate = "Generate a commit message for these changes:\n" +
                               "Files: {FILES_LIST}\n" +
                               "Diffs: {DIFFS}\n" +
                               "Use conventional commit format.";
        
        try {
            config.setCommitPromptTemplate(customTemplate);
            System.out.println("Commit template updated successfully");
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid template: " + e.getMessage());
        }
        
        // Save all changes
        config.saveConfig();
        System.out.println("Configuration saved to zest-plugin.properties");
        
        // Example: Use configuration in your code
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken(); // This might prompt user if not set
        
        // Make API call with these settings...
    }
    
    /**
     * Example: Check if project is properly configured
     */
    public static boolean isProjectConfigured(Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        
        // Check required settings
        if (config.getApiUrl().isEmpty()) {
            return false;
        }
        
        if (config.getAuthTokenNoPrompt().isEmpty()) {
            return false;
        }
        
        // Check feature-specific requirements
        if (config.isProjectIndexEnabled() && 
            (config.getKnowledgeId() == null || config.getKnowledgeId().isEmpty())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Example: Reset to defaults
     */
    public static void resetToDefaults(Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        
        // Reset prompts to defaults
        config.setSystemPrompt(ConfigurationManager.DEFAULT_SYSTEM_PROMPT);
        config.setCodeSystemPrompt(ConfigurationManager.DEFAULT_CODE_SYSTEM_PROMPT);
        config.setCommitPromptTemplate(ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE);
        
        // Reset features
        config.setRagEnabled(false);
        config.setMcpEnabled(false);
        config.setContextInjectionEnabled(true);
        config.setProjectIndexEnabled(false);
        
        // Save
        config.saveConfig();
    }
}
