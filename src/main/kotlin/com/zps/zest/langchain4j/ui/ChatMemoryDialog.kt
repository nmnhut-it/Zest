package com.zps.zest.langchain4j.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.langchain4j.data.message.*
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Efficient tree-based dialog for viewing LangChain4j chat memory.
 * Optimized for large message sets with compact preview and detailed view on demand.
 */
class ChatMemoryDialog(
    private val project: Project,
    private val chatMemory: MessageWindowChatMemory?,
    private val agentName: String = "Agent"
) : DialogWrapper(project) {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("$agentName Conversation"))
    private val messageTree = JTree(treeModel)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss")

    companion object {
        /**
         * Create a reusable chat memory panel component that can be embedded anywhere.
         * This is the same component used in the dialog but without dialog-specific controls.
         */
        @JvmStatic
        fun createChatMemoryPanel(
            project: Project,
            chatMemory: MessageWindowChatMemory?,
            agentName: String = "Agent"
        ): JComponent {
            return ChatMemoryPanel(project, chatMemory, agentName)
        }
    }

    init {
        title = "$agentName Chat Memory"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(900, 600)
        
        // Header with stats
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Tree setup
        setupMessageTree()
        val scrollPane = JBScrollPane(messageTree)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Footer with controls
        val footerPanel = createFooterPanel()
        mainPanel.add(footerPanel, BorderLayout.SOUTH)
        
        loadMessages()
        return mainPanel
    }
    
    private fun setupMessageTree() {
        messageTree.setRootVisible(true)
        messageTree.setShowsRootHandles(true)
        messageTree.cellRenderer = MessageTreeCellRenderer()
        
        // Double-click to view details
        messageTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = messageTree.getPathForLocation(e.x, e.y)
                    if (path != null) {
                        val node = path.lastPathComponent as DefaultMutableTreeNode
                        val nodeData = node.userObject
                        if (nodeData is MessageNodeData) {
                            showMessageDetails(nodeData.message)
                        }
                    }
                }
            }
        })
        
        // Right-click context menu
        messageTree.componentPopupMenu = createContextMenu()
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        val titleLabel = JBLabel("💬 $agentName Conversation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        val statsLabel = JBLabel(getMemoryStats())
        statsLabel.foreground = UIUtil.getInactiveTextColor()
        panel.add(statsLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createFooterPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        panel.border = EmptyBorder(8, 10, 10, 10)
        
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadMessages() }
        panel.add(refreshButton)
        
        val exportButton = JButton("Export")
        exportButton.addActionListener { exportMemory() }
        panel.add(exportButton)
        
        return panel
    }
    
    private fun loadMessages() {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        
        val messages = getMessages()
        
        if (messages.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No messages yet - start a conversation to see chat history")
            root.add(emptyNode)
            root.userObject = "$agentName Conversation (Empty)"
        } else {
            root.userObject = "$agentName Conversation (${messages.size} messages)"
            
            messages.forEachIndexed { index, message ->
                val nodeData = MessageNodeData(message, index)
                val node = DefaultMutableTreeNode(nodeData)
                root.add(node)
            }
        }
        
        treeModel.reload()
        messageTree.expandRow(0) // Expand root
    }
    
    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()
        
        val viewDetailsItem = JMenuItem("View Details")
        viewDetailsItem.addActionListener {
            val selectedNode = messageTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val nodeData = selectedNode?.userObject as? MessageNodeData
            nodeData?.let { showMessageDetails(it.message) }
        }
        menu.add(viewDetailsItem)
        
        menu.addSeparator()
        
        val copyItem = JMenuItem("Copy Message")
        copyItem.addActionListener {
            val selectedNode = messageTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val nodeData = selectedNode?.userObject as? MessageNodeData
            nodeData?.let { copyMessage(it.message) }
        }
        menu.add(copyItem)
        
        return menu
    }
    
    private fun showMessageDetails(message: ChatMessage) {
        val dialog = MessageDetailDialog(project, message, agentName)
        dialog.show()
    }
    
    private fun copyMessage(message: ChatMessage) {
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
            "No chat memory"
        }
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
    
    // Tree node data class
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
    
    // Tree cell renderer with color coding
    private inner class MessageTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            
            if (!sel) { // Only color when not selected
                val node = value as DefaultMutableTreeNode
                val userData = node.userObject
                
                if (userData is MessageNodeData) {
                    foreground = when (userData.message) {
                        is SystemMessage -> if (UIUtil.isUnderDarcula()) Color(255, 200, 100) else Color(180, 120, 0)
                        is UserMessage -> if (UIUtil.isUnderDarcula()) Color(120, 180, 255) else Color(70, 120, 200)
                        is AiMessage -> if (UIUtil.isUnderDarcula()) Color(120, 255, 180) else Color(0, 150, 50)
                        is ToolExecutionResultMessage -> if (UIUtil.isUnderDarcula()) Color(200, 200, 200) else Color(100, 100, 100)
                        else -> UIUtil.getTreeForeground()
                    }
                }
            }
            
            return this
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
}

