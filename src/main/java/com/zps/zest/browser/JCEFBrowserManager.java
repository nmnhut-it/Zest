package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBuilder;
import com.intellij.ui.jcef.JBCefClient;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

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
    private JBCefBrowser devToolsBrowser;
    private boolean devToolsVisible = false;

    /**
     * Creates a new browser manager.
     */
    public JCEFBrowserManager() {
        browser = new JBCefBrowserBuilder().setOffScreenRendering(false).build();
        browser.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 10);
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
        addNetworkMonitor();
//        addCompletedRequestHandler();

        // Set up compatibility injector for modern JavaScript support
//        BrowserCompatibilityInjector.setup(this);

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
     * Adds a network response monitor that detects when a response with 'completed' in the URL
     * is received and then finds and clicks a Copy button.
     */
    public void addNetworkMonitor() {
        // We need to use the DevTools protocol to access network events
        try {
            // First, ensure DevTools is enabled
            DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

            // Create a JavaScript function that will monitor network requests using the fetch API
            String monitorScript =
                    "(function() {" + "\n"+

                            "  // Store the original fetch function" + "\n"+
                            "  const originalFetch = window.fetch;" + "\n"+
                            "  " + "\n"+
                            "  // Override the fetch function to monitor responses" + "\n"+
                            "  window.fetch = function(input, init) {" + "\n"+
                            "    // Call the original fetch function" + "\n"+
                            "    return originalFetch.apply(this, arguments)" + "\n"+
                            "      .then(response => {" + "\n"+
                            "        // Clone the response to avoid consuming it" + "\n"+
                            "        const responseClone = response.clone();" + "\n"+
                            "        " + "\n"+
                            "        // Check if the URL contains 'completed'" + "\n"+
                            "        if (responseClone.url && responseClone.url.includes('completed')) {" + "\n"+
                            "          console.log('Detected network response with completed in URL:', responseClone.url);" + "\n"+
                            "          " + "\n"+
                            "          // Set a small timeout to allow the page to update" + "\n"+
                            "          setTimeout(() => {" + "\n"+
                            "            findAndClickCopyButton();" + "\n"+
                            "          }, 1000);" + "\n"+
                            "        }" + "\n"+
                            "        " + "\n"+
                            "        return response;" + "\n"+
                            "      });" + "\n"+
                            "  };" + "\n"+
                            "  " + "\n"+
                            "  // Function to find and click the Copy button" + "\n"+
                            "  function findAndClickCopyButton() {" + "\n"+
                            "    // Method 1: Find button elements with 'Copy' text" + "\n"+
                            "    let buttons = Array.from(document.getElementsByTagName('button'));" + "\n"+
                            "    let copyButton = buttons.find(button => {" + "\n"+
                            "      return button.textContent.trim() === 'Copy' || " + "\n"+
                            "             button.innerText.trim() === 'Copy' || " + "\n"+
                            "             button.getAttribute('title') === 'Copy';" + "\n"+
                            "    });" + "\n"+
                            "    " + "\n"+
                            "    // Method 2: If not found, look for elements with class/ID containing 'copy'" + "\n"+
                            "    if (!copyButton) {" + "\n"+
                            "      const allElements = document.querySelectorAll('*');" + "\n"+
                            "      for (const element of allElements) {" + "\n"+
                            "        const classNames = element.className ? element.className.toString() : '';" + "\n"+
                            "        const id = element.id || '';" + "\n"+
                            "        if (classNames.toLowerCase().includes('copy') || id.toLowerCase().includes('copy')) {" + "\n"+
                            "          copyButton = element;" + "\n"+
                            "          break;" + "\n"+
                            "        }" + "\n"+
                            "      }" + "\n"+
                            "    }" + "\n"+
                            "    " + "\n"+
                            "    // Method 3: Look for elements with aria labels related to copying" + "\n"+
                            "    if (!copyButton) {" + "\n"+
                            "      copyButton = document.querySelector('[aria-label=\"Copy\"]') || " + "\n"+
                            "                   document.querySelector('[data-action=\"copy\"]') || " + "\n"+
                            "                   document.querySelector('[data-tooltip=\"Copy\"]');" + "\n"+
                            "    }" + "\n"+
                            "    " + "\n"+
                            "    if (copyButton) {" + "\n"+
                            "      console.log('Found Copy button, clicking it');" + "\n"+
                            "      copyButton.click();" + "\n"+
                            "      return 'Copy button clicked';" + "\n"+
                            "    } else {" + "\n"+
                            "      console.log('Copy button not found');" + "\n"+
                            "      return 'Copy button not found';" + "\n"+
                            "    }" + "\n"+
                            "  }" + "\n"+
                            "})();";

            // Add a load handler to inject our monitoring script when the page loads
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    // Inject our monitoring script
                    browser.executeJavaScript(monitorScript, frame.getURL(), 0);
                    LOG.info("Injected network monitor script");
                }
            }, browser.getCefBrowser());

            LOG.info("Added network monitor with fetch API interception");
        } catch (Exception e) {
            LOG.error("Failed to add network monitor", e);
        }
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
        browser.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 10);
        System.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, "10");
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
