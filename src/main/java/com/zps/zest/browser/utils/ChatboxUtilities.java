package com.zps.zest.browser.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.WebBrowserPanel;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.WebBrowserToolWindow;
import org.apache.commons.lang.StringEscapeUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility class for manipulating the chat box interface in the integrated browser.
 * Provides reliable methods for sending text to the chat box and triggering the send button.
 */
public class ChatboxUtilities {
    private static final Logger LOG = Logger.getInstance(ChatboxUtilities.class);
    private static final long PAGE_LOAD_TIMEOUT_SECONDS = 1;

    /**
     * Clicks the "New Chat" button in the browser, ensuring the page is fully loaded first.
     *
     * @param project The current project
     * @return true if the operation was successful, false otherwise
     */
    public static boolean clickNewChatButton(Project project) {
        if (project == null) {
            LOG.warn("Cannot click new chat button: Project is null");
            return false;
        }

        WebBrowserService browserService = WebBrowserService.getInstance(project);
        if (browserService == null) {
            LOG.warn("Cannot click new chat button: Browser service is null");
            return false;
        }

        // Get the current URL to check if page is loaded
        WebBrowserPanel browserPanel = browserService.getBrowserPanel();
        if (browserPanel == null) {
            LOG.warn("Cannot click new chat button: Browser panel is null");
            return false;
        }

        String currentUrl = browserPanel.getCurrentUrl();
        AtomicBoolean success = new AtomicBoolean(false);

        // Wait for page to load before clicking new chat button
        waitForPageToLoad(project, currentUrl).thenAccept(loaded -> {
            if (loaded) {
                LOG.info("Page loaded, clicking new chat button");
                String script =
                        "function clickNewChatButton() {\n" +
                                "  try {\n" +
                                "    const newChatButton = document.getElementById('new-chat-button');\n" +
                                "    if (!newChatButton) {\n" +
                                "      console.error('New chat button element not found');\n" +
                                "      return false;\n" +
                                "    }\n" +
                                "    \n" +
                                "    // Check if button is disabled\n" +
                                "    if (newChatButton.disabled) {\n" +
                                "      console.error('New chat button is disabled');\n" +
                                "      return false;\n" +
                                "    }\n" +
                                "    \n" +
                                "    // Click the button\n" +
                                "    newChatButton.click();\n" +
                                "    \n" +
                                "    console.log('New chat button clicked successfully');\n" +
                                "    return true;\n" +
                                "  } catch (error) {\n" +
                                "    console.error('Error clicking new chat button:', error);\n" +
                                "    return false;\n" +
                                "  }\n" +
                                "}\n" +
                                "\n" +
                                "// Call the function\n" +
                                "clickNewChatButton();";
                browserService.executeJavaScript(script);
                success.set(true);
            } else {
                LOG.warn("Page did not load within timeout, new chat button not clicked");
                success.set(false);
            }
        }).exceptionally(ex -> {
            LOG.error("Error waiting for page to load: " + ex.getMessage(), ex);
            success.set(false);
            return null;
        });

        return true; // Return optimistically since we're now async
    }

//    public static boolean newChat(Project project, String model) {
//        return newChat(project, model, null);
//    }

    public enum EnumUsage {
        // Agent-based actions (step-by-step with human interaction)
        AGENT_TEST_WRITING,           // Agent: Step-by-Step Test Writing
        AGENT_REFACTORING,            // Agent: Step-by-Step Refactor for Testability
        AGENT_ONE_CLICK_TEST,         // Agent: One-click Write Test
        AGENT_GENERATE_COMMENTS,      // Agent: Write Comment for Selected Text

        // Implementation actions
        IMPLEMENT_TODOS,              // Implement Your TODOs
        INLINE_COMPLETION,            // Inline completion

        // Chat-based actions
        CHAT_CODE_REVIEW,             // Chat: Review This Class
        CHAT_REFACTOR_ADVISORY,       // Chat: Refactor Advisory for Testability
        CHAT_WRITE_TESTS,             // Chat: Write Tests for This Class
        CHAT_GIT_COMMIT_MESSAGE,      // Chat: Generate Git Commit Message
        CHAT_QUICK_COMMIT,            // Chat: Generate Git Commit Message

        // VCS Integration
        VCS_COMMIT_MESSAGE,           // Native IntelliJ VCS commit dialog integration

