package com.zps.zest.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * Tool window for managing the test writing process.
 * This class provides programmatic control over the test writing tool window.
 */
public class TestWritingToolWindow {
    private static final Logger LOG = Logger.getInstance(TestWritingToolWindow.class);
    private static final String TOOL_WINDOW_ID = "Test Writing Assistant";

    private final Project project;
    private final TestPlan plan;
    private final TestWritingProgress progress;
    private final TestExecutionManager executionManager;
    private final TestWritingStateManager stateManager;

    private TestWritingToolWindow(Project project, TestPlan plan, TestWritingProgress progress) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.executionManager = new TestExecutionManager(project);
        this.stateManager = new TestWritingStateManager(project);
    }

    /**
     * Shows the test writing tool window with the specified plan and progress.
     * This method creates and displays the tool window programmatically.
     */
    public static TestWritingToolWindow showToolWindow(Project project, TestPlan plan, TestWritingProgress progress) {
        try {
            if (plan == null || plan.getScenarios().isEmpty() || progress == null) {
                LOG.error("Cannot show tool window: Invalid plan or progress");
                return null;
            }

            if (progress.getStatus() == TestWritingStatus.COMPLETED || progress.getStatus() == TestWritingStatus.ABORTED) {
                LOG.info("Not showing tool window - test writing was " + progress.getStatus().toString().toLowerCase());
                return null;
            }

            TestWritingToolWindow toolWindow = new TestWritingToolWindow(project, plan, progress);
            toolWindow.registerAndShow();
            return toolWindow;
        } catch (Exception e) {
            LOG.error("Failed to create test writing tool window", e);
            return null;
        }
    }

    /**
     * Checks if there's no active test writing and closes the tool window if needed.
     */
    public static void checkAndCloseIfNoTestWriting(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                TestWritingStateManager stateManager = new TestWritingStateManager(project);
                boolean isInProgress = stateManager.isTestWritingInProgress();

                if (!isInProgress) {
                    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                    ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                    if (toolWindow != null && toolWindow.isVisible()) {
                        LOG.info("No active test writing found. Closing tool window.");
                        toolWindow.hide(null);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error checking test writing status", e);
            }
        });
    }

    /**
     * Updates the tool window content to reflect the current state.
     * This method should be called when the test writing state changes.
     */
    public static void updateToolWindowContent(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow != null) {
                    TestWritingStateManager stateManager = new TestWritingStateManager(project);
                    
                    if (!stateManager.isTestWritingInProgress()) {
                        // No active session - hide the tool window
                        toolWindow.hide(null);
                        return;
                    }

                    // Load current state and update content
                    TestPlan plan = stateManager.loadPlan();
                    TestWritingProgress progress = stateManager.loadProgress();
                    
                    if (plan != null && progress != null) {
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
                    toolWindow.setTitle("Test Writing Assistant");
//                    toolWindow.setIcon(com.intellij.icons.AllIcons.RunConfigurations.TestState.Run);
                }

                // Create and set content
                TestWritingUI ui = new TestWritingUI(project, plan, progress, executionManager, stateManager);
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(
                    ui.createPanel(), 
                    "Test Writing: " + plan.getTargetClass(), 
                    false
                );

                toolWindow.getContentManager().removeAllContents(true);
                toolWindow.getContentManager().addContent(content);
//                toolWindow.setAvailable(true);
                toolWindow.show(null);

                LOG.info("Test writing tool window shown for: " + plan.getTargetClass());

                // Start the execution
                boolean success = executionManager.executeTestCase(plan, progress);
                if (!success) {
                    Messages.showErrorDialog(project, "Failed to execute the first test case", "Test Writing Error");
                }
            } catch (Exception e) {
                LOG.error("Failed to register and show tool window", e);
            }
        });
    }
}
