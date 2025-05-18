package com.zps.zest.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool window for managing the refactoring process.
 * Shows the refactoring plan and allows the user to control execution.
 */
public class RefactoringToolWindow {
    private static final Logger LOG = Logger.getInstance(RefactoringToolWindow.class);
    private static final String TOOL_WINDOW_ID = "Refactoring for Testability";
    
    private final Project project;
    private final RefactoringPlan plan;
    private final RefactoringProgress progress;
    private final RefactoringExecutionManager executionManager;
    private final RefactoringStateManager stateManager;
    private final RefactoringTableModel tableModel;
    private JTable table;
    private JTextArea descriptionArea;
    private JButton executeButton;
    private JButton skipButton;
    private JButton abortButton;
    
    /**
     * Creates and shows a new tool window for managing refactoring.
     * 
     * @param project The project
     * @param plan The refactoring plan
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
     * Creates a new refactoring tool window.
     */
    private RefactoringToolWindow(Project project, RefactoringPlan plan, RefactoringProgress progress) {
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.executionManager = new RefactoringExecutionManager(project);
        this.stateManager = new RefactoringStateManager(project);
        this.tableModel = new RefactoringTableModel(plan);
    }
    
    /**
     * Registers and shows the tool window.
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
                    // Check if the ID is registered but not available
                    boolean hasToolWindowId = false;
                    for (String id : toolWindowManager.getToolWindowIds()) {
                        if (id.equals(TOOL_WINDOW_ID)) {
                            hasToolWindowId = true;
                            break;
                        }
                    }
                    
                    if (hasToolWindowId) {
                        LOG.warn("Tool window ID exists but getToolWindow returned null - potential ID conflict");
                    }
                    
                    // Register the tool window
                    toolWindow = toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM);
                }
                
                // Create the content
                ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
                Content content = contentFactory.createContent(createPanel(), "Refactoring: " + plan.getTargetClass(), false);
                
                // Add the content to the tool window
                toolWindow.getContentManager().removeAllContents(true);
                toolWindow.getContentManager().addContent(content);
                
                // Activate the tool window
                toolWindow.setAvailable(true);
                toolWindow.show(null);
                
                // Sync with disk state before updating the UI
                syncStateFromDisk();
                
                // Update the status display
                updateStepStatusesFromProgress();
                
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
    
    /**
     * Creates the main panel for the tool window.
     */
    private JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        // Create a compact header with step info and buttons
        JPanel headerPanel = new JPanel(new BorderLayout());
        
        // Add a label showing current progress
        JLabel statusLabel = new JLabel(getProgressStatusText());
        statusLabel.setFont(new Font(statusLabel.getFont().getName(), Font.BOLD, 12));
        headerPanel.add(statusLabel, BorderLayout.WEST);
        
        // Create compact button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        
        executeButton = new JButton("Complete");
        skipButton = new JButton("Skip");
        abortButton = new JButton("Abort");
        
        // Make buttons smaller
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
        panel.add(headerPanel, BorderLayout.NORTH);
        
