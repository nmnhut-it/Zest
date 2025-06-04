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
    
    // Configuration - now using centralized constants
    public static final int MAX_TOOL_CALLS = AgentConfiguration.MAX_TOOL_CALLS;
    public static final int MAX_ROUNDS = AgentConfiguration.MAX_ROUNDS;
    
    public ToolCallingAutonomousAgent(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.toolCallParser = new ToolCallParser();
        LOG.info("Initialized ToolCallingAutonomousAgent");
    }

    /**
     * Explores code asynchronously and generates a comprehensive report.
     * 
     * @param userQuery The exploration query
     * @param callback Progress callback
     * @return Future containing the comprehensive report
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
            You are an autonomous code exploration agent that explores the codebase through tool calls.
            
            User Query: %s
            
            ## CRITICAL INSTRUCTIONS:
            
            YOU ARE THE AGENT - You execute tools, you don't give advice to others.
            - First REASON about what to explore
            - Then ACT by generating tool calls
            - Each tool call must be INDEPENDENT (don't use results from one in another)
            - Use REAL parameters, never placeholders
            - Follow EXACT JSON syntax - no comments inside JSON blocks
            
            ## CONFIGURATION LIMITS:
            - Tool calls to generate this round: %d
            - Maximum total tool calls: %d
            - Current tool calls used: %d
            
            Available Tools:
            %s
            
            ## EXECUTION PATTERN:
            
            1. **REASON**: Analyze the query and identify what aspects to explore
            2. **ACT**: Generate independent tool calls that explore different aspects
            
            ## BAD EXAMPLE (DO NOT DO THIS):
            - Don't describe what you would do
            - Don't use placeholder values
            - Don't add comments in JSON
            
            Wrong:
            ```json
            {
              "tool": "find_by_name",
              "parameters": {"name": "SomeClass"}
            }
            ```
            
            ## GOOD EXAMPLE (DO THIS):
            
            REASONING: To understand how leaderboards work, I need to:
            1. Search for code containing leaderboard logic and scoring
            2. Find specific classes with "Leaderboard" in their name
            
            ACTIONS:
            ```json
            {
              "tool": "search_code",
              "parameters": {"query": "leaderboard scoring ranking system"},
              "reasoning": "Finding code snippets that implement leaderboard functionality"
            }
            ```
            ```json
            {
              "tool": "find_by_name", 
              "parameters": {"name": "Leaderboard"},
              "reasoning": "Looking for classes with Leaderboard in the name"
            }
            ```
            
            Based on the query "%s":
            1. First, explain your reasoning about what to explore
            2. Then, generate %d independent tool calls with real parameters
            3. Use proper JSON syntax without any comments
            """, 
            userQuery, 
            AgentConfiguration.INITIAL_TOOL_CALLS,
            MAX_TOOL_CALLS,
            context.getToolCallCount(),
            toolRegistry.getToolsDescription(), 
            userQuery,
            AgentConfiguration.INITIAL_TOOL_CALLS);
    }
    
    /**
     * Builds the exploration prompt for subsequent rounds.
     */
    private String buildExplorationPrompt(ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("## CONTINUE EXPLORATION - REASON THEN ACT ##\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n");
        prompt.append("Tool calls used: ").append(context.getToolCallCount()).append("/").append(MAX_TOOL_CALLS).append("\n\n");
        
        prompt.append("## CONFIGURATION:\n");
        prompt.append("- Tool calls to generate this round: ").append(AgentConfiguration.TOOLS_PER_ROUND).append("\n");
        prompt.append("- Remaining tool calls available: ").append(MAX_TOOL_CALLS - context.getToolCallCount()).append("\n");
        prompt.append("- Each call must be INDEPENDENT\n");
        prompt.append("- Use EXACT JSON syntax - no comments allowed\n\n");
        
        prompt.append("## PREVIOUS DISCOVERIES (REAL DATA):\n");
        for (ToolExecution execution : context.getRecentExecutions(5)) {
            prompt.append("\nTool: ").append(execution.toolName).append("\n");
            prompt.append("Result: ").append(truncate(execution.result, 300)).append("\n");
            
            // Extract and highlight concrete values
            if (execution.success && execution.result != null) {
                prompt.append("CONCRETE VALUES YOU MUST USE:\n");
                
                // Extract file paths
                java.util.regex.Pattern pathPattern = java.util.regex.Pattern.compile("src[/\\\\]\\S+\\.java");
                java.util.regex.Matcher matcher = pathPattern.matcher(execution.result);
                while (matcher.find()) {
                    prompt.append("  - File path: ").append(matcher.group()).append("\n");
                }
                
                // Extract class names
                java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile("\\b([a-z]+\\.)+[A-Z][a-zA-Z0-9]+\\b");
                matcher = classPattern.matcher(execution.result);
                Set<String> classes = new HashSet<>();
                while (matcher.find()) {
                    classes.add(matcher.group());
                }
                classes.forEach(cls -> prompt.append("  - Class: ").append(cls).append("\n"));
            }
        }
        
        prompt.append("\n## REASON THEN ACT:\n");
        prompt.append(String.format("""
            
            1. REASONING - Analyze what you've learned:
               - What concrete elements did you discover?
               - What aspects of "%s" haven't been explored?
               - What specific files/classes should you examine next?
            
            2. ACTIONS - Generate %d independent tool calls:
               - MUST use the EXACT file paths and class names shown above
               - Each call explores a DIFFERENT aspect
               - NO placeholders like "path/to/file" or "SomeClass"
               - STRICT JSON syntax - no comments inside JSON blocks
            
            GOOD Example:
            
            REASONING: I found LeaderboardService at src/main/java/com/example/LeaderboardService.java.
            Now I should: 1) Read this file to understand the implementation, and 2) Search for 
            how scores are calculated (different aspect).
            
            ACTIONS:
            ```json
            {
              "tool": "read_file",
              "parameters": {"filePath": "src/main/java/com/example/LeaderboardService.java"},
              "reasoning": "Reading the LeaderboardService implementation I discovered"
            }
            ```
            ```json
            {
              "tool": "search_code",
              "parameters": {"query": "score calculation points algorithm"},
              "reasoning": "Searching for scoring logic (independent from file reading)"
            }
            ```
            
            BAD Example (DO NOT DO THIS):
            Wrong parameter value:
            ```json
            {
              "tool": "read_file",
              "parameters": {"filePath": "the leaderboard file"},
              "reasoning": "Reading the file"
            }
            ```
            
            Wrong JSON syntax with comments:
            ```json
            {
              "tool": "read_file",
              "parameters": {"filePath": "src/main/java/Example.java"}, // This is wrong
              "reasoning": "Reading file"
            }
            ```
            
            Now provide your reasoning and then generate %d tool calls with proper JSON syntax:
            """, 
            context.getUserQuery(),
            AgentConfiguration.TOOLS_PER_ROUND,
            AgentConfiguration.TOOLS_PER_ROUND));
        
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
        
        prompt.append("\nExploration Balance: ").append(context.getExplorationStats()).append("\n");
        
        prompt.append("\nKey Discoveries:\n");
        for (ToolExecution execution : context.getKeyFindings()) {
            prompt.append("\n").append(execution.toolName).append(" - ");
            prompt.append(truncate(execution.result, 300)).append("\n");
        }
        
        prompt.append("""
            
            ## SUMMARY GUIDELINES:
            
            Focus on answering the original query directly. Structure your summary to be:
            1. **Actionable**: Provide specific findings, not general observations
            2. **Hierarchical**: Start with the most important discoveries
            3. **Connected**: Show how different pieces relate to each other
            4. **Practical**: Include code examples where relevant
            5. **Test-Informed**: Incorporate insights from test files about expected behavior
            
            Please provide a comprehensive summary in the following format:
            
            ## Executive Summary
            [2-3 paragraph overview directly answering the user's query]
            [Highlight the most important findings first]
            [Make it clear and actionable]
            
            ## Key Code Elements
            [List the most important classes, methods, and relationships discovered]
            [Group by functional area or component]
            [Include brief descriptions of their roles]
            [Note which elements have comprehensive test coverage]
            
            ## Architecture Insights
            [Architectural patterns and design decisions observed]
            [How components interact]
            [Any notable design patterns used]
            [Testing strategies observed (unit tests, integration tests, etc.)]
            
            ## Implementation Details
            [Critical implementation details and dependencies]
            [Important algorithms or business logic]
            [Configuration or setup requirements]
            [Key test scenarios that validate the implementation]
            
            ## Code Examples
            [If relevant, include 1-2 short code snippets that illustrate key concepts]
            [Include examples from both source and test code]
            [Show how the code is meant to be used based on tests]
            
            ## Test Coverage Insights
            [What aspects are well-tested]
            [Test patterns and strategies used]
            [Key test scenarios that demonstrate expected behavior]
            [Any gaps in test coverage noticed]
            
            ## Recommendations
            [Specific next steps for further exploration or development]
            [Potential areas of concern or improvement]
            [Suggested tools or approaches for the user's task]
            [Testing recommendations if applicable]
            
            Remember: The summary should directly address the user's original query and provide practical, actionable insights.
            Include insights from both source code and tests to give a complete picture.
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
        public final List<ToolExecution> toolExecutions = new ArrayList<>();
        public final List<ToolCallParser.ToolCall> plannedTools = new ArrayList<>();
        public final Set<String> exploredElements = new HashSet<>();
        public final Set<String> exploredFiles = new HashSet<>();
        public final Map<String, Set<String>> discoveredRelationships = new HashMap<>();
        
        // Track source vs test exploration
        public final Set<String> exploredSourceFiles = new HashSet<>();
        public final Set<String> exploredTestFiles = new HashSet<>();
        public final Set<String> exploredSourceElements = new HashSet<>();
        public final Set<String> exploredTestElements = new HashSet<>();
        
        public ExplorationContext(String userQuery) {
            this.userQuery = userQuery;
        }
        
        public String getUserQuery() {
            return userQuery;
        }
        
        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
            
            // Track explored elements and files
            if (execution.success && execution.result != null) {
                // Extract file paths and categorize as source or test
                if (execution.toolName.equals("read_file") && execution.parameters.has("filePath")) {
                    String filePath = execution.parameters.get("filePath").getAsString();
                    exploredFiles.add(filePath);
                    
                    // Categorize as source or test
                    if (isTestFile(filePath)) {
                        exploredTestFiles.add(filePath);
                    } else {
                        exploredSourceFiles.add(filePath);
                    }
                }
                
                // Extract element IDs from results
                String result = execution.result;
                
                // Check if this result is from test context
                boolean isTestContext = result.contains("Test") || result.contains("test/") || 
                                       result.contains("/test/") || result.contains("@Test");
                
                // Pattern for class names like com.example.ClassName
                java.util.regex.Pattern classPattern = 
                    java.util.regex.Pattern.compile("\\b([a-z]+\\.)+[A-Z][a-zA-Z0-9]+\\b");
                java.util.regex.Matcher matcher = classPattern.matcher(result);
                while (matcher.find()) {
                    String element = matcher.group();
                    exploredElements.add(element);
                    
                    // Categorize element
                    if (isTestContext || element.contains("Test") || element.endsWith("Test")) {
                        exploredTestElements.add(element);
                    } else {
                        exploredSourceElements.add(element);
                    }
                }
                
                // Pattern for method references like ClassName#methodName
                java.util.regex.Pattern methodPattern = 
                    java.util.regex.Pattern.compile("\\b[A-Z][a-zA-Z0-9]+#[a-z][a-zA-Z0-9]+\\b");
                matcher = methodPattern.matcher(result);
                while (matcher.find()) {
                    String element = matcher.group();
                    exploredElements.add(element);
                    
                    // Categorize element
                    if (isTestContext || element.contains("Test")) {
                        exploredTestElements.add(element);
                    } else {
                        exploredSourceElements.add(element);
                    }
                }
                
                // Track relationships
                if (execution.toolName.equals("find_relationships") && execution.parameters.has("elementId")) {
                    String elementId = execution.parameters.get("elementId").getAsString();
                    Set<String> related = discoveredRelationships.computeIfAbsent(elementId, k -> new HashSet<>());
                    
                    // Extract related elements from results
                    String[] lines = result.split("\n");
                    for (String line : lines) {
                        if (line.trim().startsWith("- ") && !line.contains("None found")) {
                            related.add(line.substring(2).trim());
                        }
                    }
                }
            }
        }
        
        /**
         * Determines if a file path is a test file
         */
        private boolean isTestFile(String filePath) {
            return filePath.contains("/test/") || filePath.contains("\\test\\") ||
                   filePath.contains("/tests/") || filePath.contains("\\tests\\") ||
                   filePath.endsWith("Test.java") || filePath.endsWith("Tests.java") ||
                   filePath.endsWith("Spec.java") || filePath.contains("test.java");
        }
        
        /**
         * Gets the current source vs test exploration ratio
         */
        public double getSourceToTestRatio() {
            int totalSource = exploredSourceFiles.size() + exploredSourceElements.size();
            int totalTest = exploredTestFiles.size() + exploredTestElements.size();
            
            if (totalTest == 0) return totalSource > 0 ? 1.0 : 0.0;
            return (double) totalSource / (totalSource + totalTest);
        }
        
        /**
         * Checks if we need more test exploration to maintain balance
         */
        public boolean needsMoreTestExploration() {
            double currentRatio = getSourceToTestRatio();
            // If we're above 75% source (leaving less than 25% for tests), we need more tests
            return currentRatio > AgentConfiguration.SourceTestBalance.SOURCE_RATIO_UPPER_THRESHOLD && 
                   exploredTestFiles.size() < AgentConfiguration.SourceTestBalance.MIN_TEST_FILES;
        }
        
        /**
         * Checks if we need more source exploration to maintain balance
         */
        public boolean needsMoreSourceExploration() {
            double currentRatio = getSourceToTestRatio();
            // If we're below 65% source, we need more source exploration
            return currentRatio < AgentConfiguration.SourceTestBalance.SOURCE_RATIO_LOWER_THRESHOLD;
        }
        
        /**
         * Gets exploration statistics
         */
        public String getExplorationStats() {
            return String.format("Source files: %d, Test files: %d, Source elements: %d, Test elements: %d (%.0f%% source)",
                exploredSourceFiles.size(), exploredTestFiles.size(),
                exploredSourceElements.size(), exploredTestElements.size(),
                getSourceToTestRatio() * 100);
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
        
        /**
         * Gets unexplored elements that were discovered but not yet examined
         */
        public Set<String> getUnexploredElements() {
            Set<String> unexplored = new HashSet<>(exploredElements);
            
            // Remove elements we've already read files for or got detailed info
            for (ToolExecution execution : toolExecutions) {
                if (execution.success) {
                    if (execution.toolName.equals("read_file") && execution.parameters.has("filePath")) {
                        String filePath = execution.parameters.get("filePath").getAsString();
                        unexplored.removeIf(element -> filePath.contains(element.replace(".", "/")));
                    } else if (execution.toolName.equals("get_class_info") && execution.parameters.has("className")) {
                        unexplored.remove(execution.parameters.get("className").getAsString());
                    }
                }
            }
            
            return unexplored;
        }
        
        /**
         * Suggests next elements to explore based on relationships
         */
        public List<String> getSuggestedNextElements() {
            List<String> suggestions = new ArrayList<>();
            
            // Add unexplored related elements
            for (Map.Entry<String, Set<String>> entry : discoveredRelationships.entrySet()) {
                for (String related : entry.getValue()) {
                    if (!exploredElements.contains(related)) {
                        suggestions.add(related);
                    }
                }
            }
            
            // Add unexplored elements from same packages
            Set<String> packages = new HashSet<>();
            for (String element : exploredElements) {
                int lastDot = element.lastIndexOf('.');
                if (lastDot > 0) {
                    packages.add(element.substring(0, lastDot));
                }
            }
            
            return suggestions.stream().distinct().limit(5).toList();
        }
        
        /**
         * Check if we've explored enough based on query type
         */
        public boolean hasAdequateCoverage() {
            // For simple queries, fewer tools might be enough
            if (userQuery.split("\\s+").length < 5) {
                return toolExecutions.size() >= 5 && !getKeyFindings().isEmpty();
            }
            
            // For complex queries, need more exploration
            return toolExecutions.size() >= 10 || 
                   (exploredElements.size() >= 5 && exploredFiles.size() >= 3);
        }
        
        /**
         * Gets suggested test files based on explored source files
         */
        public List<String> getSuggestedTestFiles() {
            List<String> suggestions = new ArrayList<>();
            
            for (String sourceFile : exploredSourceFiles) {
                // Generate possible test file names
                String baseName = sourceFile.replace(".java", "");
                suggestions.add(baseName + "Test.java");
                suggestions.add(baseName + "Tests.java");
                suggestions.add(baseName + "Spec.java");
                
                // Also suggest test directory equivalent
                String testPath = sourceFile
                    .replace("/src/main/", "/src/test/")
                    .replace("\\src\\main\\", "\\src\\test\\");
                suggestions.add(testPath);
            }
            
            // Also look for test files for explored elements
            for (String element : exploredSourceElements) {
                if (!element.contains("Test") && element.contains(".")) {
                    String className = element.substring(element.lastIndexOf('.') + 1);
                    suggestions.add(className + "Test");
                    suggestions.add(className + "Tests");
                    suggestions.add(className + "Spec");
                }
            }
            
            return suggestions.stream()
                .distinct()
                .filter(s -> !exploredTestFiles.contains(s))
                .limit(5)
                .toList();
        }
    }

}
