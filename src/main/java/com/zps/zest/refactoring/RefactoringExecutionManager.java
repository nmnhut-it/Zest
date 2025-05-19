package com.zps.zest.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.browser.utils.ChatboxUtilities;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Manages the execution of refactoring steps by coordinating with the LLM.
 * This class handles the interaction with the chat interface and tracks progress.
 */
public class RefactoringExecutionManager {
    public static final String FIX_PROBLEMS_AND_VERIFY_ISSUE_IMPLEMENTATION = "Fix Problems and Verify Issue Implementation";
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
     * @param plan     The refactoring plan
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
                    ((PsiJavaFile) targetClass.getContainingFile()).getPackageName() : "";

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
        return true;

//        // If this is the first step, always start a new chat
//        if (progress.getCurrentIssueIndex() == 0 && progress.getCurrentStepIndex() == 0) {
//            return true;
//        }
//
//        // Check if it's been more than 30 minutes since the last update
//        if (progress.getLastUpdateDate() != null) {
//            long elapsedTime = System.currentTimeMillis() - progress.getLastUpdateDate().getTime();
//            long thirtyMinutesInMillis = 30 * 60 * 1000;
//
//            if (elapsedTime > thirtyMinutesInMillis) {
//                return true;
//            }
//        }
//
//        // Otherwise, continue the same chat
//        return false;
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
                            ((PsiJavaFile) foundClass.getContainingFile()).getPackageName() : "unknown";
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
        if (currentStep.getTitle().equals(FIX_PROBLEMS_AND_VERIFY_ISSUE_IMPLEMENTATION) == false) {

            prompt.append("## Implementation Instructions\n");
            prompt.append("Implement this refactoring:\n");
            prompt.append("1. Reason through each change you'll make and why it improves testability\n");
            prompt.append("2. If you determine this specific aspect of code is already well-designed for testability, recommend skipping this step\n");
            prompt.append("3. Otherwise, make only minimal changes needed to address the specific issue\n");
            prompt.append("4. Ensure code maintains all existing functionality\n");
            prompt.append("5. Verify your changes will compile correctly\n\n");

            prompt.append("## Response Format\n");
            prompt.append("Follow this exact structure:\n\n");
            prompt.append("#### ANALYSIS: <Your reasoning process about how to approach this refactoring>\n\n");
            prompt.append("#### SUMMARY: <One sentence describing what the change accomplishes>\n\n");
            prompt.append("#### IMPLEMENTATION:\n\n");
            prompt.append("replace_in_file:").append(currentStep.getFilePath() == null ? plan.getTargetClass() + ".java" : currentStep.getFilePath()).append("\n");
            prompt.append("```java\n");
            prompt.append("// code to be replaced - copy exact code from current class\n");
            prompt.append("```\n");
            prompt.append("```java\n");
            prompt.append("// replacement code with testability improvements\n");
            prompt.append("```\n\n");
            prompt.append("Add more replace_in_file blocks as needed for multiple changes.\n\n");
            prompt.append("#### VALIDATION\n");
            prompt.append("  <Verification of code correctness>\n");
            prompt.append("  <Potential side effects>\n\n");
            prompt.append("After implementing, check for compiler errors or warnings:\n");
            prompt.append("```\n");
            prompt.append("tool_get_project_problems_post\n");
            prompt.append("```\n\n");
        }

        // Step details at the end
        prompt.append("## Step Details\n");
        prompt.append(currentStep.getDescription()).append("\n\n");
        if (currentStep.getFilePath() != null && !currentStep.getFilePath().isEmpty()) {
            prompt.append("**File:** ").append(currentStep.getFilePath()).append("\n\n");
        }
        if (currentStep.getTitle().equals(FIX_PROBLEMS_AND_VERIFY_ISSUE_IMPLEMENTATION) == false) {

            if (currentStep.getCodeChangeDescription() != null && !currentStep.getCodeChangeDescription().isEmpty()) {
                prompt.append("**Required Change:** ").append(currentStep.getCodeChangeDescription()).append("\n\n");
            }

            prompt.append("IMPORTANT: Use the `replace_in_file` format for all code changes. This allows the changes to be applied automatically through the IDE's code replacement feature.\n\n");
            prompt.append("Implement this refactoring step now, following the exact format above.");
        }

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
                + "\n3. Otherwise, use the replace_in_file format for code changes"
                + "\n4. Include exact code blocks to replace with the new improved code"
                + "\n5. Explain how each change improves testability"
                + "\n6. Validate that code will compile and maintain functionality"
                + "\n"
                + "\nFormat your responses exactly like this: "
                + "\n#### ANALYSIS:\n\n <Your reasoning about how to approach this refactoring>"
                + "\n#### SUMMARY:\n\n <Either explanation of why no change is needed OR one-sentence overview of change> "
                + "\n#### IMPLEMENTATION:"
                + "\nreplace_in_file:absolute/path/to/file.java"
                + "\n```java"
                + "\n// exact code to be replaced"
                + "\n```"
                + "\n```java"
                + "\n// replacement code with improved testability"
                + "\n```"
                + "\n#### VALIDATION:\n\n <Verification of code correctness> <Potential side effects>"
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

