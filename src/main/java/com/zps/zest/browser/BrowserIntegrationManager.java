package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.InteractiveAgentService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages integration between the AI assistant and the web browser.
 */
public class BrowserIntegrationManager {
    private static final Logger LOG = Logger.getInstance(BrowserIntegrationManager.class);
    
    // Patterns to detect browser-related commands in AI responses
    private static final Pattern SHOW_IN_BROWSER_PATTERN = 
            Pattern.compile("(?i)\\b(show|display|open)\\s+(?:this|the|in|on)?\\s*(?:result|code|content|output|page)?\\s+(?:in|on)\\s+(?:the)?\\s*browser\\b");
    
    private static final Pattern LOAD_URL_PATTERN = 
            Pattern.compile("(?i)\\b(?:load|open|navigate|go\\s+to)\\s+(?:url|website|page|link)?\\s*[:\\s]\\s*(https?://\\S+)\\b");
    
    /**
     * Processes the AI response for browser-related commands.
     */
    public static void processAiResponse(Project project, String userMessage, String aiResponse) {
        try {
            // Check for "show in browser" command
            Matcher showInBrowserMatcher = SHOW_IN_BROWSER_PATTERN.matcher(userMessage);
            if (showInBrowserMatcher.find()) {
                // Extract code blocks from the AI response
                String contentToShow = extractCodeBlock(aiResponse);
                if (contentToShow != null) {
                    displayContentInBrowser(project, contentToShow);
                    return;
                }
            }
            
            // Check for "load URL" command
            Matcher loadUrlMatcher = LOAD_URL_PATTERN.matcher(userMessage);
            if (loadUrlMatcher.find()) {
                String url = loadUrlMatcher.group(1);
                if (url != null && !url.isEmpty()) {
                    loadUrlInBrowser(project, url);
                    return;
                }
            }
            
            // Register with the AI service for future responses
//            InteractiveAgentService.getInstance(project).addResponseListener(
//                (request, response) -> handleAiResponse(project, request, response)
//            );
        } catch (Exception e) {
            LOG.error("Error processing AI response for browser integration", e);
        }
    }
    
    /**
     * Handles an AI response to check for browser commands.
     */
    private static void handleAiResponse(Project project, String request, String response) {
        try {
            // Check for "show in browser" pattern
            if (SHOW_IN_BROWSER_PATTERN.matcher(request).find()) {
                String contentToShow = extractCodeBlock(response);
                if (contentToShow != null) {
                    displayContentInBrowser(project, contentToShow);
                }
            }
            
            // Check for URL in the response
            Pattern urlPattern = Pattern.compile("(https?://\\S+)");
            Matcher urlMatcher = urlPattern.matcher(response);
            if (urlMatcher.find()) {
                // If user asked about a website or URL
                if (request.toLowerCase().contains("website") || 
                    request.toLowerCase().contains("url") ||
                    request.toLowerCase().contains("link")) {
                    
                    String url = urlMatcher.group(1);
                    loadUrlInBrowser(project, url);
                }
            }
        } catch (Exception e) {
            LOG.error("Error handling AI response", e);
        }
    }
    
    /**
     * Extracts a code block from the AI response.
     */
    private static String extractCodeBlock(String response) {
        // Look for code blocks (```...```)
        Pattern codeBlockPattern = Pattern.compile("```(html)?\\s*([\\s\\S]*?)```");
        Matcher matcher = codeBlockPattern.matcher(response);
        
        if (matcher.find()) {
            String language = matcher.group(1);
            String code = matcher.group(2);
            
            // If it's HTML or no language specified, treat as HTML
            if ("html".equalsIgnoreCase(language) || language == null) {
                return "<!DOCTYPE html>\n<html>\n<head>\n" +
                        "<meta charset=\"UTF-8\">\n" +
                        "<title>AI Generated Content</title>\n" +
                        "</head>\n<body>\n" + code + "\n</body>\n</html>";
            }
            
            // For other languages, create an HTML page with a code block
            return "<!DOCTYPE html>\n<html>\n<head>\n" +
                    "<meta charset=\"UTF-8\">\n" +
                    "<title>AI Generated Code</title>\n" +
                    "<style>\n" +
                    "body { font-family: Arial, sans-serif; margin: 20px; }\n" +
                    "pre { background-color: #f5f5f5; padding: 10px; border-radius: 5px; overflow-x: auto; }\n" +
                    "</style>\n" +
                    "</head>\n<body>\n" +
                    "<h2>Generated Code (" + (language != null ? language : "") + ")</h2>\n" +
                    "<pre><code>" + code + "</code></pre>\n" +
                    "</body>\n</html>";
        }
        
        return null;
    }
    
    /**
     * Displays content in the browser.
     */
    private static void displayContentInBrowser(Project project, String content) {
        try {
            // Create a temporary HTML file
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("zest-ai-content", ".html");
            java.nio.file.Files.write(tempFile, content.getBytes());
            
            // Get the browser service
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            browserService.loadUrl(tempFile.toUri().toString());
            
            // Activate the tool window
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Web Browser");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
            
            LOG.info("Displayed content in browser");
        } catch (Exception e) {
            LOG.error("Error displaying content in browser", e);
        }
    }
    
    /**
     * Loads a URL in the browser.
     */
    private static void loadUrlInBrowser(Project project, String url) {
        try {
            // Get the browser service
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            browserService.loadUrl(url);
            
            // Activate the tool window
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Web Browser");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
            
            LOG.info("Loaded URL in browser: " + url);
        } catch (Exception e) {
            LOG.error("Error loading URL in browser", e);
        }
    }
}
