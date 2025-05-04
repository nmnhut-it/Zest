package com.zps.zest.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.*;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.util.Objects;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
@SuppressWarnings("removal")
public class JCEFBrowserManager {
    private static final Logger LOG = Logger.getInstance(JCEFBrowserManager.class);
    private static final String DEFAULT_URL = "https://chat.zingplay.com";

    private final JBCefBrowser browser;
    private final Project project;
    private final JavaScriptBridge jsBridge;
    private JBCefJSQuery jsQuery;
    private JBCefBrowser devToolsBrowser;
    private boolean devToolsVisible = false;

    /**
     * Creates a new browser manager with the specified project.
     *
     * @param project The IntelliJ project context
     */
    public JCEFBrowserManager(Project project) {
        this.project = project;

        // Check if JCEF is supported
        if (!JBCefApp.isSupported()) {
            LOG.error("JCEF is not supported in this IDE environment");
            throw new UnsupportedOperationException("JCEF is not supported in this IDE environment");
        }

        // Create browser with default settings
        browser = new JBCefBrowser();
        browser.getJBCefClient().setProperty(JS_QUERY_POOL_SIZE,10);

        // Create JavaScript bridge
        jsBridge = new JavaScriptBridge(project);

        // Add load handler to initialize JS bridge when page loads
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                setupJavaScriptBridge(cefBrowser, frame);
            }
        }, browser.getCefBrowser());

        // Load default URL
        loadURL(DEFAULT_URL);

        LOG.info("JCEFBrowserManager initialized");
    }

    /**
     * Gets the browser component that can be added to a Swing container.
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
     * Sets up the JavaScript bridge for communication between Java and JavaScript.
     * This follows the official JetBrains pattern for JCEF JavaScript communication.
     */
    private void setupJavaScriptBridge(CefBrowser cefBrowser, CefFrame frame) {
        try {
            LOG.info("Setting up JavaScript bridge for frame: " + frame.getURL());
//            browser.setProperty(JS_QUERY_POOL_SIZE,10);
            // Create a JS query
            jsQuery = JBCefJSQuery.create((JBCefBrowserBase)browser);

            // Add a handler for the query
            jsQuery.addHandler((query) -> {
                try {
                    // Process the query using the JavaScriptBridge
                    String result = jsBridge.handleJavaScriptQuery(query);
                    return new JBCefJSQuery.Response(result);
                } catch (Exception e) {
                    LOG.error("Error handling JavaScript query", e);
                    return new JBCefJSQuery.Response(null, 500, e.getMessage());
                }
            });

            // Create a JavaScript bridge object
            String injectScript = jsQuery.inject("request");
            String bridgeScript =
                    "window.intellijBridge = {\n" +
                            "  callIDE: function(action, data) {\n" +
                            "    return new Promise(function(resolve, reject) {\n" +
                            "      try {\n" +
                            "        // Create the request\n" +
                            "        var request = JSON.stringify({\n" +
                            "          action: action,\n" +
                            "          data: data || {}\n" +
                            "        });\n" +
                            "        \n" +
                            "       console.log(request);\n"+
                            "        // Call Java with the request\n" +
                            "        " + injectScript + ";\n" +

                            "      } catch(e) {\n" +
                            "        reject(e);\n" +
                            "      }\n" +
                            "    });\n" +
                            "  }\n" +
                            "};\n" +
                            "window.shouldAutomaticallyCopy = true;\n" +
                            "console.log('IntelliJ Bridge initialized');";

            // Inject the bridge script
            cefBrowser.executeJavaScript(bridgeScript, frame.getURL(), 0);
//            testBridge();
            LOG.info("JavaScript bridge initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to setup JavaScript bridge", e);
        }
    }

    /**
     * Tests the JavaScript bridge by showing a dialog.
     */
    public void testBridge() {
        executeJavaScript(
                "if (window.intellijBridge) {\n" +
                        "  intellijBridge.callIDE('showDialog', {\n" +
                        "    title: 'Bridge Test',\n" +
                        "    message: 'Bridge is working correctly!',\n" +
                        "    type: 'info'\n" +
                        "  }).then(function(result) {\n" +
                        "    console.log('Dialog shown successfully');\n" +
                        "  }).catch(function(error) {\n" +
                        "    console.error('Bridge test failed:', error);\n" +
                        "  });\n" +
                        "} else {\n" +
                        "  console.error('Bridge not initialized');\n" +
                        "}");
    }

    /**
     * Opens the Chrome Developer Tools in a separate window.
     */
    public boolean openDevTools() {
        try {
            if (!devToolsVisible) {
                LOG.info("Opening Developer Tools");
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
     * Toggles the visibility of developer tools.
     */
    public boolean toggleDevTools() {
        if (devToolsVisible) {
            closeDevTools();
            return false;
        } else {
            return openDevTools();
        }
    }

    /**
     * Closes the Developer Tools window.
     */
    public boolean closeDevTools() {
        try {
            if (devToolsVisible && devToolsBrowser != null) {
                LOG.info("Closing Developer Tools");
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
     * Gets the underlying JBCefBrowser instance.
     */
    public JBCefBrowser getBrowser() {
        return browser;
    }

    /**
     * Disposes of browser resources.
     */
    public void dispose() {
        LOG.info("Disposing JCEFBrowserManager");

        // Dispose JavaScript query
        if (jsQuery != null) {
            Disposer.dispose(jsQuery);
        }

        closeDevTools();

        LOG.info("JCEFBrowserManager disposed");
    }
}