package com.zps.zest.langchain4j.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.langchain4j.data.message.*
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Generic UI component for displaying LangChain4j ChatMemory contents.
 * Plug-and-play: just pass any ChatMemory instance and it renders correctly.
 * 
 * Features:
 * - Displays all LangChain4j message types (System, User, AI, Tool Results)
 * - Syntax highlighting for code blocks and tool arguments
 * - Expandable tool call sections
 * - Clean, native rendering of conversation flow
 * - Export functionality for debugging
 */
class ChatMemoryPanel(
    private val project: Project,
    private val chatMemory: MessageWindowChatMemory?,
    private val title: String = "Chat Memory"
) : JPanel(BorderLayout()) {

    private val messagesPanel = JPanel()
    private val scrollPane: JBScrollPane = JBScrollPane()
    private val disposables = mutableListOf<() -> Unit>()
    
    init {
        setupUI()
        renderMessages()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        
        // Header
        add(createHeader(), BorderLayout.NORTH)
        
        // Messages area
        messagesPanel.layout = BoxLayout(messagesPanel, BoxLayout.Y_AXIS)
        messagesPanel.background = UIUtil.getPanelBackground()
        messagesPanel.border = EmptyBorder(5, 5, 5, 5)
        
        scrollPane.setViewportView(messagesPanel)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = BorderFactory.createLoweredBevelBorder()
        
        add(scrollPane, BorderLayout.CENTER)
        
        // Footer with controls
        add(createFooter(), BorderLayout.SOUTH)
    }
    
    private fun createHeader(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(5, 5, 3, 5)
        panel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("💬 $title")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        val statsLabel = JBLabel(getMemoryStats())
        statsLabel.foreground = UIUtil.getContextHelpForeground()
        statsLabel.font = statsLabel.font.deriveFont(10f)
        panel.add(statsLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createFooter(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
        panel.background = UIUtil.getPanelBackground()
        
        val refreshButton = JButton("Refresh")
        refreshButton.font = refreshButton.font.deriveFont(10f)
        refreshButton.addActionListener { renderMessages() }
        panel.add(refreshButton)
        
        val exportButton = JButton("Export")
        exportButton.font = exportButton.font.deriveFont(10f)
        exportButton.addActionListener { exportMemory() }
        panel.add(exportButton)
        
        return panel
    }
    
    fun renderMessages() {
        SwingUtilities.invokeLater {
            messagesPanel.removeAll()
            
            val messages = getMessages()
            
            if (messages.isEmpty()) {
                showEmptyState()
            } else {
                messages.forEachIndexed { index, message ->
                    val messageComponent = createMessageComponent(message, index)
                    messagesPanel.add(messageComponent)
                    
                    // Add spacing between messages
                    if (index < messages.size - 1) {
                        messagesPanel.add(Box.createVerticalStrut(5))
                    }
                }
            }
            
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
    }
    
    private fun showEmptyState() {
        val emptyPanel = JPanel()
        emptyPanel.layout = BoxLayout(emptyPanel, BoxLayout.Y_AXIS)
        emptyPanel.isOpaque = false
        
        val iconLabel = JBLabel("💭", SwingConstants.CENTER)
        iconLabel.font = iconLabel.font.deriveFont(24f)
        iconLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(iconLabel)
        
        emptyPanel.add(Box.createVerticalStrut(10))
        
        val messageLabel = JBLabel(if (chatMemory != null) "No messages yet" else "No chat memory available")
        messageLabel.foreground = UIUtil.getContextHelpForeground()
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyPanel.add(messageLabel)
        
        messagesPanel.add(Box.createVerticalGlue())
        messagesPanel.add(emptyPanel)
        messagesPanel.add(Box.createVerticalGlue())
    }
    
    private fun createMessageComponent(message: ChatMessage, index: Int): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(getMessageBorderColor(message), 1),
            EmptyBorder(8, 10, 8, 10)
        )
        panel.background = getMessageBackgroundColor(message)
        
        // Message header
        val header = createMessageHeader(message, index)
        panel.add(header, BorderLayout.NORTH)
        
        // Message content
        val content = createMessageContent(message)
        panel.add(content, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createMessageHeader(message: ChatMessage, index: Int): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = EmptyBorder(0, 0, 5, 0)
        
        val typeLabel = JBLabel("${getMessageIcon(message)} ${getMessageTypeName(message)}")
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 11f)
        panel.add(typeLabel, BorderLayout.WEST)
        
        val indexLabel = JBLabel("#$index")
        indexLabel.foreground = UIUtil.getContextHelpForeground()
        indexLabel.font = indexLabel.font.deriveFont(9f)
        panel.add(indexLabel, BorderLayout.EAST)
        
        return panel
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
            scrollPane.border = BorderFactory.createEmptyBorder()
            scrollPane.maximumSize = Dimension(Int.MAX_VALUE, 150)
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
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(100, 149, 237), 1),
            EmptyBorder(5, 8, 5, 8)
        )
        panel.background = Color(248, 250, 255)
        
        val header = JBLabel("🔧 Tool Call #${index + 1}: ${toolRequest.name()}")
        header.font = header.font.deriveFont(Font.BOLD, 10f)
        panel.add(header, BorderLayout.NORTH)
        
        // Tool arguments with syntax highlighting
        val argsText = toolRequest.arguments()
        val argsComponent = createCodeEditor(argsText, "json", "Arguments")
        argsComponent.maximumSize = Dimension(Int.MAX_VALUE, 100)
        panel.add(argsComponent, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createToolResultContent(message: ToolExecutionResultMessage): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(5, 0, 0, 0)
        panel.isOpaque = false
        
        val header = JBLabel("🔧 Tool Result: ${message.toolName()}")
        header.font = header.font.deriveFont(Font.BOLD, 10f)
        panel.add(header, BorderLayout.NORTH)
        
        val resultText = message.text()
        val language = detectLanguage(resultText)
        val resultComponent = createCodeEditor(resultText, language, "Result")
        panel.add(resultComponent, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createCodeEditor(content: String, language: String, title: String): JComponent {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(content)
        val editor = editorFactory.createViewer(document, project) as EditorEx
        
        // Configure editor
        editor.settings.apply {
            isLineNumbersShown = false
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
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
        if (title.isNotBlank()) {
            wrapper.border = BorderFactory.createTitledBorder(title)
        }
        wrapper.add(editor.component, BorderLayout.CENTER)
        wrapper.maximumSize = Dimension(Int.MAX_VALUE, 200)
        
        disposables.add { editorFactory.releaseEditor(editor) }
        
        return wrapper
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
                "Messages: $count"
            } catch (e: Exception) {
                "Messages: error"
            }
        } else {
            "No memory"
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
            is SystemMessage -> "System"
            is UserMessage -> "User"
            is AiMessage -> "AI Assistant"
            is ToolExecutionResultMessage -> "Tool Result"
            else -> "Message"
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
    
    private fun exportMemory() {
        val content = buildString {
            appendLine("LangChain4j Chat Memory Export")
            appendLine("Title: $title")
            appendLine("Timestamp: ${java.time.LocalDateTime.now()}")
            appendLine("=" .repeat(50))
            appendLine()
            
            getMessages().forEachIndexed { index, message ->
                appendLine("[$index] ${getMessageTypeName(message)}")
                appendLine("-" .repeat(30))
                
                when (message) {
                    is SystemMessage -> {
                        appendLine("System: ${message.text()}")
                    }
                    is UserMessage -> {
                        appendLine("User: ${message.singleText()}")
                    }
                    is AiMessage -> {
                        message.text()?.let { text ->
                            appendLine("AI: $text")
                        }
                        message.toolExecutionRequests().forEach { request ->
                            appendLine("Tool Call: ${request.name()}")
                            appendLine("Arguments: ${request.arguments()}")
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
            this,
            "Chat memory exported to clipboard",
            "Export Complete", 
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    /**
     * Dispose resources when component is no longer needed
     */
    fun dispose() {
        disposables.forEach { it() }
        disposables.clear()
    }
}