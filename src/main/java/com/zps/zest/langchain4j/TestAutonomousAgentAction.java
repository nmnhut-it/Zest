package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.agent.AutonomousCodeExplorationAgent;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Test action for the Autonomous Code Exploration Agent.
 */
public class TestAutonomousAgentAction extends AnAction {
    
    public TestAutonomousAgentAction() {
        super("Test Autonomous Agent", "Tests the autonomous code exploration agent", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get initial query from user
        String query = Messages.showInputDialog(
            project,
            "Enter a query for autonomous exploration (e.g., 'How does the payment system work?'):",
            "Test Autonomous Agent",
            Messages.getQuestionIcon()
        );
        
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        // Show progress dialog
        JDialog progressDialog = createProgressDialog(project);
        progressDialog.setVisible(true);
        
        // Run exploration in background
        new Thread(() -> {
            try {
                // Get the autonomous agent
                AutonomousCodeExplorationAgent agent = project.getService(AutonomousCodeExplorationAgent.class);
                
                // Start exploration
                String explorationLog = agent.startAutonomousExploration(query);
                
                // Close progress dialog
                SwingUtilities.invokeLater(() -> progressDialog.dispose());
                
                // Show results in a scrollable dialog
                SwingUtilities.invokeLater(() -> showResultsDialog(project, explorationLog));
                
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    Messages.showErrorDialog(
                        project,
                        "Error during exploration: " + ex.getMessage(),
                        "Autonomous Agent Error"
                    );
                });
            }
        }).start();
    }
    
    private JDialog createProgressDialog(Project project) {
        JDialog dialog = new JDialog();
        dialog.setTitle("Autonomous Exploration in Progress");
        dialog.setModal(false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        JLabel label = new JLabel("The agent is exploring your codebase...");
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.NORTH);
        
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        panel.add(progressBar, BorderLayout.CENTER);
        
        JLabel infoLabel = new JLabel("<html>The agent is:<br>" +
            "• Analyzing your query<br>" +
            "• Generating questions<br>" +
            "• Exploring the codebase<br>" +
            "• Building understanding</html>");
        infoLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        panel.add(infoLabel, BorderLayout.SOUTH);
        
        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        
        return dialog;
    }
    
    private void showResultsDialog(Project project, String explorationLog) {
        JDialog dialog = new JDialog();
        dialog.setTitle("Autonomous Exploration Results");
        dialog.setModal(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        
        // Create text area with results
        JTextArea textArea = new JTextArea(explorationLog);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setCaretPosition(0);
        
        // Add to scroll pane
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(800, 600));
        
        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JButton copyButton = new JButton("Copy to Clipboard");
        copyButton.addActionListener(e -> {
            textArea.selectAll();
            textArea.copy();
            Messages.showInfoMessage("Exploration results copied to clipboard", "Copied");
        });
        buttonPanel.add(copyButton);
        
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);
        
        // Layout
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
