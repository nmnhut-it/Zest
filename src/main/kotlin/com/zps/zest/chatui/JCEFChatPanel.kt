package com.zps.zest.chatui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.zps.zest.browser.JCEFBrowserManager
import com.zps.zest.browser.jcef.JCEFResourceManager
import dev.langchain4j.data.message.*
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
    
    private val browserManager: JCEFBrowserManager = JCEFBrowserManager(project)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private val conversationMessages = mutableListOf<ChatMessageData>()
    private var messageCounter = 0
    
    init {
        // Register for proper disposal
        Disposer.register(project, browserManager)
        JCEFResourceManager.getInstance().registerBrowser(browserManager.browser, project)
        
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
     */
    fun addMessage(header: String, content: String) {
        val timestamp = timeFormatter.format(Date())
        messageCounter++
        
        val messageData = ChatMessageData(
            id = "msg-$messageCounter",
            header = header,
            content = content,
            timestamp = timestamp
        )
        
        conversationMessages.add(messageData)
        updateChatDisplay()
        scrollToMessage(messageData.id)
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
     * Scroll to specific message
     */
    private fun scrollToMessage(messageId: String) {
        browserManager.executeJavaScript("chatFunctions.scrollToMessage('$messageId');")
    }
    
    /**
     * Generate complete HTML for the chat
     */
    private fun generateChatHtml(): String {
        val messagesHtml = conversationMessages.joinToString("\n") { message ->
            val messageMarkdown = """
                ## ${message.header} `(${message.timestamp})`
                
                ${message.content}
            """.trimIndent()
            
            val messageHtml = MarkdownRenderer.markdownToJCEFHtml(messageMarkdown)
                .substringAfter("<body>")
                .substringBefore("</body>")
            
            """
            <div id="${message.id}" class="chat-message">
                $messageHtml
            </div>
            """
        }
        
        return MarkdownRenderer.markdownToJCEFHtml("").replace(
            "<body>",
            """
            <body>
                <div id="chat-container">
                    $messagesHtml
                </div>
                
                <script>
                    // Enhanced chat functions
                    window.chatFunctions = {
                        scrollToMessage: function(messageId) {
                            const element = document.getElementById(messageId);
                            if (element) {
                                element.scrollIntoView({ behavior: 'smooth', block: 'start' });
                            }
                        },
                        
                        addMessage: function(messageId, html) {
                            const container = document.getElementById('chat-container');
                            const messageDiv = document.createElement('div');
                            messageDiv.id = messageId;
                            messageDiv.className = 'chat-message';
                            messageDiv.innerHTML = html;
                            container.appendChild(messageDiv);
                            
                            // Re-highlight and add copy buttons
                            if (typeof hljs !== 'undefined') {
                                hljs.highlightAll();
                                addCopyButtons();
                            }
                            
                            this.scrollToMessage(messageId);
                        },
                        
                        clearMessages: function() {
                            document.getElementById('chat-container').innerHTML = '';
                        },
                        
                        notifyJava: function(action, data) {
                            if (window.intellijBridge) {
                                window.intellijBridge.callAction(action, data || '');
                            }
                        }
                    };
                    
                    // Notify Java that chat is ready
                    if (window.intellijBridge) {
                        window.intellijBridge.callAction('chat-ready', '');
                    }
                </script>
            """
        ).replace("</body>", "</div></body>")
    }
    
    /**
     * Get the browser component for integration
     */
    fun getBrowserComponent(): JComponent = browserManager.component
    
    /**
     * Data class for chat messages
     */
    private data class ChatMessageData(
        val id: String,
        val header: String,
        val content: String,
        val timestamp: String
    )
}