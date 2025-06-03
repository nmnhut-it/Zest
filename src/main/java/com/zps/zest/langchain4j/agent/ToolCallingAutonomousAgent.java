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
import java.util.concurrent.atomic.AtomicReference;
/**
 * Enhanced autonomous code exploration agent that uses tool calls instead of questions.
 */
@Service(Service.Level.PROJECT)
public final class ToolCallingAutonomousAgent {
    private static final Logger LOG = Logger.getInstance(ToolCallingAutonomousAgent.class);
    
    private final Project project;
    private final LLMService llmService;
    private final CodeExplorationToolRegistry toolRegistry;
    private final ToolCallParser toolCallParser;
    
    // Configuration
    private static final int MAX_TOOL_CALLS = 20;
    private static final int MAX_ROUNDS = 5;
    
    public ToolCallingAutonomousAgent(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.toolCallParser = new ToolCallParser();
        LOG.info("Initialized ToolCallingAutonomousAgent");
    }
    
    /**
     * Starts an autonomous exploration session using tool calls.
     * This method is thread-safe and can be called from any thread.
     */
    public ExplorationResult exploreWithTools(String userQuery) {
        LOG.info("Starting tool-based exploration for: " + userQuery);
        
        ExplorationContext context = new ExplorationContext(userQuery);
        ExplorationResult result = new ExplorationResult();
        
        try {
            // Initial planning phase - can be done in any thread
            String planningPrompt = buildPlanningPrompt(userQuery, context);
            String planningResponse = llmService.query(planningPrompt);
            
            if (planningResponse == null) {
                result.addError("Failed to get initial planning response from LLM");
                return result;
            }
            
            result.addRound(new ExplorationRound("Planning", planningResponse, Collections.emptyList()));
            
            // Extract initial tool calls from planning
            List<ToolCallParser.ToolCall> plannedCalls = toolCallParser.parseToolCalls(planningResponse);
            context.addPlannedTools(plannedCalls);
            
            // Exploration rounds
            int round = 0;
            while (round < MAX_ROUNDS && context.hasMoreToExplore()) {
                round++;
                
                ExplorationRound explorationRound = new ExplorationRound("Round " + round);
                
                // Get next batch of tool calls
                String explorationPrompt = buildExplorationPrompt(context);
                String llmResponse = llmService.query(explorationPrompt);
                
                if (llmResponse == null) {
                    explorationRound.setLlmResponse("Failed to get response from LLM");
                    result.addRound(explorationRound);
                    break;
                }
                
                explorationRound.setLlmResponse(llmResponse);
                
                // Parse and execute tool calls
                List<ToolCallParser.ToolCall> toolCalls = toolCallParser.parseToolCalls(llmResponse);
                
                for (ToolCallParser.ToolCall toolCall : toolCalls) {
                    if (context.getToolCallCount() >= MAX_TOOL_CALLS) {
                        explorationRound.addToolExecution(new ToolExecution(
                            toolCall.getToolName(),
                            toolCall.getParameters(),
                            "Skipped: Maximum tool calls reached",
                            false
                        ));
                        break;
                    }
                    
                    ToolExecution execution = executeToolCall(toolCall);
                    explorationRound.addToolExecution(execution);
                    context.addToolExecution(execution);
                }
                
                result.addRound(explorationRound);
                
                // Check if we've discovered enough or should continue
                if (toolCalls.isEmpty() || context.getToolCallCount() >= MAX_TOOL_CALLS) {
                    break;
                }
            }
            
            // Generate final summary
            String summaryPrompt = buildSummaryPrompt(context);
            String summary = llmService.query(summaryPrompt);
            
            if (summary != null) {
                result.setSummary(summary);
            } else {
                result.setSummary("Failed to generate summary");
            }
            
            result.setSuccess(true);
            
        } catch (Exception e) {
            LOG.error("Error during tool-based exploration", e);
            result.addError("Exploration error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Starts an autonomous exploration session with progress tracking.
     * This method handles IntelliJ's progress API properly.
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
     * Internal method that handles progress updates.
     */
    private ExplorationResult exploreWithToolsWithProgress(String userQuery, 
                                                          ProgressIndicator indicator,
                                                          ProgressCallback callback) {
        ExplorationContext context = new ExplorationContext(userQuery);
        ExplorationResult result = new ExplorationResult();
        
        try {
            // Planning phase
            indicator.setText("Planning exploration strategy...");
            String planningPrompt = buildPlanningPrompt(userQuery, context);
            String planningResponse = llmService.query(planningPrompt);
            
            if (planningResponse == null) {
                result.addError("Failed to get initial planning response from LLM");
                return result;
            }
            
            result.addRound(new ExplorationRound("Planning", planningResponse, Collections.emptyList()));
            
            // Extract initial tool calls
            List<ToolCallParser.ToolCall> plannedCalls = toolCallParser.parseToolCalls(planningResponse);
            context.addPlannedTools(plannedCalls);
            
            // Exploration rounds
            int round = 0;
            while (round < MAX_ROUNDS && context.hasMoreToExplore() && !indicator.isCanceled()) {
                round++;
                
                indicator.setText("Exploration round " + round + "...");
                ExplorationRound explorationRound = new ExplorationRound("Round " + round);
                
                // Get next batch of tool calls
                String explorationPrompt = buildExplorationPrompt(context);
                String llmResponse = llmService.query(explorationPrompt);
                
                if (llmResponse == null) {
                    explorationRound.setLlmResponse("Failed to get response from LLM");
                    result.addRound(explorationRound);
                    break;
                }
                
                explorationRound.setLlmResponse(llmResponse);
                
                // Parse and execute tool calls
                List<ToolCallParser.ToolCall> toolCalls = toolCallParser.parseToolCalls(llmResponse);
                
                for (ToolCallParser.ToolCall toolCall : toolCalls) {
                    if (indicator.isCanceled()) {
                        break;
                    }
                    
                    if (context.getToolCallCount() >= MAX_TOOL_CALLS) {
                        explorationRound.addToolExecution(new ToolExecution(
                            toolCall.getToolName(),
                            toolCall.getParameters(),
                            "Skipped: Maximum tool calls reached",
                            false
                        ));
                        break;
                    }
                    
                    indicator.setText2("Executing: " + toolCall.getToolName());
                    
                    // Execute tool call
                    ToolExecution execution = executeToolCall(toolCall);
                    explorationRound.addToolExecution(execution);
                    context.addToolExecution(execution);
                    
                    // Notify callback if provided
                    if (callback != null) {
                        ApplicationManager.getApplication().invokeLater(() -> 
                            callback.onToolExecution(execution));
                    }
                    
                    // Update progress
                    double progress = (double) context.getToolCallCount() / MAX_TOOL_CALLS;
                    indicator.setFraction(progress);
                }
                
                result.addRound(explorationRound);
                
                if (toolCalls.isEmpty() || context.getToolCallCount() >= MAX_TOOL_CALLS) {
                    break;
                }
            }
            
            // Generate summary
            if (!indicator.isCanceled()) {
                indicator.setText("Generating summary...");
                String summaryPrompt = buildSummaryPrompt(context);
                String summary = llmService.query(summaryPrompt);
                
                if (summary != null) {
                    result.setSummary(summary);
                } else {
                    result.setSummary("Failed to generate summary");
                }
            }
            
            result.setSuccess(!indicator.isCanceled());
            
        } catch (Exception e) {
            LOG.error("Error during tool-based exploration", e);
            result.addError("Exploration error: " + e.getMessage());
        }
        
        return result;
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
            return new ToolExecution(toolName, parameters, 
                result.getContent(), result.isSuccess());
        } catch (Exception e) {
            LOG.error("Error executing tool: " + toolName, e);
            return new ToolExecution(toolName, parameters, 
                "Error executing tool: " + e.getMessage(), false);
        }
    }
    
    /**
     * Builds the initial planning prompt.
     */
    private String buildPlanningPrompt(String userQuery, ExplorationContext context) {
        return String.format("""
            You are an autonomous code exploration agent with access to powerful tools for analyzing code.
            
            User Query: %s
            
            Available Tools:
            %s
            
            Your task is to plan an exploration strategy using these tools. Generate a sequence of tool calls
            that will help you thoroughly understand the code related to the user's query.
            
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
            
            Start with high-level discovery (find_by_name, search_code) then drill down into specifics
            (read_file, find_methods, find_relationships).
            
            Generate 3-5 initial tool calls to begin the exploration.
            """, userQuery, toolRegistry.getToolsDescription());
    }
    
    /**
     * Builds the exploration prompt for subsequent rounds.
     */
    private String buildExplorationPrompt(ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Continue exploring based on what you've discovered so far.\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n\n");
        
        prompt.append("Previous Tool Executions:\n");
        for (ToolExecution execution : context.getRecentExecutions(5)) {
            prompt.append("\nTool: ").append(execution.toolName).append("\n");
            prompt.append("Parameters: ").append(execution.parameters).append("\n");
            prompt.append("Result: ").append(truncate(execution.result, 500)).append("\n");
        }
        
        prompt.append("\nBased on these results, what should we explore next? ");
        prompt.append("Generate additional tool calls to deepen understanding.\n\n");
        
        prompt.append("Available Tools:\n").append(toolRegistry.getToolsDescription()).append("\n");
        
        prompt.append("""
            Generate tool calls that:
            1. Follow up on interesting findings
            2. Explore relationships and dependencies
            3. Get implementation details
            4. Find usage patterns
            
            Format as JSON blocks as before. Generate 2-5 tool calls.
            """);
        
        return prompt.toString();
    }
    
    /**
     * Builds the final summary prompt.
     */
    private String buildSummaryPrompt(ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Summarize the code exploration findings.\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n\n");
        
        prompt.append("Tools Used (").append(context.getToolCallCount()).append(" calls):\n");
        Map<String, Integer> toolUsage = context.getToolUsageStats();
        for (Map.Entry<String, Integer> entry : toolUsage.entrySet()) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" times\n");
        }
        
        prompt.append("\nKey Discoveries:\n");
        for (ToolExecution execution : context.getKeyFindings()) {
            prompt.append("\n").append(execution.toolName).append(" - ");
            prompt.append(truncate(execution.result, 300)).append("\n");
        }
        
        prompt.append("""
            
            Please provide a comprehensive summary in the following format:
            
            ## Executive Summary
            [2-3 paragraph overview of findings]
            
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
    
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
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
     * Context for tracking exploration state.
     */
    private static class ExplorationContext {
        private final String userQuery;
        private final List<ToolExecution> toolExecutions = new ArrayList<>();
        private final List<ToolCallParser.ToolCall> plannedTools = new ArrayList<>();
        private final Set<String> exploredElements = new HashSet<>();
        
        public ExplorationContext(String userQuery) {
            this.userQuery = userQuery;
        }
        
        public String getUserQuery() {
            return userQuery;
        }
        
        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
        }
        
        public void addPlannedTools(List<ToolCallParser.ToolCall> tools) {
            plannedTools.addAll(tools);
        }
        
        public boolean hasMoreToExplore() {
            return toolExecutions.size() < MAX_TOOL_CALLS;
        }
        
        public int getToolCallCount() {
            return toolExecutions.size();
        }
        
        public List<ToolExecution> getRecentExecutions(int count) {
            int start = Math.max(0, toolExecutions.size() - count);
            return toolExecutions.subList(start, toolExecutions.size());
        }
        
        public Map<String, Integer> getToolUsageStats() {
            Map<String, Integer> stats = new HashMap<>();
            for (ToolExecution execution : toolExecutions) {
                stats.merge(execution.toolName, 1, Integer::sum);
            }
            return stats;
        }
        
        public List<ToolExecution> getKeyFindings() {
            // Return successful executions with substantial results
            return toolExecutions.stream()
                .filter(e -> e.success && e.result != null && e.result.length() > 100)
                .limit(10)
                .toList();
        }
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
        
        // Getters
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
        
        public ExplorationRound(String name, String llmResponse, List<ToolExecution> executions) {
            this.name = name;
            this.llmResponse = llmResponse;
            this.toolExecutions.addAll(executions);
        }
        
        public void setLlmResponse(String response) {
            this.llmResponse = response;
        }
        
        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
        }
        
        // Getters
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
        
        // Getters
        public String getToolName() { return toolName; }
        public JsonObject getParameters() { return parameters; }
        public String getResult() { return result; }
        public boolean isSuccess() { return success; }
    }
}
