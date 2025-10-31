package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService;
import com.zps.zest.testgen.agents.AITestMergerAgent;
import com.zps.zest.testgen.evaluation.TestCodeValidator;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test merging phase of test generation.
 * Uses AI-based merging to create complete, merged test classes.
 */
public class TestMergingHandler extends AbstractStateHandler {

    private static final boolean DEBUG_HANDLER_EXECUTION = true;

    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    private final TestGenerationStateMachine stateMachine;
    private AITestMergerAgent aiTestMergerAgent;
    private MergedTestClass mergedTestClass;
    
    public TestMergingHandler(Consumer<String> streamingCallback,
                             com.zps.zest.testgen.ui.StreamingEventListener uiEventListener, TestGenerationStateMachine stateMachine) {
        super(TestGenerationState.MERGING_TESTS);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
        this.stateMachine = stateMachine;
    }

    @Override
    protected com.zps.zest.testgen.ui.StreamingEventListener getEventListener() {
        return uiEventListener;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        if (DEBUG_HANDLER_EXECUTION) {
            System.out.println("[DEBUG_HANDLER] TestMergingHandler.executeState START, sessionId=" +
                stateMachine.getSessionId());
        }

        try {
            // Validate required data - no session data checks needed
            
            // Get data from other handlers
            com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler generationHandler =
                stateMachine.getHandler(TestGenerationState.GENERATING_TESTS, com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler.class);
            com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                stateMachine.getHandler(TestGenerationState.GATHERING_CONTEXT, com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler.class);

            TestGenerationResult result = generationHandler != null ? generationHandler.getTestGenerationResult() : null;
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools =
                contextHandler != null && contextHandler.getContextAgent() != null ? contextHandler.getContextAgent().getContextTools() : null;
            
            logToolActivity(stateMachine, "TestMerger", "Preparing test merging");

            // Notify UI of phase start
            if (uiEventListener != null) {
                uiEventListener.onPhaseStarted(getHandledState());
                uiEventListener.onMergingStarted(); // New: notify merging started
            }

            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🤖 Starting phased test merging workflow...\n");
            }

            // Create AI merger agent
            logToolActivity(stateMachine, "AITestMerger", "Initializing merger agent");

            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);

            // Default to FULL_REWRITE_ONLY for simpler, faster fixing
            // TODO: Make this configurable via settings for A/B testing
            TestFixStrategy strategy = TestFixStrategy.FULL_REWRITE_ONLY;

            AITestMergerAgent aiMerger = new AITestMergerAgent(
                    getProject(stateMachine),
                    langChainService,
                    naiveLlmService,
                    strategy
            );

            // Store as field for direct access
            this.aiTestMergerAgent = aiMerger;

            // Save BEFORE checkpoint - capture state before test merging
            try {
                TestGenerationRequest request = stateMachine.getRequest();
                String promptDescription = request != null ?
                    "Generate tests for " + request.getTargetFile().getName() +
                        " (" + request.getTargetMethods().size() + " methods)" :
                    "Test merging";
                com.zps.zest.testgen.snapshot.AgentSnapshot beforeSnapshot = aiMerger.exportSnapshot(
                    stateMachine.getSessionId(),
                    "Before test merging - about to merge and fix generated tests",
                    promptDescription
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    beforeSnapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.BEFORE
                );
                LOG.info("Saved BEFORE checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save BEFORE checkpoint (non-critical)", e);
            }

            // Set UI event listener on merger agent for live updates
            if (uiEventListener != null) {
                aiMerger.setUiEventListener(uiEventListener);
                uiEventListener.onMergerAgentCreated(aiMerger);
            }

            // Initialize contextTools in mergingTools (completes the "passed later" initialization from AITestMergerAgent line 47)
            if (contextTools != null) {
                aiMerger.getMergingTools().setContextTools(contextTools);
            }

            // Execute phased workflow with explicit checkpoints
            MergedTestClass mergedTestClass = executePhasedMerging(stateMachine, aiMerger, result, contextTools);
            
