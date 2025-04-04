package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced processor for AI agent requests that supports:
 * - Conversation history integration
 * - Code context awareness
 * - Tool invocation with XML format
 */
public class EnhancedAgentRequestProcessor {
    private static final Logger LOG = Logger.getInstance(EnhancedAgentRequestProcessor.class);

    private final Project project;
    private final AgentTools tools;
    private final ConfigurationManager configManager;

    // Patterns for extracting tool usage from responses
    // Patterns for both nested XML tags and the alternative TOOL format
    private static final Pattern NESTED_TOOL_PATTERN = Pattern.compile("<([a-z_]+)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern ALTERNATIVE_TOOL_PATTERN = Pattern.compile("<TOOL>(.*?):(.*?)</TOOL>", Pattern.DOTALL);

    /**
     * Creates a new enhanced request processor.
     *
     * @param project The current project
     * @param tools The tools available to the agent
     */
    public EnhancedAgentRequestProcessor(@NotNull Project project, @NotNull AgentTools tools) {
        this.project = project;
        this.tools = tools;
        this.configManager = new ConfigurationManager(project);
    }

    /**
     * Processes a user request with tools and conversation history.
     *
     * @param userRequest The user's request
     * @param conversationHistory The conversation history
     * @param editor The current editor (can be null)
     * @return The assistant's response
     * @throws PipelineExecutionException If there's an error during processing
     */
    public String processRequestWithTools(
            @NotNull String userRequest,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {

        // Generate a request ID for tracking
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Processing enhanced request " + requestId + ": " + userRequest);

        try {
            // 1. Gather code context from the current editor
            Map<String, String> codeContext = gatherCodeContext(editor);

            // 2. Construct the full prompt with XML formatting for tools
            String fullPrompt = constructPromptWithTools(userRequest, conversationHistory, codeContext);

            // 3. Call the LLM API
            String response = callLlmApi(fullPrompt);

            // 4. Process tool usage in the response if any
            response = processToolUsage(response);

            LOG.info("Request " + requestId + " processed successfully");
            return response;

        } catch (Exception e) {
            LOG.error("Error processing request " + requestId, e);
            throw new PipelineExecutionException("Error processing request: " + e.getMessage(), e);
        }
    }

    /**
     * Processes a follow-up to a previous request.
     *
     * @param followUpResponse The user's follow-up response
     * @param conversationHistory The conversation history
     * @param editor The current editor (can be null)
     * @return The assistant's response
     * @throws PipelineExecutionException If there's an error during processing
     */
    public String processFollowUp(
            @NotNull String followUpResponse,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {

        // Tag the follow-up for special handling
        String taggedRequest = "<FOLLOW_UP>" + followUpResponse + "</FOLLOW_UP>";

        // Process normally using the tagged request
        return processRequestWithTools(taggedRequest, conversationHistory, editor);
    }

    /**
     * Gathers context from the current editor.
     *
     * @param editor The current editor
     * @return A map of context information
     */
    private Map<String, String> gatherCodeContext(@Nullable Editor editor) {
        Map<String, String> context = new HashMap<>();

        if (editor == null) {
            context.put("hasEditor", "false");
            return context;
        }

        context.put("hasEditor", "true");

        // Get current file information
        VirtualFile currentFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (currentFile != null) {
            context.put("currentFilePath", currentFile.getPath());
            context.put("currentFileName", currentFile.getName());
            context.put("currentFileExtension", currentFile.getExtension());
            context.put("currentFileContent", editor.getDocument().getText());
        }

        // Get project roots
        String sourceRoot = getSourceRoot(project);
        String testRoot = getTestRoot(project);

        if (sourceRoot != null) {
            context.put("sourceRoot", sourceRoot);
        }

        if (testRoot != null) {
            context.put("testRoot", testRoot);
        }

        // Add selection information if there is a selection
        if (editor.getSelectionModel().hasSelection()) {
            context.put("hasSelection", "true");
            context.put("selectedText", editor.getSelectionModel().getSelectedText());
            context.put("selectionStart", String.valueOf(editor.getSelectionModel().getSelectionStart()));
            context.put("selectionEnd", String.valueOf(editor.getSelectionModel().getSelectionEnd()));
        } else {
            context.put("hasSelection", "false");

            // Add cursor position
            int offset = editor.getCaretModel().getOffset();
            int lineNumber = editor.getDocument().getLineNumber(offset);
            int column = offset - editor.getDocument().getLineStartOffset(lineNumber);

            context.put("cursorOffset", String.valueOf(offset));
            context.put("cursorLineNumber", String.valueOf(lineNumber + 1));
            context.put("cursorColumn", String.valueOf(column + 1));
        }

        return context;
    }

    /**
     * Gets the source root directory for the project.
     *
     * @param project The current project
     * @return The source root path, or null if not found
     */
    private String getSourceRoot(Project project) {
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) {
                return null;
            }

            // Common source roots to check
            String[] commonSourceRoots = {
                    "src/main/java",
                    "src/main/kotlin",
                    "src/main/scala",
                    "src",
                    "java",
                    "source"
            };

            // Check if common source roots exist
            for (String root : commonSourceRoots) {
                VirtualFile sourceRoot = baseDir.findFileByRelativePath(root);
                if (sourceRoot != null && sourceRoot.isDirectory()) {
                    return sourceRoot.getPath();
                }
            }

            // Fallback - search for .java files in the project structure
            VirtualFile[] contentRoots = baseDir.getChildren();
            for (VirtualFile root : contentRoots) {
                if (root.isDirectory() && containsSourceFiles(root)) {
                    return root.getPath();
                }
            }

            return null;
        } catch (Exception e) {
            LOG.error("Error finding source root", e);
            return null;
        }
    }
    /**
     * Checks if a directory contains source files (.java, .kt, etc.)
     *
     * @param dir The directory to check
     * @return True if the directory contains source files, false otherwise
     */
    private boolean containsSourceFiles(VirtualFile dir) {
        if (!dir.isDirectory()) {
            return false;
        }

        VirtualFile[] children = dir.getChildren();
        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                if (containsSourceFiles(child)) {
                    return true;
                }
            } else {
                String extension = child.getExtension();
                if (extension != null && (extension.equals("java") ||
                        extension.equals("kt") ||
                        extension.equals("scala"))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Gets the test root directory for the project.
     *
     * @param project The current project
     * @return The test root path, or null if not found
     */
    private String getTestRoot(Project project) {
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) {
                return null;
            }

            // Common test roots to check
            String[] commonTestRoots = {
                    "src/test/java",
                    "src/test/kotlin",
                    "src/test/scala",
                    "test",
                    "tests"
            };

            // Check if common test roots exist
            for (String root : commonTestRoots) {
                VirtualFile testRoot = baseDir.findFileByRelativePath(root);
                if (testRoot != null && testRoot.isDirectory()) {
                    return testRoot.getPath();
                }
            }

            return null;
        } catch (Exception e) {
            LOG.error("Error finding test root", e);
            return null;
        }
    }


    /**
     * Constructs a complete prompt with tools, history, and the user request.
     *
     * @param userRequest The user's request
     * @param conversationHistory The conversation history
     * @param codeContext The code context from the editor
     * @return The full prompt for the LLM
     */
    private String constructPromptWithTools(
            @NotNull String userRequest,
            @NotNull List<String> conversationHistory,
            @NotNull Map<String, String> codeContext) {

        StringBuilder prompt = new StringBuilder();

        // Add system instructions with LLM-friendly tool use guidance
        prompt.append("<system>\n");
        prompt.append("You are Claude, a helpful AI coding assistant integrated into IntelliJ IDEA. ");
        prompt.append("You help programmers write, understand, and improve code. ");
        prompt.append("Be concise, precise, and helpful. Remember you are part of an IDE, so focus on code improvements, ");
        prompt.append("explanations, and practical solutions.\n\n");

        // Tool use instructions in a format more friendly to LLMs
        prompt.append("# AVAILABLE TOOLS\n\n");
        prompt.append("You have access to the following tools. Always use these tools to gather context before providing solutions.\n\n");

        prompt.append("## Code Analysis Tools\n");
        prompt.append("- `read_file`: Read the content of a file by name\n");
        prompt.append("  Usage: <TOOL>read_file:path/to/file.java</TOOL>\n\n");

        prompt.append("- `find_methods`: Find methods matching a search term\n");
        prompt.append("  Usage: <TOOL>find_methods:methodName</TOOL>\n\n");

        prompt.append("- `search_classes`: Search for classes by name or content\n");
        prompt.append("  Usage: <TOOL>search_classes:ClassName</TOOL>\n\n");

        prompt.append("- `get_project_structure`: Get an overview of the project structure\n");
        prompt.append("  Usage: <TOOL>get_project_structure:</TOOL>\n\n");

        prompt.append("- `get_current_class_info`: Get information about the current class\n");
        prompt.append("  Usage: <TOOL>get_current_class_info:</TOOL>\n\n");

        prompt.append("- `find_references`: Find references to a symbol\n");
        prompt.append("  Usage: <TOOL>find_references:symbolName</TOOL>\n\n");

        prompt.append("## Code Modification Tools\n");
        prompt.append("- `create_file`: Create a new file with content\n");
        prompt.append("  Usage: <TOOL>create_file:path/to/file.java:public class Example { ... }</TOOL>\n\n");

        prompt.append("- Replace selected code: {{REPLACE_SELECTION:code}}\n\n");

        prompt.append("- Insert at cursor: {{INSERT_AT_CURSOR:code}}\n\n");

        prompt.append("- Ask follow-up question: {{FOLLOW_UP_QUESTION:question}}\n\n");

        prompt.append("# HOW TO HELP USERS\n\n");
        prompt.append("1. First, use appropriate tools to understand the context (read files, examine structure)\n");
        prompt.append("2. Then, analyze the code and identify potential improvements\n");
        prompt.append("3. Finally, implement changes using the modification tools\n\n");

        prompt.append("When helping users with code, always follow this workflow:\n");
        prompt.append("1. UNDERSTAND: Use tools to examine relevant code\n");
        prompt.append("2. EXPLAIN: Provide clear analysis based on what you found\n");
        prompt.append("3. IMPLEMENT: Suggest or make changes with appropriate tools\n\n");

        prompt.append("Remember, your primary advantage is your ability to use tools to examine and modify code directly in the IDE.\n");
        prompt.append("</system>\n\n");

        // Add code context - this provides crucial information about the current code state
        prompt.append("<CODE_CONTEXT>\n");
        for (Map.Entry<String, String> entry : codeContext.entrySet()) {
            if (!entry.getKey().equals("currentFileContent")) {
                prompt.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        // Include the file content separately to avoid cluttering the context
        if (codeContext.containsKey("currentFileContent")) {
            String content = codeContext.get("currentFileContent");
            if (content.length() > 1000) {
                // If file is large, include a truncated version
                prompt.append("currentFileContent (truncated): \n```\n");
                prompt.append(content.substring(0, 1000)).append("\n... [truncated, use READ_FILE tool for full content]\n```\n");
            } else {
                prompt.append("currentFileContent: \n```\n").append(content).append("\n```\n");
            }
        }
        prompt.append("</CODE_CONTEXT>\n\n");

        // Add conversation history (limited to prevent context overflow)
        if (!conversationHistory.isEmpty()) {
            prompt.append("<CONVERSATION_HISTORY>\n");
            // Limit history to last 10 messages
            int startIdx = Math.max(0, conversationHistory.size() - 10);
            for (int i = startIdx; i < conversationHistory.size(); i++) {
                prompt.append(conversationHistory.get(i)).append("\n\n");
            }
            prompt.append("</CONVERSATION_HISTORY>\n\n");
        }

        // Add the current user request
        prompt.append("<USER_REQUEST>\n").append(userRequest).append("\n</USER_REQUEST>\n");

        return prompt.toString();
    }

    /**
     * Calls the LLM API with the constructed prompt.
     *
     * @param prompt The full prompt
     * @return The raw response from the LLM
     * @throws PipelineExecutionException If there's an error during the API call
     */
    private String callLlmApi(@NotNull String prompt) throws PipelineExecutionException {
        try {
            // Create a context for the API call
            CodeContext context = new CodeContext();
            context.setProject(project);
            context.setConfig(configManager);

            // Add forcing function to prompt - this significantly increases tool use
            String enhancedPrompt = addForcingFunction(prompt);
            context.setPrompt(enhancedPrompt);

            // Call the LLM API using the existing implementation
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(context);

            // Get and process the response
            String response = context.getApiResponse();

            // Check if response contains tool usage - if not, retry with stronger prompt
            if (!response.contains("<TOOL>")) {
                LOG.info("No tool usage detected in response - retrying with stronger prompt");
                String retryPrompt = addToolUseReminder(enhancedPrompt);
                context.setPrompt(retryPrompt);
                apiCallStage.process(context);
                response = context.getApiResponse();
            }

            // Return the response
            return response;
        } catch (Exception e) {
            LOG.error("Error calling LLM API", e);
            throw new PipelineExecutionException("Error calling LLM API: " + e.getMessage(), e);
        }
    }

    /**
     * Adds a forcing function to the prompt to encourage tool use.
     * This technique significantly increases the likelihood of tool usage in responses.
     *
     * @param originalPrompt The original prompt
     * @return The enhanced prompt with forcing function
     */
    private String addForcingFunction(String originalPrompt) {
        // Add a more natural, LLM-friendly prefix that guides the model to use tools
        String forcingPrefix = "When working in an IDE context, I should demonstrate the workflow of:\n" +
                "1. Exploring context with appropriate tools\n" +
                "2. Analyzing what I find\n" +
                "3. Implementing solutions based on that analysis\n\n" +
                "For this request, I'll start by using relevant tools to understand the context.\n\n";

        // Find the right position to insert the forcing function (after system instructions)
        int insertPosition = originalPrompt.indexOf("</s>") + "</s>".length();
        if (insertPosition > 0) {
            StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);
            enhancedPrompt.insert(insertPosition, "\n\n" + forcingPrefix);
            return enhancedPrompt.toString();
        }

        return originalPrompt;
    }

    /**
     * Adds a stronger reminder about tool use to the prompt.
     * Used for retry attempts when the initial response doesn't include tool usage.
     *
     * @param prompt The original prompt
     * @return The prompt with a stronger tool use reminder
     */
    private String addToolUseReminder(String prompt) {
        String reminder = "\n\n# IMPORTANT CONTEXT\n\n" +
                "As an IDE assistant, I should first use tools to examine the code context before providing advice. " +
                "Let me start by using an appropriate tool to understand the current context, such as " +
                "`read_file`, `get_current_class_info`, or `get_project_structure`.\n\n" +
                "I'll use <TOOL>tool_name:parameters</TOOL> syntax to invoke these tools.\n\n";

        // Add the reminder just before the user request section
        int insertPosition = prompt.indexOf("<USER_REQUEST>");
        if (insertPosition > 0) {
            StringBuilder enhancedPrompt = new StringBuilder(prompt);
            enhancedPrompt.insert(insertPosition, reminder);
            return enhancedPrompt.toString();
        }

        return prompt;
    }

    /**
     * Processes tool usage directives in the response.
     *
     * @param response The raw response from the LLM
     * @return The processed response with tool outputs
     */
    private String processToolUsage(@NotNull String response) {
        StringBuilder processedResponse = new StringBuilder();
        boolean foundTools = false;
        int lastEnd = 0;

        // First try the nested XML format
        Matcher nestedMatcher = NESTED_TOOL_PATTERN.matcher(response);
        while (nestedMatcher.find()) {
            // Add any text before the tool directive
            processedResponse.append(response.substring(lastEnd, nestedMatcher.start()));

            // Extract the tool name and content
            String toolName = nestedMatcher.group(1).trim();
            String toolContent = nestedMatcher.group(2).trim();
            foundTools = true;

            // Execute the tool and add the output
            String toolOutput = executeToolDirective(toolName, toolContent);

            // Format the tool output with a clear header
            processedResponse.append("\n\n### Tool Result\n```\n");
            processedResponse.append(toolOutput);
            processedResponse.append("\n```\n\n");

            lastEnd = nestedMatcher.end();
        }

        // If no nested tools found, try the alternative format
        if (!foundTools) {
            // Reset the processed response
            processedResponse = new StringBuilder();
            lastEnd = 0;

            Matcher altMatcher = ALTERNATIVE_TOOL_PATTERN.matcher(response);
            while (altMatcher.find()) {
                // Add any text before the tool directive
                processedResponse.append(response.substring(lastEnd, altMatcher.start()));

                // Extract the tool name and content
                String toolDirective = altMatcher.group(1).trim(); // e.g., "create_file"
                String toolParams = altMatcher.group(2).trim();    // e.g., "path:content"
                foundTools = true;

                // Handle the alternative format based on the tool type
                String toolOutput;
                if (toolDirective.equals("create_file")) {
                    toolOutput = tools.createFile(toolParams);
                } else {
                    // For other tools, we may need specific handling
                    toolOutput = "Unhandled tool directive: " + toolDirective;
                }

                // Format the tool output with a clear header
                processedResponse.append("\n\n### Tool Result\n```\n");
                processedResponse.append(toolOutput);
                processedResponse.append("\n```\n\n");

                lastEnd = altMatcher.end();
            }
        }

        // Add any remaining text
        if (lastEnd < response.length()) {
            processedResponse.append(response.substring(lastEnd));
        }

        // If no tools were found, add a gentle suggestion about tool usage
        if (!foundTools) {
            LOG.warn("No tools found in LLM response - adding suggestion");
            processedResponse = new StringBuilder(response);
            processedResponse.append("\n\n---\n");
            processedResponse.append("*Tip: I can examine your code files, find methods, and make targeted changes using built-in tools. ");
            processedResponse.append("This allows me to provide more specific help with your code.*\n");
        }

        return processedResponse.toString();
    }

    /**
     * Executes a tool directive and returns the output.
     *
     * @param toolName The name of the tool to execute
     * @param toolContent The content containing parameters for the tool
     * @return The output from executing the tool
     */
    private String executeToolDirective(@NotNull String toolName, @NotNull String toolContent) {
        try {
            switch (toolName) {
                case "read_file":
                    Pattern pathPattern = Pattern.compile("<path>(.*?)</path>", Pattern.DOTALL);
                    Matcher pathMatcher = pathPattern.matcher(toolContent);
                    if (pathMatcher.find()) {
                        String path = pathMatcher.group(1).trim();
                        return tools.readFile(path);
                    } else {
                        return "Error: Missing path parameter for read_file tool";
                    }

                case "find_methods":
                    Pattern searchTermPattern = Pattern.compile("<search_term>(.*?)</search_term>", Pattern.DOTALL);
                    Matcher searchTermMatcher = searchTermPattern.matcher(toolContent);
                    if (searchTermMatcher.find()) {
                        String searchTerm = searchTermMatcher.group(1).trim();
                        List<String> methods = tools.findMethods(searchTerm);
                        if (methods.isEmpty()) {
                            return "No methods found matching: " + searchTerm;
                        }
                        StringBuilder methodsResult = new StringBuilder();
                        for (String method : methods) {
                            methodsResult.append(method).append("\n\n");
                        }
                        return methodsResult.toString();
                    } else {
                        return "Error: Missing search_term parameter for find_methods tool";
                    }

                case "search_classes":
                    Pattern classSearchPattern = Pattern.compile("<search_term>(.*?)</search_term>", Pattern.DOTALL);
                    Matcher classSearchMatcher = classSearchPattern.matcher(toolContent);
                    if (classSearchMatcher.find()) {
                        String searchTerm = classSearchMatcher.group(1).trim();
                        return tools.searchClasses(searchTerm);
                    } else {
                        return "Error: Missing search_term parameter for search_classes tool";
                    }

                case "get_project_structure":
                    return tools.getProjectStructure();

                case "get_current_class_info":
                    return tools.getCurrentClassInfo();

                case "find_references":
                    Pattern symbolPattern = Pattern.compile("<symbol_name>(.*?)</symbol_name>", Pattern.DOTALL);
                    Matcher symbolMatcher = symbolPattern.matcher(toolContent);
                    if (symbolMatcher.find()) {
                        String symbolName = symbolMatcher.group(1).trim();
                        return tools.findReferences(symbolName);
                    } else {
                        return "Error: Missing symbol_name parameter for find_references tool";
                    }

                case "create_file":
                    Pattern filePathPattern = Pattern.compile("<path>(.*?)</path>", Pattern.DOTALL);
                    Pattern fileContentPattern = Pattern.compile("<content>(.*?)</content>", Pattern.DOTALL);

                    Matcher filePathMatcher = filePathPattern.matcher(toolContent);
                    Matcher fileContentMatcher = fileContentPattern.matcher(toolContent);

                    if (filePathMatcher.find() && fileContentMatcher.find()) {
                        String filePath = filePathMatcher.group(1).trim();
                        String fileContent = fileContentMatcher.group(1);

                        // Format for createFile method
                        return tools.createFile(filePath + ":" + fileContent);
                    } else {
                        return "Error: Missing path or content parameters for create_file tool";
                    }

                case "replace_selection":
                    Pattern codePattern = Pattern.compile("<code>(.*?)</code>", Pattern.DOTALL);
                    Matcher codeMatcher = codePattern.matcher(toolContent);
                    if (codeMatcher.find()) {
                        String code = codeMatcher.group(1);
                        // Convert to the format expected by AiCodingAssistantAction
                        return "{{REPLACE_SELECTION:" + code + "}}";
                    } else {
                        return "Error: Missing code parameter for replace_selection tool";
                    }

                case "insert_at_cursor":
                    Pattern insertCodePattern = Pattern.compile("<code>(.*?)</code>", Pattern.DOTALL);
                    Matcher insertCodeMatcher = insertCodePattern.matcher(toolContent);
                    if (insertCodeMatcher.find()) {
                        String code = insertCodeMatcher.group(1);
                        // Convert to the format expected by AiCodingAssistantAction
                        return "{{INSERT_AT_CURSOR:" + code + "}}";
                    } else {
                        return "Error: Missing code parameter for insert_at_cursor tool";
                    }

                case "ask_followup_question":
                    Pattern questionPattern = Pattern.compile("<question>(.*?)</question>", Pattern.DOTALL);
                    Matcher questionMatcher = questionPattern.matcher(toolContent);
                    if (questionMatcher.find()) {
                        String question = questionMatcher.group(1).trim();
                        // Convert to the format expected by InteractiveAgentPanel
                        return "{{FOLLOW_UP_QUESTION:" + question + "}}";
                    } else {
                        return "Error: Missing question parameter for ask_followup_question tool";
                    }

                default:
                    LOG.warn("Unknown tool requested: " + toolName);
                    return "Unknown tool: '" + toolName + "'. Available tools are: read_file, find_methods, search_classes, " +
                            "get_project_structure, get_current_class_info, find_references, create_file, replace_selection, " +
                            "insert_at_cursor, ask_followup_question";
            }
        } catch (Exception e) {
            LOG.error("Error executing tool: " + toolName, e);
            return "Tool execution error: " + e.getMessage() + "\n\nPlease check the parameters and try again.";
        }
    }
}