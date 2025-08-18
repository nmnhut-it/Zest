package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.ui.model.TestPlanDisplayData;
import com.zps.zest.testgen.ui.model.ScenarioDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.jetbrains.annotations.Nullable;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Test planning coordinator using LangChain4j's streaming capabilities.
 * Generates all test scenarios in a single response - no loops needed.
 */
public class CoordinatorAgent extends StreamingBaseAgent {
    private final TestPlanningTools planningTools;
    private final TestPlanningAssistant assistant;
    
    public CoordinatorAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "CoordinatorAgent");
        this.planningTools = new TestPlanningTools(this);
        
        // Build the agent with streaming support
        this.assistant = AgenticServices
                .agentBuilder(TestPlanningAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(50) // Allow multiple tool calls in one response
                .chatMemory(MessageWindowChatMemory.withMaxMessages(100))
                .tools(planningTools)
                .build();
    }
    
    /**
     * Streamlined interface - generates all scenarios in one shot
     */
    public interface TestPlanningAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test planning assistant that creates comprehensive test plans.
        
        
        PROCESS:
        1. Analyze the code and set target class.
        2. Set recommended test type.
        3. Add multiple test scenarios at once using addTestScenarios (note: plural)
        
        Prefer quality over quantity. Keep the test focused, aimed at preventing potential bugs. Make sure all paths and branches are tested. 
        If it cannot be unit-tested, go for integration test with test containers and give clear comments with TODO and FIXME on the problems.

        You will respond using tools only. Generate multiple scenarios in a single addTestScenarios call, stop when you find it is enough, or you have exceed 10 test cases per method.
        """)
        @dev.langchain4j.agentic.Agent
        String planTests(String request);
    }
    
    /**
     * Plan tests for the given request (without context)
     */
    @NotNull
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request) {
        return planTests(request, null);
    }
    
    /**
     * Set callback for test plan updates
     */
    public void setPlanUpdateCallback(@Nullable Consumer<TestPlan> callback) {
        // Will be set when planning starts
    }
    
    /**
     * Plan tests using LangChain4j's agent orchestration.
     * Generates all scenarios in one response - no loops needed.
     */
    @NotNull
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request,
                                                  @Nullable TestContext context) {
        return planTests(request, context, null);
    }
    
    @NotNull
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request,
                                                  @Nullable TestContext context,
                                                  @Nullable Consumer<TestPlan> planUpdateCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting test planning with LangChain4j orchestration");
                
                // Notify UI
                notifyStart();
                sendToUI("🎯 Planning comprehensive test scenarios...\n\n");
                
                // Reset planning tools for new session
                planningTools.reset();
                planningTools.setToolNotifier(this::sendToUI);
                planningTools.setPlanUpdateCallback(planUpdateCallback);

                for (PsiMethod psiMethod : request.getTargetMethods()) {
                    String name = psiMethod.getName();
                    planningTools.addTargetMethod(name);
                }


                // Build the planning request
                String planRequest = buildPlanningRequest(request, context);
                
                // Send the request to UI
                sendToUI("📋 Request:\n" + planRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");
                
                // Let LangChain4j handle the entire planning with all tool calls
                // The assistant will make ALL scenario additions in one response
                String response = assistant.planTests(planRequest);
                
                // Send response to UI
                sendToUI(response);
                sendToUI("\n" + "-".repeat(40) + "\n");
                
                // Build the test plan from accumulated tool data
                TestPlan testPlan = planningTools.buildTestPlan();
                
                // Summary
                sendToUI("\n📊 Test Plan Summary:\n");
                sendToUI("  • Target: " + testPlan.getTargetClass() + "." + String.join(", ", testPlan.getTargetMethods()) + "\n");
                sendToUI("  • Type: " + testPlan.getRecommendedTestType().getDescription() + "\n");
                sendToUI("  • Scenarios: " + testPlan.getScenarioCount() + "\n");
                notifyComplete();
                
                LOG.debug("Test planning complete: " + testPlan.getScenarioCount() + " scenarios");
                
                return testPlan;
                
            } catch (Exception e) {
                LOG.error("Failed to plan tests", e);
                sendToUI("\n❌ Test planning failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Test planning failed: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Build the planning request with all necessary information.
     */
    private String buildPlanningRequest(TestGenerationRequest request, TestContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a comprehensive test plan for the following code.\n\n");
        
        // File information
        prompt.append("File: ").append(request.getTargetFile().getName()).append("\n");
        
        // Add code (selected or full)
        String code = request.hasSelection() ? request.getSelectedCode() : request.getTargetFile().getText();
        if (code != null && !code.isEmpty()) {
            // Limit code length to avoid token limits
//            if (code.length() > 3000) {
//                code = code.substring(0, 3000) + "\n... [truncated]";
//            }
            prompt.append("\nCode to test:\n```java\n");
            prompt.append(code);
            prompt.append("\n```\n");
        }
        
        // Add context if available
        if (context != null) {
            prompt.append("\nContext Information:\n");
            prompt.append("• Framework: ").append(context.getFrameworkInfo()).append("\n");
            prompt.append("• Dependencies: ").append(context.getDependencies().size()).append("\n");
            prompt.append("• Test patterns found: ").append(context.getExistingTestPatterns().size()).append("\n");
            
            // Include context notes if available
            if (context.getAdditionalMetadata() != null) {
                Object notes = context.getAdditionalMetadata().get("contextNotes");
                if (notes instanceof List && !((List<?>) notes).isEmpty()) {
                    prompt.append("\nKey insights:\n");
                    for (Object note : (List<?>) notes) {
                        prompt.append("• ").append(note).append("\n");
                    }
                }
            }
        }
        
        prompt.append("\nGenerate 8-15 comprehensive test scenarios covering all aspects.");
        prompt.append("\nUse the addTestScenarios tool to add all scenarios at once.");
        
        return prompt.toString();
    }
    
    /**
     * Tools for building the test plan.
     * LangChain4j will call these tools as the assistant generates the plan.
     */
    public static class TestPlanningTools {
        private final CoordinatorAgent coordinatorAgent;
        private String targetClass = "";
        private final List<String> targetMethods = new ArrayList<>();
        private TestGenerationRequest.TestType recommendedType = TestGenerationRequest.TestType.UNIT_TESTS;
        private final List<TestPlan.TestScenario> scenarios = new ArrayList<>();
        private String reasoning = "";
        private Consumer<String> toolNotifier;
        private Consumer<TestPlan> planUpdateCallback;
        
        public TestPlanningTools(CoordinatorAgent agent) {
            this.coordinatorAgent = agent;
        }
        
        public void setToolNotifier(Consumer<String> notifier) {
            this.toolNotifier = notifier;
        }
        
        public void setPlanUpdateCallback(Consumer<TestPlan> callback) {
            this.planUpdateCallback = callback;
        }
        
        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                SwingUtilities.invokeLater(() -> 
                    toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
            }
        }
        
        public void reset() {
            targetClass = "";
            targetMethods.clear();
            recommendedType = TestGenerationRequest.TestType.UNIT_TESTS;
            scenarios.clear();
            reasoning = "";
        }
        
        @Tool("Set the target class name for testing")
        public String setTargetClass(String className) {
            notifyTool("setTargetClass", className);
            this.targetClass = className;
            return "Target class set to: " + className;
        }

        @Tool("Add a target method for testing")
        public String addTargetMethod(String methodName) {
            notifyTool("addTargetMethod", methodName);
            if (!this.targetMethods.contains(methodName)) {
                this.targetMethods.add(methodName);
            }
            return "Added target method: " + methodName + " (Total: " + targetMethods.size() + ")";
        }

        @Tool("Set the recommended test type (UNIT_TESTS or INTEGRATION_TESTS)")
        public String setRecommendedTestType(String testType) {
            notifyTool("setRecommendedTestType", testType);
            try {
                this.recommendedType = TestGenerationRequest.TestType.valueOf(testType.toUpperCase());
                return "Recommended test type set to: " + recommendedType.getDescription();
            } catch (IllegalArgumentException e) {
                // Default to UNIT_TESTS if invalid
                this.recommendedType = TestGenerationRequest.TestType.UNIT_TESTS;
                return "Invalid test type, defaulting to: " + recommendedType.getDescription();
            }
        }

        @Tool("Add multiple test scenarios to the plan at once")
        public String addTestScenarios(List<ScenarioInput> scenarioInputs) {
            notifyTool("addTestScenarios", scenarioInputs.size() + " scenarios");
            int startCount = scenarios.size();
            
            for (ScenarioInput input : scenarioInputs) {
                // Parse scenario type
                TestPlan.TestScenario.Type scenarioType;
                try {
                    scenarioType = TestPlan.TestScenario.Type.valueOf(input.type.toUpperCase());
                } catch (Exception e) {
                    scenarioType = TestPlan.TestScenario.Type.UNIT;
                }
                
                // Parse priority
                TestPlan.TestScenario.Priority scenarioPriority;
                try {
                    scenarioPriority = TestPlan.TestScenario.Priority.valueOf(input.priority.toUpperCase());
                } catch (Exception e) {
                    scenarioPriority = TestPlan.TestScenario.Priority.MEDIUM;
                }
                
                TestPlan.TestScenario scenario = new TestPlan.TestScenario(
                    input.name,
                    input.description,
                    scenarioType,
                    new ArrayList<>(), // Inputs will be determined during test writing
                    "Verify " + input.name.toLowerCase(),
                    scenarioPriority
                );
                
                scenarios.add(scenario);
                
                // Send scenario to UI immediately
                if (coordinatorAgent != null) {
                    ScenarioDisplayData.Priority displayPriority = ScenarioDisplayData.Priority.MEDIUM;
                    try {
                        displayPriority = ScenarioDisplayData.Priority.valueOf(scenarioPriority.toString());
                    } catch (Exception e) {
                        // Keep default MEDIUM
                    }
                    
                    ScenarioDisplayData scenarioData = new ScenarioDisplayData(
                        "scenario_" + input.name.hashCode(),
                        input.name,
                        input.description,
                        displayPriority,
                        scenarioType.getDisplayName(), // category
                        new ArrayList<>(), // setupSteps
                        new ArrayList<>(), // executionSteps
                        new ArrayList<>(), // assertions
                        "Medium", // expectedComplexity
                        ScenarioDisplayData.GenerationStatus.PENDING
                    );
                    
                    TestPlanDisplayData planData = new TestPlanDisplayData(
                        targetClass.isEmpty() ? "UnknownClass" : targetClass,
                        targetMethods.isEmpty() ? List.of("unknownMethod") : new ArrayList<>(targetMethods),
                        recommendedType.getDescription(),
                        List.of(scenarioData), // Send just the new scenario
                        "Planning in progress...",
                        scenarios.size(),
                        new HashSet<>() // selectedScenarios
                    );
                    
                    coordinatorAgent.sendTestPlanUpdate(planData);
                }
            }
            
            // Notify UI with current partial test plan
            if (planUpdateCallback != null) {
                TestPlan currentPlan = buildTestPlan();
                planUpdateCallback.accept(currentPlan);
            }
            
            int added = scenarios.size() - startCount;
            return "Added " + added + " scenarios. Total scenarios: " + scenarios.size();
        }
        
        // Input class for batch scenario creation
        public static class ScenarioInput {
            public String name;
            public String description;
            public String type;
            public String priority;
            
            public ScenarioInput() {}
            
            public ScenarioInput(String name, String description, String type, String priority) {
                this.name = name;
                this.description = description;
                this.type = type;
                this.priority = priority;
            }
        }
        
        @Tool("Set the reasoning for the test plan")
        public String setReasoning(String reasoning) {
            notifyTool("setReasoning", reasoning.length() > 50 ? reasoning.substring(0, 50) + "..." : reasoning);
            this.reasoning = reasoning;
            return "Reasoning recorded";
        }
        
        /**
         * Build the final test plan from accumulated data.
         */
        public TestPlan buildTestPlan() {
            // Ensure we have required fields
            if (targetClass.isEmpty()) {
                targetClass = "UnknownClass";
            }
            if (targetMethods.isEmpty()) {
                targetMethods.add("unknownMethod");
            }
            
            // Create the test plan
            TestPlan plan = new TestPlan(
                new ArrayList<>(targetMethods),
                targetClass,
                new ArrayList<>(scenarios),
                new ArrayList<>(), // Dependencies will be filled by context
                recommendedType,
                reasoning.isEmpty() ? "Comprehensive test plan with " + scenarios.size() + " scenarios" : reasoning
            );
            
            // Send complete test plan to UI
            if (coordinatorAgent != null) {
                List<ScenarioDisplayData> displayScenarios = new ArrayList<>();
                for (TestPlan.TestScenario scenario : plan.getTestScenarios()) {
                    ScenarioDisplayData.Priority displayPriority = ScenarioDisplayData.Priority.MEDIUM;
                    try {
                        displayPriority = ScenarioDisplayData.Priority.valueOf(scenario.getPriority().toString());
                    } catch (Exception e) {
                        // Keep default MEDIUM
                    }
                    
                    ScenarioDisplayData displayScenario = new ScenarioDisplayData(
                        "scenario_" + scenario.getName().hashCode(),
                        scenario.getName(),
                        scenario.getDescription(),
                        displayPriority,
                        scenario.getType().getDisplayName(), // category
                        new ArrayList<>(), // setupSteps
                        new ArrayList<>(), // executionSteps
                        new ArrayList<>(), // assertions
                        "Medium", // expectedComplexity
                        ScenarioDisplayData.GenerationStatus.PENDING
                    );
                    displayScenarios.add(displayScenario);
                }
                
                TestPlanDisplayData displayData = new TestPlanDisplayData(
                    plan.getTargetClass(),
                    plan.getTargetMethods(),
                    plan.getRecommendedTestType().getDescription(),
                    displayScenarios,
                    "Test plan with " + plan.getScenarioCount() + " scenarios",
                    plan.getScenarioCount(),
                    new HashSet<>() // selectedScenarios
                );
                coordinatorAgent.sendTestPlanUpdate(displayData);
            }
            
            return plan;
        }
    }
}