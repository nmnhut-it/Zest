package com.zps.zest.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for executing the refactoring plan by orchestrating chat interactions.
 * Manages the step-by-step refactoring process with LLM assistance.
 */
public class RefactoringExecutionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(RefactoringExecutionStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Starting refactoring execution stage");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get the refactoring state manager
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        
        // Load the refactoring plan
        RefactoringPlan plan = stateManager.loadPlan();
        if (plan == null) {
            LOG.error("No refactoring plan found");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project, 
                        "No refactoring plan found. The previous plan may have been aborted or completed.", 
                        "Refactoring Error");
            });
            return;
        }
        
        // Load the progress
        RefactoringProgress progress = stateManager.loadProgress();
        if (progress == null) {
            progress = new RefactoringProgress(plan.getName());
            stateManager.saveProgress(progress);
        } else if (progress.getStatus() == RefactoringStatus.ABORTED || progress.getStatus() == RefactoringStatus.COMPLETED) {
            LOG.info("Refactoring was previously " + progress.getStatus().toString().toLowerCase());
            RefactoringProgress finalProgress = progress;
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(project,
                        "The previous refactoring was " + finalProgress.getStatus().toString().toLowerCase() + ". Starting a new refactoring process.",
                        "Previous Refactoring " + finalProgress.getStatus().toString());
            });
            
            // Clear the old state and create a new progress
            stateManager.clearRefactoringState();
            progress = new RefactoringProgress(plan.getName());
            stateManager.saveProgress(progress);
        }
        
        // Check if the plan is empty (no issues)
        if (plan.getIssues().isEmpty()) {
            LOG.info("No issues found in the refactoring plan");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(project,
                        "No testability issues were found in the class. It appears to be well-designed for testing already.", 
                        "No Refactoring Needed");
            });
            stateManager.clearRefactoringState();
            return;
        }
        
        // Show the refactoring tool window to guide the user through the process
        RefactoringToolWindow toolWindow = RefactoringToolWindow.showToolWindow(project, plan, progress);
        if (toolWindow == null) {
            throw new PipelineExecutionException("Failed to show refactoring tool window");
        }
        
        // Update the status message
        String statusMessage = String.format(
                "Starting refactoring execution for %s. Beginning with %s.", 
                plan.getTargetClass(), 
                progress.getCurrentStep());
        
        LOG.info(statusMessage);
    }
}
