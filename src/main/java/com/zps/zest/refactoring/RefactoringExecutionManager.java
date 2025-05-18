package com.zps.zest.refactoring;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.zps.zest.ClassAnalyzer;
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
        
        // Load additional context from the state manager
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        JsonObject contextJson = stateManager.loadContext();
        
        if (contextJson == null) {
            LOG.error("Failed to load refactoring context");
            return false;
        }
        
        // Extract target class name
        String className = contextJson.has("className") ? contextJson.get("className").getAsString() : plan.getTargetClass();
        String packageName = contextJson.has("packageName") ? contextJson.get("packageName").getAsString() : "";
        
        // Try to find the class file programmatically to get fresh context
        PsiClass targetClass = findTargetClass(packageName, className);
        String updatedClassContext = null;
        
        if (targetClass != null) {
            // Use ClassAnalyzer to get fresh class context
            updatedClassContext = ClassAnalyzer.collectClassContext(targetClass);
            LOG.info("Using fresh class context from ClassAnalyzer");
        } else if (contextJson.has("classContext")) {
            // Fall back to stored context
            updatedClassContext = contextJson.get("classContext").getAsString();
            LOG.info("Using stored class context");
        }
        
        if (updatedClassContext == null) {
            LOG.error("No class context available");
            return false;
        }
        
        // Remember if this is a continuation of a conversation or a new chat
        boolean isNewChat = isNewChatNeeded(progress);
        
        // Create the execution prompt
        String executionPrompt = createStepExecutionPrompt(
                plan, progress, currentIssue, currentStep, 
                updatedClassContext, packageName, contextJson, isNewChat);
        
        // If it's a new chat, first click the new chat button
        if (isNewChat) {
            boolean newChatClicked = ChatboxUtilities.clickNewChatButton(project);
            if (!newChatClicked) {
                LOG.warn("Failed to click new chat button");
                // Continue anyway, it might work
            }
        }
        
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
     * Determines if a new chat is needed based on the progress.
     */
    private boolean isNewChatNeeded(RefactoringProgress progress) {
        // If this is the first step, always start a new chat
        if (progress.getCurrentIssueIndex() == 0 && progress.getCurrentStepIndex() == 0) {
            return true;
        }
        
        // Check if it's been more than 30 minutes since the last update
        if (progress.getLastUpdateDate() != null) {
            long elapsedTime = System.currentTimeMillis() - progress.getLastUpdateDate().getTime();
            long thirtyMinutesInMillis = 30 * 60 * 1000;
            
            if (elapsedTime > thirtyMinutesInMillis) {
                return true;
            }
        }
        
        // Otherwise, continue the same chat
        return false;
    }
    
    /**
     * Attempts to find the target class in the project.
     */
    private PsiClass findTargetClass(String packageName, String className) {
        try {
            return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<PsiClass>) () -> {
                // Get all Java files
                com.intellij.psi.search.PsiShortNamesCache cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                PsiClass[] classes = cache.getClassesByName(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project));
                
                // Find the class with the matching package
                for (PsiClass psiClass : classes) {
                    if (psiClass.getContainingFile() instanceof PsiJavaFile) {
                        PsiJavaFile javaFile = (PsiJavaFile) psiClass.getContainingFile();
                        if (javaFile.getPackageName().equals(packageName)) {
                            return psiClass;
                        }
                    }
                }
                
                return null;
            });
        } catch (Exception e) {
            LOG.error("Error finding target class", e);
            return null;
        }
    }
    
    /**
     * Creates a prompt for executing a specific refactoring step.
     */
    private String createStepExecutionPrompt(RefactoringPlan plan, RefactoringProgress progress, 
                                             RefactoringIssue currentIssue, RefactoringStep currentStep,
                                             String classContext, String packageName, JsonObject contextJson,
                                             boolean isNewChat) {
        StringBuilder prompt = new StringBuilder();
        
        // If this is a new chat, include the full project and refactoring context
        if (isNewChat) {
            prompt.append("# Agent-Based Refactoring for Testability\n\n");
            prompt.append("I'll help you implement a series of refactoring steps to improve the testability of ");
            prompt.append(plan.getTargetClass()).append(". This is a structured refactoring process with multiple steps.\n\n");
            
            prompt.append("## Refactoring Plan Overview\n");
            prompt.append("Total issues: ").append(plan.getIssues().size());
            prompt.append(", Total steps: ").append(plan.getTotalStepCount()).append("\n\n");
            
            // List all issues and steps
            for (RefactoringIssue issue : plan.getIssues()) {
                prompt.append("- Issue ").append(issue.getId()).append(": ").append(issue.getTitle()).append("\n");
                for (RefactoringStep step : issue.getSteps()) {
                    prompt.append("  - Step ").append(step.getId()).append(": ").append(step.getTitle()).append("\n");
                }
            }
            prompt.append("\n");
        }
        
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
        
        // Add comprehensive class context
        prompt.append("## Class Context\n\n");
        
        // Include package information
        prompt.append("**Package:** ").append(packageName).append("\n\n");
        
        // Include imports
        if (contextJson.has("imports")) {
            prompt.append("**Imports:**\n```java\n").append(contextJson.get("imports").getAsString()).append("\n```\n\n");
        }
        
        // Include the full class context from ClassAnalyzer
        prompt.append("**Full Class Analysis:**\n").append(classContext).append("\n\n");
        
        // Add instructions for the LLM agent
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
        
        // Save the updated progress and plan
        stateManager.saveProgress(progress);
        stateManager.savePlan(plan);
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
        
        // Save the updated progress and plan
        stateManager.saveProgress(progress);
        stateManager.savePlan(plan);
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
