package com.zps.zest.refactoring;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Factory for creating the Refactoring for Testability tool window.
 * This tool window is only shown when there's an active refactoring session.
 */
public class RefactoringToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(RefactoringToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LOG.info("Creating Refactoring for Testability tool window content");
        
        // Check if there's an active refactoring session
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        if (!stateManager.isRefactoringInProgress()) {
            // No active session - show a placeholder
            LOG.info("No active refactoring session found");
            createPlaceholderContent(toolWindow);
            toolWindow.hide(null); // Hide by default when no session is active
            return;
        }

        // Load the current refactoring session
        RefactoringPlan plan = stateManager.loadPlan();
        RefactoringProgress progress = stateManager.loadProgress();
        
        if (plan == null || progress == null) {
            LOG.warn("Failed to load refactoring session data");
            createPlaceholderContent(toolWindow);
            return;
        }

        // Validate the plan has issues
        if (plan.getIssues() == null || plan.getIssues().isEmpty()) {
            LOG.info("No refactoring issues found in plan");
            createPlaceholderContent(toolWindow);
            toolWindow.hide(null);
            return;
        }

        // Create the actual refactoring UI
        createRefactoringContent(project, toolWindow, plan, progress, stateManager);
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        LOG.info("Initializing Refactoring for Testability tool window");
        toolWindow.setTitle("Refactoring for Testability");
        toolWindow.setStripeTitle("Refactoring");
        toolWindow.setIcon(com.intellij.icons.AllIcons.Actions.RefactoringBulb);
//        toolWindow.setAvailable(true);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // Only show the tool window when there's an active refactoring session
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        boolean available = stateManager.isRefactoringInProgress();
        LOG.debug("Refactoring tool window availability check: " + available);
        return available;
    }

    /**
     * Creates placeholder content when no refactoring session is active.
     */
    private void createPlaceholderContent(@NotNull ToolWindow toolWindow) {
        JPanel placeholderPanel = new JPanel();
        placeholderPanel.add(new JLabel("No active refactoring session"));
        
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(placeholderPanel, "Inactive", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Creates the actual refactoring UI content.
     */
    private void createRefactoringContent(@NotNull Project project, @NotNull ToolWindow toolWindow,
                                        @NotNull RefactoringPlan plan, @NotNull RefactoringProgress progress,
                                        @NotNull RefactoringStateManager stateManager) {
        try {
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
//            toolWindow.setAvailable(true);
            toolWindow.show(null);
            
            LOG.info("Refactoring UI created successfully for: " + plan.getTargetClass());
            
            // Start the execution if it's not already running
            if (progress.getStatus() == RefactoringStatus.IN_PROGRESS) {
                boolean success = executionManager.executeStep(plan, progress);
                if (!success) {
                    LOG.warn("Failed to execute current refactoring step");
                }
            }
        } catch (Exception e) {
            LOG.error("Error creating refactoring content", e);
            createPlaceholderContent(toolWindow);
        }
    }
}
