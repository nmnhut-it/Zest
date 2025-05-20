package com.zps.zest.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import javax.swing.*;
import java.awt.*;

import static com.zps.zest.ZestNotifications.showError;

/**
 * UI components for the test writing tool window.
 */
public class TestWritingUI {
    private static final Logger LOG = Logger.getInstance(TestWritingUI.class);
    private static final String TOOL_WINDOW_ID = "Test Writing Assistant";

    private final Project project;
    private final TestPlan plan;
    private final TestWritingProgress progress;
    private final TestExecutionManager executionManager;
    private final TestWritingStateManager stateManager;

    public TestWritingUI(Project project, TestPlan plan, TestWritingProgress progress, 
                        TestExecutionManager executionManager, TestWritingStateManager stateManager) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.executionManager = executionManager;
        this.stateManager = stateManager;
    }

    public JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Header with progress and buttons
        panel.add(createHeaderPanel(), BorderLayout.NORTH);
        
        // Current test case info
        panel.add(createInfoPanel(), BorderLayout.CENTER);
        
        // Progress bar
        panel.add(createProgressBar(), BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());

        JLabel statusLabel = new JLabel(getProgressStatusText());
        statusLabel.setFont(new Font(statusLabel.getFont().getName(), Font.BOLD, 12));
        headerPanel.add(statusLabel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        
        JButton executeButton = new JButton("Complete");
        JButton skipButton = new JButton("Skip");
        JButton abortButton = new JButton("Abort");

        Dimension buttonSize = new Dimension(80, 25);
        executeButton.setPreferredSize(buttonSize);
        skipButton.setPreferredSize(buttonSize);
        abortButton.setPreferredSize(buttonSize);

        executeButton.addActionListener(e -> executeTestCase());
        skipButton.addActionListener(e -> skipTestCase());
        abortButton.addActionListener(e -> abortTestWriting());

        buttonPanel.add(executeButton);
        buttonPanel.add(skipButton);
        buttonPanel.add(abortButton);

        headerPanel.add(buttonPanel, BorderLayout.EAST);
        return headerPanel;
    }

    private JPanel createInfoPanel() {
        JPanel infoPanel = new JPanel(new BorderLayout(5, 5));
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 0, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                        BorderFactory.createEmptyBorder(8, 8, 8, 8)
                )
        ));

        TestScenario currentScenario = null;
        TestCase currentTestCase = null;
        
        try {
            currentScenario = plan.getScenarios().get(progress.getCurrentScenarioIndex());
            currentTestCase = currentScenario.getTestCases().get(progress.getCurrentTestCaseIndex());
        } catch (IndexOutOfBoundsException e) {
            // Handle case where there are no test cases
        }

        if (currentTestCase != null) {
            // Test case header
            JPanel testCaseHeaderPanel = new JPanel(new BorderLayout());
            JLabel testCaseTitleLabel = new JLabel("<html><b>" + currentTestCase.getTitle() + "</b></html>");
            testCaseTitleLabel.setFont(new Font(testCaseTitleLabel.getFont().getName(), Font.BOLD, 13));
            testCaseHeaderPanel.add(testCaseTitleLabel, BorderLayout.CENTER);

            JLabel scenarioLabel = new JLabel("<html><i>Scenario: " + currentScenario.getTitle() + "</i></html>");
            scenarioLabel.setFont(new Font(scenarioLabel.getFont().getName(), Font.ITALIC, 12));
            scenarioLabel.setForeground(new Color(100, 100, 100));
            testCaseHeaderPanel.add(scenarioLabel, BorderLayout.SOUTH);

            infoPanel.add(testCaseHeaderPanel, BorderLayout.NORTH);

            // Description
            JTextArea descriptionArea = new JTextArea(currentTestCase.getDescription());
            descriptionArea.setEditable(false);
            descriptionArea.setLineWrap(true);
            descriptionArea.setWrapStyleWord(true);
            descriptionArea.setFont(new Font("Dialog", Font.PLAIN, 12));
            descriptionArea.setMargin(new Insets(5, 5, 5, 5));
            descriptionArea.setBackground(new Color(250, 250, 250));

            JScrollPane scrollPane = new JBScrollPane(descriptionArea);
            scrollPane.setPreferredSize(new Dimension(300, 75));
            infoPanel.add(scrollPane, BorderLayout.CENTER);
        } else {
            infoPanel.add(new JLabel("No test cases available"), BorderLayout.CENTER);
        }

        return infoPanel;
    }

    private JProgressBar createProgressBar() {
        int totalTestCases = 0;
        int completedTestCases = progress.getCompletedTestCaseIds().size();
        int skippedTestCases = progress.getSkippedTestCaseIds().size();

        for (TestScenario scenario : plan.getScenarios()) {
            totalTestCases += scenario.getTestCases().size();
        }

        JProgressBar progressBar = new JProgressBar(0, totalTestCases);
        progressBar.setValue(completedTestCases + skippedTestCases);
        progressBar.setStringPainted(true);
        progressBar.setString(String.format("%d of %d test cases completed", completedTestCases, totalTestCases));

        return progressBar;
    }

    private String getProgressStatusText() {
        int scenarioIndex = progress.getCurrentScenarioIndex() + 1;
        int testCaseIndex = progress.getCurrentTestCaseIndex() + 1;
        int totalScenarios = plan.getScenarios().size();

        int totalTestCases = 0;
        if (scenarioIndex <= totalScenarios && scenarioIndex > 0) {
            totalTestCases = plan.getScenarios().get(scenarioIndex - 1).getTestCases().size();
        }

        return String.format("Scenario %d/%d, Test Case %d/%d", scenarioIndex, totalScenarios, testCaseIndex, totalTestCases);
    }

    private void executeTestCase() {
        int result = Messages.showYesNoCancelDialog(
                "Has the test case been implemented successfully?",
                "Test Case Implementation",
                "Completed Successfully",
                "Skip this Test Case",
                "Stop Test Writing",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            boolean hasMoreTestCases = executionManager.completeCurrentTestCaseAndMoveToNext();
            if (hasMoreTestCases) {
                refreshUI();
                try {
                    executionManager.executeTestCase(plan, progress);
                } catch (Exception e) {
                    showError(project, "Test Writing Error", e.getMessage());
                }
            } else {
                closeToolWindow();
            }
        } else if (result == Messages.NO) {
            boolean hasMoreTestCases = executionManager.skipCurrentTestCaseAndMoveToNext();
            if (hasMoreTestCases) {
                refreshUI();
                try {
                    executionManager.executeTestCase(plan, progress);
                } catch (Exception e) {
                    showError(project, "Test Writing Error", e.getMessage());
                    abortTestWriting();
                }
            } else {
                closeToolWindow();
            }
        } else {
            abortTestWriting();
        }
    }

    private void skipTestCase() {
        int result = Messages.showYesNoDialog(
                "Are you sure you want to skip this test case?",
                "Skip Test Case",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            boolean hasMoreTestCases = executionManager.skipCurrentTestCaseAndMoveToNext();
            if (hasMoreTestCases) {
                refreshUI();
                try {
                    executionManager.executeTestCase(plan, progress);
                } catch (Exception e) {
                    showError(project, "Test Writing Error", e.getMessage());
                    abortTestWriting();
                }
            } else {
                closeToolWindow();
            }
        }
    }

    private void abortTestWriting() {
        int result = Messages.showYesNoDialog(
                "Are you sure you want to abort the test writing process?",
                "Abort Test Writing",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            executionManager.abortTestWriting();
            closeToolWindow();
        }
    }

    private void refreshUI() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow != null) {
                    JPanel updatedPanel = createPanel();
                    ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
                    Content content = contentFactory.createContent(updatedPanel, "Test Writing: " + plan.getTargetClass(), false);

                    toolWindow.getContentManager().removeAllContents(true);
                    toolWindow.getContentManager().addContent(content);
                }
            } catch (Exception e) {
                LOG.error("Error updating tool window content", e);
            }
        });
    }

    private void closeToolWindow() {
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

            if (toolWindow != null) {
                toolWindow.hide(null);
            }
        });
    }
}
