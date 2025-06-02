package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Quick action to test LangChain4j search functionality.
 * Allows users to input a query and see results immediately.
 */
public class QuickTestLangChain4j extends AnAction {
    
    public QuickTestLangChain4j() {
        super("Quick Search with LangChain4j", 
              "Search project code using local embeddings", 
              null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get search query from user
        String query = Messages.showInputDialog(
            project,
            "Enter search query:",
            "LangChain4j Quick Search",
            Messages.getQuestionIcon()
        );
        
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        // Perform search
        RagService ragService = project.getService(RagService.class);
        
        try {
            CompletableFuture<List<RagService.SearchResult>> futureResults = 
                ragService.search(query, 5);
            
            List<RagService.SearchResult> results = futureResults.join();
            
            if (results.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "No results found for: " + query + "\n\n" +
                    "Make sure you've indexed some files first using:\n" +
                    "Tools → Test LangChain4j Embeddings → Search Test",
                    "No Results"
                );
                return;
            }
            
            // Format results
            StringBuilder message = new StringBuilder();
            message.append("Found ").append(results.size())
                   .append(" results for: \"").append(query).append("\"\n\n");
            
            for (int i = 0; i < results.size(); i++) {
                RagService.SearchResult result = results.get(i);
                message.append(i + 1).append(". Score: ")
                       .append(String.format("%.3f", result.getScore())).append("\n");
                
                // Add file info if available
                if (result.getMetadata().containsKey("file_name")) {
                    message.append("   File: ")
                           .append(result.getMetadata().get("file_name")).append("\n");
                }
                
                // Add content preview
                String content = result.getContent();
                if (content.length() > 100) {
                    content = content.substring(0, 100) + "...";
                }
                message.append("   ").append(content.replace("\n", " ")).append("\n\n");
            }
            
            // Show results
            Messages.showInfoMessage(project, message.toString(), "Search Results");
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                project,
                "Search failed: " + ex.getMessage(),
                "Error"
            );
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