/**
 * Detailed view dialog for individual messages - clean and focused
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

/**
 * Tree node data for efficient message display
 */
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
            is ToolExecutionResultMessage -> message.toolName()
            else -> "Message"
        }
        
        val preview = when (message) {
            is SystemMessage -> message.text().take(100)
            is UserMessage -> message.singleText().take(100)
            is AiMessage -> (message.text() ?: "Tool calls: ${message.toolExecutionRequests().size}").take(100)
            is ToolExecutionResultMessage -> message.text().take(100)
            else -> "Unknown"
        }.replace('\n', ' ').replace('\t', ' ').trim()
        
        val cleanPreview = if (preview.length >= 100) "${preview.take(97)}..." else preview
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        
        return "$icon $type: $cleanPreview [$time]"
    }
}

/**
 * Tree cell renderer with message type color coding
 */
private class MessageTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        if (!sel) { // Only apply colors when not selected
            val node = value as DefaultMutableTreeNode
            val userData = node.userObject

            if (userData is MessageNodeData) {
                foreground = when (userData.message) {
                    is SystemMessage -> if (UIUtil.isUnderDarcula()) Color(255, 200, 100) else Color(180, 120, 0)
                    is UserMessage -> if (UIUtil.isUnderDarcula()) Color(120, 180, 255) else Color(70, 120, 200)
                    is AiMessage -> if (UIUtil.isUnderDarcula()) Color(120, 255, 180) else Color(0, 150, 50)
                    is ToolExecutionResultMessage -> if (UIUtil.isUnderDarcula()) Color(200, 200, 200) else Color(120, 120, 120)
                    else -> UIUtil.getTreeForeground()
                }
            }
        }

        return this
    }
}

/**
 * Reusable chat memory panel that can be embedded in other UI components.
 * Contains the tree-based message display without dialog-specific controls.
 */
class ChatMemoryPanel(
    private val project: Project,
    private var chatMemory: MessageWindowChatMemory?,
    private val agentName: String = "Agent"
) : JPanel(BorderLayout()) {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("$agentName Conversation"))
    private val messageTree = JTree(treeModel)
    private val statsLabel = JBLabel()

    init {
        setupUI()
        loadMessages()
    }

    private fun setupUI() {
        // Header with stats
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)

        val titleLabel = JBLabel("💬 $agentName Conversation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.WEST)

        statsLabel.foreground = UIUtil.getInactiveTextColor()
        updateStats()
        headerPanel.add(statsLabel, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)

        // Tree setup
        messageTree.setRootVisible(true)
        messageTree.setShowsRootHandles(true)
        messageTree.cellRenderer = MessageTreeCellRenderer()

        // Double-click to view details
        messageTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = messageTree.getPathForLocation(e.x, e.y)
                    if (path != null) {
                        val node = path.lastPathComponent as DefaultMutableTreeNode
                        val nodeData = node.userObject
                        if (nodeData is MessageNodeData) {
                            showMessageDetails(nodeData.message)
                        }
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) handlePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) handlePopup(e)
            }

            private fun handlePopup(e: MouseEvent) {
                val path = messageTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val nodeData = node.userObject as? MessageNodeData ?: return

                messageTree.selectionPath = path

                val popupMenu = JPopupMenu()

                val viewDetailsItem = JMenuItem("View Details")
                viewDetailsItem.addActionListener {
                    showMessageDetails(nodeData.message)
                }
                popupMenu.add(viewDetailsItem)

                popupMenu.addSeparator()

                val copyItem = JMenuItem("Copy Message")
                copyItem.addActionListener {
                    copyMessage(nodeData.message)
                }
                popupMenu.add(copyItem)

                popupMenu.show(messageTree, e.x, e.y)
            }
        })

        val scrollPane = JBScrollPane(messageTree)
        add(scrollPane, BorderLayout.CENTER)

        // Footer with controls
        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        footerPanel.border = EmptyBorder(5, 10, 5, 10)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refresh() }
        footerPanel.add(refreshButton)

        val exportButton = JButton("Export")
        exportButton.addActionListener { exportMemory() }
        footerPanel.add(exportButton)

        add(footerPanel, BorderLayout.SOUTH)
    }

    fun setChatMemory(memory: MessageWindowChatMemory?) {
        this.chatMemory = memory
        refresh()
    }

    fun refresh() {
        loadMessages()
        updateStats()
    }

    private fun loadMessages() {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()

        val messages = getMessages()

        if (messages.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No messages yet - start a conversation to see chat history")
            root.add(emptyNode)
            root.userObject = "$agentName Conversation (Empty)"
        } else {
            root.userObject = "$agentName Conversation (${messages.size} messages)"

            messages.forEachIndexed { index, message ->
                val nodeData = MessageNodeData(message, index)
                val node = DefaultMutableTreeNode(nodeData)
                root.add(node)
            }
        }

        treeModel.reload()
        messageTree.expandRow(0) // Expand root
    }

    private fun updateStats() {
        statsLabel.text = if (chatMemory != null) {
            try {
                val count = chatMemory!!.messages().size
                "Messages: $count"
            } catch (e: Exception) {
                "Messages: error"
            }
        } else {
            "No chat memory"
        }
    }

    private fun getMessages(): List<ChatMessage> {
        return try {
            chatMemory?.messages() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showMessageDetails(message: ChatMessage) {
        val dialog = MessageDetailDialog(project, message, agentName)
        dialog.show()
    }

    private fun copyMessage(message: ChatMessage) {
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
            this,
            "Chat memory exported to clipboard",
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
}