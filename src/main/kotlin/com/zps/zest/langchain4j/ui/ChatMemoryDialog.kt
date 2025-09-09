package com.zps.zest.langchain4j.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.langchain4j.data.message.*
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Standalone dialog for viewing LangChain4j chat memory with collapsible messages.
 * Provides dedicated window for debugging agent conversations.
 */
class ChatMemoryDialog(
    private val project: Project,
    private val chatMemory: MessageWindowChatMemory?,
    private val agentName: String = "Agent"
) : DialogWrapper(project) {

    private val messagesPanel = JPanel()
    private lateinit var scrollPane: JBScrollPane
    private val disposables = mutableListOf<() -> Unit>()
    
    // Custom panel class for chat bubbles with rounded corners and hover effects
    private inner class ChatBubblePanel(private val message: ChatMessage) : JPanel() {
        private val cornerRadius = 12 // Slightly smaller for modern look
        private var isHovered = false
        
        init {
            // Add mouse listener for hover effects
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    isHovered = true
                    repaint()
                }
                
                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    isHovered = false
                    repaint()
                }
            })
        }
        
        override fun paintComponent(g: Graphics) {
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 
                                               cornerRadius.toFloat(), cornerRadius.toFloat())
            
            // Add subtle shadow/elevation effect
            if (isHovered) {
                val shadowOffset = 2
                val shadowShape = RoundRectangle2D.Float(
                    shadowOffset.toFloat(), shadowOffset.toFloat(), 
                    width.toFloat(), height.toFloat(), 
                    cornerRadius.toFloat(), cornerRadius.toFloat()
                )
                g2d.color = if (UIUtil.isUnderDarcula()) Color(0, 0, 0, 40) else Color(0, 0, 0, 20)
                g2d.fill(shadowShape)
            }
            
            // Fill background with subtle hover effect
            val backgroundColor = getMessageBackgroundColor(message)
            g2d.color = if (isHovered) {
                Color(
                    (backgroundColor.red * 0.95).toInt(),
                    (backgroundColor.green * 0.95).toInt(), 
                    (backgroundColor.blue * 0.95).toInt(),
                    backgroundColor.alpha
                )
            } else {
                backgroundColor
            }
            g2d.fill(shape)
            
            // Draw border with subtle gradient
            val borderColor = getMessageBorderColor(message)
            g2d.color = if (isHovered) {
                Color(borderColor.red, borderColor.green, borderColor.blue, 180)
            } else {
                Color(borderColor.red, borderColor.green, borderColor.blue, 120)
            }
            g2d.stroke = BasicStroke(1.2f)
            g2d.draw(shape)
        }
    }
    
    init {
        title = "$agentName Chat Memory"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(1000, 700)
        
        // Header with stats
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Messages area
        messagesPanel.layout = BoxLayout(messagesPanel, BoxLayout.Y_AXIS)
        messagesPanel.background = UIUtil.getPanelBackground()
        messagesPanel.border = EmptyBorder(20, 10, 20, 10) // More top/bottom padding for chat feel
        
        scrollPane = JBScrollPane(messagesPanel)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = null // Clean border for chat interface
        
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Footer with controls
        val footerPanel = createFooterPanel()
        mainPanel.add(footerPanel, BorderLayout.SOUTH)
        
        renderMessages()
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 5, 10)
        panel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("💬 $agentName Conversation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        val statsLabel = JBLabel(getMemoryStats())
        statsLabel.foreground = UIUtil.getContextHelpForeground()
        panel.add(statsLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createFooterPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        panel.border = EmptyBorder(8, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { renderMessages() }
        panel.add(refreshButton)
        
        val exportButton = JButton("Export")
        exportButton.addActionListener { exportMemory() }
        panel.add(exportButton)
        
        return panel
    }
    
    private fun renderMessages() {
        SwingUtilities.invokeLater {
            messagesPanel.removeAll()
            
            val messages = getMessages()
            
            if (messages.isEmpty()) {
                showEmptyState()
            } else {
                messages.forEachIndexed { index, message ->
                    val messageComponent = createCollapsibleMessage(message, index)
                    messagesPanel.add(messageComponent)
                    
                    if (index < messages.size - 1) {
                        val nextMessage = messages[index + 1]
                        // Group consecutive messages from same type with less spacing
                        val spacing = if (isSameMessageType(message, nextMessage)) {
                            8 // Tighter spacing for grouped messages
                        } else {
                            20 // More spacing between different message types
                        }
                        messagesPanel.add(Box.createVerticalStrut(spacing))
                    }
                }
            }
            
            messagesPanel.revalidate()
            messagesPanel.repaint()
            
            // Auto-scroll to bottom
            SwingUtilities.invokeLater {
                val scrollBar = scrollPane.verticalScrollBar
                scrollBar.value = scrollBar.maximum
            }
        }
    }
    
    private fun createCollapsibleMessage(message: ChatMessage, index: Int): JComponent {
        // Container panel for alignment
        val containerPanel = JPanel()
        containerPanel.layout = BoxLayout(containerPanel, BoxLayout.X_AXIS)
        containerPanel.isOpaque = false
        
        // Determine alignment based on message type
        val isUserMessage = message is UserMessage
        
        // Create chat bubble with adaptive sizing
        val bubblePanel = ChatBubblePanel(message)
        bubblePanel.layout = BorderLayout()
        bubblePanel.border = EmptyBorder(12, 18, 12, 18) // Slightly more horizontal padding
        
        // Add right-click context menu
        addContextMenu(bubblePanel, message)
        
        // Adaptive width based on content and message type
        val contentLength = getMessageContentLength(message)
        val dialogWidth = scrollPane.width.takeIf { it > 0 } ?: 1000 // Use scroll pane width or fallback
        val availableWidth = dialogWidth.coerceAtLeast(600) // Minimum sensible width
        
        val maxWidth = when {
            message is SystemMessage -> (availableWidth * 0.85).toInt().coerceAtLeast(450)
            contentLength > 200 -> (availableWidth * 0.75).toInt().coerceAtLeast(400)
            else -> (availableWidth * 0.65).toInt().coerceAtLeast(300)
        }.coerceAtMost(800) // Maximum width cap
        
        bubblePanel.maximumSize = Dimension(maxWidth, Int.MAX_VALUE)
        bubblePanel.preferredSize = Dimension(maxWidth, -1)
        
        // Header with message type (no toggle needed)
        val headerPanel = createSimpleChatHeader(message, index)
        bubblePanel.add(headerPanel, BorderLayout.NORTH)
        
        // Content panel (always visible - no collapse/expand)
        val contentPanel = createMessageContent(message)
        bubblePanel.add(contentPanel, BorderLayout.CENTER)
        
        // Align message bubbles
        if (isUserMessage) {
            // User messages on the right
            containerPanel.add(Box.createHorizontalGlue())
            containerPanel.add(bubblePanel)
            containerPanel.add(Box.createHorizontalStrut(20))
        } else {
            // AI/System/Tool messages on the left
            containerPanel.add(Box.createHorizontalStrut(20))
            containerPanel.add(bubblePanel)
            containerPanel.add(Box.createHorizontalGlue())
        }
        
        return containerPanel
    }
    
    private fun createSimpleChatHeader(message: ChatMessage, index: Int): JComponent {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.border = EmptyBorder(0, 0, 6, 0)
        
        // Left: Message type with icon (clean and simple)
        val typeLabel = JLabel("${getMessageIcon(message)} ${getMessageTypeName(message)}")
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 12f) // Slightly smaller for less prominence
        typeLabel.foreground = UIUtil.getInactiveTextColor() // Subtle color
        
        headerPanel.add(typeLabel, BorderLayout.WEST)
        
        // Right: Just timestamp (move copy to context menu later)
        val timestampLabel = JLabel(java.text.SimpleDateFormat("HH:mm").format(java.util.Date()))
        timestampLabel.foreground = UIUtil.getInactiveTextColor()
        timestampLabel.font = timestampLabel.font.deriveFont(10f) // Small and subtle
        
        headerPanel.add(timestampLabel, BorderLayout.EAST)
        
        return headerPanel
    }

    private fun addContextMenu(component: JComponent, message: ChatMessage) {
        val popupMenu = JPopupMenu()
        
        // Copy message
        val copyItem = JMenuItem("Copy Message")
        copyItem.addActionListener { copyMessageContent(message) }
        popupMenu.add(copyItem)
        
        // Copy as text
        val copyTextItem = JMenuItem("Copy as Plain Text")
        copyTextItem.addActionListener { copyMessageAsPlainText(message) }
        popupMenu.add(copyTextItem)
        
        if (message is AiMessage && message.toolExecutionRequests().isNotEmpty()) {
            popupMenu.addSeparator()
            val toolsItem = JMenuItem("Show Tool Details")
            toolsItem.addActionListener { showToolDetails(message) }
            popupMenu.add(toolsItem)
        }
        
        component.componentPopupMenu = popupMenu
    }
    
    private fun copyMessageAsPlainText(message: ChatMessage) {
        val text = when (message) {
            is SystemMessage -> message.text()
            is UserMessage -> message.singleText()
            is AiMessage -> message.text() ?: ""
            is ToolExecutionResultMessage -> message.text()
            else -> message.toString()
        }
        
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
    
    private fun showToolDetails(message: AiMessage) {
        // This could open a detailed tool execution dialog
        val details = buildString {
            message.toolExecutionRequests().forEach { tool ->
                appendLine("Tool: ${tool.name()}")
                appendLine("Arguments:")
                appendLine(tool.arguments())
                appendLine("---")
            }
        }
        
        showFullTextContent(details, "Tool Execution Details")
    }
    
    private fun createMessageContent(message: ChatMessage): JComponent {
        return when (message) {
            is SystemMessage -> createTextContent(message.text(), "System Prompt")
            is UserMessage -> createTextContent(message.singleText(), "User Input") 
            is AiMessage -> createAiMessageContent(message)
            is ToolExecutionResultMessage -> createToolResultContent(message)
            else -> createTextContent(message.toString(), "Unknown Message")
        }
    }
    
    private fun createTextContent(text: String, label: String): JComponent {
        if (containsCode(text)) {
            return createCodeEditor(text, detectLanguage(text), label)
        } else {
            // Implement progressive disclosure for long text
            if (text.length > 500) {
                return createProgressiveTextContent(text)
            } else {
                val textArea = JTextArea(text)
                textArea.isEditable = false
                textArea.isOpaque = false // Transparent background to blend with bubble
                textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 14) // Main content font size
                textArea.lineWrap = true
                textArea.wrapStyleWord = true
                textArea.border = EmptyBorder(8, 0, 0, 0) // Better spacing from header
                textArea.caretPosition = 0 // Ensure cursor is at start
                
                return textArea
            }
        }
    }
    
    private fun createProgressiveTextContent(text: String): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = EmptyBorder(8, 0, 0, 0)
        
        // Show first few lines
        val lines = text.split('\n')
        val previewLines = lines.take(3)
        val previewText = previewLines.joinToString("\n")
        
        val textArea = JTextArea(previewText)
        textArea.isEditable = false
        textArea.isOpaque = false
        textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.caretPosition = 0
        
        panel.add(textArea)
        
        // Add "Show more" link if there's more content
        if (lines.size > 3 || text.length > 300) {
            panel.add(Box.createVerticalStrut(8))
            val showMoreButton = JButton("Show more...")
            showMoreButton.font = showMoreButton.font.deriveFont(12f)
            showMoreButton.isFocusPainted = false
            showMoreButton.isBorderPainted = false
            showMoreButton.isContentAreaFilled = false
            showMoreButton.foreground = if (UIUtil.isUnderDarcula()) Color(150, 200, 255) else Color(70, 120, 200)
            showMoreButton.addActionListener { showFullTextContent(text, "Message Content") }
            
            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            buttonPanel.isOpaque = false
            buttonPanel.add(showMoreButton)
            panel.add(buttonPanel)
        }
        
        return panel
    }
    
    private fun showFullTextContent(text: String, title: String) {
        val dialog = object : DialogWrapper(project) {
            init {
                this.title = title
                init()
            }
            
            override fun createCenterPanel(): JComponent {
                val textArea = JTextArea(text)
                textArea.isEditable = false
                textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                textArea.lineWrap = true
                textArea.wrapStyleWord = true
                textArea.caretPosition = 0
                
                val scrollPane = JBScrollPane(textArea)
                scrollPane.preferredSize = Dimension(700, 500)
                return scrollPane
            }
            
            override fun createActions() = arrayOf(okAction)
        }
        dialog.show()
    }
    
    private fun createAiMessageContent(message: AiMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        
        // AI text response
        message.text()?.let { text ->
            if (text.isNotBlank()) {
                val textComponent = createTextContent(text, "AI Response")
                panel.add(textComponent)
                panel.add(Box.createVerticalStrut(8))
            }
        }
        
        // Tool execution requests
        message.toolExecutionRequests().forEachIndexed { index, toolRequest ->
            val toolComponent = createToolRequestComponent(toolRequest, index)
            panel.add(toolComponent)
            if (index < message.toolExecutionRequests().size - 1) {
                panel.add(Box.createVerticalStrut(5))
            }
        }
        
        return panel
    }
    
    private fun createToolRequestComponent(toolRequest: dev.langchain4j.agent.tool.ToolExecutionRequest, index: Int): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = EmptyBorder(6, 0, 6, 0)
        
        // Create inline tool call chip
        val toolChip = createToolChip(toolRequest)
        panel.add(toolChip)
        
        return panel
    }
    
    private fun createToolChip(toolRequest: dev.langchain4j.agent.tool.ToolExecutionRequest): JComponent {
        val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        chipPanel.isOpaque = false
        
        // Tool icon and name
        val toolLabel = JLabel("🔧 ${toolRequest.name()}")
        toolLabel.font = toolLabel.font.deriveFont(Font.BOLD, 12f)
        toolLabel.foreground = if (UIUtil.isUnderDarcula()) Color(150, 200, 255) else Color(70, 120, 200)
        
        // Parse and format arguments nicely
        val argsDisplay = formatToolArguments(toolRequest.arguments())
        val argsLabel = JLabel("($argsDisplay)")
        argsLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        argsLabel.foreground = UIUtil.getInactiveTextColor()
        
        chipPanel.add(toolLabel)
        chipPanel.add(argsLabel)
        
        // Add expand button for full JSON if needed
        if (toolRequest.arguments().length > 100) {
            val expandButton = JButton("...")
            expandButton.font = expandButton.font.deriveFont(10f)
            expandButton.isFocusPainted = false
            expandButton.isBorderPainted = false
            expandButton.isContentAreaFilled = false
            expandButton.toolTipText = "Show full arguments"
            expandButton.addActionListener { showFullToolArguments(toolRequest) }
            chipPanel.add(Box.createHorizontalStrut(5))
            chipPanel.add(expandButton)
        }
        
        return chipPanel
    }
    
    private fun createToolResultContent(message: ToolExecutionResultMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = EmptyBorder(6, 0, 6, 0)
        
        // Create inline tool result chip
        val resultChip = createToolResultChip(message)
        panel.add(resultChip)
        
        return panel
    }
    
    private fun createToolResultChip(message: ToolExecutionResultMessage): JComponent {
        val chipPanel = JPanel()
        chipPanel.layout = BoxLayout(chipPanel, BoxLayout.Y_AXIS)
        chipPanel.isOpaque = false
        
        // Result header with collapse/expand
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        headerPanel.isOpaque = false
        
        val resultLabel = JLabel("✅ ${message.toolName()}")
        resultLabel.font = resultLabel.font.deriveFont(Font.BOLD, 12f)
        resultLabel.foreground = if (UIUtil.isUnderDarcula()) Color(150, 255, 150) else Color(60, 150, 60)
        headerPanel.add(resultLabel)
        
        // Show summary/preview of result
        val resultText = message.text()
        val preview = if (resultText.length > 100) {
            resultText.take(97) + "..."
        } else {
            resultText
        }
        
        val previewLabel = JLabel(" → ${preview.replace('\n', ' ')}")
        previewLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
        previewLabel.foreground = UIUtil.getInactiveTextColor()
        headerPanel.add(previewLabel)
        
        // Add expand button for long results
        if (resultText.length > 100) {
            val expandButton = JButton("Show full result")
            expandButton.font = expandButton.font.deriveFont(10f)
            expandButton.isFocusPainted = false
            expandButton.isBorderPainted = false
            expandButton.isContentAreaFilled = false
            expandButton.addActionListener { showFullToolResult(message) }
            headerPanel.add(Box.createHorizontalStrut(5))
            headerPanel.add(expandButton)
        }
        
        chipPanel.add(headerPanel)
        return chipPanel
    }
    
    private fun createCodeEditor(content: String, language: String, label: String): JComponent {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(content)
        val editor = editorFactory.createViewer(document, project) as EditorEx
        
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = false
            isUseSoftWraps = true
            additionalLinesCount = 1
        }
        
        // Apply syntax highlighting
        val fileType = when (language.lowercase()) {
            "java" -> FileTypeManager.getInstance().getFileTypeByExtension("java")
            "json" -> FileTypeManager.getInstance().getFileTypeByExtension("json")
            "xml" -> FileTypeManager.getInstance().getFileTypeByExtension("xml")
            "yaml", "yml" -> FileTypeManager.getInstance().getFileTypeByExtension("yml")
            else -> PlainTextFileType.INSTANCE
        }
        
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
        editor.highlighter = highlighter
        editor.isViewer = true
        
        val wrapper = JPanel(BorderLayout())
        wrapper.add(editor.component, BorderLayout.CENTER)
        wrapper.maximumSize = Dimension(Int.MAX_VALUE, 300)
        
        disposables.add { editorFactory.releaseEditor(editor) }
        
        return wrapper
    }
    
    private fun showEmptyState() {
        val emptyPanel = JPanel()
        emptyPanel.layout = BoxLayout(emptyPanel, BoxLayout.Y_AXIS)
        emptyPanel.isOpaque = false
        
        val iconLabel = JBLabel("💭", SwingConstants.CENTER)
        iconLabel.font = iconLabel.font.deriveFont(48f)
        iconLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(iconLabel)
        
        emptyPanel.add(Box.createVerticalStrut(20))
        
        val (message, helpText) = if (chatMemory != null) {
            "No conversation messages yet" to "Start a test generation session to see agent conversations here."
        } else {
            "Chat memory not initialized" to "The $agentName hasn't been created yet. Try running a test generation session first."
        }
        
        val messageLabel = JBLabel(message, SwingConstants.CENTER)
        messageLabel.font = messageLabel.font.deriveFont(Font.BOLD, 16f)
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(messageLabel)
        
        emptyPanel.add(Box.createVerticalStrut(10))
        
        val helpLabel = JBLabel(helpText, SwingConstants.CENTER)
        helpLabel.font = helpLabel.font.deriveFont(Font.ITALIC, 12f)
        helpLabel.foreground = UIUtil.getInactiveTextColor()
        helpLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(helpLabel)
        
        messagesPanel.add(Box.createVerticalGlue())
        messagesPanel.add(emptyPanel)
        messagesPanel.add(Box.createVerticalGlue())
    }
    
    // Removed collapse/expand functionality - modern chat UX doesn't use this pattern
    
    private fun getMessages(): List<ChatMessage> {
        return try {
            chatMemory?.messages() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun getMemoryStats(): String {
        return if (chatMemory != null) {
            try {
                val count = chatMemory.messages().size
                "Messages: $count | Max: 100"
            } catch (e: Exception) {
                "Messages: error"
            }
        } else {
            "No memory available"
        }
    }
    
    private fun getMessageIcon(message: ChatMessage): String {
        return when (message) {
            is SystemMessage -> "⚙️"
            is UserMessage -> "👤"
            is AiMessage -> "🤖"
            is ToolExecutionResultMessage -> "🔧"
            else -> "💬"
        }
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
    
    private fun getMessagePreview(message: ChatMessage): String {
        val text = when (message) {
            is SystemMessage -> message.text()
            is UserMessage -> message.singleText()
            is AiMessage -> message.text() ?: "Tool calls: ${message.toolExecutionRequests().size}"
            is ToolExecutionResultMessage -> message.toolName()
            else -> "Unknown message"
        }
        
        return if (text.length > 80) {
            text.substring(0, 77) + "..."
        } else {
            text
        }
    }
    
    private fun getMessageContentLength(message: ChatMessage): Int {
        return when (message) {
            is SystemMessage -> message.text().length
            is UserMessage -> message.singleText().length
            is AiMessage -> (message.text()?.length ?: 0) + (message.toolExecutionRequests().size * 50)
            is ToolExecutionResultMessage -> message.text().length
            else -> 50
        }
    }
    
    private fun isSameMessageType(message1: ChatMessage, message2: ChatMessage): Boolean {
        return message1.javaClass == message2.javaClass
    }
    
    private fun formatToolArguments(jsonArgs: String): String {
        return try {
            // Simple JSON parsing to extract key parameters
            val args = jsonArgs.trim()
                .removePrefix("{").removeSuffix("}")
                .split(",")
                .take(3) // Show max 3 parameters
                .map { it.split(":").let { parts ->
                    if (parts.size >= 2) {
                        val key = parts[0].trim().removeSurrounding("\"")
                        val value = parts[1].trim().removeSurrounding("\"")
                        "$key=${if (value.length > 20) value.take(17) + "..." else value}"
                    } else it.trim()
                }}
                .joinToString(", ")
            
            if (jsonArgs.split(",").size > 3) "$args, ..." else args
        } catch (e: Exception) {
            "..."
        }
    }
    
    private fun showFullToolArguments(toolRequest: dev.langchain4j.agent.tool.ToolExecutionRequest) {
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Tool Arguments - ${toolRequest.name()}"
                init()
            }
            
            override fun createCenterPanel(): JComponent {
                val textArea = JTextArea(toolRequest.arguments())
                textArea.isEditable = false
                textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                textArea.lineWrap = true
                textArea.wrapStyleWord = true
                
                val scrollPane = JBScrollPane(textArea)
                scrollPane.preferredSize = Dimension(400, 200)
                return scrollPane
            }
            
            override fun createActions() = arrayOf(okAction)
        }
        dialog.show()
    }
    
    private fun showFullToolResult(message: ToolExecutionResultMessage) {
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Tool Result - ${message.toolName()}"
                init()
            }
            
            override fun createCenterPanel(): JComponent {
                val resultText = message.text()
                val language = detectLanguage(resultText)
                
                // Use code editor for formatted results if it looks like code/data
                if (containsCode(resultText)) {
                    return createCodeEditor(resultText, language, "Result")
                } else {
                    val textArea = JTextArea(resultText)
                    textArea.isEditable = false
                    textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                    textArea.lineWrap = true
                    textArea.wrapStyleWord = true
                    
                    val scrollPane = JBScrollPane(textArea)
                    scrollPane.preferredSize = Dimension(600, 400)
                    return scrollPane
                }
            }
            
            override fun createActions() = arrayOf(okAction)
        }
        dialog.show()
    }
    
    private fun getMessageBackgroundColor(message: ChatMessage): Color {
        return if (UIUtil.isUnderDarcula()) {
            when (message) {
                is SystemMessage -> Color(60, 45, 30)    // Dark orange
                is UserMessage -> Color(30, 40, 55)      // Dark blue
                is AiMessage -> Color(30, 55, 30)        // Dark green
                is ToolExecutionResultMessage -> Color(45, 45, 45) // Dark gray
                else -> UIUtil.getPanelBackground()
            }
        } else {
            when (message) {
                is SystemMessage -> Color(255, 250, 240) // Light orange
                is UserMessage -> Color(240, 248, 255)   // Light blue
                is AiMessage -> Color(248, 255, 248)     // Light green
                is ToolExecutionResultMessage -> Color(250, 250, 250) // Light gray
                else -> UIUtil.getPanelBackground()
            }
        }
    }
    
    private fun getMessageBorderColor(message: ChatMessage): Color {
        return if (UIUtil.isUnderDarcula()) {
            when (message) {
                is SystemMessage -> Color(200, 130, 30)   // Bright orange for dark theme
                is UserMessage -> Color(80, 120, 200)     // Bright blue for dark theme
                is AiMessage -> Color(80, 150, 80)        // Bright green for dark theme
                is ToolExecutionResultMessage -> Color(150, 150, 150) // Light gray for dark theme
                else -> UIUtil.getBoundsColor()
            }
        } else {
            when (message) {
                is SystemMessage -> Color(255, 165, 0)   // Orange
                is UserMessage -> Color(100, 149, 237)   // Cornflower blue
                is AiMessage -> Color(60, 179, 113)      // Medium sea green
                is ToolExecutionResultMessage -> Color(128, 128, 128) // Gray
                else -> UIUtil.getBoundsColor()
            }
        }
    }
    
    private fun containsCode(text: String): Boolean {
        return text.contains("{") || text.contains("class ") || text.contains("function ") || 
               text.contains("import ") || text.contains("package ")
    }
    
    private fun detectLanguage(content: String): String {
        return when {
            content.contains("class ") && content.contains("public") -> "java"
            content.contains("{") && content.contains("\"") -> "json"
            content.contains("<") && content.contains(">") -> "xml"
            content.contains("import ") -> "java"
            else -> "text"
        }
    }
    
    private fun copyMessageContent(message: ChatMessage) {
        val content = when (message) {
            is SystemMessage -> "System: ${message.text()}"
            is UserMessage -> "User: ${message.singleText()}"
            is AiMessage -> buildString {
                message.text()?.let { append("AI: $it\n") }
                message.toolExecutionRequests().forEach {
                    append("Tool: ${it.name()} - ${it.arguments()}\n")
                }
            }
            is ToolExecutionResultMessage -> "Tool Result: ${message.toolName()} - ${message.text()}"
            else -> message.toString()
        }
        
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(content), null)
    }
    
    private fun exportMemory() {
        val content = buildString {
            appendLine("$agentName Chat Memory Export")
            appendLine("=" .repeat(50))
            appendLine("Exported: ${java.time.LocalDateTime.now()}")
            appendLine()
            
            getMessages().forEachIndexed { index, message ->
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
            "Chat memory exported to clipboard",
            "Export Complete",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    
    override fun dispose() {
        disposables.forEach { it() }
        super.dispose()
    }
}