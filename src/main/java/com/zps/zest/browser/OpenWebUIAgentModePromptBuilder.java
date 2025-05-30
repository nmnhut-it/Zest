package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Builds prompts for the LLM with emphasis on effective tool usage for Open Web UI.
 */
public class OpenWebUIAgentModePromptBuilder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = Logger.getInstance(OpenWebUIAgentModePromptBuilder.class);

    private final Project project;
    private final ConfigurationManager configManager;
    private final AgentModeContextEnhancer contextEnhancer;
    private List<String> conversationHistory;

    /**
     * Creates a new OpenWebUIPromptBuilder.
     *
     * @param project The current project
     */
    public OpenWebUIAgentModePromptBuilder(Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
        this.contextEnhancer = new AgentModeContextEnhancer(project);
    }

    /**
     * Builds a complete prompt with tool usage guidelines, context, history, and user request.
     *
     * @return The complete prompt
     */
    public String buildPrompt() {
        return "<s>\n" +
                "You are Zest, Zingplay's IDE assistant. You help programmers write better code with concise, practical solutions. " +
                "You're professional, intellectual, and speak concisely with a playful tone.\n" +
                "\n" +
                "# AGENT MODE CAPABILITIES\n" +
                "As a coding agent, you:\n" +
                "- Autonomously use tools to understand, analyze, and modify code directly in the IDE\n" +
                "- Follow a structured workflow: Clarify → Collect → Analyze → Implement → Verify\n" +
                "- Strategically select appropriate tools for each task stage\n" +
                "- Always examine code before suggesting or implementing changes\n" +
                "- Break complex tasks into executable tool operations\n" +
                "\n" +
                "# TOOL USAGE RULES\n" +
                "- EXPLAIN your reasoning and ASK permission before using tools\n" +
                "- PERFORM ONLY ONE tool call per response\n" +
                "- Wait for results before proceeding to next tool\n" +
                "- Prioritize understanding over modification\n" +
                "- Use tools to read current files/directories to better understand context\n" +
                "\n" +
                "# JETBRAINS TOOL USAGE\n" +
                "To capture code context efficiently:\n" +
                "1. Get current file with tool_get_open_in_editor_file_text_post\n" +
                "2. Understand project structure with tool_list_directory_tree_in_folder_post\n" +
                "3. Find related files with tool_find_files_by_name_substring_post\n" +
                "4. Search for usages with tool_search_in_files_content_post\n" +
                "5. Check for errors with tool_get_current_file_errors_post\n" +
                "6. Examine recent changes with tool_get_project_vcs_status_post\n" +
                "7. Get related file content with tool_get_file_text_by_path_post\n" +
                "8. Explore dependencies with tool_get_project_dependencies_post\n" +
                "\n" +
                "# CODE REPLACEMENT FORMAT\n" +
                "When you want to suggest code changes in a file, use the following format to enable automatic code replacement:\n" +
                "\n" +
                "replace_in_file:absolute/path/to/file.ext\n" +
                "```language\n" +
                "old code to be replaced\n" +
                "```\n" +
                "```language\n" +
                "new code\n" +
                "```\n" +
                "\n" +
                "You can include multiple replace_in_file blocks in your response. The system will automatically batch multiple replacements for the same file, showing a unified diff to the user. This is useful when suggesting related changes across a file.\n" +
                "\n" +
                "You actively use this format instead of providing plain code blocks.\n" +
                "\n" +
                "# RESPONSE STYLE\n" +
                "- Concise, focused answers addressing specific requests\n" +
                "- Proper code blocks with syntax highlighting\n" +
                "- Summarize key findings from large tool outputs\n" +
                "- Step-by-step explanations for complex operations\n" +
                "</s>";
    }

    /**
     * Builds a complete prompt with tool usage guidelines, context, history, and user request.
     * Now includes enhanced context from Research Agent.
     *
     * @param userQuery The user's query to be enhanced
     * @param currentFileContext Current file information (optional)
     * @return CompletableFuture with the complete prompt
     */
    public CompletableFuture<String> buildEnhancedPrompt(String userQuery, String currentFileContext) {
        // Notify UI that we're collecting context
        notifyUIContextCollection();
        
        // Get enhanced context asynchronously
        return contextEnhancer.enhancePromptWithContext(userQuery, currentFileContext)
            .thenApply(enhancedContext -> {
                StringBuilder prompt = new StringBuilder();
                
                // Add base prompt
                prompt.append(buildPrompt());
                
                // Add enhanced context
                prompt.append("\n\n# PROJECT CONTEXT\n");
                prompt.append("The following relevant code was found in the project:\n");
                
                try {
                    JsonObject context = GSON.fromJson(enhancedContext, JsonObject.class);
                    
                    // Add git history context
                    if (context.has("recentChanges") && 
                        context.getAsJsonArray("recentChanges").size() > 0) {
                        prompt.append("\n## Recent Changes\n");
                        prompt.append("Recent commits related to your query:\n");
                        prompt.append(formatGitContext(context.getAsJsonArray("recentChanges")));
                    }
                    
                    // Add related code context
                    if (context.has("relatedCode") && 
                        context.getAsJsonArray("relatedCode").size() > 0) {
                        prompt.append("\n## Related Code in Project\n");
                        prompt.append("Existing code that might be relevant:\n");
                        prompt.append(formatCodeContext(context.getAsJsonArray("relatedCode")));
                    }
                } catch (Exception e) {
                    LOG.warn("Error formatting enhanced context", e);
                }
                
                // Add user query
                prompt.append("\n\n# USER REQUEST\n");
                prompt.append(userQuery);
                
                // Notify UI that context collection is complete
                notifyUIContextComplete();
                
                return prompt.toString();
            })
            .exceptionally(e -> {
                LOG.error("Error building enhanced prompt", e);
                notifyUIContextError();
                // Fall back to basic prompt
                return buildPrompt() + "\n\n# USER REQUEST\n" + userQuery;
            });
    }
    
    /**
     * Formats git context for the prompt.
     */
    private String formatGitContext(com.google.gson.JsonArray gitResults) {
        LOG.info("Formatting git context from " + gitResults.size() + " results");
        StringBuilder formatted = new StringBuilder();
        
        for (int i = 0; i < Math.min(gitResults.size(), 2); i++) {
            JsonObject result = gitResults.get(i).getAsJsonObject();
            String keyword = result.get("keyword").getAsString();
            JsonArray commits = result.getAsJsonArray("commits");
            
            formatted.append("- Keyword '").append(keyword)
                    .append("' found in ").append(commits.size())
                    .append(" recent commits\n");
            
            // Log first commit for debugging
            if (commits.size() > 0) {
                JsonObject firstCommit = commits.get(0).getAsJsonObject();
                LOG.info("  First commit: " + firstCommit.get("message").getAsString());
            }
        }
        
        return formatted.toString();
    }
    
    /**
     * Formats code context for the prompt.
     */
    private String formatCodeContext(com.google.gson.JsonArray codeResults) {
        LOG.info("Formatting code context from " + codeResults.size() + " results");
        StringBuilder formatted = new StringBuilder();
        
        for (int i = 0; i < Math.min(codeResults.size(), 5); i++) {
            JsonObject result = codeResults.get(i).getAsJsonObject();
            String type = result.get("type").getAsString();
            String keyword = result.get("keyword").getAsString();
            JsonArray matches = result.getAsJsonArray("matches");
            
            if (type.equals("function")) {
                formatted.append("- Function '").append(keyword)
                        .append("' found in ").append(matches.size())
                        .append(" files (use as reference)\n");
                
                // Log first match for debugging
                if (matches.size() > 0) {
                    JsonObject firstMatch = matches.get(0).getAsJsonObject();
                    LOG.info("  First match in: " + firstMatch.get("file").getAsString());
                }
            } else {
                formatted.append("- Code pattern '").append(keyword)
                        .append("' found in ").append(matches.size())
                        .append(" locations\n");
            }
        }
        
        return formatted.toString();
    }
    
    /**
     * Notifies the UI that context collection has started.
     */
    private void notifyUIContextCollection() {
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService != null ) {
                browserService.executeJavaScript(
                    "if (window.notifyContextCollection) window.notifyContextCollection();"
                );
            }
        } catch (Exception e) {
            LOG.warn("Could not notify UI about context collection", e);
        }
    }
    
    /**
     * Notifies the UI that context collection is complete.
     */
    private void notifyUIContextComplete() {
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService != null  ) {
                browserService.executeJavaScript(
                    "if (window.notifyContextComplete) window.notifyContextComplete();"
                );
            }
        } catch (Exception e) {
            LOG.warn("Could not notify UI about context completion", e);
        }
    }
    
    /**
     * Notifies the UI that context collection had an error.
     */
    private void notifyUIContextError() {
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService != null ) {
                browserService. executeJavaScript(
                    "if (window.notifyContextError) window.notifyContextError();"
                );
            }
        } catch (Exception e) {
            LOG.warn("Could not notify UI about context error", e);
        }
    }

    /**
     * Disposes resources.
     */
    public void dispose() {
        if (contextEnhancer != null) {
            contextEnhancer.dispose();
        }
    }

    /**
     * Adds system instructions to the prompt.
     */
    private void addSystemInstructions(StringBuilder prompt) {
        prompt.append("<s>\n");
        prompt.append("You are Zest, Zingplay's IDE assistant. You help programmers write better code with concise, practical solutions. You strictly follow instructions while being professional and highly intellectual.")
                .append("You speak in concise and playful manner.\n\n");

        prompt.append("# WORKFLOW\n");
        prompt.append("Follow these steps in sequence. EXPLAIN AND ASK BEFORE USING. PERFORM AT MOST ONE TOOL CALL IN A RESPONSE.\n");
        prompt.append("1. CLARIFY: Ask questions to understand requirements when needed\n");
        prompt.append("2. COLLECT: Use appropriate tools to gather necessary context and code\n");
        prompt.append("3. ANALYZE: Identify improvements and solutions based on collected information\n");
        prompt.append("4. IMPLEMENT: Apply changes with modification tools\n");
        prompt.append("5. VERIFY: Test changes and fix any issues\n\n");

        prompt.append("# APPROACH\n");
        prompt.append("- UNDERSTAND: Examine code thoroughly before suggesting changes. IMPORTANT: You can use tools to read current file or directory to further understand what is needed\n");
        prompt.append("- EXPLAIN: Provide clear, concise analysis that focuses on key issues\n");
        prompt.append("- IMPLEMENT: Make targeted improvements rather than rewriting everything\n");
        prompt.append("- VERIFY: Ensure quality and functionality by checking for unintended side effects\n");

        prompt.append("RESPONSE GUIDELINES:\n");
        prompt.append("- Keep responses concise and focused on the user's specific request\n");
        prompt.append("- Use code blocks with proper syntax highlighting when sharing code\n");
        prompt.append("- When tools return large outputs, summarize the key findings\n");
        prompt.append("- Provide step-by-step explanations for complex operations\n\n");

        prompt.append("Your primary advantage is your ability to use tools strategically to examine and modify code directly in the IDE.\n");
        prompt.append("</s>\n\n");
    }

    /**
     * Adds tool usage guidelines to the prompt.
     */
    private void addToolUsageGuidelines(StringBuilder prompt) {
        prompt.append("# TOOL USAGE GUIDELINES\n\n");
        prompt.append("Tools will be provided to you through the system. Your job is to understand tool capabilities and use them wisely. DO NOT CREATE OR DEFINE TOOLS YOURSELF.\n\n");

        prompt.append("## TOOL INFORMATION EXTRACTION\n");
        prompt.append("- When presented with tool information, carefully analyze:\n");
        prompt.append("  - The tool's name and purpose\n");
        prompt.append("  - Required parameters and their expected formats\n");
        prompt.append("  - Expected output format and how to interpret it\n");
        prompt.append("  - Any usage limitations or constraints\n\n");

        prompt.append("## TOOL SELECTION STRATEGY\n");
        prompt.append("- Choose tools based on the specific task at hand, not just familiarity\n");
        prompt.append("- For code understanding tasks: prioritize reading and analyzing tools\n");
        prompt.append("- For code modification tasks: always examine code before modifying it\n");
        prompt.append("- For problem diagnosis: use analysis and search tools\n");
        prompt.append("- For navigation: use structure and reference tools\n\n");

        prompt.append("## EFFECTIVE TOOL USAGE PATTERNS\n");
        prompt.append("- ALWAYS read and understand code before modifying it\n");
        prompt.append("- Use navigation tools to understand relationships between components\n");
        prompt.append("- When fixing bugs, first understand the problem with analysis tools\n");
        prompt.append("- When implementing features, first understand the context\n");
        prompt.append("- Break down complex tasks into a sequence of tool operations\n");
        prompt.append("- ONLY call ONE tool per message\n\n");
        prompt.append("- ASK and EXPLAIN before using tools\n\n");


        prompt.append("## COMMON TOOL USAGE PITFALLS TO AVOID\n");
        prompt.append("- DON'T modify code without first reading and understanding it\n");
        prompt.append("- DON'T assume the structure or content of files you haven't examined\n");
        prompt.append("- DON'T call multiple tools at once - wait for each result before proceeding\n");
        prompt.append("- DON'T forget to escape special characters in JSON parameters\n");
        prompt.append("- DON'T use tools that aren't provided by the system\n\n");

        prompt.append("## TOOL CATEGORIES TO EXPECT\n");
        prompt.append("You will be provided with tools that generally fall into these categories:\n");
        prompt.append("- File system operations (reading, writing, listing)\n");
        prompt.append("- Code analysis and inspection\n");
        prompt.append("- Project navigation and structure\n");
        prompt.append("- Code modification and refactoring\n");
        prompt.append("- Interactive dialog with the user\n\n");

        prompt.append("Learn and adapt to the specific tools provided by examining their documentation.\n\n");
    }

}