package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.nio.file.Path;

/**
 * Utility class to load the demo page demonstrating the browser bridge functionality.
 */
public class DemoPageLoader {
    private static final Logger LOG = Logger.getInstance(DemoPageLoader.class);
    private static final String DEMO_PAGE_PATH = "/webpages/demo.html";
    private static final String DEMO_CSS_PATH = "/webpages/demo.css";
    private static final String DEMO_JS_PATH = "/webpages/demo.js";
    
    /**
     * Loads the demo page in the browser.
     */
    public static void loadDemoPage(Project project) {
        try {
            // Create temporary files for resources
            Path htmlFile = WebResourceLoader.createTempFileFromResource(
                DEMO_PAGE_PATH, "zest-browser-demo", ".html");
            Path cssFile = WebResourceLoader.createTempFileFromResource(
                DEMO_CSS_PATH, "zest-browser-demo", ".css");
            Path jsFile = WebResourceLoader.createTempFileFromResource(
                DEMO_JS_PATH, "zest-browser-demo", ".js");
            
            if (htmlFile == null) {
                LOG.error("Failed to create temporary HTML file for demo page");
                return;
            }
            
            // Get the browser service
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            browserService.loadUrl(htmlFile.toUri().toString());
            
            // Activate the tool window
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Web Browser");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
            
            LOG.info("Demo page loaded successfully");
        } catch (Exception e) {
            LOG.error("Error loading demo page", e);
        }
    }
}
