package com.zps.zest.testing;

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
 * Factory for creating the Test Writing Assistant tool window.
 * This tool window is only shown when there's an active test writing session.
 */
public class TestWritingToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(TestWritingToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LOG.info("Creating Test Writing Assistant tool window content");
        
        // Check if there's an active test writing session
        TestWritingStateManager stateManager = new TestWritingStateManager(project);
        if (!stateManager.isTestWritingInProgress()) {
            // No active session - show a placeholder
            LOG.info("No active test writing session found");
            createPlaceholderContent(toolWindow);
            toolWindow.hide(null); // Hide by default when no session is active
            return;
        }

        // Load the current test writing session
        TestPlan plan = stateManager.loadPlan();
        TestWritingProgress progress = stateManager.loadProgress();
        
        if (plan == null || progress == null) {
            LOG.warn("Failed to load test writing session data");
            createPlaceholderContent(toolWindow);
            return;
        }

        // Create the actual test writing UI
        createTestWritingContent(project, toolWindow, plan, progress, stateManager);
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        LOG.info("Initializing Test Writing Assistant tool window");
        toolWindow.setTitle("Test Writing Assistant");
        toolWindow.setStripeTitle("Test Writing");
        toolWindow.setIcon(com.intellij.icons.AllIcons.RunConfigurations.TestState.Run);
        toolWindow.setAvailable(true);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // Only show the tool window when there's an active test writing session
        TestWritingStateManager stateManager = new TestWritingStateManager(project);
        boolean available = stateManager.isTestWritingInProgress();
        LOG.debug("Test Writing tool window availability check: " + available);
        return available;
    }

    /**
     * Creates placeholder content when no test writing session is active.
     */
    private void createPlaceholderContent(@NotNull ToolWindow toolWindow) {
        JPanel placeholderPanel = new JPanel();
        placeholderPanel.add(new JLabel("No active test writing session"));
        
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(placeholderPanel, "Inactive", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Creates the actual test writing UI content.
     */
    private void createTestWritingContent(@NotNull Project project, @NotNull ToolWindow toolWindow,
                                        @NotNull TestPlan plan, @NotNull TestWritingProgress progress,
                                        @NotNull TestWritingStateManager stateManager) {
        try {
            TestExecutionManager executionManager = new TestExecutionManager(project);
            TestWritingUI ui = new TestWritingUI(project, plan, progress, executionManager, stateManager);
            
            ContentFactory contentFactory = ContentFactory.getInstance();
            Content content = contentFactory.createContent(
                ui.createPanel(), 
                "Test Writing: " + plan.getTargetClass(), 
                false
            );
            
            toolWindow.getContentManager().removeAllContents(true);
            toolWindow.getContentManager().addContent(content);
            toolWindow.setAvailable(true);
            toolWindow.show(null);
            
            LOG.info("Test Writing UI created successfully for: " + plan.getTargetClass());
            
            // Start the execution if it's not already running
            if (progress.getStatus() == TestWritingStatus.IN_PROGRESS) {
                boolean success = executionManager.executeTestCase(plan, progress);
                if (!success) {
                    LOG.warn("Failed to execute current test case");
                }
            }
        } catch (Exception e) {
            LOG.error("Error creating test writing content", e);
            createPlaceholderContent(toolWindow);
        }
    }
}
