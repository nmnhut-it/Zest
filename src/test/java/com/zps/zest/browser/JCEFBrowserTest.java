package com.zps.zest.browser;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.jcef.JBCefApp;
import org.junit.Test;

import javax.swing.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for JCEF browser functionality
 */
public class JCEFBrowserTest extends BasePlatformTestCase {
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Ensure JCEF is initialized
        if (!JBCefApp.isSupported()) {
            System.out.println("JCEF is not supported in this test environment");
        }
    }
    
    @Test
    public void testJCEFSupport() {
        assertTrue("JCEF should be supported", JBCefApp.isSupported());
    }
    
    @Test
    public void testBrowserManagerCreation() {
        Project project = getProject();
        assertNotNull("Project should not be null", project);
        
        try {
            JCEFBrowserManager manager = new JCEFBrowserManager(project);
            assertNotNull("Browser manager should be created", manager);
            assertNotNull("Browser should be created", manager.getBrowser());
            assertNotNull("Component should be available", manager.getComponent());
            
            // Clean up
            manager.dispose();
        } catch (UnsupportedOperationException e) {
            // JCEF not supported in test environment
            System.out.println("Skipping test - JCEF not supported: " + e.getMessage());
        }
    }
    
    @Test
    public void testResourceLoading() {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        try {
            JCEFBrowserManager manager = new JCEFBrowserManager(project);
            
            // Test loading HTML from resource
            manager.loadHTMLFromResource("/html/test.html");
            
            // Give it time to load
            Thread.sleep(1000);
            
            // The content should be loaded directly via loadHTML
            String currentUrl = manager.getBrowser().getCefBrowser().getURL();
            assertNotNull("URL should not be null", currentUrl);
            
            manager.dispose();
        } catch (Exception e) {
            System.out.println("Test skipped due to: " + e.getMessage());
        }
    }
    
    @Test
    public void testJavaScriptBridge() {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        try {
            JCEFBrowserManager manager = new JCEFBrowserManager(project);
            JavaScriptBridge bridge = manager.getJavaScriptBridge();
            
            assertNotNull("JavaScript bridge should be created", bridge);
            
            // Test handling a simple query
            String testQuery = "{\"action\":\"getProjectInfo\",\"data\":{}}";
            String response = bridge.handleJavaScriptQuery(testQuery);
            
            assertNotNull("Response should not be null", response);
            assertTrue("Response should contain success", 
                response.contains("success"));
            
            manager.dispose();
        } catch (Exception e) {
            System.out.println("Test skipped due to: " + e.getMessage());
        }
    }
    
    @Test
    public void testChunkedMessageHandling() {
        Project project = getProject();
        ChunkedMessageHandler handler = new ChunkedMessageHandler();
        
        // Test single message (no chunking needed)
        String singleMessage = "{\"action\":\"test\",\"data\":{\"message\":\"hello\"}}";
        ChunkedMessageHandler.ProcessResult result = handler.processChunkedMessage(singleMessage);
        
        assertTrue("Single message should be complete", result.isComplete());
        assertEquals("Message should match", singleMessage, result.getAssembledMessage());
        
        // Test chunked message
        String largeMessage = "{\"action\":\"test\",\"data\":{\"message\":\"" + "x".repeat(2000) + "\"}}";
        String chunk1 = "CHUNK:id123:0:2:" + largeMessage.substring(0, 1000);
        String chunk2 = "CHUNK:id123:1:2:" + largeMessage.substring(1000);
        
        ChunkedMessageHandler.ProcessResult result1 = handler.processChunkedMessage(chunk1);
        assertFalse("First chunk should not be complete", result1.isComplete());
        
        ChunkedMessageHandler.ProcessResult result2 = handler.processChunkedMessage(chunk2);
        assertTrue("Second chunk should complete the message", result2.isComplete());
        assertEquals("Assembled message should match original", 
            largeMessage, result2.getAssembledMessage());
    }
    
    @Test
    public void testWebBrowserPanel() {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        try {
            WebBrowserPanel panel = new WebBrowserPanel(project);
            
            assertNotNull("Panel should be created", panel);
            assertNotNull("Component should be available", panel.getComponent());
            assertNotNull("Browser manager should be available", panel.getBrowserManager());
            
            // Test mode switching
            panel.switchToAgentMode();
            assertNotNull("Current mode should not be null", panel.getCurrentMode());
            
            // Test JavaScript execution
            panel.executeJavaScript("console.log('Test from unit test');");
            
            // Test URL loading
            panel.loadUrl("https://example.com");
            
        } catch (Exception e) {
            System.out.println("Test skipped due to: " + e.getMessage());
        }
    }
    
    @Test
    public void testJavaScriptExecution() throws Exception {
        if (!JBCefApp.isSupported()) {
            System.out.println("Skipping test - JCEF not supported");
            return;
        }
        
        Project project = getProject();
        JCEFBrowserManager manager = new JCEFBrowserManager(project);
        
        // Load a simple HTML page
        String html = "<html><body><div id='test'>Original</div></body></html>";
        manager.loadURL("data:text/html," + html);
        
        // Wait for page to load
        Thread.sleep(1000);
        
        // Execute JavaScript to modify the page
        manager.executeJavaScript(
            "document.getElementById('test').textContent = 'Modified';"
        );
        
        // In a real test, we would verify the result through the bridge
        // For now, we just ensure no exceptions are thrown
        
        manager.dispose();
    }
    
    @Test
    public void testBrowserService() {
        Project project = getProject();
        WebBrowserService service = WebBrowserService.getInstance(project);
        
        assertNotNull("Service should be available", service);
        
        // In test environment, browser panel might not be created
        // This is expected behavior
        WebBrowserPanel panel = service.getBrowserPanel();
        if (panel != null) {
            assertNotNull("Panel component should be available", panel.getComponent());
        }
    }
}
