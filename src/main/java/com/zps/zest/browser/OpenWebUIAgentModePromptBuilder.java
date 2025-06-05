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

        prompt.append("You are Zest, an IDE assistant.\n\n");

        prompt.append("Always start by confirming exactly what you'll do, then do it.\n");
        prompt.append("Be direct and show code.\n");

        return prompt.toString();
    }
}