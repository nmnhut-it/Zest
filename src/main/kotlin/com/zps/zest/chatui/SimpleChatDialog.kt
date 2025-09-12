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
import java.text.SimpleDateFormat
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
        
        // Initialize Swing markdown conversation area
        conversationArea.isEditable = false
        conversationArea.background = UIUtil.getPanelBackground()
        
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
        sendButton.text = "Streaming..."
        sendButton.isEnabled = false

        // Add user message to markdown conversation
        appendToConversation("👤 **You**", userMessage)

        // Add AI message placeholder for streaming
        appendToConversation("🤖 **AI**", "...")
        
        val responseBuilder = StringBuilder()
        
        // Send to AI with streaming
        chatService.sendMessageStreaming(
            userMessage,
            onToken = { token ->
                // Update AI response in real-time
                responseBuilder.append(token)
                ApplicationManager.getApplication().invokeLater {
                    updateLastMessage(responseBuilder.toString())
                }
            },
            onComplete = { fullResponse ->
                ApplicationManager.getApplication().invokeLater {
                    // Ensure final response is displayed
                    updateLastMessage(fullResponse)
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                    
                    // Focus back to input
                    inputArea.requestFocus()
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    // Update AI message with error
                    updateLastMessage("❌ Failed to get response: ${error.message}")
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                }
            }
        )
    }

    /**
     * Append a new message to the conversation markdown with HTML anchor for scrolling
     * @return the message anchor for future updates
     */
    private fun appendToConversation(header: String, content: String): String {
        val timestamp = timeFormatter.format(java.util.Date())
        messageCounter++
        
        // Create unique anchor for this message
        val messageAnchor = "msg-$messageCounter"
        lastMessageAnchor = messageAnchor
        
        if (conversationMarkdown.isNotEmpty()) {
            conversationMarkdown.append("\n\n---\n\n")
        }
        
        // Add message with HTML anchor (Swing compatible)
        conversationMarkdown.append("<a name=\"$messageAnchor\"></a>\n")
        conversationMarkdown.append("## $header `($timestamp)`\n\n")
        conversationMarkdown.append(content)
        
        // Update the markdown display
        updateConversationDisplay()
        
        // Scroll to the beginning of this new message using anchor
        scrollToMessageAnchor(messageAnchor)
        
        return messageAnchor
    }
    
    /**
     * Update the content of the last message (for streaming)
     */
    private fun updateLastMessage(content: String) {
        if (lastMessageAnchor != null) {
            // Find the last message header in the markdown
            val text = conversationMarkdown.toString()
            val anchorPattern = "<a name=\"$lastMessageAnchor\"></a>\\n## ([^\\n]+)\\n\\n"
            val regex = anchorPattern.toRegex()
            val match = regex.find(text)
            
            if (match != null) {
                val headerWithTimestamp = match.groupValues[1]
                val beforeMessage = text.substring(0, match.range.first)
                
                // Rebuild the markdown with updated content
                conversationMarkdown.clear()
                conversationMarkdown.append(beforeMessage)
                conversationMarkdown.append("<a name=\"$lastMessageAnchor\"></a>\n")
                conversationMarkdown.append("## $headerWithTimestamp\n\n")
                conversationMarkdown.append(content)
                
                // Update the markdown display
                updateConversationDisplay()
            }
        }
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
     * Scroll to specific message anchor using Swing
     */
    private fun scrollToMessageAnchor(anchor: String) {
        SwingUtilities.invokeLater {
            try {
                // Use JEditorPane's built-in anchor scrolling
                conversationArea.scrollToReference(anchor)
            } catch (e: Exception) {
                // Fallback: scroll to bottom
                val vertical = chatScrollPane.verticalScrollBar  
                vertical.value = vertical.maximum
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

    private data class SimpleMessageData(
        val type: String,
        val content: String,
        val time: String
    )

    // Tree renderer removed - using modern chat bubbles instead

}