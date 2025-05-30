package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager;
import com.zps.zest.browser.utils.UnstagedChangesFormatter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/**
 * Builds prompts for the LLM with emphasis on research-first approach and effective tool usage.
 */
public class OpenWebUIAgentModePromptBuilder {
    private static final Logger LOG = Logger.getInstance(OpenWebUIAgentModePromptBuilder.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Constants for configuration
    private static final int MAX_GIT_RESULTS = 2;
    private static final int MAX_COMMITS_PER_KEYWORD = 3;
    private static final int MAX_CHANGED_FILES_DISPLAY = 3;
    private static final int MAX_CODE_RESULTS = 5;
    private static final int MAX_FUNCTIONS_PER_KEYWORD = 2;
    private static final int MAX_IMPLEMENTATION_LENGTH = 1500;
    private static final int COMMIT_HASH_LENGTH = 8;

    // Base prompt sections - IMPROVED FOR BREVITY AND SINGLE-STEP ENFORCEMENT
    private static final String AGENT_IDENTITY = "You are an AI assistant. Brief, practical, focused responses.";

    private static final String AGENT_MODE_CAPABILITIES = """
            # CAPABILITIES
            - ONE tool per response - NO EXCEPTIONS
            - Brief explanations only
            - Focus on action, not theory
            """;

    private static final String TOOL_USAGE_RULES = """
            # CRITICAL RULES
            1. STATE intent → USE one tool → STOP
            2. Max 2 sentences before tool use
            3. NO multi-step plans in one response
            4. Wait for results before next action
            """;

    private static final String RESPONSE_FORMAT = """
            # RESPONSE FORMAT
            <intent>[1 sentence what you'll do]</intent>
            <tool>[single tool call]</tool>
            
            NEVER include multiple tools or long explanations.
            """;

    private static final String EXAMPLES = """
            # GOOD EXAMPLES
            
            User: "Read the config file"
            You: "Reading config file."
            [tool: read_file with path="config.json"]
            
            User: "List project files"
            You: "Listing directory."
            [tool: list_directory with path="."]
            
            # BAD EXAMPLES (NEVER DO THIS)
            
            User: "Analyze the codebase"
            You: "I'll help you analyze the codebase. First, let me examine the current structure to understand what we're working with, then I'll analyze the code quality and suggest improvements..."
            [WRONG - too verbose]
            
            User: "Find all tests"  
            You: "I'll search for tests and then check their implementations"
            [tool1][tool2]
            [WRONG - multiple tools]
            """;

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
        this.project = validateProject(project);
        this.configManager = ConfigurationManager.getInstance(project);
        this.contextEnhancer = new AgentModeContextEnhancer(project);
    }

