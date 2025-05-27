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
    private final TestWritingProgress progress; // Keep original for reference
    private TestWritingProgress currentProgress; // Current progress that gets reloaded
    private final TestExecutionManager executionManager;
    private final TestWritingStateManager stateManager;

    public TestWritingUI(Project project, TestPlan plan, TestWritingProgress progress, 
                        TestExecutionManager executionManager, TestWritingStateManager stateManager) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.currentProgress = progress; // Initialize with the same progress
        this.executionManager = executionManager;
        this.stateManager = stateManager;
    }

    /**
     * Reloads the progress from disk to ensure we have the latest state.
     */
    private void reloadProgress() {
        TestWritingProgress reloadedProgress = stateManager.loadProgress();
        if (reloadedProgress != null) {
            this.currentProgress = reloadedProgress;
            LOG.info("Reloaded progress: Scenario " + (reloadedProgress.getCurrentScenarioIndex() + 1) + 
                     ", Test Case " + (reloadedProgress.getCurrentTestCaseIndex() + 1) + 
                     " (" + reloadedProgress.getCurrentTest() + ")");
            
            // Validate the indices to ensure they're within bounds
            validateProgressIndices();
        } else {
            LOG.warn("Failed to reload progress from disk - using current progress");
        }
    }

    /**
     * Validates that the current progress indices are within valid bounds.
     */
    private void validateProgressIndices() {
        int scenarioIndex = currentProgress.getCurrentScenarioIndex();
        int testCaseIndex = currentProgress.getCurrentTestCaseIndex();
        
        // Check if scenario index is valid
        if (scenarioIndex >= plan.getScenarios().size()) {
            LOG.warn("Invalid scenario index: " + scenarioIndex + ", total scenarios: " + plan.getScenarios().size());
            return;
        }
        
        // Check if test case index is valid for the current scenario
        TestScenario currentScenario = plan.getScenarios().get(scenarioIndex);
        if (testCaseIndex >= currentScenario.getTestCases().size()) {
            LOG.warn("Invalid test case index: " + testCaseIndex + " for scenario " + scenarioIndex + 
                     ", total test cases in scenario: " + currentScenario.getTestCases().size());
        }
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
            int scenarioIndex = currentProgress.getCurrentScenarioIndex();
            int testCaseIndex = currentProgress.getCurrentTestCaseIndex();
            
            // Validate indices before accessing
            if (scenarioIndex >= 0 && scenarioIndex < plan.getScenarios().size()) {
                currentScenario = plan.getScenarios().get(scenarioIndex);
                
                if (testCaseIndex >= 0 && testCaseIndex < currentScenario.getTestCases().size()) {
                    currentTestCase = currentScenario.getTestCases().get(testCaseIndex);
                } else {
                    LOG.warn("Invalid test case index: " + testCaseIndex + 
                             " for scenario with " + currentScenario.getTestCases().size() + " test cases");
                }
            } else {
                LOG.warn("Invalid scenario index: " + scenarioIndex + 
                         " with " + plan.getScenarios().size() + " total scenarios");
            }
        } catch (Exception e) {
            LOG.error("Error accessing current test case", e);
        }

        if (currentTestCase != null && currentScenario != null) {
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
            // Show appropriate message based on the state
            String message;
            if (currentProgress.getCurrentScenarioIndex() >= plan.getScenarios().size()) {
                message = "All scenarios completed";
            } else {
                message = "No test cases available or invalid state";
            }
            infoPanel.add(new JLabel(message), BorderLayout.CENTER);
        }

        return infoPanel;
    }

    private JProgressBar createProgressBar() {
        int totalTestCases = 0;
        int completedTestCases = currentProgress.getCompletedTestCaseIds().size();
        int skippedTestCases = currentProgress.getSkippedTestCaseIds().size();

        // Calculate total test cases across all scenarios
        for (TestScenario scenario : plan.getScenarios()) {
            totalTestCases += scenario.getTestCases().size();
        }

        JProgressBar progressBar = new JProgressBar(0, totalTestCases);
        int processedTestCases = completedTestCases + skippedTestCases;
        progressBar.setValue(processedTestCases);
        progressBar.setStringPainted(true);
        
        // More detailed progress string
        progressBar.setString(String.format("%d completed, %d skipped, %d total (%d remaining)", 
                                           completedTestCases, skippedTestCases, totalTestCases,
                                           totalTestCases - processedTestCases));

        return progressBar;
    }

    private String getProgressStatusText() {
        try {
            // Use currentProgress to get the latest indices
            int scenarioIndex = currentProgress.getCurrentScenarioIndex();
            int testCaseIndex = currentProgress.getCurrentTestCaseIndex();
            int totalScenarios = plan.getScenarios().size();

            // Validate indices before proceeding
            if (scenarioIndex >= totalScenarios) {
                LOG.warn("Scenario index out of bounds: " + scenarioIndex + "/" + totalScenarios);
                return "Test Writing Complete";
            }

            TestScenario currentScenario = plan.getScenarios().get(scenarioIndex);
            int totalTestCasesInScenario = currentScenario.getTestCases().size();

            // Check if we've completed all test cases in current scenario
            if (testCaseIndex >= totalTestCasesInScenario) {
                // This scenario is complete, check if there are more scenarios
                if (scenarioIndex + 1 >= totalScenarios) {
                    return "All Scenarios Complete";
                } else {
                    // This indicates a state inconsistency - we should have moved to next scenario
                    LOG.warn("Test case index exceeds scenario size: testCase=" + testCaseIndex + 
                             ", maxInScenario=" + totalTestCasesInScenario + ", scenario=" + scenarioIndex);
                    return String.format("Scenario %d/%d Complete", scenarioIndex + 1, totalScenarios);
                }
            }

            // Convert to 1-based for display
            int displayScenarioIndex = scenarioIndex + 1;
            int displayTestCaseIndex = testCaseIndex + 1;

            return String.format("Scenario %d/%d, Test Case %d/%d", 
                               displayScenarioIndex, totalScenarios, 
                               displayTestCaseIndex, totalTestCasesInScenario);
        } catch (Exception e) {
            LOG.error("Error calculating progress status", e);
            return "Progress Status Error";
        }
    }

    private void executeTestCase() {
        LOG.info("Execute button clicked - Current test: " + currentProgress.getCurrentTest());
        
        int result = Messages.showYesNoCancelDialog(
                "Has the test case been implemented successfully?",
                "Test Case Implementation",
                "Completed Successfully",
                "Skip this Test Case",
                "Stop Test Writing",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            LOG.info("User selected: Completed Successfully");
            boolean hasMoreTestCases = executionManager.completeCurrentTestCaseAndMoveToNext();
            LOG.info("completeCurrentTestCaseAndMoveToNext returned: " + hasMoreTestCases);
            
            if (hasMoreTestCases) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next test case execution
                    executionManager.executeTestCase(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next test case", e);
                    showError(project, "Test Writing Error", e.getMessage());
                }
            } else {
                LOG.info("No more test cases - closing tool window");
                closeToolWindow();
            }
        } else if (result == Messages.NO) {
            LOG.info("User selected: Skip this Test Case");
            boolean hasMoreTestCases = executionManager.skipCurrentTestCaseAndMoveToNext();
            LOG.info("skipCurrentTestCaseAndMoveToNext returned: " + hasMoreTestCases);
            
            if (hasMoreTestCases) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next test case execution
                    executionManager.executeTestCase(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next test case after skip", e);
                    showError(project, "Test Writing Error", e.getMessage());
                    abortTestWriting();
                }
            } else {
                LOG.info("No more test cases after skip - closing tool window");
                closeToolWindow();
            }
        } else {
            LOG.info("User selected: Stop Test Writing");
            abortTestWriting();
        }
    }

    private void skipTestCase() {
        LOG.info("Skip button clicked - Current test: " + currentProgress.getCurrentTest());
        
        int result = Messages.showYesNoDialog(
                "Are you sure you want to skip this test case?",
                "Skip Test Case",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            LOG.info("User confirmed skip");
            boolean hasMoreTestCases = executionManager.skipCurrentTestCaseAndMoveToNext();
            LOG.info("skipCurrentTestCaseAndMoveToNext returned: " + hasMoreTestCases);
            
            if (hasMoreTestCases) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next test case execution
                    executionManager.executeTestCase(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next test case after skip", e);
                    showError(project, "Test Writing Error", e.getMessage());
                    abortTestWriting();
                }
            } else {
                LOG.info("No more test cases after skip - closing tool window");
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
                LOG.info("Refreshing UI with current progress: Scenario " + (currentProgress.getCurrentScenarioIndex() + 1) + 
                         ", Test Case " + (currentProgress.getCurrentTestCaseIndex() + 1));
                         
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow != null) {
                    JPanel updatedPanel = createPanel();
                    ContentFactory contentFactory = ContentFactory.getInstance();
                    Content content = contentFactory.createContent(updatedPanel, "Test Writing: " + plan.getTargetClass(), false);

                    toolWindow.getContentManager().removeAllContents(true);
                    toolWindow.getContentManager().addContent(content);
                    LOG.info("UI refreshed successfully");
                } else {
                    LOG.warn("Tool window not found during refresh");
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
