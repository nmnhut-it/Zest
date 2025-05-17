package com.zps.zest.refactoring;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing the refactoring process.
 * Shows the refactoring plan and allows the user to control execution.
 */
public class RefactoringManagerDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(RefactoringManagerDialog.class);
    private final Project project;
    private final RefactoringPlan plan;
    private final RefactoringProgress progress;
    private final RefactoringExecutionManager executionManager;
    private final RefactoringTableModel tableModel;
    
    /**
     * Creates a new refactoring manager dialog.
     */
    public RefactoringManagerDialog(Project project, RefactoringPlan plan, RefactoringProgress progress) {
        super(project, true);
        this.project = project;
        this.plan = plan;
        this.progress = progress;
        this.executionManager = new RefactoringExecutionManager(project);
        this.tableModel = new RefactoringTableModel(plan);
        
        setTitle("Refactoring for Testability: " + plan.getTargetClass());
        setOKButtonText("Execute Next Step");
        setCancelButtonText("Abort Refactoring");
        init();
    }
    
    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Create the table with the refactoring steps
        JBTable table = new JBTable(tableModel);
        tableModel.setTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);  // ID
        table.getColumnModel().getColumn(1).setPreferredWidth(200); // Title
        table.getColumnModel().getColumn(2).setPreferredWidth(100); // Issue
        table.getColumnModel().getColumn(3).setPreferredWidth(100); // Status
        
        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 300));
        
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Add description panel
        JTextArea descriptionArea = new JTextArea();
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        
        // Update description when selection changes
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRow = table.getSelectedRow();
                if (selectedRow >= 0) {
                    RefactoringTableEntry entry = tableModel.getEntryAt(selectedRow);
                    descriptionArea.setText(entry.getStepDescription());
                }
            }
        });
        
        JBScrollPane descScrollPane = new JBScrollPane(descriptionArea);
        descScrollPane.setPreferredSize(new Dimension(700, 150));
        
        panel.add(descScrollPane, BorderLayout.SOUTH);
        
        // Select the current step
        int currentRow = findCurrentStepRow();
        if (currentRow >= 0) {
            table.setRowSelectionInterval(currentRow, currentRow);
        }
        
        return panel;
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
    
    @Override
    protected void doOKAction() {
        // Execute the current step
        boolean hasMoreSteps = executionManager.executeStep(plan, progress);
        
        if (hasMoreSteps) {
            // Wait for user to confirm step completion
            int result = Messages.showYesNoCancelDialog(
                    project,
                    "Has the refactoring step been completed successfully?",
                    "Refactoring Step",
                    "Completed Successfully",
                    "Skip this Step",
                    "Stop Refactoring",
                    Messages.getQuestionIcon()
            );
            
            if (result == Messages.YES) {
                // Step completed successfully
                executionManager.completeCurrentStepAndMoveToNext();
                tableModel.refreshData();
            } else if (result == Messages.NO) {
                // Skip this step
                executionManager.skipCurrentStepAndMoveToNext();
                tableModel.refreshData();
            } else {
                // Cancel refactoring
                executionManager.abortRefactoring();
                close(OK_EXIT_CODE);
                return;
            }
            
            // Select the next step
            int currentRow = findCurrentStepRow();
            if (currentRow >= 0) {
                JTable table = tableModel.getTable();
                if (table != null) {
                    table.setRowSelectionInterval(currentRow, currentRow);
                }
            }
        } else {
            // No more steps, close the dialog
            close(OK_EXIT_CODE);
        }
    }
    
    @Override
    public void doCancelAction() {
        // Confirm cancellation
        int result = Messages.showYesNoDialog(
                project,
                "Are you sure you want to abort the refactoring process?",
                "Abort Refactoring",
                Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            executionManager.abortRefactoring();
            super.doCancelAction();
        }
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
