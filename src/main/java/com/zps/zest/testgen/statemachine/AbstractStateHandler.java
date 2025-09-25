package com.zps.zest.testgen.statemachine;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base class for state handlers that provides common functionality
 * and utilities for implementing state-specific logic.
 */
public abstract class AbstractStateHandler implements StateHandler {
    protected final Logger LOG = Logger.getInstance(getClass());
    
    private final TestGenerationState handledState;
    
    protected AbstractStateHandler(@NotNull TestGenerationState handledState) {
        this.handledState = handledState;
    }
    
    @NotNull
    @Override
    public final TestGenerationState getHandledState() {
        return handledState;
    }
    
    @NotNull
    @Override
    public final StateResult execute(@NotNull TestGenerationStateMachine stateMachine) {
        LOG.info("Executing state handler: " + handledState);
        
        try {
            // Log state execution start
            logStateStart(stateMachine);
            
            // Execute the state-specific logic
            StateResult result = executeState(stateMachine);
            
            if (result.isSuccess()) {
                LOG.info("State handler completed successfully: " + handledState);
                logStateComplete(stateMachine);
            } else {
                LOG.warn("State handler failed: " + handledState + " - " + result.getSummary());
                logStateError(stateMachine, result.getSummary());
            }
            
            return result;
            
        } catch (Exception e) {
            LOG.error("Unexpected error in state handler: " + handledState, e);
            return StateResult.failure(e, isRetryable());
        }
    }
    
    /**
     * Implement this method to provide state-specific logic.
     * This is called by the execute method after initial setup.
     */
    @NotNull
    protected abstract StateResult executeState(@NotNull TestGenerationStateMachine stateMachine);
    
    /**
     * Helper method to get the project from the state machine
     */
    @NotNull
    protected final Project getProject(@NotNull TestGenerationStateMachine stateMachine) {
        return stateMachine.getProject();
    }
    

    /**
     * Log state execution start
     */
    protected final void logStateStart(@NotNull TestGenerationStateMachine stateMachine) {
        LOG.info("🏁 Starting: " + handledState.getDisplayName());
        stateMachine.logActivity("🏁 Starting: " + handledState.getDisplayName());
    }
    
    /**
     * Log state execution completion
     */
    protected final void logStateComplete(@NotNull TestGenerationStateMachine stateMachine) {
        LOG.info("✅ Completed: " + handledState.getDisplayName());
        stateMachine.logActivity("✅ Completed: " + handledState.getDisplayName());
    }
    
    /**
     * Log state execution error
     */
    protected final void logStateError(@NotNull TestGenerationStateMachine stateMachine, String error) {
        LOG.error("❌ Failed: " + handledState.getDisplayName() + " - " + error);
        stateMachine.logActivity("❌ Failed: " + handledState.getDisplayName() + " - " + error);
    }
    
    /**
     * Log tool call activity during state execution
     */
    protected final void logToolActivity(@NotNull TestGenerationStateMachine stateMachine,
                                        @NotNull String toolName, @NotNull String description) {
        LOG.info("🔧 " + toolName + ": " + description);
        stateMachine.logActivity("🔧 " + toolName + ": " + description);
    }
    
    /**
     * Helper method to request user input
     */
    protected final void requestUserInput(@NotNull TestGenerationStateMachine stateMachine,
                                         @NotNull String inputType, @NotNull String prompt, 
                                         @Nullable Object data) {
        stateMachine.requestUserInput(inputType, prompt, data);
    }
    
    /**
     * Helper method to check if state execution should be cancelled
     */
    protected final boolean shouldCancel(@NotNull TestGenerationStateMachine stateMachine) {
        return stateMachine.getCurrentState() == TestGenerationState.CANCELLED;
    }
     
    /**
     * Helper method to execute steps with activity logging
     */
    protected final void executeWithActivityLogging(@NotNull TestGenerationStateMachine stateMachine,
                                                    @NotNull String[] steps,
                                                    @NotNull ActivityCallback callback) throws Exception {
        int stepCount = steps.length;
        for (int i = 0; i < stepCount; i++) {
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("State execution cancelled");
            }
            
            String step = steps[i];
            stateMachine.logActivity("⚡ Step " + (i + 1) + "/" + stepCount + ": " + step);
            
            long startTime = System.currentTimeMillis();
            callback.executeStep(i, step);
            long duration = System.currentTimeMillis() - startTime;
            
            stateMachine.logActivity("✅ Completed step " + (i + 1) + " in " + duration + "ms");
        }
        
        stateMachine.logActivity("🎉 All steps completed for " + handledState.getDisplayName());
    }
    
    /**
     * Functional interface for activity-based execution
     */
    @FunctionalInterface
    protected interface ActivityCallback {
        void executeStep(int stepIndex, String stepDescription) throws Exception;
    }
}