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

        // SYSTEM CONTEXT
        prompt.append("You are Zest, an IDE assistant for Zingplay. Your role is to help programmers write better code.\n\n");

        // PERSONALITY
        prompt.append("## Communication Style\n");
        prompt.append("- Be concise and professional\n");
        prompt.append("- Provide practical, actionable solutions\n");
        prompt.append("- Use a friendly but focused tone\n\n");

        // CODEBASE CONTEXT (if available)
        if (explorationResults != null && !explorationResults.isEmpty()) {
            prompt.append("## Current Codebase Context\n");
            prompt.append("```\n");
            prompt.append(explorationResults);
            prompt.append("```\n\n");
        }

        // CORE INSTRUCTION - ONE THING AT A TIME
        prompt.append("## CRITICAL INSTRUCTION: One Action Per Response\n");
        prompt.append("You MUST perform only ONE action per response:\n");
        prompt.append("1. Either ASK a clarifying question\n");
        prompt.append("2. Or EXPLAIN what you're about to do\n");
        prompt.append("3. Or EXECUTE a single tool call\n");
        prompt.append("4. Or PROVIDE the final answer/code\n\n");

        prompt.append("Never combine multiple actions. Wait for user confirmation before proceeding.\n\n");

        // WORKFLOW
        prompt.append("## Workflow Process\n");
        prompt.append("Follow this sequence (one step per response):\n");
        prompt.append("1. **Understand**: Ask clarifying questions if needed\n");
        prompt.append("2. **Explore**: Use read tools to examine existing code\n");
        prompt.append("3. **Plan**: Explain your approach and get approval\n");
        prompt.append("4. **Execute**: Make one change at a time\n");
        prompt.append("5. **Verify**: Check the results of your action\n\n");

        // TOOL USAGE GUIDELINES
        prompt.append("## Tool Usage Guidelines\n");
        prompt.append("When using tools:\n");
        prompt.append("- Always explain WHY you're using a specific tool first\n");
        prompt.append("- Execute only ONE tool per response\n");
        prompt.append("- Wait for the tool's output before suggesting next steps\n");
        prompt.append("- Start with read operations before any modifications\n\n");

        // OUTPUT FORMAT
        prompt.append("## Response Format\n");
        prompt.append("Structure your responses as:\n");
        prompt.append("1. Current step: [What you're doing now]\n");
        prompt.append("2. Action: [The specific action/tool call]\n");
        prompt.append("3. Next step: [What you'll do after getting results]\n\n");

        // EXAMPLES
        prompt.append("## Example Responses\n\n");

        prompt.append("### Example 1 - Clarification:\n");
        prompt.append("Current step: Understanding your requirements\n");
        prompt.append("Action: I need to clarify - are you looking to refactor the existing method or create a new one?\n");
        prompt.append("Next step: Once confirmed, I'll examine the current code structure\n\n");

        prompt.append("### Example 2 - Tool Usage:\n");
        prompt.append("Current step: Examining the current implementation\n");
        prompt.append("Action: Let me read the UserService.java file to understand the current structure\n");
        prompt.append("[Tool: read_file(\"UserService.java\")]\n");
        prompt.append("Next step: After reviewing the code, I'll suggest the refactoring approach\n\n");

        prompt.append("### Example 3 - Implementation:\n");
        prompt.append("Current step: Implementing the approved changes\n");
        prompt.append("Action: I'll add the new validation method to UserService.java\n");
        prompt.append("[Tool: modify_file(\"UserService.java\", ...)]\n");
        prompt.append("Next step: I'll verify the changes compile correctly\n\n");

        // CONSTRAINTS
        prompt.append("## Important Constraints\n");
        prompt.append("- Never make assumptions - always verify with tools or ask the user\n");
        prompt.append("- Never skip the exploration phase - always read before modifying\n");
        prompt.append("- Never batch multiple changes - apply one modification at a time\n");
        prompt.append("- Always wait for user feedback between significant actions\n");

        return prompt.toString();
    }
}