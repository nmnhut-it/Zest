package com.zps.zest.browser;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
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
    
    /**
     * Navigates to the default chat URL if needed.
     * This method checks the current URL and loads the default URL if necessary.
     * 
     * @return true if navigation was performed, false otherwise
     */
    public boolean navigateToDefaultUrlIfNeeded() {
        if (browserPanel == null) {
            LOG.warn("Cannot navigate to default URL, browser panel not registered");
            return false;
        }
        
        String currentUrl = browserPanel.getCurrentUrl();
        
        // Navigate to default URL if the current URL is empty, about:blank, or not the chat URL
        String url = ConfigurationManager.getInstance(project).getApiUrl().replace("/api/chat/completions", "");
        if (currentUrl == null || currentUrl.isEmpty() ||
            "about:blank".equals(currentUrl) || 
            !currentUrl.contains(url)) {
            
            LOG.info("Navigating to default chat URL: " + url);
            browserPanel.loadUrl(url);
            return true;
        }
        
        return false;
    }
    
    /**
     * Shows the browser panel (opens the tool window).
     */
    public void showBrowserPanel() {
        if (browserPanel != null) {
            browserPanel.showToolWindow();
        } else {
            LOG.warn("Cannot show browser panel, not registered");
        }
    }
    
    /**
     * Loads a resource from the plugin's resources.
     * @param resourcePath The path to the resource (e.g., "/html/workflowBuilder.html")
     */
    public void loadResource(String resourcePath) {
        if (browserPanel != null && browserPanel.getBrowserManager() != null) {
            browserPanel.getBrowserManager().loadHTMLFromResource(resourcePath);
        } else {
            LOG.warn("Cannot load resource, browser panel not registered");
        }
    }
}
