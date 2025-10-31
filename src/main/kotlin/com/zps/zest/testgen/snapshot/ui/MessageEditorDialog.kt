package com.zps.zest.testgen.snapshot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.zps.zest.testgen.snapshot.MessageType
import com.zps.zest.testgen.snapshot.SerializableChatMessage
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog for editing a single chat message.
 * Allows changing message type and content.
 */
class MessageEditorDialog(
    private val project: Project,
    private val originalMessage: SerializableChatMessage?
) : DialogWrapper(project) {

    private val typeComboBox: JComboBox<MessageType>
    private val contentTextArea: JBTextArea
    private var editedMessage: SerializableChatMessage? = null

    init {
        title = if (originalMessage == null) "Add Message" else "Edit Message"

        // Message type selector
        typeComboBox = JComboBox(MessageType.values())
        typeComboBox.selectedItem = originalMessage?.type ?: MessageType.USER

        // Content editor
        contentTextArea = JBTextArea()
        contentTextArea.lineWrap = true
        contentTextArea.wrapStyleWord = true
        contentTextArea.rows = 15

        // Load original content if editing
        if (originalMessage != null) {
            try {
                val decompressed = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer
                    .decompressString(originalMessage.contentCompressed)
                contentTextArea.text = decompressed
            } catch (e: Exception) {
                contentTextArea.text = "[Error decompressing content]"
            }
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(5, 5))

        // Type selector panel
        val typePanel = JPanel(BorderLayout())
        typePanel.add(JLabel("Message Type: "), BorderLayout.WEST)
        typePanel.add(typeComboBox, BorderLayout.CENTER)

        // Content panel
        val contentPanel = JPanel(BorderLayout())
        contentPanel.border = BorderFactory.createTitledBorder("Content")
        contentPanel.add(JBScrollPane(contentTextArea), BorderLayout.CENTER)

        // Help text
        val helpLabel = JLabel(
            "<html><i>Tip: SYSTEM messages are prompts, USER messages are requests, " +
                    "AI messages are agent responses</i></html>"
        )

        panel.add(typePanel, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)
        panel.add(helpLabel, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(600, 400)

        return panel
    }

    override fun doOKAction() {
        val content = contentTextArea.text.trim()
        if (content.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Message content cannot be empty",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        val messageType = typeComboBox.selectedItem as MessageType

        // Compress content
        val compressed = try {
            com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.compressString(content)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Failed to compress content: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        // Create new message (preserve tool calls/results if editing AI/TOOL messages)
        editedMessage = SerializableChatMessage(
            type = messageType,
            contentCompressed = compressed,
            toolCalls = if (messageType == MessageType.AI) originalMessage?.toolCalls else null,
            toolResults = if (messageType == MessageType.TOOL_RESULT) originalMessage?.toolResults else null
        )

        super.doOKAction()
    }

    fun getMessage(): SerializableChatMessage? = editedMessage
}
