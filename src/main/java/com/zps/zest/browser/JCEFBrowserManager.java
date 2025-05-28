package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.*;
import com.zps.zest.ConfigurationManager;
import org.cef.CefApp;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import javax.swing.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.cef.CefApp;
import org.cef.callback.CefCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
@SuppressWarnings("removal")
public class JCEFBrowserManager {
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
        
        // Register custom scheme handler for resources before creating browser
        registerResourceSchemeHandler();

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
        loadHTMLFromResource("/html/test.html");
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
            
            // Load and inject the Agent Framework scripts
            String agentFrameworkScript = loadResourceAsString("/js/agentFramework.js");
            cefBrowser.executeJavaScript(agentFrameworkScript, frame.getURL(), 0);
            
            String agentUIScript = loadResourceAsString("/js/agentUI.js");
            cefBrowser.executeJavaScript(agentUIScript, frame.getURL(), 0);
            
            String googleAgentIntegrationScript = loadResourceAsString("/js/googleAgentIntegration.js");
            cefBrowser.executeJavaScript(googleAgentIntegrationScript, frame.getURL(), 0);
            
            // Load startup notification script
            String agentStartupScript = loadResourceAsString("/js/agentStartup.js");
            cefBrowser.executeJavaScript(agentStartupScript, frame.getURL(), 0);
            
            LOG.info("JavaScript bridge initialized successfully with chunked messaging support, git integration, and Agent Framework");
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

    public JavaScriptBridge getJavaScriptBridge() {
        return jsBridge;
    }
    
    /**
     * Registers a custom scheme handler for loading resources
     */
    private void registerResourceSchemeHandler() {
        try {
            CefApp.getInstance().registerSchemeHandlerFactory("jcef", "resource", new CefSchemeHandlerFactory() {
                @Override
                public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
                    String url = request.getURL();
                    LOG.info("Resource request: " + url);
                    
                    // Extract resource path from URL (jcef://resource/path/to/file)
                    if (url.startsWith("jcef://resource/")) {
                        String resourcePath = url.substring("jcef://resource".length());
                        if (!resourcePath.startsWith("/")) {
                            resourcePath = "/" + resourcePath;
                        }
                        return new ResourceHandler(resourcePath);
                    }
                    return null;
                }
            });
            
            LOG.info("Resource scheme handler registered for jcef://resource/");
        } catch (Exception e) {
            LOG.error("Failed to register resource scheme handler", e);
        }
    }
    
    /**
     * Loads an HTML file from resources
     * @param resourcePath Path to the HTML file in resources
     */
    public void loadHTMLFromResource(String resourcePath) {
        if (!resourcePath.startsWith("/")) {
            resourcePath = "/" + resourcePath;
        }
        
        String url = "jcef://resource" + resourcePath;
        LOG.info("Loading HTML from resource: " + url);
        browser.loadURL(url);
    }
    
    /**
     * Custom resource handler for serving files from resources
     */
    private class ResourceHandler implements CefResourceHandler {
        private final String resourcePath;
        private byte[] data;
        private int offset = 0;
        private String mimeType = "text/html";
        
        public ResourceHandler(String resourcePath) {
            this.resourcePath = resourcePath;
            loadResource();
        }
        
        private void loadResource() {
            try {
                try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        data = is.readAllBytes();
                        
                        // Determine MIME type based on file extension
                        if (resourcePath.endsWith(".js")) {
                            mimeType = "application/javascript";
                        } else if (resourcePath.endsWith(".css")) {
                            mimeType = "text/css";
                        } else if (resourcePath.endsWith(".html")) {
                            mimeType = "text/html";
                        } else if (resourcePath.endsWith(".json")) {
                            mimeType = "application/json";
                        } else if (resourcePath.endsWith(".png")) {
                            mimeType = "image/png";
                        } else if (resourcePath.endsWith(".jpg") || resourcePath.endsWith(".jpeg")) {
                            mimeType = "image/jpeg";
                        } else if (resourcePath.endsWith(".gif")) {
                            mimeType = "image/gif";
                        } else if (resourcePath.endsWith(".svg")) {
                            mimeType = "image/svg+xml";
                        }
                        
                        LOG.info("Loaded resource: " + resourcePath + " (" + data.length + " bytes, " + mimeType + ")");
                    } else {
                        LOG.error("Resource not found: " + resourcePath);
                        data = ("Resource not found: " + resourcePath).getBytes(StandardCharsets.UTF_8);
                        mimeType = "text/plain";
                    }
                }
            } catch (IOException e) {
                LOG.error("Failed to load resource: " + resourcePath, e);
                data = ("Error loading resource: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                mimeType = "text/plain";
            }
        }
        
        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            callback.Continue();
            return true;
        }
        
        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(data.length > 0 ? 200 : 404);
            response.setMimeType(mimeType);
            responseLength.set(data.length);
            response.setHeaderByName("Access-Control-Allow-Origin", "*", true);
        }
        
        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            if (offset >= data.length) {
                bytesRead.set(0);
                return false;
            }
            
            int availableBytes = Math.min(bytesToRead, data.length - offset);
            System.arraycopy(data, offset, dataOut, 0, availableBytes);
            offset += availableBytes;
            bytesRead.set(availableBytes);
            
            return offset < data.length;
        }
        
        @Override
        public void cancel() {
            // Nothing to clean up
        }
    }
}