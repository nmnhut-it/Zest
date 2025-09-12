package com.zps.zest.browser;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
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
        if (this.browserPanel != null) {
            LOG.warn("Replacing existing browser panel with new one for project: " + project.getName());
        }
        this.browserPanel = panel;
        LOG.info("Browser panel registered with service for project: " + project.getName() + 
                 " (Panel class: " + panel.getClass().getSimpleName() + ")");
    }
    
    /**
     * Loads the specified URL in the browser.
     */
    public void loadUrl(String url) {
        if (browserPanel != null) {
            browserPanel.loadUrl(url);
        } else {
            LOG.warn("Cannot load URL, browser panel not registered. URL: " + url);
            // Try again after a short delay
            ApplicationManager.getApplication().invokeLater(() -> {
                if (browserPanel != null) {
                    browserPanel.loadUrl(url);
                } else {
                    LOG.error("Browser panel still not available after retry");
                }
            });
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
            // Try again after a short delay
            ApplicationManager.getApplication().invokeLater(() -> {
                if (browserPanel != null) {
                    browserPanel.executeJavaScript(script);
                } else {
                    LOG.error("Browser panel still not available after retry for JavaScript execution");
                }
            });
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
            // Try again after a short delay
            ApplicationManager.getApplication().invokeLater(() -> {
                if (browserPanel != null) {
                    browserPanel.sendTextToBrowser(text);
                } else {
                    LOG.error("Browser panel still not available after retry for sending text");
                }
            });
        }
    }
    
    /**
     * Gets the browser panel.
     */
    public WebBrowserPanel getBrowserPanel() {
        if (browserPanel == null) {
            LOG.debug("getBrowserPanel() returning null - no panel registered for project: " + project.getName() + 
                     " (This may be normal if using JCEFChatPanel instead of WebBrowserPanel)");
        } else {
            LOG.debug("getBrowserPanel() returning panel of type: " + browserPanel.getClass().getSimpleName() + 
                     " for project: " + project.getName());
        }
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
}
