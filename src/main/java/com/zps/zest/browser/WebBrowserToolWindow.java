package com.zps.zest.browser;

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
 * Factory class for creating the web browser tool window.
 */
public class WebBrowserToolWindow implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(WebBrowserToolWindow.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Initialize DevTools registry settings
        DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();
        
        DumbService.getInstance(project).smartInvokeLater(() -> {
            LOG.info("Creating web browser tool window for project: " + project.getName());

            try {
                // Create the browser panel
                WebBrowserPanel browserPanel = new WebBrowserPanel(project);

                // Create content and add it to the tool window
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(browserPanel.getComponent(), "", false);
                toolWindow.getContentManager().addContent(content);

                // Register the panel with the service
                WebBrowserService.getInstance(project).registerPanel(browserPanel);
                
                // Integrate with AI assistant
                BrowserIntegrator.integrate(project);
                
                // Log debug port information
                int debugPort = DevToolsRegistryManager.getInstance().getDebugPort();
                LOG.info("JCEF browser initialized with debug port: " + debugPort);
                LOG.info("You can debug the browser using Chrome DevTools at: http://localhost:" + debugPort);

                LOG.info("Web browser tool window created successfully");
            } catch (Exception e) {
                LOG.error("Error creating web browser tool window content", e);
            }
        });
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // Configure tool window options
        toolWindow.setTitle("ZPS Chat");
        toolWindow.setStripeTitle("ZPS Chat");
        toolWindow.setIcon(com.intellij.icons.AllIcons.General.Web);
    }
}
