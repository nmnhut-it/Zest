package com.zps.zest.completion.metrics.testing.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.metrics.MetricsSessionLogger
import com.zps.zest.completion.metrics.SessionLogEntry
import com.zps.zest.ConfigurationManager
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

/**
 * Dialog for viewing complete metrics session log.
 * Shows all events in a table with details panel.
 */
class MetricsSessionLogDialog(private val project: Project) : DialogWrapper(project, true) {

    private val sessionLogger = MetricsSessionLogger.getInstance(project)
    private val eventsTableModel = DefaultTableModel()
    private val eventsTable = JBTable(eventsTableModel)
    private val detailsArea = JBTextArea(20, 100)
    private val statsArea = JBTextArea(8, 100)

    // Filters
    private val eventTypeFilter = JComboBox(arrayOf(
        "All Events",
        "inline_completion",
        "code_health",
        "quick_action",
        "dual_evaluation",
        "code_quality",
        "unit_test"
    ))
    private val successFilter = JComboBox(arrayOf("All", "Success Only", "Failures Only"))

    init {
        title = "Metrics Session Log Viewer"
        init()
        setupTable()
        loadSessionData()
        setupFilters()
    }

    private fun setupTable() {
        eventsTableModel.addColumn("Time")
        eventsTableModel.addColumn("Event Type")
        eventsTableModel.addColumn("Completion ID")
        eventsTableModel.addColumn("Response")
        eventsTableModel.addColumn("Time (ms)")

        eventsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        eventsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showSelectedEventDetails()
            }
        }
    }

    private fun setupFilters() {
        eventTypeFilter.addActionListener { applyFilters() }
        successFilter.addActionListener { applyFilters() }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(1200, 800)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitPane.topComponent = createTablePanel()
        splitPane.bottomComponent = createDetailsPanel()
        splitPane.resizeWeight = 0.5

        panel.add(splitPane, BorderLayout.CENTER)

        return panel
    }

    private fun createTablePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Session Events")

        // Filters panel
        val filtersPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        filtersPanel.add(JLabel("Event Type:"))
        filtersPanel.add(eventTypeFilter)
        filtersPanel.add(Box.createHorizontalStrut(20))
        filtersPanel.add(JLabel("Status:"))
        filtersPanel.add(successFilter)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadSessionData() }
        filtersPanel.add(Box.createHorizontalStrut(20))
        filtersPanel.add(refreshButton)

        panel.add(filtersPanel, BorderLayout.NORTH)

        // Table
        val scrollPane = JBScrollPane(eventsTable)
        scrollPane.preferredSize = JBUI.size(1100, 300)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Stats panel
        statsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        statsArea.isEditable = false
        statsArea.background = panel.background
        val statsScroll = JBScrollPane(statsArea)
        statsScroll.preferredSize = JBUI.size(1100, 120)
        panel.add(statsScroll, BorderLayout.SOUTH)

        return panel
    }

    private fun createDetailsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Event Details")

        detailsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        detailsArea.isEditable = false
        detailsArea.lineWrap = false

        val scrollPane = JBScrollPane(detailsArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Toolbar for details
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT))

        val copyJsonButton = JButton("Copy JSON")
        copyJsonButton.addActionListener { copyDetailToClipboard("json") }
        toolbar.add(copyJsonButton)

        val copyCurlButton = JButton("Copy CURL")
        copyCurlButton.addActionListener { copyDetailToClipboard("curl") }
        toolbar.add(copyCurlButton)

        val copyAllButton = JButton("Copy All")
        copyAllButton.addActionListener { copyDetailToClipboard("all") }
        toolbar.add(copyAllButton)

        panel.add(toolbar, BorderLayout.SOUTH)

        return panel
    }

    private fun loadSessionData() {
        val events = sessionLogger.getAllEvents()

        // Clear table
        eventsTableModel.rowCount = 0

        // Populate table
        events.reversed().forEach { entry ->
            eventsTableModel.addRow(arrayOf(
                entry.getFormattedTimestamp(),
                entry.eventType,
                entry.completionId,
                "${if (entry.success) "✅" else "❌"} ${entry.responseCode}",
                entry.responseTimeMs.toString()
            ))
        }

        // Update stats
        updateStats()
    }

    private fun applyFilters() {
        val eventTypeFilter = eventTypeFilter.selectedItem as String
        val successFilter = successFilter.selectedItem as String

        var events = sessionLogger.getAllEvents()

        // Filter by event type
        if (eventTypeFilter != "All Events") {
            events = events.filter { it.eventType == eventTypeFilter }
        }

        // Filter by success
        when (successFilter) {
            "Success Only" -> events = events.filter { it.success }
            "Failures Only" -> events = events.filter { !it.success }
        }

        // Clear and populate table
        eventsTableModel.rowCount = 0
        events.reversed().forEach { entry ->
            eventsTableModel.addRow(arrayOf(
                entry.getFormattedTimestamp(),
                entry.eventType,
                entry.completionId,
                "${if (entry.success) "✅" else "❌"} ${entry.responseCode}",
                entry.responseTimeMs.toString()
            ))
        }
    }

    private fun showSelectedEventDetails() {
        val selectedRow = eventsTable.selectedRow
        if (selectedRow < 0) return

        val allEvents = sessionLogger.getAllEvents().reversed()
        if (selectedRow >= allEvents.size) return

        val entry = allEvents[selectedRow]
        val config = ConfigurationManager.getInstance(project)

        detailsArea.text = entry.getDetailedReport(config.authToken)
    }

    private fun updateStats() {
        val stats = sessionLogger.getSessionStats()

        statsArea.text = buildString {
            appendLine("═══════════ SESSION STATISTICS ═══════════")
            appendLine("Session Duration: ${stats.sessionDurationMs / 1000}s")
            appendLine("Total Events: ${stats.totalEvents}")
            appendLine("Success: ${stats.successfulEvents} | Failed: ${stats.failedEvents}")
            appendLine("Success Rate: ${String.format("%.1f%%", stats.successRate)}")
            appendLine("Average Response Time: ${stats.averageResponseTime}ms")
            appendLine("Total Data Sent: ${stats.totalDataSentBytes} bytes")
            append("Events by Type: ")
            append(stats.eventsByType.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
    }

    private fun copyDetailToClipboard(type: String) {
        val selectedRow = eventsTable.selectedRow
        if (selectedRow < 0) {
            Messages.showWarningDialog(project, "Please select an event first", "No Selection")
            return
        }

        val allEvents = sessionLogger.getAllEvents().reversed()
        val entry = allEvents[selectedRow]
        val config = ConfigurationManager.getInstance(project)

        val text = when (type) {
            "json" -> entry.getPrettyJsonPayload()
            "curl" -> entry.getCurlCommand(config.authToken)
            "all" -> entry.getDetailedReport(config.authToken)
            else -> detailsArea.text
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        Messages.showInfoMessage(project, "Copied to clipboard", "Success")
    }

    override fun createActions(): Array<Action> {
        val exportAction = object : AbstractAction("Export Session") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                exportSession()
            }
        }

        val clearAction = object : AbstractAction("Clear Session") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                clearSession()
            }
        }

        return arrayOf(exportAction, clearAction, okAction)
    }

    private fun exportSession() {
        val descriptor = FileSaverDescriptor(
            "Export Metrics Session Log",
            "Choose location to save session log",
            "log"
        )

        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val defaultName = "zest-metrics-session-${System.currentTimeMillis()}.log"

        val wrapper = saveDialog.save(defaultName)
        if (wrapper != null) {
            val filePath = wrapper.file.path
            val success = sessionLogger.exportToFile(filePath)

            if (success) {
                Messages.showInfoMessage(
                    project,
                    "Session log exported to: $filePath",
                    "Export Successful"
                )
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to export session log",
                    "Export Failed"
                )
            }
        }
    }

    private fun clearSession() {
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to clear the session log?",
            "Clear Session",
            Messages.getQuestionIcon()
        )

        if (confirm == Messages.YES) {
            sessionLogger.clearSession()
            loadSessionData()
            detailsArea.text = ""
            Messages.showInfoMessage(project, "Session log cleared", "Cleared")
        }
    }
}
