package com.zps.zest.browser.jcef;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefApp;
import org.cef.CefSettings;

import java.io.File;

/**
 * Ensures JCEF is properly initialized with persistent cache before any browser instances are created.
 * This class should be loaded early in the plugin lifecycle.
 */
public class JCEFInitializer {
    private static final Logger LOG = Logger.getInstance(JCEFInitializer.class);
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();
    
    /**
     * Initialize JCEF with proper settings for cookie persistence.
     * This should be called before any JCEF browser instances are created.
     */
    public static void initialize() {
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            
            try {
                // Set up cache directory
                File userHome = new File(System.getProperty("user.home"));
                File cacheDir = new File(userHome, ".intellij-zest-browser-cache");
                
                // Try to create cache directory in a platform-appropriate location
                String osName = System.getProperty("os.name").toLowerCase();
                if (osName.contains("win")) {
                    // Windows: Use AppData
                    File appData = new File(System.getenv("APPDATA"));
                    cacheDir = new File(appData, "IntelliJZestBrowser/Cache");
                } else if (osName.contains("mac")) {
                    // macOS: Use Library/Caches
                    File library = new File(userHome, "Library/Caches");
                    cacheDir = new File(library, "IntelliJZestBrowser");
                } else {
                    // Linux/Unix: Use .cache
                    File dotCache = new File(userHome, ".cache");
                    cacheDir = new File(dotCache, "intellij-zest-browser");
                }
                
                if (!cacheDir.exists()) {
                    if (!cacheDir.mkdirs()) {
                        LOG.warn("Failed to create cache directory: " + cacheDir.getAbsolutePath());
                        // Fallback to temp directory
                        cacheDir = new File(System.getProperty("java.io.tmpdir"), "intellij-zest-cache");
                        if (!cacheDir.exists()) {
                            cacheDir.mkdirs();
                        }
                    }
                }
                
                LOG.info("Setting JCEF cache directory to: " + cacheDir.getAbsolutePath());
                
                // Set system properties before JCEF initialization
                System.setProperty("jcef.cache_path", cacheDir.getAbsolutePath());
                System.setProperty("jcef.persist_session_cookies", "true");
                System.setProperty("jcef.log_severity", "warning");
                System.setProperty("jcef.log_file", new File(cacheDir, "jcef.log").getAbsolutePath());
                
                // These properties might help with cookie persistence
                System.setProperty("jcef.cookie_persist_session", "true");
                System.setProperty("jcef.cookie_storage_type", "sqlite");
                
                // Check if JCEF is supported before trying to initialize
                if (JBCefApp.isSupported()) {
                    // Force initialization by getting the instance
                    JBCefApp app = JBCefApp.getInstance();
                    
                    // Try to configure settings if possible
                    try {
                        CefSettings settings = app.getCefSettings();
                        if (settings != null) {
                            settings.cache_path = cacheDir.getAbsolutePath();
                            settings.persist_session_cookies = true;
                            settings.user_agent = "IntelliJ IDEA Zest Browser";
                            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
                            settings.log_file = new File(cacheDir, "cef.log").getAbsolutePath();
                            LOG.info("Successfully configured CEF settings for cookie persistence");
                        }
                    } catch (Exception e) {
                        LOG.warn("Could not modify CEF settings after initialization", e);
                    }
                    
                    LOG.info("JCEF initialized successfully with cache at: " + cacheDir.getAbsolutePath());
                } else {
                    LOG.warn("JCEF is not supported in this environment");
                }
                
                initialized = true;
            } catch (Exception e) {
                LOG.error("Failed to initialize JCEF", e);
                initialized = true; // Prevent repeated failed attempts
            }
        }
    }
    
    /**
     * Check if JCEF has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