        // Create a compact info panel for the current step
        JPanel infoPanel = new JPanel(new BorderLayout(5, 5));
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 0, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                        BorderFactory.createEmptyBorder(8, 8, 8, 8)
                )
        ));
        
        // Get current step information
        RefactoringIssue currentIssue = null;
        RefactoringStep currentStep = null;
        try {
            currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
            currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
        } catch (IndexOutOfBoundsException e) {
            // Handle case where there are no steps
        }
        
        if (currentStep != null) {
            // Create header with step title
            JPanel stepHeaderPanel = new JPanel(new BorderLayout());
            JLabel stepTitleLabel = new JLabel("<html><b>" + currentStep.getTitle() + "</b></html>");
            stepTitleLabel.setFont(new Font(stepTitleLabel.getFont().getName(), Font.BOLD, 13));
            stepHeaderPanel.add(stepTitleLabel, BorderLayout.CENTER);
            
            // Add issue label
            JLabel issueLabel = new JLabel("<html><i>Issue: " + currentIssue.getTitle() + "</i></html>");
            issueLabel.setFont(new Font(issueLabel.getFont().getName(), Font.ITALIC, 12));
            issueLabel.setForeground(new Color(100, 100, 100));
            stepHeaderPanel.add(issueLabel, BorderLayout.SOUTH);
            
            infoPanel.add(stepHeaderPanel, BorderLayout.NORTH);
            
            // Add scrollable description
            descriptionArea = new JTextArea(currentStep.getDescription());
            descriptionArea.setEditable(false);
            descriptionArea.setLineWrap(true);
            descriptionArea.setWrapStyleWord(true);
            descriptionArea.setFont(new Font("Dialog", Font.PLAIN, 12));
            descriptionArea.setMargin(new Insets(5, 5, 5, 5));
            descriptionArea.setBackground(new Color(250, 250, 250));
            
            // Limit height to 3-4 visible lines
            JScrollPane scrollPane = new JBScrollPane(descriptionArea);
            scrollPane.setPreferredSize(new Dimension(300, 75));
            infoPanel.add(scrollPane, BorderLayout.CENTER);
        } else {
            infoPanel.add(new JLabel("No refactoring steps available"), BorderLayout.CENTER);
        }
        
        panel.add(infoPanel, BorderLayout.CENTER);
        
        // Add a small progress bar at the bottom
        int totalSteps = 0;
        int completedSteps = progress.getCompletedStepIds().size();
        int skippedSteps = progress.getSkippedStepIds().size();
        
        for (RefactoringIssue issue : plan.getIssues()) {
            totalSteps += issue.getSteps().size();
        }
        
        JProgressBar progressBar = new JProgressBar(0, totalSteps);
        progressBar.setValue(completedSteps + skippedSteps);
        progressBar.setStringPainted(true);
        progressBar.setString(String.format("%d of %d steps completed", completedSteps, totalSteps));
        
        panel.add(progressBar, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * Gets the current progress status text.
     */
    private String getProgressStatusText() {
        int issueIndex = progress.getCurrentIssueIndex() + 1;
        int stepIndex = progress.getCurrentStepIndex() + 1;
        int totalIssues = plan.getIssues().size();
        
        int totalSteps = 0;
        if (issueIndex <= totalIssues && issueIndex > 0) {
            totalSteps = plan.getIssues().get(issueIndex - 1).getSteps().size();
        }
        
        return String.format("Issue %d/%d, Step %d/%d", issueIndex, totalIssues, stepIndex, totalSteps);
    }
    
    /**
     * Finds the row index of the current step.
     */
    private int findCurrentStepRow() {
        List<RefactoringTableEntry> entries = tableModel.getEntries();
        
        for (int i = 0; i < entries.size(); i++) {
            RefactoringTableEntry entry = entries.get(i);
            if (entry.getIssueIndex() == progress.getCurrentIssueIndex() &&
                    entry.getStepIndex() == progress.getCurrentStepIndex()) {
                return i;
            }
        }
        
        return -1;
    }
    
    /**
     * Executes the current step.
     */
    private void executeStep() {
        // Wait for user to confirm step completion
        int result = Messages.showYesNoCancelDialog(
                 "Has the refactoring step been completed successfully?",
                "Refactoring Step",
                "Completed Successfully",
                "Skip this Step",
                "Stop Refactoring",
                Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            // Step completed successfully
            boolean hasMoreSteps = executionManager.completeCurrentStepAndMoveToNext();
            if (hasMoreSteps) {
                // Sync with disk state and refresh UI
                syncStateFromDisk();
                updateStepStatusesFromProgress();
                refreshUI();
                
                // Start the next step
                executionManager.executeStep(plan, progress);
            } else {
                // No more steps, close the tool window
                closeToolWindow();
            }
        } else if (result == Messages.NO) {
            // Skip this step
            boolean hasMoreSteps = executionManager.skipCurrentStepAndMoveToNext();
            if (hasMoreSteps) {
                // Sync with disk state and refresh UI
                syncStateFromDisk();
                updateStepStatusesFromProgress();
                refreshUI();
                
                // Start the next step
                executionManager.executeStep(plan, progress);
            } else {
                // No more steps, close the tool window
                closeToolWindow();
            }
        } else {
            // Cancel refactoring
            abortRefactoring();
        }
    }
    
    /**
     * Refreshes the UI with the latest progress information.
     */
    private void refreshUI() {
        try {
            // Sync state from disk first
            syncStateFromDisk();
            
            // Then update the UI status
            updateStepStatusesFromProgress();
            
            // Re-create the panel with updated information
            JPanel updatedPanel = createPanel();
            
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
            
            if (toolWindow != null) {
                ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
                Content content = contentFactory.createContent(updatedPanel, "Refactoring: " + plan.getTargetClass(), false);
                
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        toolWindow.getContentManager().removeAllContents(true);
                        toolWindow.getContentManager().addContent(content);
                    } catch (Exception e) {
                        LOG.error("Error updating tool window content", e);
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("Failed to refresh UI", e);
        }
    }
    
    /**
     * Updates the UI table entries to reflect the current progress status.
     */
    private void updateStepStatusesFromProgress() {
        // Get all step entries
        List<RefactoringTableEntry> entries = tableModel.getEntries();
        
        // Update status based on progress
        for (RefactoringTableEntry entry : entries) {
            int stepId = entry.getStepId();
            
            if (progress.getCompletedStepIds().contains(stepId)) {
                entry.setStatus(RefactoringStepStatus.COMPLETED);
            } else if (progress.getSkippedStepIds().contains(stepId)) {
                entry.setStatus(RefactoringStepStatus.SKIPPED);
            } else if (progress.getFailedStepIds().contains(stepId)) {
                entry.setStatus(RefactoringStepStatus.FAILED);
            } else if (entry.getIssueIndex() == progress.getCurrentIssueIndex() && 
                       entry.getStepIndex() == progress.getCurrentStepIndex()) {
                entry.setStatus(RefactoringStepStatus.IN_PROGRESS);
            } else {
                entry.setStatus(RefactoringStepStatus.PENDING);
            }
        }
        
        // Also update the steps in the plan to keep them in sync
        int issueIndex = 0;
        for (RefactoringIssue issue : plan.getIssues()) {
            int stepIndex = 0;
            for (RefactoringStep step : issue.getSteps()) {
                if (progress.getCompletedStepIds().contains(step.getId())) {
                    step.setStatus(RefactoringStepStatus.COMPLETED);
                } else if (progress.getSkippedStepIds().contains(step.getId())) {
                    step.setStatus(RefactoringStepStatus.SKIPPED);
                } else if (progress.getFailedStepIds().contains(step.getId())) {
                    step.setStatus(RefactoringStepStatus.FAILED);
                } else if (issueIndex == progress.getCurrentIssueIndex() && 
                           stepIndex == progress.getCurrentStepIndex()) {
                    step.setStatus(RefactoringStepStatus.IN_PROGRESS);
                } else {
                    step.setStatus(RefactoringStepStatus.PENDING);
                }
                stepIndex++;
            }
            issueIndex++;
        }
    }
    
    /**
     * Skips the current step.
     */
    private void skipStep() {
        // Confirm skipping
        int result = Messages.showYesNoDialog(
                 "Are you sure you want to skip this refactoring step?",
                "Skip Step",
                Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            boolean hasMoreSteps = executionManager.skipCurrentStepAndMoveToNext();
            if (hasMoreSteps) {
                // Sync with disk state and refresh UI
                syncStateFromDisk();
                updateStepStatusesFromProgress();
                refreshUI();
                
                // Start the next step
                executionManager.executeStep(plan, progress);
            } else {
                // No more steps, close the tool window
                closeToolWindow();
            }
        }
    }
    
    /**
     * Aborts the refactoring process.
     */
    private void abortRefactoring() {
        // Confirm cancellation
        int result = Messages.showYesNoDialog(
                 "Are you sure you want to abort the refactoring process?",
                "Abort Refactoring",
                Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            // Update the status of the current step to skipped
            if (progress.getCurrentIssueIndex() < plan.getIssues().size() && 
                progress.getCurrentStepIndex() < plan.getIssues().get(progress.getCurrentIssueIndex()).getSteps().size()) {
                
                RefactoringIssue currentIssue = plan.getIssues().get(progress.getCurrentIssueIndex());
                RefactoringStep currentStep = currentIssue.getSteps().get(progress.getCurrentStepIndex());
                currentStep.setStatus(RefactoringStepStatus.SKIPPED);
                progress.markStepSkipped(currentStep.getId());
            }
            
            // Mark all remaining steps as skipped
            for (int i = progress.getCurrentIssueIndex(); i < plan.getIssues().size(); i++) {
                RefactoringIssue issue = plan.getIssues().get(i);
                
                int startStepIndex = (i == progress.getCurrentIssueIndex()) ? progress.getCurrentStepIndex() + 1 : 0;
                
                for (int j = startStepIndex; j < issue.getSteps().size(); j++) {
                    RefactoringStep step = issue.getSteps().get(j);
                    step.setStatus(RefactoringStepStatus.SKIPPED);
                    progress.markStepSkipped(step.getId());
                }
            }
            
            // Mark the refactoring as aborted in the progress
            progress.markAborted();
            
            // Save the final state before clearing
            stateManager.saveProgress(progress);
            
            // Log the abortion
            LOG.info("Refactoring process aborted by user");
            
            // Clean up the state files
            executionManager.abortRefactoring();
            
            // Close the tool window
            closeToolWindow();
        }
    }
    
    /**
     * Synchronizes the local plan and progress objects with the latest state from disk.
     * This ensures that the UI is always showing the most up-to-date information.
     */
    private void syncStateFromDisk() {
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        RefactoringPlan updatedPlan = stateManager.loadPlan();
        RefactoringProgress updatedProgress = stateManager.loadProgress();
        
        if (updatedPlan != null && updatedProgress != null) {
            // Update our references to the plan and progress
            this.plan.getIssues().clear();
            this.plan.getIssues().addAll(updatedPlan.getIssues());
            this.progress.setCurrentIssueIndex(updatedProgress.getCurrentIssueIndex());
            this.progress.setCurrentStepIndex(updatedProgress.getCurrentStepIndex());
            this.progress.setCompletedStepIds(updatedProgress.getCompletedStepIds());
            this.progress.setSkippedStepIds(updatedProgress.getSkippedStepIds());
            this.progress.setFailedStepIds(updatedProgress.getFailedStepIds());
            
            LOG.info("Synchronized state from disk. Current step: " + progress.getCurrentStep());
        } else {
            LOG.error("Failed to load plan or progress from disk for synchronization");
        }
    }
    
    /**
     * Closes the tool window.
     */
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
     * Table model for displaying refactoring steps.
     */
    private static class RefactoringTableModel extends AbstractTableModel {
        private final List<RefactoringTableEntry> entries;
        private final String[] columnNames = {"ID", "Step", "Issue", "Status"};
        private JTable table;
        
        /**
         * Creates a new table model for the refactoring plan.
         */
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
        
        public JTable getTable() {
            return table;
        }
        
        public void setTable(JTable table) {
            this.table = table;
        }
        
        public List<RefactoringTableEntry> getEntries() {
            return entries;
        }
        
        public RefactoringTableEntry getEntryAt(int rowIndex) {
            return entries.get(rowIndex);
        }
        
        /**
         * Refreshes the data in the table.
         */
        public void refreshData() {
            fireTableDataChanged();
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
        private RefactoringStepStatus status;
        private final int issueIndex;
        private final int stepIndex;
        
        /**
         * Creates a new table entry.
         */
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
        public int getStepId() {
            return stepId;
        }
        
        public String getStepTitle() {
            return stepTitle;
        }
        
        public String getIssueTitle() {
            return issueTitle;
        }
        
        public String getStepDescription() {
            return stepDescription;
        }
        
        public RefactoringStepStatus getStatus() {
            return status;
        }
        
        public void setStatus(RefactoringStepStatus status) {
            this.status = status;
        }
        
        public int getIssueIndex() {
            return issueIndex;
        }
        
        public int getStepIndex() {
            return stepIndex;
        }
    }
}
