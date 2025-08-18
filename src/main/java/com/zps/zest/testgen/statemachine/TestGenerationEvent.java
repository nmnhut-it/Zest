package com.zps.zest.testgen.statemachine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Represents events that occur during test generation workflow.
 * Events are immutable and contain all necessary information about what happened.
 */
public abstract class TestGenerationEvent {
    private final String sessionId;
    private final Instant timestamp;
    
    protected TestGenerationEvent(@NotNull String sessionId) {
        this.sessionId = sessionId;
        this.timestamp = Instant.now();
    }
    
    @NotNull
    public String getSessionId() {
        return sessionId;
    }
    
    @NotNull
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * State transition event
     */
    public static class StateChanged extends TestGenerationEvent {
        private final TestGenerationState oldState;
        private final TestGenerationState newState;
        private final String reason;
        
        public StateChanged(@NotNull String sessionId, 
                           @NotNull TestGenerationState oldState, 
                           @NotNull TestGenerationState newState,
                           @Nullable String reason) {
            super(sessionId);
            this.oldState = oldState;
            this.newState = newState;
            this.reason = reason;
        }
        
        @NotNull
        public TestGenerationState getOldState() {
            return oldState;
        }
        
        @NotNull
        public TestGenerationState getNewState() {
            return newState;
        }
        
        @Nullable
        public String getReason() {
            return reason;
        }
    }
    
    /**
     * Progress update event
     */
    public static class ProgressUpdated extends TestGenerationEvent {
        private final int progressPercent;
        private final String message;
        private final TestGenerationState currentState;
        
        public ProgressUpdated(@NotNull String sessionId, 
                              @NotNull TestGenerationState currentState,
                              int progressPercent, 
                              @NotNull String message) {
            super(sessionId);
            this.currentState = currentState;
            this.progressPercent = Math.max(0, Math.min(100, progressPercent));
            this.message = message;
        }
        
        @NotNull
        public TestGenerationState getCurrentState() {
            return currentState;
        }
        
        public int getProgressPercent() {
            return progressPercent;
        }
        
        @NotNull
        public String getMessage() {
            return message;
        }
    }
    
    /**
     * Error occurred event
     */
    public static class ErrorOccurred extends TestGenerationEvent {
        private final TestGenerationState currentState;
        private final String errorMessage;
        private final Exception exception;
        private final boolean recoverable;
        
        public ErrorOccurred(@NotNull String sessionId,
                            @NotNull TestGenerationState currentState,
                            @NotNull String errorMessage,
                            @Nullable Exception exception,
                            boolean recoverable) {
            super(sessionId);
            this.currentState = currentState;
            this.errorMessage = errorMessage;
            this.exception = exception;
            this.recoverable = recoverable;
        }
        
        @NotNull
        public TestGenerationState getCurrentState() {
            return currentState;
        }
        
        @NotNull
        public String getErrorMessage() {
            return errorMessage;
        }
        
        @Nullable
        public Exception getException() {
            return exception;
        }
        
        public boolean isRecoverable() {
            return recoverable;
        }
    }
    
    /**
     * Step completed successfully event
     */
    public static class StepCompleted extends TestGenerationEvent {
        private final TestGenerationState completedState;
        private final Object result;
        private final String summary;
        
        public StepCompleted(@NotNull String sessionId,
                            @NotNull TestGenerationState completedState,
                            @Nullable Object result,
                            @NotNull String summary) {
            super(sessionId);
            this.completedState = completedState;
            this.result = result;
            this.summary = summary;
        }
        
        @NotNull
        public TestGenerationState getCompletedState() {
            return completedState;
        }
        
        @Nullable
        public Object getResult() {
            return result;
        }
        
        @NotNull
        public String getSummary() {
            return summary;
        }
    }
    
    /**
     * User input required event
     */
    public static class UserInputRequired extends TestGenerationEvent {
        private final TestGenerationState currentState;
        private final String inputType;
        private final String prompt;
        private final Object data;
        
        public UserInputRequired(@NotNull String sessionId,
                               @NotNull TestGenerationState currentState,
                               @NotNull String inputType,
                               @NotNull String prompt,
                               @Nullable Object data) {
            super(sessionId);
            this.currentState = currentState;
            this.inputType = inputType;
            this.prompt = prompt;
            this.data = data;
        }
        
        @NotNull
        public TestGenerationState getCurrentState() {
            return currentState;
        }
        
        @NotNull
        public String getInputType() {
            return inputType;
        }
        
        @NotNull
        public String getPrompt() {
            return prompt;
        }
        
        @Nullable
        public Object getData() {
            return data;
        }
    }
}