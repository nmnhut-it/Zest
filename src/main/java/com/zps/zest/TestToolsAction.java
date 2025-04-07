package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action for testing agent tools directly.
 * This can be registered as an action or added to a toolbar.
 */
public class TestToolsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestToolsAction.class);
    
    public TestToolsAction() {
        super("Test Tools", "Test agent tools directly", AllIcons.Debugger.Console);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        try {
            SimpleToolsDialog.showDialog(project);
            
            // If you need to notify the chat panel, you can do it through the service
            InteractiveAgentService service = InteractiveAgentService.getInstance(project);
            service.addSystemMessage("Tools testing panel was opened.");
        } catch (Exception ex) {
            LOG.error("Error opening tools dialog", ex);
        }
    }
}