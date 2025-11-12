package com.zps.zest.testgen.batch;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.zps.zest.testgen.StateMachineTestGenerationService;
import com.zps.zest.testgen.model.TestGenerationConfig;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.statemachine.TestGenerationEvent;
import com.zps.zest.testgen.statemachine.TestGenerationEventListener;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Executes test generation on multiple files sequentially.
 * Tracks progress and collects metrics for evaluation.
 */
public class BatchTestGenerationExecutor {

    private static final Logger LOG = Logger.getInstance(BatchTestGenerationExecutor.class);
    private static final long TIMEOUT_PER_FILE_MS = 15 * 60 * 1000; // 15 minutes per file

    private final Project project;
    private final StateMachineTestGenerationService testGenService;
    private final java.util.Map<com.intellij.openapi.vfs.VirtualFile, java.util.Set<String>> fileMethodSelections;

    public BatchTestGenerationExecutor(@NotNull Project project) {
        this(project, new java.util.HashMap<>());
    }

    public BatchTestGenerationExecutor(@NotNull Project project,
                                      @NotNull java.util.Map<com.intellij.openapi.vfs.VirtualFile, java.util.Set<String>> fileMethodSelections) {
        this.project = project;
        this.testGenService = project.getService(StateMachineTestGenerationService.class);
        this.fileMethodSelections = fileMethodSelections;
    }

    /**
     * Execute batch test generation on multiple files.
     */
    @NotNull
    public CompletableFuture<BatchTestGenerationResult> executeBatch(
            @NotNull List<PsiFile> files,
            @NotNull TestGenerationConfig config,
            @NotNull BatchExecutionOptions options,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<FileProgressUpdate> progressCallback) {

        return CompletableFuture.supplyAsync(() -> {
            BatchTestGenerationResult batchResult = new BatchTestGenerationResult();

            try {
                for (int i = 0; i < files.size(); i++) {
                    PsiFile file = files.get(i);

                    // Check cancellation
                    if (indicator != null && indicator.isCanceled()) {
                        LOG.info("Batch execution cancelled by user at file " + (i + 1) + " of " + files.size());
                        break;
                    }

                    // Update progress
                    if (indicator != null) {
                        indicator.setFraction((double) i / files.size());
                        indicator.setText("Processing file " + (i + 1) + " of " + files.size() + ": " + file.getName());
                    }

                    if (progressCallback != null) {
                        progressCallback.accept(new FileProgressUpdate(
                            i, files.size(), file.getName(), "Starting..."
                        ));
                    }

                    // Process file
                    FileResult fileResult = processFile(file, config, options, indicator, progressCallback);
                    batchResult.addFileResult(fileResult);

                    // Report completion
                    if (progressCallback != null) {
                        String status = fileResult.isSuccess() ?
                            "✓ Completed: " + fileResult.getTestCount() + " tests" :
                            "✗ Failed: " + fileResult.getErrorMessage();
                        progressCallback.accept(new FileProgressUpdate(
                            i, files.size(), file.getName(), status
                        ));
                    }

                    // Stop on failure if configured
                    if (!fileResult.isSuccess() && !options.continueOnFailure) {
                        LOG.info("Stopping batch execution due to failure in: " + file.getName());
                        break;
                    }
                }

                batchResult.markComplete();
                return batchResult;

            } catch (Exception e) {
                LOG.error("Batch execution failed", e);
                batchResult.markComplete();
                return batchResult;
            }
        });
    }