        EXPLORE_TOOL,                 // Developer tools
        LLM_SERVICE
    }
    /**
     * Sends text to the chat box and clicks the send button in one operation,
     * ensuring the page is fully loaded first.
     * 
     * @param project The current project
     * @param text The text to be sent to the chat box
     * @return true if the operation was successful, false otherwise
     */
    public static boolean sendTextAndSubmit(Project project, String text, boolean copyFirstResult, String systemPrompt,
                                            boolean useNativeFunctionCalling, EnumUsage enumUsage) {
        if (project == null || text == null) {
            LOG.warn("Cannot send text and submit: Project or text is null");
            return false;
        }
        
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        if (browserService == null) {
            LOG.warn("Cannot send text and submit: Browser service is null");
            return false;
        }
        
        // Get the current URL to check if page is loaded
        WebBrowserPanel browserPanel = browserService.getBrowserPanel();
        if (browserPanel == null) {
            LOG.warn("Cannot send text and submit: Browser panel is null");
            return false;
        }

        String currentUrl = browserPanel.getCurrentUrl();
        WebBrowserToolWindow.resetPageLoadState(project, currentUrl);
        AtomicBoolean success = new AtomicBoolean(false);
        browserPanel.getComponent().requestFocus();
        text = StringEscapeUtils.escapeHtml(text);
        text = text.replace("\r\n","<br>");
        text = text.replace("\n","<br>");
        text = text.replace("<br>","<br>\n");
        // Wait for page to load before sending text and clicking submit
        String finalText = text;
        waitForPageToLoad(project, currentUrl).thenAccept(loaded -> {
            if (loaded) {
                LOG.info("Page loaded, sending text and submitting");
                String escapedText = escapeJavaScriptString(finalText);

                // Create a comprehensive script that handles both operations with proper timing
                String script =
                        "function sendTextAndSubmit(text) {\n" +
                                "  try {\n" +
                                "    // Step 1: Find the chat input element\n" +
                                "    const chatInput = document.getElementById('chat-input');\n" +
                                "    if (!chatInput) {\n" +
                                "      alert('Chat input element not found');\n" +
                                "      return false;\n" +
                                "    }\n" +
                                "    \n" +
                                "    // Step 2: Clear any existing content\n" +
                                "    chatInput.innerHTML = '';\n" +
                                "    \n" +
                                "    // Step 3: Add the new text content\n" +
                                "    const p = document.createElement('p');\n" +
                                "    p.innerHTML = ''+text+'';\n" +
                                "    chatInput.appendChild(p);\n" +
                                "    console.log('text',text); \n" +
                                "    // Trigger input event to ensure the UI recognizes the change\n" +
                                "    const inputEvent = new Event('input', { bubbles: true });\n" +
                                "    chatInput.dispatchEvent(inputEvent);\n" +
                                "    \n" +
                                "    // Step 4: Wait a small amount of time to ensure the UI has updated\n" +
                                "    setTimeout(() => {\n" +
                                "      const sendButton = document.getElementById('send-message-button');\n" +
                                "      if (!sendButton) {\n" +
                                "        console.error('Send button element not found');\n" +
                                "        return false;\n" +
                                "      }\n" +
                                "      // Step 5: Check if button is disabled\n" +
                                "      if (sendButton.disabled) {\n" +
                                "        console.error('Send button is disabled after text insertion');\n" +
                                "        return false;\n" +
                                "      }\n" +
                                "      \n" +
                                "      // Step 6: Click the send button\n" +
                                "      window.__zest_usage__ = '" + enumUsage.name() +"';\n"+
                                "      sendButton.click();\n" +
                                "      window.shouldAutomaticallyCopy =  " +copyFirstResult +";\n" +
                                "      window.shouldAutomaticallyCopy =  " +copyFirstResult +";\n" +
                                "      console.log('Message successfully sent');\n" +
                                "    }, 300);\n" + // 300ms delay to ensure the UI has updated
                                "    \n" +
                                "    return true;\n" +
                                "  } catch (error) {\n" +
                                "    console.error('Error sending message:', error);\n" +
                                "    return false;\n" +
                                "  }\n" +
                                "}\n" +
                                "\n" +
                                "// Call the function with your message\n" +
                                "window.__injected_system_prompt__ = '" + escapeJavaScriptString(systemPrompt) + "';\n" +
                                "window.__should_use_native_function_calling__ = '" + useNativeFunctionCalling + "';\n" +
                                "sendTextAndSubmit('" + escapedText + "');";
                
                browserService.executeJavaScript(script);
                success.set(true);
            } else {
                LOG.warn("Page did not load within timeout, text not sent");
                success.set(false);
            }
        }).exceptionally(ex -> {
            LOG.error("Error waiting for page to load: " + ex.getMessage(), ex);
            success.set(false);
            return null;
        });
        
        return true; // Return optimistically since we're now async
    }
    
    /**
     * Waits for a page to finish loading before executing an operation.
     * If needed, it will navigate to the default chat URL first.
     * 
     * @param project The current project
     * @param url The URL to wait for
     * @return A CompletableFuture that completes when the page is loaded
     */
    private static CompletableFuture<Boolean> waitForPageToLoad(Project project, String url) {
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Check if we need to navigate to the default chat URL
        boolean navigated = true;
        
        // If we navigated to a different URL, we need to wait for that URL instead
        if (navigated) {
            WebBrowserPanel panel = browserService.getBrowserPanel();
            if (panel != null) {
                String defaultUrl = panel.getCurrentUrl();
                LOG.info("Navigated to default URL: " + defaultUrl + ", will wait for this page to load");
                url = defaultUrl;
            }
        }
        
        // Check if page is already loaded
        if (WebBrowserToolWindow.isPageLoaded(project, url)) {
            LOG.info("Page already loaded: " + url);
            return CompletableFuture.completedFuture(true);
        }
        
        // Wait for page to load with timeout
        LOG.info("Waiting for page to load: " + url);
        String finalUrl = url;
        return WebBrowserToolWindow.waitForPageToLoad(project, url)
                .orTimeout(PAGE_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Timeout waiting for page to load: " + finalUrl);
                    return true;
                });
    }
    
    /**
     * Escapes a string for use in JavaScript.
     */
    private static String escapeJavaScriptString(String str) {
        if (str == null) {
            return "";
        }
        
        return str.replace("\\", "\\\\")
                 .replace("'", "\\'")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t")
                 .replace("\b", "\\b")
                 .replace("\f", "\\f");
    }
}
