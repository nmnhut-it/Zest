package com.zps.zest.codehealth.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import java.awt.*
import java.beans.PropertyChangeListener
import java.time.LocalDate
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
    private val component: JComponent
    private var currentData: List<CodeHealthAnalyzer.MethodHealthResult>? = null
    
    init {
        component = createEditorComponent()
        loadRecentData()
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
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        mainPanel.add(createToolbar(), BorderLayout.NORTH)
        
        // Add dashboard content
        val splitter = JBSplitter(true, 0.25f) // 25% top, 75% bottom
        splitter.firstComponent = createSummaryPanel()
        splitter.secondComponent = createDetailedViewTabs()
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
    }
    
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
    
    private fun createSummaryPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(20, 20, 20, 20)
        
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
        panel.add(titleLabel, gbc)
        
        // Summary metrics
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.25
        
        val summaryData = calculateSummaryMetrics()
        
        // Overall health score
        gbc.gridx = 0
        panel.add(createMetricCard(
            "🏆 Overall Health", 
            "${summaryData.overallScore}/100",
            getScoreColor(summaryData.overallScore)
        ), gbc)
        
        // Methods analyzed
        gbc.gridx = 1
        panel.add(createMetricCard(
            "🔍 Methods Analyzed", 
            summaryData.methodsAnalyzed.toString(),
            UIUtil.getLabelForeground()
        ), gbc)
        
        // Issues found
        gbc.gridx = 2
        panel.add(createMetricCard(
            "🎯 Issues Found", 
            summaryData.issuesFound.toString(),
            if (summaryData.issuesFound > 0) Color(255, 152, 0) else Color(76, 175, 80)
        ), gbc)
        
        // Critical issues
        gbc.gridx = 3
        panel.add(createMetricCard(
            "🚨 Critical Issues", 
            summaryData.criticalIssues.toString(),
            if (summaryData.criticalIssues > 0) Color(244, 67, 54) else Color(76, 175, 80)
        ), gbc)
        
        return panel
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
    
    private fun createDetailedViewTabs(): JComponent {
        val tabbedPane = JBTabbedPane()
        
        tabbedPane.addTab("🚨 Critical Issues", createCriticalIssuesPanel())
        tabbedPane.addTab("📊 Health Trends", createHealthTrendsPanel())
        tabbedPane.addTab("📂 By File", createFileHealthPanel())
        tabbedPane.addTab("👥 By Author", createAuthorHealthPanel())
        tabbedPane.addTab("🔄 Recent Changes", createRecentChangesPanel())
        
        return tabbedPane
    }
    
    private fun createCriticalIssuesPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val criticalIssues = getCriticalIssues()
        
        if (criticalIssues.isEmpty()) {
            val noIssuesLabel = JBLabel("✨ No critical issues found - your code is in great shape!")
            noIssuesLabel.font = noIssuesLabel.font.deriveFont(16f)
            panel.add(noIssuesLabel)
        } else {
            criticalIssues.forEach { (method, issues) ->
                val issueCard = createIssueCard(method, issues)
                panel.add(issueCard)
                panel.add(Box.createVerticalStrut(10))
            }
        }
        
        return JBScrollPane(panel)
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
    
    private fun createFileHealthPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val fileHealthData = getFileHealthData()
        
        fileHealthData.forEach { (fileName, healthScore, issueCount) ->
            val fileCard = createFileHealthCard(fileName, healthScore, issueCount)
            panel.add(fileCard)
            panel.add(Box.createVerticalStrut(8))
        }
        
        return JBScrollPane(panel)
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
        val issueFile = CodeHealthVirtualFileSystem.createIssueFile(issue, method.fqn)
        FileEditorManager.getInstance(project).openFile(issueFile, true)
    }
    
    private fun loadRecentData() {
        // Try to load the most recent data
        val today = LocalDate.now()
        currentData = storage.getReportForDate(today) 
            ?: storage.getReportForDate(today.minusDays(1))
            ?: storage.getReportForDate(today.minusDays(2))
        
        // If we have data, refresh the UI
        if (currentData != null) {
            SwingUtilities.invokeLater {
                component.revalidate()
                component.repaint()
            }
        }
    }
    
    private fun refreshData() {
        loadRecentData()
        Messages.showInfoMessage(project, "Code Health data refreshed", "Data Refreshed")
    }
    
    private fun runNewAnalysis() {
        Messages.showInfoMessage(
            project,
            "New Code Health analysis will be started in the background.\n" +
            "Results will appear in the Code Guardian tool window when complete.",
            "Analysis Started"
        )
        // TODO: Trigger actual analysis
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