            // Trigger UI update for merged test class
            if (uiEventListener != null && mergedTestClass != null) {
                uiEventListener.onMergedTestClassUpdated(mergedTestClass);
            }
            
            if (mergedTestClass == null) {
                return StateResult.failure(
                    new Exception("Merger returned null result"), 
                    true, 
                    "Test merging returned null result"
                );
            }
            
            // Store merged result in handler field
            this.mergedTestClass = mergedTestClass;
            
            // Session management removed - data available via handler getter
            
            String summary = String.format("AI test merging and review completed: %s with %d methods",
                mergedTestClass.getClassName(),
                mergedTestClass.getMethodCount());
            LOG.info(summary);

            // Notify UI that merging completed successfully
            if (uiEventListener != null) {
                uiEventListener.onMergingCompleted(true);
            }

            // Track unit test metrics
            trackUnitTestMetrics(stateMachine, result, mergedTestClass);

            // Save AFTER checkpoint for debugging and prompt experimentation
            try {
                TestGenerationRequest request = stateMachine.getRequest();
                String promptDescription = request != null ?
                    "Generate tests for " + request.getTargetFile().getName() +
                        " (" + request.getTargetMethods().size() + " methods)" :
                    "Test generation";
                com.zps.zest.testgen.snapshot.AgentSnapshot snapshot = aiTestMergerAgent.exportSnapshot(
                    stateMachine.getSessionId(),
                    "After test merging - " + mergedTestClass.getClassName() + " with " + mergedTestClass.getMethodCount() + " methods",
                    promptDescription
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    snapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.AFTER
                );
                LOG.info("Saved AFTER checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save AFTER checkpoint (non-critical)", e);
            }

