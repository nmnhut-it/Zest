package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

/**
 * Helper class for testing browser functionality.
 * This is not a unit test but a manual verification tool.
 */
public class BrowserTestHelper {
    private static final Logger LOG = Logger.getInstance(BrowserTestHelper.class);
    
    /**
     * Runs a basic functionality test of the browser component.
     */
    public static void runBasicTest(Project project) {
        LOG.info("Running basic browser test");
        
        try {
            // Get browser service
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            
            if (browserService == null) {
                showError(project, "Error: WebBrowserService not available");
                return;
            }
            
            // Check if browser panel is registered
            if (browserService.getBrowserPanel() == null) {
                showError(project, "Error: Browser panel not registered with service");
                return;
            }
            
            // Load the demo page
            DemoPageLoader.loadDemoPage(project);
            
            // Execute test JavaScript
            String testResult = executeTestJavaScript(browserService);
            
            // Show success message
            Messages.showInfoMessage(project, 
                    "Browser component test successful!\n\n" + testResult, 
                    "Browser Test");
            
            LOG.info("Browser test completed successfully");
        } catch (Exception e) {
            LOG.error("Error running browser test", e);
            showError(project, "Error running browser test: " + e.getMessage());
        }
    }
    
    /**
     * Executes test JavaScript in the browser.
     */
    private static String executeTestJavaScript(WebBrowserService browserService) {
        String testScript = 
                "(() => {\n" +
                "  let results = [];\n" +
                "  \n" +
                "  // Test 1: Check if bridge is available\n" +
                "  const bridgeAvailable = window.intellijBridge !== undefined;\n" +
                "  results.push(`Bridge available: ${bridgeAvailable}`);\n" +
                "  \n" +
                "  // Test 2: Check if demo script is loaded\n" +
                "  const demoScriptLoaded = window.intellijBridgeDemo !== undefined;\n" +
                "  results.push(`Demo script loaded: ${demoScriptLoaded}`);\n" +
                "  \n" +
                "  // Test 3: Document title\n" +
                "  results.push(`Page title: ${document.title}`);\n" +
                "  \n" +
                "  return results.join('\\n');\n" +
                "})();";
        
        // Execute the script and return the result
        browserService.executeJavaScript(
                "(() => {\n" +
                "  const result = " + testScript + ";\n" +
                "  console.log('Test result:', result);\n" +
                "  return result;\n" +
                "})();"
        );
        
        return "JavaScript test executed. Check browser console for results.";
    }
    
    /**
     * Shows an error message.
     */
    private static void showError(Project project, String message) {
        LOG.error(message);
        Messages.showErrorDialog(project, message, "Browser Test Error");
    }
}
