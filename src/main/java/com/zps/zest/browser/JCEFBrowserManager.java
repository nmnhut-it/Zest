package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.jcef.*;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.browser.jcef.JCEFInitializer;
import com.zps.zest.browser.jcef.JCEFResourceManager;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Manages the JCEF browser instance and provides a simplified API for browser interactions.
 */
@SuppressWarnings("removal")
public class JCEFBrowserManager implements Disposable {
    // Static initializer to ensure CEF is configured before any usage
    static {
        JCEFInitializer.initialize();
    }

    private static final Logger LOG = Logger.getInstance(JCEFBrowserManager.class);

    private final JBCefBrowser browser;
    private final Project project;
    private final JavaScriptBridge jsBridge;
    private JBCefJSQuery jsQuery;

    /**
     * Creates a new browser manager with the specified project.
     *
     * @param project The IntelliJ project context
     */
    public JCEFBrowserManager(Project project) {
        this.project = project;
        LOG.info("Creating JCEFBrowserManager for project: " + project.getName() +
                 " (This should be reused when possible to avoid resource waste)");

        // Ensure JCEF is initialized with proper cache settings
        JCEFInitializer.initialize();

        // Check if JCEF is supported
        if (!JBCefApp.isSupported()) {
            LOG.error("JCEF is not supported in this IDE environment");
            throw new UnsupportedOperationException("JCEF is not supported in this IDE environment");
        }

        // Use the shared client instead of creating a new one
        JBCefClient client = JCEFClientProvider.getSharedClient();

        // Enable cookies in browser settings
        JBCefBrowserBuilder browserBuilder = new JBCefBrowserBuilder()
                .setClient(client)
                .setOffScreenRendering(false)
                .setCefBrowser(null)
                .setEnableOpenDevToolsMenuItem(true);
        
        // Set the initial URL with cookie persistence flag
        String initialUrl = ConfigurationManager.getInstance(project).getApiUrl().replace("/api/chat/completions", "");
        browserBuilder.setUrl(initialUrl);
        
        browser = browserBuilder.build();
        
        // Register browser with resource manager for proper tracking and disposal
        JCEFResourceManager.getInstance().registerBrowser(browser, project);
        
        // Force enable cookies and local storage at the browser level
        browser.getCefBrowser().executeJavaScript(
            "console.log('Browser initialized with persistent storage enabled');", 
            initialUrl, 0
        );

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
     * Alternative method to ensure cookies and localStorage persist by setting proper attributes
     */
    private void ensureCookiePersistence() {
        String script = """
            // Ensure cookies and localStorage are persisted properly
            (function() {
                console.log('Installing persistence handlers for cookies and localStorage...');
                
                // === COOKIE PERSISTENCE ===
                // Store original cookie setter
                const originalCookieSetter = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie').set;
                const originalCookieGetter = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie').get;
                
                // Override cookie setter to ensure persistence
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
                                               cookieName.includes('session') ||
                                               cookieName.includes('user') ||
                                               cookieName.includes('jwt');
                            
                            // If no expiration is set and it's important, add one
                            const hasExpiration = parts.some(p => 
                                p.toLowerCase().startsWith('expires') || 
                                p.toLowerCase().startsWith('max-age')
                            );
                            
                            if (!hasExpiration && isAuthCookie) {
                                // Add 90 days expiration for auth cookies
                                const expires = new Date();
                                expires.setDate(expires.getDate() + 90);
                                val += '; expires=' + expires.toUTCString();
                                val += '; SameSite=Lax'; // Add SameSite for security
                                val += '; Secure'; // Ensure HTTPS only
                                console.log('Enhanced cookie:', cookieName, 'with 90-day expiration');
                            }
                        }
                        return originalCookieSetter.call(this, val);
                    },
                    configurable: true
                });
                
                // === LOCALSTORAGE PERSISTENCE ===
                // Create a backup mechanism for localStorage
                const STORAGE_BACKUP_KEY = '__zest_storage_backup__';
                const AUTH_KEYS = ['token', 'auth', 'session', 'user', 'jwt', 'access', 'refresh', 'bearer', 'api', 'key'];
                
                // Function to check if a key is auth-related
                const isAuthKey = (key) => {
                    const lowerKey = key.toLowerCase();
                    return AUTH_KEYS.some(authKey => lowerKey.includes(authKey)) || 
                           key === 'token' ||
                           key === 'authToken' || 
                           key === 'auth_token' || 
                           key === 'access_token';
                };
                
                // Backup current localStorage data
                const backupLocalStorage = () => {
                    try {
                        const backup = {};
                        for (let i = 0; i < localStorage.length; i++) {
                            const key = localStorage.key(i);
                            if (isAuthKey(key)) {
                                backup[key] = localStorage.getItem(key);
                            }
                        }
                        
                        // Store backup in a persistent way
                        if (Object.keys(backup).length > 0) {
                            // Try to store in sessionStorage as additional backup
                            try {
                                sessionStorage.setItem(STORAGE_BACKUP_KEY, JSON.stringify(backup));
                            } catch (e) {
                                console.warn('SessionStorage backup failed:', e);
                            }
                            
                            // Also store with extended expiration in localStorage itself
                            localStorage.setItem(STORAGE_BACKUP_KEY, JSON.stringify({
                                data: backup,
                                timestamp: Date.now(),
                                expires: Date.now() + (90 * 24 * 60 * 60 * 1000) // 90 days
                            }));
                            
                            console.log('Backed up auth keys:', Object.keys(backup));
                        }
                    } catch (e) {
                        console.error('LocalStorage backup failed:', e);
                    }
                };
                
                // Restore localStorage from backup
                const restoreLocalStorage = () => {
                    try {
                        // Try to restore from localStorage backup first
                        const backupStr = localStorage.getItem(STORAGE_BACKUP_KEY);
                        if (backupStr) {
                            const backupData = JSON.parse(backupStr);
                            if (backupData.expires && backupData.expires > Date.now()) {
                                Object.entries(backupData.data).forEach(([key, value]) => {
                                    if (!localStorage.getItem(key)) {
                                        localStorage.setItem(key, value);
                                        console.log('Restored key from backup:', key);
                                    }
                                });
                            }
                        }
                        
                        // Try sessionStorage as fallback
                        const sessionBackup = sessionStorage.getItem(STORAGE_BACKUP_KEY);
                        if (sessionBackup) {
                            const backup = JSON.parse(sessionBackup);
                            Object.entries(backup).forEach(([key, value]) => {
                                if (!localStorage.getItem(key)) {
                                    localStorage.setItem(key, value);
                                    console.log('Restored key from session backup:', key);
                                }
                            });
                        }
                    } catch (e) {
                        console.error('LocalStorage restore failed:', e);
                    }
                };
                
                // Override localStorage methods to add persistence
                const originalSetItem = localStorage.setItem.bind(localStorage);
                const originalRemoveItem = localStorage.removeItem.bind(localStorage);
                const originalClear = localStorage.clear.bind(localStorage);
                
                localStorage.setItem = function(key, value) {
                    originalSetItem(key, value);
                    if (isAuthKey(key)) {
                        console.log('Persisting auth key:', key);
                        backupLocalStorage();
                    }
                };
                
                localStorage.removeItem = function(key) {
                    if (isAuthKey(key)) {
                        console.warn('Preventing removal of auth key:', key);
                        return; // Don't remove auth keys
                    }
                    originalRemoveItem(key);
                };
                
                localStorage.clear = function() {
                    // Save auth keys before clearing
                    const authData = {};
                    for (let i = 0; i < localStorage.length; i++) {
                        const key = localStorage.key(i);
                        if (isAuthKey(key)) {
                            authData[key] = localStorage.getItem(key);
                        }
                    }
                    
                    originalClear();
                    
                    // Restore auth keys
                    Object.entries(authData).forEach(([key, value]) => {
                        localStorage.setItem(key, value);
                    });
                    console.log('Cleared localStorage but preserved auth keys');
                };
                
                // Make backup and restore functions globally available
                window.backupLocalStorage = backupLocalStorage;
                window.restoreLocalStorage = restoreLocalStorage;
                
                // Initial backup and restore
                restoreLocalStorage();
                backupLocalStorage();
                
                // Periodic backup (every 5 minutes)
                setInterval(backupLocalStorage, 5 * 60 * 1000);
                
                // Backup on page unload
                window.addEventListener('beforeunload', backupLocalStorage);
                
                // Listen for storage events from other tabs/windows
                window.addEventListener('storage', (e) => {
                    if (e.key && isAuthKey(e.key) && !e.newValue) {
                        // Auth key was removed, restore it
                        console.log('Auth key removed, restoring:', e.key);
                        restoreLocalStorage();
                    }
                });
                
                console.log('Cookie and localStorage persistence handlers installed successfully');
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

        // Ensure cookie and localStorage persistence after loading
        browser.getCefBrowser().executeJavaScript(
                """
                setTimeout(() => { 
                    console.log('Page loaded, ensuring storage persistence...');
                    // Trigger persistence check
                    if (typeof window.__zest_storage_backup__ !== 'undefined') {
                        console.log('Storage persistence already initialized');
                    }
                }, 1000);
                """,
                url, 0
        );
        
        // Inject auth token after a short delay to ensure page is ready
        browser.getCefBrowser().executeJavaScript(
                """
                setTimeout(() => {
                    console.log('Checking for auth token injection after page load...');
                }, 500);
                """,
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
            String frameUrl = frame.getURL();
            LOG.info("Setting up JavaScript bridge for frame: " + frameUrl);
            
            // Check if this is the Git UI page - if so, only inject minimal scripts
            boolean isGitUI = frameUrl.contains("git-ui.html") || 
                            (frameUrl.startsWith("data:") && (frameUrl.contains("Git Operations") || frameUrl.contains("Git%20Operations")));
            boolean isLocalFile = frameUrl.startsWith("file://") || frameUrl.startsWith("jar:file://") || frameUrl.startsWith("data:");
            
            // For local files, don't inject auth tokens or cookie persistence
            if (!isLocalFile) {
                // Inject auth token from ConfigurationManager before setting up the bridge
                injectAuthToken(cefBrowser, frame);
            }

            // Create a JS query only if not already created
            if (jsQuery == null) {
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
            }

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

            // Always inject the bridge script
            cefBrowser.executeJavaScript(bridgeScript, frame.getURL(), 0);

            // For Git UI, no additional scripts needed - it's self-contained
            if (isGitUI) {
                LOG.info("Git UI page detected - no additional scripts needed");
            } else {
                // For non-Git UI pages, inject all scripts
                
                // Load and inject the response parser script
                String responseParserScript = loadResourceAsString("/js/responseParser.js");
                cefBrowser.executeJavaScript(responseParserScript, frame.getURL(), 0);

                // Load and inject the code extractor script
                String codeExtractorScript = loadResourceAsString("/js/codeExtractor.js");
                cefBrowser.executeJavaScript(codeExtractorScript, frame.getURL(), 0);
                
                // Load and inject the exploration UI script
//                String explorationUIScript = loadResourceAsString("/js/explorationUI.js");
//                cefBrowser.executeJavaScript(explorationUIScript, frame.getURL(), 0);
                
                // Load and inject the context debugger script
//                String contextDebuggerScript = loadResourceAsString("/js/contextDebugger.js");
//                cefBrowser.executeJavaScript(contextDebuggerScript, frame.getURL(), 0);

                // Load and inject the context toggle script
//                String contextToggleScript = loadResourceAsString("/js/context-toggle.js");
//                cefBrowser.executeJavaScript(contextToggleScript, frame.getURL(), 0);

                // Only ensure cookie persistence for non-local files
                if (!isLocalFile) {
                    // Ensure cookie persistence
                    ensureCookiePersistence();
                    
                    // Try to restore any saved browser state
                    restoreBrowserState();
                }
            }

            LOG.info("JavaScript bridge initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to setup JavaScript bridge", e);
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
        LOG.info("Disposing JCEFBrowserManager for project: " + project.getName());

        // Save browser state before disposing
        saveBrowserState();

        // Dispose JavaScript query
        if (jsQuery != null) {
            try {
                Disposer.dispose(jsQuery);
            } catch (Exception e) {
                LOG.warn("Error disposing jsQuery", e);
            }
            jsQuery = null;
        }

        // Dispose the browser properly using IntelliJ's disposal mechanism
        if (browser != null) {
            try {
                // First, navigate to about:blank to stop any running JavaScript
                browser.getCefBrowser().loadURL("about:blank");

                // Give it a moment to stop
                Thread.sleep(100);

                // Properly close the browser
                browser.getCefBrowser().close(true);

                // Wait a bit for the close to complete
                Thread.sleep(200);

                // Dispose the browser using IntelliJ's Disposer
                Disposer.dispose(browser);

                // Release the shared client reference (but don't dispose the client itself)
                JCEFClientProvider.releaseSharedClient();

                // Force garbage collection to help clean up native resources
                System.gc();

            } catch (Exception e) {
                LOG.warn("Error disposing browser", e);
            }
        }

        LOG.info("JCEFBrowserManager disposed");
    }

    /**
     * Saves the current browser state (cookies and localStorage) before disposal
     */
    private void saveBrowserState() {
        try {
            String saveScript = """
                (function() {
                    try {
                        // Force backup of all auth data
                        if (typeof backupLocalStorage === 'function') {
                            backupLocalStorage();
                            console.log('Browser state saved successfully');
                        }
                        
                        // Also try to persist cookies one more time
                        const cookies = document.cookie;
                        if (cookies) {
                            console.log('Current cookies will be preserved:', cookies.split(';').length + ' cookies');
                        }
                    } catch (e) {
                        console.error('Failed to save browser state:', e);
                    }
                })();
                """;
            
            executeJavaScript(saveScript);
            
            // Give it a moment to complete
            Thread.sleep(100);
        } catch (Exception e) {
            LOG.warn("Error saving browser state", e);
        }
    }

    /**
     * Restores browser state after initialization
     */
    private void restoreBrowserState() {
        try {
            String restoreScript = """
                (function() {
                    try {
                        // Trigger restoration if available
                        if (typeof restoreLocalStorage === 'function') {
                            restoreLocalStorage();
                            console.log('Browser state restoration attempted');
                        }
                    } catch (e) {
                        console.error('Failed to restore browser state:', e);
                    }
                })();
                """;
            
            executeJavaScript(restoreScript);
        } catch (Exception e) {
            LOG.warn("Error restoring browser state", e);
        }
    }

    /**
     * Injects the authentication token from ConfigurationManager into the browser's localStorage
     */
    private void injectAuthToken(CefBrowser cefBrowser, CefFrame frame) {
        try {
            String authToken = ConfigurationManager.getInstance(project).getAuthTokenNoPrompt();
            
            if (authToken != null && !authToken.trim().isEmpty()) {
                LOG.info("Injecting auth token into browser localStorage");
                
                // Escape the token for JavaScript string literal
                String escapedToken = authToken.replace("\\", "\\\\")
                                              .replace("'", "\\'")
                                              .replace("\"", "\\\"")
                                              .replace("\n", "\\n")
                                              .replace("\r", "\\r");
                
                String injectScript = String.format("""
                    (function() {
                        try {
                            // Set the auth token in localStorage
                            localStorage.setItem('token', '%s');
                            localStorage.setItem('authToken', '%s');
                            localStorage.setItem('auth_token', '%s');
                            localStorage.setItem('access_token', '%s');
                            
                            // Also try to set it in sessionStorage as backup
                            sessionStorage.setItem('token', '%s');
                            sessionStorage.setItem('authToken', '%s');
                            sessionStorage.setItem('auth_token', '%s');
                            sessionStorage.setItem('access_token', '%s');
                            
                            console.log('Auth token injected successfully from IDE configuration');
                            
                            // Trigger any auth-related events the web app might be listening for
                            window.dispatchEvent(new Event('storage'));
                            window.dispatchEvent(new CustomEvent('authTokenUpdated', { 
                                detail: { source: 'ide-injection' } 
                            }));
                            
                            // If there's a specific auth mechanism in the app, try to trigger it
                            if (typeof window.setAuthToken === 'function') {
                                window.setAuthToken('%s');
                            }
                            
                            // Force backup of the injected token
                            if (typeof window.backupLocalStorage === 'function') {
                                setTimeout(() => window.backupLocalStorage(), 100);
                            }
                        } catch (e) {
                            console.error('Failed to inject auth token:', e);
                        }
                    })();
                    """, escapedToken, escapedToken, escapedToken, escapedToken,
                         escapedToken, escapedToken, escapedToken, escapedToken,
                         escapedToken);
                
                cefBrowser.executeJavaScript(injectScript, frame.getURL(), 0);
                
                // Also set as a cookie for extra persistence
                String cookieScript = String.format("""
                    (function() {
                        try {
                            const expires = new Date();
                            expires.setDate(expires.getDate() + 90);
                            document.cookie = 'token=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                            document.cookie = 'authToken=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                            document.cookie = 'auth_token=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                            console.log('Auth token also set as cookies');
                        } catch (e) {
                            console.error('Failed to set auth cookie:', e);
                        }
                    })();
                    """, escapedToken, escapedToken, escapedToken);
                
                cefBrowser.executeJavaScript(cookieScript, frame.getURL(), 0);
            } else {
                LOG.info("No auth token found in configuration to inject");
            }
        } catch (Exception e) {
            LOG.error("Error injecting auth token", e);
        }
    }

    String  interceptorScript;
    String  toolInjectorScript;
//    String  projectModeInterceptorScript;
//    String  augmentedModeInterceptorScript;
//    String  interceptorAugmentedScript;
//    String  agentModeEnhancedScript;
    public void addNetworkMonitorAndRequestModifier() {
        try {
            // Ensure DevTools is enabled
            DevToolsRegistryManager.getInstance().ensureDevToolsEnabled();

            // Load the tool interceptor script from file
            if (interceptorScript == null) {
                interceptorScript = loadResourceAsString("/chat-ui/tool-interceptor.js");
            }
            // Load the tool injector script
            if (toolInjectorScript == null) {
                toolInjectorScript = loadResourceAsString("/chat-ui/tool-injector.js");
            }
//            if (projectModeInterceptorScript == null) {
//                projectModeInterceptorScript = loadResourceAsString("/js/projectModeInterceptor.js");
//            }
//            if (augmentedModeInterceptorScript == null) {
//                augmentedModeInterceptorScript = loadResourceAsString("/js/augmentedModeInterceptor.js");
//            }
//            if (interceptorAugmentedScript == null) {
//                interceptorAugmentedScript = loadResourceAsString("/js/interceptor-augmented.js");
//            }
//            if (agentModeEnhancedScript == null) {
//                agentModeEnhancedScript = loadResourceAsString("/js/agentModeEnhanced.js");
//            }

            // Add a load handler to inject our script when the page loads
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    // Inject our interceptor script
                    browser.executeJavaScript(interceptorScript, frame.getURL(), 0);
                    // Inject tool injector script
                    browser.executeJavaScript(toolInjectorScript, frame.getURL(), 0);
                    // Inject the project mode interceptor script
//                    browser.executeJavaScript(projectModeInterceptorScript, frame.getURL(), 0);
//                    // Inject the augmented mode interceptor script
//                    browser.executeJavaScript(augmentedModeInterceptorScript, frame.getURL(), 0);
//                    // Inject the enhanced interceptor with async support
//                    browser.executeJavaScript(interceptorAugmentedScript, frame.getURL(), 0);
//                    // Inject the enhanced agent mode script
//                    browser.executeJavaScript(agentModeEnhancedScript, frame.getURL(), 0);
                    LOG.info("Injected tool interceptor script for Agent Mode OpenAPI tool injection");
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
     * Updates the auth token in the browser when it changes in configuration.
     * This provides the reverse sync from ConfigurationManager to browser.
     */
    public void updateAuthTokenInBrowser(String newToken) {
        if (newToken == null || newToken.trim().isEmpty()) {
            LOG.info("No auth token to update in browser");
            return;
        }
        
        try {
            // Escape the token for JavaScript
            String escapedToken = newToken.replace("\\", "\\\\")
                                         .replace("'", "\\'")
                                         .replace("\"", "\\\"")
                                         .replace("\n", "\\n")
                                         .replace("\r", "\\r");
            
            String updateScript = String.format("""
                (function() {
                    try {
                        // Update auth token in localStorage
                        localStorage.setItem('token', '%s');
                        localStorage.setItem('authToken', '%s');
                        localStorage.setItem('auth_token', '%s');
                        localStorage.setItem('access_token', '%s');
                        
                        // Update in sessionStorage too
                        sessionStorage.setItem('token', '%s');
                        sessionStorage.setItem('authToken', '%s');
                        sessionStorage.setItem('auth_token', '%s');
                        sessionStorage.setItem('access_token', '%s');
                        
                        // Update cookies
                        const expires = new Date();
                        expires.setDate(expires.getDate() + 90);
                        document.cookie = 'token=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                        document.cookie = 'authToken=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                        document.cookie = 'auth_token=%s; expires=' + expires.toUTCString() + '; path=/; SameSite=Lax';
                        
                        console.log('Auth token updated from IDE configuration change');
                        
                        // Trigger storage events
                        window.dispatchEvent(new Event('storage'));
                        window.dispatchEvent(new CustomEvent('authTokenUpdated', { 
                            detail: { source: 'ide-config-update' } 
                        }));
                        
                        // Force backup
                        if (typeof window.backupLocalStorage === 'function') {
                            window.backupLocalStorage();
                        }
                        
                        // If the app has a specific auth update mechanism
                        if (typeof window.updateAuthToken === 'function') {
                            window.updateAuthToken('%s');
                        }
                    } catch (e) {
                        console.error('Failed to update auth token:', e);
                    }
                })();
                """, escapedToken, escapedToken, escapedToken, escapedToken,
                     escapedToken, escapedToken, escapedToken, escapedToken,
                     escapedToken, escapedToken, escapedToken,
                     escapedToken);
            
            executeJavaScript(updateScript);
            LOG.info("Auth token updated in browser");
            
        } catch (Exception e) {
            LOG.error("Error updating auth token in browser", e);
        }
    }
}