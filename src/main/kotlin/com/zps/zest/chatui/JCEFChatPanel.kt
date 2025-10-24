package com.zps.zest.chatui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.browser.LightweightChatBrowser
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * JCEF-based chat panel for better HTML rendering with interactive features
 */
class JCEFChatPanel(
    private val project: Project,
    private var chatMemory: dev.langchain4j.memory.ChatMemory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(100)
) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(JCEFChatPanel::class.java)
    }

    private val browser: LightweightChatBrowser = LightweightChatBrowser(project)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private var messageCounter = 0

    init {
        // Initialize browser with chat memory
        initializeBrowser()

        // Add browser component directly
        add(browser.getComponent(), BorderLayout.CENTER)
    }

    /**
     * Set the chat memory to render messages from
     */
    fun setChatMemory(memory: dev.langchain4j.memory.ChatMemory) {
        this.chatMemory = memory
        updateChatDisplay()
    }

    private fun initializeBrowser() {
        val initialHtml = generateChatHtml()
        // Create base64 data URL to avoid encoding issues
        val encodedHtml = java.util.Base64.getEncoder().encodeToString(initialHtml.toByteArray())
        val dataUrl = "data:text/html;base64,$encodedHtml"
        browser.loadURL(dataUrl)

        LOG.info("Lightweight chat browser initialized")
    }
    
    /**
     * Add message to ChatMemory (for system prompts only, not UI messages)
     * Use addTemporaryChunk() for UI-only messages like welcome text
     */
    fun addMessage(header: String, content: String, messageType: String = "user") {
        // Add to LangChain4j chat memory
        when (messageType) {
            "user" -> chatMemory.add(dev.langchain4j.data.message.UserMessage.from(content))
            "ai" -> chatMemory.add(dev.langchain4j.data.message.AiMessage.from(content))
            "system" -> chatMemory.add(dev.langchain4j.data.message.SystemMessage.from(content))
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
        println("[KOTLIN] addTemporaryChunk called: type=$chunkType, header=$header, contentLength=${content.length}")

        val headerEscaped = escapeJavaScriptString(header)
        val contentEscaped = escapeJavaScriptString(content)
        val typeEscaped = escapeJavaScriptString(chunkType)

        browser.executeJavaScript("""
            console.log('[KOTLIN->JS] Executing addTemporaryChunk');
            if (window.chatFunctions && window.chatFunctions.addTemporaryChunk) {
                console.log('[KOTLIN->JS] window.chatFunctions found, calling addTemporaryChunk');
                window.chatFunctions.addTemporaryChunk(
                    '$typeEscaped',
                    '$headerEscaped',
                    '$contentEscaped'
                );
            } else {
                console.error('[KOTLIN->JS] ERROR: window.chatFunctions or addTemporaryChunk not found!');
                console.log('[KOTLIN->JS] window.chatFunctions:', window.chatFunctions);
            }
        """)

        println("[KOTLIN] executeJavaScript completed")
    }
    
    /**
     * Update the last message during streaming (no message ID needed)
     */
    fun updateLastMessage(newContent: String) {
        // Send chunk to client-side streaming handler (no page reload)
        val escapedContent = escapeJavaScriptString(newContent)
        browser.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.updateLastMessageStreaming) {
                window.chatFunctions.updateLastMessageStreaming('$escapedContent');
            }
        """)
    }

    /**
     * Finalize streaming and reload from ChatMemory
     */
    fun finalizeStreaming() {
        // Trigger finalize
        browser.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.finalizeWithAnimation) {
                window.chatFunctions.finalizeWithAnimation();
            }
        """)

        // Reload from ChatMemory
        java.util.Timer().schedule(object : java.util.TimerTask() {
            override fun run() {
                updateChatDisplay()
            }
        }, 200)
    }

    /**
     * Add tool badge to current AI message during streaming (temporary - replaced on finalize)
     */
    fun addToolBadgeLive(toolName: String, toolArgs: String, toolId: String) {
        val toolArgsEscaped = escapeJavaScriptString(toolArgs)
        val toolNameEscaped = escapeJavaScriptString(toolName)
        browser.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.appendToolBadge) {
                window.chatFunctions.appendToolBadge('$toolNameEscaped', '$toolArgsEscaped', '$toolId');
            }
        """)
    }

    /**
     * Update tool badge with result during streaming (temporary - replaced on finalize)
     */
    fun updateToolBadgeWithResult(toolId: String, result: String) {
        val resultEscaped = escapeJavaScriptString(result)
        browser.executeJavaScript("""
            if (window.chatFunctions && window.chatFunctions.updateToolBadgeWithResult) {
                window.chatFunctions.updateToolBadgeWithResult('$toolId', '$resultEscaped');
            }
        """)
    }


    /**
     * Clear all messages
     */
    fun clearMessages() {
        chatMemory.clear()
        messageCounter = 0
        updateChatDisplay()
    }
    
    private fun updateChatDisplay() {
        val html = generateChatHtml()
        val encodedHtml = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
        val dataUrl = "data:text/html;base64,$encodedHtml"

        browser.executeJavaScript("""
            (function() {
                const scrollY = window.scrollY;
                const maxScroll = document.body.scrollHeight - window.innerHeight;
                const wasAtBottom = scrollY >= maxScroll - 50;
                window.__preservedScrollY = wasAtBottom ? -1 : scrollY;
            })();
        """)

        browser.loadURL(dataUrl)

        browser.executeJavaScript("""
            setTimeout(function() {
                const scrollY = window.__preservedScrollY;
                if (scrollY === -1) {
                    window.scrollTo(0, document.body.scrollHeight);
                } else if (scrollY !== undefined) {
                    window.scrollTo(0, scrollY);
                }
            }, 100);
        """)
    }
    
    /**
     * Scroll to bottom of chat with delay to ensure DOM is ready
     */
    private fun scrollToBottom() {
        browser.executeJavaScript("""
            setTimeout(function() {
                window.scrollTo(0, document.body.scrollHeight);
            }, 100);
        """)
    }
    
    /**
     * Generate complete HTML for the chat by transforming ChatMessages into VisualChunks
     */
    private fun generateChatHtml(): String {
        val messages = chatMemory.messages()

        // Build map of tool results: toolId → result text
        val toolResultsMap = mutableMapOf<String, String>()
        messages.forEach { message ->
            if (message is dev.langchain4j.data.message.ToolExecutionResultMessage) {
                toolResultsMap[message.id()] = message.text()
            }
        }

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
                    // Collect tool calls with their results (if available)
                    val toolCalls = message.toolExecutionRequests().map { tool ->
                        ToolCallInfo(
                            id = tool.id(),
                            name = tool.name(),
                            args = tool.arguments(),
                            result = toolResultsMap[tool.id()]  // Match result by ID
                        )
                    }

                    // AI text chunk with embedded tool calls
                    val hasContent = message.text()?.isNotBlank() == true
                    val hasTools = toolCalls.isNotEmpty()

                    if (hasContent || hasTools) {
                        visualChunks.add(VisualChunk(
                            id = "chunk-${chunkId++}",
                            type = "ai",
                            header = "🤖 Assistant",
                            content = message.text()?.takeIf { it.isNotBlank() },
                            timestamp = timeFormatter.format(Date()),
                            toolCalls = toolCalls.takeIf { it.isNotEmpty() }
                        ))
                    }
                }

                is dev.langchain4j.data.message.ToolExecutionResultMessage -> {
                    // Skip - results are now embedded in AI message tool badges
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
            val toolCallsJson = if (chunk.toolCalls != null) {
                chunk.toolCalls.joinToString(",", "[", "]") { tool ->
                    val resultJson = if (tool.result != null) "\"${escapeJsonString(tool.result)}\"" else "null"
                    """{"id":"${escapeJsonString(tool.id)}","name":"${escapeJsonString(tool.name)}","args":"${escapeJsonString(tool.args)}","result":$resultJson}"""
                }
            } else {
                "null"
            }

            """
            {
                "id": "${chunk.id}",
                "type": "${escapeJsonString(chunk.type)}",
                "header": "${escapeJsonString(chunk.header)}",
                "content": ${if (chunk.content != null) "\"${escapeJsonString(chunk.content)}\"" else "null"},
                "timestamp": "${chunk.timestamp}",
                "toolCalls": $toolCallsJson
            }
            """.trimIndent()
        }

        return generateBaseChatHtml(chunksJson)
    }

    /**
     * Data class for tool call information (embedded in VisualChunk)
     */
    private data class ToolCallInfo(
        val id: String,
        val name: String,
        val args: String,
        val result: String? = null
    )

    /**
     * Data class for visual rendering chunks
     */
    private data class VisualChunk(
        val id: String,
        val type: String,         // "user", "ai", "tool_result", "system"
        val header: String,
        val content: String?,
        val timestamp: String,
        val toolCalls: List<ToolCallInfo>? = null  // For ai type with tools
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

                    // Visual chunks from Kotlin
                    const visualChunks = [$chunksData];
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
    fun getBrowserComponent(): JComponent = browser.getComponent()

    /**
     * Get the lightweight browser for developer tools access
     */
    fun getLightweightBrowser(): LightweightChatBrowser = browser
}