package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.zps.zest.ConfigurationManager;
import org.apache.commons.lang.StringEscapeUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jsoup.internal.StringUtil;

import javax.swing.*;

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

    /**
     * Sets up the JavaScript bridge for communication between Java and JavaScript.
     * This follows the official JetBrains pattern for JCEF JavaScript communication.
     */
    private void setupJavaScriptBridge(CefBrowser cefBrowser, CefFrame frame) {
        try {
            LOG.info("Setting up JavaScript bridge for frame: " + frame.getURL());
//            browser.setProperty(JS_QUERY_POOL_SIZE,10);
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
                            "       console.log(request);\n" +
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

    public void addNetworkMonitor() {
        // We need to use the DevTools protocol to access network events
        try {
            // First, ensure DevTools is enabled
            DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

            // Create a JavaScript function that will monitor network requests using the fetch API
            String monitorScript =
                    "(function() {" + "\n" +

                            "  // Store the original fetch function" + "\n" +
                            "  const originalFetch = window.fetch; let textToReplace = window.__text_to_replace_ide___;" + "\n" +
                            "  " + "\n" +
                            "  // Override the fetch function to monitor responses" + "\n" +
                            "  window.fetch = function(input, init) {" + "\n" +
                            "    // Call the original fetch function" + "\n" +
                            "    return originalFetch.apply(this, arguments)" + "\n" +
                            "      .then(response => {" + "\n" +
                            "        // Clone the response to avoid consuming it" + "\n" +
                            "        const responseClone = response.clone();" + "\n" +
                            "        " + "\n" +
                            "        // Check if the URL contains 'completed'" + "\n" +
                            "        if (responseClone.url && responseClone.url.includes('completed')) {" + "\n" +
                            "          console.log('Detected network response with completed in URL:', responseClone.url);" + "\n" +
                            "          " + "\n" +
                            "          // Set a small timeout to allow the page to update" + "\n" +
                            "          setTimeout(() => {" + "\n" +
                            "            window.extractCodeToIntelliJ(!window.__text_to_replace_ide___ ? '__##use_selected_text##__' : window.__text_to_replace_ide___);" + "\n" +
                            "            window.__text_to_replace_ide___  = null; \n" +
                            "            // Notify that content has been updated" + "\n" +
                            "            if (window.intellijBridge) {" + "\n" +
                            "              intellijBridge.callIDE('contentUpdated', { url: window.location.href });" + "\n" +
                            "            }" + "\n" +
                            "          }, 1000);" + "\n" +
                            "        }" + "\n" +
                            "        " + "\n" +
                            "        return response;" + "\n" +
                            "      });" + "\n" +
                            "  };" + "\n" +
                            "  " + "\n" +
                            "  // Function to find and click the Copy button" + "\n" +
                            "  function findAndClickCopyButton() {" + "\n" +
                            "    // Method 1: Find button elements with 'Copy' text" + "\n" +
                            "    let buttons = Array.from(document.getElementsByTagName('button'));" + "\n" +
                            "    let copyButton = buttons.find(button => {" + "\n" +
                            "      return button.textContent.trim() === 'Copy' || " + "\n" +
                            "             button.innerText.trim() === 'Copy' || " + "\n" +
                            "             button.getAttribute('title') === 'Copy';" + "\n" +
                            "    });" + "\n" +
                            "    " + "\n" +
                            "    // Method 2: If not found, look for elements with class/ID containing 'copy'" + "\n" +
                            "    if (!copyButton) {" + "\n" +
                            "      const allElements = document.querySelectorAll('*');" + "\n" +
                            "      for (const element of allElements) {" + "\n" +
                            "        const classNames = element.className ? element.className.toString() : '';" + "\n" +
                            "        const id = element.id || '';" + "\n" +
                            "        if (classNames.toLowerCase().includes('copy') || id.toLowerCase().includes('copy')) {" + "\n" +
                            "          copyButton = element;" + "\n" +
                            "          break;" + "\n" +
                            "        }" + "\n" +
                            "      }" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    // Method 3: Look for elements with aria labels related to copying" + "\n" +
                            "    if (!copyButton) {" + "\n" +
                            "      copyButton = document.querySelector('[aria-label=\"Copy\"]') || " + "\n" +
                            "                   document.querySelector('[data-action=\"copy\"]') || " + "\n" +
                            "                   document.querySelector('[data-tooltip=\"Copy\"]');" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    if (copyButton && window.shouldAutomaticallyCopy) {" + "\n" +
                            "      console.log('Found Copy button, clicking it');" + "\n" +
                            "    window.focus(); document.body.focus();\n" +
                            "      copyButton.click();window.shouldAutomaticallyCopy  = false; " + "\n" +
                            "      return 'Copy button clicked';" + "\n" +
                            "    } else {" + "\n" +
                            "      console.log('Copy button not found');" + "\n" +
                            "      return 'Copy button not found';" + "\n" +
                            "    }" + "\n" +
                            "  }" + "\n" +
                            "})();";

            // Add a load handler to inject our monitoring script when the page loads
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
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
     * Adds a network monitor and request modifier for OpenWebUI customization
     * with a fixed system prompt.
     */
    public void addNetworkMonitorAndRequestModifier() {
        // Constant system prompt
        final String SYSTEM_PROMPT = getSystemPrompt();

        try {
            // Ensure DevTools is enabled
            DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

            // Create a JavaScript function that will intercept and modify requests
            String interceptorScript =
                    "(function() {" + "\n" +
                            "  // Store the original fetch function" + "\n" +
                            "  const originalFetch = window.fetch;" + "\n" +
                            "  let textToReplace = window.__text_to_replace_ide___;" + "\n" +
                            "  " + "\n" +
                            "  // Fixed system prompt" + "\n" +
                            "  const SYSTEM_PROMPT = '" + StringEscapeUtils.escapeJavaScript(SYSTEM_PROMPT) + "';" + "\n" +
                            "  " + "\n" +
                            "  // Function to modify request body for OpenWebUI" + "\n" +
                            "  function enhanceRequestBody(body) {" + "\n" +
                            "    if (!body) return body;" + "\n" +
                            "    " + "\n" +
                            "    try {" + "\n" +
                            "      const data = JSON.parse(body);" + "\n" +
                            "      " + "\n" +
                            "      // Check if this is a chat completion request" + "\n" +
                            "      if (data.messages && Array.isArray(data.messages)) {" + "\n" +
                            "        // Add system message if it doesn't exist" + "\n" +
                            "        const hasSystemMsg = data.messages.some(msg => msg.role === 'system');" + "\n" +
                            "        if (!hasSystemMsg) {" + "\n" +
                            "          data.messages.unshift({" + "\n" +
                            "            role: 'system'," + "\n" +
                            "            content: SYSTEM_PROMPT\n" +
                            "          });" + "\n" +
                            "        }" + "\n" +
                            "        " + "\n" +
                            "        // You can add more customizations here if needed" + "\n" +
                            "        // For example:" + "\n" +
                            "        // data.temperature = 0.7;" + "\n" +
                            "        // data.max_tokens = 2000;" + "\n" +
                            "      }" + "\n" +
                            "      " + "\n" +
                            "      // Handle knowledge collection integration if present" + "\n" +
                            "      if (data.files && Array.isArray(data.files)) {" + "\n" +
                            "        // Ensure any file-related parameters are properly set" + "\n" +
                            "        console.log('Request includes files/collections');" + "\n" +
                            "      }" + "\n" +
                            "      " + "\n" +
                            "      return JSON.stringify(data);" + "\n" +
                            "    } catch (e) {" + "\n" +
                            "      console.error('Failed to modify request body:', e);" + "\n" +
                            "      return body;" + "\n" +
                            "    }" + "\n" +
                            "  }" + "\n" +
                            "  " + "\n" +
                            "  // Override the fetch function to intercept and modify requests" + "\n" +
                            "  window.fetch = function(input, init) {" + "\n" +
                            "    // Clone the init object to avoid modifying the original" + "\n" +
                            "    let newInit = init ? {...init} : {};" + "\n" +
                            "    let url = input instanceof Request ? input.url : input;" + "\n" +
                            "    " + "\n" +
                            "    // Check if this is an API request to OpenWebUI" + "\n" +
                            "    const isOpenWebUIAPI = typeof url === 'string' && " + "\n" +
                            "      (url.includes('/api/chat/completions') || " + "\n" +
                            "       url.includes('/api/conversation') || " + "\n" +
                            "       url.includes('/v1/chat/completions'));" + "\n" +
                            "       " + "\n" +
                            "    if (isOpenWebUIAPI) {" + "\n" +
                            "      console.log('Intercepting OpenWebUI API request:', url);" + "\n" +
                            "      " + "\n" +
                            "      // If there's a body in the request, modify it" + "\n" +
                            "      if (newInit.body) {" + "\n" +
                            "        const originalBody = newInit.body;" + "\n" +
                            "        if (typeof originalBody === 'string') {" + "\n" +
                            "          newInit.body = enhanceRequestBody(originalBody);" + "\n" +
                            "          console.log('Modified string request body');" + "\n" +
                            "        } else if (originalBody instanceof FormData || originalBody instanceof URLSearchParams) {" + "\n" +
                            "          console.log('FormData or URLSearchParams body not modified');" + "\n" +
                            "        } else if (originalBody instanceof Blob) {" + "\n" +
                            "          // Handle Blob body (requires async processing)" + "\n" +
                            "          return new Promise((resolve, reject) => {" + "\n" +
                            "            const reader = new FileReader();" + "\n" +
                            "            reader.onload = function() {" + "\n" +
                            "              const bodyText = reader.result;" + "\n" +
                            "              const modifiedBody = enhanceRequestBody(bodyText);" + "\n" +
                            "              newInit.body = modifiedBody;" + "\n" +
                            "              resolve(originalFetch(input, newInit)" + "\n" +
                            "                .then(handleResponse));" + "\n" +
                            "            };" + "\n" +
                            "            reader.onerror = function() {" + "\n" +
                            "              reject(reader.error);" + "\n" +
                            "            };" + "\n" +
                            "            reader.readAsText(originalBody);" + "\n" +
                            "          });" + "\n" +
                            "        }" + "\n" +
                            "      } else if (input instanceof Request) {" + "\n" +
                            "        // If input is a Request object with a body" + "\n" +
                            "        return input.clone().text().then(body => {" + "\n" +
                            "          const modifiedBody = enhanceRequestBody(body);" + "\n" +
                            "          const newRequest = new Request(input, {" + "\n" +
                            "            method: input.method," + "\n" +
                            "            headers: input.headers," + "\n" +
                            "            body: modifiedBody," + "\n" +
                            "            mode: input.mode," + "\n" +
                            "            credentials: input.credentials," + "\n" +
                            "            cache: input.cache," + "\n" +
                            "            redirect: input.redirect," + "\n" +
                            "            referrer: input.referrer," + "\n" +
                            "            integrity: input.integrity" + "\n" +
                            "          });" + "\n" +
                            "          return originalFetch(newRequest)" + "\n" +
                            "            .then(handleResponse);" + "\n" +
                            "        });" + "\n" +
                            "      }" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    // Function to handle responses" + "\n" +
                            "    function handleResponse(response) {" + "\n" +
                            "      const responseClone = response.clone();" + "\n" +
                            "      " + "\n" +
                            "      if (responseClone.url && responseClone.url.includes('completed')) {" + "\n" +
                            "        console.log('Detected network response with completed in URL:', responseClone.url);" + "\n" +
                            "        " + "\n" +
                            "        setTimeout(() => {" + "\n" +
                            "          window.extractCodeToIntelliJ(!window.__text_to_replace_ide___ ? '__##use_selected_text##__' : window.__text_to_replace_ide___);" + "\n" +
                            "          window.__text_to_replace_ide___ = null;" + "\n" +
                            "          if (window.intellijBridge) {" + "\n" +
                            "            intellijBridge.callIDE('contentUpdated', { url: window.location.href });" + "\n" +
                            "          }" + "\n" +
                            "        }, 1000);" + "\n" +
                            "      }" + "\n" +
                            "      " + "\n" +
                            "      return response;" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    // Continue with the original fetch" + "\n" +
                            "    return originalFetch.apply(this, [input, newInit])" + "\n" +
                            "      .then(handleResponse);" + "\n" +
                            "  };" + "\n" +
                            "  " + "\n" +
                            "  // Function to find and click the Copy button (keeping your original code)" + "\n" +
                            "  function findAndClickCopyButton() {" + "\n" +
                            "    // Method 1: Find button elements with 'Copy' text" + "\n" +
                            "    let buttons = Array.from(document.getElementsByTagName('button'));" + "\n" +
                            "    let copyButton = buttons.find(button => {" + "\n" +
                            "      return button.textContent.trim() === 'Copy' || " + "\n" +
                            "             button.innerText.trim() === 'Copy' || " + "\n" +
                            "             button.getAttribute('title') === 'Copy';" + "\n" +
                            "    });" + "\n" +
                            "    " + "\n" +
                            "    // Method 2: If not found, look for elements with class/ID containing 'copy'" + "\n" +
                            "    if (!copyButton) {" + "\n" +
                            "      const allElements = document.querySelectorAll('*');" + "\n" +
                            "      for (const element of allElements) {" + "\n" +
                            "        const classNames = element.className ? element.className.toString() : '';" + "\n" +
                            "        const id = element.id || '';" + "\n" +
                            "        if (classNames.toLowerCase().includes('copy') || id.toLowerCase().includes('copy')) {" + "\n" +
                            "          copyButton = element;" + "\n" +
                            "          break;" + "\n" +
                            "        }" + "\n" +
                            "      }" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    // Method 3: Look for elements with aria labels related to copying" + "\n" +
                            "    if (!copyButton) {" + "\n" +
                            "      copyButton = document.querySelector('[aria-label=\"Copy\"]') || " + "\n" +
                            "                   document.querySelector('[data-action=\"copy\"]') || " + "\n" +
                            "                   document.querySelector('[data-tooltip=\"Copy\"]');" + "\n" +
                            "    }" + "\n" +
                            "    " + "\n" +
                            "    if (copyButton && window.shouldAutomaticallyCopy) {" + "\n" +
                            "      console.log('Found Copy button, clicking it');" + "\n" +
                            "      window.focus(); document.body.focus();" + "\n" +
                            "      copyButton.click(); window.shouldAutomaticallyCopy = false; " + "\n" +
                            "      return 'Copy button clicked';" + "\n" +
                            "    } else {" + "\n" +
                            "      console.log('Copy button not found');" + "\n" +
                            "      return 'Copy button not found';" + "\n" +
                            "    }" + "\n" +
                            "  }" + "\n" +
                            "  " + "\n" +
                            "  console.log('OpenWebUI request interceptor initialized with system prompt');" + "\n" +
                            "})();";

            // Add a load handler to inject our script when the page loads
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    // Inject our interceptor script
                    browser.executeJavaScript(interceptorScript, frame.getURL(), 0);
                    LOG.info("Injected request interceptor script with system prompt: " + SYSTEM_PROMPT);
                }
            }, browser.getCefBrowser());

            LOG.info("Added network monitor and request modifier for OpenWebUI customization");

        } catch (Exception e) {
            LOG.error("Failed to add network monitor and request modifier", e);
        }
    }

    private static @NotNull String getSystemPrompt() {
return "You are an advanced problem-solving assistant with a multi-faceted cognitive framework. Your approach to addressing challenges mirrors the way skilled human experts think.\n" +
        "\n" +
        "CORE PROBLEM-SOLVING METHODOLOGY:\n" +
        "\n" +
        "1. MULTI-PERSPECTIVE ANALYSIS\n" +
        "   - Begin by examining each problem from multiple angles\n" +
        "   - Consider both stated requirements and unstated needs\n" +
        "   - Balance immediate solutions with long-term implications\n" +
        "   - Explore both practical constraints and theoretical ideals\n" +
        "\n" +
        "2. DECOMPOSITION AND INTEGRATION\n" +
        "   - Break complex problems into fundamental components\n" +
        "   - Identify patterns from your knowledge base and experiences\n" +
        "   - Build solutions incrementally while maintaining holistic awareness\n" +
        "   - Distinguish between core issues and symptoms\n" +
        "\n" +
        "3. ADAPTIVE METHODOLOGY\n" +
        "   - Avoid rigid application of any single problem-solving framework\n" +
        "   - Adjust your approach based on the specific context and constraints\n" +
        "   - Remain flexible when new information emerges\n" +
        "   - Recalibrate solutions based on feedback and changing requirements\n" +
        "\n" +
        "4. MENTAL MODELS APPLICATION\n" +
        "   - Apply first principles thinking by breaking down to fundamental truths\n" +
        "   - Use systems thinking to understand relationships and feedback loops\n" +
        "   - Implement design thinking with empathy for end-user needs\n" +
        "   - Employ scientific reasoning through hypothesis formation and testing\n" +
        "\n" +
        "5. PROACTIVE QUESTIONING\n" +
        "   - Ask clarifying questions when requirements are ambiguous or incomplete\n" +
        "   - Do not hesitate to request additional information needed for optimal solutions\n" +
        "   - Use questions to validate your understanding before proceeding\n" +
        "   - Frame questions to guide users toward considering important factors they may have overlooked\n" +
        "\n" +
        "In your responses, seamlessly integrate these approaches rather than mechanically working through them as steps. Your goal is to provide solutions that demonstrate a balance of creativity, pragmatism, and methodical reasoning. Prioritize clarity and effectiveness while avoiding unnecessary complexity.\n" +
        "\n" +
        "When solving problems, think step-by-step while maintaining awareness of the whole system. Consider multiple solutions, evaluate tradeoffs transparently, and explain your reasoning process to help users understand not just what to do, but why and how a solution works."    ;
    }
}