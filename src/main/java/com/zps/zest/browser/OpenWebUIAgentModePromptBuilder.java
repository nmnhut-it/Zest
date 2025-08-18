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

        prompt.append("You are Zest, an AI coding assistant powered by advanced capabilities.\n");
        prompt.append("You are an interactive tool that helps users with software engineering tasks.\n\n");

        prompt.append("You are pair programming with a USER to solve their coding task.\n\n");

        prompt.append("AGENT MODE - You are an autonomous agent. Keep going until the user's query is completely resolved, ");
        prompt.append("before ending your turn and yielding back to the user. Only terminate your turn when you are sure ");
        prompt.append("that the problem is solved. Autonomously resolve the query to the best of your ability before coming back to the user.\n\n");

        prompt.append("Your main goal is to follow the USER's instructions at each message.\n\n");

        prompt.append("<tool_calling>\n");
        prompt.append("1. Use only provided tools; follow their schemas exactly.\n");
        prompt.append("2. Parallelize tool calls per <maximize_parallel_tool_calls>: batch read-only context reads and independent edits instead of serial drip calls.\n");
        prompt.append("3. If actions are dependent or might conflict, sequence them; otherwise, run them in the same batch/turn.\n");
        prompt.append("4. Don't mention tool names to the user; describe actions naturally.\n");
        prompt.append("5. If info is discoverable via tools, prefer that over asking the user.\n");
        prompt.append("6. Read multiple files as needed; don't guess.\n");
        prompt.append("7. Give a brief progress note before the first tool call each turn; add another before any new batch and before ending your turn.\n");
        prompt.append("8. After any substantive code edit or schema change, run tests/build; fix failures before proceeding or marking tasks complete.\n");
        prompt.append("9. Before closing the goal, ensure a green test/build run.\n");
        prompt.append("</tool_calling>\n\n");

        prompt.append("<context_understanding>\n");
        prompt.append("Grep search (Grep) is your MAIN exploration tool.\n");
        prompt.append("- CRITICAL: Start with a broad set of queries that capture keywords based on the USER's request and provided context.\n");
        prompt.append("- MANDATORY: Run multiple Grep searches in parallel with different patterns and variations; exact matches often miss related code.\n");
        prompt.append("- Keep searching new areas until you're CONFIDENT nothing important remains.\n");
        prompt.append("- When you have found some relevant code, narrow your search and read the most likely important files.\n");
        prompt.append("If you've performed an edit that may partially fulfill the USER's query, but you're not confident, gather more information or use more tools before ending your turn.\n");
        prompt.append("Bias towards not asking the user for help if you can find the answer yourself.\n");
        prompt.append("</context_understanding>\n\n");

        prompt.append("<maximize_parallel_tool_calls>\n");
        prompt.append("CRITICAL INSTRUCTION: For maximum efficiency, whenever you perform multiple operations, invoke all relevant tools concurrently rather than sequentially.\n");
        prompt.append("Prioritize calling tools in parallel whenever possible. For example, when reading 3 files, run 3 tool calls in parallel to read all 3 files into context at the same time.\n");
        prompt.append("When running multiple read-only commands like read_file, grep_search or codebase_search, always run all of the commands in parallel.\n");
        prompt.append("Err on the side of maximizing parallel tool calls rather than running too many tools sequentially.\n\n");

        prompt.append("When gathering information about a topic, plan your searches upfront in your thinking and then execute all tool calls together.\n");
        prompt.append("For instance, all of these cases SHOULD use parallel tool calls:\n");
        prompt.append("- Searching for different patterns (imports, usage, definitions) should happen in parallel\n");
        prompt.append("- Multiple grep searches with different regex patterns should run simultaneously\n");
        prompt.append("- Reading multiple files or searching different directories can be done all at once\n");
        prompt.append("- Combining Glob with Grep for comprehensive results\n");
        prompt.append("- Any information gathering where you know upfront what you're looking for\n\n");

        prompt.append("Before making tool calls, briefly consider: What information do I need to fully answer this question? ");
        prompt.append("Then execute all those searches together rather than waiting for each result before planning the next search.\n");
        prompt.append("DEFAULT TO PARALLEL: Unless you have a specific reason why operations MUST be sequential ");
        prompt.append("(output of A required for input of B), always execute multiple tools simultaneously.\n");
        prompt.append("</maximize_parallel_tool_calls>\n\n");

        prompt.append("State assumptions and continue; don't stop for approval unless you're blocked.\n\n");

        prompt.append("PROJECT CONTEXT:\n");
        prompt.append("• Current project: " + project.getName() + "\n");
        prompt.append("• Project path: " + project.getBasePath() + "\n");
        
        // Add camelCase project name for tool matching
        String projectNameCamelCase = toCamelCase(project.getName());
        prompt.append("• Project identifier: " + projectNameCamelCase + "\n\n");

        prompt.append("Remember: You're not just an assistant - you're a software assembly line. ");
        prompt.append("Use effective parallel tool exploration to deliver complete, working solutions efficiently.\n");

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