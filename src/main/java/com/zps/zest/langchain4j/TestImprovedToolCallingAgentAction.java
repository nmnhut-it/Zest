package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import com.zps.zest.langchain4j.agent.CodeExplorationReport;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.List;

/**
 * Test action for the Improved Tool-Calling Autonomous Agent with Report Display.
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
     * Dialog for showing tool-based exploration progress and results with report display.
     */
    private static class ToolExplorationDialog extends JDialog {
        private final Project project;
        private final String query;
        private final JTextArea outputArea;
        private final JProgressBar progressBar;
        private final JLabel statusLabel;
        private final DefaultListModel<String> toolListModel;
        private final JTextArea conversationArea;
        private final JTextArea reportArea;
        private CodeExplorationReport generatedReport;

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

            reportArea = new JTextArea();
            reportArea.setEditable(false);
            reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            reportArea.setLineWrap(true);
            reportArea.setWrapStyleWord(true);

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

            // Tab 1: Generated Report
            JPanel reportPanel = new JPanel(new BorderLayout());
            reportPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            reportPanel.add(new JScrollPane(reportArea), BorderLayout.CENTER);
            tabbedPane.addTab("Generated Report", reportPanel);

            // Tab 2: Conversation flow
            JPanel conversationPanel = new JPanel(new BorderLayout());
            conversationPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            conversationPanel.add(new JScrollPane(conversationArea), BorderLayout.CENTER);
            tabbedPane.addTab("Conversation Flow", conversationPanel);

            // Tab 3: Tool executions and results
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

            JButton copyReportButton = new JButton("Copy Report");
            copyReportButton.addActionListener(e -> {
                reportArea.selectAll();
                reportArea.copy();
                Messages.showInfoMessage("Report copied to clipboard", "Copied");
            });
            buttonPanel.add(copyReportButton);

            JButton copyConversationButton = new JButton("Copy Conversation");
            copyConversationButton.addActionListener(e -> {
                conversationArea.selectAll();
                conversationArea.copy();
                Messages.showInfoMessage("Conversation copied to clipboard", "Copied");
            });
            buttonPanel.add(copyConversationButton);

            JButton exportReportButton = new JButton("Export Report as JSON");
            exportReportButton.addActionListener(e -> exportReport());
            buttonPanel.add(exportReportButton);

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
                    // This is handled in the async completion below
                }
            };

            // Update UI
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Starting exploration...");
                conversationArea.append("=== Improved Tool-Calling Autonomous Code Exploration ===\n\n");
                conversationArea.append("User Query: " + query + "\n");
                conversationArea.append("─".repeat(60) + "\n");

                reportArea.append("Generating comprehensive report...\n\n");
                reportArea.append("Please wait while the agent explores the codebase...\n");
            });

            // Start exploration and generate report
            agent.exploreAndGenerateReportAsync(query, callback)
                    .thenAccept(report -> {
                        SwingUtilities.invokeLater(() -> {
                            generatedReport = report;
                            displayReport(report);
                            displayFinalStatus(report);
                        });
                    })
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setIndeterminate(false);
                            statusLabel.setText("Error: " + ex.getMessage());
                            conversationArea.append("\n\nERROR: " + ex.getMessage() + "\n");
                            reportArea.setText("ERROR: Failed to generate report\n\n" + ex.getMessage());
                        });
                        return null;
                    });
        }

        private void displayReport(CodeExplorationReport report) {
            reportArea.setText("");

            // Header
            reportArea.append("═".repeat(80) + "\n");
            reportArea.append("CODE EXPLORATION REPORT\n");
            reportArea.append("═".repeat(80) + "\n\n");

            // Metadata
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            reportArea.append("Generated: " + dateFormat.format(report.getTimestamp()) + "\n");
            reportArea.append("Query: " + report.getOriginalQuery() + "\n");
            reportArea.append("Discovered Elements: " + report.getDiscoveredElements().size() + "\n");
            reportArea.append("Code Pieces: " + report.getCodePieces().size() + "\n\n");

            // Exploration Summary
            if (report.getExplorationSummary() != null) {
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("EXPLORATION SUMMARY\n");
                reportArea.append("═".repeat(80) + "\n\n");
                reportArea.append(report.getExplorationSummary());
                reportArea.append("\n\n");
            }

            // Structured Context
            if (report.getStructuredContext() != null) {
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("STRUCTURED CONTEXT\n");
                reportArea.append("═".repeat(80) + "\n\n");
                reportArea.append(report.getStructuredContext());
                reportArea.append("\n");
            }

            // Discovered Elements
            if (!report.getDiscoveredElements().isEmpty()) {
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("DISCOVERED CODE ELEMENTS\n");
                reportArea.append("═".repeat(80) + "\n\n");

                for (String element : report.getDiscoveredElements()) {
                    reportArea.append("• " + element + "\n");
                }
                reportArea.append("\n");
            }

            // Code Pieces
            if (!report.getCodePieces().isEmpty()) {
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("CODE PIECES\n");
                reportArea.append("═".repeat(80) + "\n\n");

                for (CodeExplorationReport.CodePiece piece : report.getCodePieces()) {
                    reportArea.append("### " + piece.getId() + "\n");
                    reportArea.append("Type: " + piece.getType() + "\n");

                    if (piece.getFilePath() != null) {
                        reportArea.append("File: " + piece.getFilePath() + "\n");
                    }
                    if (piece.getClassName() != null) {
                        reportArea.append("Class: " + piece.getClassName() + "\n");
                    }

                    reportArea.append("\n```" + piece.getLanguage() + "\n");
                    reportArea.append(piece.getContent());
                    if (!piece.getContent().endsWith("\n")) {
                        reportArea.append("\n");
                    }
                    reportArea.append("```\n\n");
                }
            }

            // Relationships
            if (report.getRelationships() != null && !report.getRelationships().isEmpty()) {
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("RELATIONSHIPS AND DEPENDENCIES\n");
                reportArea.append("═".repeat(80) + "\n\n");

                for (Map.Entry<String, List<String>> entry : report.getRelationships().entrySet()) {
                    reportArea.append("▶ " + entry.getKey() + "\n");
                    for (String related : entry.getValue()) {
                        reportArea.append("  → " + related + "\n");
                    }
                    reportArea.append("\n");
                }
            }

            // Coding Context (Full)
            if (report.getCodingContext() != null) {
                reportArea.append("\n");
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("COMPREHENSIVE CODING CONTEXT\n");
                reportArea.append("═".repeat(80) + "\n\n");
                reportArea.append(report.getCodingContext());
            }

            // Summary
            if (report.getSummary() != null) {
                reportArea.append("\n");
                reportArea.append("═".repeat(80) + "\n");
                reportArea.append("SUMMARY\n");
                reportArea.append("═".repeat(80) + "\n\n");
                reportArea.append(report.getSummary());
            }

            // Scroll to top
            reportArea.setCaretPosition(0);
        }

        private void displayFinalStatus(CodeExplorationReport report) {
            progressBar.setIndeterminate(false);

            // Count statistics
            int totalCodePieces = report.getCodePieces().size();
            int totalElements = report.getDiscoveredElements().size();
            int totalRelationships = report.getRelationships() != null ?
                    report.getRelationships().values().stream().mapToInt(List::size).sum() : 0;

            statusLabel.setText(String.format("Report generated - %d code pieces, %d elements, %d relationships",
                    totalCodePieces, totalElements, totalRelationships));
        }

        private void exportReport() {
            if (generatedReport == null) {
                Messages.showWarningDialog("No report generated yet", "Export Report");
                return;
            }

            // Create JSON representation
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"originalQuery\": \"").append(escapeJson(generatedReport.getOriginalQuery())).append("\",\n");
            json.append("  \"timestamp\": \"").append(generatedReport.getTimestamp()).append("\",\n");
            json.append("  \"summary\": \"").append(escapeJson(generatedReport.getSummary())).append("\",\n");
            json.append("  \"discoveredElements\": ").append(generatedReport.getDiscoveredElements().size()).append(",\n");
            json.append("  \"codePieces\": ").append(generatedReport.getCodePieces().size()).append(",\n");
            json.append("  \"codingContext\": \"").append(escapeJson(generatedReport.getCodingContext())).append("\"\n");
            json.append("}");

            // Copy to clipboard
            java.awt.datatransfer.StringSelection stringSelection =
                    new java.awt.datatransfer.StringSelection(json.toString());
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(stringSelection, null);

            Messages.showInfoMessage("Report exported to clipboard as JSON", "Export Complete");
        }

        private String escapeJson(String text) {
            if (text == null) return "";
            return text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}