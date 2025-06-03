package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Test action for the Improved Tool-Calling Autonomous Agent.
 */
public class TestImprovedToolCallingAgentAction extends AnAction {
    
    public TestImprovedToolCallingAgentAction() {
        super("Test Improved Tool-Calling Agent", "Tests the improved tool-calling autonomous code exploration agent", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get initial query from user
        String query = Messages.showInputDialog(
            project,
            "Enter a query for tool-based exploration (e.g., 'How does CodeSearchUtility handle hybrid search?'):",
            "Test Improved Tool-Calling Agent",
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
        private final JTextArea conversationArea;
        
        public ToolExplorationDialog(Project project, String query) {
            super();
            this.project = project;
            this.query = query;
            
            setTitle("Improved Tool-Calling Agent Explorer");
            setModal(false);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            
            // Create UI components
            outputArea = new JTextArea();
            outputArea.setEditable(false);
            outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            
            conversationArea = new JTextArea();
            conversationArea.setEditable(false);
            conversationArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            conversationArea.setLineWrap(true);
            conversationArea.setWrapStyleWord(true);
            
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
            
            // Main content with tabs
            JTabbedPane tabbedPane = new JTabbedPane();
            
            // Tab 1: Conversation flow
            JPanel conversationPanel = new JPanel(new BorderLayout());
            conversationPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            conversationPanel.add(new JScrollPane(conversationArea), BorderLayout.CENTER);
            tabbedPane.addTab("Conversation Flow", conversationPanel);
            
            // Tab 2: Tool executions and results
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            
            // Left: Tool executions
            JPanel toolPanel = new JPanel(new BorderLayout());
            toolPanel.setBorder(BorderFactory.createTitledBorder("Tool Executions"));
            toolPanel.add(new JScrollPane(toolList), BorderLayout.CENTER);
            splitPane.setLeftComponent(toolPanel);
            
            // Right: Detailed results
            JPanel outputPanel = new JPanel(new BorderLayout());
            outputPanel.setBorder(BorderFactory.createTitledBorder("Detailed Results"));
            outputPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
            splitPane.setRightComponent(outputPanel);
            
            splitPane.setDividerLocation(250);
            tabbedPane.addTab("Tool Results", splitPane);
            
            add(tabbedPane, BorderLayout.CENTER);
            
            // Button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            
            JButton copyConversationButton = new JButton("Copy Conversation");
            copyConversationButton.addActionListener(e -> {
                conversationArea.selectAll();
                conversationArea.copy();
                Messages.showInfoMessage("Conversation copied to clipboard", "Copied");
            });
            buttonPanel.add(copyConversationButton);
            
            JButton copyResultsButton = new JButton("Copy Results");
            copyResultsButton.addActionListener(e -> {
                outputArea.selectAll();
                outputArea.copy();
                Messages.showInfoMessage("Results copied to clipboard", "Copied");
            });
            buttonPanel.add(copyResultsButton);
            
            JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dispose());
            buttonPanel.add(closeButton);
            
            add(buttonPanel, BorderLayout.SOUTH);
            
            // Set size and position
            setSize(1200, 800);
            setLocationRelativeTo(null);
        }
        
        public void startExploration() {
            // Use the improved agent
            ImprovedToolCallingAutonomousAgent agent = project.getService(ImprovedToolCallingAutonomousAgent.class);
            
            // Create progress callback
            ImprovedToolCallingAutonomousAgent.ProgressCallback callback = new ImprovedToolCallingAutonomousAgent.ProgressCallback() {
                @Override
                public void onToolExecution(ImprovedToolCallingAutonomousAgent.ToolExecution execution) {
                    SwingUtilities.invokeLater(() -> {
                        String toolEntry = String.format("%s(%s) - %s", 
                            execution.getToolName(), 
                            execution.getParameters().toString(),
                            execution.isSuccess() ? "✓" : "✗");
                        toolListModel.addElement(toolEntry);
                        
                        // Update detailed output
                        outputArea.append("\n" + "=".repeat(80) + "\n");
                        outputArea.append("Tool: " + execution.getToolName() + "\n");
                        outputArea.append("Parameters: " + execution.getParameters() + "\n");
                        outputArea.append("Status: " + (execution.isSuccess() ? "Success" : "Failed") + "\n");
                        outputArea.append("Result:\n" + execution.getResult() + "\n");
                    });
                }
                
                @Override
                public void onRoundComplete(ImprovedToolCallingAutonomousAgent.ExplorationRound round) {
                    SwingUtilities.invokeLater(() -> {
                        // Update conversation area
                        conversationArea.append("\n\n" + "─".repeat(60) + "\n");
                        conversationArea.append("▶ " + round.getName() + "\n");
                        conversationArea.append("─".repeat(60) + "\n\n");
                        
                        conversationArea.append("Assistant:\n");
                        conversationArea.append(round.getLlmResponse() + "\n");
                        
                        if (!round.getToolExecutions().isEmpty()) {
                            conversationArea.append("\n[Executing " + round.getToolExecutions().size() + " tool calls...]\n");
                            
                            for (ImprovedToolCallingAutonomousAgent.ToolExecution exec : round.getToolExecutions()) {
                                conversationArea.append("\n→ " + exec.getToolName() + ": ");
                                conversationArea.append(exec.isSuccess() ? "Success" : "Failed");
                                
                                // Show abbreviated result
                                String result = exec.getResult();
                                if (result.length() > 200) {
                                    result = result.substring(0, 200) + "... [see Tool Results tab for full output]";
                                }
                                conversationArea.append("\n  " + result.replace("\n", "\n  ") + "\n");
                            }
                        }
                        
                        // Auto-scroll to bottom
                        conversationArea.setCaretPosition(conversationArea.getDocument().getLength());
                    });
                }
                
                @Override
                public void onExplorationComplete(ImprovedToolCallingAutonomousAgent.ExplorationResult result) {
                    SwingUtilities.invokeLater(() -> displayFinalResults(result));
                }
            };
            
            // Update UI
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Starting exploration...");
                conversationArea.append("=== Improved Tool-Calling Autonomous Code Exploration ===\n\n");
                conversationArea.append("User Query: " + query + "\n");
                conversationArea.append("─".repeat(60) + "\n");
            });
            
            // Start exploration with async method
            agent.exploreWithToolsAsync(query, callback)
                .thenAccept(result -> {
                    SwingUtilities.invokeLater(() -> displayFinalResults(result));
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        statusLabel.setText("Error: " + ex.getMessage());
                        conversationArea.append("\n\nERROR: " + ex.getMessage() + "\n");
                        outputArea.append("\n\nERROR: " + ex.getMessage() + "\n");
                    });
                    return null;
                });
        }
        
        private void displayFinalResults(ImprovedToolCallingAutonomousAgent.ExplorationResult result) {
            progressBar.setIndeterminate(false);
            
            if (!result.isSuccess()) {
                statusLabel.setText("Exploration failed");
                conversationArea.append("\n\n=== ERRORS ===\n");
                for (String error : result.getErrors()) {
                    conversationArea.append("- " + error + "\n");
                }
                return;
            }
            
            // Display summary
            if (result.getSummary() != null) {
                conversationArea.append("\n\n" + "═".repeat(60) + "\n");
                conversationArea.append("▶ FINAL SUMMARY\n");
                conversationArea.append("═".repeat(60) + "\n\n");
                conversationArea.append(result.getSummary());
                
                outputArea.append("\n\n" + "=".repeat(80) + "\n");
                outputArea.append("FINAL SUMMARY\n");
                outputArea.append("=".repeat(80) + "\n\n");
                outputArea.append(result.getSummary());
            }
            
            // Update status with stats
            int totalTools = result.getRounds().stream()
                .mapToInt(r -> r.getToolExecutions().size())
                .sum();
            statusLabel.setText("Exploration complete - " + result.getRounds().size() + 
                              " rounds, " + totalTools + " tool calls");
            
            // Scroll to top of conversation
            conversationArea.setCaretPosition(0);
        }
    }
}
