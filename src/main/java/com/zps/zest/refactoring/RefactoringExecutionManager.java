package com.zps.zest.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.browser.utils.ChatboxUtilities;

/**
 * Manages the execution of refactoring steps by coordinating with the LLM.
 * This class handles the interaction with the chat interface and tracks progress.
 */
public class RefactoringExecutionManager {
    private static final Logger LOG = Logger.getInstance(RefactoringExecutionManager.class);
    private final Project project;
    private final RefactoringStateManager stateManager;
    
    /**
     * Creates a new refactoring execution manager.
     * 
     * @param project The project to execute refactorings for
     */
    public RefactoringExecutionManager(Project project) {
        this.project = project;
        this.stateManager = new RefactoringStateManager(project);
    }
    
    /**
     * Executes a single refactoring step and updates progress.
     * 
     * @param plan The refactoring plan
     * @param progress The current progress
     * @return true if the step was executed successfully, false otherwise
     */
    public boolean executeStep(RefactoringPlan plan, RefactoringProgress progress) {
        LOG.info("Executing refactoring step: " + progress.getCurrentStep());
        
        // Get the current issue and step
        RefactoringIssue currentIssue;
        RefactoringStep currentStep;
        
        try {
            currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid issue or step index", e);
            return false;
        }
        
        // Create the execution prompt
        String executionPrompt = createStepExecutionPrompt(plan, progress, currentIssue, currentStep);
        
        // Send the prompt to the chat box
        boolean sent = sendPromptToChatBox(executionPrompt);
        if (!sent) {
            LOG.error("Failed to send execution prompt to chat box");
            return false;
        }
        
        // Update the step status
        currentStep.setStatus(RefactoringStepStatus.IN_PROGRESS);
        
        // Save the updated progress
        return stateManager.saveProgress(progress);
    }
    
    /**
     * Creates a prompt for executing a specific refactoring step.
     */
    private String createStepExecutionPrompt(RefactoringPlan plan, RefactoringProgress progress, 
                                             RefactoringIssue currentIssue, RefactoringStep currentStep) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("# Refactoring Step: ").append(currentStep.getTitle()).append("\n\n");
        
        prompt.append("## Context\n");
        prompt.append("- Issue: ").append(currentIssue.getTitle()).append("\n");
        prompt.append("- Issue Description: ").append(currentIssue.getDescription()).append("\n");
        prompt.append("- Progress: Step ").append(progress.getCurrentStepIndex() + 1)
              .append(" of ").append(currentIssue.getSteps().size())
              .append(" in Issue ").append(progress.getCurrentIssueIndex() + 1)
              .append(" of ").append(plan.getIssues().size()).append("\n\n");
        
        prompt.append("## Step Details\n");
        prompt.append(currentStep.getDescription()).append("\n\n");
        
        if (currentStep.getFilePath() != null && !currentStep.getFilePath().isEmpty()) {
            prompt.append("**File:** ").append(currentStep.getFilePath()).append("\n\n");
        }
        
        if (currentStep.getCodeChangeDescription() != null && !currentStep.getCodeChangeDescription().isEmpty()) {
            prompt.append("**Required Change:** ").append(currentStep.getCodeChangeDescription()).append("\n\n");
        }
        
        if (currentStep.getBefore() != null && !currentStep.getBefore().isEmpty()) {
            prompt.append("**Code Before:**\n```java\n").append(currentStep.getBefore()).append("\n```\n\n");
        }
        
        if (currentStep.getAfter() != null && !currentStep.getAfter().isEmpty()) {
            prompt.append("**Suggested Code After:**\n```java\n").append(currentStep.getAfter()).append("\n```\n\n");
        }
        
        // Project-specific context can be loaded here if needed
        
        prompt.append("## Instructions\n");
        prompt.append("1. Analyze the refactoring step and its context\n");
        prompt.append("2. Implement the changes according to the step description\n");
        prompt.append("3. Provide the complete code after your changes\n");
        prompt.append("4. Explain the changes made and how they improve testability\n");
        prompt.append("5. After completion, I will mark this step as done and move to the next step\n\n");
        
        prompt.append("Please implement this refactoring step now.");
        
