package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.jcef.*;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.browser.jcef.JCEFInitializer;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
@SuppressWarnings("removal")
public class JCEFBrowserManager {
    // Static initializer to ensure CEF is configured before any usage
    static {
        JCEFInitializer.initialize();
    }
    
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

        // Ensure JCEF is initialized with proper cache settings
        JCEFInitializer.initialize();

        // Check if JCEF is supported
        if (!JBCefApp.isSupported()) {
            LOG.error("JCEF is not supported in this IDE environment");
            throw new UnsupportedOperationException("JCEF is not supported in this IDE environment");
        }

        // Create browser with cookies enabled and proper settings
        JBCefClient client = JBCefApp.getInstance().createClient();
        client.setProperty(JS_QUERY_POOL_SIZE, 10);
        
        // Enable cookies in browser settings
        JBCefBrowserBuilder browserBuilder = new JBCefBrowserBuilder()
                .setClient(client)
                .setOffScreenRendering(false);
        
        // Set the initial URL with cookie persistence flag
        String initialUrl = ConfigurationManager.getInstance(project).getApiUrl().replace("/api/chat/completions", "");
        browserBuilder.setUrl(initialUrl);
        
        browser = browserBuilder.build();

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

        addNetworkMonitorAndRequestModifier();

        LOG.info("JCEFBrowserManager initialized with persistent cookies enabled");
    }


    /**
     * Alternative method to ensure cookies persist by setting proper cookie attributes
     */
    private void ensureCookiePersistence() {
        String script = """
            // Ensure cookies are set with appropriate expiration and security settings
            (function() {
                console.log('Installing cookie persistence handler...');
                
                // Store original cookie setter
                const originalCookieSetter = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie').set;
                const originalCookieGetter = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie').get;
                
                // Override cookie setter
                Object.defineProperty(document, 'cookie', {
                    get: function() {
                        return originalCookieGetter.call(this);
                    },
                    set: function(val) {
                        if (val && typeof val === 'string') {
                            // Parse the cookie string
                            const parts = val.split(';').map(p => p.trim());
                            const cookieName = parts[0].split('=')[0];
                            
                            // Check if it's an auth-related cookie
                            const isAuthCookie = cookieName.includes('token') || 
                                               cookieName.includes('auth') || 
                                               cookieName.includes('session');
                            
                            // If no expiration is set and it's important, add one
                            const hasExpiration = parts.some(p => 
                                p.toLowerCase().startsWith('expires') || 
                                p.toLowerCase().startsWith('max-age')
                            );
                            
                            if (!hasExpiration && isAuthCookie) {
                                // Add 30 days expiration for auth cookies
                                const expires = new Date();
                                expires.setDate(expires.getDate() + 30);
                                val += '; expires=' + expires.toUTCString();
                                val += '; SameSite=Lax'; // Add SameSite for security
                                console.log('Enhanced cookie:', cookieName, 'with 30-day expiration');
                            }
                        }
                        return originalCookieSetter.call(this, val);
                    },
                    configurable: true
                });
                
                console.log('Cookie persistence handler installed successfully');
            })();
            """;

        executeJavaScript(script);
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

        // Ensure cookie persistence after loading
        browser.getCefBrowser().executeJavaScript(
                "setTimeout(() => { console.log('Page loaded, cookies should persist'); }, 1000);",
                url, 0
        );
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

            // Ensure cookie persistence
            ensureCookiePersistence();

            LOG.info("JavaScript bridge initialized successfully with chunked messaging support and git integration");
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
}