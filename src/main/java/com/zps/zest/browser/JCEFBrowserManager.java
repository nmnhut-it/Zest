package com.zps.zest.browser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.*; 
import com.zps.zest.ConfigurationManager;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
@SuppressWarnings("removal")
public class JCEFBrowserManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(JCEFBrowserManager.class);

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

        // No longer using custom scheme handler - loading resources directly
        // registerResourceSchemeHandler();

        // Create JavaScript bridge
        jsBridge = new JavaScriptBridge(project);

        // Add load handler to initialize JS bridge when page loads
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                setupJavaScriptBridge(cefBrowser, frame);

                new AutoCodeExtractorWithBridge().onLoadEnd(cefBrowser, frame, httpStatusCode);
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
                setupJavaScriptBridge(browser, frame);
                new AutoCodeExtractorWithBridge().onLoadEnd(browser, frame, errorCode.getCode());
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
     * Loads the Agent Framework demo page from resources
     */
    public void loadAgentDemo() {
        loadHTMLFromResource("/html/agentDemo.html");
    }

    /**
     * Loads the resource test page
     */
    public void loadResourceTest() {
        loadHTMLFromResource("/html/resourceTest.html");
    }

    /**
     * Loads the Workflow Builder page from resources
     */
    public void loadWorkflowBuilder() {
        loadHTMLFromResource("/html/workflowBuilder.html");
    }

    /**
     * Loads the JCEF test page from resources
     */
    public void loadJCEFTest() {
        loadHTMLFromResource("/html/jcefTest.html");
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


    private void setupJavaScriptBridge(CefBrowser cefBrowser, CefFrame frame) {
        try {
            LOG.info("Setting up JavaScript bridge for frame: " + frame.getURL());

            // Create a JS query
            jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

            // Add a handler for the query
            jsQuery.addHandler((query) -> {
                try {
                    // Process the query using the JavaScriptBridge (now with chunked messaging support)
                    String result = jsBridge.handleJavaScriptQuery(query);
                    return new JBCefJSQuery.Response(result);
                } catch (Exception e) {
                    LOG.error("Error handling JavaScript query", e);
                    return new JBCefJSQuery.Response(null, 500, e.getMessage());
                }
            });

            // Load the bridge script template
            String bridgeScript = loadResourceAsString("/js/intellijBridgeChunked.js");

            // Replace the placeholder with the actual JBCefJSQuery.inject call for single messages
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

            // Replace the placeholder with the actual JBCefJSQuery inject code for single messages
            bridgeScript = bridgeScript.replace("[[JBCEF_QUERY_INJECT]]", jsQueryInject);

            // For chunked messages, we use the same query handler but with chunk data
            String chunkQueryInject = jsQuery.inject("chunkRequest",
                    "function(response) { " +
                            "  console.log('Chunk sent successfully'); " +
                            "  resolve(); " +  // Resolve the Promise for successful chunk sending
                            "}",
                    "function(errorCode, errorMessage) { " +
                            "  console.error('Chunk send error:', errorCode, errorMessage); " +
                            "  reject({code: errorCode, message: errorMessage}); " +
                            "}");
            // Replace the chunk placeholder
            bridgeScript = bridgeScript.replace("[[JBCEF_CHUNK_INJECT]]", chunkQueryInject);

            // Inject the bridge script
            cefBrowser.executeJavaScript(bridgeScript, frame.getURL(), 0);

            // Load and inject the response parser script
            String responseParserScript = loadResourceAsString("/js/responseParser.js");
            cefBrowser.executeJavaScript(responseParserScript, frame.getURL(), 0);

            // Load and inject the code extractor script
            String codeExtractorScript = loadResourceAsString("/js/codeExtractor.js");
            cefBrowser.executeJavaScript(codeExtractorScript, frame.getURL(), 0);

            // Load and inject the git integration scripts
            String gitScript = loadResourceAsString("/js/git.js");
            cefBrowser.executeJavaScript(gitScript, frame.getURL(), 0);

            String gitUIScript = loadResourceAsString("/js/git-ui.js");
            cefBrowser.executeJavaScript(gitUIScript, frame.getURL(), 0);

            // Load and inject the LLM Provider script
            String llmProviderScript = loadResourceAsString("/js/LLMProvider.js");
            cefBrowser.executeJavaScript(llmProviderScript, frame.getURL(), 0);

            // Load and inject the Agent Framework scripts
            String agentFrameworkScript = loadResourceAsString("/js/agentFramework.js");
            cefBrowser.executeJavaScript(agentFrameworkScript, frame.getURL(), 0);

            // Load and inject the File API script
            String fileAPIScript = loadResourceAsString("/js/fileAPI.js");
            cefBrowser.executeJavaScript(fileAPIScript, frame.getURL(), 0);

            // Load and inject the Research Agent script
            String researchAgentScript = loadResourceAsString("/js/researchAgentIntegrated.js");
            cefBrowser.executeJavaScript(researchAgentScript, frame.getURL(), 0);

            // Load and inject the Workflow Engine script
            String workflowEngineScript = loadResourceAsString("/js/workflowEngine.js");
            cefBrowser.executeJavaScript(workflowEngineScript, frame.getURL(), 0);

            // Load and inject the Workflow Builder script
            String workflowBuilderScript = loadResourceAsString("/js/workflowBuilder.js");
            cefBrowser.executeJavaScript(workflowBuilderScript, frame.getURL(), 0);

            String agentUIScript = loadResourceAsString("/js/agentUI.js");
            cefBrowser.executeJavaScript(agentUIScript, frame.getURL(), 0);

            String googleAgentIntegrationScript = loadResourceAsString("/js/googleAgentIntegration.js");
            cefBrowser.executeJavaScript(googleAgentIntegrationScript, frame.getURL(), 0);

            // Load startup notification script
            String agentStartupScript = loadResourceAsString("/js/agentStartup.js");
            cefBrowser.executeJavaScript(agentStartupScript, frame.getURL(), 0);

            LOG.info("JavaScript bridge initialized successfully with chunked messaging support, git integration, LLM Provider, Agent Framework, and Research Agent");
        } catch (Exception e) {
            LOG.error("Failed to setup JavaScript bridge", e);
        }
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

    String interceptorScript;

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
     *
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

    public JavaScriptBridge getJavaScriptBridge() {
        return jsBridge;
    }

    /*
    // Commented out - no longer using custom protocol
    private void registerResourceSchemeHandler() {
        try {
            // Use JBCefLocalRequestHandler for more reliable resource handling
            JBCefLocalRequestHandler localRequestHandler =
                    new JBCefLocalRequestHandler("jcef", "resource");

            // Register HTML resources
            localRequestHandler.addResource("/html/workflowBuilder.html", () -> {
                InputStream stream = getClass().getResourceAsStream("/html/workflowBuilder.html");
                return stream != null ? htmlResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/html/agentDemo.html", () -> {
                InputStream stream = getClass().getResourceAsStream("/html/agentDemo.html");
                return stream != null ? htmlResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/html/test.html", () -> {
                InputStream stream = getClass().getResourceAsStream("/html/test.html");
                return stream != null ? htmlResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/html/jcefTest.html", () -> {
                InputStream stream = getClass().getResourceAsStream("/html/jcefTest.html");
                return stream != null ? htmlResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/html/resourceTest.html", () -> {
                InputStream stream = getClass().getResourceAsStream("/html/resourceTest.html");
                return stream != null ? htmlResourceHandler(stream) : null;
            });

            // Register JavaScript resources
            localRequestHandler.addResource("/js/agentFramework.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/agentFramework.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/workflowEngine.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/workflowEngine.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/workflowBuilder.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/workflowBuilder.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/agentUI.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/agentUI.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/agentStartup.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/agentStartup.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/LLMProvider.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/LLMProvider.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/fileAPI.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/fileAPI.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/researchAgentIntegrated.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/researchAgentIntegrated.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            localRequestHandler.addResource("/js/googleAgentIntegration.js", () -> {
                InputStream stream = getClass().getResourceAsStream("/js/googleAgentIntegration.js");
                return stream != null ? javascriptResourceHandler(stream) : null;
            });

            // Register CSS resources
            localRequestHandler.addResource("/css/test.css", () -> {
                InputStream stream = getClass().getResourceAsStream("/css/test.css");
                return stream != null ? cssResourceHandler(stream) : null;
            });

            // Add the request handler to the browser client before creating the browser
            browser.getJBCefClient().addRequestHandler(localRequestHandler, browser.getCefBrowser());

            LOG.info("Local request handler registered for jcef://resource/ with JBCefLocalRequestHandler pattern");
        } catch (Exception e) {
            LOG.error("Failed to register local request handler", e);
        }
    }

    private @NotNull JBCefStreamResourceHandler cssResourceHandler(InputStream stream) {
        return new JBCefStreamResourceHandler(stream, "text/css", this, new HashMap<>());
    }

    private @NotNull JBCefStreamResourceHandler javascriptResourceHandler(InputStream stream) {
        return new JBCefStreamResourceHandler(stream, "application/javascript", this, new HashMap<>());
    }

    private @NotNull JBCefStreamResourceHandler htmlResourceHandler(InputStream stream) {
        return new JBCefStreamResourceHandler(stream, "text/html", this, new HashMap<>());
    }
    */

    /**
     * Loads an HTML file from resources
     *
     * @param resourcePath Path to the HTML file in resources
     */
    public void loadHTMLFromResource(String resourcePath) {
        if (!resourcePath.startsWith("/")) {
            resourcePath = "/" + resourcePath;
        }

        try {
            String htmlContent = loadResourceAsString(resourcePath);
            
            // Replace any jcef://resource references with inline content or data URLs
            htmlContent = processResourceReferences(htmlContent);
            
            // Use loadHTML to load the content directly
            browser.loadHTML(htmlContent);
            LOG.info("Loaded HTML from resource: " + resourcePath);
        } catch (IOException e) {
            LOG.error("Failed to load HTML resource: " + resourcePath, e);
            browser.loadHTML("<html><body><h1>Error loading resource</h1><p>" + e.getMessage() + "</p></body></html>");
        }
    }
    
    /**
     * Processes resource references in HTML content to replace jcef:// URLs
     */
    private String processResourceReferences(String htmlContent) {
        try {
            // Process script tags
            java.util.regex.Pattern scriptPattern = java.util.regex.Pattern.compile(
                "<script\\s+src=\"jcef://resource(/[^\"]+)\"[^>]*>", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher scriptMatcher = scriptPattern.matcher(htmlContent);
            
            while (scriptMatcher.find()) {
                String fullMatch = scriptMatcher.group(0);
                String resourcePath = scriptMatcher.group(1);
                try {
                    String content = loadResourceAsString(resourcePath);
                    String replacement = "<script>\n" + content + "\n</script>\n<!-- Originally from: " + resourcePath + " -->";
                    htmlContent = htmlContent.replace(fullMatch, replacement);
                } catch (IOException e) {
                    LOG.warn("Failed to load script resource: " + resourcePath, e);
                    String replacement = "<script>\nconsole.error('Failed to load resource: " + resourcePath + "');\n</script>";
                    htmlContent = htmlContent.replace(fullMatch, replacement);
                }
            }
            
            // Process CSS link tags
            java.util.regex.Pattern cssPattern = java.util.regex.Pattern.compile(
                "<link\\s+[^>]*?href=\"jcef://resource(/[^\"]+)\"[^>]*>",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher cssMatcher = cssPattern.matcher(htmlContent);
            
            while (cssMatcher.find()) {
                String fullMatch = cssMatcher.group(0);
                String resourcePath = cssMatcher.group(1);
                try {
                    String content = loadResourceAsString(resourcePath);
                    String replacement = "<style>\n" + content + "\n</style>\n<!-- Originally from: " + resourcePath + " -->";
                    htmlContent = htmlContent.replace(fullMatch, replacement);
                } catch (IOException e) {
                    LOG.warn("Failed to load CSS resource: " + resourcePath, e);
                    String replacement = "<style>\n/* Failed to load resource: " + resourcePath + " */\n</style>";
                    htmlContent = htmlContent.replace(fullMatch, replacement);
                }
            }
            
            // Process fetch() calls in JavaScript - inject resource content as variables
            if (htmlContent.contains("fetch") && htmlContent.contains("jcef://resource")) {
                htmlContent = injectResourcesAsVariables(htmlContent);
            }
            
            return htmlContent;
        } catch (Exception e) {
            LOG.error("Error processing resource references", e);
            return htmlContent;
        }
    }
    
    /**
     * Injects resources as JavaScript variables for dynamic loading
     */
    private String injectResourcesAsVariables(String htmlContent) {
        StringBuilder resourceScript = new StringBuilder();
        resourceScript.append("<script>\n");
        resourceScript.append("// Injected resources for dynamic loading\n");
        resourceScript.append("window.__INJECTED_RESOURCES__ = {};\n");
        
        // List of resources that might be dynamically loaded
        String[] dynamicResources = {
            // JavaScript files
            "/js/agentFramework.js",
            "/js/workflowEngine.js", 
            "/js/workflowBuilder.js",
            "/js/agentUI.js",
            "/js/LLMProvider.js",
            "/js/fileAPI.js",
            "/js/researchAgentIntegrated.js",
            "/js/googleAgentIntegration.js",
            "/js/test.js",
            "/js/codeExtractor.js",
            "/js/responseParser.js",
            "/js/git.js",
            "/js/git-ui.js",
            "/js/intellijBridge.js",
            "/js/intellijBridgeChunked.js",
            "/js/interceptor.js",
            "/js/agentStartup.js",
            
            // HTML files
            "/html/agentDemo.html",
            "/html/workflowBuilder.html",
            "/html/test.html",
            "/html/jcefTest.html",
            "/html/resourceTest.html",
            
            // CSS files
            "/css/test.css",
            
            // Data files
            "/data/test.json"
        };
        
        for (String resource : dynamicResources) {
            try {
                String content = loadResourceAsString(resource);
                // Convert to base64 to avoid escaping issues
                String base64Content = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
                resourceScript.append("window.__INJECTED_RESOURCES__['").append(resource).append("'] = atob('").append(base64Content).append("');\n");
            } catch (IOException e) {
                // Resource might not exist, that's okay
                LOG.debug("Resource not found (this may be expected): " + resource);
            }
        }
        
        // Add a helper to list available resources
        resourceScript.append("\n// Helper to list available resources\n");
        resourceScript.append("window.__LIST_RESOURCES__ = function() {\n");
        resourceScript.append("  return Object.keys(window.__INJECTED_RESOURCES__);\n");
        resourceScript.append("};\n");
        
        // Override fetch to handle jcef:// URLs
        resourceScript.append("\n// Override fetch for jcef:// URLs\n");
        resourceScript.append("""
(function() {
  const originalFetch = window.fetch;
  window.fetch = function(url, ...args) {
    if (typeof url === 'string' && url.startsWith('jcef://resource/')) {
      const resourcePath = url.replace('jcef://resource', '');
      const content = window.__INJECTED_RESOURCES__[resourcePath];
      if (content !== undefined) {
        return Promise.resolve(new Response(content, {
          status: 200,
          statusText: 'OK',
          headers: new Headers({
            'Content-Type': resourcePath.endsWith('.js') ? 'application/javascript' :
                           resourcePath.endsWith('.css') ? 'text/css' :
                           resourcePath.endsWith('.html') ? 'text/html' :
                           resourcePath.endsWith('.json') ? 'application/json' : 'text/plain'
          })
        }));
      } else {
        console.warn('Resource not found:', resourcePath);
        console.log('Available resources:', Object.keys(window.__INJECTED_RESOURCES__));
        return Promise.resolve(new Response('Resource not found: ' + resourcePath, {
          status: 404,
          statusText: 'Not Found'
        }));
      }
    }
    return originalFetch(url, ...args);
  };
  
  // Also override XMLHttpRequest for compatibility
  const originalXHROpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url, ...args) {
    if (typeof url === 'string' && url.startsWith('jcef://resource/')) {
      const resourcePath = url.replace('jcef://resource', '');
      const content = window.__INJECTED_RESOURCES__[resourcePath];
      if (content !== undefined) {
        // Convert to data URL for XHR
        const mimeType = resourcePath.endsWith('.js') ? 'application/javascript' :
                        resourcePath.endsWith('.css') ? 'text/css' :
                        resourcePath.endsWith('.html') ? 'text/html' :
                        resourcePath.endsWith('.json') ? 'application/json' : 'text/plain';
        const dataUrl = 'data:' + mimeType + ';base64,' + btoa(content);
        return originalXHROpen.call(this, method, dataUrl, ...args);
      }
    }
    return originalXHROpen.call(this, method, url, ...args);
  };
})();
""");
        resourceScript.append("</script>\n");
        
        // Inject at the beginning of the body or head
        int bodyIndex = htmlContent.indexOf("<body");
        if (bodyIndex != -1) {
            int bodyEndIndex = htmlContent.indexOf(">", bodyIndex) + 1;
            return htmlContent.substring(0, bodyEndIndex) + "\n" + resourceScript.toString() + htmlContent.substring(bodyEndIndex);
        } else {
            // Fallback: inject before closing head tag
            int headCloseIndex = htmlContent.indexOf("</head>");
            if (headCloseIndex != -1) {
                return htmlContent.substring(0, headCloseIndex) + resourceScript.toString() + htmlContent.substring(headCloseIndex);
            }
        }
        
        return htmlContent;
    }
}