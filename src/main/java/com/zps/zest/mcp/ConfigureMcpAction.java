package com.zps.zest.mcp;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the MCP configuration dialog.
 */
public class ConfigureMcpAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        McpConfigurationDialog dialog = new McpConfigurationDialog(project);
        dialog.show();
    }
}