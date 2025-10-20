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
import javax.swing.Timer
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
        val dialog = MessageDetailDialog(project, message, agentName, chatMemory)
        DialogManager.showDialog(dialog)
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
                        is SystemMessage -> if (com.zps.zest.util.ThemeUtils.isDarkTheme()) Color(255, 200, 100) else Color(180, 120, 0)
                        is UserMessage -> if (com.zps.zest.util.ThemeUtils.isDarkTheme()) Color(120, 180, 255) else Color(70, 120, 200)
                        is AiMessage -> if (com.zps.zest.util.ThemeUtils.isDarkTheme()) Color(120, 255, 180) else Color(0, 150, 50)
                        is ToolExecutionResultMessage -> if (com.zps.zest.util.ThemeUtils.isDarkTheme()) Color(200, 200, 200) else Color(100, 100, 100)
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
 * Detailed view dialog for individual messages - uses JCEF for markdown rendering
 */
public class MessageDetailDialog(
    private val project: Project,
    private val message: ChatMessage,
    agentName: String,
    private val chatMemory: MessageWindowChatMemory? = null
) : DialogWrapper(project) {

    init {
        title = "Message Details - $agentName"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // Create temporary chat memory for rendering this single message
        val tempMemory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(100)

        // Add the message to temporary memory based on type
        when (message) {
            is SystemMessage -> {
                tempMemory.add(message)
            }
            is UserMessage -> {
                tempMemory.add(message)
            }
            is AiMessage -> {
                tempMemory.add(message)

                // If this AI message has tool calls, look for their results in the original chat memory
                if (message.toolExecutionRequests().isNotEmpty() && chatMemory != null) {
                    try {
                        val allMessages = chatMemory.messages()
                        val messageIndex = allMessages.indexOf(message)

                        if (messageIndex >= 0) {
                            // Add any tool results that follow this AI message
                            message.toolExecutionRequests().forEach { toolReq ->
                                val toolId = toolReq.id()

                                // Find the corresponding tool result
                                for (i in messageIndex + 1 until allMessages.size) {
                                    val nextMsg = allMessages[i]
                                    if (nextMsg is ToolExecutionResultMessage && nextMsg.id() == toolId) {
                                        tempMemory.add(nextMsg)
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors during lookup
                    }
                }
            }
            is ToolExecutionResultMessage -> {
                // For tool result messages, try to find the corresponding AI message with the tool call
                var foundToolCall = false

                if (chatMemory != null) {
                    try {
                        val allMessages = chatMemory.messages()
                        val messageIndex = allMessages.indexOf(message)
                        val toolId = message.id()

                        if (messageIndex >= 0 && toolId != null) {
                            // Search backwards for the AI message with this tool call
                            for (i in messageIndex - 1 downTo 0) {
                                val prevMsg = allMessages[i]
                                if (prevMsg is AiMessage) {
                                    val matchingTool = prevMsg.toolExecutionRequests().find { it.id() == toolId }
                                    if (matchingTool != null) {
                                        tempMemory.add(prevMsg)
                                        foundToolCall = true
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors during lookup
                    }
                }

                // Add the tool result message
                tempMemory.add(message)
            }
            else -> {
                // For unknown message types, try to add as-is
                if (message is dev.langchain4j.data.message.ChatMessage) {
                    tempMemory.add(message)
                }
            }
        }

        // Create JCEFChatPanel with the populated temporary memory
        val chatPanel = com.zps.zest.chatui.JCEFChatPanel(
            project,
            tempMemory
        )
        chatPanel.preferredSize = JBUI.size(800, 600)

        return chatPanel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}

/**
 * Tree node data for efficient message display
 */
private data class MessageNodeData(
    val message: ChatMessage,
    val index: Int,
    val timestamp: String = SimpleDateFormat("HH:mm:ss").format(Date())
) {
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

        return "$icon $type: $cleanPreview [$timestamp]"
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
    private var scrollPane: JBScrollPane? = null
    private var autoScroll = true
    private var autoScrollCheckBox: JCheckBox? = null
    private val scrollDebounceTimer = Timer(300) { performScrollToBottom() }
    // Track displayed messages to enable incremental updates
    private val displayedMessages = mutableListOf<ChatMessage>()
    private val messageNodeMap = mutableMapOf<ChatMessage, DefaultMutableTreeNode>()

    init {
        scrollDebounceTimer.isRepeats = false
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

        scrollPane = JBScrollPane(messageTree)
        add(scrollPane, BorderLayout.CENTER)

        // Footer with controls
        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        footerPanel.border = EmptyBorder(5, 10, 5, 10)

        // Auto-scroll checkbox
        autoScrollCheckBox = JCheckBox("Auto-scroll", autoScroll)
        autoScrollCheckBox!!.toolTipText = "Automatically scroll to the latest message"
        autoScrollCheckBox!!.addActionListener {
            autoScroll = autoScrollCheckBox!!.isSelected
            if (autoScroll) {
                scheduleScrollToBottom()
            }
        }
        footerPanel.add(autoScrollCheckBox)

        // Add separator
        footerPanel.add(JSeparator(JSeparator.VERTICAL).apply {
            preferredSize = Dimension(1, 20)
        })

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

    /**
     * Call this method periodically to check for new messages and update the display.
     * Returns true if new messages were found.
     */
    fun checkForUpdates(): Boolean {
        val currentMessageCount = getMessages().size
        val root = treeModel.root as DefaultMutableTreeNode
        val displayedMessageCount = if (root.childCount == 1 && root.getChildAt(0).toString().contains("No messages")) {
            0
        } else {
            root.childCount
        }

        if (currentMessageCount != displayedMessageCount) {
            refresh()
            return true
        }
        return false
    }

    private fun loadMessages() {
        val root = treeModel.root as DefaultMutableTreeNode
        val messages = getMessages()
        val hadMessages = displayedMessages.isNotEmpty()

        // Check if we need a full reload (messages removed or reordered)
        val needsFullReload = displayedMessages.size > messages.size ||
            (displayedMessages.isNotEmpty() && messages.isNotEmpty() && displayedMessages.first() != messages.first())

        if (needsFullReload) {
            // Full reload needed - messages were removed or reordered
            root.removeAllChildren()
            displayedMessages.clear()
            messageNodeMap.clear()

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
                    displayedMessages.add(message)
                    messageNodeMap[message] = node
                }
            }
            treeModel.reload()
        } else if (messages.size > displayedMessages.size) {
            // Incremental update - just add new messages
            if (displayedMessages.isEmpty() && messages.isNotEmpty()) {
                // First messages arriving - clear placeholder
                root.removeAllChildren()
            }

            root.userObject = "$agentName Conversation (${messages.size} messages)"

            // Add only the new messages
            for (i in displayedMessages.size until messages.size) {
                val message = messages[i]
                val nodeData = MessageNodeData(message, i)
                val node = DefaultMutableTreeNode(nodeData)
                root.add(node)
                displayedMessages.add(message)
                messageNodeMap[message] = node

                // Notify tree model about the new node
                treeModel.nodesWereInserted(root, intArrayOf(root.childCount - 1))
            }
        }

        // Expand all nodes to show messages
        SwingUtilities.invokeLater {
            for (i in 0 until messageTree.rowCount) {
                messageTree.expandRow(i)
            }

            // Auto-scroll if new messages were added
            if (autoScroll && messages.size > displayedMessages.size - (messages.size - displayedMessages.size)) {
                scheduleScrollToBottom()
            }
        }
    }

    private fun scheduleScrollToBottom() {
        // Cancel any pending scroll and schedule a new one (debounce)
        scrollDebounceTimer.restart()
    }

    private fun performScrollToBottom() {
        SwingUtilities.invokeLater {
            scrollPane?.let { sp ->
                val vertical = sp.verticalScrollBar
                vertical.value = vertical.maximum

                // Also ensure the last tree node is visible
                val rowCount = messageTree.rowCount
                if (rowCount > 0) {
                    messageTree.scrollRowToVisible(rowCount - 1)
                }
            }
        }
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
        val dialog = MessageDetailDialog(project, message, agentName, chatMemory)
        DialogManager.showDialog(dialog)
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