package com.zps.zest.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * Handles migration from old properties file to new IntelliJ settings.
 */
public class SettingsMigrator {
    private static final Logger LOG = Logger.getInstance(SettingsMigrator.class);
    private static final String OLD_CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String OLD_CONFIG_FILE_NAME_2 = "zest-plugin.properties";
    
    /**
     * Migrates settings from old properties file to new IntelliJ settings.
     * This should be called once when the plugin starts.
     */
    public static void migrateIfNeeded(Project project) {
        File oldConfigFile = findOldConfigFile(project);
        if (oldConfigFile == null || !oldConfigFile.exists()) {
            return; // No migration needed
        }
        
        try {
            LOG.info("Found old config file, migrating settings: " + oldConfigFile.getName());
            
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(oldConfigFile)) {
                props.load(fis);
            }
            
            // Get settings instances
            ZestGlobalSettings globalSettings = ZestGlobalSettings.getInstance();
            ZestProjectSettings projectSettings = ZestProjectSettings.getInstance(project);
            
            // Migrate global settings
            String apiUrl = props.getProperty("apiUrl");
            if (apiUrl != null && !apiUrl.isEmpty()) {
                globalSettings.apiUrl = apiUrl;
            }
            
            String authToken = props.getProperty("authToken");
            if (authToken != null && !authToken.isEmpty()) {
                globalSettings.authToken = authToken;
            }
            
            String testModel = props.getProperty("testModel");
            if (testModel != null && !testModel.isEmpty()) {
                globalSettings.testModel = testModel;
            }
            
            String codeModel = props.getProperty("codeModel");
            if (codeModel != null && !codeModel.isEmpty()) {
                globalSettings.codeModel = codeModel;
            }
            
            String inlineCompletionStr = props.getProperty("inlineCompletionEnabled");
            if (inlineCompletionStr != null) {
                globalSettings.inlineCompletionEnabled = Boolean.parseBoolean(inlineCompletionStr);
            }
            
            String autoTriggerStr = props.getProperty("autoTriggerEnabled");
            if (autoTriggerStr != null) {
                globalSettings.autoTriggerEnabled = Boolean.parseBoolean(autoTriggerStr);
            }
            
            String backgroundContextStr = props.getProperty("backgroundContextEnabled");
            if (backgroundContextStr != null) {
                globalSettings.backgroundContextEnabled = Boolean.parseBoolean(backgroundContextStr);
            }
            
            // Migrate system prompts
            String systemPrompt = unescapeFromProperties(props.getProperty("systemPrompt"));
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                globalSettings.systemPrompt = systemPrompt;
            }
            
            String codeSystemPrompt = unescapeFromProperties(props.getProperty("codeSystemPrompt"));
            if (codeSystemPrompt != null && !codeSystemPrompt.trim().isEmpty()) {
                globalSettings.codeSystemPrompt = codeSystemPrompt;
            }
            
            String commitPromptTemplate = unescapeFromProperties(props.getProperty("commitPromptTemplate"));
            if (commitPromptTemplate != null && !commitPromptTemplate.trim().isEmpty()) {
                // Validate before migrating
                com.zps.zest.validation.CommitTemplateValidator.ValidationResult validation = 
                    com.zps.zest.validation.CommitTemplateValidator.validate(commitPromptTemplate);
                if (validation.isValid) {
                    globalSettings.commitPromptTemplate = commitPromptTemplate;
                } else {
                    // Use default if invalid
                    globalSettings.commitPromptTemplate = ZestGlobalSettings.DEFAULT_COMMIT_PROMPT_TEMPLATE;
                }
            }
            
            // Migrate project settings
            String knowledgeId = props.getProperty("knowledgeId");
            if (knowledgeId != null && !knowledgeId.isEmpty()) {
                projectSettings.knowledgeId = knowledgeId;
            }
            
            String contextInjectionStr = props.getProperty("contextInjectionEnabled");
            if (contextInjectionStr != null) {
                projectSettings.contextInjectionEnabled = Boolean.parseBoolean(contextInjectionStr);
            }
            
            String projectIndexStr = props.getProperty("projectIndexEnabled");
            if (projectIndexStr != null) {
                projectSettings.projectIndexEnabled = Boolean.parseBoolean(projectIndexStr);
            }
            
            // Enforce mutual exclusion
            if (projectSettings.contextInjectionEnabled && projectSettings.projectIndexEnabled) {
                projectSettings.projectIndexEnabled = false;
            }
            
            String docsPath = props.getProperty("docsPath");
            if (docsPath != null && !docsPath.isEmpty()) {
                projectSettings.docsPath = docsPath;
            }
            
            String docsSearchEnabledStr = props.getProperty("docsSearchEnabled");
            if (docsSearchEnabledStr != null) {
                projectSettings.docsSearchEnabled = Boolean.parseBoolean(docsSearchEnabledStr);
            }
            
            String ragEnabledStr = props.getProperty("ragEnabled");
            if (ragEnabledStr != null) {
                projectSettings.ragEnabled = Boolean.parseBoolean(ragEnabledStr);
            }
            
            String mcpEnabledStr = props.getProperty("mcpEnabled");
            if (mcpEnabledStr != null) {
                projectSettings.mcpEnabled = Boolean.parseBoolean(mcpEnabledStr);
            }
            
            String mcpServerUri = props.getProperty("mcpServerUri");
            if (mcpServerUri != null && !mcpServerUri.isEmpty()) {
                projectSettings.mcpServerUri = mcpServerUri;
            }
            
            String maxIterationsStr = props.getProperty("maxIterations");
            if (maxIterationsStr != null) {
                try {
                    projectSettings.maxIterations = Integer.parseInt(maxIterationsStr);
                } catch (NumberFormatException e) {
                    // Keep default
                }
            }
            
            // Delete old config file
            if (oldConfigFile.delete()) {
                LOG.info("Successfully migrated and deleted old config file: " + oldConfigFile.getName());
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showInfoMessage(project,
                        "Zest Plugin settings have been migrated to IntelliJ's settings system.\n" +
                        "The old configuration file has been removed.",
                        "Settings Migration Complete");
                });
            } else {
                LOG.warn("Failed to delete old config file: " + oldConfigFile.getName());
            }
            
        } catch (Exception e) {
            LOG.error("Failed to migrate settings from old config file", e);
        }
    }
    
    private static File findOldConfigFile(Project project) {
        if (project.getBasePath() == null) {
            return null;
        }
        
        File configFile = new File(project.getBasePath(), OLD_CONFIG_FILE_NAME);
        if (configFile.exists()) {
            return configFile;
        }
        
        configFile = new File(project.getBasePath(), OLD_CONFIG_FILE_NAME_2);
        if (configFile.exists()) {
            return configFile;
        }
        
        return null;
    }
    
    /**
     * Unescapes a string loaded from Properties file.
     */
    private static String unescapeFromProperties(String input) {
        if (input == null) return "";
        return input.replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\t", "\t")
                   .replace("\\f", "\f")
                   .replace("\\\\", "\\");
    }
}
