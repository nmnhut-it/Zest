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
            // Update progress to indicate start
            stateMachine.updateProgress(0, "Starting " + handledState.getDisplayName().toLowerCase());
            
            // Execute the state-specific logic
            StateResult result = executeState(stateMachine);
            
            if (result.isSuccess()) {
                LOG.info("State handler completed successfully: " + handledState);
                stateMachine.updateProgress(100, "Completed " + handledState.getDisplayName().toLowerCase());
            } else {
                LOG.warn("State handler failed: " + handledState + " - " + result.getSummary());
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
     * Helper method to get session data
     */
    @Nullable
    protected final Object getSessionData(@NotNull TestGenerationStateMachine stateMachine, @NotNull String key) {
        return stateMachine.getSessionData().get(key);
    }
    
    /**
     * Helper method to set session data
     */
    protected final void setSessionData(@NotNull TestGenerationStateMachine stateMachine, 
                                       @NotNull String key, @Nullable Object value) {
        stateMachine.setSessionData(key, value);
    }
    
    /**
     * Helper method to update progress during execution
     */
    protected final void updateProgress(@NotNull TestGenerationStateMachine stateMachine, 
                                       int percent, @NotNull String message) {
        stateMachine.updateProgress(percent, message);
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
     * Helper method to validate required session data
     */
    protected final boolean hasRequiredData(@NotNull TestGenerationStateMachine stateMachine, 
                                           @NotNull String... keys) {
        for (String key : keys) {
            if (getSessionData(stateMachine, key) == null) {
                LOG.warn("Missing required session data: " + key);
                return false;
            }
        }
        return true;
    }
    
    /**
     * Helper method to create a step-by-step progress callback
     */
    protected final void executeWithProgress(@NotNull TestGenerationStateMachine stateMachine,
                                            @NotNull String[] steps,
                                            @NotNull ProgressCallback callback) throws Exception {
        int stepCount = steps.length;
        for (int i = 0; i < stepCount; i++) {
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("State execution cancelled");
            }
            
            String step = steps[i];
            int progressPercent = (i * 100) / stepCount;
            updateProgress(stateMachine, progressPercent, step);
            
            callback.executeStep(i, step);
        }
        
        updateProgress(stateMachine, 100, "Completed " + handledState.getDisplayName().toLowerCase());
    }
    
    /**
     * Functional interface for progress-based execution
     */
    @FunctionalInterface
    protected interface ProgressCallback {
        void executeStep(int stepIndex, String stepDescription) throws Exception;
    }
}