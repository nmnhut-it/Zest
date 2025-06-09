package com.zps.zest.diff;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefBrowser;

/**
 * Manages shared browser instances for diff viewers.
 * Uses a singleton pattern to reuse browser instances for performance.
 */
public class DiffBrowserManager {
    private static final Logger LOG = Logger.getInstance(DiffBrowserManager.class);
    
    // Shared browser instances for different use cases
    private static JBCefBrowser sharedSimpleDiffBrowser;
    private static JBCefBrowser sharedGitHubStyleBrowser;
    
    /**
     * Get or create a browser instance for simple diff viewer
     */
    public static JBCefBrowser getOrCreateSimpleDiffBrowser() {
        LOG.info("=== getOrCreateSimpleDiffBrowser called ===");
        
        if (sharedSimpleDiffBrowser == null || sharedSimpleDiffBrowser.isDisposed()) {
            LOG.info("Creating new JBCefBrowser instance for simple diff...");
            
            // Enable JCef DevTools
            System.setProperty("ide.browser.jcef.debug.port", "9223");
            System.setProperty("ide.browser.jcef.headless", "false");
            
            try {
                sharedSimpleDiffBrowser = new JBCefBrowser();
                sharedSimpleDiffBrowser.getJBCefClient().setProperty("chromiumSwitches", "--remote-debugging-port=9224");
                LOG.info("JBCefBrowser created successfully for simple diff");
            } catch (Exception e) {
                LOG.error("ERROR creating JBCefBrowser: " + e.getMessage(), e);
                throw e;
            }
        } else {
            LOG.info("Reusing existing JBCefBrowser instance for simple diff");
        }
        return sharedSimpleDiffBrowser;
    }
    
    /**
     * Get or create a browser instance for GitHub-style diff viewer
     */
    public static JBCefBrowser getOrCreateGitHubStyleBrowser() {
        LOG.info("=== getOrCreateGitHubStyleBrowser called ===");
        
        if (sharedGitHubStyleBrowser == null || sharedGitHubStyleBrowser.isDisposed()) {
            LOG.info("Creating new JBCefBrowser instance for GitHub-style diff...");
            
            // Enable JCef DevTools
            System.setProperty("ide.browser.jcef.debug.port", "9225");
            System.setProperty("ide.browser.jcef.headless", "false");
            
            try {
                sharedGitHubStyleBrowser = new JBCefBrowser();
                sharedGitHubStyleBrowser.getJBCefClient().setProperty("chromiumSwitches", "--remote-debugging-port=9226");
                LOG.info("JBCefBrowser created successfully for GitHub-style diff");
            } catch (Exception e) {
                LOG.error("ERROR creating JBCefBrowser: " + e.getMessage(), e);
                throw e;
            }
        } else {
            LOG.info("Reusing existing JBCefBrowser instance for GitHub-style diff");
        }
        return sharedGitHubStyleBrowser;
    }
    
    /**
     * Clean up resources when shutting down
     */
    public static void dispose() {
        if (sharedSimpleDiffBrowser != null && !sharedSimpleDiffBrowser.isDisposed()) {
            sharedSimpleDiffBrowser.dispose();
            sharedSimpleDiffBrowser = null;
        }
        
        if (sharedGitHubStyleBrowser != null && !sharedGitHubStyleBrowser.isDisposed()) {
            sharedGitHubStyleBrowser.dispose();
            sharedGitHubStyleBrowser = null;
        }
    }
}
