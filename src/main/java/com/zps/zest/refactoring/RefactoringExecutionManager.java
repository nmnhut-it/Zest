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
        
        // Extract target class name from the plan
        String className = plan.getTargetClass();
        String packageName = "";
        
        // We don't need or use context.json - always get fresh class context
        PsiClass targetClass = findTargetClass(packageName, className);
        String updatedClassContext = null;
        
        if (targetClass != null) {
            // Get the actual package name from the found class
            packageName = targetClass.getContainingFile() instanceof PsiJavaFile ?
                    ((PsiJavaFile)targetClass.getContainingFile()).getPackageName() : "";
                    
            // Use ClassAnalyzer to get fresh class context with any recent user modifications
            updatedClassContext = ClassAnalyzer.collectClassContext(targetClass);
            LOG.info("Using fresh class context from ClassAnalyzer for step: " + progress.getCurrentStep());
        } else {
            LOG.error("Could not find target class: " + className);
            return false;
        }
        
        if (updatedClassContext == null) {
            LOG.error("No class context available");
            return false;
        }
        
        // Remember if this is a continuation of a conversation or a new chat
        boolean isNewChat = isNewChatNeeded(progress);
        
        // Create the execution prompt with the fresh context
        String executionPrompt = createStepExecutionPrompt(
                plan, progress, currentIssue, currentStep, 
                updatedClassContext, packageName, isNewChat);
        
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
     * Uses the available class name information and tries multiple strategies.
     */
    private PsiClass findTargetClass(String packageName, String className) {
        try {
            return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<PsiClass>) () -> {
                // Get all Java files
                com.intellij.psi.search.PsiShortNamesCache cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                PsiClass[] classes = cache.getClassesByName(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project));
                
                // If we have a package name, try to find an exact match first
                if (packageName != null && !packageName.isEmpty()) {
                    for (PsiClass psiClass : classes) {
                        if (psiClass.getContainingFile() instanceof PsiJavaFile) {
                            PsiJavaFile javaFile = (PsiJavaFile) psiClass.getContainingFile();
                            if (javaFile.getPackageName().equals(packageName)) {
                                LOG.info("Found target class by exact package match: " + packageName + "." + className);
                                return psiClass;
                            }
                        }
                    }
                }
                
                // If no match with package or no package provided, just return the first class with matching name
                if (classes.length > 0) {
                    PsiClass foundClass = classes[0];
                    String foundPackage = foundClass.getContainingFile() instanceof PsiJavaFile ? 
                            ((PsiJavaFile)foundClass.getContainingFile()).getPackageName() : "unknown";
                    LOG.info("Found target class by name only: " + foundPackage + "." + className);
                    return foundClass;
                }
                
                LOG.warn("Could not find target class: " + className);
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
                                             String classContext, String packageName, boolean isNewChat) {
        StringBuilder prompt = new StringBuilder();
        
        // If this is a new chat, include a brief introduction
        if (isNewChat) {
            prompt.append("# Java Testability Refactoring Implementation\n\n");
            prompt.append("I'll help you implement a series of refactoring steps to improve the testability of ");
            prompt.append(plan.getTargetClass()).append(".\n\n");
        }
        
        prompt.append("# Refactoring Step: ").append(currentStep.getTitle()).append("\n\n");
        
        prompt.append("## Context\n");
        prompt.append("- Issue: ").append(currentIssue.getTitle()).append("\n");
        prompt.append("- Progress: Step ").append(progress.getCurrentStepIndex() + 1)
              .append("/").append(currentIssue.getSteps().size())
              .append(" (Issue ").append(progress.getCurrentIssueIndex() + 1)
              .append("/").append(plan.getIssues().size()).append(")\n\n");
        
        // Add class context - gets the latest directly from ClassAnalyzer for each step
        prompt.append("## Current Class\n");
        
        // Include package information if available
        if (packageName != null && !packageName.isEmpty()) {
            prompt.append("**Package:** ").append(packageName).append("\n\n");
        }
        
        // Include the full class context from ClassAnalyzer - this reflects any recent changes
        prompt.append("```java\n").append(classContext).append("\n```\n\n");
        
        prompt.append("## Implementation Instructions\n");
        prompt.append("Implement this refactoring:\n");
        prompt.append("1. Reason through each change you'll make and why it improves testability\n");
        prompt.append("2. If you determine this specific aspect of code is already well-designed for testability, recommend skipping this step\n");
        prompt.append("3. Otherwise, make only minimal changes needed to address the specific issue\n");
        prompt.append("4. Ensure code maintains all existing functionality\n");
        prompt.append("5. Verify your changes will compile correctly\n\n");
        
        prompt.append("## Response Format\n");
        prompt.append("Follow this exact structure:\n\n");
        prompt.append("◉ ANALYSIS: <Your reasoning process about how to approach this refactoring>\n\n");
        prompt.append("◉ SUMMARY: <One sentence describing what the change accomplishes>\n\n");
        prompt.append("◉ CHANGE #1\n");
        prompt.append("  Location: Lines XX-YY\n");
        prompt.append("  Replace:\n");
        prompt.append("  ```java\n");
        prompt.append("  // Exact code to be replaced\n");
        prompt.append("  ```\n");
        prompt.append("  With:\n");
        prompt.append("  ```java\n");
        prompt.append("  // New code\n");
        prompt.append("  ```\n");
        prompt.append("  Why: <Brief explanation of testability benefit>\n\n");
        prompt.append("[Add more CHANGE sections as needed]\n\n");
        prompt.append("◉ VALIDATION\n");
        prompt.append("  <Verification of code correctness>\n");
        prompt.append("  <Potential side effects>\n\n");
        
        // Step details at the end
        prompt.append("## Step Details\n");
        prompt.append(currentStep.getDescription()).append("\n\n");
        
        if (currentStep.getFilePath() != null && !currentStep.getFilePath().isEmpty()) {
            prompt.append("**File:** ").append(currentStep.getFilePath()).append("\n\n");
        }
        
        if (currentStep.getCodeChangeDescription() != null && !currentStep.getCodeChangeDescription().isEmpty()) {
            prompt.append("**Required Change:** ").append(currentStep.getCodeChangeDescription()).append("\n\n");
        }
        
        prompt.append("Implement this refactoring step now, following the exact format above.");
        
        return prompt.toString();
    }
    
    /**
     * Sends a prompt to the chat box and activates the browser window.
     */
    private boolean sendPromptToChatBox(String prompt) {
        // Create a specialized system prompt for refactoring steps
        String systemPrompt = "You are a specialized Java refactoring agent with superior expertise in improving code testability. "
                + "\nAnalyze Java code to identify testability issues and implement precise refactorings that maintain functionality."
                + "\n"
                + "\nWhen implementing refactorings:"
                + "\n1. First reason through your approach and explain your thought process"
                + "\n2. If the specific aspect of code is already well-designed for testability, recommend skipping this step"
                + "\n3. Otherwise, provide exact line numbers for changes"
                + "\n4. Include before/after code blocks with minimum necessary changes"
                + "\n5. Explain how each change improves testability"
                + "\n6. Validate that code will compile and maintain functionality"
                + "\n"
                + "\nFormat your responses exactly like this: "
                + "\n◉ ANALYSIS: <Your reasoning about how to approach this refactoring>"
                + "\n◉ SUMMARY: <Either explanation of why no change is needed OR one-sentence overview of change> "
                + "\n◉ CHANGE #1 "
                + "\n  Location: Lines XX-YY "
                + "\n  Replace: [code block with ```java] "
                + "\n  With: [code block with ```java] "
                + "\n  Why: <Brief testability benefit> "
                + "\n◉ VALIDATION: <Verification of code correctness> <Potential side effects>"
                + "\n"
                + "\nFocus exclusively on testability issues such as:"
                + "\n- Dependency injection opportunities"
                + "\n- Removing static method calls"
                + "\n- Extracting hard-coded values"
                + "\n- Breaking down complex methods"
                + "\n- Removing global state"
                + "\n- Decoupling from external resources"
                + "\n"
                + "\nPreserve original functionality while improving testability."
                + "\n/no_think";

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
            
            // Only close the tool window when all steps are complete
            // This branch is only reached at the end of the entire refactoring process
            RefactoringToolWindow.checkAndCloseIfNoRefactoring(project);
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
        
        // Move to the next step
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
            
            // Only close the tool window when all steps are complete
            // This branch is only reached at the end of the entire refactoring process
            RefactoringToolWindow.checkAndCloseIfNoRefactoring(project);
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
        
        // Close the tool window
        RefactoringToolWindow.checkAndCloseIfNoRefactoring(project);
        
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
