package com.zps.zest.chatui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.browser.JCEFBrowserManager
import com.zps.zest.browser.JCEFBrowserService
import com.zps.zest.browser.BrowserPurpose
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * JCEF-based chat panel for better HTML rendering with interactive features
 */
class JCEFChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(JCEFChatPanel::class.java)
    }

    private val browserManager: JCEFBrowserManager = JCEFBrowserService.getInstance(project).getBrowserManager(BrowserPurpose.CHAT)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private val conversationMessages = mutableListOf<ChatMessageData>()
    private var messageCounter = 0
    
    init {
        
        // Initialize browser with empty chat
        initializeBrowser()
        
        // Add browser component directly
        add(browserManager.browser.component, BorderLayout.CENTER)
    }
    
    private fun initializeBrowser() {
        val initialHtml = generateChatHtml()
        // Create base64 data URL to avoid encoding issues
        val encodedHtml = java.util.Base64.getEncoder().encodeToString(initialHtml.toByteArray())
        val dataUrl = "data:text/html;base64,$encodedHtml"
        browserManager.loadURL(dataUrl)
        
        // Set up JavaScript bridge for chat interactions
        setupJavaScriptBridge()
    }
    
    private fun setupJavaScriptBridge() {
        // Use the existing JavaScript bridge from JCEFBrowserManager
        val jsBridge = browserManager.javaScriptBridge
        // The bridge is already set up by JCEFBrowserManager, just log that it's ready
        LOG.info("JavaScript bridge ready for chat interactions")
    }
    
    /**
     * Add a new message to the chat
     * @return the ID of the created message
     */
    fun addMessage(header: String, content: String, messageType: String = "user"): String {
        val timestamp = timeFormatter.format(Date())
        messageCounter++

        val messageData = ChatMessageData(
            id = "msg-$messageCounter",
            messageType = messageType,
            header = header,
            content = content,
            timestamp = timestamp,
            toolCalls = mutableListOf()
        )

        conversationMessages.add(messageData)
        updateChatDisplay()
        scrollToMessage(messageData.id)
        return messageData.id
    }
    
    /**
     * Update an existing message's content for streaming (sends chunk to client-side queue)
     * Note: This does NOT reload the page - only updates via JavaScript for better performance
     * IMPORTANT: Preserves tool calls when updating content
     */
    fun updateMessage(messageId: String, newContent: String) {
        val messageIndex = conversationMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val originalMessage = conversationMessages[messageIndex]
            // Preserve toolCalls when updating content during streaming
            val updatedMessage = originalMessage.copy(
                content = newContent,
                toolCalls = originalMessage.toolCalls
            )
            conversationMessages[messageIndex] = updatedMessage

            // Send chunk to client-side streaming handler (no page reload)
            val escapedContent = escapeJavaScriptString(newContent)
            browserManager.executeJavaScript("""
                if (window.chatFunctions && window.chatFunctions.updateMessageStreaming) {
                    window.chatFunctions.updateMessageStreaming('$messageId', '$escapedContent');
                }
            """)
        }
    }
    
    /**
     * Finalize message content (render as markdown when streaming is complete)
     * IMPORTANT: Preserves tool calls by using copy() properly
     */
    fun finalizeMessage(messageId: String, finalContent: String) {
        val messageIndex = conversationMessages.indexOfLast { it.id == messageId }
        if (messageIndex >= 0) {
            val originalMessage = conversationMessages[messageIndex]
            // Must preserve toolCalls list when copying!
            val updatedMessage = originalMessage.copy(
                content = finalContent,
                toolCalls = originalMessage.toolCalls  // Preserve tool calls
            )
            conversationMessages[messageIndex] = updatedMessage

            // Finalize message with proper markdown rendering (JS will re-render from updated data model)
            val escapedContent = escapeJavaScriptString(finalContent)
            browserManager.executeJavaScript("""
                if (window.chatFunctions && window.chatFunctions.finalizeMessage) {
                    window.chatFunctions.finalizeMessage('$messageId', '$escapedContent');
                }
            """)
        }
    }

    /**
     * Add a tool call to a message (data-driven: updates both Kotlin and JS data models, then re-renders)
     */
    fun addToolCallToMessage(messageId: String, toolCallId: String, toolName: String, arguments: String) {
        val messageIndex = conversationMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val message = conversationMessages[messageIndex]
            val toolCall = ToolCallData(
                id = toolCallId,
                toolName = toolName,
                arguments = arguments,
                status = "executing"
            )
            message.toolCalls.add(toolCall)

            // Update JS data model and re-render from data
            val toolCallJson = """
                {
                    "id": "${escapeJavaScriptString(toolCallId)}",
                    "toolName": "${escapeJavaScriptString(toolName)}",
                    "arguments": "${escapeJavaScriptString(arguments)}",
                    "status": "executing",
                    "result": null
                }
            """.trimIndent()

            browserManager.executeJavaScript("""
                if (window.chatFunctions) {
                    window.chatFunctions.addToolCallToArray('$messageId', $toolCallJson);
                    window.chatFunctions.reRenderMessage('$messageId');
                }
            """)
        }
    }

    /**
     * Update a tool call's status and result (data-driven: updates both Kotlin and JS data models, then re-renders)
     */
    fun updateToolCallStatus(messageId: String, toolCallId: String, status: String, result: String?) {
        val messageIndex = conversationMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val message = conversationMessages[messageIndex]
            val toolCall = message.toolCalls.find { it.id == toolCallId }
            if (toolCall != null) {
                toolCall.status = status
                toolCall.result = result

                // Update JS data model and re-render from data
                val escapedStatus = escapeJavaScriptString(status)
                val escapedResult = result?.let { escapeJavaScriptString(it) } ?: "null"
                val resultJson = if (result != null) "\"$escapedResult\"" else "null"

                browserManager.executeJavaScript("""
                    if (window.chatFunctions) {
                        window.chatFunctions.updateToolCallInArray('$messageId', '$toolCallId', '$escapedStatus', $resultJson);
                        window.chatFunctions.reRenderMessage('$messageId');
                    }
                """)
            }
        }
    }

    /**
     * Clear all messages
     */
    fun clearMessages() {
        conversationMessages.clear()
        messageCounter = 0
        updateChatDisplay()
    }
    
    /**
     * Update the entire chat display
     */
    private fun updateChatDisplay() {
        val html = generateChatHtml()
        // Use base64 encoding to avoid URL encoding issues
        val encodedHtml = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
        val dataUrl = "data:text/html;base64,$encodedHtml"
        browserManager.loadURL(dataUrl)
    }
    
    /**
     * Scroll to specific message with delay to ensure DOM is ready
     */
    private fun scrollToMessage(messageId: String) {
        // Add small delay to ensure DOM is fully rendered
        browserManager.executeJavaScript("""
            setTimeout(function() {
                if (window.chatFunctions) {
                    window.chatFunctions.scrollToMessage('$messageId');
                }
            }, 100);
        """)
    }
    
    /**
     * Generate complete HTML for the chat using client-side markdown rendering
     */
    private fun generateChatHtml(): String {
        // Prepare message data for client-side processing, including tool calls
        val messagesData = conversationMessages.mapIndexed { index, message ->
            val toolCallsJson = message.toolCalls.joinToString(",\n") { toolCall ->
                """
                {
                    "id": "${escapeJsonString(toolCall.id)}",
                    "toolName": "${escapeJsonString(toolCall.toolName)}",
                    "arguments": "${escapeJsonString(toolCall.arguments)}",
                    "status": "${escapeJsonString(toolCall.status)}",
                    "result": ${if (toolCall.result != null) "\"${escapeJsonString(toolCall.result!!)}\"" else "null"}
                }
                """.trimIndent()
            }

            """
            {
                "id": "${message.id}",
                "messageType": "${escapeJsonString(message.messageType)}",
                "header": "${escapeJsonString(message.header)}",
                "content": ${if (message.content != null) "\"${escapeJsonString(message.content!!)}\"" else "null"},
                "timestamp": "${message.timestamp}",
                "showSeparator": ${index > 0},
                "toolCalls": [$toolCallsJson]
            }
            """
        }.joinToString(",\n")

        return generateBaseChatHtml(messagesData)
    }
    
    /**
     * Generate theme CSS variables based on current IDE theme
     */
    private fun generateThemeVariables(isDarkTheme: Boolean): String {
        return if (isDarkTheme) {
            """
            :root {
                --bg-primary: #0d1117;
                --bg-secondary: #161b22;
                --bg-tertiary: #0d1117;
                --text-primary: #e6edf3;
                --text-secondary: #7d8590;
                --text-link: #58a6ff;
                --text-link-hover: #79c0ff;
                --border-primary: #30363d;
                --border-secondary: #21262d;
                --border-accent: #58a6ff;
                --shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.4);
                --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.5);
                --code-bg: #161b22;
                --code-border: #30363d;
            }
            """.trimIndent()
        } else {
            """
            :root {
                --bg-primary: #f6f8fa;
                --bg-secondary: #ffffff;
                --bg-tertiary: #ffffff;
                --text-primary: #24292f;
                --text-secondary: #57606a;
                --text-link: #0969da;
                --text-link-hover: #0550ae;
                --border-primary: #d0d7de;
                --border-secondary: #eaeef2;
                --border-accent: #0969da;
                --shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.06);
                --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.1);
                --code-bg: #eff1f3;
                --code-border: #d0d7de;
            }
            """.trimIndent()
        }
    }

    /**
     * Generate base HTML template with client-side markdown processing
     */
    private fun generateBaseChatHtml(messagesData: String): String {
        val isDarkTheme = !com.intellij.ui.JBColor.isBright()
        val markedJs = loadResource("js/marked.min.js") ?: ""
        val highlightJs = loadResource("js/highlight.min.js") ?: ""
        val highlightCss = loadResource("js/${if (isDarkTheme) "github-dark" else "github"}.css") ?: ""
        val chatCss = loadResource("chat-ui/chat.css") ?: ""
        val chatJs = loadResource("chat-ui/chat-functions.js") ?: ""
        val themeVariables = generateThemeVariables(isDarkTheme)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Zest Chat</title>
                <style>
                    $highlightCss

                    $themeVariables

                    $chatCss
                </style>
            </head>
            <body>
                <div id="chat-container"></div>
                
                <script>
                    $markedJs
                    $highlightJs

                    // Message data from Kotlin
                    const messages = [$messagesData];
                </script>

                <script>
                    $chatJs
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Escape JSON string values to prevent injection
     */
    private fun escapeJsonString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }


    
    /**
     * Escape JavaScript string for safe execution in executeJavaScript
     */
    private fun escapeJavaScriptString(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }
    
    /**
     * Load a resource file as a string
     */
    private fun loadResource(path: String): String? {
        return try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(path)
            if (resourceStream != null) {
                resourceStream.bufferedReader().use { it.readText() }
            } else {
                LOG.warn("Resource not found: $path")
                null
            }
        } catch (e: Exception) {
            LOG.error("Error loading resource: $path", e)
            null
        }
    }
    
    /**
     * Get the browser component for integration
     */
    fun getBrowserComponent(): JComponent = browserManager.component
    
    /**
     * Get the browser manager for developer tools access
     */
    fun getBrowserManager(): JCEFBrowserManager = browserManager
    
    /**
     * Data class for chat messages - matches langchain4j AiMessage structure
     */
    private data class ChatMessageData(
        val id: String,
        val messageType: String, // "user", "ai", "system", "tool_result"
        val header: String,
        val content: String?,
        val timestamp: String,
        val toolCalls: MutableList<ToolCallData> = mutableListOf()
    )

    /**
     * Data class for tool execution requests and results
     */
    private data class ToolCallData(
        val id: String,
        val toolName: String,
        val arguments: String,
        var status: String, // "executing", "complete", "error"
        var result: String? = null
    )
}