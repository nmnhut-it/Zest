package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.*;
import com.zps.zest.ConfigurationManager;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
        browser = new JBCefBrowserBuilder().setOffScreenRendering(false).build();
        browser.getJBCefClient().setProperty(JS_QUERY_POOL_SIZE, 10);

        // Create JavaScript bridge
        jsBridge = new JavaScriptBridge(project);

        // Add load handler to initialize JS bridge when page loads
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                setupJavaScriptBridge(cefBrowser, frame);
                new AutoCodeExtractorWithBridge().onLoadEnd(cefBrowser, frame, httpStatusCode);
            }
        }, browser.getCefBrowser());
//        browser.getJBCefClient().addLoadHandler(new AutoCodeExtractorWithBridge(),browser.getCefBrowser());

//        addNetworkMonitor();
        addNetworkMonitorAndRequestModifier();
        // Load default URL
        String url = ConfigurationManager.getInstance(project).getApiUrl().replace("/api/chat/completions", "");

        loadURL(url);
        LOG.info("JCEFBrowserManager initialized");
    }

    /**
     * Gets the browser component that can be added to a Swing container.
     */
    public JComponent getComponent() {
        return browser.getComponent();
    }

    /**
     * Loads the specified URL into the browser.
     *
     * @param url the URL to be loaded into the browser
     */
    public void loadURL(String url) {
        // Log the URL being loaded for debugging and informational purposes
        LOG.info("Loading URL: " + url);

        // Load the specified URL into the browser
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

// Correct implementation of setupJavaScriptBridge in JCEFBrowserManager.java

    private void setupJavaScriptBridge(CefBrowser cefBrowser, CefFrame frame) {
        try {
            LOG.info("Setting up JavaScript bridge for frame: " + frame.getURL());

            // Create a JS query
            jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

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

            // Load the bridge script template
            String bridgeScript = loadResourceAsString("/js/intellijBridge.js");
            
            // Replace the placeholder with the actual JBCefJSQuery.inject call
            String jsQueryInject = jsQuery.inject("request",
                    "function(response) { " +
                            "  console.log('Received success response from IDE'); " +
                            "  try { resolve(JSON.parse(response)); } " +
                            "  catch(e) { resolve(response); } " +
                            "}",
                    "function(errorCode, errorMessage) { " +
                            "  console.error('Received error from IDE:', errorCode, errorMessage); " +
                            "  reject({code: errorCode, message: errorMessage}); " +
                            "}");
            
            // Replace the placeholder with the actual JBCefJSQuery inject code
            bridgeScript = bridgeScript.replace("[[JBCEF_QUERY_INJECT]]", jsQueryInject);

            // Inject the bridge script
            cefBrowser.executeJavaScript(bridgeScript, frame.getURL(), 0);
            
            // Load and inject the code extractor script
            String codeExtractorScript = loadResourceAsString("/js/codeExtractor.js");
            cefBrowser.executeJavaScript(codeExtractorScript, frame.getURL(), 0);
            
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

    String  interceptorScript;
    public void addNetworkMonitorAndRequestModifier() {
        try {
            // Ensure DevTools is enabled
            DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

            // Load the interceptor script from file
            // TODO: CACHE TH
            if (interceptorScript == null) {
                interceptorScript = loadResourceAsString("/js/interceptor.js");
            }

            // Add a load handler to inject our script when the page loads
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    // Inject our interceptor script
                    browser.executeJavaScript(interceptorScript, frame.getURL(), 0);
                    LOG.info("Injected request interceptor script with dynamic project info support");
                }
            }, browser.getCefBrowser());

            LOG.info("Added network monitor and request modifier with real-time project info support");

        } catch (Exception e) {
            LOG.error("Failed to add network monitor and request modifier", e);
        }
    }

    /**
     * Helper method to load a resource file as a string
     * @param path Path to the resource
     * @return The content of the resource as a string
     * @throws IOException If the resource cannot be read
     */
    private String loadResourceAsString(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}