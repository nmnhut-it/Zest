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
    
    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final LLMService llmService;
    
    // Agents
    private final CoordinatorAgent coordinatorAgent;
    private final ContextAgent contextAgent;
    private final TestWriterAgent testWriterAgent;
    private final ValidatorAgent validatorAgent;
    
    // Active sessions
    private final Map<String, TestGenerationSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, List<TestGenerationProgressListener>> sessionListeners = new ConcurrentHashMap<>();
    private final Map<String, TestGenerationProgressTracker> progressTrackers = new ConcurrentHashMap<>();
    
    public TestGenerationService(@NotNull Project project) {
        this.project = project;
        this.langChainService = project.getService(ZestLangChain4jService.class);
        this.llmService = project.getService(LLMService.class);
        
        // Initialize agents
        this.coordinatorAgent = new CoordinatorAgent(project, langChainService, llmService);
        this.contextAgent = new ContextAgent(project, langChainService, llmService);
        this.testWriterAgent = new TestWriterAgent(project, langChainService, llmService);
        this.validatorAgent = new ValidatorAgent(project, langChainService, llmService);
        
        LOG.info("TestGenerationService initialized for project: " + project.getName());
    }
    
    /**
     * Start a new test generation session
     */
    @NotNull
    public CompletableFuture<TestGenerationSession> startTestGeneration(@NotNull TestGenerationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            String sessionId = UUID.randomUUID().toString();
            
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
     * Execute the multi-agent workflow using ReAct pattern
     */
    private void executeTestGenerationWorkflow(@NotNull TestGenerationSession session) {
        CompletableFuture.runAsync(() -> {
            TestGenerationProgressTracker tracker = progressTrackers.get(session.getSessionId());
            
            try {
                // Phase 1: Planning and Coordination
                session.setStatus(TestGenerationSession.Status.PLANNING);
                tracker.completePhase("Initialization complete");
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.PLANNING);
                tracker.updatePhaseProgress("Analyzing code structure...", 20);
                
                TestPlan testPlan = coordinatorAgent.planTests(session.getRequest()).join();
                session.setTestPlan(testPlan);
                
                if (testPlan.getTestScenarios().isEmpty()) {
                    throw new RuntimeException("No test scenarios could be planned for the selected code");
                }
                
                tracker.updatePhaseProgress("Test plan created with " + testPlan.getScenarioCount() + " scenarios", 100);
                tracker.completePhase("Test planning completed");
                
                // Phase 2: Context Gathering
                session.setStatus(TestGenerationSession.Status.GATHERING_CONTEXT);
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.CONTEXT_GATHERING);
                tracker.updatePhaseProgress("Gathering code context and dependencies...", 30);
                
                TestContext context = contextAgent.gatherContext(session.getRequest(), testPlan).join();
                session.setContext(context);
                
                tracker.updatePhaseProgress("Context gathered: " + context.getContextItemCount() + " items", 80);
                tracker.completePhase("Context gathering completed");
                
                // Phase 3: Test Generation
                session.setStatus(TestGenerationSession.Status.GENERATING);
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.TEST_GENERATION);
                tracker.updatePhaseProgress("Generating test code...", 25);
                
                List<GeneratedTest> generatedTests = testWriterAgent.generateTests(testPlan, context).join();
                session.setGeneratedTests(generatedTests);
                
                if (generatedTests.isEmpty()) {
                    throw new RuntimeException("No tests could be generated");
                }
                
                tracker.updatePhaseProgress("Generated " + generatedTests.size() + " tests", 100);
                tracker.completePhase("Test generation completed");
                
                // Phase 4: Validation
                session.setStatus(TestGenerationSession.Status.VALIDATING);
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.VALIDATION);
                tracker.updatePhaseProgress("Validating generated tests...", 40);
                
                ValidationResult validationResult = validatorAgent.validateTests(generatedTests, context).join();
                session.setValidationResult(validationResult);
                
                // Update tests with fixes
                if (validationResult.hasFixedTests()) {
                    session.setGeneratedTests(validationResult.getFixedTests());
                }
                
                tracker.updatePhaseProgress("Validation complete: " + validationResult.getAppliedFixes().size() + " fixes applied", 100);
                tracker.completePhase("Validation completed");
                
                // Phase 5: Completion
                tracker.startPhase(TestGenerationProgressTracker.PhaseType.COMPLETION);
                if (validationResult.hasErrors()) {
                    session.setStatus(TestGenerationSession.Status.COMPLETED_WITH_ISSUES);
                    tracker.complete("Test generation completed with " + validationResult.getErrorCount() + " issues");
                } else {
                    session.setStatus(TestGenerationSession.Status.COMPLETED);
                    tracker.complete("Test generation completed successfully!");
                }
                
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
     * Write tests to actual files
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
                for (GeneratedTest test : session.getGeneratedTests()) {
                    // Use existing file creation tools to write tests
                    String filePath = testWriterAgent.writeTestFile(test);
                    writtenFiles.add(filePath);
                    LOG.info("Written test file: " + filePath);
                }
                
                return writtenFiles;
                
            } catch (Exception e) {
                LOG.error("Failed to write test files for session: " + sessionId, e);
                throw new RuntimeException("Failed to write test files: " + e.getMessage());
            }
        });
    }
    
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