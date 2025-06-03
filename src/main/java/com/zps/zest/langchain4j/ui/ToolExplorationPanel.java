package com.zps.zest.langchain4j.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.zps.zest.langchain4j.agent.ToolCallingAutonomousAgent;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Vector;

/**
 * Enhanced UI panel for tool-based code exploration.
 */
public class ToolExplorationPanel extends JPanel {
    private final Project project;
    private final JTextArea queryArea;
    private final JTextArea resultArea;
    private final DefaultTableModel toolTableModel;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JBTabbedPane tabbedPane;
    
    // Visualization components
    private final ExplorationGraphPanel graphPanel;
    private final CodeReferencePanel codeRefPanel;
    
    public ToolExplorationPanel(Project project) {
        this.project = project;
        
        setLayout(new BorderLayout());
        
        // Initialize components
        queryArea = new JTextArea(3, 50);
        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        toolTableModel = new DefaultTableModel(
            new String[]{"Tool", "Parameters", "Status", "Duration"}, 0);
        JBTable toolTable = new JBTable(toolTableModel);
        
        progressBar = new JProgressBar();
        statusLabel = new JLabel("Ready");
        
        graphPanel = new ExplorationGraphPanel();
        codeRefPanel = new CodeReferencePanel(project);
        
        // Layout
        add(createTopPanel(), BorderLayout.NORTH);
        
        tabbedPane = new JBTabbedPane();
        tabbedPane.addTab("Exploration Log", createResultPanel());
        tabbedPane.addTab("Tool Executions", new JBScrollPane(toolTable));
        tabbedPane.addTab("Exploration Graph", graphPanel);
        tabbedPane.addTab("Code References", codeRefPanel);
        
        add(tabbedPane, BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);
    }
    
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Query input
        JPanel queryPanel = new JPanel(new BorderLayout());
        queryPanel.setBorder(BorderFactory.createTitledBorder("Query"));
        queryPanel.add(new JBScrollPane(queryArea), BorderLayout.CENTER);
        
        // Control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton exploreButton = new JButton("Start Exploration");
        exploreButton.addActionListener(e -> startExploration());
        buttonPanel.add(exploreButton);
        
        JButton stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        buttonPanel.add(stopButton);
        