        // Record the summary of this completed step
        String completedChange = currentIssue.getTitle() + " - " + currentStep.getTitle();
        progress.addCompletedChange(completedChange);

        // Mark the current step as complete
        currentStep.setStatus(RefactoringStepStatus.COMPLETED);
        progress.markStepComplete(currentStep.getId());

        // Check if this was the last step of the current issue
        boolean isLastStepOfIssue = (progress.getCurrentStepIndex() >= currentIssue.getSteps().size() - 1);

        // If this is the last step of the issue, check for problems and try to fix them
        if (isLastStepOfIssue) {
            LOG.info("Completed all steps in issue: " + currentIssue.getTitle() + ". Checking for problems.");

            // We'll create a prompt that Claude will use to check for problems
            // This is handled by the MCP protocol via the browser integration
            // The result will be included in the next prompt when a fix step is created

            // Create a placeholder entry for problems to be filled with actual data
            // from the chat interaction
            progress.addProblemsAfterIssue(currentIssue.getId(), new ArrayList<>());

            // Create a special step to fix any problems
            // This step will be shown in the UI and executed by Claude via MCP
            if (!isProblemFixStep(currentStep)) {
                createFixProblemStep(currentIssue);

                // Don't move to next issue yet - stay at the fix step
                progress.setCurrentStepIndex(currentIssue.getSteps().size() - 1);
            }
            stateManager.saveProgress(progress);
            stateManager.savePlan(plan);
            return true;
        }

