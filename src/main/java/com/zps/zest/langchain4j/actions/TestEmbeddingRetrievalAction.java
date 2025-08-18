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
        private JCheckBox useAdvancedRAGCheckbox;
        private JCheckBox useQueryExpansionCheckbox;
        private JCheckBox useMultiRetrievalCheckbox;
        private JButton testEmbeddingButton;
        private JButton testRetrievalButton;
        private JButton indexStatusButton;
        private JButton reindexButton;
        private JButton incrementalUpdateButton;
        private JButton cleanupButton;
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
            
            // Advanced RAG options panel
            JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            optionsPanel.setBorder(new TitledBorder("Advanced RAG Options"));
            
            useAdvancedRAGCheckbox = new JCheckBox("Use Advanced RAG", true);
            useAdvancedRAGCheckbox.setToolTipText("Enable query transformation, multi-retrieval, and contextual compression");
            optionsPanel.add(useAdvancedRAGCheckbox);
            
            useQueryExpansionCheckbox = new JCheckBox("Query Expansion", true);
            useQueryExpansionCheckbox.setToolTipText("Expand queries into multiple variations for better coverage");
            optionsPanel.add(useQueryExpansionCheckbox);
            
            useMultiRetrievalCheckbox = new JCheckBox("Multi-source Retrieval", true);
            useMultiRetrievalCheckbox.setToolTipText("Retrieve from specialized content sources (Java, config, test files)");
            optionsPanel.add(useMultiRetrievalCheckbox);
            
            // Container for options and buttons
            JPanel bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(optionsPanel, BorderLayout.NORTH);
            
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
            
            reindexButton = new JButton("Force Re-index");
            reindexButton.addActionListener(e -> forceReindex());
            buttonPanel.add(reindexButton);
            
            incrementalUpdateButton = new JButton("Incremental Update");
            incrementalUpdateButton.addActionListener(e -> forceIncrementalUpdate());
            buttonPanel.add(incrementalUpdateButton);
            
            cleanupButton = new JButton("Memory Cleanup");
            cleanupButton.addActionListener(e -> forceCleanup());
            buttonPanel.add(cleanupButton);
            
            bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
            panel.add(bottomPanel, BorderLayout.SOUTH);
            
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
            appendResult("Query: " + query + "\n");
            
            if (useAdvancedRAGCheckbox.isSelected()) {
                appendResult("Mode: Advanced RAG (Query Expansion: " + useQueryExpansionCheckbox.isSelected() + 
                           ", Multi-Retrieval: " + useMultiRetrievalCheckbox.isSelected() + ")\n\n");
            } else {
                appendResult("Mode: Basic Hybrid Search\n\n");
            }

            CompletableFuture.supplyAsync(() -> {
                try {
                    // Test context retrieval with advanced RAG if enabled
                    long startTime = System.currentTimeMillis();
                    ZestLangChain4jService.RetrievalResult result;
                    
                    if (useAdvancedRAGCheckbox.isSelected()) {
                        result = langChainService.retrieveContextAdvanced(
                            query, 
                            5, 
                            0.3, 
                            null, // No conversation history for testing
                            useQueryExpansionCheckbox.isSelected(),
                            useMultiRetrievalCheckbox.isSelected()
                        ).get(45, TimeUnit.SECONDS); // Longer timeout for advanced RAG
                    } else {
                        result = langChainService.retrieveContext(query, 5, 0.3).get(30, TimeUnit.SECONDS);
                    }
                    
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
                    status.append("🔧 Services Status:\n");
                    status.append("  LangChain Service: ").append(langChainService != null ? "✅ Available" : "❌ Not available").append("\n");
                    status.append("  Embedding Service: ").append(embeddingService != null ? "✅ Available" : "❌ Not available").append("\n");
                    
                    if (langChainService != null) {
                        int indexedChunks = langChainService.getIndexedChunkCount();
                        status.append("\n📚 Vector Store Status:\n");
                        status.append("  Indexed chunks: ").append(indexedChunks).append("\n");
                        status.append("  Index ready: ").append(indexedChunks > 0 ? "✅ Yes" : "❌ No - Index is empty!").append("\n");
                        
                        if (indexedChunks == 0) {
                            status.append("  ⚠️ The index is empty. Click 'Force Re-index' to populate it.\n");
                        }
                    }
                    
                    if (embeddingService != null) {
                        EmbeddingService.EmbeddingCacheStats stats = embeddingService.getCacheStats();
                        status.append("\n🗃️ Embedding Cache:\n");
                        status.append("  Cached embeddings: ").append(stats.getCacheSize()).append("/").append(stats.getMaxCacheSize()).append("\n");
                        status.append("  Cache utilization: ").append(String.format("%.1f%%", stats.getCacheUtilization() * 100)).append("\n");
                        status.append("  Memory usage: ").append(String.format("%.2f MB", stats.getCacheSizeMB())).append("\n");
                    }
                    
                    // Try a simple retrieval to check if index is working
                    if (langChainService != null) {
                        try {
                            ZestLangChain4jService.RetrievalResult testResult = langChainService.retrieveContext("test", 1, 0.5).get(10, TimeUnit.SECONDS);
                            status.append("\n🔍 Retrieval Test:\n");
                            status.append("  Status: ").append(testResult.isSuccess() ? "✅ Working" : "⚠️ May not be ready").append("\n");
                            if (testResult.isSuccess()) {
                                status.append("  Test query found: ").append(testResult.getItems().size()).append(" items\n");
                                if (testResult.getItems().size() > 0) {
                                    status.append("  Sample result: ").append(testResult.getItems().get(0).getTitle()).append("\n");
                                }
                            } else {
                                status.append("  Error: ").append(testResult.getMessage()).append("\n");
                            }
                        } catch (Exception e) {
                            status.append("\n🔍 Retrieval Test: ❌ Error - ").append(e.getMessage()).append("\n");
                        }
                    }
                    
                    // Add incremental indexing information
                    if (langChainService != null) {
                        long lastUpdate = langChainService.getLastIncrementalUpdateTime();
                        status.append("\n🔄 Incremental Updates:\n");
                        if (lastUpdate > 0) {
                            long timeSince = System.currentTimeMillis() - lastUpdate;
                            long minutes = timeSince / (60 * 1000);
                            status.append("  Last update: ").append(minutes).append(" minutes ago\n");
                        } else {
                            status.append("  Last update: Never\n");
                        }
                        status.append("  Auto-update: ✅ Every 5 minutes\n");
                        status.append("  Change tracking: ✅ ProjectChangesTracker integrated\n");
                        status.append("  Memory cleanup: ✅ Every 30 minutes\n");
                        
                        // Show memory management info
                        int chunks = langChainService.getIndexedChunkCount();
                        status.append("\n🧠 Memory Management:\n");
                        status.append("  Current chunks: ").append(chunks).append("\n");
                        status.append("  Cleanup threshold: ").append("45,000 chunks\n");
                        status.append("  Max capacity: ").append("50,000 chunks\n");
                        
                        if (chunks > 45000) {
                            status.append("  ⚠️ Approaching cleanup threshold!\n");
                        } else if (chunks > 50000) {
                            status.append("  🚨 Over capacity - cleanup needed!\n");
                        } else {
                            double usage = (chunks / 50000.0) * 100;
                            status.append("  Memory usage: ").append(String.format("%.1f%%", usage)).append("\n");
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
                reindexButton.setEnabled(!running);
                incrementalUpdateButton.setEnabled(!running);
                cleanupButton.setEnabled(!running);
            });
        }

        private void forceReindex() {
            int result = Messages.showYesNoDialog(
                project,
                "This will clear the current index and re-index all code files in the project.\n" +
                "This may take several minutes depending on project size.\n\n" +
                "Continue?",
                "Force Re-index",
                Messages.getQuestionIcon()
            );
            
            if (result != Messages.YES) {
                return;
            }

            setProgress(true, "Force re-indexing codebase...");
            appendResult("=== Force Re-indexing Codebase ===\n");
            appendResult("Starting fresh indexing process...\n\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // Force re-index by calling indexCodebase directly
                    boolean success = langChainService.indexCodebase().get(300, TimeUnit.SECONDS); // 5 minute timeout
                    
                    long duration = System.currentTimeMillis() - startTime;
                    
                    StringBuilder indexResult = new StringBuilder();
                    indexResult.append(success ? "✅ Re-indexing completed successfully!\n" : "❌ Re-indexing failed!\n");
                    indexResult.append("Duration: ").append(duration).append("ms (").append(duration / 1000).append(" seconds)\n");
                    
                    if (success) {
                        int chunks = langChainService.getIndexedChunkCount();
                        indexResult.append("Total indexed chunks: ").append(chunks).append("\n");
                        
                        if (chunks == 0) {
                            indexResult.append("⚠️ Warning: No chunks were indexed. Check logs for issues.\n");
                        }
                    }
                    
                    return indexResult.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error during force re-index", ex);
                    return "❌ Re-indexing failed with error: " + ex.getMessage() + "\n";
                }
            }).thenAccept(indexingResult -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(indexingResult);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Re-indexing completed");
                });
            });
        }
        
        private void forceIncrementalUpdate() {
            setProgress(true, "Running incremental update...");
            appendResult("=== Force Incremental Update ===\n");
            appendResult("Checking for modified files and updating index...\n\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // Force incremental update
                    int newChunks = langChainService.forceIncrementalUpdate().get(60, TimeUnit.SECONDS);
                    
                    long duration = System.currentTimeMillis() - startTime;
                    
                    StringBuilder updateResult = new StringBuilder();
                    updateResult.append("✅ Incremental update completed!\n");
                    updateResult.append("Duration: ").append(duration).append("ms (").append(duration / 1000).append(" seconds)\n");
                    updateResult.append("New chunks added: ").append(newChunks).append("\n");
                    
                    if (newChunks == 0) {
                        updateResult.append("ℹ️ No changes detected or all changes already indexed.\n");
                    } else {
                        updateResult.append("🎉 Successfully updated index with recent changes!\n");
                    }
                    
                    // Show updated stats
                    int totalChunks = langChainService.getIndexedChunkCount();
                    updateResult.append("Total indexed chunks: ").append(totalChunks).append("\n");
                    
                    return updateResult.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error during force incremental update", ex);
                    return "❌ Incremental update failed with error: " + ex.getMessage() + "\n";
                }
            }).thenAccept(result -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(result);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Incremental update completed");
                });
            });
        }
        
        private void forceCleanup() {
            int result = Messages.showYesNoDialog(
                project,
                "This will clean up old chunks from the vector store to manage memory usage.\n" +
                "Chunks older than 7 days may be removed if the store is approaching capacity.\n\n" +
                "Continue?",
                "Memory Cleanup",
                Messages.getQuestionIcon()
            );
            
            if (result != Messages.YES) {
                return;
            }

            setProgress(true, "Running memory cleanup...");
            appendResult("=== Memory Cleanup ===\n");
            appendResult("Cleaning up vector store to manage memory usage...\n\n");

            CompletableFuture.supplyAsync(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    int beforeCount = langChainService.getIndexedChunkCount();
                    
                    // Force cleanup using the public method
                    int freed = langChainService.forceCleanup().get(120, TimeUnit.SECONDS);
                    
                    int afterCount = langChainService.getIndexedChunkCount();
                    long duration = System.currentTimeMillis() - startTime;
                    
                    StringBuilder cleanupResult = new StringBuilder();
                    cleanupResult.append("✅ Memory cleanup completed!\n");
                    cleanupResult.append("Duration: ").append(duration).append("ms (").append(duration / 1000).append(" seconds)\n");
                    cleanupResult.append("Chunks before: ").append(beforeCount).append("\n");
                    cleanupResult.append("Chunks after: ").append(afterCount).append("\n");
                    cleanupResult.append("Chunks freed: ").append(freed).append("\n");
                    
                    if (freed > 0) {
                        cleanupResult.append("🎉 Memory cleanup successful!\n");
                    } else {
                        cleanupResult.append("ℹ️ No cleanup needed - store is within limits.\n");
                    }
                    
                    double usage = (afterCount / 50000.0) * 100;
                    cleanupResult.append("Memory usage: ").append(String.format("%.1f%%", usage)).append("\n");
                    
                    return cleanupResult.toString();
                    
                } catch (Exception ex) {
                    LOG.error("Error during force cleanup", ex);
                    return "❌ Cleanup failed with error: " + ex.getMessage() + "\n";
                }
            }).thenAccept(r2 -> {
                SwingUtilities.invokeLater(() -> {
                    appendResult(r2);
                    appendResult("\n" + "=".repeat(50) + "\n\n");
                    setProgress(false, "Memory cleanup completed");
                });
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