package com.zps.zest.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * Tool window for managing the refactoring process.
 * Updated for compatibility with newer IntelliJ Platform versions.
 */
public class RefactoringToolWindow {
    private static final Logger LOG = Logger.getInstance(RefactoringToolWindow.class);
    private static final String TOOL_WINDOW_ID = "Refactoring for Testability";

    private final Project project;
    private final RefactoringPlan plan;
    private final RefactoringProgress progress;
    private final RefactoringExecutionManager executionManager;
    private final RefactoringStateManager stateManager;

    /**
     * Creates a new refactoring tool window.
     */
    private RefactoringToolWindow(Project project, RefactoringPlan plan, RefactoringProgress progress) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.executionManager = new RefactoringExecutionManager(project);
        this.stateManager = new RefactoringStateManager(project);
    }

    /**
     * Creates and shows a new tool window for managing refactoring.
     * This method creates and displays the tool window programmatically.
     *
     * @param project  The project
     * @param plan     The refactoring plan
     * @param progress The current progress
     * @return The created tool window instance, or null if creation failed
     */
    public static RefactoringToolWindow showToolWindow(Project project, RefactoringPlan plan, RefactoringProgress progress) {
        try {
            // Validate plan and progress
            if (plan == null) {
                LOG.error("Cannot show tool window: Refactoring plan is null");
                Messages.showErrorDialog(project,
                        "No refactoring plan is available. Please start a new refactoring process.",
                        "No Refactoring Plan");
                return null;
            }

            if (plan.getIssues() == null || plan.getIssues().isEmpty()) {
                LOG.error("Cannot show tool window: Refactoring plan has no issues");
                Messages.showInfoMessage(project,
                        "No testability issues were found in the selected class.",
                        "No Issues Found");
                RefactoringStateManager stateManager = new RefactoringStateManager(project);
                stateManager.clearRefactoringState();
                return null;
            }

            if (progress == null) {
                LOG.error("Cannot show tool window: Refactoring progress is null");
                Messages.showErrorDialog(project,
                        "Refactoring progress information is missing. Please start a new refactoring process.",
                        "No Progress Information");
                RefactoringStateManager stateManager = new RefactoringStateManager(project);
                stateManager.clearRefactoringState();
                return null;
            }

            // If a previous refactoring was completed or aborted, don't show the tool window
            if (progress.getStatus() == RefactoringStatus.COMPLETED || progress.getStatus() == RefactoringStatus.ABORTED) {
                LOG.info("Not showing tool window - refactoring was " + progress.getStatus().toString().toLowerCase());
                Messages.showInfoMessage(project,
                        "The previous refactoring was " + progress.getStatus().toString().toLowerCase() + ". Please start a new refactoring process.",
                        "Refactoring Already " + progress.getStatus().toString());
                RefactoringStateManager stateManager = new RefactoringStateManager(project);
                stateManager.clearRefactoringState();
                return null;
            }

            // Create the tool window
            RefactoringToolWindow toolWindow = new RefactoringToolWindow(project, plan, progress);

            // Register and show the tool window
            toolWindow.registerAndShow();

            return toolWindow;
        } catch (Exception e) {
            LOG.error("Failed to create refactoring tool window", e);
            return null;
        }
    }

    /**
     * Checks if there's an active refactoring in progress, and closes the tool window if not.
     * This should be called whenever the refactoring state might have changed.
     */
    public static void checkAndCloseIfNoRefactoring(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                RefactoringStateManager stateManager = new RefactoringStateManager(project);
                boolean isInProgress = stateManager.isRefactoringInProgress();

                // Also check if any refactoring plan exists and progress status
                RefactoringPlan plan = stateManager.loadPlan();
                RefactoringProgress progress = stateManager.loadProgress();

                // Only close if:
                // 1. No refactoring is in progress according to the isRefactoringInProgress check OR
                // 2. There is no plan OR
                // 3. The progress is marked as COMPLETED or ABORTED
                boolean shouldClose = !isInProgress ||
                        plan == null ||
                        (progress != null &&
                                (progress.getStatus() == RefactoringStatus.COMPLETED ||
                                        progress.getStatus() == RefactoringStatus.ABORTED));

                if (shouldClose) {
                    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                    ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                    if (toolWindow != null && toolWindow.isVisible()) {
                        LOG.info("No active refactoring found. Closing tool window.");
                        toolWindow.hide(null);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error checking refactoring status", e);
            }
        });
    }

    /**
     * Updates the tool window content to reflect the current state.
     * This method should be called when the refactoring state changes.
     */
    public static void updateToolWindowContent(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow != null) {
                    RefactoringStateManager stateManager = new RefactoringStateManager(project);
                    
                    if (!stateManager.isRefactoringInProgress()) {
                        // No active session - hide the tool window
                        toolWindow.hide(null);
                        return;
                    }

                    // Load current state and update content
                    RefactoringPlan plan = stateManager.loadPlan();
                    RefactoringProgress progress = stateManager.loadProgress();
                    
                    if (plan != null && progress != null) {
                        RefactoringExecutionManager executionManager = new RefactoringExecutionManager(project);
                        RefactoringUI ui = new RefactoringUI(project, plan, progress, executionManager, stateManager);
                        
                        ContentFactory contentFactory = ContentFactory.getInstance();
                        Content content = contentFactory.createContent(
                            ui.createPanel(), 
                            "Refactoring: " + plan.getTargetClass(), 
                            false
                        );
                        
                        toolWindow.getContentManager().removeAllContents(true);
                        toolWindow.getContentManager().addContent(content);
                        
                        if (!toolWindow.isVisible()) {
                            toolWindow.show(null);
                        }
                        
                        LOG.info("Updated tool window content for: " + plan.getTargetClass());
                    }
                }
            } catch (Exception e) {
                LOG.error("Error updating tool window content", e);
            }
        });
    }

    /**
     * Registers and shows the tool window if it doesn't exist, or updates its content if it does.
     */
    private void registerAndShow() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Check if refactoring is still in progress
                RefactoringStateManager stateManager = new RefactoringStateManager(project);
                if (!stateManager.isRefactoringInProgress()) {
                    LOG.info("Refactoring is no longer in progress - not showing tool window");
                    return;
                }

                // Get or create the tool window
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow == null) {
                    LOG.warn("Tool window not found. It should be registered via plugin.xml. Attempting manual registration...");
                    // Fallback: manually register if not found (shouldn't happen with proper plugin.xml)
                    toolWindow = toolWindowManager.registerToolWindow(
                        TOOL_WINDOW_ID, 
                        true, 
                        com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM
                    );
                    toolWindow.setTitle("Refactoring for Testability");
                    toolWindow.setIcon(com.intellij.icons.AllIcons.Actions.RefactoringBulb);
                }

                // Create and set content using modern API
                RefactoringUI ui = new RefactoringUI(project, plan, progress, executionManager, stateManager);
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(
                    ui.createPanel(), 
                    "Refactoring: " + plan.getTargetClass(), 
                    false
                );

                // Add the content to the tool window
                toolWindow.getContentManager().removeAllContents(true);
                toolWindow.getContentManager().addContent(content);

                // Activate the tool window
//                toolWindow.setAvailable(true);
                toolWindow.show(null);

                LOG.info("Refactoring tool window shown for: " + plan.getTargetClass());

                // Start the execution
                boolean success = executionManager.executeStep(plan, progress);
                if (!success) {
                    Messages.showErrorDialog(project, "Failed to execute the first refactoring step", "Refactoring Error");
                }
            } catch (Exception e) {
                LOG.error("Failed to register and show tool window", e);
                Messages.showErrorDialog(project, "Failed to show refactoring tool window: " + e.getMessage(), "Refactoring Error");
            }
        });
    }
}
