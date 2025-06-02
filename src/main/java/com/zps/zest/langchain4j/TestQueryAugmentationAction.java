package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.agent.QueryAugmentationAgent;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to verify QueryAugmentationAgent functionality.
 */
public class TestQueryAugmentationAction extends AnAction {
    
    public TestQueryAugmentationAction() {
        super("Test Query Augmentation", "Tests the query augmentation functionality", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get test query from user
        String query = Messages.showInputDialog(
            project,
            "Enter a query to augment (e.g., 'show me all controllers', 'fix the service handler'):",
            "Test Query Augmentation",
            Messages.getQuestionIcon()
        );
        
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        try {
            // Get the augmentation service
            QueryAugmentationService service = project.getService(QueryAugmentationService.class);
            
            // Augment the query
            long startTime = System.currentTimeMillis();
            String augmented = service.augmentQuery(query);
            long duration = System.currentTimeMillis() - startTime;
            
            // Show results
            String message = String.format(
                "Original Query:\n%s\n\n" +
                "Augmented Context (%d ms):\n%s",
                query,
                duration,
                augmented.isEmpty() ? "(No augmentation generated)" : augmented
            );
            
            Messages.showMultilineInputDialog(
                project,
                message,
                "Query Augmentation Result",
                augmented,
                Messages.getInformationIcon(),
                null
            );
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                project,
                "Error during augmentation: " + ex.getMessage(),
                "Query Augmentation Error"
            );
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
