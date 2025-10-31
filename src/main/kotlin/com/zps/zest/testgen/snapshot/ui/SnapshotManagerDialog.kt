package com.zps.zest.testgen.snapshot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.zps.zest.testgen.snapshot.AgentSnapshotSerializer
import com.zps.zest.testgen.snapshot.SnapshotMetadata
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SnapshotManagerDialog(private val project: Project) : DialogWrapper(project) {

    private val snapshotTable: JBTable
    private val tableModel: DefaultTableModel
    private val detailsTextArea: JBTextArea
    private var snapshots: List<SnapshotMetadata> = emptyList()
    private var selectedSnapshot: SnapshotMetadata? = null

    init {
        title = "Agent Snapshot Manager"

        tableModel = DefaultTableModel(
            arrayOf("Agent Type", "Timestamp", "Description", "Messages"),
            0
        )
        snapshotTable = JBTable(tableModel)
        snapshotTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        snapshotTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateDetails()
            }
        }

        detailsTextArea = JBTextArea()
        detailsTextArea.isEditable = false
        detailsTextArea.lineWrap = true
        detailsTextArea.wrapStyleWord = true

        loadSnapshots()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))

        val tablePanel = JPanel(BorderLayout())
        tablePanel.border = BorderFactory.createTitledBorder("Available Snapshots")
        tablePanel.add(JBScrollPane(snapshotTable), BorderLayout.CENTER)

        val detailsPanel = JPanel(BorderLayout())
        detailsPanel.border = BorderFactory.createTitledBorder("Snapshot Details")
        detailsPanel.add(JBScrollPane(detailsTextArea), BorderLayout.CENTER)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, detailsPanel)
        splitPane.resizeWeight = 0.6
        splitPane.dividerLocation = 300

        panel.add(splitPane, BorderLayout.CENTER)
        panel.preferredSize = Dimension(800, 600)

        return panel
    }

    override fun createActions(): Array<Action> {
        val resumeAction = object : DialogWrapperAction("Resume") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resumeSelectedSnapshot(null)
            }
        }

        val resumeWithInstructionsAction = object : DialogWrapperAction("Resume with Instructions...") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resumeWithInstructions()
            }
        }

        val deleteAction = object : DialogWrapperAction("Delete") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                deleteSelectedSnapshot()
            }
        }

        val refreshAction = object : DialogWrapperAction("Refresh") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                loadSnapshots()
            }
        }

        val exportAction = object : DialogWrapperAction("Export JSON...") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                exportSelectedSnapshot()
            }
        }

        val experimentAction = object : DialogWrapperAction("Experiment with Prompts...") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                experimentWithPrompts()
            }
        }

        val deleteAllAction = object : DialogWrapperAction("Delete All...") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                deleteAllSnapshots()
            }
        }

        return arrayOf(resumeAction, resumeWithInstructionsAction, experimentAction, deleteAction, deleteAllAction, exportAction, refreshAction, cancelAction)
    }

    private fun loadSnapshots() {
        snapshots = AgentSnapshotSerializer.listSnapshots(project)
        updateTable()
    }

    private fun updateTable() {
        tableModel.rowCount = 0
        for (snapshot in snapshots) {
            tableModel.addRow(arrayOf(
                snapshot.agentType.name,
                java.time.Instant.ofEpochMilli(snapshot.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                snapshot.description,
                snapshot.messageCount.toString()
            ))
        }
    }

    private fun updateDetails() {
        val selectedRow = snapshotTable.selectedRow
        if (selectedRow >= 0 && selectedRow < snapshots.size) {
            selectedSnapshot = snapshots[selectedRow]
            val sb = StringBuilder()
            sb.append("Agent Type: ${selectedSnapshot!!.agentType}\n")
            sb.append("Session ID: ${selectedSnapshot!!.sessionId}\n")
            sb.append("Timestamp: ${java.time.Instant.ofEpochMilli(selectedSnapshot!!.timestamp)}\n")
            sb.append("Description: ${selectedSnapshot!!.description}\n")
            sb.append("Messages: ${selectedSnapshot!!.messageCount}\n")
            sb.append("\n--- Original Prompt ---\n")
            sb.append(selectedSnapshot!!.originalPrompt)
            sb.append("\n\n--- File Path ---\n")
            sb.append(selectedSnapshot!!.filePath)

            detailsTextArea.text = sb.toString()
        } else {
            selectedSnapshot = null
            detailsTextArea.text = "Select a snapshot to view details"
        }
    }

    private fun resumeSelectedSnapshot(nudgeInstructions: String?) {
        val snapshot = selectedSnapshot ?: run {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select a snapshot to resume",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Close this dialog first
        close(OK_EXIT_CODE)

        // Create a virtual file for the resumed session
        val virtualFile = com.zps.zest.testgen.ui.TestGenerationVirtualFile(
            "Resumed: ${snapshot.description}",
            null  // No request object for resumed sessions
        )

        // Register the virtual file
        com.zps.zest.testgen.ui.TestGenerationFileSystem.INSTANCE.registerFile(virtualFile)

        // Open the editor - this will create the UI
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)

        // Get the editor that was just created
        val editors = fileEditorManager.getEditors(virtualFile)
        val editor = editors.firstOrNull() as? com.zps.zest.testgen.ui.StateMachineTestGenerationEditor

        if (editor == null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "Failed to create editor for resumed session",
                "Resume Failed"
            )
            return
        }

        // Get test generation service
        val testGenService = project.getService(com.zps.zest.testgen.StateMachineTestGenerationService::class.java)

        // Resume from checkpoint with the editor's event listener
        testGenService.resumeFromCheckpoint(
            snapshot.filePath,
            nudgeInstructions,
            editor.getEventListener(),  // Use editor's event listener
            editor.getStreamingCallback()  // Use editor's streaming callback
        ).thenAccept { stateMachine ->
            // Notify editor about the resumed session
            SwingUtilities.invokeLater {
                editor.onSessionResumed(stateMachine)

                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "Resumed from checkpoint: ${snapshot.description}\n\nSession ID: ${stateMachine.sessionId}",
                    "Checkpoint Resumed"
                )
            }
        }.exceptionally { throwable ->
            // Notify user of failure and close the editor
            SwingUtilities.invokeLater {
                fileEditorManager.closeFile(virtualFile)
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to resume from checkpoint:\n${throwable.message}",
                    "Resume Failed"
                )
            }
            null
        }
    }

    private fun resumeWithInstructions() {
        val snapshot = selectedSnapshot ?: run {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select a snapshot to resume",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Show dialog to get nudge instructions
        val instructions = JOptionPane.showInputDialog(
            contentPanel,
            "Enter instructions for the agent (or leave empty for default 'Continue'):\n\n" +
                    "Checkpoint: ${snapshot.description}\n" +
                    "Agent: ${snapshot.agentType}\n" +
                    "Messages: ${snapshot.messageCount}",
            "Resume Instructions",
            JOptionPane.QUESTION_MESSAGE,
            null,
            null,
            "Continue with the task."
        ) as? String

        if (instructions != null) {
            resumeSelectedSnapshot(instructions)
        }
    }

    private fun deleteSelectedSnapshot() {
        val snapshot = selectedSnapshot ?: return

        val choice = JOptionPane.showConfirmDialog(
            contentPanel,
            "Are you sure you want to delete this snapshot?\n${snapshot.description}",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION
        )

        if (choice == JOptionPane.YES_OPTION) {
            if (AgentSnapshotSerializer.deleteSnapshot(snapshot.filePath)) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Snapshot deleted successfully",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
                loadSnapshots()
            } else {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to delete snapshot",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun deleteAllSnapshots() {
        if (snapshots.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "No snapshots to delete",
                "Info",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val count = snapshots.size
        val choice = JOptionPane.showConfirmDialog(
            contentPanel,
            "Are you sure you want to delete ALL $count snapshots?\n\nThis action cannot be undone!",
            "Confirm Delete All",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (choice == JOptionPane.YES_OPTION) {
            var successCount = 0
            var failCount = 0

            for (snapshot in snapshots) {
                if (AgentSnapshotSerializer.deleteSnapshot(snapshot.filePath)) {
                    successCount++
                } else {
                    failCount++
                }
            }

            val message = if (failCount == 0) {
                "Successfully deleted all $successCount snapshots"
            } else {
                "Deleted $successCount snapshots\nFailed to delete $failCount snapshots"
            }

            JOptionPane.showMessageDialog(
                contentPanel,
                message,
                if (failCount == 0) "Success" else "Partial Success",
                if (failCount == 0) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
            )

            loadSnapshots()
        }
    }

    private fun experimentWithPrompts() {
        val snapshot = selectedSnapshot ?: run {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select a snapshot to experiment with",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Close this dialog and open experiment dialog
        val experimentDialog = PromptExperimentDialog(project, snapshot)
        experimentDialog.show()
    }

    private fun exportSelectedSnapshot() {
        val snapshot = selectedSnapshot ?: return

        val fileChooser = JFileChooser()
        fileChooser.selectedFile = File(snapshot.fileName)
        fileChooser.dialogTitle = "Export Snapshot"

        if (fileChooser.showSaveDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                val sourceFile = File(snapshot.filePath)
                val targetFile = fileChooser.selectedFile
                sourceFile.copyTo(targetFile, overwrite = true)

                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Snapshot exported to:\n${targetFile.absolutePath}",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to export snapshot:\n${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
}
