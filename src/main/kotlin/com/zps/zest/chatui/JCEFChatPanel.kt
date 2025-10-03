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
    private var chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var messageCounter = 0
    
    init {

        // Initialize browser with empty chat
        initializeBrowser()

        // Add browser component directly
        add(browserManager.browser.component, BorderLayout.CENTER)
    }

    /**
     * Set the chat memory to render messages from
     */
    fun setChatMemory(memory: dev.langchain4j.memory.chat.MessageWindowChatMemory) {
        this.chatMemory = memory
        updateChatDisplay()
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
     * Add message to ChatMemory (for system prompts only, not UI messages)
     * Use addTemporaryChunk() for UI-only messages like welcome text
     */
    fun addMessage(header: String, content: String, messageType: String = "user") {
        // Add to LangChain4j chat memory
        when (messageType) {
            "user" -> chatMemory?.add(dev.langchain4j.data.message.UserMessage.from(content))
            "ai" -> chatMemory?.add(dev.langchain4j.data.message.AiMessage.from(content))
            "system" -> chatMemory?.add(dev.langchain4j.data.message.SystemMessage.from(content))
        }

        // Trigger full re-render from chat memory
        updateChatDisplay()
        scrollToBottom()
    }

    /**
     * Add temporary DOM-only chunk for streaming (does NOT touch ChatMemory)
     * Used for UI messages that will be replaced by ChatMemory reload
     */
    fun addTemporaryChunk(header: String, content: String, chunkType: String = "user") {
        val headerEscaped = escapeJavaScriptString(header)
        val contentEscaped = escapeJavaScriptString(content)
        val typeEscaped = escapeJavaScriptString(chunkType)

        browserManager.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.addTemporaryChunk) {
                window.chatFunctions.addTemporaryChunk(
                    '$typeEscaped',
                    '$headerEscaped',
                    '$contentEscaped'
                );
            }
        """)
    }
    
    /**
     * Update the last message during streaming (no message ID needed)
     */
    fun updateLastMessage(newContent: String) {
        // Send chunk to client-side streaming handler (no page reload)
        val escapedContent = escapeJavaScriptString(newContent)
        browserManager.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.updateLastMessageStreaming) {
                window.chatFunctions.updateLastMessageStreaming('$escapedContent');
            }
        """)
    }

    /**
     * Finalize streaming with animation, reload from ChatMemory
     * No message ID needed - reloads all from authoritative ChatMemory
     */
    fun finalizeStreaming() {
        // Trigger animated finalize
        browserManager.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.finalizeWithAnimation) {
                window.chatFunctions.finalizeWithAnimation();
            }
        """)

        // Schedule full reload from ChatMemory after animation starts
        java.util.Timer().schedule(object : java.util.TimerTask() {
            override fun run() {
                updateChatDisplay()
            }
        }, 300) // 300ms matches fade-out animation
    }

    /**
     * Add tool call chunk during streaming (temporary - replaced on finalize)
     */
    fun addToolCallChunkLive(toolName: String, toolArgs: String, toolId: String) {
        val toolArgsEscaped = escapeJavaScriptString(toolArgs)
        val toolNameEscaped = escapeJavaScriptString(toolName)
        browserManager.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.appendToolCallChunk) {
                window.chatFunctions.appendToolCallChunk('$toolNameEscaped', '$toolArgsEscaped', '$toolId');
            }
        """)
    }

    /**
     * Add tool result chunk during streaming (temporary - replaced on finalize)
     */
    fun addToolResultChunkLive(toolName: String, result: String) {
        val resultEscaped = escapeJavaScriptString(result)
        val toolNameEscaped = escapeJavaScriptString(toolName)
        browserManager.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.appendToolResultChunk) {
                window.chatFunctions.appendToolResultChunk('$toolNameEscaped', '$resultEscaped');
            }
        """)
    }


    /**
     * Clear all messages
     */
    fun clearMessages() {
        chatMemory?.clear()
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
     * Scroll to bottom of chat with delay to ensure DOM is ready
     */
    private fun scrollToBottom() {
        browserManager.executeJavaScript("""
            setTimeout(function() {
                window.scrollTo(0, document.body.scrollHeight);
            }, 100);
        """)
    }
    
    /**
     * Generate complete HTML for the chat by transforming ChatMessages into VisualChunks
     */
    private fun generateChatHtml(): String {
        val messages = chatMemory?.messages() ?: emptyList()

        // Transform ChatMessage → VisualChunks (breaking AiMessage into text + tools)
        val visualChunks = mutableListOf<VisualChunk>()
        var chunkId = 0

        messages.forEach { message ->
            when (message) {
                is dev.langchain4j.data.message.UserMessage -> {
                    visualChunks.add(VisualChunk(
                        id = "chunk-${chunkId++}",
                        type = "user",
                        header = "👤 You",
                        content = message.singleText(),
                        timestamp = timeFormatter.format(Date())
                    ))
                }

                is dev.langchain4j.data.message.AiMessage -> {
                    // AI text chunk (if exists)
                    message.text()?.let { text ->
                        if (text.isNotBlank()) {
                            visualChunks.add(VisualChunk(
                                id = "chunk-${chunkId++}",
                                type = "ai",
                                header = "🤖 Assistant",
                                content = text,
                                timestamp = timeFormatter.format(Date())
                            ))
                        }
                    }

                    // Each tool execution request as separate chunk
                    message.toolExecutionRequests().forEachIndexed { index, tool ->
                        visualChunks.add(VisualChunk(
                            id = "chunk-${chunkId++}",
                            type = "tool_call",
                            header = "🔧 ${tool.name()}",
                            content = null,  // Will be formatted in JS
                            timestamp = timeFormatter.format(Date()),
                            toolName = tool.name(),
                            toolArgs = tool.arguments(),
                            toolId = tool.id()
                        ))
                    }
                }

                is dev.langchain4j.data.message.ToolExecutionResultMessage -> {
                    visualChunks.add(VisualChunk(
                        id = "chunk-${chunkId++}",
                        type = "tool_result",
                        header = "📄 Result: ${message.toolName()}",
                        content = message.text(),
                        timestamp = timeFormatter.format(Date()),
                        toolName = message.toolName()
                    ))
                }

                is dev.langchain4j.data.message.SystemMessage -> {
                    visualChunks.add(VisualChunk(
                        id = "chunk-${chunkId++}",
                        type = "system",
                        header = "⚙️ System",
                        content = message.text(),
                        timestamp = timeFormatter.format(Date())
                    ))
                }
            }
        }

        // Serialize visual chunks to JSON for JavaScript
        val chunksJson = visualChunks.joinToString(",\n") { chunk ->
            """
            {
                "id": "${chunk.id}",
                "type": "${escapeJsonString(chunk.type)}",
                "header": "${escapeJsonString(chunk.header)}",
                "content": ${if (chunk.content != null) "\"${escapeJsonString(chunk.content)}\"" else "null"},
                "timestamp": "${chunk.timestamp}",
                "toolName": ${if (chunk.toolName != null) "\"${escapeJsonString(chunk.toolName)}\"" else "null"},
                "toolArgs": ${if (chunk.toolArgs != null) "\"${escapeJsonString(chunk.toolArgs)}\"" else "null"},
                "toolId": ${if (chunk.toolId != null) "\"${escapeJsonString(chunk.toolId)}\"" else "null"}
            }
            """.trimIndent()
        }

        return generateBaseChatHtml(chunksJson)
    }

    /**
     * Data class for visual rendering chunks
     */
    private data class VisualChunk(
        val id: String,
        val type: String,         // "user", "ai", "tool_call", "tool_result", "system"
        val header: String,
        val content: String?,
        val timestamp: String,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val toolId: String? = null
    )
    
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
     * Generate base HTML template with client-side markdown processing and tool renderers
     */
    private fun generateBaseChatHtml(chunksData: String): String {
        val isDarkTheme = !com.intellij.ui.JBColor.isBright()
        val markedJs = loadResource("js/marked.min.js") ?: ""
        val highlightJs = loadResource("js/highlight.min.js") ?: ""
        val highlightCss = loadResource("js/${if (isDarkTheme) "github-dark" else "github"}.css") ?: ""
        val chatCss = loadResource("chat-ui/chat.css") ?: ""
        val chatJs = loadResource("chat-ui/chat-functions.js") ?: ""

        // Load tool renderers
        val toolRendererBase = loadResource("chat-ui/tool-renderer-base.js") ?: ""
        val toolRendererReadFile = loadResource("chat-ui/tool-renderer-read-file.js") ?: ""
        val toolRendererSearchCode = loadResource("chat-ui/tool-renderer-search-code.js") ?: ""
        val toolRendererCodeMod = loadResource("chat-ui/tool-renderer-code-mod.js") ?: ""
        val toolRendererDefault = loadResource("chat-ui/tool-renderer-default.js") ?: ""

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
                <div class="collapse-all-container">
                    <button id="collapse-all-btn" class="collapse-all-button" onclick="window.chatFunctions.toggleCollapseAll()">▲ Collapse All</button>
                </div>
                <div id="chat-container"></div>

                <script>
                    $markedJs
                    $highlightJs

                    // Visual chunks from Kotlin (transformed from ChatMessages)
                    const visualChunks = [$chunksData];
                </script>

                <script>
                    $chatJs
                    $toolRendererBase
                    $toolRendererReadFile
                    $toolRendererSearchCode
                    $toolRendererCodeMod
                    $toolRendererDefault
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
}