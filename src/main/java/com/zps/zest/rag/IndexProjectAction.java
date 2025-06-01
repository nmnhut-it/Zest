package com.zps.zest.rag;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Action to index the project for RAG.
 */
public class IndexProjectAction extends AnAction {
    
    public IndexProjectAction() {
        super("Index Project for RAG", "Index all project code signatures for retrieval", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        int result = Messages.showYesNoDialog(
            project,
            "This will index all Java and Kotlin files in your project.\n" +
            "The process may take a few minutes for large projects.\n\n" +
            "Do you want to proceed?",
            "Index Project",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            RagAgent ragAgent = RagAgent.getInstance(project);
            ragAgent.indexProject(true);
            
            Messages.showInfoMessage(
                "Project indexing started in background.\n" +
                "You can continue working while indexing proceeds.",
                "Indexing Started"
            );
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
