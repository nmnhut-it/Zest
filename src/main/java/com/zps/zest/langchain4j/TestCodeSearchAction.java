package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.zps.zest.langchain4j.index.StructuralIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Action to test the hybrid code search functionality.
 */
public class TestCodeSearchAction extends AnAction {
    
    public TestCodeSearchAction() {
        super("Test Hybrid Code Search", 
              "Search for code using the hybrid index system", 
              null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Show search dialog
        SearchDialog dialog = new SearchDialog(project);
        if (dialog.showAndGet()) {
            String query = dialog.getQuery();
            int maxResults = dialog.getMaxResults();
            
            performSearch(project, query, maxResults);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
    
    private void performSearch(Project project, String query, int maxResults) {
        CodeSearchUtility searchUtility = project.getService(CodeSearchUtility.class);
        
        // Start search
        CompletableFuture<List<CodeSearchUtility.EnrichedSearchResult>> searchFuture = 
            searchUtility.searchRelatedCode(query, maxResults);
        
        searchFuture.thenAccept(results -> {
            SwingUtilities.invokeLater(() -> {
                if (results.isEmpty()) {
                    Messages.showInfoMessage(
                        project,
                        "No results found for: " + query + "\n\n" +
                        "Make sure you've indexed your project first:\n" +
                        "Tools → Index Project for Function-Level Search",
                        "No Results"
                    );
                } else {
                    showResults(project, query, results);
                }
            });
        }).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
                Messages.showErrorDialog(
                    project,
                    "Search failed: " + ex.getMessage(),
                    "Error"
                );
            });
            return null;
        });
    }
    
    private void showResults(Project project, String query, List<CodeSearchUtility.EnrichedSearchResult> results) {
        // Build results text
        StringBuilder sb = new StringBuilder();
        sb.append("Hybrid Search Results for: \"").append(query).append("\"\n");
        sb.append("Found ").append(results.size()).append(" results\n\n");
        sb.append("=".repeat(80)).append("\n\n");
        
        int resultNum = 1;
        for (CodeSearchUtility.EnrichedSearchResult result : results) {
            sb.append("### Result ").append(resultNum++).append(" ###\n");
            sb.append("ID: ").append(result.getId()).append("\n");
            sb.append("Score: ").append(String.format("%.3f", result.getScore())).append("\n");
            sb.append("File: ").append(result.getFilePath()).append("\n");
            
            // Show search sources
            sb.append("Found via: ");
            result.getSources().forEach(source -> sb.append(source.getDisplayName()).append(" "));
            sb.append("\n\n");
            
            // Show content preview
            String content = result.getContent();
            if (content.length() > 400) {
                content = content.substring(0, 400) + "...";
            }
            sb.append("Content:\n").append(content).append("\n\n");
            
            // Show structural relationships
            if (!result.getStructuralRelationships().isEmpty()) {
                sb.append("Structural Relationships:\n");
                for (Map.Entry<StructuralIndex.RelationType, List<String>> entry : 
                     result.getStructuralRelationships().entrySet()) {
                    StructuralIndex.RelationType relType = entry.getKey();
                    List<String> related = entry.getValue();
                    
                    if (!related.isEmpty()) {
                        sb.append("  ").append(relType.getDisplayName()).append(":\n");
                        for (String relatedId : related) {
                            sb.append("    - ").append(relatedId).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            
            // Show similar code
            if (!result.getSimilarCode().isEmpty()) {
                sb.append("Similar Code:\n");
                for (CodeSearchUtility.SimilarCode similar : result.getSimilarCode()) {
                    sb.append("  - ").append(similar.getId())
                      .append(" (similarity: ").append(String.format("%.3f", similar.getSimilarity()))
                      .append(") - ").append(similar.getReason()).append("\n");
                }
                sb.append("\n");
            }
            
            // Show related code in same file
            if (!result.getRelatedInFile().isEmpty()) {
                sb.append("Related in same file:\n");
                for (CodeSearchUtility.RelatedInFile related : result.getRelatedInFile()) {
                    sb.append("  - ").append(related.getId())
                      .append(" (").append(related.getType())
                      .append(", score: ").append(String.format("%.3f", related.getScore()))
                      .append(")\n");
                }
                sb.append("\n");
            }
            
            sb.append("-".repeat(80)).append("\n\n");
        }
        
        // Show results in dialog
        ResultsDialog resultsDialog = new ResultsDialog(project, sb.toString());
        resultsDialog.show();
    }
    
    /**
     * Dialog for entering search parameters.
     */
    private static class SearchDialog extends DialogWrapper {
        private JTextField queryField;
        private JSpinner maxResultsSpinner;
        
        protected SearchDialog(@Nullable Project project) {
            super(project);
            setTitle("Hybrid Code Search");
            init();
        }
        
        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            
            // Query field
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel("Search Query:"), gbc);
            
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            queryField = new JTextField(30);
            panel.add(queryField, gbc);
            
            // Max results spinner
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel("Max Results:"), gbc);
            
            gbc.gridx = 1;
            maxResultsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
            panel.add(maxResultsSpinner, gbc);
            
            // Help text
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            JTextArea helpText = new JTextArea(
                "The hybrid search combines three strategies:\n" +
                "• Name Index: Finds exact matches (e.g., 'add score' finds 'addScore')\n" +
                "• Semantic Index: Finds conceptually similar code\n" +
                "• Structural Index: Finds related code through usage patterns\n\n" +
                "Try queries like:\n" +
                "• 'add score' - finds methods with camelCase matching\n" +
                "• 'validate input' - finds semantically similar validation code\n" +
                "• 'calls database' - finds methods that call database operations"
            );
            helpText.setEditable(false);
            helpText.setBackground(panel.getBackground());
            helpText.setFont(helpText.getFont().deriveFont(Font.ITALIC));
            panel.add(helpText, gbc);
            
            return panel;
        }
        
        @Override
        public JComponent getPreferredFocusedComponent() {
            return queryField;
        }
        
        public String getQuery() {
            return queryField.getText().trim();
        }
        
        public int getMaxResults() {
            return (Integer) maxResultsSpinner.getValue();
        }
    }
    
    /**
     * Dialog for showing search results.
     */
    private static class ResultsDialog extends DialogWrapper {
        private final String results;
        
        protected ResultsDialog(@Nullable Project project, String results) {
            super(project);
            this.results = results;
            setTitle("Hybrid Code Search Results");
            setModal(false);
            init();
        }
        
        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JBTextArea textArea = new JBTextArea(results);
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            
            JBScrollPane scrollPane = new JBScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(900, 700));
            
            return scrollPane;
        }
    }
}
