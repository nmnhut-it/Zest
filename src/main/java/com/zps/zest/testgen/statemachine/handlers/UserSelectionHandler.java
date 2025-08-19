package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Handles the user selection phase of test generation.
 * Waits for user to select which test scenarios to implement.
 * This handler doesn't do much work itself - it mainly waits for external input.
 */
public class UserSelectionHandler extends AbstractStateHandler {
    
    public UserSelectionHandler() {
        super(TestGenerationState.AWAITING_USER_SELECTION);
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data
            if (!hasRequiredData(stateMachine, "testPlan")) {
                return StateResult.failure("Missing test plan for user selection", false);
            }
            
            TestPlan testPlan = (TestPlan) getSessionData(stateMachine, "testPlan");
            
            logToolActivity(stateMachine, "UserSelection", "Waiting for user scenario selection");
            
            // Check if user has already made a selection
            @SuppressWarnings("unchecked")
            List<TestPlan.TestScenario> selectedScenarios = 
                (List<TestPlan.TestScenario>) getSessionData(stateMachine, "selectedScenarios");
            
            if (selectedScenarios != null && !selectedScenarios.isEmpty()) {
                // User has already selected scenarios - proceed to generation
                String summary = String.format("User selected %d of %d scenarios", 
                    selectedScenarios.size(), testPlan.getScenarioCount());
                LOG.info(summary);
                
                setSessionData(stateMachine, "workflowPhase", "user_selection");
                
                return StateResult.success(selectedScenarios, summary, TestGenerationState.GENERATING_TESTS);
            }
            
            // No selection made yet - this typically means the UI should show selection dialog
            // The handler will be re-executed when user makes a selection
            
            // Request user input if not already requested
            if (getSessionData(stateMachine, "selectionRequested") == null) {
                requestUserInput(stateMachine, "scenario_selection",
                    String.format("Please select from %d available test scenarios", testPlan.getScenarioCount()),
                    testPlan);
                setSessionData(stateMachine, "selectionRequested", true);
            }
            
            // Return success but don't transition - wait for external input
            return StateResult.success(null, "Waiting for user to select test scenarios");
            
        } catch (Exception e) {
            LOG.error("User selection handler failed", e);
            return StateResult.failure(e, true, "Failed during user selection phase: " + e.getMessage());
        }
    }
    
    /**
     * Called externally when user makes a selection
     */
    public void setUserSelection(@NotNull TestGenerationStateMachine stateMachine, 
                                @NotNull List<TestPlan.TestScenario> selectedScenarios) {
        if (selectedScenarios.isEmpty()) {
            LOG.warn("User selected no scenarios");
            return;
        }
        
        LOG.info("User selected " + selectedScenarios.size() + " scenarios");
        setSessionData(stateMachine, "selectedScenarios", selectedScenarios);
        
        // Enable auto-flow for remaining states after user selection
        stateMachine.enableAutoFlow();
        LOG.info("Auto-flow enabled after user selection");
        
        // Trigger re-execution of this state which will now proceed to next state
        stateMachine.executeCurrentState();
    }
    
    @Override
    public boolean isRetryable() {
        return true;
    }
    
    @Override
    public boolean isSkippable() {
        return false; // User selection cannot be skipped - it's required
    }
}