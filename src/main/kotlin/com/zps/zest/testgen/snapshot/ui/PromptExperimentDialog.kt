package com.zps.zest.testgen.snapshot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.zps.zest.testgen.snapshot.AgentSnapshot
import com.zps.zest.testgen.snapshot.SerializableChatMessage
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Dialog for experimenting with agent prompts.
 * Allows editing messages and system prompts, then re-running the agent to see different responses.
 */
class PromptExperimentDialog(
    private val project: Project,
    private val originalSnapshot: AgentSnapshot
) : DialogWrapper(project) {

    private val messageTableModel: DefaultTableModel
    private val messageTable: JBTable
    private val outputTextArea: JBTextArea
    private val messages: MutableList<SerializableChatMessage>
    private var isRunning = false

    init {
        title = "Prompt Experiment - ${originalSnapshot.description}"

        // Copy messages from snapshot (mutable copy for editing)
        messages = originalSnapshot.chatMessages.toMutableList()

        // Create table model
        messageTableModel = DefaultTableModel(
            arrayOf("Type", "Preview", "Actions"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        messageTable = JBTable(messageTableModel)
        messageTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        outputTextArea = JBTextArea()
        outputTextArea.isEditable = false
        outputTextArea.lineWrap = true
        outputTextArea.wrapStyleWord = true

        loadMessages()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))

        // Top: Snapshot info
        val infoPanel = JPanel(BorderLayout())
        infoPanel.border = BorderFactory.createTitledBorder("Snapshot Info")
        val infoLabel = JLabel(
            "<html><b>Agent:</b> ${originalSnapshot.agentType} | " +
                    "<b>Session:</b> ${originalSnapshot.sessionId.take(8)} | " +
                    "<b>Messages:</b> ${messages.size}</html>"
        )
        infoPanel.add(infoLabel, BorderLayout.CENTER)

        // Middle: Message editor table
        val tablePanel = JPanel(BorderLayout())
        tablePanel.border = BorderFactory.createTitledBorder("Messages (Click to Edit)")
        tablePanel.add(JBScrollPane(messageTable), BorderLayout.CENTER)

        // Add action buttons below table
        val buttonPanel = JPanel()
        val addButton = JButton("Add Message")
        val editButton = JButton("Edit Selected")
        val deleteButton = JButton("Delete Selected")

        addButton.addActionListener { addMessage() }
        editButton.addActionListener { editSelectedMessage() }
        deleteButton.addActionListener { deleteSelectedMessage() }

        buttonPanel.add(addButton)
        buttonPanel.add(editButton)
        buttonPanel.add(deleteButton)
        tablePanel.add(buttonPanel, BorderLayout.SOUTH)

        // Bottom: Output area
        val outputPanel = JPanel(BorderLayout())
        outputPanel.border = BorderFactory.createTitledBorder("Experiment Output")
        outputPanel.add(JBScrollPane(outputTextArea), BorderLayout.CENTER)

        // Split pane
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, outputPanel)
        splitPane.resizeWeight = 0.6
        splitPane.dividerLocation = 300

        panel.add(infoPanel, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.preferredSize = Dimension(900, 700)

        return panel
    }

    override fun createActions(): Array<Action> {
        val runAction = object : DialogWrapperAction("Run Experiment") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                runExperiment()
            }
        }

        val resetAction = object : DialogWrapperAction("Reset") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resetToOriginal()
            }
        }

        return arrayOf(runAction, resetAction, cancelAction)
    }

    private fun loadMessages() {
        messageTableModel.rowCount = 0
        for ((index, message) in messages.withIndex()) {
            val typeIcon = when (message.type) {
                com.zps.zest.testgen.snapshot.MessageType.SYSTEM -> "🔧"
                com.zps.zest.testgen.snapshot.MessageType.USER -> "👤"
                com.zps.zest.testgen.snapshot.MessageType.AI -> "🤖"
                com.zps.zest.testgen.snapshot.MessageType.TOOL_RESULT -> "🔨"
            }

            // Decompress content for preview
            val content = try {
                com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.decompressString(message.contentCompressed)
            } catch (e: Exception) {
                "[Error decompressing]"
            }

            val preview = if (content.length > 60) {
                content.take(60) + "..."
            } else {
                content
            }

            messageTableModel.addRow(arrayOf(
                "$typeIcon ${message.type}",
                preview,
                "Row $index"
            ))
        }
    }

    private fun addMessage() {
        val dialog = MessageEditorDialog(project, null)
        if (dialog.showAndGet()) {
            val newMessage = dialog.getMessage()
            if (newMessage != null) {
                messages.add(newMessage)
                loadMessages()
            }
        }
    }

    private fun editSelectedMessage() {
        val selectedRow = messageTable.selectedRow
        if (selectedRow < 0 || selectedRow >= messages.size) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select a message to edit",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val message = messages[selectedRow]
        val dialog = MessageEditorDialog(project, message)
        if (dialog.showAndGet()) {
            val editedMessage = dialog.getMessage()
            if (editedMessage != null) {
                messages[selectedRow] = editedMessage
                loadMessages()
            }
        }
    }

    private fun deleteSelectedMessage() {
        val selectedRow = messageTable.selectedRow
        if (selectedRow < 0 || selectedRow >= messages.size) {
            return
        }

        val choice = JOptionPane.showConfirmDialog(
            contentPanel,
            "Delete this message?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION
        )

        if (choice == JOptionPane.YES_OPTION) {
            messages.removeAt(selectedRow)
            loadMessages()
        }
    }

    private fun resetToOriginal() {
        messages.clear()
        messages.addAll(originalSnapshot.chatMessages)
        loadMessages()
        outputTextArea.text = ""
    }

    private fun runExperiment() {
        if (isRunning) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Experiment is already running",
                "Already Running",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        if (messages.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Cannot run experiment with no messages",
                "No Messages",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        outputTextArea.text = "Starting experiment...\n\n"
        isRunning = true

        // Run experiment in background
        SwingUtilities.invokeLater {
            try {
                val runner = com.zps.zest.testgen.snapshot.ExperimentRunner(project)
                runner.runExperiment(
                    originalSnapshot,
                    messages,
                    { text -> appendOutput(text) }
                )
            } catch (e: Exception) {
                appendOutput("\n\n❌ Error: ${e.message}\n")
                e.printStackTrace()
            } finally {
                isRunning = false
            }
        }
    }

    private fun appendOutput(text: String) {
        SwingUtilities.invokeLater {
            outputTextArea.append(text)
            outputTextArea.caretPosition = outputTextArea.document.length
        }
    }
}
