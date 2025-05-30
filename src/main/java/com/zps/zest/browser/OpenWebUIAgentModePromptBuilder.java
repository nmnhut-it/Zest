package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/**
 * Builds prompts for the LLM with emphasis on effective tool usage for Open Web UI.
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

    // Base prompt sections
    private static final String AGENT_IDENTITY = "You are Zest, Zingplay's IDE assistant. You help programmers write better code with concise, practical solutions. " +
            "You're professional, intellectual, and speak concisely with a playful tone.";

    private static final String AGENT_MODE_CAPABILITIES = """
            # AGENT MODE CAPABILITIES
            As a coding agent, you:
            - Autonomously use tools to understand, analyze, and modify code directly in the IDE
            - Follow a structured workflow: Clarify → Collect → Analyze → Implement → Verify
            - Strategically select appropriate tools for each task stage
            - Always examine code before suggesting or implementing changes
            - Break complex tasks into executable tool operations
            """;

    private static final String TOOL_USAGE_RULES = """
            # TOOL USAGE RULES
            - EXPLAIN your reasoning and ASK permission before using tools
            - PERFORM ONLY ONE tool call per response
            - Wait for results before proceeding to next tool
            - Prioritize understanding over modification
            - Use tools to read current files/directories to better understand context
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
     * Builds the base prompt structure
     */
    private String buildBasePrompt() {
        return String.join("\n",
                "<s>",
                AGENT_IDENTITY,
                "",
                AGENT_MODE_CAPABILITIES,
                TOOL_USAGE_RULES,
                buildJetBrainsToolUsage(),
                buildCodeReplacementFormat(),
                buildResponseStyle(),
                "</s>"
        );
    }

    /**
     * Builds JetBrains tool usage section
     */
    private String buildJetBrainsToolUsage() {
        return """
            # JETBRAINS TOOL USAGE
            To capture code context efficiently:
            1. Get current file with tool_get_open_in_editor_file_text_post
            2. Understand project structure with tool_list_directory_tree_in_folder_post
            3. Find related files with tool_find_files_by_name_substring_post
            4. Search for usages with tool_search_in_files_content_post
            5. Check for errors with tool_get_current_file_errors_post
            6. Examine recent changes with tool_get_project_vcs_status_post
            7. Get related file content with tool_get_file_text_by_path_post
            8. Explore dependencies with tool_get_project_dependencies_post
            """;
    }

    /**
     * Builds code replacement format section
     */
    private String buildCodeReplacementFormat() {
        return """
            # CODE REPLACEMENT FORMAT
            When you want to suggest code changes in a file, use the following format to enable automatic code replacement:
            
            replace_in_file:absolute/path/to/file.ext
            ```language
            old code to be replaced
            ```
            ```language
            new code
            ```
            
            You can include multiple replace_in_file blocks in your response. The system will automatically batch multiple replacements for the same file, showing a unified diff to the user. This is useful when suggesting related changes across a file.
            
            You actively use this format instead of providing plain code blocks.
            """;
    }

    /**
     * Builds response style section
     */
    private String buildResponseStyle() {
        return """
            # RESPONSE STYLE
            - Concise, focused answers addressing specific requests
            - Proper code blocks with syntax highlighting
            - Summarize key findings from large tool outputs
            - Step-by-step explanations for complex operations
            """;
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
     * Builds prompt with enhanced context
     */
    private String buildPromptWithContext(String userQuery, String enhancedContext) {
        StringBuilder prompt = new StringBuilder();

        // Add base prompt
        prompt.append(buildPrompt());

        // Add enhanced context
        prompt.append("\n\n# PROJECT CONTEXT\n");
        prompt.append("The following relevant code was found in the project:\n");

        try {
            JsonObject context = parseContext(enhancedContext);
            appendGitHistory(prompt, context);
            appendRelatedCode(prompt, context);
        } catch (Exception e) {
            LOG.warn("Error formatting enhanced context", e);
            prompt.append("\n[Context formatting error - using raw context]\n");
        }

        // Add user query
        prompt.append("\n\n# USER REQUEST\n");
        prompt.append(userQuery);

        // Notify UI that context collection is complete
        notifyUI(UINotificationType.CONTEXT_COLLECTION_COMPLETED);

        return prompt.toString();
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
     * Appends git history to prompt if available
     */
    private void appendGitHistory(StringBuilder prompt, JsonObject context) {
        JsonArray recentChanges = getJsonArray(context, "recentChanges");
        if (recentChanges.size() > 0) {
            prompt.append("\n## Recent Changes\n");
            prompt.append("Recent commits related to your query:\n");
            prompt.append(formatGitContext(recentChanges));
        }
    }

    /**
     * Appends related code to prompt if available
     */
    private void appendRelatedCode(StringBuilder prompt, JsonObject context) {
        JsonArray relatedCode = getJsonArray(context, "relatedCode");
        if (relatedCode.size() > 0) {
            prompt.append("\n## Related Code in Project\n");
            prompt.append("Existing code that might be relevant:\n");
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
     * Formats git context for the prompt.
     * Now includes commit messages and file changes.
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
     * Formats a single git result
     */
    private void formatGitResult(StringBuilder formatted, JsonObject result) {
        String keyword = getJsonString(result, "keyword", "unknown");
        JsonArray commits = getJsonArray(result, "commits");

        if (commits.size() == 0) return;

        formatted.append("\n### Recent commits for '").append(keyword).append("':\n");

        int commitsToShow = Math.min(commits.size(), MAX_COMMITS_PER_KEYWORD);
        for (int j = 0; j < commitsToShow; j++) {
            JsonObject commit = getJsonObject(commits, j);
            if (commit != null) {
                formatCommit(formatted, commit);
            }
        }

        if (commits.size() > MAX_COMMITS_PER_KEYWORD) {
            formatted.append("... and ").append(commits.size() - MAX_COMMITS_PER_KEYWORD).append(" more commits\n");
        }
    }

    /**
     * Formats a single commit
     */
    private void formatCommit(StringBuilder formatted, JsonObject commit) {
        String hash = getJsonString(commit, "hash", "unknown");
        String shortHash = hash.length() > COMMIT_HASH_LENGTH ? hash.substring(0, COMMIT_HASH_LENGTH) : hash;
        String message = getJsonString(commit, "message", "No message");
        String author = getJsonString(commit, "author", "Unknown");

        formatted.append("- [").append(shortHash).append("] ").append(message);
        if (!author.equals("Unknown")) {
            formatted.append(" (by ").append(author).append(")");
        }
        formatted.append("\n");

        formatChangedFiles(formatted, commit);
    }

    /**
     * Formats changed files in a commit
     */
    private void formatChangedFiles(StringBuilder formatted, JsonObject commit) {
        JsonArray files = getJsonArray(commit, "files");
        if (files.size() == 0) return;

        formatted.append("  Files: ");
        int filesToShow = Math.min(files.size(), MAX_CHANGED_FILES_DISPLAY);

        for (int k = 0; k < filesToShow; k++) {
            if (k > 0) formatted.append(", ");
            formatted.append(getJsonString(files.get(k), ""));
        }

        if (files.size() > MAX_CHANGED_FILES_DISPLAY) {
            formatted.append(" and ").append(files.size() - MAX_CHANGED_FILES_DISPLAY).append(" more");
        }
        formatted.append("\n");
    }

    /**
     * Formats code context for the prompt.
     * Now includes full function implementations when available.
     */
    private String formatCodeContext(JsonArray codeResults) {
        LOG.info("Formatting code context from " + codeResults.size() + " results");
        StringBuilder formatted = new StringBuilder();

        int resultsToShow = Math.min(codeResults.size(), MAX_CODE_RESULTS);

        for (int i = 0; i < resultsToShow; i++) {
            JsonObject result = getJsonObject(codeResults, i);
            if (result == null) continue;

            String type = getJsonString(result, "type", "unknown");
            if (type.equals("function") || type.equals("text")) {
                formatCodeResult(formatted, result, type);
            }
        }

        return formatted.toString();
    }

    /**
     * Formats a single code result
     */
    private void formatCodeResult(StringBuilder formatted, JsonObject result, String type) {
        String keyword = getJsonString(result, "keyword", "unknown");
        JsonArray matches = getJsonArray(result, "matches");

        if (matches.size() == 0) return;

        boolean hasFunction = checkHasFunction(matches);

        if (hasFunction || type.equals("function")) {
            formatFunctionResults(formatted, keyword, matches);
        } else {
            formatTextResults(formatted, keyword, matches);
        }
    }

    /**
     * Checks if matches contain function information
     */
    private boolean checkHasFunction(JsonArray matches) {
        if (matches.size() > 0) {
            JsonObject firstMatch = getJsonObject(matches, 0);
            return firstMatch != null && firstMatch.has("functions");
        }
        return false;
    }

    /**
     * Formats function results
     */
    private void formatFunctionResults(StringBuilder formatted, String keyword, JsonArray matches) {
        formatted.append("\n### Functions matching '").append(keyword).append("':\n");

        int funcCount = 0;
        for (int j = 0; j < matches.size() && funcCount < MAX_FUNCTIONS_PER_KEYWORD; j++) {
            JsonObject fileMatch = getJsonObject(matches, j);
            if (fileMatch == null) continue;

            funcCount = formatFileFunctions(formatted, fileMatch, funcCount);
        }

        if (matches.size() > funcCount) {
            formatted.append("... and ").append(matches.size() - funcCount).append(" more occurrences\n");
        }
    }

    /**
     * Formats functions from a file match
     */
    private int formatFileFunctions(StringBuilder formatted, JsonObject fileMatch, int currentCount) {
        String filePath = getJsonString(fileMatch, "file", "unknown");
        JsonArray functions = getJsonArray(fileMatch, "functions");

        int funcCount = currentCount;
        for (int k = 0; k < functions.size() && funcCount < MAX_FUNCTIONS_PER_KEYWORD; k++) {
            JsonObject function = getJsonObject(functions, k);
            if (function != null) {
                formatFunction(formatted, function, filePath);
                funcCount++;
            }
        }

        return funcCount;
    }

    /**
     * Formats a single function
     */
    private void formatFunction(StringBuilder formatted, JsonObject function, String filePath) {
        String funcName = getJsonString(function, "name", "unknown");
        int line = getJsonInt(function, "line", 0);

        formatted.append("\nFile: `").append(filePath).append("` (line ").append(line).append(")\n");

        if (function.has("implementation")) {
            formatImplementation(formatted, function, filePath);
        } else if (function.has("signature")) {
            formatSignature(formatted, function);
        }
    }

    /**
     * Formats function implementation
     */
    private void formatImplementation(StringBuilder formatted, JsonObject function, String filePath) {
        String impl = getJsonString(function, "implementation", "");
        if (impl.isEmpty()) return;

        // Limit implementation length
        if (impl.length() > MAX_IMPLEMENTATION_LENGTH) {
            impl = impl.substring(0, MAX_IMPLEMENTATION_LENGTH) + "\n... (truncated)";
        }

        String lang = detectLanguage(filePath);
        formatted.append("```").append(lang).append("\n").append(impl).append("\n```\n");
    }

    /**
     * Formats function signature
     */
    private void formatSignature(StringBuilder formatted, JsonObject function) {
        String signature = getJsonString(function, "signature", "");
        if (!signature.isEmpty()) {
            formatted.append("```javascript\n").append(signature).append("\n```\n");
        }
    }

    /**
     * Formats text match results
     */
    private void formatTextResults(StringBuilder formatted, String keyword, JsonArray matches) {
        formatted.append("\n### Text matches for '").append(keyword).append("':\n");
        formatted.append("Found in ").append(matches.size()).append(" locations\n");

        if (matches.size() > 0) {
            JsonObject firstMatch = getJsonObject(matches, 0);
            if (firstMatch != null) {
                formatTextMatch(formatted, firstMatch);
            }
        }
    }

    /**
     * Formats a single text match
     */
    private void formatTextMatch(StringBuilder formatted, JsonObject firstMatch) {
        String filePath = getJsonString(firstMatch, "file", "unknown");
        JsonArray fileMatches = getJsonArray(firstMatch, "matches");

        if (fileMatches.size() > 0) {
            JsonObject match = getJsonObject(fileMatches, 0);
            if (match != null) {
                formatMatchContext(formatted, match, filePath);
            }
        }
    }

    /**
     * Formats match context
     */
    private void formatMatchContext(StringBuilder formatted, JsonObject match, String filePath) {
        int line = getJsonInt(match, "line", 0);

        formatted.append("Example from `").append(filePath).append("` (line ").append(line).append(")\n");

        if (match.has("context")) {
            JsonObject context = match.getAsJsonObject("context");
            String lang = detectLanguage(filePath);

            formatted.append("```").append(lang).append("\n");
            formatContextLines(formatted, context);
            formatted.append("```\n");
        }
    }

    /**
     * Formats context lines (before, current, after)
     */
    private void formatContextLines(StringBuilder formatted, JsonObject context) {
        // Before lines
        JsonArray before = getJsonArray(context, "before");
        for (int j = 0; j < before.size(); j++) {
            formatted.append(getJsonString(before.get(j), "")).append("\n");
        }

        // Current line
        String current = getJsonString(context, "current", "");
        if (!current.isEmpty()) {
            formatted.append(">>> ").append(current).append("\n");
        }

        // After lines
        JsonArray after = getJsonArray(context, "after");
        for (int j = 0; j < after.size(); j++) {
            formatted.append(getJsonString(after.get(j), "")).append("\n");
        }
    }

    /**
     * Detects programming language from file extension
     */
    private String detectLanguage(String filePath) {
        if (filePath == null) return "";

        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".java")) return "java";
        if (lowerPath.endsWith(".py")) return "python";
        if (lowerPath.endsWith(".js")) return "javascript";
        if (lowerPath.endsWith(".ts") || lowerPath.endsWith(".tsx")) return "typescript";
        if (lowerPath.endsWith(".cpp") || lowerPath.endsWith(".cc")) return "cpp";
        if (lowerPath.endsWith(".c")) return "c";
        if (lowerPath.endsWith(".cs")) return "csharp";
        if (lowerPath.endsWith(".go")) return "go";
        if (lowerPath.endsWith(".rb")) return "ruby";
        if (lowerPath.endsWith(".php")) return "php";
        if (lowerPath.endsWith(".swift")) return "swift";
        if (lowerPath.endsWith(".kt")) return "kotlin";
        if (lowerPath.endsWith(".rs")) return "rust";

        return "";
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