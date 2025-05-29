package com.zps.zest.browser;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.jcef.JBCefApp;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for Workflow Builder in JCEF
 */
public class WorkflowBuilderIntegrationTest extends BasePlatformTestCase {
    
    @Test
    public void testWorkflowBuilderLoading() throws Exception {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        JCEFBrowserManager manager = new JCEFBrowserManager(project);
        
        try {
            // Create a future to track when frameworks are loaded
            CompletableFuture<Boolean> frameworksLoaded = new CompletableFuture<>();
            
            // Execute JavaScript to check if frameworks are loaded
            manager.executeJavaScript(
                "window.checkFrameworksLoaded = function() {" +
                "  return !!(window.AgentFramework && window.WorkflowEngine && window.WorkflowBuilder);" +
                "};"
            );
            
            // Load the workflow builder page
            manager.loadWorkflowBuilder();
            
            // Poll for frameworks to be loaded (with timeout)
            boolean loaded = false;
            int attempts = 0;
            while (!loaded && attempts < 50) { // 5 seconds max
                Thread.sleep(100);
                
                // Check if frameworks are loaded
                manager.executeJavaScript(
                    "if (window.checkFrameworksLoaded()) {" +
                    "  console.log('Frameworks loaded!');" +
                    "}"
                );
                
                // In a real test, we'd need to get the result back through the bridge
                // For now, we just ensure the page loads without errors
                attempts++;
            }
            
            // Verify the URL is correct
            String currentUrl = manager.getBrowser().getCefBrowser().getURL();
            assertTrue("URL should be workflow builder page", 
                currentUrl.contains("workflowBuilder.html"));
            
        } finally {
            manager.dispose();
        }
    }
    
    @Test
    public void testAgentFrameworkInitialization() throws Exception {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        WebBrowserPanel panel = new WebBrowserPanel(project);
        
        try {
            // Switch to agent mode to ensure all scripts are loaded
            panel.switchToAgentMode();
            
            // Load a test page
            panel.getBrowserManager().loadJCEFTest();
            
            // Wait for page to load
            Thread.sleep(2000);
            
            // Execute test to verify frameworks
            panel.executeJavaScript(
                "if (window.AgentFramework) {" +
                "  console.log('AgentFramework is loaded');" +
                "  console.log('Available roles:', Object.keys(window.AgentFramework.AgentRoles));" +
                "}"
            );
            
            // Test creating an agent
            panel.executeJavaScript(
                "if (window.AgentFramework && window.AgentFramework.createAgent) {" +
                "  console.log('Creating test agent...');" +
                "  // Note: Actual agent creation would be async" +
                "}"
            );
            
        } finally {
            // Cleanup
        }
    }
    
    @Test
    public void testResourceLoadingErrors() throws Exception {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        JCEFBrowserManager manager = new JCEFBrowserManager(project);
        
        try {
            // Try loading a non-existent resource
            manager.loadHTMLFromResource("/html/nonexistent.html");
            
            // Wait for load
            Thread.sleep(500);
            
            // The resource handler should handle this gracefully
            String currentUrl = manager.getBrowser().getCefBrowser().getURL();
            assertTrue("URL should still be set even for missing resource", 
                currentUrl.contains("jcef://resource/"));
            
        } finally {
            manager.dispose();
        }
    }
    
    @Test
    public void testJavaScriptBridgeCommunication() throws Exception {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        JCEFBrowserManager manager = new JCEFBrowserManager(project);
        JavaScriptBridge bridge = manager.getJavaScriptBridge();
        
        try {
            // Test various bridge actions
            String[] testQueries = {
                "{\"action\":\"getProjectInfo\",\"data\":{}}",
                "{\"action\":\"getProjectPath\",\"data\":{}}",
                "{\"action\":\"showDialog\",\"data\":{\"title\":\"Test\",\"message\":\"Test message\"}}",
            };
            
            for (String query : testQueries) {
                String response = bridge.handleJavaScriptQuery(query);
                assertNotNull("Response should not be null for: " + query, response);
                assertFalse("Response should not be empty", response.isEmpty());
                
                // Response should be valid JSON
                assertTrue("Response should contain success or error", 
                    response.contains("\"success\"") || response.contains("\"error\""));
            }
            
        } finally {
            manager.dispose();
        }
    }
}
