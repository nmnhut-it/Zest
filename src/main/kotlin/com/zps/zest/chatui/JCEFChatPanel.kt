package com.zps.zest.chatui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.zps.zest.browser.JCEFBrowserManager
import com.zps.zest.browser.JCEFBrowserService
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
    
    private val browserManager: JCEFBrowserManager = JCEFBrowserService.getInstance(project).getBrowserManager()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private val conversationMessages = mutableListOf<ChatMessageData>()
    private var messageCounter = 0
    
    init {
        // Browser manager disposal is now handled by JCEFBrowserService
        // Resource registration is handled by JCEFBrowserManager itself
        
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
    fun addMessage(header: String, content: String): String {
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
        return messageData.id
    }
    
    /**
     * Update an existing message's content for streaming (sends chunk to client-side queue)
     */
    fun updateMessage(messageId: String, newContent: String) {
        val messageIndex = conversationMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val updatedMessage = conversationMessages[messageIndex].copy(content = newContent)
            conversationMessages[messageIndex] = updatedMessage
            
            // Send chunk to client-side streaming handler
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
     */
    fun finalizeMessage(messageId: String, finalContent: String) {
        val messageIndex = conversationMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val updatedMessage = conversationMessages[messageIndex].copy(content = finalContent)
            conversationMessages[messageIndex] = updatedMessage
            
            // Finalize message with proper markdown rendering
            val escapedContent = escapeJavaScriptString(finalContent)
            browserManager.executeJavaScript("""
                if (window.chatFunctions && window.chatFunctions.finalizeMessage) {
                    window.chatFunctions.finalizeMessage('$messageId', '$escapedContent');
                }
            """)
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
        // Prepare message data for client-side processing
        val messagesData = conversationMessages.mapIndexed { index, message ->
            """
            {
                "id": "${message.id}",
                "header": "${escapeJsonString(message.header)}",
                "content": "${escapeJsonString(message.content)}",
                "timestamp": "${message.timestamp}",
                "showSeparator": ${index > 0}
            }
            """
        }.joinToString(",\n")
        
        return generateBaseChatHtml(messagesData)
    }
    
    /**
     * Generate base HTML template with client-side markdown processing
     */
    private fun generateBaseChatHtml(messagesData: String): String {
        val isDarkTheme = com.intellij.util.ui.UIUtil.isUnderDarcula()
        val markedJs = loadResource("js/marked.min.js") ?: ""
        val highlightJs = loadResource("js/highlight.min.js") ?: ""
        val highlightCss = loadResource("js/${if (isDarkTheme) "github-dark" else "github"}.css") ?: ""
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Zest Chat</title>
                <style>
                    $highlightCss
                    
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 14px;
                        margin: 0;
                        padding: 16px;
                        line-height: 1.6;
                        background: ${if (isDarkTheme) "#1e1e1e" else "#ffffff"};
                        color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
                    }
                    
                    .chat-message {
                        margin-bottom: 24px;
                    }
                    
                    .message-header {
                        font-size: 16px;
                        font-weight: 600;
                        color: ${if (isDarkTheme) "#6366f1" else "#4f46e5"};
                        margin-bottom: 12px;
                        border-bottom: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        padding-bottom: 8px;
                    }
                    
                    .message-content {
                        margin-left: 0;
                    }
                    
                    pre { 
                        background: ${if (isDarkTheme) "#2d2d30" else "#f6f8fa"};
                        border: 1px solid ${if (isDarkTheme) "#3a3d40" else "#d0d7de"};
                        border-radius: 6px;
                        padding: 12px;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 13px;
                        line-height: 1.4;
                        overflow-x: auto;
                        position: relative;
                    }
                    
                    code {
                        background: ${if (isDarkTheme) "#3c3f41" else "#f3f4f6"};
                        border-radius: 3px;
                        padding: 2px 4px;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 13px;
                    }
                    
                    pre code {
                        background: transparent;
                        padding: 0;
                        border-radius: 0;
                    }
                    
                    .copy-button {
                        position: absolute;
                        top: 8px;
                        right: 8px;
                        background: ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        border: none;
                        border-radius: 4px;
                        padding: 4px 8px;
                        font-size: 12px;
                        cursor: pointer;
                        color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
                        opacity: 0.7;
                        transition: opacity 0.2s;
                    }
                    
                    .copy-button:hover {
                        opacity: 1;
                        background: ${if (isDarkTheme) "#5a5d5e" else "#d0d7de"};
                    }
                    
                    blockquote {
                        border-left: 4px solid ${if (isDarkTheme) "#6366f1" else "#4f46e5"};
                        margin: 16px 0;
                        padding-left: 16px;
                        color: ${if (isDarkTheme) "#aaaaaa" else "#666666"};
                        font-style: italic;
                    }
                    
                    h1, h2, h3, h4, h5, h6 {
                        font-weight: 600;
                        margin: 24px 0 16px 0;
                        line-height: 1.25;
                    }
                    
                    h1 { font-size: 24px; }
                    h2 { font-size: 20px; }
                    h3 { font-size: 16px; }
                    
                    ul, ol {
                        margin: 16px 0;
                        padding-left: 32px;
                    }
                    
                    li {
                        margin: 4px 0;
                    }
                    
                    table {
                        border-collapse: collapse;
                        margin: 16px 0;
                        width: 100%;
                    }
                    
                    th, td {
                        border: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        padding: 8px 12px;
                        text-align: left;
                    }
                    
                    th {
                        background: ${if (isDarkTheme) "#3c3f41" else "#f9fafb"};
                        font-weight: 600;
                    }
                    
                    p {
                        margin: 12px 0;
                    }
                    
                    hr {
                        border: none;
                        border-top: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        margin: 24px 0;
                    }
                    
                    /* Message separators */
                    .message-separator {
                        height: 2px;
                        background: linear-gradient(to right, 
                            transparent, 
                            ${if (isDarkTheme) "#464647" else "#e1e4e8"}, 
                            transparent);
                        margin: 32px 0;
                        opacity: 0.6;
                    }
                    
                    /* Collapsible code blocks */
                    .collapse-button {
                        position: absolute;
                        top: 8px;
                        left: 8px;
                        background: ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        border: none;
                        border-radius: 4px;
                        padding: 4px 8px;
                        font-size: 11px;
                        cursor: pointer;
                        color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
                        opacity: 0.8;
                        transition: all 0.2s;
                        z-index: 10;
                    }
                    
                    .collapse-button:hover {
                        opacity: 1;
                        background: ${if (isDarkTheme) "#5a5d5e" else "#d0d7de"};
                    }
                    
                    .line-info {
                        position: absolute;
                        top: 8px;
                        left: 100px;
                        background: ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        border-radius: 4px;
                        padding: 4px 8px;
                        font-size: 10px;
                        color: ${if (isDarkTheme) "#aaaaaa" else "#666666"};
                        opacity: 0.7;
                        pointer-events: none;
                    }
                    
                    /* Collapsed state */
                    pre.collapsed {
                        max-height: 120px;
                        overflow: hidden;
                        transition: max-height 0.3s ease;
                    }
                    
                    /* Expanded state (default) */
                    pre:not(.collapsed) {
                        max-height: none;
                        transition: max-height 0.3s ease;
                    }
                    
                    .streaming-cursor {
                        animation: blink 1s infinite;
                        color: #3b82f6;
                        font-weight: bold;
                    }
                    
                    @keyframes blink {
                        0%, 50% { opacity: 1; }
                        51%, 100% { opacity: 0; }
                    }
                    
                    .tool-call {
                        margin: 12px 0;
                        padding: 12px;
                        background: ${if (isDarkTheme) "#2a2d30" else "#f8f9fa"};
                        border: 1px solid ${if (isDarkTheme) "#404448" else "#dee2e6"};
                        border-radius: 8px;
                        border-left: 4px solid #3b82f6;
                    }
                    
                    .tool-header {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-weight: 500;
                        margin-bottom: 8px;
                    }
                    
                    .tool-icon {
                        font-size: 16px;
                    }
                    
                    .tool-name {
                        color: #3b82f6;
                        font-family: 'JetBrains Mono', Consolas, monospace;
                    }
                    
                    .tool-status {
                        color: ${if (isDarkTheme) "#9ca3af" else "#6b7280"};
                        font-size: 12px;
                        margin-left: auto;
                    }
                    
                    .tool-args {
                        margin: 4px 0;
                    }
                    
                    .tool-args code {
                        background: ${if (isDarkTheme) "#1e1e1e" else "#ffffff"};
                        padding: 4px 8px;
                        border-radius: 4px;
                        font-size: 12px;
                        border: 1px solid ${if (isDarkTheme) "#404448" else "#e5e7eb"};
                    }
                    
                    .tool-result {
                        margin-top: 8px;
                        padding-top: 8px;
                        border-top: 1px solid ${if (isDarkTheme) "#404448" else "#e5e7eb"};
                    }
                    
                    .tool-result-header {
                        font-weight: 500;
                        color: ${if (isDarkTheme) "#9ca3af" else "#6b7280"};
                        font-size: 12px;
                        margin-bottom: 4px;
                    }
                    
                    .tool-result-content {
                        background: ${if (isDarkTheme) "#1e1e1e" else "#ffffff"};
                        padding: 8px;
                        border-radius: 4px;
                        font-size: 12px;
                        max-height: 200px;
                        overflow-y: auto;
                        border: 1px solid ${if (isDarkTheme) "#404448" else "#e5e7eb"};
                    }
                </style>
            </head>
            <body>
                <div id="chat-container"></div>
                
                <script>
                    $markedJs
                    $highlightJs
                    
                    // Message data from Kotlin
                    const messages = [$messagesData];
                    
                    document.addEventListener('DOMContentLoaded', function() {
                        renderMessages();
                        
                        // Initialize highlight.js
                        if (typeof hljs !== 'undefined') {
                            hljs.highlightAll();
                            // Temporarily removed: addCopyButtons();
                            makeCodeBlocksCollapsible();
                        }
                    });
                    
                    function renderMessages() {
                        const container = document.getElementById('chat-container');
                        container.innerHTML = '';
                        
                        messages.forEach(function(message) {
                            // Add separator if needed
                            if (message.showSeparator) {
                                const separator = document.createElement('div');
                                separator.className = 'message-separator';
                                container.appendChild(separator);
                            }
                            
                            // Create message container
                            const messageDiv = document.createElement('div');
                            messageDiv.id = message.id;
                            messageDiv.className = 'chat-message';
                            
                            // Create header
                            const headerDiv = document.createElement('div');
                            headerDiv.className = 'message-header';
                            headerDiv.textContent = message.header + ' - (' + message.timestamp + ')';
                            messageDiv.appendChild(headerDiv);
                            
                            // Create content and render markdown
                            const contentDiv = document.createElement('div');
                            contentDiv.className = 'message-content';
                            
                            // Use marked.js to render markdown
                            if (typeof marked !== 'undefined') {
                                contentDiv.innerHTML = marked.parse(message.content);
                            } else {
                                // Fallback to plain text
                                contentDiv.textContent = message.content;
                            }
                            
                            messageDiv.appendChild(contentDiv);
                            container.appendChild(messageDiv);
                        });
                    }
                    
                    function addCopyButtons() {
                        document.querySelectorAll('pre code').forEach(function(block) {
                            const pre = block.parentNode;
                            if (pre.querySelector('.copy-button')) return; // Already has button
                            
                            const button = document.createElement('button');
                            button.className = 'copy-button';
                            button.textContent = 'Copy';
                            button.onclick = function() {
                                navigator.clipboard.writeText(block.textContent).then(function() {
                                    button.textContent = 'Copied!';
                                    setTimeout(function() {
                                        button.textContent = 'Copy';
                                    }, 2000);
                                });
                            };
                            
                            pre.style.position = 'relative';
                            pre.appendChild(button);
                        });
                    }
                    
                    function makeCodeBlocksCollapsible() {
                        document.querySelectorAll('pre code').forEach(function(codeElement) {
                            const pre = codeElement.parentNode;
                            if (pre.querySelector('.collapse-button')) return; // Already has collapse button
                            
                            const codeText = codeElement.textContent || '';
                            const lineCount = codeText.split('\\n').length;
                            
                            // Make ALL code blocks collapsible (no line count discrimination)
                            const collapseButton = document.createElement('button');
                            collapseButton.className = 'collapse-button';
                            collapseButton.textContent = '▶ Expand';
                            
                            // Position button similar to copy button
                            pre.style.position = 'relative';
                            pre.appendChild(collapseButton);
                            
                            // Add line count info
                            const lineInfo = document.createElement('span');
                            lineInfo.className = 'line-info';
                            lineInfo.textContent = lineCount + ' lines';
                            pre.appendChild(lineInfo);
                            
                            // Start collapsed by default
                            pre.classList.add('collapsed');
                            
                            // Toggle collapse state
                            collapseButton.onclick = function() {
                                if (pre.classList.contains('collapsed')) {
                                    pre.classList.remove('collapsed');
                                    collapseButton.textContent = '▼ Collapse';
                                } else {
                                    pre.classList.add('collapsed');
                                    collapseButton.textContent = '▶ Expand';
                                }
                            };
                        });
                    }
                    
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
                            
                            // Re-highlight and add interactive features
                            if (typeof hljs !== 'undefined') {
                                hljs.highlightAll();
                                // Temporarily removed: addCopyButtons();
                                makeCodeBlocksCollapsible();
                            }
                            
                            this.scrollToMessage(messageId);
                        },
                        
                        updateMessage: function(messageId, newContent) {
                            const messageElement = document.getElementById(messageId);
                            if (messageElement) {
                                const contentDiv = messageElement.querySelector('.message-content');
                                if (contentDiv) {
                                    // Show as plain text during streaming for better performance
                                    contentDiv.innerHTML = '<pre style="white-space: pre-wrap; font-family: inherit; margin: 0; background: none; border: none; padding: 0;">' + this.escapeHtml(newContent) + '</pre>';
                                }
                            }
                        },
                        
                        updateMessageStreaming: function(messageId, newChunk) {
                            const messageElement = document.getElementById(messageId);
                            if (!messageElement) return;
                            
                            const contentDiv = messageElement.querySelector('.message-content');
                            if (!contentDiv) return;
                            
                            // Initialize streaming state if needed
                            if (!messageElement._streamingState) {
                                messageElement._streamingState = {
                                    contentBuffer: '',
                                    displayedContent: '',
                                    wordQueue: [],
                                    isProcessing: false,
                                    lastChunkTime: Date.now(),
                                    chunkInterval: 2000, // Start with 2 second default
                                    adaptiveSpeed: 500,   // Current display speed
                                    lastScrollTime: 0,    // Throttle scrolling
                                    isFinalized: false,   // Whether response is complete
                                    finalContent: null    // Store final content for conversion
                                };
                            }
                            
                            const state = messageElement._streamingState;
                            const currentTime = Date.now();
                            
                            // Calculate adaptive speed based on chunk arrival rate
                            if (state.lastChunkTime > 0) {
                                const timeSinceLastChunk = currentTime - state.lastChunkTime;
                                state.chunkInterval = timeSinceLastChunk;
                                
                                // Adaptive speed calculation:
                                // Fast chunks (< 500ms apart) → Fast display (100-200ms per word)
                                // Medium chunks (500-2000ms apart) → Medium display (300-500ms per word)  
                                // Slow chunks (> 2000ms apart) → Slow display (800-1500ms per word)
                                if (timeSinceLastChunk < 500) {
                                    state.adaptiveSpeed = Math.max(100, Math.min(200, timeSinceLastChunk / 3));
                                } else if (timeSinceLastChunk < 2000) {
                                    state.adaptiveSpeed = Math.max(300, Math.min(500, timeSinceLastChunk / 2));
                                } else {
                                    state.adaptiveSpeed = Math.max(800, Math.min(1500, timeSinceLastChunk / 2));
                                }
                                
                                console.log('Adaptive speed:', state.adaptiveSpeed + 'ms per word (chunk interval:', timeSinceLastChunk + 'ms)');
                            }
                            state.lastChunkTime = currentTime;
                            
                            state.contentBuffer += newChunk;
                            
                            // Split into words and add to queue
                            const words = newChunk.split(/(\s+)/);
                            state.wordQueue.push(...words);
                            
                            // If queue gets too long, speed up to catch up
                            if (state.wordQueue.length > 50) {
                                state.adaptiveSpeed = Math.max(50, state.adaptiveSpeed / 2);
                                console.log('Queue overflow, speeding up to:', state.adaptiveSpeed + 'ms per word');
                            }
                            
                            // Start word-by-word display if not already processing
                            if (!state.isProcessing) {
                                this.processWordQueue(messageId);
                            }
                            
                            // Also scroll to message when new chunks arrive (throttled)
                            if (currentTime - state.lastScrollTime > 300) { // Throttle chunk scrolling to every 300ms
                                state.lastScrollTime = currentTime;
                                messageElement.scrollIntoView({ 
                                    behavior: 'smooth', 
                                    block: 'end',
                                    inline: 'nearest' 
                                });
                            }
                        },
                        
                        processWordQueue: function(messageId) {
                            const messageElement = document.getElementById(messageId);
                            if (!messageElement || !messageElement._streamingState) return;
                            
                            const state = messageElement._streamingState;
                            const contentDiv = messageElement.querySelector('.message-content');
                            
                            if (state.wordQueue.length === 0) {
                                state.isProcessing = false;
                                
                                // If we're finalized and queue is empty, convert to markdown
                                if (state.isFinalized && state.finalContent) {
                                    this.convertToMarkdown(messageId, state.finalContent);
                                }
                                return;
                            }
                            
                            state.isProcessing = true;
                            
                            // Display next word
                            const nextWord = state.wordQueue.shift();
                            state.displayedContent += nextWord;
                            
                            // Update display with plain text
                            contentDiv.innerHTML = '<pre style="white-space: pre-wrap; font-family: inherit; margin: 0; background: none; border: none; padding: 0;">' + this.escapeHtml(state.displayedContent) + '<span class="streaming-cursor">▎</span></pre>';
                            
                            // Auto-scroll to keep the streaming message visible (throttled)
                            const now = Date.now();
                            if (now - state.lastScrollTime > 500) { // Throttle scrolling to every 500ms
                                state.lastScrollTime = now;
                                messageElement.scrollIntoView({ 
                                    behavior: 'smooth', 
                                    block: 'end',    // Keep bottom of message visible
                                    inline: 'nearest' 
                                });
                            }
                            
                            // Use adaptive speed based on chunk arrival rate
                            const isSpace = /^\s+$/.test(nextWord);
                            const baseDelay = isSpace ? Math.max(50, state.adaptiveSpeed / 5) : state.adaptiveSpeed;
                            
                            // Add some randomness for more natural typing (±20%)
                            const randomFactor = 0.8 + (Math.random() * 0.4);
                            const delay = Math.floor(baseDelay * randomFactor);
                            
                            setTimeout(() => {
                                this.processWordQueue(messageId);
                            }, delay);
                        },
                        
                        finalizeMessage: function(messageId, finalContent) {
                            const messageElement = document.getElementById(messageId);
                            if (!messageElement) return;
                            
                            const state = messageElement._streamingState;
                            if (state) {
                                // Mark as finalized and speed up remaining word display
                                state.isFinalized = true;
                                state.finalContent = finalContent;
                                
                                // Calculate remaining words to display
                                const remainingWords = finalContent.split(/(\s+)/).slice(state.displayedContent.split(/(\s+)/).length);
                                if (remainingWords.length > 0) {
                                    // Add remaining words to queue if any
                                    state.wordQueue.push(...remainingWords);
                                    
                                    // Speed up display significantly for remaining content
                                    state.adaptiveSpeed = Math.min(50, state.adaptiveSpeed / 4); // 4x faster
                                    console.log('Response complete, speeding up to:', state.adaptiveSpeed + 'ms per word for remaining', remainingWords.length, 'words');
                                    
                                    // If not currently processing, start the accelerated display
                                    if (!state.isProcessing) {
                                        this.processWordQueue(messageId);
                                    }
                                } else {
                                    // Nothing left to display, convert to markdown immediately
                                    this.convertToMarkdown(messageId, finalContent);
                                }
                            } else {
                                // No streaming state, display immediately
                                this.convertToMarkdown(messageId, finalContent);
                            }
                        },
                        
                        convertToMarkdown: function(messageId, content) {
                            const messageElement = document.getElementById(messageId);
                            if (messageElement) {
                                // Clear streaming state
                                if (messageElement._streamingState) {
                                    delete messageElement._streamingState;
                                }
                                
                                const contentDiv = messageElement.querySelector('.message-content');
                                if (contentDiv && typeof marked !== 'undefined') {
                                    // Now render as proper markdown
                                    contentDiv.innerHTML = marked.parse(content);
                                    // Re-highlight code blocks
                                    if (typeof hljs !== 'undefined') {
                                        contentDiv.querySelectorAll('pre code').forEach(function(block) {
                                            hljs.highlightElement(block);
                                        });
                                    }
                                    // Re-apply interactive features
                                    makeCodeBlocksCollapsible();
                                }
                                
                                // Final scroll to ensure the complete message is visible
                                setTimeout(() => {
                                    messageElement.scrollIntoView({ 
                                        behavior: 'smooth', 
                                        block: 'end',
                                        inline: 'nearest' 
                                    });
                                }, 100); // Small delay to ensure DOM is updated
                            }
                        },
                        
                        escapeHtml: function(text) {
                            const div = document.createElement('div');
                            div.textContent = text;
                            return div.innerHTML;
                        },
                        
                        addToolCall: function(messageId, toolName, toolArgs, status, toolCallId) {
                            const messageElement = document.getElementById(messageId);
                            if (!messageElement) return;
                            
                            const contentDiv = messageElement.querySelector('.message-content');
                            if (!contentDiv) return;
                            
                            // Create tool call element 
                            const statusIcon = status === 'executing' ? '⏳' : status === 'complete' ? '✅' : '❌';
                            const statusText = status === 'executing' ? 'Executing...' : status === 'complete' ? 'Complete' : 'Error';
                            
                            const toolCallHtml = 
                                '<div class="tool-call" id="' + toolCallId + '">' +
                                    '<div class="tool-header">' +
                                        '<span class="tool-icon">' + statusIcon + '</span>' +
                                        '<span class="tool-name">' + this.escapeHtml(toolName) + '</span>' +
                                        '<span class="tool-status">' + statusText + '</span>' +
                                    '</div>' +
                                    '<div class="tool-args">' +
                                        '<code>' + this.escapeHtml(toolArgs || '...') + '</code>' +
                                    '</div>' +
                                '</div>';
                            
                            // Add to the end of current content
                            const currentContent = contentDiv.innerHTML;
                            contentDiv.innerHTML = currentContent + toolCallHtml;
                             
                        },
                        
                        updateToolCall: function(toolCallId, status, result) {
                            const toolElement = document.getElementById(toolCallId);
                            if (!toolElement) {
                            console.log('Element for tool call id not found', toolCallId); 
                            return;
                            }
                            
                            const statusIcon = status === 'complete' ? '✅' : '❌';
                            const statusText = status === 'complete' ? 'Complete' : 'Error';
                            
                            const statusSpan = toolElement.querySelector('.tool-status');
                            const iconSpan = toolElement.querySelector('.tool-icon');
                            
                            if (statusSpan) statusSpan.textContent = statusText;
                            if (iconSpan) iconSpan.textContent = statusIcon;
                            
                            // Add result section if provided
                            if (result && status === 'complete') {
                                const resultHtml = 
                                    '<div class="tool-result">' +
                                        '<div class="tool-result-header">📄 Result:</div>' +
                                        '<pre class="tool-result-content">' + this.escapeHtml(result) + '</pre>' +
                                    '</div>';
                                toolElement.innerHTML += resultHtml;
                            }
                        },
                        
                        clearMessages: function() {
                            document.getElementById('chat-container').innerHTML = '';
                        },
                        
                        notifyJava: function(action, data) {
                            if (window.intellijBridge) {
                                window.intellijBridge.callIDE(action, data || '');
                            }
                        }
                    };
                    
                    // Notify Java that chat is ready
                    if (window.intellijBridge) {
                        window.intellijBridge.callIDE('chat-ready', '');
                    }
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
     * Data class for chat messages
     */
    private data class ChatMessageData(
        val id: String,
        val header: String,
        val content: String,
        val timestamp: String
    )
}