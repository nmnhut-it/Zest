package com.zps.zest.browser;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Service for managing JCEF browser instances per project.
 * Provides singleton browser manager instances to avoid resource waste from creating new browsers every time.
 */
@Service(Service.Level.PROJECT)
public final class JCEFBrowserService implements Disposable {
    private static final Logger LOG = Logger.getInstance(JCEFBrowserService.class);
    
    private final Project project;
    private JCEFBrowserManager browserManager;
    
    public JCEFBrowserService(Project project) {
        this.project = project;
        LOG.info("JCEFBrowserService created for project: " + project.getName());
    }
    
    /**
     * Gets the service instance for the specified project.
     */
    public static JCEFBrowserService getInstance(@NotNull Project project) {
        return project.getService(JCEFBrowserService.class);
    }
    
    /**
     * Gets the browser manager, creating it if necessary (lazy initialization).
     */
    @NotNull
    public synchronized JCEFBrowserManager getBrowserManager() {
        if (browserManager == null) {
            LOG.info("Creating new JCEFBrowserManager for project: " + project.getName());
            browserManager = new JCEFBrowserManager(project);
            
            // Register for disposal when this service is disposed
            Disposer.register(this, browserManager);
            
            LOG.info("JCEFBrowserManager created and registered for disposal");
        } else {
            LOG.debug("Reusing existing JCEFBrowserManager for project: " + project.getName());
        }
        
        return browserManager;
    }
    
    /**
     * Gets the existing browser manager without creating a new one.
     * Returns null if no browser manager has been created yet.
     */
    @Nullable
    public JCEFBrowserManager getExistingBrowserManager() {
        return browserManager;
    }
    
    /**
     * Updates the auth token in the browser when it changes in configuration.
     * This provides the reverse sync from ConfigurationManager to browser.
     */
    public void updateAuthTokenInBrowser(String newToken) {
        if (browserManager != null) {
            browserManager.updateAuthTokenInBrowser(newToken);
        } else {
            LOG.debug("No browser manager exists yet, auth token will be injected when browser is created");
        }
    }
    
    /**
     * Executes JavaScript in the browser if available.
     */
    public void executeJavaScript(String script) {
        if (browserManager != null) {
            browserManager.executeJavaScript(script);
        } else {
            LOG.warn("Cannot execute JavaScript, browser manager not created yet");
        }
    }
    
    /**
     * Loads URL in the browser if available.
     */
    public void loadURL(String url) {
        if (browserManager != null) {
            browserManager.loadURL(url);
        } else {
            LOG.warn("Cannot load URL, browser manager not created yet");
        }
    }
    
    /**
     * Checks if a browser manager has been created.
     */
    public boolean hasBrowserManager() {
        return browserManager != null;
    }
    
    @Override
    public void dispose() {
        LOG.info("Disposing JCEFBrowserService for project: " + project.getName());
        
        // browserManager will be disposed automatically through Disposer.register()
        // but we set it to null to prevent further access
        browserManager = null;
        
        LOG.info("JCEFBrowserService disposed");
    }
}