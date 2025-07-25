package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;

import java.util.List;

/**
 * Builds prompts for the LLM with emphasis on effective tool usage for Open Web UI.
 */
public class OpenWebUIAgentModePromptBuilder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = Logger.getInstance(OpenWebUIAgentModePromptBuilder.class);

    private final Project project;
    private final ConfigurationManager configManager;
    private List<String> conversationHistory;
    private String explorationResults;

    /**
     * Creates a new OpenWebUIPromptBuilder.
     *
     * @param project The current project
     */
    public OpenWebUIAgentModePromptBuilder(Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
    }

    /**
     * Sets the exploration results to include in the prompt.
     *
     * @param results The exploration results from ImprovedToolCallingAutonomousAgent
     */
    public void setExplorationResults(String results) {
        this.explorationResults = results;
    }

    /**
     * Builds a complete prompt with tool usage guidelines, context, history, and user request.
     *
     * @return The complete prompt
     */
    public String buildPrompt() {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are Zest, an advanced IDE assistant with a powerful software assembly line at your disposal.\n\n");

        prompt.append("AGENT MODE ACTIVATED - You have access to sophisticated code exploration tools:\n");
        prompt.append("• Use the Zest Code Explorer tools to analyze the codebase in real-time\n");
        prompt.append("• Each tool is project-specific - select based on the project context\n");
        prompt.append("• Tools can search files, explore code structure, find implementations, and more\n\n");
        
        prompt.append("TOOL USAGE TIPS:\n");
        prompt.append("• Start with faster tools: list_files_in_directory, read_file, get_project_structure\n");
        prompt.append("• Use search_code sparingly - it performs deep analysis and takes 30-60 seconds\n");
        prompt.append("• search_code is best for finding concepts, patterns, or features by meaning\n");
        prompt.append("• For known file locations, use direct file reading instead of searching\n\n");

        prompt.append("WORKFLOW:\n");
        prompt.append("1. Confirm what you'll do\n");
        prompt.append("2. Use tools to explore and understand the code\n");
        prompt.append("3. Provide concrete solutions with code\n");
        prompt.append("4. Be direct and action-oriented\n\n");

        prompt.append("PROJECT CONTEXT:\n");
        prompt.append("• Current project: " + project.getName() + "\n");
        prompt.append("• Project path: " + project.getBasePath() + "\n");
        
        // Add camelCase project name for tool matching
        String projectNameCamelCase = toCamelCase(project.getName());
        prompt.append("• Project identifier: " + projectNameCamelCase + "\n\n");

        prompt.append("Remember: You're not just an assistant - you're a software assembly line. ");
        prompt.append("Use your tools efficiently to deliver complete, working solutions.\n");

        return prompt.toString();
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
}