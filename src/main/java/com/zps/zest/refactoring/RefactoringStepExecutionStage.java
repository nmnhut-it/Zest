package com.zps.zest.refactoring;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for executing a single step in the refactoring plan.
 * This stage is designed to be used repeatedly to execute each step in sequence.
 */
public class RefactoringStepExecutionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(RefactoringStepExecutionStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Executing refactoring step");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get the refactoring state manager
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        
        // Load the refactoring plan
        RefactoringPlan plan = stateManager.loadPlan();
        if (plan == null) {
            throw new PipelineExecutionException("Failed to load refactoring plan");
        }
        
        // Load the progress
        RefactoringProgress progress = stateManager.loadProgress();
        if (progress == null) {
            throw new PipelineExecutionException("Failed to load refactoring progress");
        }
        
        // Check if the plan has any issues
        if (plan.getIssues().isEmpty()) {
            progress.markComplete();
            stateManager.saveProgress(progress);
            LOG.info("Refactoring plan has no issues, marking as complete");
            return;
        }
        
        // Get the current issue and step
        RefactoringIssue currentIssue = null;
        RefactoringStep currentStep = null;
        
        try {
            currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid issue or step index", e);
            throw new PipelineExecutionException("Invalid issue or step index: " + e.getMessage());
        }
        
        // Process the LLM response for the current step
        // In a real implementation, we would analyze the LLM response
        // and apply the changes to the code, potentially using diff tools.
        // For now, we'll just update the progress and move to the next step.
        
        // Mark the current step as complete
        currentStep.markComplete();
        progress.markStepComplete(currentStep.getId());
        
        // Move to the next step or issue
        moveToNextStep(plan, progress, currentIssue);
        
        // Save the updated progress
        if (!stateManager.saveProgress(progress)) {
            throw new PipelineExecutionException("Failed to save refactoring progress");
        }
        
        // If we've completed all steps, mark the refactoring as complete
        if (progress.getCurrentIssueIndex() >= plan.getIssues().size()) {
            progress.markComplete();
            stateManager.saveProgress(progress);
            
            LOG.info("Refactoring completed successfully");
            
            // Show completion message
            Messages.showInfoMessage(
                    project,
                    "The refactoring process has been completed successfully!",
                    "Refactoring Complete"
            );
            
            // Clear the refactoring state
            stateManager.clearRefactoringState();
        }
    }
    
    /**
     * Moves the progress to the next step or issue.
     */
    private void moveToNextStep(RefactoringPlan plan, RefactoringProgress progress, RefactoringIssue currentIssue) {
        // Move to the next step in the current issue
        if (progress.getCurrentStepIndex() < currentIssue.getSteps().size() - 1) {
            progress.setCurrentStepIndex(progress.getCurrentStepIndex() + 1);
            return;
        }
        
        // Move to the next issue
        if (progress.getCurrentIssueIndex() < plan.getIssues().size() - 1) {
            progress.setCurrentIssueIndex(progress.getCurrentIssueIndex() + 1);
            progress.setCurrentStepIndex(0);
            return;
        }
        
        // All issues and steps have been completed
        progress.setCurrentIssueIndex(plan.getIssues().size());
        progress.setCurrentStepIndex(0);
    }
}
