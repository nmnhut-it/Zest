package com.zps.zest.browser;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Service for managing web browser functionality across the plugin.
 */
@Service(Service.Level.PROJECT)
public final class WebBrowserService {
    private static final Logger LOG = Logger.getInstance(WebBrowserService.class);
    
    private final Project project;
    private WebBrowserPanel browserPanel;
    
    /**
     * Creates a new web browser service.
     */
    public WebBrowserService(Project project) {
        this.project = project;
        LOG.info("WebBrowserService created for project: " + project.getName());
    }
    
    /**
     * Gets the service instance for the specified project.
     */
    public static WebBrowserService getInstance(@NotNull Project project) {
        return project.getService(WebBrowserService.class);
    }
    
    /**
     * Registers a browser panel with this service.
     */
    public void registerPanel(WebBrowserPanel panel) {
        this.browserPanel = panel;
        LOG.info("Browser panel registered with service");
    }
    
    /**
     * Loads the specified URL in the browser.
     */
    public void loadUrl(String url) {
        if (browserPanel != null) {
            browserPanel.loadUrl(url);
        } else {
            LOG.warn("Cannot load URL, browser panel not registered");
        }
    }
    
    /**
     * Executes JavaScript in the browser.
     */
    public void executeJavaScript(String script) {
        if (browserPanel != null) {
            browserPanel.executeJavaScript(script);
        } else {
            LOG.warn("Cannot execute JavaScript, browser panel not registered");
        }
    }
    
    /**
     * Sends text to the browser.
     */
    public void sendTextToBrowser(String text) {
        if (browserPanel != null) {
            browserPanel.sendTextToBrowser(text);
        } else {
            LOG.warn("Cannot send text to browser, browser panel not registered");
        }
    }
    
    /**
     * Gets the browser panel.
     */
    public WebBrowserPanel getBrowserPanel() {
        return browserPanel;
    }
}
