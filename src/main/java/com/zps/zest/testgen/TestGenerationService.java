package com.zps.zest.testgen;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.agents.*;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.util.TestGenerationProgressTracker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class TestGenerationService {
    private static final Logger LOG = Logger.getInstance(TestGenerationService.class);
    private static final boolean DEBUG_STREAMING = true; // Debug flag for streaming
    
    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final LLMService llmService;
    
    // Agents
    private final CoordinatorAgent coordinatorAgent;
    private final ContextAgent contextAgent;
    private final TestWriterAgent testWriterAgent;
    private final ValidatorAgent validatorAgent;
    private final TestMergerAgent testMergerAgent;
    
    // Active sessions
    private final Map<String, TestGenerationSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, List<TestGenerationProgressListener>> sessionListeners = new ConcurrentHashMap<>();
    private final Map<String, TestGenerationProgressTracker> progressTrackers = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.Consumer<String>> streamingConsumers = new ConcurrentHashMap<>();
    
    // For tracking multi-method test generation
    private final Map<String, List<TestPlan>> methodTestPlans = new ConcurrentHashMap<>();
    private final Map<String, TestPlan> mergedTestPlans = new ConcurrentHashMap<>();
    
    public TestGenerationService(@NotNull Project project) {
        this.project = project;
        this.langChainService = project.getService(ZestLangChain4jService.class);
        this.llmService = project.getService(LLMService.class);
        
        // Initialize agents
        this.coordinatorAgent = new CoordinatorAgent(project, langChainService, llmService);
        this.contextAgent = new ContextAgent(project, langChainService, llmService);
        this.testWriterAgent = new TestWriterAgent(project, langChainService, llmService);
        this.validatorAgent = new ValidatorAgent(project, langChainService, llmService);
        this.testMergerAgent = new TestMergerAgent(project, langChainService, llmService);
        
        LOG.info("TestGenerationService initialized for project: " + project.getName());
    }
    
    /**
     * Start a new test generation session with optional streaming consumer
     */
    @NotNull
    public CompletableFuture<TestGenerationSession> startTestGeneration(@NotNull TestGenerationRequest request,
                                                                        @Nullable java.util.function.Consumer<String> streamingConsumer) {
        String sessionId = UUID.randomUUID().toString();
        
        // Register streaming consumer BEFORE starting the workflow
        if (streamingConsumer != null) {
            streamingConsumers.put(sessionId, streamingConsumer);
            if (DEBUG_STREAMING) {
                System.out.println("[DEBUG-STREAMING] Pre-registered consumer for session: " + sessionId);
            }
        }
        
        return startTestGenerationInternal(request, sessionId);
    }
    
    /**
     * Start a new test generation session (backward compatibility)
     */
    @NotNull
    public CompletableFuture<TestGenerationSession> startTestGeneration(@NotNull TestGenerationRequest request) {
        return startTestGeneration(request, null);
    }
    
    /**
     * Internal method to start test generation
     */
    @NotNull
    private CompletableFuture<TestGenerationSession> startTestGenerationInternal(@NotNull TestGenerationRequest request,
                                                                                 @NotNull String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting test generation session: " + sessionId);
                
                TestGenerationSession session = new TestGenerationSession(
                    sessionId,
                    request,
                    TestGenerationSession.Status.PLANNING
                );
                
                activeSessions.put(sessionId, session);
                
                // Create and setup progress tracker
                TestGenerationProgressTracker tracker = new TestGenerationProgressTracker(sessionId);
                progressTrackers.put(sessionId, tracker);
                
                // Add existing listeners to tracker
                List<TestGenerationProgressListener> listeners = sessionListeners.get(sessionId);
                if (listeners != null) {
                    listeners.forEach(tracker::addListener);
                }
                
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.INITIALIZATION);
                
                // Execute the multi-agent workflow  
                // Consumer is already registered if provided
                executeTestGenerationWorkflow(session);
                
                return session;
                
            } catch (Exception e) {
                LOG.error("Failed to start test generation session: " + sessionId, e);
                TestGenerationSession failedSession = new TestGenerationSession(
                    sessionId,
                    request,
                    TestGenerationSession.Status.FAILED
                );
                failedSession.addError("Failed to start session: " + e.getMessage());
                activeSessions.put(sessionId, failedSession);
                return failedSession;
            }
        });
    }
    
    /**
     * Start method selection phase for test generation
     */
    @NotNull
    public CompletableFuture<List<String>> extractAvailableMethods(@NotNull TestGenerationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract all testable methods from the target file
                return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<List<String>>) () -> {
                        List<String> methods = new ArrayList<>();
                        if (request.getTargetFile() instanceof com.intellij.psi.PsiJavaFile) {
                            com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) request.getTargetFile();
                            for (com.intellij.psi.PsiClass psiClass : javaFile.getClasses()) {
                                for (com.intellij.psi.PsiMethod method : psiClass.getMethods()) {
                                    // Skip constructors and private methods for now
                                    if (!method.isConstructor() && !method.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE)) {
                                        String methodSignature = psiClass.getName() + "." + method.getName() + "(" +
                                            String.join(", ", Arrays.stream(method.getParameterList().getParameters())
                                                .map(p -> p.getType().getPresentableText())
                                                .toArray(String[]::new)) + ")";
                                        methods.add(methodSignature);
                                    }
                                }
                            }
                        }
                        return methods;
                    }
                );
            } catch (Exception e) {
                LOG.error("Failed to extract methods", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Generate test plans for selected methods
     */
    @NotNull
    public CompletableFuture<List<TestPlan>> generateTestPlansForMethods(@NotNull String sessionId, 
                                                                         @NotNull List<String> selectedMethods,
                                                                         @NotNull TestGenerationRequest originalRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<TestPlan> plans = new ArrayList<>();
                TestGenerationSession session = activeSessions.get(sessionId);
                
                if (session == null) {
                    throw new RuntimeException("Session not found: " + sessionId);
                }
                
                // Get streaming consumer for this session
                java.util.function.Consumer<String> streamingConsumer = streamingConsumers.get(sessionId);
                if (streamingConsumer != null && coordinatorAgent instanceof StreamingBaseAgent) {
                    ((StreamingBaseAgent) coordinatorAgent).setStreamingConsumer(streamingConsumer);
                }
                
                // Generate a test plan for each selected method
                for (String methodSignature : selectedMethods) {
                    // Create a request with the specific method
                    Map<String, String> options = new HashMap<>(originalRequest.getOptions());
                    options.put("selectedMethods", methodSignature);
                    
                    TestGenerationRequest methodRequest = new TestGenerationRequest(
                        originalRequest.getTargetFile(),
                        originalRequest.getSelectionStart(),
                        originalRequest.getSelectionEnd(),
                        "Generate tests for " + methodSignature,
                        originalRequest.getTestType(),
                        options
                    );
                    
                    // Generate test plan for this specific method
                    TestPlan plan = coordinatorAgent.planTests(methodRequest, session.getContext()).join();
                    plans.add(plan);
                }
                
                // Store the individual plans
                methodTestPlans.put(sessionId, plans);
                
                return plans;
            } catch (Exception e) {
                LOG.error("Failed to generate test plans for methods", e);
                throw new RuntimeException("Test plan generation failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Merge multiple test plans into a single consolidated plan
     */
    @NotNull
    public TestPlan mergeTestPlans(@NotNull String sessionId, @NotNull List<TestPlan> plans) {
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("No test plans to merge");
        }
        
        if (plans.size() == 1) {
            return plans.get(0);
        }
        
        // Merge all test scenarios from all plans
        List<TestPlan.TestScenario> allScenarios = new ArrayList<>();
        Set<String> allDependencies = new HashSet<>();
        StringBuilder mergedReasoning = new StringBuilder("Merged test plan for multiple methods:\n");
        
        for (TestPlan plan : plans) {
            allScenarios.addAll(plan.getTestScenarios());
            allDependencies.addAll(plan.getDependencies());
            mergedReasoning.append("\n- ").append(plan.getTargetMethod()).append(": ")
                          .append(plan.getScenarioCount()).append(" scenarios");
        }
        
        // Use the first plan's target class and type as the base
        TestPlan firstPlan = plans.get(0);
        TestPlan mergedPlan = new TestPlan(
            "Multiple Methods",
            firstPlan.getTargetClass(),
            allScenarios,
            new ArrayList<>(allDependencies),
            firstPlan.getRecommendedTestType(),
            mergedReasoning.toString()
        );
        
        // Store the merged plan
        mergedTestPlans.put(sessionId, mergedPlan);
        
        return mergedPlan;
    }
    
    /**
     * Continue test generation with selected scenarios
     */
    public void continueWithSelectedScenarios(@NotNull String sessionId, @NotNull List<TestPlan.TestScenario> selectedScenarios) {
        TestGenerationSession session = activeSessions.get(sessionId);
        if (session == null || session.getStatus() != TestGenerationSession.Status.AWAITING_USER_SELECTION) {
            LOG.warn("Cannot continue session " + sessionId + " - not in awaiting selection state");
            return;
        }
        
        // Filter test plan to only include selected scenarios
        TestPlan originalPlan = session.getTestPlan();
        if (originalPlan == null) {
            LOG.error("No test plan found for session " + sessionId);
            return;
        }
        
        // Create filtered test plan with only selected scenarios
        TestPlan filteredPlan = new TestPlan(
            originalPlan.getTargetMethod(),
            originalPlan.getTargetClass(),
            selectedScenarios,
            originalPlan.getDependencies(),
            originalPlan.getRecommendedTestType(),
            originalPlan.getReasoning() + "\n\nUser selected " + selectedScenarios.size() + " of " + originalPlan.getScenarioCount() + " scenarios."
        );
        session.setTestPlan(filteredPlan);
        
        // Continue with the workflow
        executeTestGenerationWorkflowPhase2(session);
    }
    
    /**
     * Execute the multi-agent workflow using ReAct pattern
     */
    private void executeTestGenerationWorkflow(@NotNull TestGenerationSession session) {
        CompletableFuture.runAsync(() -> {
            TestGenerationProgressTracker tracker = progressTrackers.get(session.getSessionId());
            
            try {
                // Get streaming consumer for this session
                java.util.function.Consumer<String> streamingConsumer = streamingConsumers.get(session.getSessionId());

                if (DEBUG_STREAMING) {
                    System.out.println("[DEBUG-STREAMING] executeTestGenerationWorkflow - Session: " + session.getSessionId());
                    System.out.println("[DEBUG-STREAMING] streamingConsumer found? " + (streamingConsumer != null));
                    System.out.println("[DEBUG-STREAMING] Total consumers in map: " + streamingConsumers.size());
                }
                
                // Set streaming consumer for ALL agents if available
                if (streamingConsumer != null) {
                    if (coordinatorAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) coordinatorAgent).setStreamingConsumer(streamingConsumer);
                        if (DEBUG_STREAMING) {
                            System.out.println("[DEBUG-STREAMING] Consumer set for CoordinatorAgent");
                        }
                    }
                    if (contextAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) contextAgent).setStreamingConsumer(streamingConsumer);
                        if (DEBUG_STREAMING) {
                            System.out.println("[DEBUG-STREAMING] Consumer set for ContextAgent");
                        }
                    }
                    if (testWriterAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) testWriterAgent).setStreamingConsumer(streamingConsumer);
                        if (DEBUG_STREAMING) {
                            System.out.println("[DEBUG-STREAMING] Consumer set for TestWriterAgent");
                        }
                    }
                    if (validatorAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) validatorAgent).setStreamingConsumer(streamingConsumer);
                        if (DEBUG_STREAMING) {
                            System.out.println("[DEBUG-STREAMING] Consumer set for ValidatorAgent");
                        }
                    }
                } else {
                    if (DEBUG_STREAMING) {
                        System.out.println("[DEBUG-STREAMING] WARNING: No streaming consumer found for session!");
                        System.out.println("[DEBUG-STREAMING] Available session IDs in map: " + streamingConsumers.keySet());
                    }
                }
                
                // Phase 1: Context Gathering (moved before planning)
                session.setStatus(TestGenerationSession.Status.GATHERING_CONTEXT);
                tracker.completePhase("Initialization complete");
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.CONTEXT_GATHERING);
                tracker.updatePhaseProgress("Exploring codebase and gathering context...", 20);
                
                // Gather context FIRST before planning (pass sessionId for tracking)
                TestContext context = contextAgent.gatherContext(session.getRequest(), null, session.getSessionId()).join();
                session.setContext(context);
                tracker.updatePhaseProgress("Context gathered: " + context.getContextItemCount() + " items", 100);
                tracker.completePhase("Context gathering completed");
                
                // Phase 2: Planning and Coordination (using the gathered context)
                session.setStatus(TestGenerationSession.Status.PLANNING);
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.PLANNING);
                tracker.updatePhaseProgress("Planning test scenarios based on context...", 20);
                
                TestPlan testPlan = coordinatorAgent.planTests(session.getRequest(), context).join();
                session.setTestPlan(testPlan);
                
                System.out.println("[DEBUG-SERVICE] Test plan created with " + testPlan.getScenarioCount() + " scenarios");
                System.out.println("[DEBUG-SERVICE] Test plan set for session: " + session.getSessionId());
                System.out.println("[DEBUG-SERVICE] Target method: " + testPlan.getTargetMethod());
                System.out.println("[DEBUG-SERVICE] Target class: " + testPlan.getTargetClass());
                
                if (testPlan.getTestScenarios().isEmpty()) {
                    throw new RuntimeException("No test scenarios could be planned for the selected code");
                }
                
                tracker.updatePhaseProgress("Test plan created with " + testPlan.getScenarioCount() + " scenarios", 100);
                tracker.completePhase("Test planning completed");
                
                // Force a progress update to trigger UI refresh
                notifyProgress(session.getSessionId(), "Test plan ready with " + testPlan.getScenarioCount() + " scenarios", 50);
                
                // Check if human-in-the-loop is enabled (could be a project setting)
                boolean humanInTheLoop = true; // TODO: Get from project settings
                
                if (humanInTheLoop && testPlan.getScenarioCount() > 0) {
                    // Pause for user selection
                    session.setStatus(TestGenerationSession.Status.AWAITING_USER_SELECTION);
                    tracker.updatePhaseProgress("Waiting for user to select test scenarios...", 100);
                    
                    // Notify listeners that selection is needed
                    notifyProgress(session.getSessionId(), "Please select which test scenarios to generate", 100);
                    
                    // Workflow will continue when continueWithSelectedScenarios is called
                    return;
                }
                
                // If no human-in-the-loop, continue immediately
                executeTestGenerationWorkflowPhase2(session);
                
            } catch (Exception e) {
                LOG.error("Test generation workflow failed for session: " + session.getSessionId(), e);
                session.setStatus(TestGenerationSession.Status.FAILED);
                session.addError("Workflow failed: " + e.getMessage());
                if (tracker != null) {
                    tracker.fail("Workflow failed: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Continue workflow after user selection (Phase 2 onwards)
     */
    private void executeTestGenerationWorkflowPhase2(@NotNull TestGenerationSession session) {
        CompletableFuture.runAsync(() -> {
            TestGenerationProgressTracker tracker = progressTrackers.get(session.getSessionId());
            
            try {
                // Get streaming consumer for this session
                java.util.function.Consumer<String> streamingConsumer = streamingConsumers.get(session.getSessionId());
                
                if (DEBUG_STREAMING) {
                    System.out.println("[DEBUG-STREAMING] executeTestGenerationWorkflowPhase2 - Session: " + session.getSessionId());
                    System.out.println("[DEBUG-STREAMING] streamingConsumer found? " + (streamingConsumer != null));
                }
                
                // Re-set streaming consumer for remaining agents if available
                if (streamingConsumer != null) {
                    if (contextAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) contextAgent).setStreamingConsumer(streamingConsumer);
                    }
                    if (testWriterAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) testWriterAgent).setStreamingConsumer(streamingConsumer);
                    }
                    if (validatorAgent instanceof StreamingBaseAgent) {
                        ((StreamingBaseAgent) validatorAgent).setStreamingConsumer(streamingConsumer);
                    }
                }
                
                TestPlan testPlan = session.getTestPlan();
                if (testPlan == null || testPlan.getScenarioCount() == 0) {
                    throw new RuntimeException("No test scenarios available for generation");
                }
                
                // Get the context that was already gathered in Phase 1
                TestContext context = session.getContext();
                if (context == null) {
                    throw new RuntimeException("Context not available for test generation");
                }
                
                // Phase 3: Test Generation (using context) - Now in batches of 5
                session.setStatus(TestGenerationSession.Status.GENERATING);
                if (tracker != null) {
                    tracker.startPhase(TestGenerationProgressTracker.PhaseType.TEST_GENERATION);
                    tracker.updatePhaseProgress("Generating test code in batches...", 25);
                }
                
                List<GeneratedTest> allGeneratedTests = new ArrayList<>();
                List<TestPlan.TestScenario> scenarios = testPlan.getTestScenarios();
                
                // Generate tests in batches of 5
                int batchSize = 5;
                for (int i = 0; i < scenarios.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, scenarios.size());
                    List<TestPlan.TestScenario> batch = scenarios.subList(i, endIndex);
                    
                    // Create a test plan for this batch
                    TestPlan batchPlan = new TestPlan(
                        testPlan.getTargetMethod(),
                        testPlan.getTargetClass(),
                        batch,
                        testPlan.getDependencies(),
                        testPlan.getRecommendedTestType(),
                        "Batch " + ((i/batchSize) + 1) + " of " + ((scenarios.size() + batchSize - 1) / batchSize)
                    );
                    
                    if (tracker != null) {
                        tracker.updatePhaseProgress("Generating batch " + ((i/batchSize) + 1) + " (" + batch.size() + " tests)...", 
                                                   25 + (50 * i / scenarios.size()));
                    }
                    
                    List<GeneratedTest> batchTests = testWriterAgent.generateTests(batchPlan, context).join();
                    allGeneratedTests.addAll(batchTests);
                    
                    // Small delay between batches to avoid overwhelming the system
                    if (endIndex < scenarios.size()) {
                        try {
                            Thread.sleep(1000); // 1 second delay between batches
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                
                session.setGeneratedTests(allGeneratedTests);
                
                if (allGeneratedTests.isEmpty()) {
                    throw new RuntimeException("No tests could be generated");
                }
                
                if (tracker != null) {
                    tracker.updatePhaseProgress("Generated " + allGeneratedTests.size() + " tests", 100);
                    tracker.completePhase("Test generation completed");
                }
                
                // Force a progress update to trigger UI refresh
                notifyProgress(session.getSessionId(), "Generated " + allGeneratedTests.size() + " tests", 65);
                
                // Phase 4: Merge and organize tests
                LOG.info("Phase 4: Merging and organizing tests");
                session.setStatus(TestGenerationSession.Status.GENERATING);
                
                if (tracker != null) {
                    tracker.startPhase(TestGenerationProgressTracker.PhaseType.TEST_GENERATION);
                    tracker.updatePhaseProgress("Merging tests and checking for existing files...", 25);
                }
                
                // Set streaming consumer for merger agent
                if (streamingConsumer != null) {
                    testMergerAgent.setStreamingConsumer(streamingConsumer);
                }
                
                TestMergerAgent.MergedTestResult mergedResult = testMergerAgent.mergeTests(allGeneratedTests, context).join();
                
                if (!mergedResult.isSuccess()) {
                    throw new RuntimeException("Test merging failed: " + mergedResult.getMessage());
                }
                
                // Convert merged classes back to GeneratedTest format for compatibility
                List<GeneratedTest> mergedTests = new ArrayList<>();
                for (TestMergerAgent.MergedTestClass mergedClass : mergedResult.getMergedClasses()) {
                    GeneratedTest mergedTest = new GeneratedTest(
                        "merged",
                        mergedClass.getClassName(),
                        mergedClass.getContent(),
                        allGeneratedTests.get(0).getScenario(), // Use first scenario as representative
                        mergedClass.getClassName() + ".java",
                        mergedClass.getPackageName(),
                        mergedClass.getImports(),
                        Arrays.asList("@Test"),
                        context.getFrameworkInfo()
                    );
                    mergedTests.add(mergedTest);
                }
                
                session.setGeneratedTests(mergedTests);
                
                if (tracker != null) {
                    tracker.updatePhaseProgress("Merged " + mergedResult.getMergedClasses().size() + " test classes", 100);
                    tracker.completePhase("Test merging completed");
                }
                
                notifyProgress(session.getSessionId(), "Tests merged and organized", 75);
                
                // Phase 5: Validation
                session.setStatus(TestGenerationSession.Status.VALIDATING);
                if (tracker != null) {
                    tracker.startPhase(TestGenerationProgressTracker.PhaseType.VALIDATION);
                    tracker.updatePhaseProgress("Validating generated tests...", 40);
                }
                
                // Validate the merged tests
                ValidationResult validationResult = validatorAgent.validateTests(mergedTests, context).join();
                session.setValidationResult(validationResult);
                
                // Update tests with fixes
                if (validationResult.hasFixedTests()) {
                    session.setGeneratedTests(validationResult.getFixedTests());
                }
                
                if (tracker != null) {
                    tracker.updatePhaseProgress("Validation complete: " + validationResult.getAppliedFixes().size() + " fixes applied", 100);
                    tracker.completePhase("Validation completed");
                }
                
                // Phase 5: Completion
                if (tracker != null) {
                    tracker.startPhase(TestGenerationProgressTracker.PhaseType.COMPLETION);
                }
                if (validationResult.hasErrors()) {
                    session.setStatus(TestGenerationSession.Status.COMPLETED_WITH_ISSUES);
                    if (tracker != null) {
                        tracker.complete("Test generation completed with " + validationResult.getErrorCount() + " issues");
                    }
                } else {
                    session.setStatus(TestGenerationSession.Status.COMPLETED);
                    if (tracker != null) {
                        tracker.complete("Test generation completed successfully!");
                    }
                }
                
            } catch (Exception e) {
                LOG.error("Test generation workflow phase 2 failed for session: " + session.getSessionId(), e);
                session.setStatus(TestGenerationSession.Status.FAILED);
                session.addError("Workflow failed: " + e.getMessage());
                if (tracker != null) {
                    tracker.fail("Workflow failed: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Get active session by ID
     */
    @Nullable
    public TestGenerationSession getSession(@NotNull String sessionId) {
        return activeSessions.get(sessionId);
    }
    
    /**
     * Get all active sessions
     */
    @NotNull
    public Collection<TestGenerationSession> getActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }
    
    /**
     * Cancel a running session
     */
    public void cancelSession(@NotNull String sessionId) {
        TestGenerationSession session = activeSessions.get(sessionId);
        if (session != null && session.getStatus().isActive()) {
            session.setStatus(TestGenerationSession.Status.CANCELLED);
            notifyProgress(sessionId, "Session cancelled by user", 100);
            LOG.info("Test generation session cancelled: " + sessionId);
        }
    }
    
    /**
     * Remove completed/failed sessions
     */
    public void cleanupSession(@NotNull String sessionId) {
        TestGenerationSession session = activeSessions.get(sessionId);
        if (session != null && !session.getStatus().isActive()) {
            activeSessions.remove(sessionId);
            sessionListeners.remove(sessionId);
            LOG.debug("Cleaned up session: " + sessionId);
        }
    }
    
    /**
     * Add progress listener for a session
     */
    public void addProgressListener(@NotNull String sessionId, @NotNull TestGenerationProgressListener listener) {
        sessionListeners.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(listener);
        
        // Also add to progress tracker if it exists
        TestGenerationProgressTracker tracker = progressTrackers.get(sessionId);
        if (tracker != null) {
            tracker.addListener(listener);
        }
    }
    
    /**
     * Set streaming consumer for a session to receive real-time AI responses
     */
    public void setStreamingConsumer(@NotNull String sessionId, @Nullable java.util.function.Consumer<String> consumer) {
        if (DEBUG_STREAMING) {
            System.out.println("[DEBUG-STREAMING] setStreamingConsumer called for session: " + sessionId);
            System.out.println("[DEBUG-STREAMING] Consumer is null? " + (consumer == null));
        }
        if (consumer != null) {
            streamingConsumers.put(sessionId, consumer);
            if (DEBUG_STREAMING) {
                System.out.println("[DEBUG-STREAMING] Consumer registered for session: " + sessionId);
                System.out.println("[DEBUG-STREAMING] Total consumers registered: " + streamingConsumers.size());
            }
        } else {
            streamingConsumers.remove(sessionId);
            if (DEBUG_STREAMING) {
                System.out.println("[DEBUG-STREAMING] Consumer removed for session: " + sessionId);
            }
        }
    }
    
    /**
     * Remove progress listener
     */
    public void removeProgressListener(@NotNull String sessionId, @NotNull TestGenerationProgressListener listener) {
        List<TestGenerationProgressListener> listeners = sessionListeners.get(sessionId);
        if (listeners != null) {
            listeners.remove(listener);
        }
        
        // Also remove from progress tracker
        TestGenerationProgressTracker tracker = progressTrackers.get(sessionId);
        if (tracker != null) {
            tracker.removeListener(listener);
        }
    }
    
    /**
     * Get progress tracker for a session
     */
    @Nullable
    public TestGenerationProgressTracker getProgressTracker(@NotNull String sessionId) {
        return progressTrackers.get(sessionId);
    }
    
    /**
     * Get detailed progress report for a session
     */
    @NotNull
    public String getProgressReport(@NotNull String sessionId) {
        TestGenerationProgressTracker tracker = progressTrackers.get(sessionId);
        if (tracker != null) {
            return tracker.generateProgressReport();
        }
        return "Progress tracker not found for session: " + sessionId;
    }
    
    /**
     * Notify progress to all listeners (deprecated - use progress tracker)
     */
    @Deprecated
    private void notifyProgress(@NotNull String sessionId, @NotNull String message, int progressPercent) {
        List<TestGenerationProgressListener> listeners = sessionListeners.get(sessionId);
        if (listeners != null) {
            TestGenerationProgress progress = new TestGenerationProgress(sessionId, message, progressPercent);
            listeners.forEach(listener -> {
                try {
                    listener.onProgress(progress);
                } catch (Exception e) {
                    LOG.warn("Progress listener failed", e);
                }
            });
        }
    }
    
    /**
     * Write tests to actual files - groups tests by class
     */
    @NotNull
    public CompletableFuture<List<String>> writeTestsToFiles(@NotNull String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            TestGenerationSession session = getSession(sessionId);
            if (session == null) {
                throw new RuntimeException("Session not found: " + sessionId);
            }
            
            if (session.getGeneratedTests() == null || session.getGeneratedTests().isEmpty()) {
                throw new RuntimeException("No tests to write");
            }
            
            List<String> writtenFiles = new ArrayList<>();
            
            try {
                // The tests are already merged by TestMergerAgent, just write them
                List<GeneratedTest> testsToWrite = session.getGeneratedTests();
                
                // Write each test file (already merged by TestMergerAgent)
                for (int i = 0; i < testsToWrite.size(); i++) {
                    GeneratedTest test = testsToWrite.get(i);
                    
                    // Write the test file - this method now handles threading properly
                    String filePath = testWriterAgent.writeTestFile(test);
                    writtenFiles.add(filePath);
                    LOG.info("Written test file: " + filePath + " for class " + test.getTestClassName());
                    
                    // Notify progress (safe from any thread)
                    notifyProgress(sessionId, "Written " + test.getTestClassName(), 
                                  ((i + 1) * 100) / testsToWrite.size());
                }
                
                notifyProgress(sessionId, "All tests written to files: " + writtenFiles.size() + " file(s) created", 100);
                return writtenFiles;
                
            } catch (Exception e) {
                LOG.error("Failed to write test files for session: " + sessionId, e);
                throw new RuntimeException("Failed to write test files: " + e.getMessage(), e);
            }
        });
    }
    
    // REMOVED: combineTestsIntoClass method - this is TestMergerAgent's job!
    // The TestMergerAgent already handles merging tests for the same class.
    // We should not duplicate this logic here.
    
    /**
     * Get statistics for all sessions
     */
    @NotNull
    public TestGenerationStatistics getStatistics() {
        int totalSessions = activeSessions.size();
        int completedSessions = (int) activeSessions.values().stream()
            .filter(s -> s.getStatus() == TestGenerationSession.Status.COMPLETED)
            .count();
        int failedSessions = (int) activeSessions.values().stream()
            .filter(s -> s.getStatus() == TestGenerationSession.Status.FAILED)
            .count();
        int totalTests = activeSessions.values().stream()
            .filter(s -> s.getGeneratedTests() != null)
            .mapToInt(s -> s.getGeneratedTests().size())
            .sum();
            
        return new TestGenerationStatistics(totalSessions, completedSessions, failedSessions, totalTests);
    }
}