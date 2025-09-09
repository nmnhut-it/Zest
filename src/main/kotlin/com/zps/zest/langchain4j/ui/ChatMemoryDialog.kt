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
import java.awt.event.ActionEvent
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
        messagesPanel.border = EmptyBorder(10, 10, 10, 10)
        
        scrollPane = JBScrollPane(messagesPanel)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = BorderFactory.createLoweredBevelBorder()
        
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
        panel.border = EmptyBorder(5, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        val collapseAllButton = JButton("Collapse All")
        collapseAllButton.addActionListener { collapseAllMessages() }
        panel.add(collapseAllButton)
        
        val expandAllButton = JButton("Expand All")  
        expandAllButton.addActionListener { expandAllMessages() }
        panel.add(expandAllButton)
        
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
                        messagesPanel.add(Box.createVerticalStrut(8))
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
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(getMessageBorderColor(message), 2),
            EmptyBorder(8, 10, 8, 10)
        )
        mainPanel.background = getMessageBackgroundColor(message)
        
        // Header with toggle button
        val headerPanel = createMessageHeader(message, index, mainPanel)
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Content panel (initially visible for non-system messages, collapsed for system)
        val contentPanel = createMessageContent(message)
        contentPanel.isVisible = !(message is SystemMessage) // Collapse system messages by default
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createMessageHeader(message: ChatMessage, index: Int, parentPanel: JPanel): JComponent {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.border = EmptyBorder(0, 0, 5, 0)
        
        // Left: Message type and toggle
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        leftPanel.isOpaque = false
        
        val toggleButton = JButton("▼")
        toggleButton.font = Font(Font.MONOSPACED, Font.BOLD, 10)
        toggleButton.preferredSize = Dimension(25, 20)
        toggleButton.addActionListener { toggleMessageContent(parentPanel, toggleButton) }
        leftPanel.add(toggleButton)
        
        val typeLabel = JBLabel("${getMessageIcon(message)} ${getMessageTypeName(message)}")
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 12f)
        leftPanel.add(typeLabel)
        
        // Content preview for collapsed view
        val previewLabel = JBLabel(getMessagePreview(message))
        previewLabel.foreground = UIUtil.getContextHelpForeground()
        previewLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 10)
        leftPanel.add(previewLabel)
        
        headerPanel.add(leftPanel, BorderLayout.WEST)
        
        // Right: Message index and controls
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        rightPanel.isOpaque = false
        
        val indexLabel = JBLabel("#$index")
        indexLabel.foreground = UIUtil.getContextHelpForeground()
        indexLabel.font = indexLabel.font.deriveFont(9f)
        rightPanel.add(indexLabel)
        
        val copyButton = JButton("📋")
        copyButton.font = copyButton.font.deriveFont(8f)
        copyButton.toolTipText = "Copy message content"
        copyButton.addActionListener { copyMessageContent(message) }
        rightPanel.add(copyButton)
        
        headerPanel.add(rightPanel, BorderLayout.EAST)
        
        return headerPanel
    }
    
    private fun toggleMessageContent(parentPanel: JPanel, toggleButton: JButton) {
        val contentPanel = findContentPanel(parentPanel)
        contentPanel?.let {
            val isVisible = it.isVisible
            it.isVisible = !isVisible
            toggleButton.text = if (isVisible) "▶" else "▼"
            parentPanel.revalidate()
            parentPanel.repaint()
        }
    }
    
    private fun findContentPanel(parentPanel: JPanel): JComponent? {
        for (component in parentPanel.components) {
            if (component != parentPanel.getComponent(0)) { // Skip header
                return component as? JComponent
            }
        }
        return null
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
            val textArea = JTextArea(text)
            textArea.isEditable = false
            textArea.background = UIUtil.getPanelBackground()
            textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            
            val scrollPane = JBScrollPane(textArea)
            scrollPane.border = BorderFactory.createTitledBorder(label)
            scrollPane.maximumSize = Dimension(Int.MAX_VALUE, 200)
            return scrollPane
        }
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
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("🔧 Tool Call #${index + 1}: ${toolRequest.name()}")
        panel.background = Color(248, 250, 255)
        
        val argsText = toolRequest.arguments()
        val argsComponent = createCodeEditor(argsText, "json", "Arguments")
        panel.add(argsComponent, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createToolResultContent(message: ToolExecutionResultMessage): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("🔧 ${message.toolName()} Result")
        panel.background = Color(240, 255, 240)
        
        val resultText = message.text()
        val language = detectLanguage(resultText)
        val resultComponent = createCodeEditor(resultText, language, "Result")
        panel.add(resultComponent, BorderLayout.CENTER)
        
        return panel
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
        
        val messageLabel = JBLabel(
            if (chatMemory != null) "No conversation messages yet" else "No chat memory available", 
            SwingConstants.CENTER
        )
        messageLabel.font = messageLabel.font.deriveFont(Font.BOLD, 16f)
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(messageLabel)
        
        messagesPanel.add(Box.createVerticalGlue())
        messagesPanel.add(emptyPanel)
        messagesPanel.add(Box.createVerticalGlue())
    }
    
    private fun collapseAllMessages() {
        for (component in messagesPanel.components) {
            if (component is JPanel) {
                val contentPanel = findContentPanel(component)
                contentPanel?.isVisible = false
                findToggleButton(component)?.text = "▶"
            }
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }
    
    private fun expandAllMessages() {
        for (component in messagesPanel.components) {
            if (component is JPanel) {
                val contentPanel = findContentPanel(component)
                contentPanel?.isVisible = true
                findToggleButton(component)?.text = "▼"
            }
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }
    
    private fun findToggleButton(panel: JPanel): JButton? {
        // Find the toggle button in the header panel
        for (component in panel.components) {
            if (component is JPanel) {
                for (subComponent in component.components) {
                    if (subComponent is JPanel) {
                        for (buttonComponent in subComponent.components) {
                            if (buttonComponent is JButton && (buttonComponent.text == "▼" || buttonComponent.text == "▶")) {
                                return buttonComponent
                            }
                        }
                    }
                }
            }
        }
        return null
    }
    
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
    
    private fun getMessageBackgroundColor(message: ChatMessage): Color {
        return when (message) {
            is SystemMessage -> Color(255, 250, 240) // Light orange
            is UserMessage -> Color(240, 248, 255)   // Light blue
            is AiMessage -> Color(248, 255, 248)     // Light green
            is ToolExecutionResultMessage -> Color(250, 250, 250) // Light gray
            else -> UIUtil.getPanelBackground()
        }
    }
    
    private fun getMessageBorderColor(message: ChatMessage): Color {
        return when (message) {
            is SystemMessage -> Color(255, 165, 0)   // Orange
            is UserMessage -> Color(100, 149, 237)   // Cornflower blue
            is AiMessage -> Color(60, 179, 113)      // Medium sea green
            is ToolExecutionResultMessage -> Color(128, 128, 128) // Gray
            else -> UIUtil.getBoundsColor()
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