    /**
     * Validates the project parameter
     */
    private Project validateProject(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("Project cannot be null");
        }
        if (project.isDisposed()) {
            throw new IllegalStateException("Project has been disposed");
        }
        return project;
    }

    /**
     * Builds a complete prompt with tool usage guidelines, context, history, and user request.
     *
     * @return The complete prompt
     */
    public String buildPrompt() {
        return buildBasePrompt();
    }

    /**
     * Builds the base prompt structure - IMPROVED VERSION
     */
    private String buildBasePrompt() {
        return String.join("\n",
                "<s>",
                AGENT_IDENTITY,
                "",
                AGENT_MODE_CAPABILITIES,
                TOOL_USAGE_RULES,
                RESPONSE_FORMAT,
                buildToolUsage(),
                buildCodeReplacementFormat(),
                buildResponseStyle(),
                EXAMPLES,
                "</s>"
        );
    }

    /**
     * Builds generic tool usage section - SIMPLIFIED
     */
    private String buildToolUsage() {
        return """
            # TOOL USAGE
            - Use provided tools strategically
            - Read before modifying
            - ONE TOOL AT A TIME!
            """;
    }

    /**
     * Builds code replacement format section - SIMPLIFIED
     */
    private String buildCodeReplacementFormat() {
        return """
            # CODE CHANGES
            replace_in_file:path/to/file.ext
            ```language
            old code
            ```
            ```language  
            new code
            ```
            """;
    }

    /**
     * Builds response style section - ULTRA CONCISE
     */
    private String buildResponseStyle() {
        return """
            # STYLE
            - Max 50 words per response
            - Action > explanation
            - One step only
            """;
    }

    /**
     * Builds a complete prompt with tool usage guidelines, context, history, and user request.
     * Now includes enhanced context from Research Agent with unstaged changes.
     *
     * @param userQuery The user's query to be enhanced
     * @param currentFileContext Current file information (optional)
     * @return CompletableFuture with the complete prompt
     */
    public CompletableFuture<String> buildEnhancedPrompt(String userQuery, String currentFileContext) {
        // Validate inputs
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return CompletableFuture.completedFuture(buildPrompt() + "\n\n# USER REQUEST\n[No query provided]");
        }

        // Notify UI that we're collecting context
        notifyUI(UINotificationType.CONTEXT_COLLECTION_STARTED);

        // Get enhanced context asynchronously
        return contextEnhancer.enhancePromptWithContext(userQuery, currentFileContext)
                .thenApply(enhancedContext -> buildPromptWithContext(userQuery, enhancedContext))
                .exceptionally(e -> handleEnhancementError(e, userQuery));
    }

    /**
     * Builds prompt with enhanced context including unstaged changes
     */
    private String buildPromptWithContext(String userQuery, String enhancedContext) {
        StringBuilder prompt = new StringBuilder();

        // Add base prompt
        prompt.append(buildPrompt());

        // Add reminder about single tool usage
        prompt.append("\n\n# REMEMBER: ONE TOOL PER RESPONSE!\n");

        // Add enhanced context
        prompt.append("\n# CONTEXT (reference only)\n");

        try {
            JsonObject context = parseContext(enhancedContext);

            // Add summary first
            String summary = createContextSummary(context);
            if (!summary.isEmpty()) {
                prompt.append("\nSummary: ").append(summary).append("\n");
            }

            // Add unstaged changes first (most recent/relevant)
            appendUnstagedChanges(prompt, context);

            // Then git history
            appendGitHistory(prompt, context);

            // Then related code
            appendRelatedCode(prompt, context);

        } catch (Exception e) {
            LOG.warn("Error formatting enhanced context", e);
        }

        // Add user query with final reminder
        prompt.append("\n\n# USER REQUEST\n");
        prompt.append(userQuery);
        prompt.append("\n\n# YOUR RESPONSE: Brief intent + ONE tool only!");

        // Notify UI that context collection is complete
        notifyUI(UINotificationType.CONTEXT_COLLECTION_COMPLETED);

        return prompt.toString();
    }

    /**
     * Creates a summary of the context
     */
    private String createContextSummary(JsonObject context) {
        StringBuilder summary = new StringBuilder();

        JsonArray unstagedChanges = getJsonArray(context, "unstagedChanges");
        if (unstagedChanges.size() > 0) {
            summary.append(UnstagedChangesFormatter.createUnstagedSummary(unstagedChanges));
            summary.append(" | ");
        }

        JsonArray recentChanges = getJsonArray(context, "recentChanges");
        if (recentChanges.size() > 0) {
            summary.append("Git: ").append(recentChanges.size()).append(" relevant commits | ");
        }

        JsonArray relatedCode = getJsonArray(context, "relatedCode");
        if (relatedCode.size() > 0) {
            int totalMatches = 0;
            for (int i = 0; i < relatedCode.size(); i++) {
                JsonObject result = getJsonObject(relatedCode, i);
                if (result != null && result.has("matches")) {
                    totalMatches += getJsonArray(result, "matches").size();
                }
            }
            summary.append("Code: ").append(totalMatches).append(" matches found");
        }

        return summary.toString();
    }

    /**
     * Appends unstaged changes to the prompt
     */
    private void appendUnstagedChanges(StringBuilder prompt, JsonObject context) {
        JsonArray unstagedChanges = getJsonArray(context, "unstagedChanges");
        if (unstagedChanges.size() > 0) {
            String formatted = UnstagedChangesFormatter.formatUnstagedChanges(unstagedChanges);
            if (!formatted.isEmpty()) {
                prompt.append(formatted);
                prompt.append("\n⚠️ These changes are NOT committed yet - be careful not to overwrite them!\n");
            }
        }
    }

    /**
     * Parses context JSON safely
     */
    private JsonObject parseContext(String enhancedContext) {
        if (enhancedContext == null || enhancedContext.trim().isEmpty()) {
            return new JsonObject();
        }

        try {
            return GSON.fromJson(enhancedContext, JsonObject.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse context JSON", e);
            return new JsonObject();
        }
    }

    /**
     * Appends git history to prompt if available - CONDENSED VERSION
     */
    private void appendGitHistory(StringBuilder prompt, JsonObject context) {
        JsonArray recentChanges = getJsonArray(context, "recentChanges");
        if (recentChanges.size() > 0) {
            prompt.append("\nRecent changes: ");
            prompt.append(formatGitContext(recentChanges));
        }
    }

    /**
     * Appends related code to prompt if available - CONDENSED VERSION
     */
    private void appendRelatedCode(StringBuilder prompt, JsonObject context) {
        JsonArray relatedCode = getJsonArray(context, "relatedCode");
        if (relatedCode.size() > 0) {
            prompt.append("\nRelated code found: ");
            prompt.append(formatCodeContext(relatedCode));
        }
    }

    /**
     * Handles enhancement errors
     */
    private String handleEnhancementError(Throwable e, String userQuery) {
        LOG.error("Error building enhanced prompt", e);
        notifyUI(UINotificationType.CONTEXT_COLLECTION_ERROR);
        // Fall back to basic prompt
        return buildPrompt() + "\n\n# USER REQUEST\n" + userQuery;
    }

    /**
     * Formats git context for the prompt - ULTRA CONDENSED
     */
    private String formatGitContext(JsonArray gitResults) {
        LOG.info("Formatting git context from " + gitResults.size() + " results");
        StringBuilder formatted = new StringBuilder();

        int resultsToShow = Math.min(gitResults.size(), MAX_GIT_RESULTS);

        for (int i = 0; i < resultsToShow; i++) {
            JsonObject result = getJsonObject(gitResults, i);
            if (result == null) continue;

            formatGitResult(formatted, result);
        }

        return formatted.toString();
    }

    /**
     * Formats a single git result - CONDENSED
     */
    private void formatGitResult(StringBuilder formatted, JsonObject result) {
        String keyword = getJsonString(result, "keyword", "unknown");
        JsonArray commits = getJsonArray(result, "commits");

        if (commits.size() == 0) return;

        formatted.append(keyword).append(" (").append(commits.size()).append(" commits); ");
    }

    /**
     * Formats code context for the prompt - ULTRA CONDENSED
     */
    private String formatCodeContext(JsonArray codeResults) {
        LOG.info("Formatting code context from " + codeResults.size() + " results");
        StringBuilder formatted = new StringBuilder();

        int resultsToShow = Math.min(codeResults.size(), 2); // Limit to 2 for brevity

        for (int i = 0; i < resultsToShow; i++) {
            JsonObject result = getJsonObject(codeResults, i);
            if (result == null) continue;

            String keyword = getJsonString(result, "keyword", "unknown");
            JsonArray matches = getJsonArray(result, "matches");
            formatted.append(keyword).append(" (").append(matches.size()).append(" matches); ");
        }

        return formatted.toString();
    }

    /**
     * UI notification types
     */
    private enum UINotificationType {
        CONTEXT_COLLECTION_STARTED("notifyContextCollection"),
        CONTEXT_COLLECTION_COMPLETED("notifyContextComplete"),
        CONTEXT_COLLECTION_ERROR("notifyContextError");

        private final String functionName;

        UINotificationType(String functionName) {
            this.functionName = functionName;
        }
    }

    /**
     * Notifies the UI about various events
     */
    private void notifyUI(UINotificationType type) {
        if (project.isDisposed()) {
            LOG.warn("Cannot notify UI - project is disposed");
            return;
        }

        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService != null) {
                String script = String.format(
                        "if (window.%s) window.%s();",
                        type.functionName,
                        type.functionName
                );
                browserService.executeJavaScript(script);
            }
        } catch (Exception e) {
            LOG.warn("Could not notify UI about " + type.name(), e);
        }
    }

    /**
     * Safe JSON helper methods
     */
    private JsonArray getJsonArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return new JsonArray();
        try {
            JsonElement element = obj.get(key);
            return element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            LOG.warn("Error getting JSON array for key: " + key, e);
            return new JsonArray();
        }
    }

    private JsonObject getJsonObject(JsonArray array, int index) {
        if (array == null || index < 0 || index >= array.size()) return null;
        try {
            JsonElement element = array.get(index);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            LOG.warn("Error getting JSON object at index: " + index, e);
            return null;
        }
    }

    private String getJsonString(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key)) return defaultValue;
        try {
            JsonElement element = obj.get(key);
            return element.isJsonPrimitive() ? element.getAsString() : defaultValue;
        } catch (Exception e) {
            LOG.warn("Error getting JSON string for key: " + key, e);
            return defaultValue;
        }
    }

    private String getJsonString(JsonElement element, String defaultValue) {
        if (element == null) return defaultValue;
        try {
            return element.isJsonPrimitive() ? element.getAsString() : defaultValue;
        } catch (Exception e) {
            LOG.warn("Error getting JSON string from element", e);
            return defaultValue;
        }
    }

    private int getJsonInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key)) return defaultValue;
        try {
            JsonElement element = obj.get(key);
            return element.isJsonPrimitive() ? element.getAsInt() : defaultValue;
        } catch (Exception e) {
            LOG.warn("Error getting JSON int for key: " + key, e);
            return defaultValue;
        }
    }

    /**
     * Disposes resources.
     */
    public void dispose() {
        if (contextEnhancer != null) {
            try {
                contextEnhancer.dispose();
            } catch (Exception e) {
                LOG.error("Error disposing context enhancer", e);
            }
        }
    }
}