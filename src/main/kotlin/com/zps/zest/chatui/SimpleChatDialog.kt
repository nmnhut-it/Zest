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
    private val chatPanel = JPanel()
    private val chatScrollPane: JBScrollPane
    private val inputArea = JBTextArea()
    private val sendButton = JButton("Send")
    private val clearButton = JButton("Clear")
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")
    private var isProcessing = false

    init {
        title = "💬 Zest Chat"
        setSize(1000, 700)
        isModal = false
        
        // Initialize chat panel with vertical layout
        chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
        chatPanel.background = UIUtil.getPanelBackground()
        chatPanel.border = EmptyBorder(10, 10, 10, 10)
        
        // Initialize scroll pane
        chatScrollPane = JBScrollPane(chatPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }
        
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

        // Chat area with modern bubbles
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

    private fun setupChatPanel() {
        // Clear existing messages
        chatPanel.removeAll()
        chatPanel.revalidate()
        chatPanel.repaint()
    }

    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)

        val titleLabel = JBLabel("💬 Zest Chat - AI Code Assistant")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)

        // Right side panel with model selector and stats
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        
        // Model selector dropdown
        val chatService = project.getService(ChatUIService::class.java)
        val modelSelector = com.intellij.openapi.ui.ComboBox(chatService.getAvailableModels().toTypedArray())
        modelSelector.selectedItem = chatService.getSelectedModel()
        modelSelector.addActionListener {
            val selectedModel = modelSelector.selectedItem as String
            chatService.setSelectedModel(selectedModel)
        }
        modelSelector.toolTipText = "Select AI model for chat"
        
        rightPanel.add(JBLabel("Model: "))
        rightPanel.add(modelSelector)
        
        // Stats label
        val statsLabel = JBLabel(getChatStats())
        statsLabel.foreground = UIUtil.getInactiveTextColor()
        statsLabel.border = EmptyBorder(0, 15, 0, 0)
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

        // Add user message to conversation
        addChatBubble(MessageType.USER, userMessage, false)

        // Send to AI asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = chatService.sendMessage(userMessage)
                
                ApplicationManager.getApplication().invokeLater {
                    // Add AI response to conversation
                    addChatBubble(MessageType.AI, response, true)
                    
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
                    addChatBubble(MessageType.ERROR, "Failed to get AI response: ${e.message}", false)
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun addChatBubble(type: MessageType, content: String, isMarkdown: Boolean = true) {
        val messagePanel = createChatBubblePanel(type, content, isMarkdown)
        
        chatPanel.add(messagePanel)
        chatPanel.add(Box.createVerticalStrut(8)) // Space between messages
        
        chatPanel.revalidate()
        chatPanel.repaint()
        
        // Auto-scroll to bottom
        SwingUtilities.invokeLater {
            val vertical = chatScrollPane.verticalScrollBar
            vertical.value = vertical.maximum
        }
    }
    
    private fun createChatBubblePanel(type: MessageType, content: String, isMarkdown: Boolean): JPanel {
        val bubblePanel = JPanel(BorderLayout())
        val timestamp = timeFormatter.format(Date())
        
        // Create the message content panel
        val contentPanel = if (isMarkdown) {
            MarkdownRenderer.createMarkdownPane(content)
        } else {
            JBTextArea(content).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            }
        }
        
        // Create bubble container with appropriate styling
        val bubble = JPanel(BorderLayout())
        bubble.border = EmptyBorder(12, 16, 12, 16)
        bubble.add(contentPanel, BorderLayout.CENTER)
        
        // Style the bubble based on message type
        when (type) {
            MessageType.USER -> {
                bubble.background = if (isDarkMode()) Color(70, 130, 200) else Color(0, 122, 255)
                if (!isMarkdown) contentPanel.foreground = Color.WHITE
                bubblePanel.border = EmptyBorder(8, 50, 8, 8)
                bubble.setBorder(createRoundedBorder(bubble.background, 18))
            }
            MessageType.AI -> {
                bubble.background = if (isDarkMode()) Color(60, 63, 65) else Color(245, 245, 245)
                if (!isMarkdown) contentPanel.foreground = if (isDarkMode()) Color.WHITE else Color.BLACK
                bubblePanel.border = EmptyBorder(8, 8, 8, 50)
                bubble.setBorder(createRoundedBorder(bubble.background, 18))
            }
            MessageType.SYSTEM -> {
                bubble.background = if (isDarkMode()) Color(80, 80, 80) else Color(230, 230, 230)
                if (!isMarkdown) contentPanel.foreground = if (isDarkMode()) Color(200, 200, 200) else Color(100, 100, 100)
                bubblePanel.border = EmptyBorder(8, 20, 8, 20)
                bubble.setBorder(createRoundedBorder(bubble.background, 12))
            }
            MessageType.ERROR -> {
                bubble.background = if (isDarkMode()) Color(150, 60, 60) else Color(255, 200, 200)
                if (!isMarkdown) contentPanel.foreground = if (isDarkMode()) Color.WHITE else Color(150, 50, 50)
                bubblePanel.border = EmptyBorder(8, 20, 8, 20)
                bubble.setBorder(createRoundedBorder(bubble.background, 12))
            }
        }
        
        // Add timestamp and copy button on hover
        val headerPanel = JPanel(FlowLayout(if (type == MessageType.USER) FlowLayout.RIGHT else FlowLayout.LEFT, 0, 0))
        val timestampLabel = JBLabel(timestamp)
        timestampLabel.foreground = if (isDarkMode()) Color(150, 150, 150) else Color(120, 120, 120)
        timestampLabel.font = timestampLabel.font.deriveFont(10f)
        headerPanel.add(timestampLabel)
        headerPanel.isOpaque = false
        
        val wrapperPanel = JPanel(BorderLayout())
        wrapperPanel.add(headerPanel, BorderLayout.NORTH)
        wrapperPanel.add(bubble, BorderLayout.CENTER)
        wrapperPanel.isOpaque = false
        
        bubblePanel.add(wrapperPanel, if (type == MessageType.USER) BorderLayout.EAST else BorderLayout.WEST)
        bubblePanel.isOpaque = false
        
        return bubblePanel
    }
    
    private fun createRoundedBorder(backgroundColor: Color, radius: Int): javax.swing.border.Border {
        return object : javax.swing.border.AbstractBorder() {
            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = backgroundColor
                g2.fillRoundRect(x, y, width - 1, height - 1, radius, radius)
                g2.dispose()
            }
            
            override fun getBorderInsets(c: Component): Insets {
                return Insets(radius/2, radius/2, radius/2, radius/2)
            }
        }
    }
    
    enum class MessageType {
        USER, AI, SYSTEM, ERROR
    }
    
    private fun isDarkMode(): Boolean = UIUtil.isUnderDarcula()

    private fun clearInput() {
        inputArea.text = ""
        inputArea.requestFocus()
    }

    private fun startNewChat() {
        chatService.clearConversation()
        loadMessages()
        inputArea.requestFocus()
    }

    private fun loadMessages() {
        // Clear existing chat bubbles
        chatPanel.removeAll()

        val messages = chatService.getMessages()

        if (messages.isEmpty()) {
            // Add welcome message
            addChatBubble(MessageType.SYSTEM, "👋 Welcome to Zest Chat! Start a conversation by typing your question below.", false)
        } else {
            // Load all existing messages as chat bubbles
            messages.forEach { message ->
                when (message) {
                    is UserMessage -> addChatBubble(MessageType.USER, message.singleText(), false)
                    is AiMessage -> addChatBubble(MessageType.AI, message.text(), true)
                    is dev.langchain4j.data.message.SystemMessage -> addChatBubble(MessageType.SYSTEM, message.text(), false)
                    else -> addChatBubble(MessageType.SYSTEM, message.toString(), false)
                }
            }
        }

        chatPanel.revalidate()
        chatPanel.repaint()
    }

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

    private fun getChatStats(): String {
        val messageCount = chatService.getMessages().size
        return "Messages: $messageCount"
    }

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