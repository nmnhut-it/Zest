package com.zps.zest.testgen.statemachine;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for listening to test generation events.
 * Implementations can handle specific event types as needed.
 */
public interface TestGenerationEventListener {
    
    /**
     * Called when the state machine transitions between states
     */
    default void onStateChanged(@NotNull TestGenerationEvent.StateChanged event) {
        // Default empty implementation
    }
    
    /**
     * Called when progress is updated during a state execution
     */
    default void onProgressUpdated(@NotNull TestGenerationEvent.ProgressUpdated event) {
        // Default empty implementation
    }
    
    /**
     * Called when an error occurs during state execution
     */
    default void onErrorOccurred(@NotNull TestGenerationEvent.ErrorOccurred event) {
        // Default empty implementation
    }
    
    /**
     * Called when a state completes successfully
     */
    default void onStepCompleted(@NotNull TestGenerationEvent.StepCompleted event) {
        // Default empty implementation
    }
    
    /**
     * Called when user input is required to continue
     */
    default void onUserInputRequired(@NotNull TestGenerationEvent.UserInputRequired event) {
        // Default empty implementation
    }
    
    /**
     * Generic event handler - called for all events
     * Useful for logging or debugging
     */
    default void onEvent(@NotNull TestGenerationEvent event) {
        // Default empty implementation
    }
}