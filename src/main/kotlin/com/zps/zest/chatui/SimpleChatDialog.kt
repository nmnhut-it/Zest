package com.zps.zest.chatui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.langchain4j.data.message.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Simple chat dialog for code review and general AI assistance.
 * Based on ChatMemoryDialog patterns with input capabilities.
 */
class SimpleChatDialog(
    private val project: Project
) : DialogWrapper(project, false) {

    private val chatService = project.getService(ChatUIService::class.java)
    private val conversationArea: JEditorPane = MarkdownRenderer.createMarkdownPane("", Int.MAX_VALUE)
    private val chatScrollPane: JBScrollPane
    private val inputArea = JBTextArea()
    private val sendButton = JButton("Send")
    private val clearButton = JButton("Clear")
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private var isProcessing = false
    private var conversationMarkdown = StringBuilder()
    private var messageCounter = 0
    private var lastMessageAnchor: String? = null

    init {
        title = "💬 Zest Chat"
        setSize(1000, 700)
        isModal = false
        
        // Initialize single markdown conversation area
        conversationArea.isEditable = false
        conversationArea.background = UIUtil.getPanelBackground()
        
        // Initialize scroll pane for the single conversation area
        chatScrollPane = JBScrollPane(conversationArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = null
        }
        
        // Initialize with welcome message
        appendToConversation("💬 **Welcome to Zest Chat!**", "Start a conversation by typing your question below...")
        
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(1000, 700)

        // Header with stats
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Main split pane: messages (top) and input (bottom)
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitPane.resizeWeight = 0.7 // 70% for messages, 30% for input

        // Single conversation area (much simpler than bubbles)
        chatScrollPane.minimumSize = JBUI.size(400, 200)
        splitPane.topComponent = chatScrollPane

        // Input panel
        val inputPanel = createInputPanel()
        inputPanel.minimumSize = JBUI.size(400, 150)
        splitPane.bottomComponent = inputPanel

        mainPanel.add(splitPane, BorderLayout.CENTER)

        // Footer with controls
        val footerPanel = createFooterPanel()
        mainPanel.add(footerPanel, BorderLayout.SOUTH)

        loadMessages()
        return mainPanel
    }

    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)

        val titleLabel = JBLabel("💬 Zest Chat - AI Code Assistant")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)

        // Right side panel with model selector and stats
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        
        // Model selector dropdown with refresh button
        val chatService = project.getService(ChatUIService::class.java)
        val modelSelector = com.intellij.openapi.ui.ComboBox(chatService.getAvailableModels().toTypedArray())
        modelSelector.selectedItem = chatService.getSelectedModel()
        modelSelector.addActionListener {
            val selectedModel = modelSelector.selectedItem as String
            chatService.setSelectedModel(selectedModel)
        }
        modelSelector.toolTipText = "Select AI model for chat"
        
        // Refresh button for models
        val refreshModelsButton = JButton("🔄")
        refreshModelsButton.toolTipText = "Refresh models from OpenWebUI API"
        refreshModelsButton.preferredSize = JBUI.size(24, 24)
        refreshModelsButton.addActionListener {
            // Refresh models in background
            SwingUtilities.invokeLater {
                refreshModelsButton.isEnabled = false
                refreshModelsButton.text = "⏳"
                
                Thread {
                    val newModels = chatService.refreshModelsFromAPI()
                    SwingUtilities.invokeLater {
                        // Update the combo box
                        modelSelector.removeAllItems()
                        newModels.forEach { modelSelector.addItem(it) }
                        modelSelector.selectedItem = chatService.getSelectedModel()
                        
                        refreshModelsButton.isEnabled = true
                        refreshModelsButton.text = "🔄"
                    }
                }.start()
            }
        }
        
        rightPanel.add(JBLabel("Model: "))
        rightPanel.add(modelSelector)
        rightPanel.add(refreshModelsButton)
        
        // Message limit indicator (simple counter)
        val messageCount = chatService.getMessages().size
        val statsLabel = JBLabel("$messageCount/50")
        statsLabel.foreground = when {
            messageCount > 40 -> if (isDarkMode()) Color(255, 200, 100) else Color(200, 100, 0) // Warning
            messageCount > 45 -> if (isDarkMode()) Color(255, 150, 150) else Color(200, 50, 50) // Alert
            else -> UIUtil.getInactiveTextColor() // Normal
        }
        statsLabel.toolTipText = "Chat memory: $messageCount of 50 messages used"
        statsLabel.border = EmptyBorder(0, 8, 0, 0)
        rightPanel.add(statsLabel)

        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }

    private fun createInputPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 0, 10)

        // Input area
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        inputArea.emptyText.text = "Ask about your code, request reviews, or get help with development..."

        // Handle Enter key for sending (Ctrl+Enter for new line)
        inputArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    if (e.isControlDown) {
                        // Ctrl+Enter: insert new line
                        inputArea.insert("\n", inputArea.caretPosition)
                        e.consume()
                    } else if (!e.isShiftDown && !isProcessing) {
                        // Enter: send message
                        e.consume()
                        sendMessage()
                    }
                }
            }
        })

        val inputScrollPane = JBScrollPane(inputArea)
        inputScrollPane.preferredSize = JBUI.size(400, 100)
        panel.add(inputScrollPane, BorderLayout.CENTER)

        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        sendButton.addActionListener { sendMessage() }
        sendButton.isEnabled = !isProcessing
        
        clearButton.addActionListener { clearInput() }
        
        buttonPanel.add(clearButton)
        buttonPanel.add(sendButton)
        panel.add(buttonPanel, BorderLayout.EAST)

        return panel
    }

    private fun createFooterPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        panel.border = EmptyBorder(8, 10, 10, 10)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadMessages() }
        panel.add(refreshButton)

        val exportButton = JButton("Export")
        exportButton.addActionListener { exportConversation() }
        panel.add(exportButton)

        val newChatButton = JButton("New Chat")
        newChatButton.addActionListener { startNewChat() }
        panel.add(newChatButton)

        return panel
    }

    private fun sendMessage() {
        val userMessage = inputArea.text.trim()
        if (userMessage.isEmpty() || isProcessing) return

        // Clear input immediately
        inputArea.text = ""
        
        // Update UI state
        isProcessing = true
        sendButton.text = "Sending..."
        sendButton.isEnabled = false

        // Add user message to markdown conversation
        appendToConversation("👤 **You**", userMessage)

        // Send to AI asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = chatService.sendMessage(userMessage)
                
                ApplicationManager.getApplication().invokeLater {
                    // Add AI response to markdown conversation
                    appendToConversation("🤖 **AI**", response)
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                    
                    // Focus back to input
                    inputArea.requestFocus()
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    // Show error message
                    appendToConversation("❌ **Error**", "Failed to get AI response: ${e.message}")
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Append a new message to the conversation markdown with HTML anchor for scrolling
     */
    private fun appendToConversation(header: String, content: String) {
        val timestamp = timeFormatter.format(java.util.Date())
        messageCounter++
        
        // Create unique anchor for this message
        val messageAnchor = "msg-$messageCounter"
        lastMessageAnchor = messageAnchor
        
        if (conversationMarkdown.isNotEmpty()) {
            conversationMarkdown.append("\n\n---\n\n")
        }
        
        // Add message with HTML anchor (this will be preserved in the HTML output)
        conversationMarkdown.append("<a name=\"$messageAnchor\"></a>\n")
        conversationMarkdown.append("## $header `($timestamp)`\n\n")
        conversationMarkdown.append(content)
        
        // Update the markdown display
        updateConversationDisplay()
        
        // Scroll to the beginning of this new message using anchor
        scrollToMessageAnchor(messageAnchor)
    }
    
    /**
     * Update the conversation display with current markdown
     */
    private fun updateConversationDisplay() {
        SwingUtilities.invokeLater {
            val html = MarkdownRenderer.markdownToHtml(conversationMarkdown.toString(), Int.MAX_VALUE)
            conversationArea.text = html
            conversationArea.revalidate()
            conversationArea.repaint()
        }
    }
    
    /**
     * Scroll to specific message anchor using JEditorPane's scrollToReference method
     */
    private fun scrollToMessageAnchor(anchor: String) {
        SwingUtilities.invokeLater {
            try {
                // Use JEditorPane's built-in anchor scrolling (most reliable)
                conversationArea.scrollToReference(anchor)
            } catch (e: Exception) {
                // Fallback: try to find anchor manually and scroll
                try {
                    val htmlText = conversationArea.text
                    val anchorPattern = "name=\"$anchor\""
                    val anchorIndex = htmlText.indexOf(anchorPattern)
                    
                    if (anchorIndex >= 0) {
                        conversationArea.caretPosition = anchorIndex
                        conversationArea.scrollRectToVisible(conversationArea.modelToView(anchorIndex))
                    }
                } catch (e2: Exception) {
                    // Final fallback: scroll to bottom
                    val vertical = chatScrollPane.verticalScrollBar  
                    vertical.value = vertical.maximum
                }
            }
        }
    }
    
    // All complex bubble methods removed - using simple single markdown area
    
    private fun isDarkMode(): Boolean = UIUtil.isUnderDarcula()

    private fun clearInput() {
        inputArea.text = ""
        inputArea.requestFocus()
    }

    private fun startNewChat() {
        chatService.clearConversation()
        conversationMarkdown.clear()
        messageCounter = 0
        lastMessageAnchor = null
        appendToConversation("💬 **Welcome to Zest Chat!**", "Start a conversation by typing your question below...")
        inputArea.requestFocus()
    }

    private fun loadMessages() {
        conversationMarkdown.clear()
        val messages = chatService.getMessages()

        if (messages.isEmpty()) {
            appendToConversation("💬 **Welcome to Zest Chat!**", "Start a conversation by typing your question below...")
        } else {
            // Rebuild markdown from all messages
            messages.forEach { message ->
                when (message) {
                    is UserMessage -> appendToConversation("👤 **You**", message.singleText())
                    is AiMessage -> appendToConversation("🤖 **AI**", message.text())
                    is dev.langchain4j.data.message.SystemMessage -> appendToConversation("⚙️ **System**", message.text())
                    else -> appendToConversation("💬 **Message**", message.toString())
                }
            }
        }
    }
    
    // Complex bubble system removed - using simple single markdown area

    // Context menu removed - modern chat interface doesn't need tree-based context menu

    private fun showMessageDetails(message: ChatMessage) {
        val dialog = MessageDetailDialog(project, message, "Zest Chat")
        dialog.show()
    }

    private fun showSimpleMessageDetails(messageData: SimpleMessageData) {
        val dialog = SimpleMessageDetailDialog(project, messageData)
        dialog.show()
    }

    private fun copyMessage(message: ChatMessage) {
        val content = when (message) {
            is SystemMessage -> "System: ${message.text()}"
            is UserMessage -> "User: ${message.singleText()}"
            is AiMessage -> "AI: ${message.text() ?: "Tool calls: ${message.toolExecutionRequests().size}"}"
            is ToolExecutionResultMessage -> "Tool Result: ${message.toolName()} - ${message.text()}"
            else -> message.toString()
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(content), null)
    }

    private fun copySimpleMessage(messageData: SimpleMessageData) {
        val content = "${messageData.type}: ${messageData.content}"
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(content), null)
    }

    // getChatStats removed - using new message limit indicator in header

    private fun exportConversation() {
        val content = buildString {
            appendLine("Zest Chat Export")
            appendLine("=" .repeat(50))
            appendLine("Exported: ${java.time.LocalDateTime.now()}")
            appendLine()

            chatService.getMessages().forEachIndexed { index, message ->
                appendLine("[$index] ${getMessageTypeName(message)}")
                appendLine("-" .repeat(40))

                when (message) {
                    is SystemMessage -> appendLine(message.text())
                    is UserMessage -> appendLine(message.singleText())
                    is AiMessage -> {
                        message.text()?.let { appendLine("Response: $it") }
                        message.toolExecutionRequests().forEach {
                            appendLine("Tool: ${it.name()}")
                            appendLine("Args: ${it.arguments()}")
                        }
                    }
                    is ToolExecutionResultMessage -> {
                        appendLine("Tool: ${message.toolName()}")
                        appendLine("Result: ${message.text()}")
                    }
                }
                appendLine()
            }
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(content), null)

        JOptionPane.showMessageDialog(
            contentPane,
            "Chat conversation exported to clipboard",
            "Export Complete",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun getMessageTypeName(message: ChatMessage): String {
        return when (message) {
            is SystemMessage -> "System Prompt"
            is UserMessage -> "User Message"
            is AiMessage -> "AI Response"
            is ToolExecutionResultMessage -> "Tool Result"
            else -> "Message"
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }

    /**
     * Pre-fill the input with a message and optionally send it
     */
    fun openWithMessage(message: String, autoSend: Boolean = false) {
        inputArea.text = message
        inputArea.requestFocus()
        
        if (autoSend && !isProcessing) {
            SwingUtilities.invokeLater {
                sendMessage()
            }
        }
    }

    // Data classes for message storage
    private data class MessageNodeData(val message: ChatMessage, val index: Int) {
        override fun toString(): String {
            val icon = when (message) {
                is SystemMessage -> "⚙️"
                is UserMessage -> "👤"
                is AiMessage -> "🤖"
                is ToolExecutionResultMessage -> "🔧"
                else -> "💬"
            }

            val type = when (message) {
                is SystemMessage -> "System"
                is UserMessage -> "User"
                is AiMessage -> "AI"
                is ToolExecutionResultMessage -> "Tool"
                else -> "Message"
            }

            val preview = getMessagePreview(message, 80)
            val time = SimpleDateFormat("HH:mm:ss").format(Date())

            return "$icon $type: $preview [$time]"
        }

        private fun getMessagePreview(message: ChatMessage, maxLength: Int): String {
            val text = when (message) {
                is SystemMessage -> message.text()
                is UserMessage -> message.singleText()
                is AiMessage -> message.text() ?: "Tool calls: ${message.toolExecutionRequests().size}"
                is ToolExecutionResultMessage -> "${message.toolName()} → ${message.text()}"
                else -> "Unknown"
            }

            val clean = text.replace('\n', ' ').replace('\t', ' ').trim()
            return if (clean.length > maxLength) clean.take(maxLength - 3) + "..." else clean
        }
    }

    private data class SimpleMessageData(
        val type: String,
        val content: String,
        val time: String
    )

    // Tree renderer removed - using modern chat bubbles instead
    
    /**
     * Simple message detail dialog for non-LangChain4j messages
     */
    private inner class SimpleMessageDetailDialog(
        project: Project,
        private val messageData: SimpleMessageData
    ) : DialogWrapper(project) {

    init {
        title = "Message Details - ${messageData.type}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(600, 400)

        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)

        val icon = when (messageData.type) {
            "User" -> "👤"
            "AI" -> "🤖"
            "Error" -> "❌"
            else -> "💬"
        }

        val typeLabel = JBLabel("$icon ${messageData.type}")
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 16f)
        headerPanel.add(typeLabel, BorderLayout.WEST)

        val timeLabel = JBLabel(messageData.time)
        timeLabel.foreground = UIUtil.getInactiveTextColor()
        headerPanel.add(timeLabel, BorderLayout.EAST)

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Content
        val textArea = JBTextArea(messageData.content)
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.border = EmptyBorder(5, 5, 5, 5)

        mainPanel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return mainPanel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    }
    
    /**
     * Message detail dialog copied from ChatMemoryDialog for LangChain4j messages
     */
    private class MessageDetailDialog(
        project: Project,
        private val message: ChatMessage,
        agentName: String
    ) : DialogWrapper(project) {

    init {
        title = "Message Details - $agentName"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(700, 500)

        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)

        val icon = when (message) {
            is SystemMessage -> "⚙️"
            is UserMessage -> "👤"
            is AiMessage -> "🤖"
            is ToolExecutionResultMessage -> "🔧"
            else -> "💬"
        }

        val type = when (message) {
            is SystemMessage -> "System Prompt"
            is UserMessage -> "User Message"
            is AiMessage -> "AI Response"
            is ToolExecutionResultMessage -> "Tool Result"
            else -> "Message"
        }

        val typeLabel = JBLabel("$icon $type")
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 16f)
        headerPanel.add(typeLabel, BorderLayout.WEST)

        val timeLabel = JBLabel(SimpleDateFormat("HH:mm:ss").format(Date()))
        timeLabel.foreground = UIUtil.getInactiveTextColor()
        headerPanel.add(timeLabel, BorderLayout.EAST)

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Content based on message type
        val contentPanel = when (message) {
            is SystemMessage -> createTextContent(message.text())
            is UserMessage -> createTextContent(message.singleText())
            is AiMessage -> createAiMessageContent(message)
            is ToolExecutionResultMessage -> createToolResultContent(message)
            else -> createTextContent(message.toString())
        }

        mainPanel.add(contentPanel, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createTextContent(text: String): JComponent {
        val textArea = JBTextArea(text)
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.border = EmptyBorder(5, 5, 5, 5)

        return JBScrollPane(textArea)
    }

    private fun createAiMessageContent(message: AiMessage): JComponent {
        val tabbedPane = JTabbedPane()

        // AI text response
        message.text()?.let { text ->
            if (text.isNotBlank()) {
                tabbedPane.addTab("💬 Response", createTextContent(text))
            }
        }

        // Tool calls
        if (message.toolExecutionRequests().isNotEmpty()) {
            val toolsPanel = JPanel()
            toolsPanel.layout = BoxLayout(toolsPanel, BoxLayout.Y_AXIS)

            message.toolExecutionRequests().forEachIndexed { index, tool ->
                val toolContent = "Tool: ${tool.name()}\n\nArguments:\n${tool.arguments()}"
                val toolTextArea = JBTextArea(toolContent)
                toolTextArea.isEditable = false
                toolTextArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                toolTextArea.lineWrap = true
                toolTextArea.wrapStyleWord = true
                toolTextArea.border = EmptyBorder(10, 10, 10, 10)

                val toolScrollPane = JBScrollPane(toolTextArea)
                toolScrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
                toolScrollPane.preferredSize = Dimension(-1, 150)

                toolsPanel.add(toolScrollPane)
                if (index < message.toolExecutionRequests().size - 1) {
                    toolsPanel.add(Box.createVerticalStrut(10))
                }
            }

            val toolsScrollPane = JBScrollPane(toolsPanel)
            tabbedPane.addTab("🔧 Tool Calls (${message.toolExecutionRequests().size})", toolsScrollPane)
        }

        return tabbedPane
    }

    private fun createToolResultContent(message: ToolExecutionResultMessage): JComponent {
        val content = "Tool: ${message.toolName()}\n\nResult:\n${message.text()}"
        return createTextContent(content)
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    }
}

private fun isDarkMode(): Boolean = UIUtil.isUnderDarcula()