        queryPanel.add(buttonPanel, BorderLayout.SOUTH);
        panel.add(queryPanel, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JBScrollPane(resultArea), BorderLayout.CENTER);
        return panel;
    }
    
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(progressBar, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void startExploration() {
        String query = queryArea.getText().trim();
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a query", 
                "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // Clear previous results
        resultArea.setText("");
        toolTableModel.setRowCount(0);
        graphPanel.clear();
        codeRefPanel.clear();
        
        // Update UI
        progressBar.setIndeterminate(true);
        statusLabel.setText("Starting exploration...");
        
        // Run exploration in background
        new Thread(() -> runExploration(query)).start();
    }
    
    private void runExploration(String query) {
        try {
            ToolCallingAutonomousAgent agent = project.getService(ToolCallingAutonomousAgent.class);
            
            // Create a custom result handler
            ExplorationResultHandler handler = new ExplorationResultHandler();
            
            // Start exploration
            ToolCallingAutonomousAgent.ExplorationResult result = agent.exploreWithTools(query);
            
            // Update UI with results
            SwingUtilities.invokeLater(() -> displayResults(result));
            
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setIndeterminate(false);
                statusLabel.setText("Error: " + e.getMessage());
                resultArea.append("\n\nERROR: " + e.getMessage() + "\n");
            });
        }
    }
    
    private void displayResults(ToolCallingAutonomousAgent.ExplorationResult result) {
        progressBar.setIndeterminate(false);
        
        if (!result.isSuccess()) {
            statusLabel.setText("Exploration failed");
            resultArea.append("=== ERRORS ===\n");
            for (String error : result.getErrors()) {
                resultArea.append("- " + error + "\n");
            }
            return;
        }
        
        // Display results
        resultArea.append("=== Tool-Based Code Exploration Results ===\n\n");
        
        // Process each round
        for (ToolCallingAutonomousAgent.ExplorationRound round : result.getRounds()) {
            resultArea.append("\n--- " + round.getName() + " ---\n");
            
            // Add tool executions to table
            for (ToolCallingAutonomousAgent.ToolExecution execution : round.getToolExecutions()) {
                Vector<Object> row = new Vector<>();
                row.add(execution.getToolName());
                row.add(execution.getParameters().toString());
                row.add(execution.isSuccess() ? "Success" : "Failed");
                row.add("N/A"); // Duration placeholder
                toolTableModel.addRow(row);
                
                // Update graph
                graphPanel.addToolExecution(execution);
                
                // Extract code references
                codeRefPanel.addCodeReferences(execution);
            }
        }
        
        // Display summary
        if (result.getSummary() != null) {
            resultArea.append("\n\n=== SUMMARY ===\n");
            resultArea.append(result.getSummary());
        }
        
        // Update status
        int totalTools = result.getRounds().stream()
            .mapToInt(r -> r.getToolExecutions().size())
            .sum();
        statusLabel.setText("Complete - " + result.getRounds().size() + 
                          " rounds, " + totalTools + " tool calls");
    }
    
    /**
     * Custom handler for processing exploration results in real-time.
     */
    private class ExplorationResultHandler {
        public void onRoundStart(String roundName) {
            SwingUtilities.invokeLater(() -> {
                resultArea.append("\n=== " + roundName + " ===\n");
                statusLabel.setText("Executing: " + roundName);
            });
        }
        
        public void onToolExecution(ToolCallingAutonomousAgent.ToolExecution execution) {
            SwingUtilities.invokeLater(() -> {
                // Add to table
                Vector<Object> row = new Vector<>();
                row.add(execution.getToolName());
                row.add(execution.getParameters().toString());
                row.add(execution.isSuccess() ? "✓" : "✗");
                row.add("0.0s");
                toolTableModel.addRow(row);
                
                // Update graph
                graphPanel.addToolExecution(execution);
                
                // Update status
                statusLabel.setText("Executed: " + execution.getToolName());
            });
        }
    }
    
    /**
     * Panel for visualizing the exploration graph.
     */
    private static class ExplorationGraphPanel extends JPanel {
        // Simplified graph visualization
        private final DefaultListModel<String> nodeModel = new DefaultListModel<>();
        
        public ExplorationGraphPanel() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder("Exploration Path"));
            
            JList<String> nodeList = new JList<>(nodeModel);
            add(new JBScrollPane(nodeList), BorderLayout.CENTER);
        }
        
        public void addToolExecution(ToolCallingAutonomousAgent.ToolExecution execution) {
            String node = execution.getToolName() + " → " + 
                         (execution.isSuccess() ? "Success" : "Failed");
            nodeModel.addElement(node);
        }
        
        public void clear() {
            nodeModel.clear();
        }
    }
    
    /**
     * Panel for showing discovered code references.
     */
    private static class CodeReferencePanel extends JPanel {
        private final Project project;
        private final DefaultListModel<String> refModel = new DefaultListModel<>();
        
        public CodeReferencePanel(Project project) {
            this.project = project;
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder("Discovered Code Elements"));
            
            JList<String> refList = new JList<>(refModel);
            refList.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent evt) {
                    if (evt.getClickCount() == 2) {
                        String selected = refList.getSelectedValue();
                        if (selected != null) {
                            // Navigate to code element
                            navigateToElement(selected);
                        }
                    }
                }
            });
            
            add(new JBScrollPane(refList), BorderLayout.CENTER);
        }
        
        public void addCodeReferences(ToolCallingAutonomousAgent.ToolExecution execution) {
            // Extract code references from execution result
            String result = execution.getResult();
            if (result != null && result.contains("#")) {
                // Simple extraction of method references
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.contains("#") && line.contains("(")) {
                        int start = line.lastIndexOf(' ', line.indexOf('#'));
                        if (start < 0) start = 0;
                        int end = line.indexOf(')', line.indexOf('#'));
                        if (end > 0) {
                            String ref = line.substring(start, end + 1).trim();
                            if (!refModel.contains(ref)) {
                                refModel.addElement(ref);
                            }
                        }
                    }
                }
            }
        }
        
        public void clear() {
            refModel.clear();
        }
        
        private void navigateToElement(String element) {
            // TODO: Implement navigation to code element
            JOptionPane.showMessageDialog(this, 
                "Navigation to: " + element + " (not yet implemented)",
                "Navigate", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
