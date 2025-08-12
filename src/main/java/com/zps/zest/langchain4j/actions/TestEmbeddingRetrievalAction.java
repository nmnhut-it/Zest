package com.zps.zest.langchain4j.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.retrieval.core.EmbeddingService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Action to test the embedding service and context retrieval functionality
 */
public class TestEmbeddingRetrievalAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestEmbeddingRetrievalAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project found", "Error");
            return;
        }

        TestDialog dialog = new TestDialog(project);
        dialog.show();
    }

    /**
     * Dialog for testing embedding and retrieval functionality
     */
    private static class TestDialog extends DialogWrapper {
        private final Project project;
        private final ZestLangChain4jService langChainService;
        private final EmbeddingService embeddingService;
        
        private JTextArea queryField;
        private JTextArea resultArea;
        private JButton testEmbeddingButton;
        private JButton testRetrievalButton;
        private JButton indexStatusButton;
        private JProgressBar progressBar;
        private JLabel statusLabel;

        protected TestDialog(@NotNull Project project) {
            super(project);
            this.project = project;
            this.langChainService = project.getService(ZestLangChain4jService.class);
            this.embeddingService = project.getService(EmbeddingService.class);
            
            setTitle("Test Embedding Service & Context Retrieval");
            setModal(false);
            setSize(800, 600);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel mainPanel = new JPanel(new BorderLayout());
            
            // Input panel
            JPanel inputPanel = createInputPanel();
            mainPanel.add(inputPanel, BorderLayout.NORTH);
            
            // Results panel
            JPanel resultsPanel = createResultsPanel();
            mainPanel.add(resultsPanel, BorderLayout.CENTER);
            
            // Status panel
            JPanel statusPanel = createStatusPanel();
            mainPanel.add(statusPanel, BorderLayout.SOUTH);
            
            return mainPanel;
        }

        private JPanel createInputPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(new TitledBorder("Test Query"));
            
            queryField = new JTextArea(3, 50);
            queryField.setText("method security validation user input");
            queryField.setLineWrap(true);
            queryField.setWrapStyleWord(true);
            
            JScrollPane scrollPane = new JScrollPane(queryField);
            panel.add(scrollPane, BorderLayout.CENTER);
            
            // Button panel
            JPanel buttonPanel = new JPanel(new FlowLayout());
            
            testEmbeddingButton = new JButton("Test Embedding");
            testEmbeddingButton.addActionListener(e -> testEmbedding());
            buttonPanel.add(testEmbeddingButton);
            
            testRetrievalButton = new JButton("Test Retrieval");
            testRetrievalButton.addActionListener(e -> testRetrieval());
            buttonPanel.add(testRetrievalButton);
            
            indexStatusButton = new JButton("Check Index Status");
            indexStatusButton.addActionListener(e -> checkIndexStatus());
            buttonPanel.add(indexStatusButton);
            
            panel.add(buttonPanel, BorderLayout.SOUTH);
            
            return panel;
        }

        private JPanel createResultsPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(new TitledBorder("Results"));
            
            resultArea = new JTextArea(20, 60);
            resultArea.setEditable(false);
            resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            
            JScrollPane scrollPane = new JScrollPane(resultArea);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
            
            panel.add(scrollPane, BorderLayout.CENTER);
            
            return panel;
        }

        private JPanel createStatusPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            
            statusLabel = new JLabel("Ready");
            progressBar = new JProgressBar();
            progressBar.setIndeterminate(false);
            progressBar.setVisible(false);
            
            panel.add(statusLabel, BorderLayout.WEST);
            panel.add(progressBar, BorderLayout.CENTER);
            
            return panel;
        }

        private void testEmbedding() {
            String query = queryField.getText().trim();
            if (query.isEmpty()) {
                Messages.showWarningDialog("Please enter a test query", "Warning");
                return;
            }

            setProgress(true, "Testing embedding generation...");
            appendResult("=== Testing Embedding Generation ===\n");
            appendResult("Query: " + query + "\n\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    // Test embedding generation
                    long startTime = System.currentTimeMillis();
                    float[] embedding = embeddingService.generateEmbedding(query).get(30, TimeUnit.SECONDS);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    StringBuilder result = new StringBuilder();
                    result.append("✅ Embedding generated successfully!\n");
                    result.append("Duration: ").append(duration).append("ms\n");
                    result.append("Dimensions: ").append(embedding != null ? embedding.length : 0).append("\n");
                    
                    if (embedding != null && embedding.length > 0) {
                        result.append("Sample values: [");
                        int sampleSize = Math.min(10, embedding.length);
                        for (int i = 0; i < sampleSize; i++) {
                            result.append(String.format("%.4f", embedding[i]));
                            if (i < sampleSize - 1) result.append(", ");
                        }
                        if (embedding.length > sampleSize) {
                            result.append(", ...");
                        }
                        result.append("]\n");
                        
                        // Calculate magnitude for validation
                        double magnitude = 0;
                        for (float value : embedding) {
                            magnitude += value * value;
                        }
                        magnitude = Math.sqrt(magnitude);
                        result.append("Vector magnitude: ").append(String.format("%.4f", magnitude)).append("\n");
                    }
                    
                    // Test cache stats
                    EmbeddingService.EmbeddingCacheStats stats = embeddingService.getCacheStats();
                    result.append("\nCache Statistics:\n");
                    result.append("  Cache size: ").append(stats.getCacheSize()).append("/").append(stats.getMaxCacheSize()).append("\n");
                    result.append("  Cache utilization: ").append(String.format("%.1f%%", stats.getCacheUtilization() * 100)).append("\n");
                    result.append("  Cache memory: ").append(String.format("%.2f MB", stats.getCacheSizeMB())).append("\n");
                    
                    return result.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error testing embedding", ex);
                    return "❌ Embedding test failed: " + ex.getMessage() + "\n";
                }
            }).thenAccept(result -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(result);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Embedding test completed");
                });
            });
        }

        private void testRetrieval() {
            String query = queryField.getText().trim();
            if (query.isEmpty()) {
                Messages.showWarningDialog("Please enter a test query", "Warning");
                return;
            }

            setProgress(true, "Testing context retrieval...");
            appendResult("=== Testing Context Retrieval ===\n");
            appendResult("Query: " + query + "\n\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    // Test context retrieval
                    long startTime = System.currentTimeMillis();
                    ZestLangChain4jService.RetrievalResult result = langChainService.retrieveContext(query, 5, 0.6).get(30, TimeUnit.SECONDS);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    StringBuilder output = new StringBuilder();
                    output.append("Duration: ").append(duration).append("ms\n");
                    output.append("Success: ").append(result.isSuccess()).append("\n");
                    output.append("Message: ").append(result.getMessage()).append("\n");
                    output.append("Items found: ").append(result.getItems().size()).append("\n\n");
                    
                    if (result.isSuccess() && !result.getItems().isEmpty()) {
                        output.append("Retrieved Context Items:\n");
                        output.append("-".repeat(40)).append("\n");
                        
                        for (int i = 0; i < result.getItems().size(); i++) {
                            ZestLangChain4jService.ContextItem item = result.getItems().get(i);
                            output.append(String.format("%d. %s (score: %.3f)\n", i + 1, item.getTitle(), item.getScore()));
                            
                            String content = item.getContent();
                            if (content.length() > 200) {
                                content = content.substring(0, 200) + "...";
                            }
                            output.append("   ").append(content.replace("\n", "\n   ")).append("\n\n");
                        }
                    } else if (!result.isSuccess()) {
                        output.append("❌ Retrieval failed: ").append(result.getMessage()).append("\n");
                    } else {
                        output.append("ℹ️ No relevant context found for the query\n");
                    }
                    
                    return output.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error testing retrieval", ex);
                    return "❌ Retrieval test failed: " + ex.getMessage() + "\n";
                }
            }).thenAccept(result -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(result);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Retrieval test completed");
                });
            });
        }

        private void checkIndexStatus() {
            setProgress(true, "Checking index status...");
            appendResult("=== Index Status ===\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    StringBuilder status = new StringBuilder();
                    
                    // Check if services are available
                    status.append("LangChain Service: ").append(langChainService != null ? "✅ Available" : "❌ Not available").append("\n");
                    status.append("Embedding Service: ").append(embeddingService != null ? "✅ Available" : "❌ Not available").append("\n");
                    
                    if (embeddingService != null) {
                        EmbeddingService.EmbeddingCacheStats stats = embeddingService.getCacheStats();
                        status.append("\nEmbedding Cache:\n");
                        status.append("  Cached embeddings: ").append(stats.getCacheSize()).append("\n");
                        status.append("  Max cache size: ").append(stats.getMaxCacheSize()).append("\n");
                        status.append("  Memory usage: ").append(String.format("%.2f MB", stats.getCacheSizeMB())).append("\n");
                    }
                    
                    // Try a simple retrieval to check if index is working
                    if (langChainService != null) {
                        try {
                            ZestLangChain4jService.RetrievalResult testResult = langChainService.retrieveContext("test", 1, 0.5).get(10, TimeUnit.SECONDS);
                            status.append("\nIndex Status: ").append(testResult.isSuccess() ? "✅ Working" : "⚠️ May not be ready").append("\n");
                            if (testResult.isSuccess()) {
                                status.append("  Test query returned ").append(testResult.getItems().size()).append(" items\n");
                            }
                        } catch (Exception e) {
                            status.append("\nIndex Status: ❌ Error during test - ").append(e.getMessage()).append("\n");
                        }
                    }
                    
                    return status.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error checking index status", ex);
                    return "❌ Status check failed: " + ex.getMessage() + "\n";
                }
            }).thenAccept(result -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(result);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Status check completed");
                });
            });
        }

        private void setProgress(boolean running, String status) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setVisible(running);
                progressBar.setIndeterminate(running);
                statusLabel.setText(status);
                
                // Disable buttons while running
                testEmbeddingButton.setEnabled(!running);
                testRetrievalButton.setEnabled(!running);
                indexStatusButton.setEnabled(!running);
            });
        }

        private void appendResult(String text) {
            SwingUtilities.invokeLater(() -> {
                resultArea.append(text);
                resultArea.setCaretPosition(resultArea.getDocument().getLength());
            });
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getCancelAction()};
        }
    }
}