package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.persistence.SessionRepository
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Dialog for selecting and resuming a saved test generation session.
 */
class SessionResumeDialog(
    private val project: Project
) : DialogWrapper(project) {
    
    private val repository = SessionRepository.getInstance(project)
    private var selectedSession: SessionRepository.SessionMetadata? = null
    private lateinit var sessionTable: JBTable
    private lateinit var detailsPanel: JPanel
    
    init {
        title = "Resume Test Generation Session"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(900, 600)
        
        // Header
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Split pane - sessions list on left, details on right
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = 600
        
        // Left: Sessions table
        splitPane.leftComponent = createSessionsPanel()
        
        // Right: Details panel
        detailsPanel = createDetailsPanel()
        splitPane.rightComponent = JBScrollPane(detailsPanel)
        
        mainPanel.add(splitPane, BorderLayout.CENTER)
        
        // Bottom: Actions
        val actionsPanel = createActionsPanel()
        mainPanel.add(actionsPanel, BorderLayout.SOUTH)
        
        // Load sessions
        loadSessions()
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("Select a saved session to resume")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadSessions() }
        panel.add(refreshButton, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createSessionsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create table model
        val columnNames = arrayOf(
            "Session ID",
            "Target Class",
            "Status",
            "Progress",
            "Created",
            "Modified"
        )
        
        val tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
            
            override fun getColumnClass(columnIndex: Int): Class<*> {
                return when (columnIndex) {
                    3 -> Int::class.java // Progress
                    else -> String::class.java
                }
            }
        }
        
        sessionTable = JBTable(tableModel)
        sessionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        sessionTable.rowHeight = 25
        
        // Custom renderer for progress column
        sessionTable.columnModel.getColumn(3).cellRenderer = ProgressCellRenderer()
        
        // Date formatter
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        sessionTable.columnModel.getColumn(4).cellRenderer = DateCellRenderer(dateFormatter)
        sessionTable.columnModel.getColumn(5).cellRenderer = DateCellRenderer(dateFormatter)
        
        // Column widths
        sessionTable.columnModel.getColumn(0).preferredWidth = 100 // Session ID
        sessionTable.columnModel.getColumn(1).preferredWidth = 150 // Target Class
        sessionTable.columnModel.getColumn(2).preferredWidth = 100 // Status
        sessionTable.columnModel.getColumn(3).preferredWidth = 80  // Progress
        sessionTable.columnModel.getColumn(4).preferredWidth = 120 // Created
        sessionTable.columnModel.getColumn(5).preferredWidth = 120 // Modified
        
        // Selection listener
        sessionTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = sessionTable.selectedRow
                if (selectedRow >= 0) {
                    val sessions = repository.listSessions()
                    if (selectedRow < sessions.size) {
                        selectedSession = sessions[selectedRow]
                        updateDetailsPanel()
                    }
                }
            }
        }
        
        // Double-click to load
        sessionTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && sessionTable.selectedRow >= 0) {
                    doOKAction()
                }
            }
        })
        
        val scrollPane = JBScrollPane(sessionTable)
        scrollPane.border = BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createDetailsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("Session Details")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(10))
        
        val placeholderLabel = JBLabel("Select a session to view details")
        placeholderLabel.foreground = UIUtil.getContextHelpForeground()
        placeholderLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(placeholderLabel)
        
        return panel
    }
    
    private fun createActionsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // Left: Delete button
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val deleteButton = JButton("Delete Selected")
        deleteButton.addActionListener { deleteSelectedSession() }
        leftPanel.add(deleteButton)
        
        val cleanupButton = JButton("Clean Old Auto-saves")
        cleanupButton.addActionListener { cleanupOldSessions() }
        leftPanel.add(cleanupButton)
        
        panel.add(leftPanel, BorderLayout.WEST)
        
        // Right: Info label
        val infoLabel = JBLabel("Double-click a session to resume")
        infoLabel.foreground = UIUtil.getContextHelpForeground()
        panel.add(infoLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun loadSessions() {
        val tableModel = sessionTable.model as DefaultTableModel
        tableModel.setRowCount(0)
        
        val sessions = repository.listSessions()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        
        sessions.forEach { session ->
            tableModel.addRow(arrayOf(
                session.sessionId.take(8) + "...",
                session.targetClass ?: "Unknown",
                formatStatus(session.status),
                session.progressPercent,
                session.createdAt,
                session.lastModified
            ))
        }
        
        if (sessions.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPane,
                "No saved sessions found",
                "Information",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
    
    private fun updateDetailsPanel() {
        detailsPanel.removeAll()
        
        val session = selectedSession ?: run {
            detailsPanel.revalidate()
            detailsPanel.repaint()
            return
        }
        
        val titleLabel = JBLabel("Session Details")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        detailsPanel.add(titleLabel)
        detailsPanel.add(Box.createVerticalStrut(10))
        
        // Session details
        addDetailRow("Session ID:", session.sessionId)
        addDetailRow("File:", session.fileName)
        addDetailRow("Target Class:", session.targetClass ?: "Unknown")
        addDetailRow("Status:", formatStatus(session.status))
        addDetailRow("Progress:", "${session.progressPercent}%")
        addDetailRow("Created:", session.createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        addDetailRow("Modified:", session.lastModified.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        addDetailRow("File Size:", formatFileSize(session.fileSize))
        
        detailsPanel.add(Box.createVerticalStrut(20))
        
        // Resume hint
        val hintLabel = JBLabel("<html><i>Click OK to resume this session</i></html>")
        hintLabel.foreground = UIUtil.getContextHelpForeground()
        hintLabel.alignmentX = Component.LEFT_ALIGNMENT
        detailsPanel.add(hintLabel)
        
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }
    
    private fun addDetailRow(label: String, value: String) {
        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2))
        rowPanel.alignmentX = Component.LEFT_ALIGNMENT
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, 25)
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        labelComponent.preferredSize = Dimension(100, 20)
        rowPanel.add(labelComponent)
        
        val valueComponent = JBLabel(value)
        rowPanel.add(valueComponent)
        
        detailsPanel.add(rowPanel)
    }
    
    private fun deleteSelectedSession() {
        val session = selectedSession ?: return
        
        val result = JOptionPane.showConfirmDialog(
            contentPane,
            "Are you sure you want to delete this session?\n${session.fileName}",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            if (repository.deleteSession(session.fileName)) {
                loadSessions()
                selectedSession = null
                updateDetailsPanel()
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Session deleted successfully",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } else {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Failed to delete session",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
    
    private fun cleanupOldSessions() {
        repository.cleanupAutoSaves(7)
        loadSessions()
        JOptionPane.showMessageDialog(
            contentPane,
            "Old auto-save sessions cleaned up",
            "Cleanup Complete",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    private fun formatStatus(status: String): String {
        return status.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    fun getSelectedSessionFileName(): String? {
        return selectedSession?.fileName
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }
    
    /**
     * Custom renderer for progress column
     */
    private class ProgressCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            )
            
            if (value is Int && component is JLabel) {
                component.text = "$value%"
                component.foreground = when {
                    value >= 100 -> Color(0, 128, 0)
                    value >= 50 -> Color(255, 140, 0)
                    else -> UIUtil.getLabelForeground()
                }
            }
            
            return component
        }
    }
    
    /**
     * Custom renderer for date columns
     */
    private class DateCellRenderer(
        private val formatter: DateTimeFormatter
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            )
            
            if (value is java.time.LocalDateTime && component is JLabel) {
                component.text = value.format(formatter)
            }
            
            return component
        }
    }
}