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
    private static final int MAX_TOOL_CALLS = AgentConfiguration.MAX_TOOL_CALLS;
    private static final int MAX_ROUNDS = AgentConfiguration.MAX_ROUNDS;
    
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
            You are an autonomous code exploration agent with access to powerful tools for analyzing code.
            
            User Query: %s
            
            Available Tools:
            %s
            
            ## EXPLORATION STRATEGY GUIDELINES:
            
            ### 1. Tool Execution Order (IMPORTANT - Follow this sequence):
            
            **Phase 1 - Discovery (Start Here):**
            - Use `search_code` FIRST for semantic/conceptual queries (e.g., "authentication logic", "payment processing")
            - Use `find_by_name` FIRST for specific names (e.g., "UserService", "calculateTotal")
            - These tools are fast and give you entry points into the codebase
            
            **Phase 2 - Context Building:**
            - Use `get_current_context` to understand the active file/location
            - Use `list_files_in_directory` to explore package structures
            - Use `find_similar` to discover related code patterns
            
            **Phase 3 - Deep Analysis:**
            - Use `read_file` to examine specific implementations
            - Use `get_class_info` for detailed class structure
            - Use `find_methods` to explore method signatures
            
            **Phase 4 - Relationship Mapping:**
            - Use `find_relationships` to understand dependencies
            - Use `find_callers` to trace execution paths
            - Use `find_implementations` for interface/abstract class exploration
            - Use `find_usages` to see where elements are referenced
            
            ### 2. Source vs Test Balance (CRITICAL):
            
            **Maintain a 70%% source / 30%% test exploration ratio:**
            - For every 2-3 source files explored, explore 1 test file
            - When you find a class (e.g., UserService), also look for its test (UserServiceTest)
            - Tests provide valuable usage examples and expected behavior
            - Don't ignore tests - they document how code should be used
            
            **How to find tests:**
            - Look for files ending with "Test", "Tests", or "Spec"
            - Check "/test/" or "/tests/" directories parallel to source
            - Use `find_by_name` with "Test" suffix (e.g., if exploring "UserService", search "UserServiceTest")
            
            ### 3. Best Practices:
            
            - **Be specific with search queries**: Use technical terms and full context
              ✓ Good: "user authentication validation logic"
              ✗ Bad: "validation"
            
            - **Case sensitivity matters**: `find_by_name` is case-sensitive
              ✓ "UserService" will find UserService, UserServiceImpl
              ✗ "userservice" will NOT find UserService
            
            - **Start broad, then narrow**: Begin with search/discovery, then drill into specifics
            
            - **Use relationship tools after finding elements**: First find the class/method, then explore its relationships
            
            - **Balance source and tests**: Tests are documentation - include them in exploration
            
            ### 4. Common Patterns:
            
            For "How does X work?" queries:
            1. `search_code` or `find_by_name` to locate X
            2. `read_file` to see implementation
            3. `find_by_name` to locate XTest or tests for X
            4. `read_file` on test to see usage examples
            5. `find_relationships` to understand dependencies
            
            For "Find all implementations of Y" queries:
            1. `find_by_name` to locate interface/abstract class Y
            2. `find_implementations` to get all implementations
            3. `read_file` on each implementation
            4. Look for test files for key implementations
            
            For "What uses Z?" queries:
            1. `find_by_name` to locate Z
            2. `find_usages` or `find_relationships` with USED_BY
            3. Check both source and test usages
            4. `read_file` to examine usage contexts
            
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
            
            Generate 3-5 initial tool calls following the strategy above. Always start with discovery tools!
            Remember to plan for both source and test exploration.
            """, userQuery, toolRegistry.getToolsDescription());
    }
    
    /**
     * Builds the exploration prompt for subsequent rounds.
     */
    private String buildExplorationPrompt(ExplorationContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Continue exploring based on what you've discovered so far.\n\n");
        prompt.append("Original Query: ").append(context.getUserQuery()).append("\n\n");
        
        // Add exploration statistics
        prompt.append("**Exploration Balance:** ").append(context.getExplorationStats()).append("\n\n");
        
        prompt.append("Previous Tool Executions:\n");
        for (ToolExecution execution : context.getRecentExecutions(5)) {
            prompt.append("\nTool: ").append(execution.toolName).append("\n");
            prompt.append("Parameters: ").append(execution.parameters).append("\n");
            prompt.append("Result: ").append(truncate(execution.result, 500)).append("\n");
        }
        
        prompt.append("\n## NEXT STEPS STRATEGY:\n\n");
        
        // Check source/test balance
        if (context.needsMoreTestExploration()) {
            prompt.append("⚠️ **Test Coverage Alert**: You've focused heavily on source code (")
                  .append(String.format("%.0f%%", context.getSourceToTestRatio() * 100))
                  .append("). Now explore some tests:\n");
            prompt.append("- Look for test files (ending with Test, Tests, or Spec)\n");
            prompt.append("- Use `find_by_name` with 'Test' suffix for classes you've explored\n");
            prompt.append("- Check /test/ directories\n\n");
        } else if (context.needsMoreSourceExploration()) {
            prompt.append("⚠️ **Source Coverage Alert**: Balance is tilted toward tests. Focus on more source code.\n\n");
        }
        
        // Analyze what phase we're in and suggest next steps
        Map<String, Integer> toolUsage = context.getToolUsageStats();
        boolean hasDiscovery = toolUsage.containsKey("search_code") || toolUsage.containsKey("find_by_name");
        boolean hasFileReads = toolUsage.containsKey("read_file");
        boolean hasRelationships = toolUsage.containsKey("find_relationships") || 
                                   toolUsage.containsKey("find_callers") || 
                                   toolUsage.containsKey("find_implementations");
        
        if (!hasDiscovery) {
            prompt.append("⚠️ You haven't used discovery tools yet! Start with:\n");
            prompt.append("- `search_code` for conceptual searches\n");
            prompt.append("- `find_by_name` for specific class/method names\n\n");
        } else if (!hasFileReads) {
            prompt.append("✓ Discovery complete. Now examine implementations:\n");
            prompt.append("- Use `read_file` to see the actual code\n");
            prompt.append("- Use `get_class_info` for structural details\n");
            prompt.append("- Remember to check both source and test files\n\n");
        } else if (!hasRelationships) {
            prompt.append("✓ Implementation examined. Now explore relationships:\n");
            prompt.append("- Use `find_relationships` to map dependencies\n");
            prompt.append("- Use `find_callers` to trace usage\n");
            prompt.append("- Use `find_usages` to find references\n");
            prompt.append("- Check relationships in both source and tests\n\n");
        } else {
            prompt.append("✓ Deep exploration phase. Consider:\n");
            prompt.append("- Following interesting relationships you found\n");
            prompt.append("- Exploring similar code patterns\n");
            prompt.append("- Checking test files for usage examples\n");
            prompt.append("- Looking for edge cases in tests\n\n");
        }
        
        // Add intelligent suggestions based on context
        Set<String> unexplored = context.getUnexploredElements();
        if (!unexplored.isEmpty()) {
            prompt.append("### Discovered but unexplored elements:\n");
            unexplored.stream().limit(5).forEach(element -> {
                prompt.append("- ").append(element);
                if (element.contains("Test")) {
                    prompt.append(" (TEST)");
                }
                prompt.append("\n");
            });
            prompt.append("\n");
        }
        
        // Suggest test exploration if source files were explored
        if (context.exploredSourceFiles.size() > 0 && context.needsMoreTestExploration()) {
            prompt.append("### Suggested test files to explore:\n");
            List<String> testSuggestions = context.getSuggestedTestFiles();
            for (String testFile : testSuggestions) {
                prompt.append("- ").append(testFile).append("\n");
            }
            prompt.append("\n");
        }
        
        List<String> suggestions = context.getSuggestedNextElements();
        if (!suggestions.isEmpty()) {
            prompt.append("### Suggested elements to explore (based on relationships):\n");
            suggestions.forEach(element -> 
                prompt.append("- ").append(element).append("\n"));
            prompt.append("\n");
        }
        
        // Check if we have adequate coverage
        if (context.hasAdequateCoverage()) {
            prompt.append("### Coverage Status: ✓ Good\n");
            prompt.append("You've explored ").append(context.getToolCallCount())
                  .append(" tools with ").append(context.exploredElements.size())
                  .append(" elements and ").append(context.exploredFiles.size())
                  .append(" files. Consider wrapping up unless you find critical gaps.\n\n");
        }
        
        prompt.append("Based on these results, what should we explore next?\n\n");
        
        prompt.append("## SMART EXPLORATION TIPS:\n");
        prompt.append("1. **Maintain Balance**: Aim for 70% source, 30% test exploration\n");
        prompt.append("2. **Tests are Documentation**: They show expected behavior and usage patterns\n");
        prompt.append("3. **Follow the breadcrumbs**: If you found a class, explore its methods AND its tests\n");
        prompt.append("4. **Look for patterns**: UserService → UserServiceTest, UserRepository → UserRepositoryTest\n");
        prompt.append("5. **Check both directions**: For inheritance, check both parent and children. For calls, check both callers and callees\n");
        prompt.append("6. **Don't repeat**: Avoid calling the same tool with the same parameters\n");
        prompt.append("7. **Focus on relevance**: Prioritize exploring code that directly relates to the original query\n\n");
        
        prompt.append("Available Tools:\n").append(toolRegistry.getToolsDescription()).append("\n");
        
        prompt.append("""
            Generate tool calls that build upon your discoveries. Format as JSON blocks:
            ```json
            {
              "tool": "tool_name",
              "parameters": {...},
              "reasoning": "How this extends our understanding"
            }
            ```
            
            Generate 2-5 tool calls. Focus on depth over breadth - explore the most relevant findings thoroughly.
            Remember to maintain the 70/30 source/test balance!
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
        private final List<ToolExecution> toolExecutions = new ArrayList<>();
        private final List<ToolCallParser.ToolCall> plannedTools = new ArrayList<>();
        private final Set<String> exploredElements = new HashSet<>();
        private final Set<String> exploredFiles = new HashSet<>();
        private final Map<String, Set<String>> discoveredRelationships = new HashMap<>();
        
        // Track source vs test exploration
        private final Set<String> exploredSourceFiles = new HashSet<>();
        private final Set<String> exploredTestFiles = new HashSet<>();
        private final Set<String> exploredSourceElements = new HashSet<>();
        private final Set<String> exploredTestElements = new HashSet<>();
        
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
