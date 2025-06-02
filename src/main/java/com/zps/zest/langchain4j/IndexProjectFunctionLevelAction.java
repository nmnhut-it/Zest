package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Action to index the project at function level for semantic search.
 */
public class IndexProjectFunctionLevelAction extends AnAction {
    
    public IndexProjectFunctionLevelAction() {
        super("Index Project for Function-Level Search", 
              "Index all functions and classes for semantic code search", 
              null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        FunctionLevelIndexService indexService = project.getService(FunctionLevelIndexService.class);
        Map<String, Object> currentStats = indexService.getStatistics();
        
        String message = "This will index all Java and Kotlin files at function level.\n" +
                        "Each method, class, and field will be indexed for semantic search.\n\n" +
                        "Current index statistics:\n" +
                        "- Files indexed: " + currentStats.get("total_files_indexed") + "\n" +
                        "- Functions indexed: " + currentStats.get("total_functions_indexed") + "\n" +
                        "- Classes indexed: " + currentStats.get("total_classes_indexed") + "\n\n" +
                        "Do you want to proceed?";
        
        int result = Messages.showYesNoCancelDialog(
            project,
            message,
            "Index Project for Function-Level Search",
            "Index All",
            "Re-index Changed Files",
            "Cancel",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            // Full re-index
            indexService.clearIndex();
            indexService.indexProject(true);
            showIndexingStartedMessage(project, true);
        } else if (result == Messages.NO) {
            // Incremental index (only changed files)
            indexService.indexProject(false);
            showIndexingStartedMessage(project, false);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
    
    private void showIndexingStartedMessage(Project project, boolean fullReindex) {
        String mode = fullReindex ? "Full re-indexing" : "Incremental indexing";
        Messages.showInfoMessage(
            project,
            mode + " started in background.\n\n" +
            "You can continue working while indexing proceeds.\n" +
            "Use 'Test Two-Phase Code Search' to search when ready.",
            "Function-Level Indexing Started"
        );
    }
}