    /**
     * Process a single file with test generation.
     */
    @NotNull
    private FileResult processFile(
            @NotNull PsiFile file,
            @NotNull TestGenerationConfig config,
            @NotNull BatchExecutionOptions options,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<FileProgressUpdate> progressCallback) {

        long startTime = System.currentTimeMillis();
        String filePath = file.getVirtualFile().getPath();
        String fileName = file.getName();

        try {
            // Find public methods
            List<PsiMethod> publicMethods = PublicApiDetector.findPublicMethods(
                file,
                options.excludeSimpleAccessors
            );

            // Filter methods based on user selection (if any)
            java.util.Set<String> selectedMethodNames = fileMethodSelections.get(file.getVirtualFile());
            if (selectedMethodNames != null && !selectedMethodNames.isEmpty()) {
                publicMethods = publicMethods.stream()
                    .filter(method -> selectedMethodNames.contains(method.getName()))
                    .collect(java.util.stream.Collectors.toList());

                LOG.info("Filtered to " + publicMethods.size() + " selected methods for " + fileName);
            }

            if (publicMethods.isEmpty()) {
                LOG.info("Skipping file with no public methods: " + fileName);
                return new FileResult(
                    filePath, fileName, 0, 0,
                    TestGenerationState.IDLE,
                    "No public methods found",
                    System.currentTimeMillis() - startTime,
                    null, "N/A"
                );
            }

            // Create request
            TestGenerationRequest request = new TestGenerationRequest(
                file,
                publicMethods,
                null,  // selectedCode
                options.testType,
                null,  // additionalContext
                null,  // userProvidedFiles
                null,  // userProvidedCode
                config
            );

            // Track current state and errors
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicBoolean failed = new AtomicBoolean(false);
            final String[] errorMsg = {null};
            final TestGenerationState[] finalState = {TestGenerationState.INITIALIZING};
            final TestPlan[] testPlan = {null};

            // Event listener to track progress and auto-select scenarios
            TestGenerationEventListener listener = new TestGenerationEventListener() {
                @Override
                public void onStateChanged(TestGenerationEvent.StateChanged event) {
                    finalState[0] = event.getNewState();

                    if (progressCallback != null) {
                        progressCallback.accept(new FileProgressUpdate(
                            0, 1, fileName, event.getNewState().getDisplayName()
                        ));
                    }

                    if (event.getNewState().isTerminal()) {
                        completed.set(true);
                        if (event.getNewState() == TestGenerationState.FAILED ||
                            event.getNewState() == TestGenerationState.CANCELLED) {
                            failed.set(true);
                        }
                    }
                }

                @Override
                public void onActivityLogged(TestGenerationEvent.ActivityLogged event) {
                    // Log activities
                }

                @Override
                public void onErrorOccurred(TestGenerationEvent.ErrorOccurred event) {
                    errorMsg[0] = event.getErrorMessage();
                    failed.set(true);
                }

                @Override
                public void onStepCompleted(TestGenerationEvent.StepCompleted event) {
                    // Track step completion
                }

                @Override
                public void onUserInputRequired(TestGenerationEvent.UserInputRequired event) {
                    // Auto-select all scenarios for batch mode
                    if (event.getData() instanceof TestPlan) {
                        testPlan[0] = (TestPlan) event.getData();
                    }
                }
            };

            // Start test generation
            CompletableFuture<TestGenerationStateMachine> future =
                testGenService.startTestGeneration(request, listener, null);

            // Wait for state machine creation
            TestGenerationStateMachine stateMachine = future.get();
            String sessionId = stateMachine.getSessionId();

            LOG.info("Started test generation for " + fileName + ", sessionId=" + sessionId);

            // Wait for test plan (AWAITING_USER_SELECTION state)
            int maxWaitIterations = 900; // 15 minutes (900 seconds)
            int iteration = 0;
            while (!completed.get() && testPlan[0] == null && iteration < maxWaitIterations) {
                Thread.sleep(1000);
                iteration++;

                if (indicator != null && indicator.isCanceled()) {
                    testGenService.cancelGeneration(sessionId, "Cancelled by user");
                    break;
                }
            }

            // Auto-select all scenarios
            if (testPlan[0] != null && !completed.get()) {
                LOG.info("Auto-selecting all " + testPlan[0].getScenarioCount() + " scenarios for " + fileName);
                testGenService.setUserSelection(sessionId, testPlan[0].getTestScenarios());
            }

            // Wait for completion
            iteration = 0;
            while (!completed.get() && iteration < maxWaitIterations) {
                Thread.sleep(1000);
                iteration++;

                if (indicator != null && indicator.isCanceled()) {
                    testGenService.cancelGeneration(sessionId, "Cancelled by user");
                    break;
                }
            }

            // Collect results
            long duration = System.currentTimeMillis() - startTime;

            if (!completed.get()) {
                LOG.warn("Test generation timed out for: " + fileName);
                return new FileResult(
                    filePath, fileName, publicMethods.size(), 0,
                    finalState[0], "Timeout after " + (duration / 1000) + " seconds",
                    duration, null, sessionId
                );
            }

            if (failed.get()) {
                LOG.warn("Test generation failed for: " + fileName + ", error=" + errorMsg[0]);
                return new FileResult(
                    filePath, fileName, publicMethods.size(), 0,
                    finalState[0], errorMsg[0] != null ? errorMsg[0] : "Unknown error",
                    duration, null, sessionId
                );
            }

            // Success - get results from handler
            com.zps.zest.testgen.model.TestGenerationResult testResult = null;
            try {
                com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler handler =
                    stateMachine.getHandler(
                        com.zps.zest.testgen.statemachine.TestGenerationState.GENERATING_TESTS,
                        com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler.class
                    );
                if (handler != null) {
                    testResult = handler.getTestGenerationResult();
                }
            } catch (Exception e) {
                LOG.warn("Could not retrieve test results for: " + fileName, e);
            }

            int testCount = testResult != null ? testResult.getMethodCount() : 0;

            LOG.info("Test generation succeeded for " + fileName + ": " + testCount + " tests in " +
                (duration / 1000.0) + " seconds");

            return new FileResult(
                filePath, fileName, publicMethods.size(), testCount,
                TestGenerationState.COMPLETED, null, duration, testResult, sessionId
            );

        } catch (Exception e) {
            LOG.error("Failed to process file: " + fileName, e);
            return new FileResult(
                filePath, fileName, 0, 0,
                TestGenerationState.FAILED, e.getMessage(),
                System.currentTimeMillis() - startTime, null, "N/A"
            );
        }
    }

