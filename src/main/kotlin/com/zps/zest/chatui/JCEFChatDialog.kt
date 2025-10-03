package com.zps.zest.chatui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
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
 * JCEF-based chat dialog for better HTML rendering and interactive features
 */
class JCEFChatDialog(
    private val project: Project
) : DialogWrapper(project, false) {


    private val chatService = project.getService(ChatUIService::class.java)
    private val chatPanel = JCEFChatPanel(project)
    private val inputArea = JBTextArea()
    private val sendButton = JButton("Send")
    private val clearButton = JButton("Clear")
    private var isProcessing = false

    init {
        title = "💬 Zest Chat"
        setSize(1000, 700)
        isModal = false
        
        // Initialize with welcome message
        chatPanel.addMessage(
            "💬 **Welcome to Zest Chat!**", 
            "Start a conversation by typing your question below..."
        )
        
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(1000, 700)

        // Header with stats
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Main split pane: chat (top) and input (bottom)
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitPane.resizeWeight = 0.7 // 70% for chat, 30% for input

        // Chat panel
        splitPane.topComponent = chatPanel
        chatPanel.minimumSize = JBUI.size(400, 200)

        // Input panel
        val inputPanel = createInputPanel()
        inputPanel.minimumSize = JBUI.size(400, 150)
        splitPane.bottomComponent = inputPanel

        mainPanel.add(splitPane, BorderLayout.CENTER)

        // Footer with controls
        val footerPanel = createFooterPanel()
        mainPanel.add(footerPanel, BorderLayout.SOUTH)

        // Add developer tools integration
        addDeveloperToolsIntegration(mainPanel)

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
        
        // Message limit indicator
        val messageCount = chatService.getMessages().size
        val statsLabel = JBLabel("$messageCount/50")
        statsLabel.foreground = when {
            messageCount > 40 -> if (isDarkMode()) Color(255, 200, 100) else Color(200, 100, 0)
            messageCount > 45 -> if (isDarkMode()) Color(255, 150, 150) else Color(200, 50, 50)
            else -> UIUtil.getInactiveTextColor()
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

        // Handle Enter key for sending
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

        val inputScrollPane = com.intellij.ui.components.JBScrollPane(inputArea)
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
        refreshButton.addActionListener { loadMessages(forceRefresh = true) }
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

        // Add user message to chat
        chatPanel.addMessage("👤 **You**", userMessage)

        // Add initial AI message placeholder for streaming
        val aiMessageId = chatPanel.addMessage("🤖 **AI**", "...")
        
        val responseBuilder = StringBuilder()
        
        // Send to AI with streaming and tool support
        chatService.sendMessageStreaming(
            userMessage,
            onToken = { chunk ->
                // Send chunk to client-side streaming handler
                val escapedChunk = escapeJavaScriptString(chunk)
                chatPanel.getBrowserManager().executeJavaScript("""
                    if (window.chatFunctions && window.chatFunctions.updateMessageStreaming) {
                        window.chatFunctions.updateMessageStreaming('$aiMessageId', '$escapedChunk');
                    }
                """)
            },
            onComplete = { fullResponse ->
                ApplicationManager.getApplication().invokeLater {
                    // Finalize message with proper markdown rendering
                    chatPanel.finalizeMessage(aiMessageId, fullResponse)
                    
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
                    // Show error in the message
                    val errorMessage = "❌ Failed to get response: ${error.message}"
                    chatPanel.finalizeMessage(aiMessageId, errorMessage)
                    
                    // Reset UI state
                    isProcessing = false
                    sendButton.text = "Send"
                    sendButton.isEnabled = true
                }
            },
            onToolCall = { toolName, toolArgs, toolCallId ->
                // Add tool call to message using data model (persists through reloads)
                chatPanel.addToolCallToMessage(aiMessageId, toolCallId, toolName, toolArgs)
            },
            onToolResult = { toolCallId, result ->
                // Update tool call status and result in data model
                val status = if (result.startsWith("❌")) "error" else "complete"
                chatPanel.updateToolCallStatus(aiMessageId, toolCallId, status, result)
            }
        )
    }

    private fun clearInput() {
        inputArea.text = ""
        inputArea.requestFocus()
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
     * Add developer tools integration with context menu and F12 key binding
     */
    private fun addDeveloperToolsIntegration(panel: JPanel) {
        // Create context menu for debugging
        val contextMenu = JPopupMenu()
        val devToolsItem = JMenuItem("Open Developer Tools (F12)")
        devToolsItem.addActionListener {
            try {
                chatPanel.getBrowserManager().getBrowser().openDevtools()
            } catch (ex: Exception) {
                ex.printStackTrace();
            }
        }
        contextMenu.add(devToolsItem)
        
        // Add context menu to main panel and chat component
        panel.setComponentPopupMenu(contextMenu)
        val chatComponent = chatPanel.getComponent(0)
        
        // Add mouse listener for context menu
        chatComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger) {
                    contextMenu.show(e.component, e.x, e.y)
                }
            }
            
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger) {
                    contextMenu.show(e.component, e.x, e.y)
                }
            }
        })
        
        // Add F12 key binding for dev tools
        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F12, 0), "openDevTools")
        panel.actionMap.put("openDevTools", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                try {
                    chatPanel.getBrowserManager().getBrowser().openDevtools()
//                    LOG.info("Dev tools opened via F12 key")
                } catch (ex: Exception) {
//                    LOG.warn("Failed to open dev tools via F12", ex)
                }
            }
        })
    }

    private fun startNewChat() {
        chatService.clearConversation()
        chatPanel.clearMessages()
        chatPanel.addMessage(
            "💬 **Welcome to Zest Chat!**", 
            "Start a conversation by typing your question below..."
        )
        inputArea.requestFocus()
    }

    private fun loadMessages(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            // Force a complete browser reload by clearing and rebuilding
            chatPanel.clearMessages()
            // Add small delay to ensure browser processes the clear
            Thread.sleep(50)
        } else {
            chatPanel.clearMessages()
        }

        val messages = chatService.getMessages()

        if (messages.isEmpty()) {
            chatPanel.addMessage(
                "💬 **Welcome to Zest Chat!**",
                "Start a conversation by typing your question below..."
            )
        } else {
            // Rebuild chat from all messages, grouping tool results with AI messages
            var i = 0
            while (i < messages.size) {
                val message = messages[i]
                when (message) {
                    is UserMessage -> {
                        chatPanel.addMessage("👤 **You**", message.singleText())
                        i++
                    }
                    is AiMessage -> {
                        val aiText = message.text() ?: ""
                        val toolRequests = message.toolExecutionRequests()

                        // Collect tool results that follow this AI message
                        val toolResults = mutableListOf<ToolExecutionResultMessage>()
                        var j = i + 1
                        while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                            toolResults.add(messages[j] as ToolExecutionResultMessage)
                            j++
                        }

                        // Build content with tool calls and results
                        val fullContent = buildString {
                            if (aiText.isNotBlank()) {
                                appendLine(aiText)
                                appendLine()
                            }

                            if (!toolRequests.isNullOrEmpty()) {
                                toolRequests.forEach { toolRequest ->
                                    appendLine("🔧 **Tool Call: ${toolRequest.name()}**")
                                    appendLine("```json")
                                    appendLine(toolRequest.arguments())
                                    appendLine("```")

                                    // Find matching tool result
                                    val matchingResult = toolResults.find { it.toolName() == toolRequest.name() }
                                    if (matchingResult != null) {
                                        appendLine()
                                        appendLine("**Result:**")
                                        appendLine("```")
                                        appendLine(matchingResult.text())
                                        appendLine("```")
                                    }
                                    appendLine()
                                }
                            }
                        }

                        chatPanel.addMessage("🤖 **AI**", fullContent.trim())

                        // Skip the tool result messages we've already processed
                        i = j
                    }
                    is dev.langchain4j.data.message.SystemMessage -> {
                        chatPanel.addMessage("⚙️ **System**", message.text())
                        i++
                    }
                    is ToolExecutionResultMessage -> {
                        // Skip standalone tool results (they should have been grouped with AI message)
                        i++
                    }
                    else -> {
                        chatPanel.addMessage("💬 **Message**", message.toString())
                        i++
                    }
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

    private fun isDarkMode(): Boolean = com.zps.zest.util.ThemeUtils.isDarkTheme()
}