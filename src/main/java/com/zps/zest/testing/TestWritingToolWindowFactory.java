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
            // Don't hide by default anymore as it might interfere with visibility in IntelliJ 2024
            // toolWindow.hide(null); 
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
        LOG.info("Initializing Test Writing Assistant tool window for IntelliJ 2024 compatibility");
        toolWindow.setTitle("Test Writing Assistant");
        toolWindow.setStripeTitle("Test Writing");
        toolWindow.setIcon(com.intellij.icons.AllIcons.RunConfigurations.TestState.Run);
        toolWindow.setAvailable(true);
        LOG.info("Tool window availability set to true");
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // In IntelliJ 2024, we'll make the tool window always available
        // and control visibility within the window itself
        LOG.info("shouldBeAvailable called - returning true for IntelliJ 2024 compatibility");
        return true;
    }

    /**
     * Creates placeholder content when no test writing session is active.
     */
    private void createPlaceholderContent(@NotNull ToolWindow toolWindow) {
        LOG.info("Creating placeholder content for Test Writing Assistant");
        JPanel placeholderPanel = new JPanel();
        placeholderPanel.add(new JLabel("No active test writing session"));
        placeholderPanel.add(new JLabel("To start a new session, right-click on a class and select Zest > Agent: Step-by-Step Test Writing"));
        
        try {
            ContentFactory contentFactory = ContentFactory.getInstance();
            Content content = contentFactory.createContent(placeholderPanel, "Inactive", false);
            toolWindow.getContentManager().addContent(content);
            LOG.info("Placeholder content added successfully");
        } catch (Exception e) {
            LOG.error("Error creating placeholder content", e);
        }
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
