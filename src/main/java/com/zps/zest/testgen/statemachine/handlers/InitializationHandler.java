package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestGenerationSession;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import org.jetbrains.annotations.NotNull;

/**
 * Handles the initialization phase of test generation.
 * Sets up the session and validates the request.
 */
public class InitializationHandler extends AbstractStateHandler {
    
    public InitializationHandler() {
        super(TestGenerationState.INITIALIZING);
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            String[] steps = {
                "Validating test generation request",
                "Setting up session data",
                "Preparing workflow"
            };

            executeWithActivityLogging(stateMachine, steps, (stepIndex, stepDescription) -> {
                switch (stepIndex) {
                    case 0:
                        validateRequest(stateMachine);
                        break;
                    case 1:
                        setupSessionData(stateMachine);
                        break;
                    case 2:
                        prepareWorkflow(stateMachine);
                        break;
                }
            });

            return StateResult.success(null, "Initialization completed successfully",
                                     TestGenerationState.GATHERING_CONTEXT);

        } catch (Exception e) {
            return StateResult.failure(e, true, "Failed to initialize test generation session");
        }
    }
    
    private void validateRequest(@NotNull TestGenerationStateMachine stateMachine) throws Exception {
        TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
        
        if (request == null) {
            throw new IllegalStateException("No test generation request provided");
        }
        
        if (request.getTargetFile() == null) {
            throw new IllegalArgumentException("Target file is required");
        }
        
        if (request.getTargetMethods() == null || request.getTargetMethods().isEmpty()) {
            throw new IllegalArgumentException("At least one target method is required");
        }
        
        LOG.info("Request validation passed for " + request.getTargetMethods().size() + " methods");
    }
    
    private void setupSessionData(@NotNull TestGenerationStateMachine stateMachine) throws Exception {
        TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
        
        // Create session object
        TestGenerationSession session = new TestGenerationSession(
            stateMachine.getSessionId(),
            request,
            TestGenerationSession.Status.INITIALIZING
        );
        
        setSessionData(stateMachine, "session", session);
        setSessionData(stateMachine, "startTime", System.currentTimeMillis());
        setSessionData(stateMachine, "errors", new java.util.ArrayList<String>());
        
        LOG.info("Session data initialized for session: " + stateMachine.getSessionId());
    }
    
    private void prepareWorkflow(@NotNull TestGenerationStateMachine stateMachine) throws Exception {
        // Set up any workflow-specific data or configurations
        setSessionData(stateMachine, "workflowPhase", "initialization");
        setSessionData(stateMachine, "retryCount", 0);

        LOG.info("Workflow preparation completed");
    }
}