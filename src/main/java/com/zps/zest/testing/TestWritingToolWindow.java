package com.zps.zest.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * Tool window for managing the test writing process.
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

    private void registerAndShow() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow == null) {
                    toolWindow = toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM);
                }

                TestWritingUI ui = new TestWritingUI(project, plan, progress, executionManager, stateManager);
                ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
                Content content = contentFactory.createContent(ui.createPanel(), "Test Writing: " + plan.getTargetClass(), false);

                toolWindow.getContentManager().removeAllContents(true);
                toolWindow.getContentManager().addContent(content);
                toolWindow.setAvailable(true);
                toolWindow.show(null);

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
