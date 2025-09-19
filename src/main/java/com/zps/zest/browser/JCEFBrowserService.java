package com.zps.zest.browser;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Collection;

/**
 * Service for managing JCEF browser instances per project.
 * Provides singleton browser manager instances to avoid resource waste from creating new browsers every time.
 */
@Service(Service.Level.PROJECT)
public final class JCEFBrowserService implements Disposable {
    private static final Logger LOG = Logger.getInstance(JCEFBrowserService.class);

    private final Project project;
    // Pool of browser managers, one for each purpose
    private final Map<BrowserPurpose, JCEFBrowserManager> browserManagerPool = new ConcurrentHashMap<>();
    
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
     * Gets the browser manager for the specified purpose, creating it if necessary.
     * Each purpose gets its own dedicated browser manager instance.
     * @param purpose The purpose for which the browser is needed
     * @return The browser manager for the specified purpose
     */
    @NotNull
    public synchronized JCEFBrowserManager getBrowserManager(@NotNull BrowserPurpose purpose) {
        JCEFBrowserManager manager = browserManagerPool.get(purpose);

        if (manager == null) {
            LOG.info("Creating new JCEFBrowserManager for purpose: " + purpose + " in project: " + project.getName());
            manager = new JCEFBrowserManager(project);

            // Register for disposal when this service is disposed
            Disposer.register(this, manager);

            // Add to pool
            browserManagerPool.put(purpose, manager);

            LOG.info("JCEFBrowserManager created for purpose: " + purpose);
        } else {
            LOG.debug("Reusing existing JCEFBrowserManager for purpose: " + purpose);
        }

        return manager;
    }
    
    /**
     * Gets the existing browser manager for the default purpose without creating a new one.
     * @deprecated Use getExistingBrowserManager(BrowserPurpose) instead
     */
    @Deprecated
    @Nullable
    public JCEFBrowserManager getExistingBrowserManager() {
        return getExistingBrowserManager(BrowserPurpose.WEB_BROWSER);
    }

    /**
     * Gets the existing browser manager for the specified purpose without creating a new one.
     * Returns null if no browser manager has been created for this purpose yet.
     * @param purpose The browser purpose
     * @return The existing browser manager or null
     */
    @Nullable
    public JCEFBrowserManager getExistingBrowserManager(@NotNull BrowserPurpose purpose) {
        return browserManagerPool.get(purpose);
    }

    /**
     * Gets all active browser managers in the pool.
     * @return Collection of active browser managers
     */
    @NotNull
    public Collection<JCEFBrowserManager> getActiveBrowserManagers() {
        return browserManagerPool.values();
    }
    
    /**
     * Updates the auth token in all active browsers when it changes in configuration.
     * This provides the reverse sync from ConfigurationManager to all browsers.
     */
    public void updateAuthTokenInBrowser(String newToken) {
        if (browserManagerPool.isEmpty()) {
            LOG.debug("No browser managers exist yet, auth token will be injected when browsers are created");
        } else {
            // Update auth token in all active browser managers
            for (Map.Entry<BrowserPurpose, JCEFBrowserManager> entry : browserManagerPool.entrySet()) {
                JCEFBrowserManager manager = entry.getValue();
                if (manager != null) {
                    LOG.debug("Updating auth token in browser for purpose: " + entry.getKey());
                    manager.updateAuthTokenInBrowser(newToken);
                }
            }
        }
    }
    
    /**
     * Executes JavaScript in the default browser if available.
     * @deprecated Use executeJavaScript(BrowserPurpose, String) instead
     */
    @Deprecated
    public void executeJavaScript(String script) {
        executeJavaScript(BrowserPurpose.WEB_BROWSER, script);
    }

    /**
     * Executes JavaScript in the browser for the specified purpose if available.
     * @param purpose The browser purpose
     * @param script The JavaScript to execute
     */
    public void executeJavaScript(@NotNull BrowserPurpose purpose, String script) {
        JCEFBrowserManager manager = browserManagerPool.get(purpose);
        if (manager != null) {
            manager.executeJavaScript(script);
        } else {
            LOG.warn("Cannot execute JavaScript, browser manager not created yet for purpose: " + purpose);
        }
    }
    
    /**
     * Loads URL in the default browser if available.
     * @deprecated Use loadURL(BrowserPurpose, String) instead
     */
    @Deprecated
    public void loadURL(String url) {
        loadURL(BrowserPurpose.WEB_BROWSER, url);
    }

    /**
     * Loads URL in the browser for the specified purpose if available.
     * @param purpose The browser purpose
     * @param url The URL to load
     */
    public void loadURL(@NotNull BrowserPurpose purpose, String url) {
        JCEFBrowserManager manager = browserManagerPool.get(purpose);
        if (manager != null) {
            manager.loadURL(url);
        } else {
            LOG.warn("Cannot load URL, browser manager not created yet for purpose: " + purpose);
        }
    }
    
    /**
     * Checks if any browser manager has been created.
     */
    public boolean hasBrowserManager() {
        return !browserManagerPool.isEmpty();
    }

    /**
     * Checks if a browser manager has been created for the specified purpose.
     * @param purpose The browser purpose
     * @return true if a browser manager exists for this purpose
     */
    public boolean hasBrowserManager(@NotNull BrowserPurpose purpose) {
        return browserManagerPool.containsKey(purpose);
    }

    /**
     * Disposes the browser manager for the specified purpose.
     * @param purpose The browser purpose to dispose
     */
    public synchronized void disposeBrowserManager(@NotNull BrowserPurpose purpose) {
        JCEFBrowserManager manager = browserManagerPool.remove(purpose);
        if (manager != null) {
            LOG.info("Disposing browser manager for purpose: " + purpose);
            Disposer.dispose(manager);
        }
    }
    
    @Override
    public void dispose() {
        LOG.info("Disposing JCEFBrowserService for project: " + project.getName());

        // All browser managers will be disposed automatically through Disposer.register()
        // but we clear the pool to prevent further access
        browserManagerPool.clear();

        LOG.info("JCEFBrowserService disposed, all browser managers cleared");
    }
}