package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiUtilBase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced processor for AI agent requests with tool usage and follow-up capabilities.
 */
public class EnhancedAgentRequestProcessor {
    private static final Logger LOG = Logger.getInstance(EnhancedAgentRequestProcessor.class);
    
    private final Project project;
    private final ConfigurationManager config;
    private final AgentTools tools;

    private static final Pattern USE_TOOL_PATTERN =
            Pattern.compile("\\{\\{USE_TOOL:([\\w_]+)(?::([^}]*)?)?\\}\\}", Pattern.MULTILINE);
    
    // Map of available tools and their implementations
    private final Map<String, ToolFunction> toolFunctions = new HashMap<>();

    /**
     * Creates a new enhanced request processor.
     *
     * @param project The current project
     * @param tools The tools for the agent to use
     */
    public EnhancedAgentRequestProcessor(Project project, AgentTools tools) {
        this.project = project;
        this.tools = tools;
        this.config = new ConfigurationManager(project);
        
        // Register available tools
        registerTools();
    }

    /**
     * Registers the available tools with their implementations.
     */
    private void registerTools() {
        // File reading tool
        toolFunctions.put("readFile", tools::readFile);
        
        // Method search tool
        toolFunctions.put("findMethods", param -> {
            List<String> methods = tools.findMethods(param);
            StringBuilder result = new StringBuilder("Found methods:\n\n");
            for (String method : methods) {
                result.append(method).append("\n\n");
            }
            return result.toString();
        });
        
        // Class search tool
        toolFunctions.put("searchClasses", tools::searchClasses);
        
        // Project structure tool
        toolFunctions.put("getProjectStructure", param -> tools.getProjectStructure());
        
        // Current class info tool
        toolFunctions.put("getCurrentClassInfo", param -> tools.getCurrentClassInfo());
        
        // Reference finding tool
        toolFunctions.put("findReferences", tools::findReferences);
        toolFunctions.put("createFile", tools::createFile);
    }

    /**
     * Processes a request with tool usage capabilities.
     *
     * @param request The user's request
     * @param conversationHistory The conversation history
     * @param editor The current editor
     * @return The agent's response
     * @throws PipelineExecutionException If an error occurs during processing
     */
    public String processRequestWithTools(String request, List<String> conversationHistory, Editor editor) 
            throws PipelineExecutionException {
        try {
            // Gather context from the editor
            String context = gatherContext(editor);
            
            // Build the prompt with tools
            String prompt = buildPromptWithTools(request, conversationHistory, context, editor);
            
            // Call the LLM API
            String initialResponse = callLlmApi(prompt);
            
            // Process the response for tool usage
            return processToolUsage(initialResponse, conversationHistory, editor);
        } catch (Exception e) {
            LOG.error("Error processing request", e);
            throw new PipelineExecutionException("Error processing request: " + e.getMessage(), e);
        }
    }

    /**
     * Processes a follow-up response.
     *
     * @param answer The user's answer to the follow-up
     * @param conversationHistory The conversation history
     * @param editor The current editor
     * @return The agent's response
     * @throws PipelineExecutionException If an error occurs during processing
     */
    public String processFollowUp(String answer, List<String> conversationHistory, Editor editor) 
            throws PipelineExecutionException {
        try {
            // Build follow-up prompt
            String prompt = buildFollowUpPrompt(answer, conversationHistory, editor);
            
            // Call the LLM API
            String initialResponse = callLlmApi(prompt);
            
            // Process the response for tool usage
            return processToolUsage(initialResponse, conversationHistory, editor);
        } catch (Exception e) {
            LOG.error("Error processing follow-up", e);
            throw new PipelineExecutionException("Error processing follow-up: " + e.getMessage(), e);
        }
    }