        // Move to the next step or issue
        if (!isLastStepOfIssue) {
            // More steps in the current issue
            progress.setCurrentStepIndex(progress.getCurrentStepIndex() + 1);
        } else if (progress.getCurrentIssueIndex() < plan.getIssues().size() - 1) {
            // Move to the next issue - we know there are no problems with the current issue
            progress.setCurrentIssueIndex(progress.getCurrentIssueIndex() + 1);
            progress.setCurrentStepIndex(0);
        } else {
            // All steps are complete - generate final report
            progress.markComplete();
            stateManager.saveProgress(progress);

            // Generate and show final report
            generateFinalReport(plan, progress);

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

            // Generate and show final report
            generateFinalReport(plan, progress);

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

    boolean isProblemFixStep(RefactoringStep step) {
        return step.getTitle().equals(FIX_PROBLEMS_AND_VERIFY_ISSUE_IMPLEMENTATION);

    }

    /**
     * Creates a new step to fix problems detected after completing an issue.
     *
     * @param issue The current issue
     */
    private void createFixProblemStep(RefactoringIssue issue) {
        // Create a description of the problem check
        StringBuilder problemDescription = new StringBuilder();
        problemDescription.append("Now that we've completed the implementation for this issue, let's check for any problems and fix them.\n\n");
        problemDescription.append("First, I'll check for any compiler errors or warnings:\n\n");
        problemDescription.append("```\n");
        problemDescription.append("tool_get_project_problems_post\n");
        problemDescription.append("```\n\n");
        problemDescription.append("Just call the tool, do nothing just yet.");

        // Create a new step
        RefactoringStep fixStep = new RefactoringStep();
        fixStep.setId(issue.getSteps().size() + 1); // Generate a new ID
        fixStep.setTitle(FIX_PROBLEMS_AND_VERIFY_ISSUE_IMPLEMENTATION);
        fixStep.setDescription(problemDescription.toString());
        fixStep.setStatus(RefactoringStepStatus.PENDING);
        // Set the file path from the issue's target file if available, otherwise use the target class name
        String targetFile = issue.getTargetFile();
        if (targetFile != null && !targetFile.isEmpty()) {
            fixStep.setFilePath(targetFile);
        } else {
            // Fall back to using the target class name
            RefactoringPlan plan = stateManager.loadPlan();
            if (plan != null) {
                fixStep.setFilePath(plan.getTargetClass() + ".java");
            }
        }

        // Add the step to the issue
        issue.getSteps().add(fixStep);
    }

    /**
     * Generates a final report of the refactoring process and displays it.
     */
    private void generateFinalReport(RefactoringPlan plan, RefactoringProgress progress) {
        StringBuilder report = new StringBuilder();

        // Report header
        report.append("# Refactoring Completed: ").append(plan.getName()).append("\n\n");
        report.append("## Target Class: ").append(plan.getTargetClass()).append("\n\n");

        // Statistics
        int totalSteps = 0;
        for (RefactoringIssue issue : plan.getIssues()) {
            totalSteps += issue.getSteps().size();
        }

        report.append("## Statistics\n");
        report.append("- **Start Date:** ").append(formatDate(progress.getStartDate())).append("\n");
        report.append("- **End Date:** ").append(formatDate(progress.getLastUpdateDate())).append("\n");
        report.append("- **Total Issues:** ").append(plan.getIssues().size()).append("\n");
        report.append("- **Total Steps:** ").append(totalSteps).append("\n");
        report.append("- **Completed Steps:** ").append(progress.getCompletedStepIds().size()).append("\n");
        report.append("- **Skipped Steps:** ").append(progress.getSkippedStepIds().size()).append("\n");
        report.append("- **Failed Steps:** ").append(progress.getFailedStepIds().size()).append("\n\n");

        // Summary of changes by issue
        report.append("## Changes Implemented by Issue\n");
        List<String> changes = progress.getCompletedChanges();
        if (changes != null && !changes.isEmpty()) {
            // Group by issue
            Map<String, List<String>> changesByIssue = new java.util.HashMap<>();

            for (String change : changes) {
                String[] parts = change.split(" - ", 2);
                if (parts.length >= 2) {
                    String issueTitle = parts[0];
                    String stepTitle = parts[1];

                    if (!changesByIssue.containsKey(issueTitle)) {
                        changesByIssue.put(issueTitle, new java.util.ArrayList<>());
                    }
                    changesByIssue.get(issueTitle).add(stepTitle);
                } else {
                    // Fallback if the format is unexpected
                    if (!changesByIssue.containsKey("Other")) {
                        changesByIssue.put("Other", new java.util.ArrayList<>());
                    }
                    changesByIssue.get("Other").add(change);
                }
            }

            // Display by issue
            for (Map.Entry<String, List<String>> entry : changesByIssue.entrySet()) {
                report.append("### ").append(entry.getKey()).append("\n");
                for (String step : entry.getValue()) {
                    report.append("- ").append(step).append("\n");
                }
                report.append("\n");
            }
        } else {
            report.append("No changes were implemented.\n\n");
        }

        // Conclusion
        report.append("## Conclusion\n");
        report.append("This refactoring has improved the testability of ").append(plan.getTargetClass());
        report.append(" by addressing ").append(plan.getIssues().size()).append(" testability issues. ");
        report.append("The code is now more maintainable and easier to test, which will lead to more reliable software.");

        // Show the final report
        String finalReport = report.toString();

        // Send the report to the browser
        ApplicationManager.getApplication().invokeLater(() -> {
            // First show a notification about the complete refactoring
            Messages.showInfoMessage(
                    project,
                    "The refactoring process has been completed successfully! A final report has been generated.",
                    "Refactoring Complete"
            );

            // Send the report to the chat box
            boolean sent = ChatboxUtilities.sendTextAndSubmit(project,
                    "# Refactoring Complete - Final Report\n\n" +
                            "Here is the final report for the completed refactoring:\n\n" +
                            finalReport,
                    true,
                    null);

            if (!sent) {
                LOG.warn("Failed to send final report to chat box");
            }
        });
    }

    /**
     * Formats a date for the report.
     */
    private String formatDate(Date date) {
        if (date == null) {
            return "Unknown";
        }

        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
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
