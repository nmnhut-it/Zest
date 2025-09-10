package com.zps.zest.codehealth.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import com.zps.zest.codehealth.ProjectChangesTracker
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.progress.ProgressIndicator
import javax.swing.table.DefaultTableModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.JBTable
import java.util.concurrent.CompletableFuture
import com.zps.zest.codehealth.ui.CodeHealthIssueDetailDialog
import java.awt.*
import java.beans.PropertyChangeListener
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Overview editor for project-wide Code Health dashboard
 */
class CodeHealthOverviewEditor(
    private val project: Project,
    private val virtualFile: CodeHealthOverviewVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    private val storage = CodeHealthReportStorage.getInstance(project)
    private val component: JPanel
    private var currentData: List<CodeHealthAnalyzer.MethodHealthResult>? = null
    
    // UI components that need updating when data changes
    private var summaryPanel: JPanel? = null
    private var criticalIssuesPanel: JPanel? = null
    private var fileHealthPanel: JPanel? = null
    private var tabbedPane: JBTabbedPane? = null
    private var trackedMethodsPanel: JPanel? = null
    private var trackedMethodsTable: JBTable? = null
    private var recentChangesPanel: JPanel? = null
    
    init {
        component = JPanel(BorderLayout())
        component.background = UIUtil.getPanelBackground()
        setupUI()
        loadRecentData()
        updateDataDependentComponents()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Code Health Overview"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {}
    
    override fun setState(state: FileEditorState) {
        // No-op for overview editor
    }
    
    override fun getFile(): com.intellij.openapi.vfs.VirtualFile = virtualFile
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Refresh data action
        actionGroup.add(object : AnAction("Refresh Data", "Reload Code Health data", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshData()
            }
        })
        
        // Run new analysis action  
        actionGroup.add(object : AnAction("Run Analysis", "Start new Code Health analysis", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                runNewAnalysis()
            }
        })
        
        // Review all pending methods
        actionGroup.add(object : AnAction("Review All Pending", "Review all pending methods immediately", AllIcons.Actions.StartDebugger) {
            override fun actionPerformed(e: AnActionEvent) {
                reviewAllPending()
            }
        })
        
        // Clear tracking data
        actionGroup.add(object : AnAction("Clear Tracking", "Clear all tracked methods", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                clearTracking()
            }
        })
        
        // Export report action
        actionGroup.add(object : AnAction("Export Report", "Export health report to file", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                exportReport()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "CodeHealthOverviewEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun setupUI() {
        // Add toolbar at top
        component.add(createToolbar(), BorderLayout.NORTH)
        
        // Create and store summary panel
        summaryPanel = JPanel(GridBagLayout())
        summaryPanel!!.background = UIUtil.getPanelBackground()
        summaryPanel!!.border = EmptyBorder(20, 20, 20, 20)
        
        // Create tabbed pane
        tabbedPane = JBTabbedPane()
        
        // Add dashboard content
        val splitter = JBSplitter(true, 0.25f)
        splitter.firstComponent = summaryPanel
        splitter.secondComponent = tabbedPane
        
        component.add(splitter, BorderLayout.CENTER)
        
        // Setup tabs (empty initially)
        setupTabs()
    }
    
    private fun setupTabs() {
        criticalIssuesPanel = JPanel()
        criticalIssuesPanel!!.layout = BoxLayout(criticalIssuesPanel, BoxLayout.Y_AXIS)
        criticalIssuesPanel!!.background = UIUtil.getPanelBackground()
        criticalIssuesPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        fileHealthPanel = JPanel()
        fileHealthPanel!!.layout = BoxLayout(fileHealthPanel, BoxLayout.Y_AXIS)
        fileHealthPanel!!.background = UIUtil.getPanelBackground()
        fileHealthPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        recentChangesPanel = JPanel()
        recentChangesPanel!!.layout = BoxLayout(recentChangesPanel, BoxLayout.Y_AXIS)
        recentChangesPanel!!.background = UIUtil.getPanelBackground()
        recentChangesPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        // Create tracked methods panel - it will set trackedMethodsPanel internally
        val trackedPanel = createTrackedMethodsPanel()
        
        tabbedPane!!.addTab("📋 Tracked Methods", trackedPanel)
        tabbedPane!!.addTab("🚨 Critical Issues", JBScrollPane(criticalIssuesPanel))
        tabbedPane!!.addTab("📊 Health Trends", createHealthTrendsPanel())
        tabbedPane!!.addTab("📂 By File", JBScrollPane(fileHealthPanel))
        tabbedPane!!.addTab("👥 By Author", createAuthorHealthPanel())
        tabbedPane!!.addTab("🔄 Recent Changes", JBScrollPane(recentChangesPanel))
    }
    
    private fun updateDataDependentComponents() {
        SwingUtilities.invokeLater {
            updateSummaryPanel()
            updateCriticalIssuesPanel()
            updateFileHealthPanel()
            updateTrackedMethodsPanel()
            updateRecentChangesPanel()
            component.revalidate()
            component.repaint()
        }
    }
    
    private fun updateSummaryPanel() {
        summaryPanel?.removeAll()
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(10)
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        // Title
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 4
        gbc.weightx = 1.0
        val titleLabel = JBLabel("🛡️ Code Health Dashboard")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 24f)
        summaryPanel!!.add(titleLabel, gbc)
        
        // Summary metrics
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.25
        
        val summaryData = calculateSummaryMetrics()
        
        // Overall health score
        gbc.gridx = 0
        summaryPanel!!.add(createMetricCard(
            "🏆 Overall Health", 
            "${summaryData.overallScore}/100",
            getScoreColor(summaryData.overallScore)
        ), gbc)
        
        // Methods analyzed
        gbc.gridx = 1
        summaryPanel!!.add(createMetricCard(
            "🔍 Methods Analyzed", 
            summaryData.methodsAnalyzed.toString(),
            UIUtil.getLabelForeground()
        ), gbc)
        
        // Issues found
        gbc.gridx = 2
        summaryPanel!!.add(createMetricCard(
            "🎯 Issues Found", 
            summaryData.issuesFound.toString(),
            if (summaryData.issuesFound > 0) Color(255, 152, 0) else Color(76, 175, 80)
        ), gbc)
        
        // Critical issues
        gbc.gridx = 3
        summaryPanel!!.add(createMetricCard(
            "🚨 Critical Issues", 
            summaryData.criticalIssues.toString(),
            if (summaryData.criticalIssues > 0) Color(244, 67, 54) else Color(76, 175, 80)
        ), gbc)
    }
    
    private fun updateCriticalIssuesPanel() {
        criticalIssuesPanel?.removeAll()
        
        val criticalIssues = getCriticalIssues()
        
        if (criticalIssues.isEmpty()) {
            val noIssuesLabel = JBLabel("✨ No critical issues found - your code is in great shape!")
            noIssuesLabel.font = noIssuesLabel.font.deriveFont(16f)
            criticalIssuesPanel!!.add(noIssuesLabel)
        } else {
            criticalIssues.forEach { (method, issues) ->
                val issueCard = createIssueCard(method, issues)
                criticalIssuesPanel!!.add(issueCard)
                criticalIssuesPanel!!.add(Box.createVerticalStrut(10))
            }
        }
    }
    
    private fun updateFileHealthPanel() {
        fileHealthPanel?.removeAll()
        
        val fileHealthData = getFileHealthData()
        
        fileHealthData.forEach { (fileName, healthScore, issueCount) ->
            val fileCard = createFileHealthCard(fileName, healthScore, issueCount)
            fileHealthPanel!!.add(fileCard)
            fileHealthPanel!!.add(Box.createVerticalStrut(8))
        }
    }
    
    
    private fun createMetricCard(title: String, value: String, valueColor: Color): JComponent {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(12f)
        titleLabel.alignmentX = Component.CENTER_ALIGNMENT
        card.add(titleLabel)
        
        card.add(Box.createVerticalStrut(5))
        
        val valueLabel = JBLabel(value)
        valueLabel.font = valueLabel.font.deriveFont(Font.BOLD, 20f)
        valueLabel.foreground = valueColor
        valueLabel.alignmentX = Component.CENTER_ALIGNMENT
        card.add(valueLabel)
        
        return card
    }
    
    
    private fun createHealthTrendsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val trendsLabel = JBLabel("📊 Health trends over time will be displayed here")
        trendsLabel.font = trendsLabel.font.deriveFont(16f)
        trendsLabel.horizontalAlignment = SwingConstants.CENTER
        
        panel.add(trendsLabel, BorderLayout.CENTER)
        
        // TODO: Implement actual trends chart
        
        return panel
    }
    
    
    private fun createAuthorHealthPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val authorLabel = JBLabel("👥 Code health metrics by author will be displayed here")
        authorLabel.font = authorLabel.font.deriveFont(16f)
        authorLabel.horizontalAlignment = SwingConstants.CENTER
        
        panel.add(authorLabel, BorderLayout.CENTER)
        
        // TODO: Implement author-based health metrics
        
        return panel
    }
    
    private fun createTrackedMethodsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // Add header with summary
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        headerPanel.background = UIUtil.getPanelBackground()
        val headerLabel = JBLabel("📋 Currently Tracked Methods")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 16f)
        headerPanel.add(headerLabel)
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Create table
        val columns = arrayOf("Method", "Modifications", "Last Modified", "Review Status", "Select")
        val tableModel = object : DefaultTableModel(columns, 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> {
                return if (columnIndex == 4) Boolean::class.java else String::class.java
            }
            
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 4 // Only checkbox column is editable
            }
        }
        
        trackedMethodsTable = JBTable(tableModel)
        trackedMethodsTable!!.setShowGrid(true)
        trackedMethodsTable!!.gridColor = UIUtil.getBoundsColor()
        trackedMethodsTable!!.rowHeight = 25
        
        // Set column widths
        trackedMethodsTable!!.columnModel.getColumn(0).preferredWidth = 400
        trackedMethodsTable!!.columnModel.getColumn(1).preferredWidth = 100
        trackedMethodsTable!!.columnModel.getColumn(2).preferredWidth = 150
        trackedMethodsTable!!.columnModel.getColumn(3).preferredWidth = 120
        trackedMethodsTable!!.columnModel.getColumn(4).preferredWidth = 60
        
        val scrollPane = JBScrollPane(trackedMethodsTable)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Add action buttons at bottom
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = UIUtil.getPanelBackground()
        
        val reviewSelectedBtn = JButton("Review Selected")
        reviewSelectedBtn.addActionListener {
            reviewSelectedMethods()
        }
        buttonPanel.add(reviewSelectedBtn)
        
        val selectAllBtn = JButton("Select All")
        selectAllBtn.addActionListener {
            selectAllMethods(true)
        }
        buttonPanel.add(selectAllBtn)
        
        val deselectAllBtn = JButton("Deselect All")
        deselectAllBtn.addActionListener {
            selectAllMethods(false)
        }
        buttonPanel.add(deselectAllBtn)
        
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        // Store the panel reference
        trackedMethodsPanel = panel
        
        // Don't populate here - will be done in updateDataDependentComponents
        
        return panel
    }
    
    private fun updateTrackedMethodsPanel() {
        if (trackedMethodsTable == null) return
        
        val tableModel = trackedMethodsTable!!.model as DefaultTableModel
        tableModel.rowCount = 0
        
        val tracker = ProjectChangesTracker.getInstance(project)
        val trackedMethods = tracker.getTrackedMethods()
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        val pendingReviews = reviewer.getPendingReviews()
        val reviewedMethods = reviewer.getReviewedMethods()
        
        val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        
        trackedMethods.forEach { (fqn, method) ->
            val status = when {
                reviewedMethods.containsKey(fqn) -> "✅ Reviewed"
                pendingReviews.containsKey(fqn) -> "⏳ Pending"
                else -> "📝 Tracking"
            }
            
            val lastModified = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(method.lastModified),
                java.time.ZoneId.systemDefault()
            ).format(dateFormatter)
            
            tableModel.addRow(arrayOf(
                formatMethodName(fqn),
                method.modificationCount.toString(),
                lastModified,
                status,
                false // checkbox
            ))
        }
        
        // Update header with count - safely check if components exist
        if (trackedMethodsPanel != null && trackedMethodsPanel!!.componentCount > 0) {
            val firstComponent = trackedMethodsPanel!!.getComponent(0)
            if (firstComponent is JPanel && firstComponent.componentCount > 0) {
                val headerLabel = firstComponent.getComponent(0) as? JBLabel
                headerLabel?.text = "📋 Currently Tracked Methods (${trackedMethods.size} total)"
            }
        }
    }
    
    private fun reviewSelectedMethods() {
        val tableModel = trackedMethodsTable?.model as? DefaultTableModel ?: return
        val selectedMethods = mutableListOf<String>()
        val tracker = ProjectChangesTracker.getInstance(project)
        val trackedMethods = tracker.getTrackedMethods()
        
        for (row in 0 until tableModel.rowCount) {
            val isSelected = tableModel.getValueAt(row, 4) as Boolean
            if (isSelected) {
                val methodName = tableModel.getValueAt(row, 0) as String
                // Find the full FQN from the tracked methods
                trackedMethods.keys.find { formatMethodName(it) == methodName }?.let {
                    selectedMethods.add(it)
                }
            }
        }
        
        if (selectedMethods.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Please select at least one method to review.",
                "No Methods Selected"
            )
            return
        }
        
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(
            project,
            "Reviewing Selected Methods",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Reviewing ${selectedMethods.size} selected methods..."
                
                val reviewer = BackgroundHealthReviewer.getInstance(project)
                val future = reviewer.triggerImmediateReview(selectedMethods) { progressMsg ->
                    indicator.text = progressMsg
                }
                
                try {
                    val results = future.get()
                    ApplicationManager.getApplication().invokeLater {
                        currentData = results
                        updateDataDependentComponents()
                        Messages.showInfoMessage(
                            project,
                            "Review complete!\n${results.size} methods reviewed\n${results.sumOf { it.issues.size }} issues found",
                            "Review Complete"
                        )
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Review failed: ${e.message}",
                            "Review Error"
                        )
                    }
                }
            }
        })
    }
    
    private fun selectAllMethods(select: Boolean) {
        val tableModel = trackedMethodsTable?.model as? DefaultTableModel ?: return
        for (row in 0 until tableModel.rowCount) {
            tableModel.setValueAt(select, row, 4)
        }
    }
    
    private fun updateRecentChangesPanel() {
        recentChangesPanel?.removeAll()
        
        val tracker = ProjectChangesTracker.getInstance(project)
        val trackedMethods = tracker.getTrackedMethods()
            .values
            .sortedByDescending { it.lastModified }
            .take(20) // Show last 20 changes
        
        if (trackedMethods.isEmpty()) {
            val noChangesLabel = JBLabel("No recent changes tracked")
            noChangesLabel.font = noChangesLabel.font.deriveFont(14f)
            recentChangesPanel!!.add(noChangesLabel)
        } else {
            val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            
            trackedMethods.forEach { method ->
                val changeCard = JPanel(BorderLayout())
                changeCard.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
                changeCard.border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
                    EmptyBorder(8, 12, 8, 12)
                )
                
                val leftPanel = JPanel()
                leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
                leftPanel.background = changeCard.background
                
                val methodLabel = JBLabel(formatMethodName(method.fqn))
                methodLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
                leftPanel.add(methodLabel)
                
                val lastModified = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(method.lastModified),
                    java.time.ZoneId.systemDefault()
                ).format(dateFormatter)
                
                val detailsLabel = JBLabel("Modified ${method.modificationCount} times | Last: $lastModified")
                detailsLabel.font = detailsLabel.font.deriveFont(10f)
                detailsLabel.foreground = UIUtil.getInactiveTextColor()
                leftPanel.add(detailsLabel)
                
                changeCard.add(leftPanel, BorderLayout.WEST)
                
                recentChangesPanel!!.add(changeCard)
                recentChangesPanel!!.add(Box.createVerticalStrut(5))
            }
        }
    }
    
    private fun createRecentChangesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val changesLabel = JBLabel("🔄 Recent changes and their health impact will be displayed here")
        changesLabel.font = changesLabel.font.deriveFont(16f)
        changesLabel.horizontalAlignment = SwingConstants.CENTER
        
        panel.add(changesLabel, BorderLayout.CENTER)
        
        // TODO: Implement recent changes tracking
        
        return panel
    }
    
    private fun createIssueCard(method: CodeHealthAnalyzer.MethodHealthResult, issues: List<CodeHealthAnalyzer.HealthIssue>): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Color(244, 67, 54), 3, 0, 0, 0),
            EmptyBorder(10, 15, 10, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        // Method name
        val methodLabel = JBLabel(formatMethodName(method.fqn))
        methodLabel.font = Font(Font.MONOSPACED, Font.BOLD, 14)
        leftPanel.add(methodLabel)
        
        // Issues summary
        val issuesLabel = JBLabel("${issues.size} critical issue(s)")
        issuesLabel.font = issuesLabel.font.deriveFont(12f)
        issuesLabel.foreground = Color(244, 67, 54)
        leftPanel.add(issuesLabel)
        
        card.add(leftPanel, BorderLayout.WEST)
        
        // Action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = card.background
        
        val viewButton = JButton("View Details")
        viewButton.addActionListener {
            openIssueInEditor(method, issues.first())
        }
        buttonPanel.add(viewButton)
        
        card.add(buttonPanel, BorderLayout.EAST)
        
        return card
    }
    
    private fun createFileHealthCard(fileName: String, healthScore: Int, issueCount: Int): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getScoreColor(healthScore), 3, 0, 0, 0),
            EmptyBorder(8, 12, 8, 12)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val fileLabel = JBLabel(fileName)
        fileLabel.font = fileLabel.font.deriveFont(Font.BOLD, 12f)
        leftPanel.add(fileLabel)
        
        val statsLabel = JBLabel("Health: $healthScore/100 | Issues: $issueCount")
        statsLabel.font = statsLabel.font.deriveFont(10f)
        statsLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(statsLabel)
        
        card.add(leftPanel, BorderLayout.WEST)
        
        return card
    }
    
    private fun calculateSummaryMetrics(): SummaryMetrics {
        val data = currentData ?: return SummaryMetrics(0, 0, 0, 0)
        
        val overallScore = if (data.isNotEmpty()) data.map { it.healthScore }.average().toInt() else 0
        val methodsAnalyzed = data.size
        val allIssues = data.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val issuesFound = allIssues.size
        val criticalIssues = allIssues.count { it.severity >= 4 }
        
        return SummaryMetrics(overallScore, methodsAnalyzed, issuesFound, criticalIssues)
    }
    
    private fun getCriticalIssues(): List<Pair<CodeHealthAnalyzer.MethodHealthResult, List<CodeHealthAnalyzer.HealthIssue>>> {
        val data = currentData ?: return emptyList()
        
        return data.mapNotNull { method ->
            val criticalIssues = method.issues.filter { it.severity >= 4 && it.verified && !it.falsePositive }
            if (criticalIssues.isNotEmpty()) method to criticalIssues else null
        }.take(10) // Show top 10 critical issues
    }
    
    private fun getFileHealthData(): List<Triple<String, Int, Int>> {
        val data = currentData ?: return emptyList()
        
        return data.groupBy { extractFileName(it.fqn) }
            .map { (fileName, methods) ->
                val avgHealth = methods.map { it.healthScore }.average().toInt()
                val issueCount = methods.flatMap { it.issues }.count { it.verified && !it.falsePositive }
                Triple(fileName, avgHealth, issueCount)
            }
            .sortedBy { it.second } // Sort by health score (worst first)
    }
    
    private fun extractFileName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file
            fqn.substringBefore(":").substringAfterLast("/").substringAfterLast("\\")
        } else {
            // Java class
            fqn.substringBeforeLast(".") + ".java"
        }
    }
    
    private fun formatMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            val lineInfo = fqn.substring(colonIndex)
            fileName + lineInfo
        } else {
            // Java method FQN
            fqn
        }
    }
    
    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> Color(76, 175, 80)  // Green
            score >= 60 -> Color(255, 152, 0)   // Orange
            else -> Color(244, 67, 54)          // Red
        }
    }
    
    private fun openIssueInEditor(method: CodeHealthAnalyzer.MethodHealthResult, issue: CodeHealthAnalyzer.HealthIssue) {
        // Use the new dialog instead of opening a separate editor
        val dialog = CodeHealthIssueDetailDialog(project, method, issue)
        dialog.show()
    }
    
    private fun loadRecentData() {
        // First check for immediate review results (highest priority)
        currentData = storage.getImmediateReviewResults()
        
        // If no immediate review, check for Git-triggered report
        if (currentData == null) {
            currentData = storage.getGitTriggeredReport()
        }
        
        // If no Git-triggered report, try to load the most recent daily data
        if (currentData == null) {
            // Get the most recent report date from storage
            val mostRecentDate = storage.getMostRecentReportDate()
            if (mostRecentDate != null) {
                currentData = storage.getReportForDate(mostRecentDate)
            } else {
                // Fallback: try last 7 days
                val today = LocalDate.now()
                for (i in 0..6) {
                    currentData = storage.getReportForDate(today.minusDays(i.toLong()))
                    if (currentData != null) break
                }
            }
        }
    }
    
    private fun refreshData() {
        loadRecentData()
        updateDataDependentComponents()
        Messages.showInfoMessage(project, "Code Health data refreshed", "Data Refreshed")
    }
    
    private fun runNewAnalysis() {
        val tracker = ProjectChangesTracker.getInstance(project)
        val trackedMethods = tracker.getTrackedMethods()
        
        if (trackedMethods.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No methods are currently being tracked.\nEdit some code to start tracking methods.",
                "No Methods to Analyze"
            )
            return
        }
        
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(
            project,
            "Running Code Health Analysis",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Analyzing ${trackedMethods.size} tracked methods..."
                
                val reviewer = BackgroundHealthReviewer.getInstance(project)
                val future = reviewer.triggerImmediateReview(
                    trackedMethods.keys.toList()
                ) { progressMsg ->
                    indicator.text = progressMsg
                }
                
                try {
                    val results = future.get()
                    ApplicationManager.getApplication().invokeLater {
                        currentData = results
                        updateDataDependentComponents()
                        Messages.showInfoMessage(
                            project,
                            "Analysis complete!\n${results.size} methods analyzed\n${results.sumOf { it.issues.size }} issues found",
                            "Analysis Complete"
                        )
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Analysis failed: ${e.message}",
                            "Analysis Error"
                        )
                    }
                }
            }
        })
    }
    
    private fun reviewAllPending() {
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        val pendingReviews = reviewer.getPendingReviews()
        
        if (pendingReviews.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No methods are pending review.",
                "No Pending Reviews"
            )
            return
        }
        
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(
            project,
            "Reviewing Pending Methods",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Reviewing ${pendingReviews.size} pending methods..."
                
                val future = reviewer.triggerImmediateReviewAll { progressMsg ->
                    indicator.text = progressMsg
                }
                
                try {
                    val results = future.get()
                    ApplicationManager.getApplication().invokeLater {
                        currentData = results
                        updateDataDependentComponents()
                        Messages.showInfoMessage(
                            project,
                            "Review complete!\n${results.size} methods reviewed\n${results.sumOf { it.issues.size }} issues found",
                            "Review Complete"
                        )
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Review failed: ${e.message}",
                            "Review Error"
                        )
                    }
                }
            }
        })
    }
    
    private fun clearTracking() {
        val result = Messages.showYesNoDialog(
            project,
            "This will clear all tracked methods and pending reviews.\nAre you sure?",
            "Clear Tracking Data",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            val tracker = ProjectChangesTracker.getInstance(project)
            tracker.clearAllTracking()
            updateDataDependentComponents()
            Messages.showInfoMessage(
                project,
                "All tracking data has been cleared.",
                "Tracking Cleared"
            )
        }
    }
    
    private fun exportReport() {
        Messages.showInfoMessage(
            project,
            "Export functionality will be implemented in a future update.",
            "Feature Coming Soon"
        )
        // TODO: Implement report export
    }
    
    data class SummaryMetrics(
        val overallScore: Int,
        val methodsAnalyzed: Int,
        val issuesFound: Int,
        val criticalIssues: Int
    )
}