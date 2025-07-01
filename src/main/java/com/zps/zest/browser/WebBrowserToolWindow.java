package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.openapi.application.ApplicationManager;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory class for creating the web browser tool window.
 * Updated for compatibility with newer IntelliJ Platform versions.
 */
public class WebBrowserToolWindow implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(WebBrowserToolWindow.class);
    protected static final ConcurrentMap<String, Boolean> pageLoadedState = new ConcurrentHashMap<>();
    protected static final ConcurrentMap<String, CompletableFuture<Boolean>> pageLoadedFutures = new ConcurrentHashMap<>();

    private static final int MAX_CONTENT_MANAGER_RETRIES = 5;
    private int contentManagerRetryCount = 0;
    
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Initialize DevTools registry settings
        DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

        // Check if ContentManager is ready, if not delay the initialization
        if (toolWindow.getContentManager() == null) {
            contentManagerRetryCount++;
            if (contentManagerRetryCount > MAX_CONTENT_MANAGER_RETRIES) {
                LOG.error("Failed to initialize tool window after " + MAX_CONTENT_MANAGER_RETRIES + " attempts - ContentManager is null");
                contentManagerRetryCount = 0; // Reset for potential future attempts
                return;
            }
            LOG.warn("ContentManager not ready (attempt " + contentManagerRetryCount + " of " + MAX_CONTENT_MANAGER_RETRIES + "), deferring tool window content creation");
            ApplicationManager.getApplication().invokeLater(() -> createToolWindowContent(project, toolWindow), project.getDisposed());
            return;
        }

        // Reset retry count on success
        contentManagerRetryCount = 0;

        // Use invokeLater to ensure proper initialization order
        ApplicationManager.getApplication().invokeLater(() -> {
            LOG.info("Creating web browser tool window for project: " + project.getName());

            try {
                // Double-check ContentManager is available
                ContentManager contentManager = toolWindow.getContentManager();
                if (contentManager == null) {
                    LOG.error("ContentManager is null after invokeLater");
                    return;
                }

                // Create the browser panel
                WebBrowserPanel browserPanel = new WebBrowserPanel(project);
                
                // Register the panel for disposal when the tool window is closed
                Disposer.register(toolWindow.getDisposable(), browserPanel);
                
                // Add load state listener to track when pages finish loading
                setupLoadStateListener(browserPanel, project);

                // Create content and add it to the tool window using modern API
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(browserPanel.getComponent(), "", false);
                contentManager.addContent(content);

                // Register the panel with the service
                WebBrowserService.getInstance(project).registerPanel(browserPanel);
                
                // Integrate with AI assistant
//                BrowserIntegrator.integrate(project);
                
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
    
    /**
     * Sets up a load state listener to track when pages finish loading.
     *
     * @param browserPanel The browser panel
     * @param project The current project
     */
    private void setupLoadStateListener(WebBrowserPanel browserPanel, Project project) {
        JCEFBrowserManager browserManager = browserPanel.getBrowserManager();
        if (browserManager != null) {
            browserManager.getBrowser().getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType transitionType) {
                    String url = frame.getURL();
                    LOG.info("Page load started: " + url);
                    pageLoadedState.put(getProjectUrlKey(project, url), false);
                }

                @Override
                public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                    String url = frame.getURL();
                    LOG.info("Page load finished: " + url + " with status code: " + httpStatusCode);
                    String key = getProjectUrlKey(project, url);
                    pageLoadedState.put(key, true);
                    
                    // Complete any pending futures for this URL
                    CompletableFuture<Boolean> future = pageLoadedFutures.remove(key);
                    if (future != null && !future.isDone()) {
                        future.complete(true);
                    }
                }
            }, browserManager.getBrowser().getCefBrowser());
        }
    }

    /**
     * Checks if a page is currently loaded.
     *
     * @param project The project
     * @param url The URL to check
     * @return true if the page is loaded, false otherwise
     */
    public static boolean isPageLoaded(Project project, String url) {
        return pageLoadedState.getOrDefault(getProjectUrlKey(project, url), false);
    }

    /**
     * Waits for a page to finish loading.
     * 
     * @param project The project
     * @param url The URL to wait for
     * @return A CompletableFuture that completes when the page is loaded
     */
    public static CompletableFuture<Boolean> waitForPageToLoad(Project project, String url) {
        String key = getProjectUrlKey(project, url);
        
        // If the page is already loaded, return a completed future
        if (pageLoadedState.getOrDefault(key, false)) {
            return CompletableFuture.completedFuture(true);
        }
        
        // Otherwise create a new future and register it
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pageLoadedFutures.put(key, future);
        return future;
    }
    
    /**
     * Resets the page load state for a URL, allowing waitForPageToLoad to work after interactions.
     * Call this method before performing interactions that will load new content.
     * 
     * @param project The project
     * @param url The URL to reset the state for
     * @return A CompletableFuture that completes when the page is loaded
     */
    public static CompletableFuture<Boolean> resetPageLoadState(Project project, String url) {
        String key = getProjectUrlKey(project, url);
        LOG.info("Resetting page load state for: " + url);
        pageLoadedState.put(key, false);
        
        // Create a new future for this page
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pageLoadedFutures.put(key, future);
        return future;
    }
    
    /**
     * Creates a unique key for tracking page load state per project and URL.
     */
    private static String getProjectUrlKey(Project project, String url) {
        return project.getName() + ":" + url;
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // Configure tool window options
        toolWindow.setTitle("ZPS Chat");
        toolWindow.setStripeTitle("ZPS Chat");
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // This tool window should always be available
        return true;
    }
}
