package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the interactive AI agent window.
 */
public class OpenInteractiveAgentAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(OpenInteractiveAgentAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return super.getActionUpdateThread();
    }

    public OpenInteractiveAgentAction() {
        super("Open AI Assistant", "Open the interactive AI coding assistant", AllIcons.Actions.StartDebugger);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        try {
            // Get the service and show the window
            InteractiveAgentService service = InteractiveAgentService.getInstance(project);
            service.showAgentWindow();
            
            // Add a welcome message if this is the first time opening
            if (service.getChatHistory().isEmpty()) {
                service.addSystemMessage("Welcome to the AI Coding Assistant. How can I help you with your code today?");
            }
        } catch (Exception ex) {
            LOG.error("Error opening interactive agent window", ex);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only if a project is available
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}