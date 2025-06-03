package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.agent.ToolCallingAutonomousAgent;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Test action for the Tool-Calling Autonomous Agent.
 */
public class TestToolCallingAgentAction extends AnAction {
    
    public TestToolCallingAgentAction() {
        super("Test Tool-Calling Agent", "Tests the tool-calling autonomous code exploration agent", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get initial query from user
        String query = Messages.showInputDialog(
            project,
            "Enter a query for tool-based exploration (e.g., 'How does CodeSearchUtility handle hybrid search?'):",
            "Test Tool-Calling Agent",
            Messages.getQuestionIcon()
        );
        
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        // Create and show the exploration dialog
        ToolExplorationDialog dialog = new ToolExplorationDialog(project, query);
        dialog.startExploration();
        dialog.setVisible(true);
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
    
    /**
     * Dialog for showing tool-based exploration progress and results.
     */
    private static class ToolExplorationDialog extends JDialog {
        private final Project project;
        private final String query;
        private final JTextArea outputArea;
        private final JProgressBar progressBar;
        private final JLabel statusLabel;
        private final DefaultListModel<String> toolListModel;
        
        public ToolExplorationDialog(Project project, String query) {
            super();
            this.project = project;
            this.query = query;
            
            setTitle("Tool-Calling Agent Explorer");
            setModal(false);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            
            // Create UI components
            outputArea = new JTextArea();
            outputArea.setEditable(false);
            outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            
            progressBar = new JProgressBar();
            progressBar.setIndeterminate(true);
            
            statusLabel = new JLabel("Initializing...");
            
            toolListModel = new DefaultListModel<>();
            JList<String> toolList = new JList<>(toolListModel);
            toolList.setVisibleRowCount(10);
            
            // Layout
            setLayout(new BorderLayout());
            
            // Top panel with query and status
            JPanel topPanel = new JPanel(new BorderLayout(5, 5));
            topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            JLabel queryLabel = new JLabel("<html><b>Query:</b> " + query + "</html>");
            topPanel.add(queryLabel, BorderLayout.NORTH);
            
            JPanel statusPanel = new JPanel(new BorderLayout());
            statusPanel.add(statusLabel, BorderLayout.WEST);
            statusPanel.add(progressBar, BorderLayout.CENTER);
            topPanel.add(statusPanel, BorderLayout.SOUTH);
            
            add(topPanel, BorderLayout.NORTH);
            
            // Split pane with tools and output
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            
            // Left: Tool executions
            JPanel toolPanel = new JPanel(new BorderLayout());
            toolPanel.setBorder(BorderFactory.createTitledBorder("Tool Executions"));
            toolPanel.add(new JScrollPane(toolList), BorderLayout.CENTER);
            splitPane.setLeftComponent(toolPanel);
            
            // Right: Output
            JPanel outputPanel = new JPanel(new BorderLayout());
            outputPanel.setBorder(BorderFactory.createTitledBorder("Exploration Results"));
            outputPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
            splitPane.setRightComponent(outputPanel);
            
            splitPane.setDividerLocation(250);
            add(splitPane, BorderLayout.CENTER);
            
            // Button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            
            JButton copyButton = new JButton("Copy Results");
            copyButton.addActionListener(e -> {
                outputArea.selectAll();
                outputArea.copy();
                Messages.showInfoMessage("Results copied to clipboard", "Copied");
            });
            buttonPanel.add(copyButton);
            
            JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dispose());
            buttonPanel.add(closeButton);
            
            add(buttonPanel, BorderLayout.SOUTH);
            
            // Set size and position
            setSize(1000, 700);
            setLocationRelativeTo(null);
        }
        
        public void startExploration() {
            // Use IntelliJ's progress API for proper threading
            ToolCallingAutonomousAgent agent = project.getService(ToolCallingAutonomousAgent.class);
            
            // Create progress callback
            ToolCallingAutonomousAgent.ProgressCallback callback = new ToolCallingAutonomousAgent.ProgressCallback() {
                @Override
                public void onToolExecution(ToolCallingAutonomousAgent.ToolExecution execution) {
                    SwingUtilities.invokeLater(() -> {
                        String toolEntry = execution.getToolName() + " - " + 
                                         (execution.isSuccess() ? "✓" : "✗");
                        toolListModel.addElement(toolEntry);
                        
                        outputArea.append("\nTool: " + execution.getToolName() + "\n");
                        outputArea.append("Status: " + (execution.isSuccess() ? "Success" : "Failed") + "\n");
                        outputArea.append("Result: " + execution.getResult() + "\n");
                        outputArea.append("-".repeat(40) + "\n");
                    });
                }
                
                @Override
                public void onRoundComplete(ToolCallingAutonomousAgent.ExplorationRound round) {
                    SwingUtilities.invokeLater(() -> {
                        outputArea.append("\n=== " + round.getName() + " Complete ===\n");
                    });
                }
                
                @Override
                public void onExplorationComplete(ToolCallingAutonomousAgent.ExplorationResult result) {
                    SwingUtilities.invokeLater(() -> displayResults(result));
                }
            };
            
            // Update UI
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Starting exploration...");
                outputArea.append("=== Tool-Calling Autonomous Code Exploration ===\n\n");
                outputArea.append("Query: " + query + "\n\n");
            });
            
            // Start exploration with async method
            agent.exploreWithToolsAsync(query, callback)
                .thenAccept(result -> {
                    SwingUtilities.invokeLater(() -> displayResults(result));
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        statusLabel.setText("Error: " + ex.getMessage());
                        outputArea.append("\n\nERROR: " + ex.getMessage() + "\n");
                    });
                    return null;
                });
        }
        
        private void displayResults(ToolCallingAutonomousAgent.ExplorationResult result) {
            progressBar.setIndeterminate(false);
            
            if (!result.isSuccess()) {
                statusLabel.setText("Exploration failed");
                outputArea.append("\n\n=== ERRORS ===\n");
                for (String error : result.getErrors()) {
                    outputArea.append("- " + error + "\n");
                }
                return;
            }
            
            statusLabel.setText("Exploration complete");
            
            // Display each round
            for (ToolCallingAutonomousAgent.ExplorationRound round : result.getRounds()) {
                outputArea.append("\n=== " + round.getName() + " ===\n\n");
                
                if (round.getLlmResponse() != null) {
                    outputArea.append("LLM Response:\n");
                    outputArea.append(round.getLlmResponse() + "\n\n");
                }
                
                // Display tool executions
                for (ToolCallingAutonomousAgent.ToolExecution execution : round.getToolExecutions()) {
                    String toolEntry = execution.getToolName() + " - " + 
                                     (execution.isSuccess() ? "✓" : "✗");
                    toolListModel.addElement(toolEntry);
                    
                    outputArea.append("Tool: " + execution.getToolName() + "\n");
                    outputArea.append("Parameters: " + execution.getParameters() + "\n");
                    outputArea.append("Status: " + (execution.isSuccess() ? "Success" : "Failed") + "\n");
                    outputArea.append("Result:\n" + execution.getResult() + "\n\n");
                    outputArea.append("-".repeat(80) + "\n\n");
                }
            }
            
            // Display summary
            if (result.getSummary() != null) {
                outputArea.append("\n\n=== FINAL SUMMARY ===\n\n");
                outputArea.append(result.getSummary());
            }
            
            // Scroll to top
            outputArea.setCaretPosition(0);
            
            // Update status with stats
            int totalTools = result.getRounds().stream()
                .mapToInt(r -> r.getToolExecutions().size())
                .sum();
            statusLabel.setText("Exploration complete - " + result.getRounds().size() + 
                              " rounds, " + totalTools + " tool calls");
        }
    }
}
