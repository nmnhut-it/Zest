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

public class CoordinatorAgent extends BaseAgent {
    
    public CoordinatorAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "CoordinatorAgent");
    }
    
    /**
     * Plan tests for the given request
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
                
                // Execute ReAct workflow
                String planningResult = executeReActTask(task, codeContext).join();
                
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
            // Get code context using ClassAnalyzer
            if (request.hasSelection()) {
                String selectionContext = ClassAnalyzer.collectSelectionContext(
                    request.getTargetFile(),
                    request.getSelectionStart(),
                    request.getSelectionEnd()
                );
                context.append("Selected Code Context:\n").append(selectionContext).append("\n\n");
            }
            
            // If it's a Java file, get class information
            if (request.getTargetFile() instanceof PsiJavaFile) {
                PsiJavaFile javaFile = (PsiJavaFile) request.getTargetFile();
                PsiClass[] classes = javaFile.getClasses();
                
                if (classes.length > 0) {
                    PsiClass targetClass = classes[0];
                    String classContext = ClassAnalyzer.collectClassContext(targetClass);
                    context.append("Class Context:\n").append(classContext).append("\n\n");
                }
            }
            
            // Add file content summary
            context.append("File: ").append(request.getTargetFile().getName()).append("\n");
            context.append("Path: ").append(request.getTargetFile().getVirtualFile().getPath()).append("\n");
            
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
        String prompt = "Analyze the following code for test planning purposes:\n\n" +
                       parameters + "\n\n" +
                       "Identify:\n" +
                       "1. Methods that need testing\n" +
                       "2. Edge cases to consider\n" +
                       "3. Dependencies and mocking requirements\n" +
                       "4. Complexity assessment\n" +
                       "5. Recommended test types (unit vs integration)\n\n" +
                       "Analysis:";
        
        return queryLLM(prompt, 1500);
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
        String prompt = "Based on the analysis, create a detailed test plan with specific test scenarios.\n\n" +
                       "Context:\n" + parameters + "\n\n" +
                       "Create a test plan that includes:\n" +
                       "1. List of test scenarios (name, description, type, priority)\n" +
                       "2. Dependencies that need to be mocked\n" +
                       "3. Recommended test type (unit/integration/both)\n" +
                       "4. Reasoning for the recommendations\n\n" +
                       "Format the response as:\n" +
                       "TARGET_METHOD: [method name]\n" +
                       "TARGET_CLASS: [class name]\n" +
                       "RECOMMENDED_TYPE: [UNIT_TESTS/INTEGRATION_TESTS/BOTH]\n" +
                       "REASONING: [explanation]\n" +
                       "DEPENDENCIES: [dependency1, dependency2, ...]\n" +
                       "SCENARIOS:\n" +
                       "- SCENARIO: [name] | TYPE: [UNIT/INTEGRATION/EDGE_CASE/ERROR_HANDLING] | PRIORITY: [HIGH/MEDIUM/LOW] | DESCRIPTION: [description] | EXPECTED: [expected outcome] | INPUTS: [input1, input2, ...]\n" +
                       "\n" +
                       "Test Plan:";
        
        return queryLLM(prompt, 2000);
    }
    
    private TestPlan parseTestPlan(@NotNull String planningResult, @NotNull TestGenerationRequest request) {
        try {
            String[] lines = planningResult.split("\n");
            
            String targetMethod = "unknown";
            String targetClass = "unknown";
            TestGenerationRequest.TestType recommendedType = request.getTestType();
            String reasoning = "";
            List<String> dependencies = new ArrayList<>();
            List<TestPlan.TestScenario> scenarios = new ArrayList<>();
            
            for (String line : lines) {
                line = line.trim();
                
                if (line.startsWith("TARGET_METHOD:")) {
                    targetMethod = line.substring("TARGET_METHOD:".length()).trim();
                } else if (line.startsWith("TARGET_CLASS:")) {
                    targetClass = line.substring("TARGET_CLASS:".length()).trim();
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
                    if (!depsStr.isEmpty()) {
                        dependencies = Arrays.asList(depsStr.split(",\\s*"));
                    }
                } else if (line.startsWith("- SCENARIO:")) {
                    TestPlan.TestScenario scenario = parseScenario(line);
                    if (scenario != null) {
                        scenarios.add(scenario);
                    }
                }
            }
            
            // If no scenarios were parsed, create default ones
            if (scenarios.isEmpty()) {
                scenarios.add(createDefaultScenario(targetMethod, recommendedType));
            }
            
            return new TestPlan(targetMethod, targetClass, scenarios, dependencies, recommendedType, reasoning);
            
        } catch (Exception e) {
            LOG.error("[CoordinatorAgent] Failed to parse test plan", e);
            // Return a fallback plan
            return createFallbackTestPlan(request);
        }
    }
    
    private TestPlan.TestScenario parseScenario(@NotNull String scenarioLine) {
        try {
            // Parse format: - SCENARIO: [name] | TYPE: [type] | PRIORITY: [priority] | DESCRIPTION: [description] | EXPECTED: [expected] | INPUTS: [inputs]
            String[] parts = scenarioLine.substring(2).split(" \\| ");
            
            String name = "Test Scenario";
            TestPlan.TestScenario.Type type = TestPlan.TestScenario.Type.UNIT;
            TestPlan.TestScenario.Priority priority = TestPlan.TestScenario.Priority.MEDIUM;
            String description = "Generated test scenario";
            String expected = "Should pass";
            List<String> inputs = new ArrayList<>();
            
            for (String part : parts) {
                if (part.startsWith("SCENARIO:")) {
                    name = part.substring("SCENARIO:".length()).trim();
                } else if (part.startsWith("TYPE:")) {
                    String typeStr = part.substring("TYPE:".length()).trim();
                    try {
                        type = TestPlan.TestScenario.Type.valueOf(typeStr);
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Unknown scenario type: " + typeStr);
                    }
                } else if (part.startsWith("PRIORITY:")) {
                    String priorityStr = part.substring("PRIORITY:".length()).trim();
                    try {
                        priority = TestPlan.TestScenario.Priority.valueOf(priorityStr);
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Unknown priority: " + priorityStr);
                    }
                } else if (part.startsWith("DESCRIPTION:")) {
                    description = part.substring("DESCRIPTION:".length()).trim();
                } else if (part.startsWith("EXPECTED:")) {
                    expected = part.substring("EXPECTED:".length()).trim();
                } else if (part.startsWith("INPUTS:")) {
                    String inputsStr = part.substring("INPUTS:".length()).trim();
                    if (!inputsStr.isEmpty()) {
                        inputs = Arrays.asList(inputsStr.split(",\\s*"));
                    }
                }
            }
            
            return new TestPlan.TestScenario(name, description, type, inputs, expected, priority);
            
        } catch (Exception e) {
            LOG.warn("[CoordinatorAgent] Failed to parse scenario: " + scenarioLine, e);
            return null;
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
}