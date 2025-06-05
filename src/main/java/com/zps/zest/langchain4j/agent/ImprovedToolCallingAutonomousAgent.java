package com.zps.zest.langchain4j.agent;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.langchain4j.tools.ToolCallParser;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Enhanced autonomous code exploration agent that uses tool calls with proper feedback loop.
 * This version properly executes tool calls and feeds results back to the LLM.
 */
@Service(Service.Level.PROJECT)
public final class ImprovedToolCallingAutonomousAgent {
    private static final Logger LOG = Logger.getInstance(ImprovedToolCallingAutonomousAgent.class);

    private final Project project;
    private final LLMService llmService;
    private final CodeExplorationToolRegistry toolRegistry;
    private final ToolCallParser toolCallParser;

    // Configuration
    private static final int MAX_TOOL_CALLS = 10;
    private static final int MAX_ROUNDS = 1;
    private static final int MAX_RESULT_LENGTH = 2000; // Increased from 500

    public ImprovedToolCallingAutonomousAgent(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.toolCallParser = new ToolCallParser();
        LOG.info("Initialized ImprovedToolCallingAutonomousAgent");
    }

    /**
     * Starts an autonomous exploration session using tool calls with proper feedback.
     */
    public ExplorationResult exploreWithTools(String userQuery) {
        LOG.info("Starting improved tool-based exploration for: " + userQuery);

        ExplorationContext context = new ExplorationContext(userQuery);
        ExplorationResult result = new ExplorationResult();

        try {
            // Build conversation history
            List<ConversationMessage> conversation = new ArrayList<>();
            conversation.add(new ConversationMessage("user", userQuery));

            int round = 0;
            while (round < MAX_ROUNDS && context.getToolCallCount() < MAX_TOOL_CALLS) {
                round++;
                LOG.info("Starting exploration round " + round);

                ExplorationRound explorationRound = new ExplorationRound("Round " + round);

                // Build prompt with full conversation history
                String prompt = buildConversationPrompt(conversation, context);
                String llmResponse = llmService.query(prompt);

                if (llmResponse == null) {
                    explorationRound.setLlmResponse("Failed to get response from LLM");
                    result.addRound(explorationRound);
                    break;
                }

                explorationRound.setLlmResponse(llmResponse);
                conversation.add(new ConversationMessage("assistant", llmResponse));

                // Parse tool calls from response
                List<ToolCallParser.ToolCall> toolCalls = toolCallParser.parseToolCalls(llmResponse);

                if (toolCalls.isEmpty()) {
                    LOG.info("No tool calls found in round " + round + ", checking if exploration is complete");

                    // Check if LLM indicates completion
                    if (llmResponse.toLowerCase().contains("exploration complete") ||
                            llmResponse.toLowerCase().contains("finished exploring") ||
                            llmResponse.toLowerCase().contains("summary:")) {
                        result.addRound(explorationRound);
                        break;
                    }

                    // Otherwise, prompt for tool calls explicitly
                    String toolPrompt = buildExplicitToolPrompt(context);
                    llmResponse = llmService.query(toolPrompt);

                    if (llmResponse != null) {
                        toolCalls = toolCallParser.parseToolCalls(llmResponse);
                        if (!toolCalls.isEmpty()) {
                            explorationRound.setLlmResponse(explorationRound.getLlmResponse() + "\n\n" + llmResponse);
                        }
                    }
                }

                // Execute tool calls and collect results
                StringBuilder toolResultsMessage = new StringBuilder();
                toolResultsMessage.append("Tool execution results:\n\n");

                for (ToolCallParser.ToolCall toolCall : toolCalls) {
                    if (context.getToolCallCount() >= MAX_TOOL_CALLS) {
                        break;
                    }

                    LOG.info("Executing tool: " + toolCall.getToolName());
                    ToolExecution execution = executeToolCall(toolCall);
                    explorationRound.addToolExecution(execution);
                    context.addToolExecution(execution);

                    // Format tool result for LLM
                    toolResultsMessage.append("### Tool: ").append(toolCall.getToolName()).append("\n");
                    toolResultsMessage.append("Parameters: ").append(toolCall.getParameters()).append("\n");
                    toolResultsMessage.append("Status: ").append(execution.isSuccess() ? "Success" : "Failed").append("\n");
                    toolResultsMessage.append("Result:\n").append(execution.getResult()).append("\n\n");
                }

                result.addRound(explorationRound);

                // Add tool results to conversation
                if (toolCalls.size() > 0) {
                    conversation.add(new ConversationMessage("tool_results", toolResultsMessage.toString()));
                }

                // Check if we should continue
                if (toolCalls.isEmpty() || context.getToolCallCount() >= MAX_TOOL_CALLS) {
                    LOG.info("Stopping exploration: " + (toolCalls.isEmpty() ? "No more tool calls" : "Max tool calls reached"));
                    break;
                }
            }

            // Check if total exploration results are under 1000 lines
            String fullExplorationContent = buildFullExplorationContent(conversation, context);
            int lineCount = countLines(fullExplorationContent);
            
            LOG.info("Total exploration content has " + lineCount + " lines");
            
            if (lineCount < 1000) {
                // Return the full exploration content as the summary
                LOG.info("Exploration results under 1000 lines, returning full content as summary");
                result.setSummary(fullExplorationContent);
            } else {
                // Generate final summary with full context
                LOG.info("Exploration results over 1000 lines, generating summary via LLM");
                String summaryPrompt = buildFinalSummaryPrompt(conversation, context);
                String summary = llmService.query(summaryPrompt);

                if (summary != null) {
                    result.setSummary(summary);
                } else {
                    result.setSummary("Failed to generate summary");
                }
            }

            result.setSuccess(true);

        } catch (Exception e) {
            LOG.error("Error during improved tool-based exploration", e);
            result.addError("Exploration error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Builds a conversation prompt that includes all previous messages and tool results.
     */
    private String buildConversationPrompt(List<ConversationMessage> conversation, ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        // Check if we have any tool results in the conversation
        boolean hasToolResults = conversation.stream()
            .anyMatch(msg -> msg.role.equals("tool_results"));
        
        // Enhanced system prompt
        prompt.append("You are an autonomous code exploration agent specialized in analyzing codebases.\n");
        prompt.append("Your approach: systematic (high-level→specific), efficient (minimal tool calls), thorough (verify findings).\n\n");
        
        // Tool information
        prompt.append("Available Tools:\n").append(toolRegistry.getToolsDescription()).append("\n");
        prompt.append("Progress: ").append(context.getToolCallCount()).append("/").append(MAX_TOOL_CALLS).append(" tool calls used\n\n");
        
        if (!hasToolResults) {
            // Initial exploration - no tool results yet
            prompt.append("""
                GOAL: Begin systematic exploration to understand the codebase structure relevant to the user's query.
                
                EXPECTED OUTPUT: 
                1. Query understanding (restate what you're looking for)
                2. Exploration strategy (1-2 sentences)
                3. Initial tool calls (2-4) to gather foundational information
                
                RESPONSE FORMAT:
                **Understanding the query**: [Restate in your own words what the user wants to know about the codebase]
                
                **Exploration strategy**: [How you'll approach finding this information]
                
                **Initial exploration**:
                ```json
                {
                  "reasoning": "What information this tool will provide",
                  "deepreasoning": "Why this is the right starting point and what you'll look for in the results",
                  "tool": "tool_name",
                  "parameters": {...}
                }
                ```
                
                FOCUS: Start broad to understand structure, then narrow based on query specifics.
                """);
        } else if (context.getToolCallCount() < MAX_TOOL_CALLS - 2) {
            // Mid-exploration - have some results, can do more
            prompt.append("""
                GOAL: Refine understanding by exploring specific findings from previous results.
                
                EXPECTED OUTPUT: Either:
                1. Additional tool calls (1-3) to fill knowledge gaps, OR
                2. A comprehensive summary if you have sufficient information
                
                RESPONSE FORMAT FOR TOOL CALLS:
                Brief analysis of what you've learned so far (1-2 sentences).
                Then new tool calls:
                ```json
                {
                  "reasoning": "What gap this fills",
                  "deepreasoning": "How this builds on finding X from previous results",
                  "tool": "tool_name",
                  "parameters": {...}
                }
                ```
                
                RESPONSE FORMAT FOR SUMMARY:
                Start with "EXPLORATION COMPLETE:" followed by your comprehensive findings.
                /no_think
                """);
        } else {
            // Near limit - time to conclude
            prompt.append("""
                GOAL: Synthesize findings into actionable insights for the user's query.
                
                EXPECTED OUTPUT: A comprehensive summary of your exploration.
                
                RESPONSE FORMAT:
                Start with "EXPLORATION COMPLETE:" then provide:
                - Direct answer to the query
                - Key findings with specific file/class/method references
                - How the discovered elements relate to each other
                
                NO MORE TOOL CALLS - focus on synthesis.
                """);
        }
        
        // Add conversation history
        prompt.append("\n\nConversation History:\n");
        for (ConversationMessage message : conversation) {
            switch (message.role) {
                case "user":
                    prompt.append("\nUSER QUERY: ").append(message.content).append("\n");
                    break;
                case "assistant":
                    prompt.append("\nYOUR EXPLORATION: ").append(message.content).append("\n");
                    break;
                case "tool_results":
                    prompt.append("\n[TOOL EXECUTION RESULTS]\n").append(message.content).append("\n");
                    break;
            }
        }
        
        // Action prompt based on stage
        prompt.append("\n");
        if (!hasToolResults) {
            prompt.append("Analyze the query and begin exploration:\n");
        } else if (context.getToolCallCount() < MAX_TOOL_CALLS - 2) {
            prompt.append("Based on results so far, continue exploration or summarize if complete:\n");
        } else {
            prompt.append("Synthesize all findings into a comprehensive summary:\n");
        }
        
        return prompt.toString();
    }

    /**
     * Builds an explicit prompt asking for tool calls.
     */
    private String buildExplicitToolPrompt(ExplorationContext context) {
        return String.format("""
                        You haven't generated any tool calls. Please generate specific tool calls to explore the code.
                        
                        Original query: %s
                        Tool calls so far: %d/%d
                        
                        Available tools:
                        %s
                        
                        Generate 1-3 tool calls in JSON format to continue the exploration.
                        """, context.getUserQuery(), context.getToolCallCount(), MAX_TOOL_CALLS,
                toolRegistry.getToolsDescription());
    }

    /**
     * Builds the final summary prompt with full conversation context.
     * This version focuses on providing concrete, actionable implementation details.
     */
    private String buildFinalSummaryPrompt(List<ConversationMessage> conversation, ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Based on the complete exploration, provide a CONCRETE IMPLEMENTATION GUIDE for the query.\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n\n");

        prompt.append("Full Exploration History:\n");
        for (ConversationMessage message : conversation) {
            if (message.role.equals("tool_results")) {
                prompt.append("\n[Tool Results]\n").append(message.content).append("\n");
            }
        }

        prompt.append("""
            
            Generate a CONTEXT REPORT for implementation:
            
            ## Query Understanding
            [2-3 sentences: what the query is asking for in the context of this codebase]
            
            ## Relevant Code Elements
            For each element found:
            - **File**: [complete absolute path as shown in tool results]
            - **Element**: ClassName#methodName
            - **What it does**: [current functionality]
            - **Key Code**: [relevant snippet showing current implementation]
            
            ## Related Code Snippets
            Format each related code section as:
            
            ### [Descriptive Title of What This Code Does]
            **File**: [complete absolute path]
            **Location**: ClassName#methodName (lines X-Y if available)
            ```java
            [actual code snippet]
            ```
            **Context**: [why this code is relevant to the query]
            
            ### [Next Related Code Section]
            **File**: [complete absolute path]
            **Location**: ClassName#methodName
            ```java
            [actual code snippet]
            ```
            **Context**: [why this is relevant]
            
            ## Code Structure & Relationships
            - **Inheritance**: X extends Y [with file paths]
            - **Calls**: A → B [with file paths]  
            - **Implements**: X implements Y [with file paths]
            - **Uses**: Dependencies between components
            
            ## Current Implementation Patterns
            - Design patterns observed
            - Naming conventions used
            - Error handling approach
            - Common utilities/helpers available
            
            ## Context Summary
            **Key Files**: [list with absolute paths]
            **Entry Points**: [where this functionality is accessed]
            **Related Tests**: [test files if found]
            
            RULES:
            - Use complete file paths exactly as shown in tool results
            - Each code snippet MUST show its file path and location
            - Include actual code snippets to show current state
            - Focus on understanding existing code, not changes
            - Provide context for how things currently work
            /no_think
            """);

        return prompt.toString();
    }
    /**
     * Executes a single tool call.
     */
    private ToolExecution executeToolCall(ToolCallParser.ToolCall toolCall) {
        String toolName = toolCall.getToolName();
        JsonObject parameters = toolCall.getParameters();

        // Add reasoning to parameters if available
        JsonObject enrichedParams = parameters.deepCopy();
        if (toolCall.getReasoning() != null) {
            enrichedParams.addProperty("reasoning", toolCall.getReasoning());
        }
        if (toolCall.getDeepReasoning() != null) {
            enrichedParams.addProperty("deepreasoning", toolCall.getDeepReasoning());
        }

        CodeExplorationTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return new ToolExecution(toolName, enrichedParams,
                    "Error: Unknown tool '" + toolName + "'", false);
        }

        try {
            CodeExplorationTool.ToolResult result = tool.execute(parameters);
            String resultContent = result.isSuccess() ? result.getContent() : result.getError();

            // NO TRUNCATION - include full content
            return new ToolExecution(toolName, enrichedParams, resultContent, result.isSuccess());
        } catch (Exception e) {
            LOG.error("Error executing tool: " + toolName, e);
            return new ToolExecution(toolName, enrichedParams,
                    "Error executing tool: " + e.getMessage(), false);
        }
    }

    /**
     * Async exploration with progress tracking.
     */
    public CompletableFuture<ExplorationResult> exploreWithToolsAsync(String userQuery,
                                                                      ProgressCallback callback) {
        CompletableFuture<ExplorationResult> future = new CompletableFuture<>();

        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Code Exploration: " + userQuery, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("Starting exploration...");
                    ExplorationResult result = exploreWithToolsWithProgress(userQuery, indicator, callback);
                    future.complete(result);
                } catch (Exception e) {
                    LOG.error("Error in async exploration", e);
                    ExplorationResult errorResult = new ExplorationResult();
                    errorResult.addError("Exploration failed: " + e.getMessage());
                    future.complete(errorResult);
                }
            }
        });

        return future;
    }

    /**
     * Internal method that handles progress updates during exploration.
     */
    private ExplorationResult exploreWithToolsWithProgress(String userQuery,
                                                           ProgressIndicator indicator,
                                                           ProgressCallback callback) {
        ExplorationContext context = new ExplorationContext(userQuery);
        ExplorationResult result = new ExplorationResult();

        try {
            List<ConversationMessage> conversation = new ArrayList<>();
            conversation.add(new ConversationMessage("user", userQuery));

            int round = 0;
            while (round < MAX_ROUNDS && context.getToolCallCount() < MAX_TOOL_CALLS && !indicator.isCanceled()) {
                round++;
                indicator.setText("Exploration round " + round + "...");

                ExplorationRound explorationRound = new ExplorationRound("Round " + round);

                String prompt = buildConversationPrompt(conversation, context);
                String llmResponse = llmService.query(prompt);

                if (llmResponse == null) {
                    explorationRound.setLlmResponse("Failed to get response from LLM");
                    result.addRound(explorationRound);
                    break;
                }

                explorationRound.setLlmResponse(llmResponse);
                conversation.add(new ConversationMessage("assistant", llmResponse));

                List<ToolCallParser.ToolCall> toolCalls = toolCallParser.parseToolCalls(llmResponse);

                if (toolCalls.isEmpty() && !isExplorationComplete(llmResponse)) {
                    String toolPrompt = buildExplicitToolPrompt(context);
                    llmResponse = llmService.query(toolPrompt);

                    if (llmResponse != null) {
                        toolCalls = toolCallParser.parseToolCalls(llmResponse);
                    }
                }

                StringBuilder toolResultsMessage = new StringBuilder();
                toolResultsMessage.append("Tool execution results:\n\n");

                for (ToolCallParser.ToolCall toolCall : toolCalls) {
                    if (indicator.isCanceled() || context.getToolCallCount() >= MAX_TOOL_CALLS) {
                        break;
                    }

                    indicator.setText2("Executing: " + toolCall.getToolName());

                    ToolExecution execution = executeToolCall(toolCall);
                    explorationRound.addToolExecution(execution);
                    context.addToolExecution(execution);

                    toolResultsMessage.append("### Tool: ").append(toolCall.getToolName()).append("\n");
                    toolResultsMessage.append("Parameters: ").append(toolCall.getParameters()).append("\n");
                    toolResultsMessage.append("Status: ").append(execution.isSuccess() ? "Success" : "Failed").append("\n");
                    toolResultsMessage.append("Result:\n").append(execution.getResult()).append("\n\n");

                    if (callback != null) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                callback.onToolExecution(execution));
                    }

                    double progress = (double) context.getToolCallCount() / MAX_TOOL_CALLS;
                    indicator.setFraction(progress);
                }

                result.addRound(explorationRound);

                if (toolCalls.size() > 0) {
                    conversation.add(new ConversationMessage("tool_results", toolResultsMessage.toString()));
                }

                if (callback != null) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            callback.onRoundComplete(explorationRound));
                }

                if (toolCalls.isEmpty() || context.getToolCallCount() >= MAX_TOOL_CALLS) {
                    break;
                }
            }

            if (!indicator.isCanceled()) {
                // Check if total exploration results are under 1000 lines
                String fullExplorationContent = buildFullExplorationContent(conversation, context);
                int lineCount = countLines(fullExplorationContent);
                
                LOG.info("Total exploration content has " + lineCount + " lines");
                
                if (lineCount < 1000) {
                    // Return the full exploration content as the summary
                    LOG.info("Exploration results under 1000 lines, returning full content as summary");
                    result.setSummary(fullExplorationContent);
                } else {
                    // Generate final summary with full context
                    LOG.info("Exploration results over 1000 lines, generating summary via LLM");
                    indicator.setText("Generating summary...");
                    String summaryPrompt = buildFinalSummaryPrompt(conversation, context);
                    String summary = llmService.query(summaryPrompt);

                    if (summary != null) {
                        result.setSummary(summary);
                    } else {
                        result.setSummary("Failed to generate summary");
                    }
                }
            }

            result.setSuccess(!indicator.isCanceled());

            if (callback != null) {
                ApplicationManager.getApplication().invokeLater(() ->
                        callback.onExplorationComplete(result));
            }

        } catch (Exception e) {
            LOG.error("Error during tool-based exploration", e);
            result.addError("Exploration error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Checks if the LLM response indicates exploration is complete.
     */
    private boolean isExplorationComplete(String response) {
        String lower = response.toLowerCase();
        return lower.contains("exploration complete") ||
                response.contains("EXPLORATION COMPLETE:") ||
                lower.contains("finished exploring") ||
                lower.contains("summary:") ||
                lower.contains("## executive summary") ||
                lower.contains("## key findings");
    }

    /**
     * Builds the full exploration content from conversation history.
     */
    private String buildFullExplorationContent(List<ConversationMessage> conversation, ExplorationContext context) {
        StringBuilder content = new StringBuilder();
        
        content.append("# Code Exploration Results\n\n");
        content.append("**Query**: ").append(context.getUserQuery()).append("\n\n");
        
        // Add all tool execution results
        content.append("## Tool Execution Results\n\n");
        
        for (ToolExecution execution : context.getAllExecutions()) {
            content.append("### ").append(execution.getToolName()).append("\n");
            content.append("**Parameters**: ").append(execution.getParameters()).append("\n");
            content.append("**Status**: ").append(execution.isSuccess() ? "Success" : "Failed").append("\n\n");
            content.append("**Result**:\n");
            content.append(execution.getResult()).append("\n\n");
        }
        
        // Add any LLM analysis from the conversation
        content.append("## Analysis\n\n");
        for (ConversationMessage message : conversation) {
            if (message.role.equals("assistant") && 
                (message.content.contains("EXPLORATION COMPLETE") || 
                 message.content.toLowerCase().contains("summary"))) {
                content.append(message.content).append("\n\n");
            }
        }
        
        return content.toString();
    }

    /**
     * Counts the number of lines in a string.
     */
    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n").length;
    }

    /**
     * Explores code and generates a comprehensive report for use in coding tasks.
     */
    public CodeExplorationReport exploreAndGenerateReport(String userQuery) {
        LOG.info("Starting exploration with report generation for: " + userQuery);

        ExplorationResult explorationResult = exploreWithTools(userQuery);

        CodeExplorationReportGenerator reportGenerator = new CodeExplorationReportGenerator(project);
        CodeExplorationReport report = reportGenerator.generateReport(userQuery, explorationResult);

        LOG.info("Generated report summary: " + report.getSummary());

        return report;
    }

    /**
     * Explores code asynchronously and generates a comprehensive report.
     */
    public CompletableFuture<CodeExplorationReport> exploreAndGenerateReportAsync(
            String userQuery, ProgressCallback callback) {

        return exploreWithToolsAsync(userQuery, callback)
                .thenApply(explorationResult -> {
                    CodeExplorationReportGenerator reportGenerator = new CodeExplorationReportGenerator(project);
                    return reportGenerator.generateReport(userQuery, explorationResult);
                });
    }

    /**
     * Represents a message in the conversation history.
     */
    private static class ConversationMessage {
        final String role;
        final String content;

        ConversationMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * Context for tracking exploration state.
     */
    private static class ExplorationContext {
        private final String userQuery;
        private final List<ToolExecution> toolExecutions = new ArrayList<>();

        public ExplorationContext(String userQuery) {
            this.userQuery = userQuery;
        }

        public String getUserQuery() {
            return userQuery;
        }

        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
        }

        public int getToolCallCount() {
            return toolExecutions.size();
        }

        public List<ToolExecution> getAllExecutions() {
            return new ArrayList<>(toolExecutions);
        }
    }

    /**
     * Callback interface for progress updates.
     */
    public interface ProgressCallback {
        void onToolExecution(ToolExecution execution);

        void onRoundComplete(ExplorationRound round);

        void onExplorationComplete(ExplorationResult result);
    }

    /**
     * Result of the exploration session.
     */
    public static class ExplorationResult {
        private final List<ExplorationRound> rounds = new ArrayList<>();
        private String summary;
        private boolean success = false;
        private final List<String> errors = new ArrayList<>();

        public void addRound(ExplorationRound round) {
            rounds.add(round);
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public void addError(String error) {
            errors.add(error);
        }

        public List<ExplorationRound> getRounds() {
            return rounds;
        }

        public String getSummary() {
            return summary;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    /**
     * Represents one round of exploration.
     */
    public static class ExplorationRound {
        private final String name;
        private String llmResponse;
        private final List<ToolExecution> toolExecutions = new ArrayList<>();

        public ExplorationRound(String name) {
            this.name = name;
        }

        public void setLlmResponse(String response) {
            this.llmResponse = response;
        }

        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
        }

        public String getName() {
            return name;
        }

        public String getLlmResponse() {
            return llmResponse;
        }

        public List<ToolExecution> getToolExecutions() {
            return toolExecutions;
        }
    }

    /**
     * Represents a tool execution.
     */
    public static class ToolExecution {
        private final String toolName;
        private final JsonObject parameters;
        private final String result;
        private final boolean success;

        public ToolExecution(String toolName, JsonObject parameters, String result, boolean success) {
            this.toolName = toolName;
            this.parameters = parameters;
            this.result = result;
            this.success = success;
        }

        public String getToolName() {
            return toolName;
        }

        public JsonObject getParameters() {
            return parameters;
        }

        public String getResult() {
            return result;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
