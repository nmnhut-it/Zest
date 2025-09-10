package com.zps.zest.langchain4j.agent.network;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Constants for the proxy server configuration.
 * Centralizes all hardcoded values for better maintainability.
 */
public final class ProxyServerConstants {
    
    // Prevent instantiation
    private ProxyServerConstants() {}
    
    // Server configuration
    public static final int MAX_REQUEST_SIZE = 10_000_000; // 10MB
    public static final String SERVICE_NAME = "zest-proxy";
    
    // API paths
    public static final String API_BASE_PATH = "/zest";
    public static final String OPENAPI_PATH = API_BASE_PATH + "/openapi.json";
    public static final String SWAGGER_UI_PATH = API_BASE_PATH + "/docs";
    public static final String REDOC_PATH = API_BASE_PATH + "/redoc";
    public static final String HEALTH_PATH = "/health";
    public static final String DEBUG_PATH = "/debug";
    
    // Orchestration endpoints
    public static final Set<String> ORCHESTRATION_ENDPOINTS = Set.of(
        "explore_code", 
        "execute_tool", 
        "list_tools", 
        "augment_query", 
        "get_config", 
        "update_config", 
        "status", 
        "get_current_context"
    );
    
    // CDN URLs for API documentation
    public static final String SWAGGER_UI_CSS_CDN = "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css";
    public static final String SWAGGER_UI_BUNDLE_CDN = "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js";
    public static final String SWAGGER_UI_PRESET_CDN = "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-standalone-preset.js";
    public static final String REDOC_CDN = "https://cdn.jsdelivr.net/npm/redoc@next/bundles/redoc.standalone.js";
    
    // Tool configuration
    public static final String BUNDLED_RIPGREP_RESOURCE_PREFIX = "/bin/rg-";
    public static final String RIPGREP_WINDOWS_BINARY = "rg-windows-64.exe";
    public static final String RIPGREP_UNIX_BINARY = "rg";
    
    // Application data directory names
    public static final String APP_NAME = "Zest";
    public static final String TOOLS_SUBDIR = "tools";
    
    /**
     * Get the application data directory for the current OS.
     * This is where we store persistent data like extracted binaries.
     */
    public static Path getAppDataDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        Path appDataPath;
        
        if (os.contains("windows")) {
            // Windows: %APPDATA%\Zest\tools
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                appDataPath = Paths.get(appData, APP_NAME, TOOLS_SUBDIR);
            } else {
                // Fallback to user home
                appDataPath = Paths.get(System.getProperty("user.home"), "." + APP_NAME.toLowerCase(), TOOLS_SUBDIR);
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            // macOS: ~/Library/Application Support/Zest/tools
            appDataPath = Paths.get(System.getProperty("user.home"), 
                "Library", "Application Support", APP_NAME, TOOLS_SUBDIR);
        } else {
            // Linux/Unix: ~/.local/share/zest/tools or XDG_DATA_HOME
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null) {
                appDataPath = Paths.get(xdgDataHome, APP_NAME.toLowerCase(), TOOLS_SUBDIR);
            } else {
                appDataPath = Paths.get(System.getProperty("user.home"), 
                    ".local", "share", APP_NAME.toLowerCase(), TOOLS_SUBDIR);
            }
        }
        
        return appDataPath;
    }
    
    /**
     * Detect the current platform for binary selection.
     */
    public static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        
        if (os.contains("windows")) {
            return "windows-x64"; // Most common Windows architecture
        } else if (os.contains("mac") || os.contains("darwin")) {
            // Detect Apple Silicon vs Intel
            return arch.contains("aarch64") || arch.contains("arm") ? "macos-arm64" : "macos-x64";
        } else if (os.contains("linux")) {
            return "linux-x64"; // Most common Linux architecture
        }
        
        return "unknown";
    }
    
    /**
     * Get the Swagger UI HTML template.
     */
    public static String getSwaggerUIHtml() {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Zest Code Explorer - Swagger UI</title>
                <link rel="stylesheet" href="%s">
            </head>
            <body>
                <div id="swagger-ui"></div>
                <script src="%s"></script>
                <script src="%s"></script>
                <script>
                window.onload = () => {
                    SwaggerUIBundle({
                        url: '%s',
                        dom_id: '#swagger-ui',
                        deepLinking: true,
                        presets: [
                            SwaggerUIBundle.presets.apis,
                            SwaggerUIStandalonePreset
                        ],
                        plugins: [
                            SwaggerUIBundle.plugins.DownloadUrl
                        ],
                        layout: "StandaloneLayout"
                    });
                }
                </script>
            </body>
            </html>
        """, SWAGGER_UI_CSS_CDN, SWAGGER_UI_BUNDLE_CDN, SWAGGER_UI_PRESET_CDN, OPENAPI_PATH);
    }
    
    /**
     * Get the ReDoc HTML template.
     */
    public static String getReDocHtml() {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Zest Code Explorer - ReDoc</title>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { margin: 0; padding: 0; }
                </style>
            </head>
            <body>
                <redoc spec-url="%s"></redoc>
                <script src="%s"></script>
            </body>
            </html>
        """, OPENAPI_PATH, REDOC_CDN);
    }
}