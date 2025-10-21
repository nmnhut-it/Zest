package com.zps.zest.testgen.statemachine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for handling execution of a specific state in the test generation workflow.
 * Each state handler is responsible for one specific phase of test generation.
 */
public interface StateHandler {
    
    /**
     * Execute the logic for this state.
     * Should be implemented to handle the specific work of the state.
     * 
     * @param stateMachine The state machine instance for context and callbacks
     * @return StateResult indicating success/failure and next actions
     */
    @NotNull
    StateResult execute(@NotNull TestGenerationStateMachine stateMachine);
    
    /**
     * Get the state this handler is responsible for
     */
    @NotNull
    TestGenerationState getHandledState();
    
    /**
     * Check if this handler can be retried after failure
     */
    default boolean isRetryable() {
        return true;
    }
    
    /**
     * Check if this handler can be skipped (manual intervention)
     */
    default boolean isSkippable() {
        return getHandledState().isSkippable();
    }
    
    /**
     * Get human-readable description of what this handler does
     */
    @NotNull
    default String getDescription() {
        return getHandledState().getDescription();
    }

    /**
     * Cancel this handler's operations
     * Default implementation does nothing - override to cancel agents
     */
    default void cancel() {
        // Default no-op implementation
    }

    /**
     * Check if this handler has been cancelled
     */
    default boolean isCancelled() {
        return false; // Default implementation - override if needed
    }

    /**
     * Result of state execution
     */
    class StateResult {
        private final boolean success;
        private final Object result;
        private final String summary;
        private final Exception error;
        private final boolean recoverable;
        private final TestGenerationState nextState;
        
        private StateResult(boolean success, Object result, String summary, 
                           Exception error, boolean recoverable, TestGenerationState nextState) {
            this.success = success;
            this.result = result;
            this.summary = summary != null ? summary : "";
            this.error = error;
            this.recoverable = recoverable;
            this.nextState = nextState;
        }
        
        /**
         * Create a successful result
         */
        @NotNull
        public static StateResult success(@Nullable Object result, @NotNull String summary) {
            return new StateResult(true, result, summary, null, false, null);
        }
        
        /**
         * Create a successful result with automatic transition to next state
         */
        @NotNull
        public static StateResult success(@Nullable Object result, @NotNull String summary, 
                                         @NotNull TestGenerationState nextState) {
            return new StateResult(true, result, summary, null, false, nextState);
        }
        
        /**
         * Create a failure result
         */
        @NotNull
        public static StateResult failure(@NotNull String errorMessage, boolean recoverable) {
            return new StateResult(false, null, errorMessage, 
                                 new RuntimeException(errorMessage), recoverable, null);
        }
        
        /**
         * Create a failure result with exception
         */
        @NotNull
        public static StateResult failure(@NotNull Exception error, boolean recoverable) {
            return new StateResult(false, null, "State execution failed: " + error.getMessage(), 
                                 error, recoverable, null);
        }
        
        /**
         * Create a failure result with custom summary
         */
        @NotNull
        public static StateResult failure(@NotNull Exception error, boolean recoverable, 
                                         @NotNull String summary) {
            return new StateResult(false, null, summary, error, recoverable, null);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        @Nullable
        public Object getResult() {
            return result;
        }
        
        @NotNull
        public String getSummary() {
            return summary;
        }
        
        @Nullable
        public Exception getError() {
            return error;
        }
        
        public boolean isRecoverable() {
            return recoverable;
        }
        
        @Nullable
        public TestGenerationState getNextState() {
            return nextState;
        }
    }
}