        return prompt.toString();
    }
    
    /**
     * Sends a prompt to the chat box and activates the browser window.
     */
    private boolean sendPromptToChatBox(String prompt) {
        // Get the appropriate system prompt
        String systemPrompt = ConfigurationManager.getInstance(project).getCodeSystemPrompt();
        
        // Send the prompt to the chat box
        boolean success = ChatboxUtilities.sendTextAndSubmit(project, prompt, false, systemPrompt);
        
        // Activate browser tool window
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
        });
        
        return success;
    }
    
    /**
     * Completes the current step and moves to the next one.
     * 
     * @return true if there are more steps to execute, false if the refactoring is complete
     */
    public boolean completeCurrentStepAndMoveToNext() {
        // Load the current plan and progress
        RefactoringPlan plan = stateManager.loadPlan();
        RefactoringProgress progress = stateManager.loadProgress();
        
        if (plan == null || progress == null) {
            LOG.error("Failed to load refactoring plan or progress");
            return false;
        }
        
        // Get the current issue and step
        RefactoringIssue currentIssue;
        RefactoringStep currentStep;
        
        try {
            currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid issue or step index", e);
            return false;
        }
        
        // Mark the current step as complete
        currentStep.setStatus(RefactoringStepStatus.COMPLETED);
        progress.markStepComplete(currentStep.getId());
        
        // Move to the next step
        if (progress.getCurrentStepIndex() < currentIssue.getSteps().size() - 1) {
            // More steps in the current issue
            progress.setCurrentStepIndex(progress.getCurrentStepIndex() + 1);
        } else if (progress.getCurrentIssueIndex() < plan.getIssues().size() - 1) {
            // Move to the next issue
            progress.setCurrentIssueIndex(progress.getCurrentIssueIndex() + 1);
            progress.setCurrentStepIndex(0);
        } else {
            // All steps are complete
            progress.markComplete();
            stateManager.saveProgress(progress);
            
            // Show completion message
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(
                        project,
                        "The refactoring process has been completed successfully!",
                        "Refactoring Complete"
                );
            });
            
            // Clear the refactoring state
            stateManager.clearRefactoringState();
            return false;
        }
        
        // Save the updated progress
        stateManager.saveProgress(progress);
        return true;
    }
    
    /**
     * Skips the current step and moves to the next one.
     * 
     * @return true if there are more steps to execute, false if the refactoring is complete
     */
    public boolean skipCurrentStepAndMoveToNext() {
        // Load the current plan and progress
        RefactoringPlan plan = stateManager.loadPlan();
        RefactoringProgress progress = stateManager.loadProgress();
        
        if (plan == null || progress == null) {
            LOG.error("Failed to load refactoring plan or progress");
            return false;
        }
        
        // Get the current issue and step
        RefactoringIssue currentIssue;
        RefactoringStep currentStep;
        
        try {
            currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid issue or step index", e);
            return false;
        }
        
        // Mark the current step as skipped
        currentStep.setStatus(RefactoringStepStatus.SKIPPED);
        progress.markStepSkipped(currentStep.getId());
        
        // Move to the next step (same logic as complete)
        if (progress.getCurrentStepIndex() < currentIssue.getSteps().size() - 1) {
            progress.setCurrentStepIndex(progress.getCurrentStepIndex() + 1);
        } else if (progress.getCurrentIssueIndex() < plan.getIssues().size() - 1) {
            progress.setCurrentIssueIndex(progress.getCurrentIssueIndex() + 1);
            progress.setCurrentStepIndex(0);
        } else {
            // All steps are complete or skipped
            progress.markComplete();
            stateManager.saveProgress(progress);
            
            // Show completion message
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage(
                        project,
                        "The refactoring process has been completed (with skipped steps).",
                        "Refactoring Complete"
                );
            });
            
            // Clear the refactoring state
            stateManager.clearRefactoringState();
            return false;
        }
        
        // Save the updated progress
        stateManager.saveProgress(progress);
        return true;
    }
    
    /**
     * Aborts the refactoring process.
     */
    public void abortRefactoring() {
        // Load the current progress
        RefactoringProgress progress = stateManager.loadProgress();
        
        if (progress != null) {
            // Mark the refactoring as aborted
            progress.markAborted();
            stateManager.saveProgress(progress);
        }
        
        // Clear the refactoring state
        stateManager.clearRefactoringState();
        
        // Show abort message
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(
                    project,
                    "The refactoring process has been aborted.",
                    "Refactoring Aborted"
            );
        });
    }
}
