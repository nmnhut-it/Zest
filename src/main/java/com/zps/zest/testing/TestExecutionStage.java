package com.zps.zest.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for executing the test writing plan by orchestrating test generation.
 * Manages the step-by-step test writing process with LLM assistance.
 */
public class TestExecutionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestExecutionStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Starting test execution stage");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get the test writing state manager
        TestWritingStateManager stateManager = new TestWritingStateManager(project);
        
        // Load the test plan
        TestPlan plan = stateManager.loadPlan();
        if (plan == null) {
            LOG.error("No test plan found");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project, 
                        "No test plan found. The previous plan may have been aborted or completed.", 
                        "Test Writing Error");
            });
            return;
        }
        
        // Load the progress
        TestWritingProgress progress = stateManager.loadProgress();
        if (progress == null) {
            progress = new TestWritingProgress(plan.getName());
            stateManager.saveProgress(progress);
        } else if (progress.getStatus() == TestWritingStatus.ABORTED || progress.getStatus() == TestWritingStatus.COMPLETED) {
            LOG.info("Test writing was previously " + progress.getStatus().toString().toLowerCase());
            TestWritingProgress finalProgress = progress;
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(project,
                        "The previous test writing was " + finalProgress.getStatus().toString().toLowerCase() + ". Starting a new test writing process.",
                        "Previous Test Writing " + finalProgress.getStatus().toString());
            });
            
            // Clear the old state and create a new progress
            stateManager.clearTestWritingState();
            progress = new TestWritingProgress(plan.getName());
            stateManager.saveProgress(progress);
        }
        
        // Check if the plan is empty (no scenarios)
        if (plan.getScenarios().isEmpty()) {
            LOG.info("No scenarios found in the test plan");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(project,
                        "No test scenarios were identified for the class. The class may already have comprehensive test coverage or may not require additional testing.", 
                        "No Test Writing Needed");
            });
            stateManager.clearTestWritingState();
            return;
        }
        
        // Show the test writing tool window to guide the user through the process
        TestWritingToolWindow toolWindow = TestWritingToolWindow.showToolWindow(project, plan, progress);
        if (toolWindow == null) {
            throw new PipelineExecutionException("Failed to show test writing tool window");
        }
        
        // Update the status message
        String statusMessage = String.format(
                "Starting test writing execution for %s. Beginning with %s.", 
                plan.getTargetClass(), 
                progress.getCurrentTest());
        
        LOG.info(statusMessage);
    }
}
