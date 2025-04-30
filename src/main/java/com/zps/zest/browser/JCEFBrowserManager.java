package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.*;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
public class JCEFBrowserManager {
    private static final Logger LOG = Logger.getInstance(JCEFBrowserManager.class);
    private static final String DEFAULT_URL = "https://chat.zingplay.com";
    private static final String BRIDGE_INIT_SCRIPT = 
            "window.intellijBridge = {};" +
            "window.receiveFromIDE = function(text) {" +
            "  const event = new CustomEvent('ideTextReceived', { detail: { text: text } });" +
            "  document.dispatchEvent(event);" +
            "};";

    private final JBCefBrowser browser;
    private final JBCefClient client;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private JavaScriptBridge jsBridge;
    private JBCefBrowser devToolsBrowser;
    private boolean devToolsVisible = false;

    /**
     * Creates a new browser manager.
     */
    public JCEFBrowserManager() {
        browser = new JBCefBrowserBuilder().setOffScreenRendering(false).build();
        browser.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE,10);
        client = browser.getJBCefClient();
        
        // Add load handler to inject bridge script when page loads
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                injectBridgeScript(frame);
            }
        }, browser.getCefBrowser());
        
        // Add context menu handler for DevTools access
//        client.getCefClient().addContextMenuHandler(new DevToolsContextMenuHandler(this), browser.getCefBrowser());
        
        // Add context menu handler for chat input debugging
//        client.getCefClient().addContextMenuHandler(new ChatInputContextMenuHandler(this), browser.getCefBrowser());
        
        // Set up compatibility injector for modern JavaScript support
        BrowserCompatibilityInjector.setup(this);
        
        // Initialize with default page
        loadURL(DEFAULT_URL);
    }

    /**
     * Gets the browser component.
     */
    public JComponent getComponent() {
        return browser.getComponent();
    }

    /**
     * Loads the specified URL.
     */
    public void loadURL(String url) {
        LOG.info("Loading URL: " + url);
        browser.loadURL(url);
    }
    
    /**
     * Loads the specified URL with enhanced compatibility options.
     * This method applies additional compatibility fixes for problematic sites.
     * 
     * @param url The URL to load
     */
    public void loadURLWithCompatibility(String url) {
        LOG.info("Loading URL with compatibility enhancements: " + url);
        
        // Apply additional error monitoring
        BrowserCompatibilityInjector.monitorJavaScriptErrors(this);
        
        // Load the URL
        browser.loadURL(url);
    }

    /**
     * Navigates back in browser history.
     */
    public void goBack() {
        browser.getCefBrowser().goBack();
    }

    /**
     * Navigates forward in browser history.
     */
    public void goForward() {
        browser.getCefBrowser().goForward();
    }

    /**
     * Refreshes the current page.
     */
    public void refresh() {
        browser.getCefBrowser().reload();
    }

    /**
     * Executes JavaScript in the browser.
     */
    public void executeJavaScript(String script) {
        browser.getCefBrowser().executeJavaScript(
                script, browser.getCefBrowser().getURL(), 0);
    }

    /**
     * Opens the Chrome Developer Tools in a separate window.
     * Based on the official IntelliJ Platform documentation for JCEF.
     * 
     * @return true if developer tools were successfully opened
     */
    public boolean openDevTools() {
        try {
            if (!devToolsVisible) {
                LOG.info("Opening Developer Tools");
                
                // Use the recommended API from the documentation
                browser.openDevtools();
                
                devToolsVisible = true;
            }
            return true;
        } catch (Exception e) {
            LOG.error("Error opening developer tools", e);
            return false;
        }
    }
    
    /**
     * Closes the Developer Tools window if it's open.
     * 
     * @return true if developer tools were successfully closed
     */
    public boolean closeDevTools() {
        try {
            if (devToolsVisible && devToolsBrowser != null) {
                LOG.info("Closing Developer Tools");
                
                // Close the devtools window
                SwingUtilities.getWindowAncestor(devToolsBrowser.getComponent()).dispose();
                devToolsBrowser = null;
                devToolsVisible = false;
            }
            return true;
        } catch (Exception e) {
            LOG.error("Error closing developer tools", e);
            return false;
        }
    }

    /**
     * Toggles the visibility of developer tools.
     * 
     * @return true if developer tools are now visible, false otherwise
     */
    public boolean toggleDevTools() {
        if (devToolsVisible) {
            return !closeDevTools(); // Return !closeDevTools() because we want to return the new state
        } else {
            return openDevTools();
        }
    }
    
    /**
     * Creates and returns a JBCefBrowser instance for devtools.
     * This follows the official documentation method for accessing DevTools programmatically.
     * 
     * @return A JBCefBrowser configured for devtools
     */
    public JBCefBrowser createDevToolsBrowser() {
        if (devToolsBrowser == null) {
            CefBrowser devTools = browser.getCefBrowser().getDevTools();
            devToolsBrowser = JBCefBrowser.createBuilder()
                .setCefBrowser(devTools)
                .setClient(browser.getJBCefClient())
                .build();
        }
        return devToolsBrowser;
    }
    
    /**
     * Checks if developer tools are currently visible.
     * 
     * @return true if developer tools are visible, false otherwise
     */
    public boolean isDevToolsVisible() {
        return devToolsVisible;
    }
    
    /**
     * Gets the underlying JBCefBrowser instance.
     * 
     * @return The JBCefBrowser instance
     */
    public JBCefBrowser getBrowser() {
        return browser;
    }

    /**
     * Registers the JavaScript bridge for communication.
     */
    public void registerBridge(JavaScriptBridge bridge) {
        this.jsBridge = bridge;
        browser.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE,10);
        // Create a message router to handle JavaScript communication
        JBCefJSQuery jsQuery = JBCefJSQuery.create(browser);
        
        // Set up query handler
        jsQuery.addHandler((query) -> {
            try {
                String result = bridge.handleJavaScriptQuery(query);
                return new JBCefJSQuery.Response(result);
            } catch (Exception e) {
                LOG.error("Error handling JavaScript query", e);
                return new JBCefJSQuery.Response(null, 500, e.getMessage());
            }
        });
        
        // Inject JavaScript to call the query
        String bridgeInitScript = 
                "window.intellijBridge.callIDE = function(action, data) {" +
                "  return new Promise((resolve, reject) => {" +
                "    const request = JSON.stringify({action: action, data: data});" +
                "    " + jsQuery.inject("request") + 
                "      .then(response => { resolve(JSON.parse(response)); })" +
                "      .catch(error => { reject(error); });" +
                "  });" +
                "};";
        
        executeJavaScript(bridgeInitScript);
        isInitialized.set(true);
    }

    /**
     * Injects the JavaScript bridge code into the loaded page.
     */
    private void injectBridgeScript(CefFrame frame) {
        executeJavaScript(BRIDGE_INIT_SCRIPT);
        
        // If bridge is registered, re-initialize the connection
        if (jsBridge != null) {
            registerBridge(jsBridge);
        }
    }

    /**
     * Disposes of browser resources.
     */
    public void dispose() {
        closeDevTools();
        
        if (jsBridge != null) {
            jsBridge.dispose();
        }
        client.dispose();
    }
}