            // Transition directly to COMPLETED since merging now includes error review and fixing
            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] TestMergingHandler SUCCESS: " + summary +
                    ", nextState=COMPLETED, sessionId=" + stateMachine.getSessionId());
            }
            return StateResult.success(mergedTestClass, summary, TestGenerationState.COMPLETED);

        } catch (Exception e) {
            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] TestMergingHandler EXCEPTION: " +
                    e.getMessage() + ", sessionId=" + stateMachine.getSessionId());
            }
            LOG.error("AI test merging failed", e);

            // Notify UI that merging failed
            if (uiEventListener != null) {
                uiEventListener.onMergingCompleted(false);
            }

            // Mark as recoverable to allow retry
            return StateResult.failure(e, true,
                "Failed to merge tests with AI: " + e.getMessage());
        }
    }

    /**
     * Execute phased merging workflow with explicit checkpoints.
     * New workflow: Java handles deterministic tasks, AI uses tools for code modification.
     */
    private MergedTestClass executePhasedMerging(
            @NotNull TestGenerationStateMachine stateMachine,
            @NotNull AITestMergerAgent aiMerger,
            @NotNull TestGenerationResult result,
            @Nullable com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools) throws Exception {

        String targetClass = result.getTargetClass();
        String testClassName = result.getClassName();
        String newTestCode = result.getCompleteTestClass();

        // ============ PHASE 1: Find Existing Test (Java - No AI) ============
        logToolActivity(stateMachine, "Phase1", "Finding existing test");
        notifyToolActivity("Finding existing test", targetClass);
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 1: Finding Existing Test (Java)**\n");
        }

        AITestMergerAgent.ExistingTestInfo existingTest = aiMerger.findExistingTest(targetClass);

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 1");
        }

        // ============ PHASE 2: Merge (AI Only If Needed) ============
        logToolActivity(stateMachine, "Phase2", "Merging test code");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 2: Merge (AI only if existing test found)**\n");
        }

        String mergedCode = aiMerger.mergeUsingTools(existingTest, newTestCode, testClassName, targetClass, contextTools);

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 2");
        }

        // ============ PHASE 3: Fix Imports (Java - Deterministic) ============
        logToolActivity(stateMachine, "Phase3", "Fixing imports");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 3: Import Enhancement (Java)**\n");
        }

        mergedCode = aiMerger.autoEnhanceImports(mergedCode, targetClass, result.getFramework(), contextTools);

        // Update working code in tools
        aiMerger.getMergingTools().updateTestCode(mergedCode, testClassName);

        if (streamingCallback != null) {
            streamingCallback.accept("✅ Imports enhanced\n");
        }

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 3");
        }

        // ============ PHASE 4: Validate (Java) ============
        logToolActivity(stateMachine, "Phase4", "Validating test code");
        notifyToolActivity("Validating", testClassName);
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 4: Validation (Java)**\n");
        }

        TestCodeValidator.ValidationResult validation = TestCodeValidator.validate(
                getProject(stateMachine),
                mergedCode,
                testClassName
        );

        java.util.List<String> compilationErrors = validation.compiles() ?
                java.util.Collections.emptyList() : validation.getErrors();

        if (streamingCallback != null) {
            streamingCallback.accept(validation.compiles() ?
                    "✅ Validation passed - no compilation errors\n" :
                    "❌ Validation failed: " + validation.getErrorCount() + " compilation errors\n");
        }

        // ============ PHASE 5: Review Logic (Read-only AI via NaiveLLMService) ============
        java.util.List<String> logicIssues = java.util.Collections.emptyList();

        if (validation.compiles()) {  // Only review if it compiles
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("Merging cancelled before logic review");
            }

            logToolActivity(stateMachine, "Phase5", "Logic review");
            if (streamingCallback != null) {
                streamingCallback.accept("\n**PHASE 5: Logic Review (Read-only AI)**\n");
            }

            logicIssues = aiMerger.reviewTestLogic(mergedCode, testClassName);

            if (streamingCallback != null) {
                if (!logicIssues.isEmpty()) {
                    streamingCallback.accept("⚠️ Found " + logicIssues.size() + " logic issue(s)\n");
                } else {
                    streamingCallback.accept("✅ No logic issues found\n");
                }
            }
        } else {
            if (streamingCallback != null) {
                streamingCallback.accept("\n⏭️ Skipping Phase 5 (has compilation errors)\n");
            }
        }

        // ============ PHASE 6: Fix ALL Issues (AI - ONE TIME) ============
        if (!compilationErrors.isEmpty() || !logicIssues.isEmpty()) {
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("Merging cancelled before fixing");
            }

            logToolActivity(stateMachine, "Phase6", "Fixing all issues");
            if (streamingCallback != null) {
                streamingCallback.accept("\n**PHASE 6: Fix All Issues (AI - ONE TIME)**\n");
                streamingCallback.accept("Fixing: " + compilationErrors.size() +
                        " compilation + " + logicIssues.size() + " logic issues\n");
            }

            int maxFixAttempts = 3;
            mergedCode = aiMerger.fixUsingTools(testClassName, compilationErrors, logicIssues, contextTools, maxFixAttempts);
        } else {
            if (streamingCallback != null) {
                streamingCallback.accept("\n⏭️ Skipping Phase 6 (no issues to fix)\n");
            }
        }

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 6");
        }

        // ============ PHASE 7: Final Validation (Java) ============
        logToolActivity(stateMachine, "Phase7", "Final validation");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 7: Final Validation (Java)**\n");
        }

        validation = TestCodeValidator.validate(getProject(stateMachine), mergedCode, testClassName);

        if (streamingCallback != null) {
            streamingCallback.accept(validation.compiles() ?
                    "✅ Final validation passed\n" :
                    "⚠️ Still has " + validation.getErrorCount() + " errors\n");
        }

        // ============ Create Final Result ============
        logToolActivity(stateMachine, "Finalizing", "Creating merged test class");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**FINALIZING**\n");
        }

        String outputPath = existingTest != null ?
                existingTest.getFilePath() :
                determineOutputPath(testClassName, result.getPackageName(), stateMachine);

        int methodCount = countTestMethods(mergedCode);

        MergedTestClass finalResult = new MergedTestClass(
                testClassName,
                result.getPackageName(),
                mergedCode,
                testClassName + ".java",
                outputPath,
                methodCount,
                result.getFramework()
        );

        if (streamingCallback != null) {
            streamingCallback.accept("✅ Merging workflow complete\n");
            streamingCallback.accept(String.format("   Class: %s\n", testClassName));
            streamingCallback.accept(String.format("   Methods: %d\n", methodCount));
            streamingCallback.accept(String.format("   Validates: %s\n", validation.compiles() ? "YES" : "NO"));
        }

        return finalResult;
    }

    private int countTestMethods(String testCode) {
        int count = 0;
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("@Test")) {
                count++;
            }
        }
        return count;
    }

    private String determineOutputPath(String className, String packageName, TestGenerationStateMachine stateMachine) {
        // Find test source root
        String basePath = getProject(stateMachine).getBasePath();
        if (basePath == null) {
            return className + ".java";
        }

        java.io.File testDir = new java.io.File(basePath, "src/test/java");
        if (!testDir.exists()) {
            testDir = new java.io.File(basePath, "test");
        }

        String packagePath = packageName.replace('.', java.io.File.separatorChar);
        java.io.File packageDir = packagePath.isEmpty() ? testDir : new java.io.File(testDir, packagePath);
        java.io.File testFile = new java.io.File(packageDir, className + ".java");

        return testFile.getAbsolutePath();
    }

    
    /**
     * Get the AI test merger agent (direct access instead of session data)
     */
    @Override
    public void cancel() {
        super.cancel();
        if (aiTestMergerAgent != null) {
            aiTestMergerAgent.cancel();
            LOG.info("Cancelled AITestMergerAgent");
        }
    }

    @Nullable
    public AITestMergerAgent getAITestMergerAgent() {
        return aiTestMergerAgent;
    }

    /**
     * Set the AI test merger agent (for checkpoint restoration)
     */
    public void setAITestMergerAgent(@NotNull AITestMergerAgent agent) {
        this.aiTestMergerAgent = agent;
    }

    /**
     * Get the merged test class
     */
    @Nullable
    public MergedTestClass getMergedTestClass() {
        return mergedTestClass;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public boolean isSkippable() {
        return false; // Merging cannot be skipped - it's required for final output
    }

    /**
     * Track unit test metrics when generation completes
     */
    private void trackUnitTestMetrics(
            @NotNull TestGenerationStateMachine stateMachine,
            @Nullable TestGenerationResult result,
            @NotNull MergedTestClass mergedTestClass
    ) {
        try {
            if (result == null) return;

            ZestInlineCompletionMetricsService metricsService =
                    getProject(stateMachine).getService(ZestInlineCompletionMetricsService.class);

            // Use timestamp for metrics (session details not directly accessible)
            long generationTimeMs = 60000L; // Default estimate: 1 minute

            // Count test methods
            int totalTests = result.getMethodCount();

            // Estimate word count from test code
            String testCode = mergedTestClass.getFullContent();
            int wordCount = testCode.split("\\s+").length;

            // ✅ REAL COMPILATION CHECK using IntelliJ's CodeSmellDetector
            TestCodeValidator.ValidationResult validation = TestCodeValidator.validate(
                    getProject(stateMachine),
                    testCode,
                    mergedTestClass.getClassName()
            );

            int testsCompiled = validation.compiles() ? totalTests : 0;
            int testsPassedImmediately = 0;  // Don't run tests, just check compilation

            // Log compilation result
            if (validation.compiles()) {
                LOG.info("Test compilation: SUCCESS - " + testsCompiled + "/" + totalTests + " tests compiled");
            } else {
                LOG.info("Test compilation: FAILED - " + validation.getErrorCount() + " errors found, 0/" + totalTests + " compiled");
            }

            metricsService.trackUnitTest(
                    "test-" + System.currentTimeMillis(),
                    totalTests,
                    wordCount,
                    generationTimeMs,
                    testsCompiled,           // ✅ Real compilation result
                    testsPassedImmediately   // 0 - not running tests
            );

            LOG.info("Unit test metrics tracked: " + totalTests + " tests, " +
                    wordCount + " words, compilation: " + (validation.compiles() ? "SUCCESS" : "FAILED"));

        } catch (Exception e) {
            LOG.warn("Failed to track unit test metrics", e);
        }
    }
}