package com.zps.zest.refactoring;

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
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static com.zps.zest.ZestNotifications.showError;

/**
 * UI components for the refactoring tool window.
 * Updated with modern APIs and better state management.
 */
public class RefactoringUI {
    private static final Logger LOG = Logger.getInstance(RefactoringUI.class);
    private static final String TOOL_WINDOW_ID = "Refactoring for Testability";

    private final Project project;
    private final RefactoringPlan plan;
    private final RefactoringProgress progress; // Keep original for reference
    private RefactoringProgress currentProgress; // Current progress that gets reloaded
    private final RefactoringExecutionManager executionManager;
    private final RefactoringStateManager stateManager;
    private final RefactoringTableModel tableModel;

    public RefactoringUI(Project project, RefactoringPlan plan, RefactoringProgress progress,
                        RefactoringExecutionManager executionManager, RefactoringStateManager stateManager) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.currentProgress = progress; // Initialize with the same progress
        this.executionManager = executionManager;
        this.stateManager = stateManager;
        this.tableModel = new RefactoringTableModel(plan);
    }

    /**
     * Reloads the progress from disk to ensure we have the latest state.
     */
    private void reloadProgress() {
        RefactoringProgress reloadedProgress = stateManager.loadProgress();
        if (reloadedProgress != null) {
            this.currentProgress = reloadedProgress;
            LOG.info("Reloaded progress: Issue " + (reloadedProgress.getCurrentIssueIndex() + 1) + 
                     ", Step " + (reloadedProgress.getCurrentStepIndex() + 1) + 
                     " (" + reloadedProgress.getCurrentStep() + ")");
        } else {
            LOG.warn("Failed to reload progress from disk - using current progress");
        }
    }

    /**
     * Creates the main panel for the tool window.
     */
    public JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Header with progress and buttons
        panel.add(createHeaderPanel(), BorderLayout.NORTH);
        
        // Current step info
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

        executeButton.addActionListener(e -> executeStep());
        skipButton.addActionListener(e -> skipStep());
        abortButton.addActionListener(e -> abortRefactoring());

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

        RefactoringIssue currentIssue = null;
        RefactoringStep currentStep = null;
        
        try {
            // Use currentProgress to get the latest state
            currentIssue = plan.getIssues().get(currentProgress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(currentProgress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.warn("Invalid issue or step index: issue=" + currentProgress.getCurrentIssueIndex() + 
                     ", step=" + currentProgress.getCurrentStepIndex());
        }

        if (currentStep != null) {
            // Step header
            JPanel stepHeaderPanel = new JPanel(new BorderLayout());
            JLabel stepTitleLabel = new JLabel("<html><b>" + currentStep.getTitle() + "</b></html>");
            stepTitleLabel.setFont(new Font(stepTitleLabel.getFont().getName(), Font.BOLD, 13));
            stepHeaderPanel.add(stepTitleLabel, BorderLayout.CENTER);

            JLabel issueLabel = new JLabel("<html><i>Issue: " + currentIssue.getTitle() + "</i></html>");
            issueLabel.setFont(new Font(issueLabel.getFont().getName(), Font.ITALIC, 12));
            issueLabel.setForeground(new Color(100, 100, 100));
            stepHeaderPanel.add(issueLabel, BorderLayout.SOUTH);

            infoPanel.add(stepHeaderPanel, BorderLayout.NORTH);

            // Description
            JTextArea descriptionArea = new JTextArea(currentStep.getDescription());
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
            infoPanel.add(new JLabel("No refactoring steps available"), BorderLayout.CENTER);
        }

        return infoPanel;
    }

    private JProgressBar createProgressBar() {
        int totalSteps = 0;
        // Use currentProgress to get the latest counts
        int completedSteps = currentProgress.getCompletedStepIds().size();
        int skippedSteps = currentProgress.getSkippedStepIds().size();

        for (RefactoringIssue issue : plan.getIssues()) {
            totalSteps += issue.getSteps().size();
        }

        JProgressBar progressBar = new JProgressBar(0, totalSteps);
        progressBar.setValue(completedSteps + skippedSteps);
        progressBar.setStringPainted(true);
        progressBar.setString(String.format("%d of %d steps completed", completedSteps, totalSteps));

        return progressBar;
    }

    private String getProgressStatusText() {
        // Use currentProgress to get the latest indices
        int issueIndex = currentProgress.getCurrentIssueIndex() + 1;
        int stepIndex = currentProgress.getCurrentStepIndex() + 1;
        int totalIssues = plan.getIssues().size();

        int totalSteps = 0;
        if (issueIndex <= totalIssues && issueIndex > 0) {
            totalSteps = plan.getIssues().get(issueIndex - 1).getSteps().size();
        }

        return String.format("Issue %d/%d, Step %d/%d", issueIndex, totalIssues, stepIndex, totalSteps);
    }

    private void executeStep() {
        LOG.info("Execute button clicked - Current step: " + currentProgress.getCurrentStep());
        
        int result = Messages.showYesNoCancelDialog(
                "Has the refactoring step been completed successfully?",
                "Refactoring Step",
                "Completed Successfully",
                "Skip this Step",
                "Stop Refactoring",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            LOG.info("User selected: Completed Successfully");
            boolean hasMoreSteps = executionManager.completeCurrentStepAndMoveToNext();
            LOG.info("completeCurrentStepAndMoveToNext returned: " + hasMoreSteps);
            
            if (hasMoreSteps) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next step execution
                    executionManager.executeStep(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next step", e);
                    showError(project, "Refactoring Error", e.getMessage());
                }
            } else {
                LOG.info("No more steps - closing tool window");
                closeToolWindow();
            }
        } else if (result == Messages.NO) {
            LOG.info("User selected: Skip this Step");
            boolean hasMoreSteps = executionManager.skipCurrentStepAndMoveToNext();
            LOG.info("skipCurrentStepAndMoveToNext returned: " + hasMoreSteps);
            
            if (hasMoreSteps) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next step execution
                    executionManager.executeStep(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next step after skip", e);
                    showError(project, "Refactoring Error", e.getMessage());
                    abortRefactoring();
                }
            } else {
                LOG.info("No more steps after skip - closing tool window");
                closeToolWindow();
            }
        } else {
            LOG.info("User selected: Stop Refactoring");
            abortRefactoring();
        }
    }

    private void skipStep() {
        LOG.info("Skip button clicked - Current step: " + currentProgress.getCurrentStep());
        
        int result = Messages.showYesNoDialog(
                "Are you sure you want to skip this refactoring step?",
                "Skip Step",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            LOG.info("User confirmed skip");
            boolean hasMoreSteps = executionManager.skipCurrentStepAndMoveToNext();
            LOG.info("skipCurrentStepAndMoveToNext returned: " + hasMoreSteps);
            
            if (hasMoreSteps) {
                // CRITICAL FIX: Reload progress before refreshing UI
                reloadProgress();
                refreshUI();
                try {
                    // Use the reloaded progress for the next step execution
                    executionManager.executeStep(plan, currentProgress);
                } catch (Exception e) {
                    LOG.error("Error executing next step after skip", e);
                    showError(project, "Refactoring Error", e.getMessage());
                    abortRefactoring();
                }
            } else {
                LOG.info("No more steps after skip - closing tool window");
                closeToolWindow();
            }
        }
    }

    private void abortRefactoring() {
        int result = Messages.showYesNoDialog(
                "Are you sure you want to abort the refactoring process?",
                "Abort Refactoring",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            executionManager.abortRefactoring();
            closeToolWindow();
        }
    }

    private void refreshUI() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                LOG.info("Refreshing UI with current progress: Issue " + (currentProgress.getCurrentIssueIndex() + 1) + 
                         ", Step " + (currentProgress.getCurrentStepIndex() + 1));
                         
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

                if (toolWindow != null) {
                    JPanel updatedPanel = createPanel();
                    ContentFactory contentFactory = ContentFactory.getInstance();
                    Content content = contentFactory.createContent(updatedPanel, "Refactoring: " + plan.getTargetClass(), false);

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

    /**
     * Table model for displaying refactoring steps.
     */
    private static class RefactoringTableModel extends AbstractTableModel {
        private final List<RefactoringTableEntry> entries;
        private final String[] columnNames = {"ID", "Step", "Issue", "Status"};

        public RefactoringTableModel(RefactoringPlan plan) {
            entries = new ArrayList<>();

            // Create entries for each step
            int issueIndex = 0;
            for (RefactoringIssue issue : plan.getIssues()) {
                int stepIndex = 0;
                for (RefactoringStep step : issue.getSteps()) {
                    entries.add(new RefactoringTableEntry(
                            step.getId(),
                            step.getTitle(),
                            issue.getTitle(),
                            step.getDescription(),
                            step.getStatus(),
                            issueIndex,
                            stepIndex
                    ));
                    stepIndex++;
                }
                issueIndex++;
            }
        }

        public List<RefactoringTableEntry> getEntries() {
            return entries;
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RefactoringTableEntry entry = entries.get(rowIndex);

            switch (columnIndex) {
                case 0:
                    return entry.getStepId();
                case 1:
                    return entry.getStepTitle();
                case 2:
                    return entry.getIssueTitle();
                case 3:
                    return entry.getStatus();
                default:
                    return null;
            }
        }
    }

    /**
     * Entry in the refactoring table.
     */
    private static class RefactoringTableEntry {
        private final int stepId;
        private final String stepTitle;
        private final String issueTitle;
        private final String stepDescription;
        private final int issueIndex;
        private final int stepIndex;
        private RefactoringStepStatus status;

        public RefactoringTableEntry(int stepId, String stepTitle, String issueTitle, String stepDescription,
                                     RefactoringStepStatus status, int issueIndex, int stepIndex) {
            this.stepId = stepId;
            this.stepTitle = stepTitle;
            this.issueTitle = issueTitle;
            this.stepDescription = stepDescription;
            this.status = status;
            this.issueIndex = issueIndex;
            this.stepIndex = stepIndex;
        }

        // Getters
        public int getStepId() { return stepId; }
        public String getStepTitle() { return stepTitle; }
        public String getIssueTitle() { return issueTitle; }
        public String getStepDescription() { return stepDescription; }
        public RefactoringStepStatus getStatus() { return status; }
        public void setStatus(RefactoringStepStatus status) { this.status = status; }
        public int getIssueIndex() { return issueIndex; }
        public int getStepIndex() { return stepIndex; }
    }
}