    /**
     * Progress update for a file.
     */
    public static class FileProgressUpdate {
        public final int currentFileIndex;
        public final int totalFiles;
        public final String fileName;
        public final String status;

        public FileProgressUpdate(int currentFileIndex, int totalFiles, String fileName, String status) {
            this.currentFileIndex = currentFileIndex;
            this.totalFiles = totalFiles;
            this.fileName = fileName;
            this.status = status;
        }
    }

    /**
     * Batch execution options.
     */
    public static class BatchExecutionOptions {
        public final TestGenerationRequest.TestType testType;
        public final boolean excludeSimpleAccessors;
        public final boolean continueOnFailure;
        public final boolean autoSelectAllScenarios;

        public BatchExecutionOptions(
                TestGenerationRequest.TestType testType,
                boolean excludeSimpleAccessors,
                boolean continueOnFailure,
                boolean autoSelectAllScenarios) {
            this.testType = testType;
            this.excludeSimpleAccessors = excludeSimpleAccessors;
            this.continueOnFailure = continueOnFailure;
            this.autoSelectAllScenarios = autoSelectAllScenarios;
        }
    }

    /**
     * Alias for FileResult in BatchTestGenerationResult.
     */
    public static class FileResult extends BatchTestGenerationResult.FileResult {
        public FileResult(String filePath, String fileName, int methodCount, int testCount,
                         TestGenerationState finalState, String errorMessage, long durationMs,
                         com.zps.zest.testgen.model.TestGenerationResult result, String sessionId) {
            super(filePath, fileName, methodCount, testCount, finalState, errorMessage, durationMs, result, sessionId);
        }
    }
}