    /**
     * Processes tool usage in the LLM response.
     *
     * @param response The initial response
     * @param conversationHistory The conversation history
     * @param editor The current editor
     * @return The processed response with tool outputs
     * @throws PipelineExecutionException If an error occurs during processing
     */
    private String processToolUsage(String response, List<String> conversationHistory, Editor editor) 
            throws PipelineExecutionException {
        // Check if the response contains tool usage commands
        Matcher matcher = USE_TOOL_PATTERN.matcher(response);
        
        if (!matcher.find()) {
            // No tool usage, return the response as is
            return response;
        }
        
        // Reset the matcher to process all tool usages
        matcher.reset();
        
        // StringBuilder to build the processed response
        StringBuilder processedResponse = new StringBuilder(response);
        
        // Track offsets as we replace tool usages with their outputs
        int offset = 0;
        
        // Process each tool usage
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String toolParam = matcher.group(2);
            String toolUsagePattern = "{{USE_TOOL:" + toolName + ":" + toolParam + "}}";
            
            // Check if the tool exists
            if (!toolFunctions.containsKey(toolName)) {
                String errorMessage = "Tool not found: " + toolName;
                LOG.warn(errorMessage);
                
                // Replace the tool usage with error
                processedResponse.replace(
                        matcher.start() + offset, 
                        matcher.end() + offset, 
                        "ERROR: " + errorMessage);
                
                // Update offset
                offset += ("ERROR: " + errorMessage).length() - toolUsagePattern.length();
                continue;
            }
            
            try {
                // Execute the tool
                String toolOutput = toolFunctions.get(toolName).execute(toolParam);
                
                // Format the tool output
                String formattedOutput = "Tool Output (" + toolName + "):\n```\n" + toolOutput + "\n```";
                
                // Replace the tool usage with its output
                processedResponse.replace(
                        matcher.start() + offset, 
                        matcher.end() + offset, 
                        formattedOutput);
                
                // Update offset
                offset += formattedOutput.length() - toolUsagePattern.length();
            } catch (Exception e) {
                String errorMessage = "Error executing tool " + toolName + ": " + e.getMessage();
                LOG.error(errorMessage, e);
                
                // Replace the tool usage with error
                processedResponse.replace(
                        matcher.start() + offset, 
                        matcher.end() + offset, 
                        "ERROR: " + errorMessage);
                
                // Update offset
                offset += ("ERROR: " + errorMessage).length() - toolUsagePattern.length();
            }
        }
        
        // Recursively handle nested tool usages
        String result = processedResponse.toString();
        if (USE_TOOL_PATTERN.matcher(result).find()) {
            // If there are still tool usages, process them
            return processToolUsage(result, conversationHistory, editor);
        }
        
        // If the response needs further processing with the new context
        if (result.contains("{{PROCESS_WITH_TOOLS:")) {
            return handleAdditionalProcessing(result, conversationHistory, editor);
        }
        
