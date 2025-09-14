package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
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
import java.util.Map;
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
    private final MessageWindowChatMemory chatMemory;
    private final ContextAgent.ContextGatheringTools contextTools;
    
    public CoordinatorAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull NaiveLLMService naiveLlmService,
                          @Nullable ContextAgent.ContextGatheringTools contextTools) {
        super(project, langChainService, naiveLlmService, "CoordinatorAgent");
        this.contextTools = contextTools;
        this.planningTools = new TestPlanningTools(this);
        
        // Build the agent with streaming support
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(100);
        this.assistant = AgenticServices
                .agentBuilder(TestPlanningAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(50) // Allow multiple tool calls in one response
                .chatMemory(chatMemory)
                .tools(planningTools)
                .build();
    }
    
    /**
     * Streamlined interface - generates all scenarios in one shot
     */
    public interface TestPlanningAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test planning assistant that creates comprehensive test plans.
        
        IMPORTANT: The target methods have already been selected by the user. Do NOT call addTargetMethod - the methods are already determined.
        
        CRITICAL: Generate test scenarios ONLY for the methods that were selected by the user. Do NOT generate tests for other methods you see in the code, even if they seem related or important. Focus exclusively on the user-selected methods.
        
        PROCESS:
        1. Analyze the code and set target class.
        2. Add multiple test scenarios at once using addTestScenarios (note: plural) - but ONLY for the selected methods
        3. Each scenario should specify its own type (UNIT or INTEGRATION) based on what it tests
        
        Prefer quality over quantity. Keep the test focused on the selected methods only, aimed at preventing potential bugs in those specific methods. Make sure all paths and branches of the SELECTED methods are tested.
        
        CRITICAL: Each test scenario should have its own test type based on what that specific scenario tests:
        - Scenarios testing pure business logic (calculations, validations) → Use "UNIT" type
        - Scenarios testing database interactions, external APIs, file I/O → Use "INTEGRATION" type  
        - Analyze each scenario individually - one test class can have both unit and integration scenarios
        - Use the context analysis to determine what each scenario needs to test

        You will respond using tools only. Generate multiple scenarios in a single addTestScenarios call for the selected methods only, stop when you find it is enough, or you have exceed 10 test cases per selected method.
        """)
        @dev.langchain4j.agentic.Agent
        String planTests(String request);
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
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request) {
        return planTests(request, null);
    }
    
    @NotNull
    public CompletableFuture<TestPlan> planTests(@NotNull TestGenerationRequest request,
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
                String planRequest = buildPlanningRequest(request);
                
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
                sendToUI("  • Overall Type: " + testPlan.getRecommendedTestType().getDescription() + "\n");
                
                // Count unit vs integration scenarios
                long unitScenarios = testPlan.getTestScenarios().stream().mapToLong(s -> s.getType() == TestPlan.TestScenario.Type.UNIT ? 1 : 0).sum();
                long integrationScenarios = testPlan.getTestScenarios().stream().mapToLong(s -> s.getType() == TestPlan.TestScenario.Type.INTEGRATION ? 1 : 0).sum();
                
                sendToUI("  • Scenarios: " + testPlan.getScenarioCount() + " total (" + unitScenarios + " unit, " + integrationScenarios + " integration)\n");
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
    private String buildPlanningRequest(TestGenerationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a comprehensive test plan for the following code.\n\n");
        
        // File information
        prompt.append("File: ").append(request.getTargetFile().getName()).append("\n");
        
        // Explicitly list the selected methods to test
        prompt.append("SELECTED METHODS TO TEST (generate scenarios ONLY for these methods):\n");
        for (var method : request.getTargetMethods()) {
            prompt.append("• ").append(method.getName()).append("()\n");
        }
        prompt.append("\n");
        
        // Skip adding raw code here since it's already included in analyzed class implementations below
        
        // Add basic framework info
        prompt.append("\nFramework Information:\n");
        if (contextTools != null) {
            prompt.append("• Testing Framework: ").append(contextTools.getFrameworkInfo()).append("\n");
        } else {
            prompt.append("• Testing Framework: JUnit 5 (default)\n");
        }
        
        // Include full context analysis using direct tool access
        if (contextTools != null) {
                // Add detailed context notes
                List<String> contextNotes = contextTools.getContextNotes();
                if (!contextNotes.isEmpty()) {
                    prompt.append("\n=== DETAILED CONTEXT ANALYSIS ===\n");
                    prompt.append("Context Agent Findings (use this for test planning decisions):\n");
                    int noteNum = 1;
                    for (String note : contextNotes) {
                        prompt.append(String.format("%d. %s\n\n", noteNum++, note));
                    }
                }
                
                // Add dependency analysis based on analyzed classes
                Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
                if (!analyzedClasses.isEmpty()) {
                    prompt.append("\n=== DEPENDENCY ANALYSIS FOR TEST TYPE DECISIONS ===\n");
                    boolean hasExternalDeps = false;
                    
                    // Analyze all class implementations for external dependencies
                    for (var entry : analyzedClasses.entrySet()) {
                        String implementation = entry.getValue().toLowerCase();
                        if (implementation.contains("database") || implementation.contains("jdbc") || implementation.contains("jpa") || 
                            implementation.contains("repository") || implementation.contains("@entity") || implementation.contains("@table") ||
                            implementation.contains("files.") || implementation.contains("path.") || implementation.contains("inputstream") ||
                            implementation.contains("http") || implementation.contains("resttemplate") || implementation.contains("webclient") ||
                            implementation.contains("kafka") || implementation.contains("redis") || implementation.contains("elasticsearch")) {
                            hasExternalDeps = true;
                            prompt.append("• External dependency detected in ").append(entry.getKey()).append("\n");
                        }
                    }
                    
                    if (hasExternalDeps) {
                        prompt.append("→ GUIDANCE: Create INTEGRATION scenarios for code paths using external dependencies\n");
                        prompt.append("→ GUIDANCE: Create UNIT scenarios for pure business logic that doesn't use external dependencies\n");
                    } else {
                        prompt.append("→ GUIDANCE: Most scenarios should be UNIT type (no external dependencies detected)\n");
                    }
                    
                    prompt.append("\n=== ANALYZED CLASS IMPLEMENTATIONS ===\n");
                    for (var entry : analyzedClasses.entrySet()) {
                        prompt.append(String.format("Class: %s\n", entry.getKey()));
                        String implementation = entry.getValue();
                        // Include full implementation context without truncation
                        prompt.append(implementation).append("\n\n");
                    }
                }
                
                // Add file contents that were read
                Map<String, String> readFiles = contextTools.getReadFiles();
                if (!readFiles.isEmpty()) {
                    prompt.append("\n=== RELATED FILES READ ===\n");
                    for (var entry : readFiles.entrySet()) {
                        prompt.append(String.format("File: %s\n", entry.getKey()));
                        String content = entry.getValue();
                        // Include full file content context without truncation
                        prompt.append(content).append("\n\n");
                    }
                }
        } else {
            prompt.append("\nNo context analysis available - analyze the provided code to determine if scenarios need UNIT or INTEGRATION types\n");
        }
        
        prompt.append("\nGenerate 2-5 comprehensive test scenarios covering all aspects of the SELECTED METHODS ONLY.");
        prompt.append("\nDo NOT create scenarios for any methods that are not in the 'SELECTED METHODS TO TEST' list above.");
        prompt.append("\nUse the addTestScenarios tool to add all scenarios at once for the selected methods.");
        
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
            scenarios.clear();
            reasoning = "";
        }
        
        @Tool("Set the target class name for testing")
        public String setTargetClass(String className) {
            notifyTool("setTargetClass", className);
            this.targetClass = className;
            return "Target class set to: " + className;
        }

        @Tool("Add a target method for testing (only if not already specified by user)")
        public String addTargetMethod(String methodName) {
            // Prevent LLM from adding methods beyond user selection
            // Only allow if we have no methods yet (fallback scenario)
            if (this.targetMethods.isEmpty()) {
                notifyTool("addTargetMethod", methodName);
                this.targetMethods.add(methodName);
                return "Added target method: " + methodName + " (Total: " + targetMethods.size() + ")";
            } else {
                return "Target methods already specified by user. Cannot add additional methods: " + methodName;
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
                        "Mixed Test Plan", // Will be determined when plan is complete
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
            
            // Determine overall test type based on scenario types
            TestGenerationRequest.TestType overallTestType = determineOverallTestType();
            
            // Create the test plan
            TestPlan plan = new TestPlan(
                new ArrayList<>(targetMethods),
                targetClass,
                new ArrayList<>(scenarios),
                new ArrayList<>(), // Dependencies will be filled by context
                overallTestType,
                reasoning.isEmpty() ? "Mixed test plan with " + scenarios.size() + " scenarios" : reasoning
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
        
        /**
         * Determine overall test type based on individual scenario types.
         * If any scenario is INTEGRATION, the overall type is INTEGRATION_TESTS.
         * If all scenarios are UNIT, the overall type is UNIT_TESTS.
         */
        private TestGenerationRequest.TestType determineOverallTestType() {
            boolean hasIntegrationScenarios = false;
            boolean hasUnitScenarios = false;
            
            for (TestPlan.TestScenario scenario : scenarios) {
                if (scenario.getType() == TestPlan.TestScenario.Type.INTEGRATION) {
                    hasIntegrationScenarios = true;
                } else if (scenario.getType() == TestPlan.TestScenario.Type.UNIT) {
                    hasUnitScenarios = true;
                }
            }
            
            // If we have any integration scenarios, mark as integration tests
            if (hasIntegrationScenarios) {
                return TestGenerationRequest.TestType.INTEGRATION_TESTS;
            } else if (hasUnitScenarios) {
                return TestGenerationRequest.TestType.UNIT_TESTS;
            } else {
                // Default to integration tests for safety if no scenarios or unclear types
                return TestGenerationRequest.TestType.INTEGRATION_TESTS;
            }
        }
    }
    
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
}