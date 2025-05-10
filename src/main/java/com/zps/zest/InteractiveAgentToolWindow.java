package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory class for creating the enhanced interactive AI agent tool window.
 */
public class InteractiveAgentToolWindow implements ToolWindowFactory {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentToolWindow.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DumbService.getInstance(project).smartInvokeLater(()->{
            LOG.info("Creating enhanced interactive AI agent tool window for project: " + project.getName());

            try {
                // Create the enhanced chat panel
                InteractiveAgentPanel agentPanel = new InteractiveAgentPanel(project);

                // Create content and add it to the tool window
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(agentPanel.getContent(), "AI Assistant", false);
                toolWindow.getContentManager().addContent(content);

                // Register the panel with the service
                InteractiveAgentService.getInstance(project).registerPanel(agentPanel);

                LOG.info("Enhanced interactive AI agent tool window created successfully");
            } catch (Exception e) {
                LOG.error("Error creating enhanced tool window content", e);
            }
        });

    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // Configure tool window options
        toolWindow.setTitle("ZPS Agent");
        toolWindow.setStripeTitle("ZPS Agent");
        toolWindow.setIcon(com.intellij.icons.AllIcons.Nodes.Plugin);
    }
}