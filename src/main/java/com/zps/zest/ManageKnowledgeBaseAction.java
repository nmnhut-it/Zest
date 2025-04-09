package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Action for managing the knowledge base.
 * This action opens a dialog that allows users to add, remove, and manage files in the knowledge base.
 */
public class ManageKnowledgeBaseAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ManageKnowledgeBaseAction.class);

    public ManageKnowledgeBaseAction() {
        super("Manage Knowledge Base", "Add and manage files in the knowledge base", AllIcons.Nodes.PpLib);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only enable if we have a project
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        try {
            ConfigurationManager config = new ConfigurationManager(project);

            // Check if RAG is enabled
            if (!config.isRagEnabled()) {
                int result = Messages.showYesNoDialog(
                        project,
                        "RAG (Retrieval Augmented Generation) is currently disabled. Would you like to enable it?",
                        "RAG is Disabled",
                        "Enable RAG",
                        "Cancel",
                        Messages.getQuestionIcon()
                );

                if (result == Messages.YES) {
                    config.setRagEnabled(true);
                } else {
                    return;
                }
            }

            // Check if we have API URL and auth token
            String apiUrl = config.getOpenWebUIRagEndpoint();
            String authToken = config.getAuthToken();

            if (apiUrl == null || apiUrl.isEmpty()) {
                Messages.showErrorDialog(
                        project,
                        "API URL is not configured. Please set it in the plugin configuration file.",
                        "Configuration Error"
                );
                return;
            }

            if (authToken == null || authToken.isEmpty()) {
                Messages.showErrorDialog(
                        project,
                        "Authentication token is not configured. Please set it in the plugin configuration file.",
                        "Configuration Error"
                );
                return;
            }

            // Create knowledge base manager
            KnowledgeBaseManager kbManager = new KnowledgeBaseManager(apiUrl, authToken, project);

            // Show dialog
            KnowledgeBaseManagerDialog dialog = new KnowledgeBaseManagerDialog(project, kbManager);
            dialog.show();

        } catch (Exception ex) {
            LOG.error("Error opening knowledge base manager", ex);
            Messages.showErrorDialog(
                    project,
                    "Error opening knowledge base manager: " + ex.getMessage(),
                    "Error"
            );
        }
    }
}