package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project; 
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

public class ManageKnowledgeBaseAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        ConfigurationManager config = new ConfigurationManager(project);
        KnowledgeBaseManager kbManager = new KnowledgeBaseManager(
            config.getOpenWebUIRagEndpoint(),
            config.getAuthToken()
        );
        
        KnowledgeBaseManagerDialog dialog = new KnowledgeBaseManagerDialog(project, kbManager);
        dialog.show();
    }
}