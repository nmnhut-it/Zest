package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.*;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
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

    /**
     * Creates a new browser manager.
     */
    public JCEFBrowserManager() {
        browser = new JBCefBrowser();
        client = browser.getJBCefClient();
        
        // Add load handler to inject bridge script when page loads
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                injectBridgeScript(frame);
            }
        }, browser.getCefBrowser());
        
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
     * Registers the JavaScript bridge for communication.
     */
    public void registerBridge(JavaScriptBridge bridge) {
        this.jsBridge = bridge;
        
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
        if (jsBridge != null) {
            jsBridge.dispose();
        }
        client.dispose();
    }
}
