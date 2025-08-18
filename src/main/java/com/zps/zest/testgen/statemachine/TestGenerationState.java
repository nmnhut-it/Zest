package com.zps.zest.testgen.statemachine;

/**
 * Represents the various states in the test generation workflow.
 * Each state represents a distinct phase with specific responsibilities.
 */
public enum TestGenerationState {
    /**
     * Initial state - no generation in progress
     */
    IDLE("Idle", "Ready to start test generation"),
    
    /**
     * Setting up the generation session
     */
    INITIALIZING("Initializing", "Setting up test generation session"),
    
    /**
     * Analyzing target files and gathering context
     */
    GATHERING_CONTEXT("Gathering Context", "Analyzing code and collecting context information"),
    
    /**
     * Creating test scenarios and plans
     */
    PLANNING_TESTS("Planning Tests", "Creating test scenarios and planning approach"),
    
    /**
     * Waiting for user to select which scenarios to implement
     */
    AWAITING_USER_SELECTION("Awaiting Selection", "Waiting for user to select test scenarios"),
    
    /**
     * Generating actual test code
     */
    GENERATING_TESTS("Generating Tests", "Creating test methods and code"),
    
    /**
     * Merging generated tests into final test class
     */
    MERGING_TESTS("Merging Tests", "Combining tests into complete test class"),
    
    /**
     * Generation completed successfully
     */
    COMPLETED("Completed", "Test generation completed successfully"),
    
    /**
     * Generation failed with unrecoverable error
     */
    FAILED("Failed", "Test generation failed"),
    
    /**
     * Generation was cancelled by user
     */
    CANCELLED("Cancelled", "Test generation was cancelled");
    
    private final String displayName;
    private final String description;
    
    TestGenerationState(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Check if this state represents an active (in-progress) state
     */
    public boolean isActive() {
        return this != IDLE && this != COMPLETED && this != FAILED && this != CANCELLED;
    }
    
    /**
     * Check if this state represents a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
    
    /**
     * Check if this state can be retried (after failure)
     */
    public boolean isRetryable() {
        return this != IDLE && this != COMPLETED && this != CANCELLED;
    }
    
    /**
     * Check if this state can be skipped (manual intervention)
     */
    public boolean isSkippable() {
        return this == GATHERING_CONTEXT || this == PLANNING_TESTS || this == GENERATING_TESTS;
    }
    
    /**
     * Get the next logical state in the normal workflow
     */
    public TestGenerationState getNextState() {
        switch (this) {
            case IDLE:
                return INITIALIZING;
            case INITIALIZING:
                return GATHERING_CONTEXT;
            case GATHERING_CONTEXT:
                return PLANNING_TESTS;
            case PLANNING_TESTS:
                return AWAITING_USER_SELECTION;
            case AWAITING_USER_SELECTION:
                return GENERATING_TESTS;
            case GENERATING_TESTS:
                return MERGING_TESTS;
            case MERGING_TESTS:
                return COMPLETED;
            default:
                return this; // Terminal states stay the same
        }
    }
    
    /**
     * Get the previous state (for rollback scenarios)
     */
    public TestGenerationState getPreviousState() {
        switch (this) {
            case INITIALIZING:
                return IDLE;
            case GATHERING_CONTEXT:
                return INITIALIZING;
            case PLANNING_TESTS:
                return GATHERING_CONTEXT;
            case AWAITING_USER_SELECTION:
                return PLANNING_TESTS;
            case GENERATING_TESTS:
                return AWAITING_USER_SELECTION;
            case MERGING_TESTS:
                return GENERATING_TESTS;
            case COMPLETED:
            case FAILED:
            case CANCELLED:
                return MERGING_TESTS;
            default:
                return IDLE;
        }
    }
}