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
        val loadAction = object : DialogWrapperAction("Load Snapshot") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                loadSelectedSnapshot()
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

        return arrayOf(loadAction, deleteAction, exportAction, refreshAction, cancelAction)
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

    private fun loadSelectedSnapshot() {
        val snapshot = selectedSnapshot ?: return

        val choice = JOptionPane.showOptionDialog(
            contentPanel,
            "This will load the snapshot into memory.\nNote: You'll need to integrate this with your agent workflow.",
            "Load Snapshot",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            arrayOf("Load", "Cancel"),
            "Load"
        )

        if (choice == 0) {
            val agentSnapshot = AgentSnapshotSerializer.loadFromFile(snapshot.filePath)
            if (agentSnapshot != null) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Snapshot loaded successfully!\n\nAgent Type: ${agentSnapshot.agentType}\nMessages: ${agentSnapshot.chatMessages.size}",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
                close(OK_EXIT_CODE)
            } else {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to load snapshot",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
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
