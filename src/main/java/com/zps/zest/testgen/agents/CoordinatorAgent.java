package com.zps.zest.testgen.agents;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.ui.model.TestPlanDisplayData;
import com.zps.zest.testgen.ui.model.ScenarioDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.ChatMemory;
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
    private static final int MAX_TOKENS = 80000; // Max tokens for this agent's memory

    private final TestPlanningTools planningTools;
    private final TestPlanningAssistant assistant;
    private final StreamingTestPlanningAssistant streamingAssistant;
    private final ChatMemory chatMemory;
    private final ContextAgent.ContextGatheringTools contextTools;

    // Flag to stop system message streaming when AI starts responding
    private volatile boolean aiResponseStarted = false;

    public CoordinatorAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull NaiveLLMService naiveLlmService,
                          @Nullable ContextAgent.ContextGatheringTools contextTools) {
        super(project, langChainService, naiveLlmService, "CoordinatorAgent");
        this.contextTools = contextTools;
        this.planningTools = new TestPlanningTools(this);

        // Build the agent with message-based memory (no token estimation needed)
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(50);
        this.assistant = AgenticServices
                .agentBuilder(TestPlanningAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(50) // Allow multiple tool calls in one response
                .chatMemory(chatMemory)
                .tools(planningTools)
                .build();

        // Build streaming assistant for real-time UI updates
        this.streamingAssistant = dev.langchain4j.service.AiServices
                .builder(StreamingTestPlanningAssistant.class)
                .streamingChatModel(getStreamingChatModel())
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

        CRITICAL: Generate test scenarios ONLY for the methods that were selected by the user. Do NOT generate tests for other methods you see in the code, even if they seem related or important. Focus exclusively on the user-selected methods.

        PROCESS (Reasoning → Tools):

        PHASE 1: REASONING (text response - think before acting)

        🔙 STEP-BACK ANALYSIS:
        Before diving into specific scenarios, step back and analyze the big picture:
        - What is the CORE PURPOSE of the selected method(s)?
        - What are REALISTIC failure modes based on signatures, parameters, return types?
        - What did the CONTEXT ANALYSIS reveal about how these methods are actually used?
        - What CATEGORIES of risks exist? (validation errors, null handling, boundary conditions, state management, integration issues)

        Share your step-back analysis (2-3 sentences).

        💭 SCENARIO BRAINSTORMING:
        List potential scenarios by category (brief bullet points):
        ✅ Happy Path: [1-2 key normal scenarios]
        ⚠️ Error Handling: [2-3 error/exception scenarios]
        🎯 Edge Cases: [1-2 boundary/corner cases]
        🔄 Integration: [1-2 scenarios if method has external dependencies]

        📊 PRIORITIZATION REASONING:
        Explain your HIGH/MEDIUM/LOW priority choices based on:
        - User impact (crashes, data loss, wrong results vs minor issues)
        - Usage frequency (from context analysis - what's called often?)
        - Business criticality (financial, security, core functionality)

        PHASE 2: TOOL EXECUTION

        1. Call setTargetClass with fully qualified class name
        2. Call addTestScenarios with ALL scenarios at once (based on your brainstorming above)
        3. Call setTestingNotes with testing approach recommendations

        Prefer quality over quantity. Focus on the selected methods only, preventing potential bugs. Ensure all paths and branches of the SELECTED methods are tested.

        CRITICAL: Each test scenario should have its own test type based on what that specific scenario tests:
        - Scenarios testing pure business logic (calculations, validations) → Use "UNIT" type
        - Scenarios testing database interactions, external APIs, file I/O → Use "INTEGRATION" type
        - Analyze each scenario individually - one test class can have both unit and integration scenarios
        - Use the context analysis to determine what each scenario needs to test

        TESTING STRATEGY:
        - UNIT tests: For pure business logic only - no external dependencies, no mocking needed
        - INTEGRATION tests: For code with external dependencies - use real test infrastructure:
          * Databases: Use Testcontainers (PostgreSQLContainer, MySQLContainer, etc.)
          * Message queues: Use Testcontainers (KafkaContainer, RabbitMQContainer, etc.)
          * HTTP APIs: Use WireMock or MockWebServer
          * File operations: Use @TempDir for temporary test directories
        - Avoid mocking frameworks when possible - prefer real test infrastructure for more reliable tests

        TEST LIFECYCLE MANAGEMENT:

        PREREQUISITES - What must be true before test runs:
        - Service state: "Service is initialized and ready"
        - Data conditions: "Test data exists in data store"
        - Dependencies: "External dependency returns expected responses"

        SETUP APPROACHES (describe WHAT, not framework-specific HOW):
        - Class-level setup: When multiple tests need same initialization
        - Test-level setup: When each test needs unique preparation
        Examples:
        - "Initialize service with test configuration"
        - "Prepare test data set with 3 valid items"
        - "Configure test double to return success response"

        TEARDOWN APPROACHES (describe WHAT to clean):
        - Class-level teardown: When cleaning up shared resources
        - Test-level teardown: When each test has unique cleanup
        Examples:
        - "Remove all test-created data"
        - "Reset service to initial state"
        - "Release acquired resources"

        ISOLATION STRATEGIES (prevent data pollution between tests):
        - INDEPENDENT: No shared state, each test creates/destroys everything
        - SHARED_FIXTURE: Tests share initial setup but clean up changes
        - RESET_BETWEEN: Reset shared resources (mocks, caches, state) between tests
        - SEPARATE_INSTANCE: Each test gets fresh instances of dependencies

        CHOOSING ISOLATION:
        - Pure logic tests → INDEPENDENT
        - Tests using shared infrastructure → SHARED_FIXTURE with cleanup
        - Tests with stateful dependencies → RESET_BETWEEN
        - Tests needing pristine environment → SEPARATE_INSTANCE

        TESTING NOTES GUIDELINES:
        When calling setTestingNotes, provide natural language recommendations:
        - Mention the detected testing framework (JUnit 5, JUnit 4, TestNG)
        - For database tests: recommend "Use TestContainers for database testing"
        - For messaging: recommend "Use TestContainers for message queue testing"
        - For HTTP APIs: recommend "Use WireMock for API mocking"
        - For pure logic: recommend "Direct unit tests, no mocking needed"
        - Include setup/teardown hints if needed: "Set up test infrastructure in @BeforeEach, clean up in @AfterEach"

        DEPENDENCY EXTRACTION (IMPORTANT):
        When you receive project dependencies (pom.xml, build.gradle content), extract ONLY test-relevant libraries and include in your testing notes as a concise list.
        Test-relevant libraries include:
        - Test frameworks: JUnit 4/5, TestNG, Spock, Kotest, etc.
        - Mocking libraries: Mockito, EasyMock, PowerMock, MockK, etc.
        - Assertion libraries: AssertJ, Hamcrest, Google Truth, Strikt, etc.
        - Test utilities: Testcontainers, H2, HSQLDB, WireMock, REST Assured, MockServer, Awaitility, etc.
        - Spring testing: Spring Boot Test Starter, Spring Test, etc.

        Include in testing notes as:
        "Available test libraries: JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers"

        DO NOT include the full build file content in testing notes - only extract the concise list of test libraries.

        TEST PLANNING PRINCIPLES:

        When creating test scenarios, think like a quality assurance expert. Start with the main purpose of the method - what should it do when everything goes right? Create at least one test for normal, expected behavior with valid inputs.

        Then consider what could go wrong: What if someone passes null? What if a list is empty? What if numbers are negative when they should be positive? What if a required resource isn't available?

        Don't forget boundaries: If the method accepts numbers 1-100, test with 0, 1, 100, and 101. For lists, test empty, single item, and many items. For strings, test empty strings, very long strings, and special characters.

        CHOOSING TEST TYPES:
        - Use UNIT when testing pure logic like calculations or validations that don't need external systems
        - Use INTEGRATION when code talks to databases, APIs, or files
        - Use EDGE_CASE when testing boundary conditions or unusual but valid inputs
        - Use ERROR_HANDLING when specifically testing how code handles failures and invalid inputs

        DETERMINING PRIORITY:
        - HIGH: Main functionality users depend on, could cause data loss/security issues, happy paths that must work
        - MEDIUM: Common error scenarios users might encounter, important but not critical functionality
        - LOW: Rare scenarios, minor impact if they fail, completeness rather than critical functionality

        WRITING TEST INPUTS:
        Be specific about test data. Instead of "invalid data", specify exactly what makes it invalid:
        Good: "null customer object", "empty string ''", "negative number -5", "list with 1000 items"
        Poor: "bad data", "invalid input", "wrong value"

        WRITING EXPECTED OUTCOMES:
        Be clear about what should happen with verifiable outcomes:
        Good: "Returns sum of 150", "Throws IllegalArgumentException with message 'Age cannot be negative'"
        Poor: "Works correctly", "Handles the error", "Returns the right value"

        COVERAGE STRATEGY:
        For each method, aim to cover:
        1. The normal case - typical inputs working as expected
        2. The null/empty case - missing or empty data handling
        3. The boundary cases - minimum/maximum values and limits
        4. The error case - invalid inputs that should be rejected
        5. The integration case (if applicable) - external system interactions

        Remember: Quality over quantity. Five well-thought-out tests are better than twenty random ones. Each test should have a clear purpose and test one specific scenario.

        RESPONSE FORMAT:
        1. First, share your reasoning (STEP-BACK ANALYSIS, SCENARIO BRAINSTORMING, PRIORITIZATION)
        2. Then, use tools (setTargetClass, addTestScenarios, setTestingNotes)

        Generate multiple scenarios in a single addTestScenarios call for the selected methods only.
        The number of test scenarios to generate will be specified in the user's request - follow that guideline.
        """)
        @dev.langchain4j.agentic.Agent
        String planTests(String request);
    }

    /**
     * Streaming interface - generates all scenarios with real-time token streaming
     */
    public interface StreamingTestPlanningAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test planning assistant that creates comprehensive test plans.

        CRITICAL: Generate test scenarios ONLY for the methods that were selected by the user. Do NOT generate tests for other methods you see in the code, even if they seem related or important. Focus exclusively on the user-selected methods.

        PROCESS (Reasoning → Tools):

        PHASE 1: REASONING (text response - think before acting)

        🔙 STEP-BACK ANALYSIS:
        Before diving into specific scenarios, step back and analyze the big picture:
        - What is the CORE PURPOSE of the selected method(s)?
        - What are REALISTIC failure modes based on signatures, parameters, return types?
        - What did the CONTEXT ANALYSIS reveal about how these methods are actually used?
        - What CATEGORIES of risks exist? (validation errors, null handling, boundary conditions, state management, integration issues)

        Share your step-back analysis (2-3 sentences).

        💭 SCENARIO BRAINSTORMING:
        List potential scenarios by category (brief bullet points):
        ✅ Happy Path: [1-2 key normal scenarios]
        ⚠️ Error Handling: [2-3 error/exception scenarios]
        🎯 Edge Cases: [1-2 boundary/corner cases]
        🔄 Integration: [1-2 scenarios if method has external dependencies]

        📊 PRIORITIZATION REASONING:
        Explain your HIGH/MEDIUM/LOW priority choices based on:
        - User impact (crashes, data loss, wrong results vs minor issues)
        - Usage frequency (from context analysis - what's called often?)
        - Business criticality (financial, security, core functionality)

        PHASE 2: TOOL EXECUTION

        1. Call setTargetClass with fully qualified class name
        2. Call addTestScenarios with ALL scenarios at once (based on your brainstorming above)
        3. Call setTestingNotes with testing approach recommendations

        CRITICAL: Both phases are REQUIRED. Don't skip the reasoning - it helps users understand your testing strategy.

        Remember: This is a SINGLE response workflow - reason thoroughly, then execute all tools in sequence. User sees real-time streaming during this process.

        The number of test scenarios to generate will be specified in the user's request - follow that guideline.
        """)
        @dev.langchain4j.agentic.Agent
        dev.langchain4j.service.TokenStream planTests(String request);
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
                sendToUI("🎯 Planning comprehensive test scenarios...\n\n");
                
                // Reset planning tools for new session
                planningTools.reset();
                planningTools.setToolNotifier(this::sendToUI);
                planningTools.setPlanUpdateCallback(planUpdateCallback);

                for (PsiMethod psiMethod : request.getTargetMethods()) {
                    String name = psiMethod.getName();
                    planningTools.targetMethods.add(name);
                }

                // Usage analysis is now pre-computed in ContextGatheringHandler before AI exploration
                // No need to call contextTools.analyzeMethodUsages() here - it's already done

                // Build the planning request
                String planRequest = buildPlanningRequest(request);

                // Reset AI response flag for new session
                aiResponseStarted = false;

                // Stream the system message to UI to show activity while AI processes
                String systemMessage = getSystemMessage();
                streamTextAsync("📋 System Instructions:\n\n" + systemMessage + "\n\n" + "-".repeat(60) + "\n\n");
                streamTextAsync("📝 User Request:\n\n" + planRequest + "\n\n" + "-".repeat(60) + "\n\n");
                streamTextAsync("🧠 AI Response:\n\n");

                // Check cancellation before making assistant call
                checkCancellation();

                // Use streaming assistant with TokenStream for real-time UI updates
                final java.util.concurrent.CompletableFuture<TestPlan> planFuture = new java.util.concurrent.CompletableFuture<>();
                final StringBuilder responseBuilder = new StringBuilder();

                // Start streaming
                dev.langchain4j.service.TokenStream tokenStream = streamingAssistant.planTests(planRequest);

                tokenStream
                    .onPartialResponse(partialResponse -> {
                        // Mark that AI has started responding (stops system message streaming)
                        if (!aiResponseStarted) {
                            aiResponseStarted = true;
                            LOG.debug("AI response started - system message streaming will stop");
                        }

                        // Accumulate response text
                        responseBuilder.append(partialResponse);

                        // Stream AI response directly (no delay - real-time)
                        sendToUI(partialResponse);
                    })
                    .onIntermediateResponse(response -> {
                        // AI finished reasoning text, about to execute tools
                        streamTextAsync("\n\n🔧 Executing tools...\n");
                        LOG.info("Intermediate response: " + response.aiMessage().toolExecutionRequests().size() + " tools to execute");
                    })
                    .beforeToolExecution(beforeToolExecution -> {
                        // Tool execution already handled by planningTools.notifyTool()
                        LOG.info("Tool execution starting: " + beforeToolExecution.request().name());
                    })
                    .onToolExecuted(toolExecution -> {
                        // Tool results already sent to UI via planningTools callback
                        LOG.info("Tool execution completed: " + toolExecution.request().name());
                    })
                    .onCompleteResponse(response -> {
                        String fullResponse = responseBuilder.toString();
                        streamTextAsync("\n\n" + "-".repeat(40) + "\n");

                        // Build the test plan from accumulated tool data
                        TestPlan testPlan = planningTools.buildTestPlan();

                        // Summary
                        sendToUI("\n📊 Test Plan Summary:\n");
                        sendToUI("  - Target: " + testPlan.getTargetClass() + "." + String.join(", ", testPlan.getTargetMethods()) + "\n");
                        sendToUI("  - Overall Type: " + testPlan.getRecommendedTestType().getDescription() + "\n");

                        // Count unit vs integration scenarios
                        long unitScenarios = testPlan.getTestScenarios().stream()
                                .filter(s -> s.getType() == TestPlan.TestScenario.Type.UNIT)
                                .count();
                        long integrationScenarios = testPlan.getTestScenarios().stream()
                                .filter(s -> s.getType() == TestPlan.TestScenario.Type.INTEGRATION)
                                .count();

                        sendToUI("  - Scenarios: " + testPlan.getScenarioCount() + " total (" +
                                unitScenarios + " unit, " + integrationScenarios + " integration)\n");

                        LOG.debug("Test planning complete: " + testPlan.getScenarioCount() + " scenarios");

                        planFuture.complete(testPlan);
                    })
                    .onError(error -> {
                        LOG.error("Streaming failed", error);
                        sendToUI("\n❌ Test planning failed: " + error.getMessage() + "\n");
                        planFuture.completeExceptionally(error);
                    })
                    .start();

                // Wait for completion
                return planFuture.get();

            } catch (java.util.concurrent.CancellationException e) {
                LOG.info("Test planning cancelled by user");
                sendToUI("\n🚫 Test planning cancelled by user.\n");
                throw e; // Re-throw to propagate cancellation
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

        // File information (wrap PSI access in read action)
        String fileName = ReadAction.compute(() -> request.getTargetFile().getName());
        prompt.append("File: ").append(fileName).append("\n");

        // Explicitly list the selected methods to test (wrap PSI access in read action)
        prompt.append("SELECTED METHODS TO TEST (generate scenarios ONLY for these methods):\n");
        String selectedMethods = ReadAction.compute(() -> {
            StringBuilder methods = new StringBuilder();
            for (var method : request.getTargetMethods()) {
                methods.append("- ").append(method.getName()).append("()\n");
            }
            return methods.toString();
        });
        prompt.append(selectedMethods);
        prompt.append("\n");

        // Add configuration
        TestGenerationConfig config = request.getConfig();
        prompt.append("**TEST GENERATION CONFIGURATION**\n");
        prompt.append("```\n");
        prompt.append(config.toPromptDescription());
        prompt.append("```\n\n");
        
        // Skip adding raw code here since it's already included in analyzed class implementations below
        
        // Add basic framework info
        prompt.append("\nFramework Information:\n");
        if (contextTools != null) {
            prompt.append("- Testing Framework: ").append(contextTools.getFrameworkInfo()).append("\n");

            // Analyze build files and create focused dependency notes
            Map<String, String> buildFiles = contextTools.getBuildFiles();
            if (!buildFiles.isEmpty()) {
                String dependencyNotes = analyzeDependencies(buildFiles, request, contextTools);
                // Store as context note so it's part of the context
                contextTools.takeNote(dependencyNotes);
                LOG.info("Dependency analysis created and stored as context note");
            } else {
                LOG.info("No build files found, skipping dependency analysis");
            }
        } else {
            prompt.append("- Testing Framework: JUnit 5 (default)\n");
        }

        // Include context analysis with token-aware summarization
        if (contextTools != null) {
                // Add context notes (with deduplication)
                List<String> contextNotes = contextTools.getContextNotes();
                if (!contextNotes.isEmpty()) {
                    prompt.append("\n**DETAILED CONTEXT ANALYSIS**\n");
                    prompt.append("```\n");
                    prompt.append("Context Agent Findings (use this for test planning decisions):\n");

                    int noteNum = 1;
                    for (String note : contextNotes) {
                        prompt.append(String.format("%d. %s\n\n", noteNum++, note));
                    }
                    prompt.append("```\n\n");
                }

                // Add method usage patterns (NEW RICH DATA from analyzeMethodUsage tool)
                Map<String, com.zps.zest.testgen.analysis.UsageContext> methodUsages = contextTools.getMethodUsages();
                if (!methodUsages.isEmpty()) {
                    prompt.append("**METHOD USAGE PATTERNS (FROM REAL CODE ANALYSIS)**\n");
                    prompt.append("```\n");
                    prompt.append("The following shows how target methods are ACTUALLY used in the codebase.\n");
                    prompt.append("Use this to plan realistic test scenarios based on real usage patterns:\n");
                    prompt.append("- Error handling patterns → Plan appropriate error test scenarios\n");
                    prompt.append("- Edge cases from actual code → Include in test plan\n");
                    prompt.append("- Integration patterns (transactions, async, events) → Plan integration vs unit tests\n");
                    prompt.append("- Call site contexts (Controller, Service, Test) → Understand architectural layers\n\n");

                    for (Map.Entry<String, com.zps.zest.testgen.analysis.UsageContext> entry : methodUsages.entrySet()) {
                        String methodName = entry.getKey();
                        com.zps.zest.testgen.analysis.UsageContext usage = entry.getValue();

                        if (!usage.isEmpty()) {
                            prompt.append("Method: ").append(methodName).append("\n");
                            prompt.append(usage.formatForLLM()).append("\n");
                        }
                    }
                    prompt.append("```\n\n");
                }

                // Add analyzed classes
                Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
                if (!analyzedClasses.isEmpty()) {
                    prompt.append("**DEPENDENCY ANALYSIS FOR TEST TYPE DECISIONS**\n");
                    prompt.append("```\n");
                    prompt.append("→ GUIDANCE: Create INTEGRATION scenarios for code paths using external dependencies\n");
                    prompt.append("→ GUIDANCE: Create UNIT scenarios for pure business logic that doesn't use external dependencies\n");
                    prompt.append("```\n\n");

                    prompt.append("**ANALYZED CLASS IMPLEMENTATIONS**\n");
                    prompt.append("```\n");

                    for (var entry : analyzedClasses.entrySet()) {
                        prompt.append(String.format("Class: %s\n", entry.getKey()));
                        prompt.append(entry.getValue()).append("\n\n");
                    }
                    prompt.append("```\n\n");
                }

                // Add file contents
                Map<String, String> readFiles = contextTools.getReadFiles();
                if (!readFiles.isEmpty()) {
                    prompt.append("**RELATED FILES READ**\n");
                    prompt.append("```\n");

                    for (var entry : readFiles.entrySet()) {
                        prompt.append(String.format("File: %s\n", entry.getKey()));
                        prompt.append(entry.getValue()).append("\n\n");
                    }
                    prompt.append("```\n\n");
                }
        } else {
            prompt.append("\nNo context analysis available - analyze the provided code to determine if scenarios need UNIT or INTEGRATION types\n");
        }

        // Use the config variable already declared at the beginning of the method
        prompt.append("\nGenerate test scenarios according to the configuration above.");
        prompt.append("\nIMPORTANT: Target " + config.getTestsPerMethod() + " test scenarios per method.");
        prompt.append("\nDo NOT create scenarios for any methods that are not in the 'SELECTED METHODS TO TEST' list above.");
        prompt.append("\nUse the addTestScenarios tool to add all scenarios at once for the selected methods.");

        // Add filter instructions if applicable
        if (!config.getTestTypeFilters().isEmpty()) {
            prompt.append("\nTest Type Focus: ");
            config.getTestTypeFilters().forEach(type ->
                prompt.append(type.getDisplayName()).append(", ")
            );
            prompt.setLength(prompt.length() - 2);
        }

        if (!config.getPriorityFilters().isEmpty()) {
            prompt.append("\nPriority Filter: Include only ");
            config.getPriorityFilters().forEach(priority ->
                prompt.append(priority.getDisplayName()).append(", ")
            );
            prompt.setLength(prompt.length() - 2);
            prompt.append(" scenarios");
        }

        if (!config.getCoverageTargets().isEmpty()) {
            prompt.append("\nCoverage Focus: ");
            config.getCoverageTargets().forEach(target ->
                prompt.append(target.getDisplayName()).append(", ")
            );
            prompt.setLength(prompt.length() - 2);
        }

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
        private String testingNotes = "";
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
            testingNotes = "";
        }
        
        @Tool("Set the target class name for testing. className should be fully qualified name")
        public String setTargetClass(String className) {
            notifyTool("setTargetClass", className);
            this.targetClass = className;
            return "Target class set to: " + className;
        }


        @Tool("Add multiple test scenarios to the plan. Each scenario must include: " +
              "1) name: Test method name (e.g., 'testCalculateTotal_WithValidInput_ReturnsSum'), " +
              "2) description: Detailed explanation of what the test verifies, " +
              "3) type: UNIT (pure logic), INTEGRATION (external systems), EDGE_CASE (boundaries), or ERROR_HANDLING (exceptions), " +
              "4) inputs: List of specific test data (e.g., ['null value', 'empty string', 'valid data 123']), " +
              "5) expectedOutcome: What the test should assert (e.g., 'Returns 150', 'Throws IllegalArgumentException'), " +
              "6) priority: HIGH (critical path), MEDIUM (important cases), or LOW (nice-to-have), " +
              "7) prerequisites: Conditions needed (e.g., ['Service initialized', 'Test data exists']), " +
              "8) setupSteps: What to prepare (e.g., ['Initialize service', 'Create test data']), " +
              "9) teardownSteps: What to cleanup (e.g., ['Remove test data', 'Reset state']), " +
              "10) isolationStrategy: INDEPENDENT, SHARED_FIXTURE, RESET_BETWEEN, or SEPARATE_INSTANCE. " +
              "\n\nIMPORTANT - Enum Value References: " +
              "Use exact enum names (case-sensitive): " +
              "type = UNIT|INTEGRATION|EDGE_CASE|ERROR_HANDLING, " +
              "priority = HIGH|MEDIUM|LOW, " +
              "isolationStrategy = INDEPENDENT|SHARED_FIXTURE|RESET_BETWEEN|SEPARATE_INSTANCE. " +
              "Return type: List<com.zps.zest.testgen.model.TestPlan$TestScenario>")
        public String addTestScenarios(List<TestPlan.TestScenario> testScenarios) {
            notifyTool("addTestScenarios", testScenarios.size() + " scenarios");
            int startCount = scenarios.size();

            for (TestPlan.TestScenario scenario : testScenarios) {
                scenarios.add(scenario);
                
                // Send scenario to UI immediately
                if (coordinatorAgent != null) {
                    ScenarioDisplayData.Priority displayPriority = ScenarioDisplayData.Priority.MEDIUM;
                    try {
                        displayPriority = ScenarioDisplayData.Priority.valueOf(scenario.getPriority().toString());
                    } catch (Exception e) {
                        // Keep default MEDIUM
                    }

                    ScenarioDisplayData scenarioData = new ScenarioDisplayData(
                        "scenario_" + scenario.getName().hashCode(),
                        scenario.getName(),
                        scenario.getDescription(),
                        displayPriority,
                        scenario.getType().getDisplayName(), // category
                        new ArrayList<>(scenario.getPrerequisites()), // prerequisites
                        new ArrayList<>(scenario.getSetupSteps()), // setupSteps
                        new ArrayList<>(), // executionSteps
                        new ArrayList<>(), // assertions
                        new ArrayList<>(scenario.getTeardownSteps()), // teardownSteps
                        scenario.getIsolationStrategy().getDescription(), // isolationStrategy
                        new ArrayList<>(scenario.getInputs()), // inputs from scenario
                        scenario.getExpectedOutcome(), // expectedOutcome from scenario
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
        
        
        @Tool("Set the reasoning for the test plan")
        public String setReasoning(String reasoning) {
            notifyTool("setReasoning", reasoning.length() > 50 ? reasoning.substring(0, 50) + "..." : reasoning);
            this.reasoning = reasoning;
            return "Reasoning recorded";
        }

        @Tool("Set natural language testing approach notes for the test plan")
        public String setTestingNotes(String notes) {
            notifyTool("setTestingNotes", notes.length() > 50 ? notes.substring(0, 50) + "..." : notes);
            this.testingNotes = notes;
            return "Testing notes recorded: " + notes;
        }

        /**
         * Generate smart testing notes based on context analysis (fallback if AI doesn't set)
         */
        private String generateTestingNotes() {
            StringBuilder notes = new StringBuilder();

            // Analyze scenarios to determine testing approach
            boolean hasDatabase = scenarios.stream()
                .anyMatch(s -> s.getDescription().toLowerCase().contains("database") ||
                              s.getDescription().toLowerCase().contains("repository") ||
                              s.getDescription().toLowerCase().contains("jpa"));

            boolean hasMessaging = scenarios.stream()
                .anyMatch(s -> s.getDescription().toLowerCase().contains("kafka") ||
                              s.getDescription().toLowerCase().contains("rabbitmq") ||
                              s.getDescription().toLowerCase().contains("message"));

            boolean hasHttpApi = scenarios.stream()
                .anyMatch(s -> s.getDescription().toLowerCase().contains("api") ||
                              s.getDescription().toLowerCase().contains("http") ||
                              s.getDescription().toLowerCase().contains("rest"));

            boolean hasPureLogic = scenarios.stream()
                .anyMatch(s -> s.getType() == TestPlan.TestScenario.Type.UNIT);

            // Get framework info from context if available
            String frameworkInfo = coordinatorAgent.contextTools != null ?
                coordinatorAgent.contextTools.getFrameworkInfo() : "JUnit 5";

            notes.append("Testing framework: ").append(frameworkInfo).append(". ");

            // Add testing approach based on scenario analysis
            if (hasDatabase) {
                notes.append("Use TestContainers for database testing. ");
            }
            if (hasMessaging) {
                notes.append("Use TestContainers for message queue testing. ");
            }
            if (hasHttpApi) {
                notes.append("Use WireMock for API mocking. ");
            }
            if (hasPureLogic) {
                notes.append("Direct unit tests for business logic, no mocking needed. ");
            }

            // Add setup/teardown hints if integration tests present
            if (scenarios.stream().anyMatch(s -> s.getType() == TestPlan.TestScenario.Type.INTEGRATION)) {
                notes.append("Set up test infrastructure in @BeforeEach, clean up in @AfterEach. ");
            }

            return notes.toString().trim();
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

            // Use AI-generated notes if available, otherwise generate fallback
            String notes = !testingNotes.isEmpty() ? testingNotes : generateTestingNotes();
            plan.setTestingNotes(notes);
            
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
                        new ArrayList<>(scenario.getPrerequisites()), // prerequisites
                        new ArrayList<>(scenario.getSetupSteps()), // setupSteps
                        new ArrayList<>(), // executionSteps
                        new ArrayList<>(), // assertions
                        new ArrayList<>(scenario.getTeardownSteps()), // teardownSteps
                        scenario.getIsolationStrategy().getDescription(), // isolationStrategy
                        new ArrayList<>(scenario.getInputs()), // inputs from scenario
                        scenario.getExpectedOutcome(), // expectedOutcome from scenario
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

        @Tool("Read file content. Use for deep investigation if needed during planning.")
        public String readFile(String filePath) {
            if (coordinatorAgent.contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("readFile", filePath);
            return coordinatorAgent.contextTools.readFile(filePath);
        }

        @Tool("Search code for patterns. Use if you need to explore code during planning.")
        public String searchCode(String query, String filePattern, String excludePattern,
                                Integer beforeLines, Integer afterLines, Boolean multiline) {
            if (coordinatorAgent.contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("searchCode", query);
            return coordinatorAgent.contextTools.searchCode(query, filePattern, excludePattern, beforeLines, afterLines, multiline);
        }

        @Tool("Look up method signature from project or library. Use if you need exact method details during planning.")
        public String lookupMethod(String className, String methodName) {
            if (coordinatorAgent.contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("lookupMethod", className + "." + methodName);
            return coordinatorAgent.contextTools.lookupMethod(className, methodName);
        }

        @Tool("Look up class structure. Use if you need to understand dependencies during planning.")
        public String lookupClass(String className) {
            if (coordinatorAgent.contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("lookupClass", className);
            return coordinatorAgent.contextTools.lookupClass(className);
        }
    }

    /**
     * Analyze build files and create focused dependency notes for test generation.
     * Returns notes about test framework, mocking libs, code dependencies, and integration test tools.
     */
    private String analyzeDependencies(
            @NotNull Map<String, String> buildFiles,
            @NotNull TestGenerationRequest request,
            @NotNull ContextAgent.ContextGatheringTools contextTools) {

        if (buildFiles.isEmpty()) {
            return "[DEPENDENCY_ANALYSIS] No build files found - using defaults (JUnit 5)";
        }

        // Build LLM prompt for dependency analysis
        StringBuilder analysisPrompt = new StringBuilder();
        analysisPrompt.append("Analyze build files and extract focused dependency information for test generation.\n\n");

        analysisPrompt.append("**BUILD FILES**\n");
        analysisPrompt.append("```\n");
        for (Map.Entry<String, String> entry : buildFiles.entrySet()) {
            String fileName = entry.getKey().substring(Math.max(
                    entry.getKey().lastIndexOf('/'),
                    entry.getKey().lastIndexOf('\\')) + 1);
            analysisPrompt.append("File: ").append(fileName).append("\n");
            analysisPrompt.append(entry.getValue()).append("\n\n");
        }
        analysisPrompt.append("```\n\n");

        analysisPrompt.append("**CODE UNDER TEST**\n");
        analysisPrompt.append("```\n");
        analysisPrompt.append("Target Class(es): ");
        // Wrap PSI access in read action since this runs in background thread
        String targetClasses = ReadAction.compute(() -> {
            StringBuilder classes = new StringBuilder();
            for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                if (method.getContainingClass() != null) {
                    classes.append(method.getContainingClass().getQualifiedName()).append(" ");
                }
            }
            return classes.toString();
        });
        analysisPrompt.append(targetClasses);
        analysisPrompt.append("\n");
        analysisPrompt.append("```\n\n");

        // Include analyzed classes to see what the code actually uses
        Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
        if (!analyzedClasses.isEmpty()) {
            analysisPrompt.append("**CODE DEPENDENCIES (what the code actually uses)**\n");
            analysisPrompt.append("```\n");
            for (String className : analyzedClasses.keySet()) {
                analysisPrompt.append("- ").append(className).append("\n");
            }
            analysisPrompt.append("```\n\n");
        }

        analysisPrompt.append("**EXTRACT (be specific with versions)**\n");
        analysisPrompt.append("```\n");
        analysisPrompt.append("1. **Testing Framework**: [name] ([version]) - [usage notes like '@Test, @BeforeEach']\n");
        analysisPrompt.append("   Example: JUnit 5 (5.9.3) - Use @Test, @BeforeEach, @AfterEach, Assertions.*\n\n");

        analysisPrompt.append("2. **Mocking/Test Utilities**: [list with versions]\n");
        analysisPrompt.append("   Example: Mockito 4.8.0 for mocking dependencies, AssertJ 3.24.0 for fluent assertions\n\n");

        analysisPrompt.append("3. **Code Dependencies**: [what the code under test actually uses]\n");
        analysisPrompt.append("   Example: Spring Data JPA 3.0.0 (code uses @Repository, @Entity), PostgreSQL JDBC 42.5.0\n\n");

        analysisPrompt.append("4. **Integration Test Tools**: [available tools for real dependencies]\n");
        analysisPrompt.append("   Example: Testcontainers PostgreSQL 1.17.6 - use for real DB tests, Spring Boot Test 3.0.0 - provides @SpringBootTest, @DataJpaTest\n\n");

        analysisPrompt.append("IMPORTANT:\n");
        analysisPrompt.append("- Include versions for all dependencies\n");
        analysisPrompt.append("- Focus ONLY on test-relevant dependencies\n");
        analysisPrompt.append("- Explain what each dependency is used for\n");
        analysisPrompt.append("- Be concise (max 200 words total)\n");
        analysisPrompt.append("- Start response with '[DEPENDENCY_ANALYSIS]'\n");
        analysisPrompt.append("```\n");

        try {
            // Use naive LLM service for analysis
            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(analysisPrompt.toString())
                    .useLiteCodeModel()
                    .withMaxTokens(500)
                    .withTemperature(0.2); // Low temperature for factual extraction

            String analysis = naiveLlmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_COORDINATOR);

            // Ensure it starts with the marker
            if (!analysis.startsWith("[DEPENDENCY_ANALYSIS]")) {
                analysis = "[DEPENDENCY_ANALYSIS] " + analysis;
            }

            LOG.info("Dependency analysis complete: " + analysis.length() + " characters");
            return analysis;

        } catch (Exception e) {
            LOG.warn("Failed to analyze dependencies: " + e.getMessage(), e);
            return "[DEPENDENCY_ANALYSIS] Failed to analyze build files - using JUnit 5 defaults";
        }
    }

    @NotNull
    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    /**
     * Get the system message from StreamingTestPlanningAssistant annotation
     */
    private static final String SYSTEM_MESSAGE = """
        You are a test planning assistant that creates comprehensive test plans.

        CRITICAL: Generate test scenarios ONLY for the methods that were selected by the user. Do NOT generate tests for other methods you see in the code, even if they seem related or important. Focus exclusively on the user-selected methods.

        PROCESS (Reasoning → Tools):

        PHASE 1: REASONING (text response - think before acting)

        🔙 STEP-BACK ANALYSIS:
        Before diving into specific scenarios, step back and analyze the big picture:
        - What is the CORE PURPOSE of the selected method(s)?
        - What are REALISTIC failure modes based on signatures, parameters, return types?
        - What did the CONTEXT ANALYSIS reveal about how these methods are actually used?
        - What CATEGORIES of risks exist? (validation errors, null handling, boundary conditions, state management, integration issues)

        Share your step-back analysis (2-3 sentences).

        💭 SCENARIO BRAINSTORMING:
        List potential scenarios by category (brief bullet points):
        ✅ Happy Path: [1-2 key normal scenarios]
        ⚠️ Error Handling: [2-3 error/exception scenarios]
        🎯 Edge Cases: [1-2 boundary/corner cases]
        🔄 Integration: [1-2 scenarios if method has external dependencies]

        📊 PRIORITIZATION REASONING:
        Explain your HIGH/MEDIUM/LOW priority choices based on:
        - User impact (crashes, data loss, wrong results vs minor issues)
        - Usage frequency (from context analysis - what's called often?)
        - Business criticality (financial, security, core functionality)

        PHASE 2: TOOL EXECUTION

        1. Call setTargetClass with fully qualified class name
        2. Call addTestScenarios with ALL scenarios at once (based on your brainstorming above)
        3. Call setTestingNotes with testing approach recommendations

        CRITICAL: Both phases are REQUIRED. Don't skip the reasoning - it helps users understand your testing strategy.

        Remember: This is a SINGLE response workflow - reason thoroughly, then execute all tools in sequence. User sees real-time streaming during this process.

        The number of test scenarios to generate will be specified in the user's request - follow that guideline.
        """;

    private String getSystemMessage() {
        return SYSTEM_MESSAGE;
    }

    /**
     * Stream text asynchronously using ForkJoinPool, character by character for realistic typing effect
     * Stops if AI response has started (to skip remaining system message chunks)
     */
    private void streamTextAsync(String text) {
        java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {
            int chunkSize = 50;
            for (int i = 0; i < text.length(); i += chunkSize) {
                // Stop streaming if AI has started responding
                if (aiResponseStarted) {
                    LOG.debug("Skipping remaining system message - AI response started");
                    break;
                }

                int end = Math.min(i + chunkSize, text.length());
                String chunk = text.substring(i, end);
                sendToUI(chunk);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}