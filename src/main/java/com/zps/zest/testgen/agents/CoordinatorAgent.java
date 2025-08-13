package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CoordinatorAgent extends StreamingBaseAgent {
    
    public CoordinatorAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "CoordinatorAgent");
    }
    
    /**
     * Plan tests for the given request with streaming support
     */
    @NotNull
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[CoordinatorAgent] Planning tests for: " + request.getTargetFile().getName());
                
                // Analyze the target code
                String codeContext = analyzeTargetCode(request);
                
                // Create planning task
                String task = "Analyze the provided code and create a comprehensive test plan. " +
                             "User description: " + request.getUserDescription() + ". " +
                             "Requested test type: " + request.getTestType().getDescription();
                
                // Direct LLM call for faster planning (no ReAct loop)
                String planningResult = generateDirectTestPlan(task, codeContext);
                
                // Parse the planning result into TestPlan
                return parseTestPlan(planningResult, request);
                
            } catch (Exception e) {
                LOG.error("[CoordinatorAgent] Failed to plan tests", e);
                throw new RuntimeException("Test planning failed: " + e.getMessage());
            }
        });
    }
    
    private String analyzeTargetCode(@NotNull TestGenerationRequest request) {
        StringBuilder context = new StringBuilder();
        
        try {
            // ClassAnalyzer methods already handle ReadAction internally
            if (request.hasSelection()) {
                String selectionContext = ClassAnalyzer.collectSelectionContext(
                    request.getTargetFile(),
                    request.getSelectionStart(),
                    request.getSelectionEnd()
                );
                context.append("Selected Code Context:\n").append(selectionContext).append("\n\n");
            }
            
            // Get the target class first in a ReadAction
            PsiClass targetClass = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<PsiClass>) () -> {
                    if (request.getTargetFile() instanceof PsiJavaFile) {
                        PsiJavaFile javaFile = (PsiJavaFile) request.getTargetFile();
                        PsiClass[] classes = javaFile.getClasses();
                        if (classes.length > 0) {
                            return classes[0];
                        }
                    }
                    return null;
                }
            );
            
            // Now call ClassAnalyzer outside the ReadAction (it has its own)
            if (targetClass != null) {
                String classContext = ClassAnalyzer.collectClassContext(targetClass);
                context.append("Class Context:\n").append(classContext).append("\n\n");
            }
            
            // Add file content summary in a separate ReadAction
            com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(() -> {
                context.append("File: ").append(request.getTargetFile().getName()).append("\n");
                context.append("Path: ").append(request.getTargetFile().getVirtualFile().getPath()).append("\n");
            });
            
        } catch (Exception e) {
            LOG.warn("[CoordinatorAgent] Failed to analyze target code", e);
            context.append("Error analyzing target code: ").append(e.getMessage());
        }
        
        return context.toString();
    }
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("analyze") || lowerReasoning.contains("examine")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze code structure and complexity", reasoning);
        } else if (lowerReasoning.contains("search") || lowerReasoning.contains("find")) {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for similar patterns or dependencies", reasoning);
        } else if (lowerReasoning.contains("plan") || lowerReasoning.contains("create")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Generate test plan", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Test planning completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Continue analysis", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return performCodeAnalysis(action.getParameters());
            case SEARCH:
                return performPatternSearch(action.getParameters());
            case GENERATE:
                return generateTestPlan(action.getParameters());
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    private String performCodeAnalysis(@NotNull String parameters) {
        String prompt = "Analyze code for test planning:\n" +
                       parameters + "\n\n" +
                       "List: method names, dependencies, complexity (simple/complex).\n" +
                       "Do NOT generate test code.\n\n" +
                       "Analysis:";
        
        return queryLLM(prompt, 500); // Reduced from 1500
    }
    
    private String performPatternSearch(@NotNull String parameters) {
        // Use RAG to find similar test patterns
        try {
            ZestLangChain4jService.RetrievalResult result = langChainService
                .retrieveContext("test patterns " + parameters, 5, 0.7).join();
            
            StringBuilder context = new StringBuilder();
            context.append("Found similar test patterns:\n");
            
            for (ZestLangChain4jService.ContextItem item : result.getItems()) {
                context.append("- ").append(item.getTitle()).append(": ")
                       .append(item.getContent().substring(0, Math.min(200, item.getContent().length())))
                       .append("...\n");
            }
            
            return context.toString();
            
        } catch (Exception e) {
            LOG.warn("[CoordinatorAgent] Pattern search failed", e);
            return "Pattern search failed: " + e.getMessage();
        }
    }
    
    private String generateTestPlan(@NotNull String parameters) {
        String prompt = "Create test plan (scenarios only, NO code).\n" +
                       parameters + "\n\n" +
                       "Output ONLY this format:\n" +
                       "TARGET_METHOD: methodName\n" +
                       "TARGET_CLASS: className\n" +
                       "RECOMMENDED_TYPE: UNIT_TESTS or INTEGRATION_TESTS\n" +
                       "REASONING: one line\n" +
                       "DEPENDENCIES: dep1, dep2\n" +
                       "SCENARIOS:\n" +
                       "- SCENARIO: name | TYPE: UNIT | PRIORITY: HIGH | DESCRIPTION: brief | EXPECTED: result | INPUTS: input1\n\n" +
                       "Max 5 scenarios. NO test code.\n\n" +
                       "Test Plan:";
        
        return queryLLM(prompt, 800); // Reduced from 2000
    }
    
    /**
     * Generate test plan directly without ReAct loop for faster response
     */
    private String generateDirectTestPlan(@NotNull String task, @NotNull String context) {
        String prompt = "You are a test planning expert. Analyze the code and create a test plan.\n\n" +
                       "Task: " + task + "\n\n" +
                       "Code Context:\n" + context + "\n\n" +
                       "Output ONLY this format (no extra text):\n" +
                       "TARGET_METHOD: methodName\n" +
                       "TARGET_CLASS: className\n" +
                       "RECOMMENDED_TYPE: UNIT_TESTS\n" +
                       "REASONING: one line reason\n" +
                       "DEPENDENCIES: none\n" +
                       "SCENARIOS:\n" +
                       "- SCENARIO: Test happy path | TYPE: UNIT | PRIORITY: HIGH | DESCRIPTION: Test normal case | EXPECTED: success | INPUTS: valid input\n" +
                       "- SCENARIO: Test error case | TYPE: UNIT | PRIORITY: HIGH | DESCRIPTION: Test error handling | EXPECTED: error handled | INPUTS: invalid input\n\n" +
                       "Max 5 scenarios. Be concise.\n\n" +
                       "Test Plan:";
        
        // Stream if consumer available, otherwise direct query
        if (streamingConsumer != null) {
            notifyStream("\n📋 Generating test plan...\n");
            String result = queryLLM(prompt, 600);
            notifyStream("\n✅ Test plan complete\n");
            return result;
        } else {
            return queryLLM(prompt, 600);
        }
    }
    
    private TestPlan parseTestPlan(@NotNull String planningResult, @NotNull TestGenerationRequest request) {
        try {
            LOG.debug("[CoordinatorAgent] Parsing test plan from result: " + 
                     (planningResult.length() > 200 ? planningResult.substring(0, 200) + "..." : planningResult));
            
            String[] lines = planningResult.split("\n");
            
            String targetMethod = "unknown";
            String targetClass = "unknown";
            TestGenerationRequest.TestType recommendedType = request.getTestType();
            String reasoning = "";
            List<String> dependencies = new ArrayList<>();
            List<TestPlan.TestScenario> scenarios = new ArrayList<>();
            boolean inScenariosSection = false;
            
            for (String line : lines) {
                line = line.trim();
                
                if (line.isEmpty()) continue;
                
                if (line.startsWith("TARGET_METHOD:")) {
                    targetMethod = line.substring("TARGET_METHOD:".length()).trim();
                    if (targetMethod.isEmpty()) targetMethod = "testMethod";
                } else if (line.startsWith("TARGET_CLASS:")) {
                    targetClass = line.substring("TARGET_CLASS:".length()).trim();
                    if (targetClass.isEmpty()) targetClass = request.getTargetFile().getName().replace(".java", "");
                } else if (line.startsWith("RECOMMENDED_TYPE:")) {
                    String typeStr = line.substring("RECOMMENDED_TYPE:".length()).trim();
                    try {
                        recommendedType = TestGenerationRequest.TestType.valueOf(typeStr);
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Unknown test type: " + typeStr);
                    }
                } else if (line.startsWith("REASONING:")) {
                    reasoning = line.substring("REASONING:".length()).trim();
                } else if (line.startsWith("DEPENDENCIES:")) {
                    String depsStr = line.substring("DEPENDENCIES:".length()).trim();
                    if (!depsStr.isEmpty() && !depsStr.equalsIgnoreCase("none")) {
                        dependencies = Arrays.asList(depsStr.split(",\\s*"));
                    }
                } else if (line.equals("SCENARIOS:") || line.startsWith("SCENARIOS:")) {
                    inScenariosSection = true;
                } else if (inScenariosSection && (line.startsWith("-") || line.startsWith("SCENARIO:"))) {
                    TestPlan.TestScenario scenario = parseScenario(line);
                    if (scenario != null) {
                        scenarios.add(scenario);
                        LOG.debug("[CoordinatorAgent] Added scenario: " + scenario.getName());
                    }
                } else if (inScenariosSection && !line.startsWith("TARGET") && !line.contains(":")) {
                    // Could be a simple scenario description without proper formatting
                    if (line.length() > 3) {
                        TestPlan.TestScenario scenario = parseScenario(line);
                        if (scenario != null) {
                            scenarios.add(scenario);
                            LOG.debug("[CoordinatorAgent] Added simple scenario: " + scenario.getName());
                        }
                    }
                }
            }
            
            // Extract target info from file if not properly parsed
            if (targetClass.equals("unknown") || targetClass.isEmpty()) {
                targetClass = request.getTargetFile().getName().replace(".java", "");
                LOG.debug("[CoordinatorAgent] Using filename for targetClass: " + targetClass);
            }
            
            if (targetMethod.equals("unknown") || targetMethod.isEmpty()) {
                targetMethod = "test" + targetClass;
                LOG.debug("[CoordinatorAgent] Generated targetMethod: " + targetMethod);
            }
            
            // If no scenarios were parsed, create default ones
            if (scenarios.isEmpty()) {
                LOG.warn("[CoordinatorAgent] No scenarios parsed, creating defaults");
                scenarios.add(createDefaultScenario(targetMethod, recommendedType));
                scenarios.add(new TestPlan.TestScenario(
                    "Test edge cases",
                    "Test edge cases and boundary conditions",
                    TestPlan.TestScenario.Type.EDGE_CASE,
                    List.of("boundary values"),
                    "Should handle edge cases correctly",
                    TestPlan.TestScenario.Priority.MEDIUM
                ));
            }
            
            LOG.info("[CoordinatorAgent] Parsed test plan with " + scenarios.size() + " scenarios");
            return new TestPlan(targetMethod, targetClass, scenarios, dependencies, recommendedType, reasoning);
            
        } catch (Exception e) {
            LOG.error("[CoordinatorAgent] Failed to parse test plan", e);
            // Return a fallback plan
            return createFallbackTestPlan(request);
        }
    }
    
    private TestPlan.TestScenario parseScenario(@NotNull String scenarioLine) {
        try {
            // More robust parsing - handle incomplete lines
            String line = scenarioLine.trim();
            if (line.startsWith("-")) {
                line = line.substring(1).trim();
            }
            
            // Default values
            String name = "Test Scenario";
            TestPlan.TestScenario.Type type = TestPlan.TestScenario.Type.UNIT;
            TestPlan.TestScenario.Priority priority = TestPlan.TestScenario.Priority.MEDIUM;
            String description = "Test scenario";
            String expected = "Should pass";
            List<String> inputs = Arrays.asList("default");
            
            // First check if it's a simple scenario description without format
            if (!line.contains("|") && !line.contains("SCENARIO:")) {
                // Simple one-line scenario
                name = line;
                description = line;
                LOG.debug("[CoordinatorAgent] Parsed simple scenario: " + name);
                return new TestPlan.TestScenario(name, description, type, inputs, expected, priority);
            }
            
            // Try to parse with | separator, but handle missing parts
            if (line.contains("|")) {
                String[] parts = line.split("\\|");
                
                for (String part : parts) {
                    part = part.trim();
                    if (part.contains("SCENARIO:") && part.length() > 9) {
                        name = part.substring(part.indexOf("SCENARIO:") + 9).trim();
                    } else if (part.contains("TYPE:") && part.length() > 5) {
                        String typeStr = part.substring(part.indexOf("TYPE:") + 5).trim().toUpperCase();
                        // Handle variations
                        if (typeStr.contains("EDGE")) type = TestPlan.TestScenario.Type.EDGE_CASE;
                        else if (typeStr.contains("ERROR")) type = TestPlan.TestScenario.Type.ERROR_HANDLING;
                        else if (typeStr.contains("INTEGRATION")) type = TestPlan.TestScenario.Type.INTEGRATION;
                        else if (typeStr.contains("UNIT")) type = TestPlan.TestScenario.Type.UNIT;
                    } else if (part.contains("PRIORITY:") && part.length() > 9) {
                        String priorityStr = part.substring(part.indexOf("PRIORITY:") + 9).trim().toUpperCase();
                        if (priorityStr.contains("HIGH")) priority = TestPlan.TestScenario.Priority.HIGH;
                        else if (priorityStr.contains("LOW")) priority = TestPlan.TestScenario.Priority.LOW;
                        else priority = TestPlan.TestScenario.Priority.MEDIUM;
                    } else if (part.contains("DESCRIPTION:") && part.length() > 12) {
                        description = part.substring(part.indexOf("DESCRIPTION:") + 12).trim();
                    } else if (part.contains("EXPECTED:") && part.length() > 9) {
                        expected = part.substring(part.indexOf("EXPECTED:") + 9).trim();
                    } else if (part.contains("INPUTS:") && part.length() > 7) {
                        String inputsStr = part.substring(part.indexOf("INPUTS:") + 7).trim();
                        if (!inputsStr.isEmpty()) {
                            inputs = Arrays.asList(inputsStr.split(",\\s*"));
                        }
                    }
                }
            } else {
                // Try to parse without | separator
                if (line.contains("SCENARIO:")) {
                    int idx = line.indexOf("SCENARIO:") + 9;
                    if (idx < line.length()) {
                        // Extract until next keyword or end of line
                        int nextKeyword = line.length();
                        for (String keyword : Arrays.asList("TYPE:", "PRIORITY:", "DESCRIPTION:", "EXPECTED:", "INPUTS:")) {
                            int kwIdx = line.indexOf(keyword, idx);
                            if (kwIdx > 0 && kwIdx < nextKeyword) {
                                nextKeyword = kwIdx;
                            }
                        }
                        name = line.substring(idx, nextKeyword).trim();
                    }
                }
            }
            
            // If we only got a scenario name and nothing else, use it as description too
            if (!name.equals("Test Scenario") && description.equals("Test scenario")) {
                description = name;
            }
            
            LOG.debug("[CoordinatorAgent] Parsed scenario: " + name + " (type=" + type + ", priority=" + priority + ")");
            return new TestPlan.TestScenario(name, description, type, inputs, expected, priority);
            
        } catch (Exception e) {
            LOG.warn("[CoordinatorAgent] Failed to parse scenario, using default: " + scenarioLine, e);
            // Try to extract at least a name from the line
            String fallbackName = scenarioLine.replaceAll("[^a-zA-Z0-9 ]", "").trim();
            if (fallbackName.isEmpty()) {
                fallbackName = "Test Case";
            }
            return new TestPlan.TestScenario(
                fallbackName,
                "Test for " + fallbackName,
                TestPlan.TestScenario.Type.UNIT,
                List.of("standard input"),
                "Should execute successfully",
                TestPlan.TestScenario.Priority.MEDIUM
            );
        }
    }
    
    private TestPlan.TestScenario createDefaultScenario(@NotNull String targetMethod, @NotNull TestGenerationRequest.TestType testType) {
        TestPlan.TestScenario.Type scenarioType = testType == TestGenerationRequest.TestType.INTEGRATION_TESTS ?
            TestPlan.TestScenario.Type.INTEGRATION : TestPlan.TestScenario.Type.UNIT;
        
        return new TestPlan.TestScenario(
            "Test " + targetMethod,
            "Basic test scenario for " + targetMethod,
            scenarioType,
            List.of("standard input"),
            "Should execute successfully",
            TestPlan.TestScenario.Priority.HIGH
        );
    }
    
    private TestPlan createFallbackTestPlan(@NotNull TestGenerationRequest request) {
        String fileName = request.getTargetFile().getName();
        String className = fileName.substring(0, fileName.lastIndexOf('.'));
        
        List<TestPlan.TestScenario> scenarios = List.of(
            createDefaultScenario("testMethod", request.getTestType())
        );
        
        return new TestPlan(
            "testMethod",
            className,
            scenarios,
            List.of(),
            request.getTestType(),
            "Fallback test plan due to parsing error"
        );
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a test planning coordinator that analyzes code and creates comprehensive test strategies";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.SEARCH,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }
    
    @NotNull
    @Override
    protected String buildActionPrompt(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return "List methods to test (names only):\n" +
                       action.getParameters() + "\n\n" +
                       "Output: method names, dependencies. NO code.\n\n" +
                       "Methods:";
                       
            case GENERATE:
                return "Create test plan.\n" +
                       action.getParameters() + "\n\n" +
                       "Output format:\n" +
                       "TARGET_METHOD: methodName\n" +
                       "TARGET_CLASS: className\n" +
                       "RECOMMENDED_TYPE: UNIT_TESTS or INTEGRATION_TESTS or BOTH\n" +
                       "REASONING: brief explanation\n" +
                       "DEPENDENCIES: dep1, dep2\n" +
                       "SCENARIOS:\n" +
                       "- SCENARIO: name | TYPE: UNIT/INTEGRATION/EDGE_CASE/ERROR_HANDLING | PRIORITY: HIGH/MEDIUM/LOW | DESCRIPTION: brief | EXPECTED: outcome | INPUTS: input1, input2\n\n" +
                       "Test Plan:";
                       
            default:
                return action.getParameters();
        }
    }
}