        return result;
    }
    
    /**
     * Handles additional processing requests from the LLM.
     *
     * @param response The response containing the processing request
     * @param conversationHistory The conversation history
     * @param editor The current editor
     * @return The processed response
     * @throws PipelineExecutionException If an error occurs during processing
     */
    private String handleAdditionalProcessing(String response, List<String> conversationHistory, Editor editor) 
            throws PipelineExecutionException {
        // Extract the content to process
        int startIndex = response.indexOf("{{PROCESS_WITH_TOOLS:") + "{{PROCESS_WITH_TOOLS:".length();
        int endIndex = response.indexOf("}}", startIndex);
        
        if (startIndex >= "{{PROCESS_WITH_TOOLS:".length() && endIndex > startIndex) {
            String contentToProcess = response.substring(startIndex, endIndex);
            
            // Clean response by removing the processing tag
            String cleanResponse = response.replace("{{PROCESS_WITH_TOOLS:" + contentToProcess + "}}", "");
            
            // Build a new prompt with the additional content
            String prompt = buildPromptWithTools(contentToProcess, conversationHistory, "", editor);
            
            // Call the LLM API
            String additionalResponse = callLlmApi(prompt);
            
            // Process any tool usage in the additional response
            String processedAdditionalResponse = processToolUsage(additionalResponse, conversationHistory, editor);
            
            // Combine the responses
            return cleanResponse + "\n\nAdditional processing results:\n\n" + processedAdditionalResponse;
        }
        
        // If extraction fails, return the original response
        return response;
    }

    /**
     * Gathers context from the editor.
     *
     * @param editor The current editor
     * @return The context string
     */
    private String gatherContext(Editor editor) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) ()->{
            StringBuilder context = new StringBuilder();

            if (editor == null) {
                return "No editor available";
            }

            // Get file information
            PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
            if (psiFile != null) {
                context.append("File: ").append(psiFile.getName()).append("\n");
                context.append("Language: ").append(psiFile.getLanguage().getDisplayName()).append("\n\n");

                // Get current selection or visible content
                SelectionModel selectionModel = editor.getSelectionModel();
                String selectedText = selectionModel.getSelectedText();

                if (selectedText != null && !selectedText.isEmpty()) {
                    context.append("Selected Code:\n```\n")
                            .append(selectedText)
                            .append("\n```\n\n");
                } else {
                    // If no selection, get the visible part of the file
                    int visibleStart = editor.getScrollingModel().getVisibleArea().y /
                            editor.getLineHeight();
                    int visibleEnd = visibleStart +
                            (editor.getScrollingModel().getVisibleArea().height /
                                    editor.getLineHeight());

                    visibleStart = Math.max(0, visibleStart - 5); // Add some context
                    visibleEnd = Math.min(editor.getDocument().getLineCount(), visibleEnd + 5);

                    int startOffset = editor.logicalPositionToOffset(
                            editor.visualToLogicalPosition(
                                    editor.offsetToVisualPosition(
                                            editor.getDocument().getLineStartOffset(visibleStart))));

                    int endOffset = editor.logicalPositionToOffset(
                            editor.visualToLogicalPosition(
                                    editor.offsetToVisualPosition(
                                            editor.getDocument().getLineEndOffset(visibleEnd))));

                    String visibleText = editor.getDocument().getText().substring(startOffset, endOffset);

                    context.append("Visible Code:\n```\n")
                            .append(visibleText)
                            .append("\n```\n\n");
                }

                // Add cursor position
                int offset = editor.getCaretModel().getOffset();
                int line = editor.getDocument().getLineNumber(offset);
                int column = offset - editor.getDocument().getLineStartOffset(line);

                context.append("Cursor Position: Line ").append(line + 1)
                        .append(", Column ").append(column + 1).append("\n\n");
            }

            return context.toString();
        });

    }

    /**
     * Builds a prompt with tool usage instructions.
     *
     * @param request The user's request
     * @param conversationHistory The conversation history
     * @param context The editor context
     * @param editor The current editor
     * @return The complete prompt
     */
    private String buildPromptWithTools(String request, List<String> conversationHistory, String context, Editor editor) {
        StringBuilder prompt = new StringBuilder();

        // System instructions
        prompt.append("You are an AI coding assistant integrated into IntelliJ IDEA. ")
                .append("Your task is to help programmers write, understand, and improve code. ")
                .append("Be concise, precise, and helpful.\n\n");

        // Replace with more comprehensive system instructions
        prompt.append("# Your Role and Capabilities\n\n")
                .append("- You're embedded in IntelliJ IDEA as an AI Assistant tool window\n")
                .append("- You can analyze code within the editor, provide explanations, and suggest improvements\n")
                .append("- You can interact with the project structure, read files, and search for classes/methods\n")
                .append("- You can generate code snippets that can be inserted at the cursor or replace selected text\n\n");

        // Tool usage instructions
        prompt.append("# Available Tools\n\n")
                .append("You have access to the following tools when you need more information:\n");
        prompt.append("1. {{USE_TOOL:readFile:filename}} - Read the content of a file\n");
        prompt.append("2. {{USE_TOOL:findMethods:searchTerm}} - Find methods matching a search term\n");
        prompt.append("3. {{USE_TOOL:searchClasses:className}} - Search for classes\n");
        prompt.append("4. {{USE_TOOL:getProjectStructure:}} - Get information about the project structure\n");
        prompt.append("5. {{USE_TOOL:getCurrentClassInfo:}} - Get information about the current class\n");
        prompt.append("6. {{USE_TOOL:findReferences:symbolName}} - Find references to a symbol\n\n");

        prompt.append("Use these tools when needed to gather more information before providing your answer. \n")
                .append("You can also ask follow-up questions using {{FOLLOW_UP_QUESTION:your question here?}} ")
                .append("if you need more information from the user.\n\n");

        // Special command instructions
        prompt.append("# Code Modification Commands\n\n")
                .append("You can use these commands to modify code:\n")
                .append("1. {{USE_TOOL:replaceSelection:code}} - Replace the user's selected text with new code\n")
                .append("2. {{USE_TOOL:insertAtCursor:code}} - Insert code at the cursor position\n")
                .append("3. {{USE_TOOL:createFile:path:content}} - Create a new file with specified content\n")
                .append("4. {{USE_TOOL:writeFile:path:content}} - Write content to a file\n\n");
        // Communication guidelines
        prompt.append("# Communication Guidelines\n\n")
                .append("1. Be concise and precise: Provide clear, focused answers that address specific needs.\n")
                .append("2. Be context-aware: Consider the code context, including the current file and cursor position.\n")
                .append("3. Think step by step: Determine needed information, use tools to gather it, then provide a comprehensive response.\n")
                .append("4. Include clear explanations: Explain your reasoning and suggest best practices.\n")
                .append("5. When suggesting code: Be precise, follow the project's coding style, and make code immediately usable.\n\n")
                .append("6. Try to utilize the tools provided.\n\n");

        // Add conversation history for context
        if (!conversationHistory.isEmpty()) {
            prompt.append("# CONVERSATION HISTORY:\n");
            for (String message : conversationHistory) {
                prompt.append(message).append("\n");
            }
            prompt.append("\n");
        }

        // Add context information
        if (context != null && !context.isEmpty()) {
            prompt.append("# CURRENT CONTEXT:\n").append(context).append("\n");
        }

        // Add user request
        prompt.append("# USER REQUEST: ").append(request).append("\n\n");

        // Final instructions
        prompt.append("Think step by step. First determine what information you need, ")
                .append("then use the appropriate tools to gather that information, ")
                .append("and finally provide a comprehensive response. You should also use tools to perform tasks like writing or editing code on user's side if necessary.")
                .append("If you don't have enough information, ask a follow-up question.\n");

        return prompt.toString();
    }
    /**
     * Builds a prompt for handling follow-up responses.
     *
     * @param answer The user's answer to the follow-up
     * @param conversationHistory The conversation history
     * @param editor The current editor
     * @return The complete prompt
     */
    private String buildFollowUpPrompt(String answer, List<String> conversationHistory, Editor editor) {
        StringBuilder prompt = new StringBuilder();

        // System instructions
        prompt.append("You are an AI coding assistant integrated into IntelliJ IDEA. ")
                .append("You previously asked a follow-up question and have now received an answer. ")
                .append("Continue the conversation based on this answer.\n\n");

        // Role and capabilities
        prompt.append("# Your Role and Capabilities\n\n")
                .append("- You're embedded in IntelliJ IDEA as an AI Assistant tool window\n")
                .append("- You can analyze code within the editor, provide explanations, and suggest improvements\n")
                .append("- You can interact with the project structure, read files, and search for classes/methods\n")
                .append("- You can generate code snippets that can be inserted at the cursor or replace selected text\n\n");

        // Tool usage instructions
        prompt.append("# Available Tools\n\n")
                .append("You have access to the following tools when you need more information:\n");
        prompt.append("1. {{USE_TOOL:readFile:filename}} - Read the content of a file\n");
        prompt.append("2. {{USE_TOOL:findMethods:searchTerm}} - Find methods matching a search term\n");
        prompt.append("3. {{USE_TOOL:searchClasses:className}} - Search for classes\n");
        prompt.append("4. {{USE_TOOL:getProjectStructure:}} - Get information about the project structure\n");
        prompt.append("5. {{USE_TOOL:getCurrentClassInfo:}} - Get information about the current class\n");
        prompt.append("6. {{USE_TOOL:findReferences:symbolName}} - Find references to a symbol\n\n");

        prompt.append("# Code Modification Commands\n\n")
                .append("You can use these commands to modify code:\n")
                .append("1. {{USE_TOOL:replaceSelection:code}} - Replace the user's selected text with new code\n")
                .append("2. {{USE_TOOL:insertAtCursor:code}} - Insert code at the cursor position\n")
                .append("3. {{USE_TOOL:createFile:path:content}} - Create a new file with specified content\n")
                .append("4. {{USE_TOOL:writeFile:path:content}} - Write content to a file\n\n");


        // Communication guidelines
        prompt.append("# Communication Guidelines\n\n")
                .append("1. Be concise and precise: Provide clear, focused answers that address specific needs.\n")
                .append("2. Be context-aware: Consider the code context, including the current file and cursor position.\n")
                .append("3. Think step by step: Determine needed information, use tools to gather it, then provide a comprehensive response.\n")
                .append("4. Include clear explanations: Explain your reasoning and suggest best practices.\n")
                .append("5. When suggesting code: Be precise, follow the project's coding style, and make code immediately usable.\n\n");

        // Add conversation history for context
        if (!conversationHistory.isEmpty()) {
            prompt.append("# CONVERSATION HISTORY:\n");
            for (String message : conversationHistory) {
                prompt.append(message).append("\n");
            }
            prompt.append("\n");
        }

        // Add user's answer to the follow-up
        prompt.append("# USER FOLLOW-UP ANSWER: ").append(answer).append("\n\n");

        // Final instructions
        prompt.append("Continue the conversation based on this answer. ")
                .append("You can use tools to gather more information if needed, ")
                .append("or ask additional follow-up questions if necessary.\n");

        return prompt.toString();
    }
    /**
     * Calls the LLM API with the prompt and returns the response.
     *
     * @param prompt The prompt to send
     * @return The response from the LLM
     * @throws PipelineExecutionException If the API call fails
     */
    private String callLlmApi(String prompt) throws PipelineExecutionException {
        try {
            // Create a temporary context for the API call
            CodeContext context = new CodeContext();
            context.setProject(project);
            context.setConfig(config);
            context.setPrompt(prompt);
            
            // Call the LLM API using the existing implementation
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(context);
            
            return context.getApiResponse();
        } catch (Exception e) {
            LOG.error("Error calling LLM API", e);
            throw new PipelineExecutionException("Failed to call LLM API: " + e.getMessage(), e);
        }
    }

    /**
     * Functional interface for tool functions.
     */
    @FunctionalInterface
    private interface ToolFunction {
        /**
         * Executes the tool with the specified parameter.
         *
         * @param parameter The parameter for the tool
         * @return The tool output
         */
        String execute(String parameter);
    }
}