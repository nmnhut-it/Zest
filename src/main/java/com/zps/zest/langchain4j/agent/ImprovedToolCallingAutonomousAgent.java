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
    private static final int MAX_TOOL_CALLS = 20;
    private static final int MAX_ROUNDS = 10;
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
            
            // Generate final summary with full context
            String summaryPrompt = buildFinalSummaryPrompt(conversation, context);
            String summary = llmService.query(summaryPrompt);
            
            if (summary != null) {
                result.setSummary(summary);
            } else {
                result.setSummary("Failed to generate summary");
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
        
        // System prompt
        prompt.append("You are an autonomous code exploration agent with access to powerful tools for analyzing code.\n\n");
        prompt.append("Available Tools:\n").append(toolRegistry.getToolsDescription()).append("\n");
        prompt.append("Tool calls executed so far: ").append(context.getToolCallCount()).append("/").append(MAX_TOOL_CALLS).append("\n\n");
        
        // Instructions
        prompt.append("""
            Your task is to explore the codebase to answer the user's query. Use tool calls to gather information.
            
            Format your tool calls as JSON blocks:
            ```json
            {
              "tool": "tool_name",
              "parameters": {
                "param1": "value1",
                "param2": "value2"
              },
              "reasoning": "Why this tool call helps answer the query"
            }
            ```
            
            After each round of tool executions, analyze the results and decide what to explore next.
            When you have gathered enough information, provide a summary instead of more tool calls.
            
            Conversation history:
            """);
        
        // Add conversation history
        for (ConversationMessage message : conversation) {
            switch (message.role) {
                case "user":
                    prompt.append("\nUser: ").append(message.content).append("\n");
                    break;
                case "assistant":
                    prompt.append("\nAssistant: ").append(message.content).append("\n");
                    break;
                case "tool_results":
                    prompt.append("\n[Tool Results]\n").append(message.content).append("\n");
                    break;
            }
        }
        
        prompt.append("\nBased on the exploration so far, what should we explore next? Generate tool calls or provide a summary if exploration is complete.\n");
        
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
     */
    private String buildFinalSummaryPrompt(List<ConversationMessage> conversation, ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Based on the complete exploration, provide a comprehensive summary.\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n\n");
        
        prompt.append("Full Exploration History:\n");
        for (ConversationMessage message : conversation) {
            if (message.role.equals("tool_results")) {
                prompt.append("\n[Tool Results]\n").append(message.content).append("\n");
            }
        }
        
        prompt.append("""
            
            Please provide a comprehensive summary in the following format:
            
            ## Executive Summary
            [2-3 paragraph overview of findings relevant to the original query]
            
            ## Key Code Elements
            [List the most important classes, methods, and relationships discovered]
            
            ## Architecture Insights
            [Architectural patterns and design decisions observed]
            
            ## Implementation Details
            [Critical implementation details and dependencies]
            
            ## Recommendations
            [Next steps for further exploration or development]
            """);
        
        return prompt.toString();
    }
    
    /**
     * Executes a single tool call.
     */
    private ToolExecution executeToolCall(ToolCallParser.ToolCall toolCall) {
        String toolName = toolCall.getToolName();
        JsonObject parameters = toolCall.getParameters();
        
        CodeExplorationTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return new ToolExecution(toolName, parameters, 
                "Error: Unknown tool '" + toolName + "'", false);
        }
        
        try {
            CodeExplorationTool.ToolResult result = tool.execute(parameters);
            String resultContent = result.getContent();
            
            // Truncate if too long but keep more content than before
            if (resultContent.length() > MAX_RESULT_LENGTH) {
                resultContent = resultContent.substring(0, MAX_RESULT_LENGTH) + "\n... [truncated]";
            }
            
            return new ToolExecution(toolName, parameters, resultContent, result.isSuccess());
        } catch (Exception e) {
            LOG.error("Error executing tool: " + toolName, e);
            return new ToolExecution(toolName, parameters, 
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
                indicator.setText("Generating summary...");
                String summaryPrompt = buildFinalSummaryPrompt(conversation, context);
                String summary = llmService.query(summaryPrompt);
                
                if (summary != null) {
                    result.setSummary(summary);
                } else {
                    result.setSummary("Failed to generate summary");
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
               lower.contains("finished exploring") ||
               lower.contains("summary:") ||
               lower.contains("## executive summary") ||
               lower.contains("## key findings");
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
        
        public List<ExplorationRound> getRounds() { return rounds; }
        public String getSummary() { return summary; }
        public boolean isSuccess() { return success; }
        public List<String> getErrors() { return errors; }
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
        
        public String getName() { return name; }
        public String getLlmResponse() { return llmResponse; }
        public List<ToolExecution> getToolExecutions() { return toolExecutions; }
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
        
        public String getToolName() { return toolName; }
        public JsonObject getParameters() { return parameters; }
        public String getResult() { return result; }
        public boolean isSuccess() { return success; }
    }
}
