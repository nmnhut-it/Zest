package com.zps.zest.codehealth.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import com.zps.zest.codehealth.ProjectChangesTracker
import com.zps.zest.codehealth.ui.CodeHealthIssueDetailDialog
import java.awt.*
import java.beans.PropertyChangeListener
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

class CodeHealthOverviewEditor(
    private val project: Project,
    private val virtualFile: CodeHealthOverviewVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val storage = CodeHealthReportStorage.getInstance(project)
    private val component = JPanel(BorderLayout())
    private var currentData: List<CodeHealthAnalyzer.MethodHealthResult>? = null

    private var summaryPanel: JPanel? = null
    private var trackedMethodsPanel: JPanel? = null
    private var criticalIssuesPanel: JPanel? = null
    private var lessIssuesPanel: JPanel? = null
    private var recentChangesPanel: JPanel? = null

    private var trackedMethodsTable: JBTable? = null
    private var tabbedPane: JBTabbedPane? = null
    private var recentScroll: JBScrollPane? = null
    private var criticalScroll: JBScrollPane? = null
    private var lessScroll: JBScrollPane? = null

    private val staticTabsCount = 3
    private val maxIssueTabs = 20

    init {
        component.background = UIUtil.getPanelBackground()
        initUI()
        loadRecentData()
        refreshUI()
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
    override fun setState(state: FileEditorState) {}
    override fun getFile(): com.intellij.openapi.vfs.VirtualFile = virtualFile

    private fun initUI() {
        component.add(createToolbar(), BorderLayout.NORTH)
        summaryPanel = buildSummaryPanel()
        tabbedPane = JBTabbedPane()
        val splitter = JBSplitter(true, 0.25f).apply {
            firstComponent = summaryPanel
            secondComponent = tabbedPane
        }
        component.add(splitter, BorderLayout.CENTER)
        initTabs()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(action("Refresh Data", AllIcons.Actions.Refresh) { refreshData() })
            add(action("Run Analysis", AllIcons.Actions.Execute) { runNewAnalysis() })
            add(action("Review All Pending", AllIcons.Actions.StartDebugger) { reviewAllPending() })
            add(action("Clear Tracking", AllIcons.Actions.GC) { clearTracking() })
            add(action("Export Report", AllIcons.ToolbarDecorator.Export) { exportReport() })
        }
        return ActionManager.getInstance()
            .createActionToolbar("CodeHealthOverviewEditor", group, true)
            .apply { targetComponent = component }.component
    }

    private fun action(text: String, icon: Icon, run: () -> Unit) =
        object : AnAction(text, text, icon) { override fun actionPerformed(e: AnActionEvent) = run() }

    private fun buildSummaryPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = EmptyBorder(20, 20, 20, 20)
        }
    }

    private fun initTabs() {
        val tracked = createTrackedMethodsPanel()
        criticalIssuesPanel = panelVBox()
        lessIssuesPanel = panelVBox()
        recentChangesPanel = panelVBox()
        criticalScroll = JBScrollPane(criticalIssuesPanel)
        lessScroll = JBScrollPane(lessIssuesPanel)
        recentScroll = JBScrollPane(recentChangesPanel)
        tabbedPane?.addTab("📋 Tracked Methods", tracked)
        tabbedPane?.addTab("🚨 Critical Issues", criticalScroll)
        tabbedPane?.addTab("⚠️ Less-Critical Issues", lessScroll)
        tabbedPane?.addTab("🔄 Recent Changes", recentScroll)
    }

    private fun panelVBox(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = EmptyBorder(15, 15, 15, 15)
        }
    }
    private fun refreshUI() {
        SwingUtilities.invokeLater {
            updateSummaryPanel()
            updateCriticalIssuesPanel()
            updateLessCriticalIssuesPanel()
            updateTrackedMethodsPanel()
            updateRecentChangesPanel()
            component.revalidate()
            component.repaint()
        }
    }

    private fun updateSummaryPanel() {
        val panel = summaryPanel ?: return
        panel.removeAll()
        val gbc = GridBagConstraints().apply { insets = JBUI.insets(10); fill = GridBagConstraints.HORIZONTAL }
        addSummaryTitle(panel, gbc)
        addSummaryMetrics(panel, gbc, calculateSummaryMetrics())
    }

    private fun addSummaryTitle(panel: JPanel, gbc: GridBagConstraints) {
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4; gbc.weightx = 1.0
        panel.add(JBLabel("🛡️ Code Health Dashboard").apply {
            font = font.deriveFont(Font.BOLD, 24f)
        }, gbc)
    }

    private fun addSummaryMetrics(panel: JPanel, gbc: GridBagConstraints, s: SummaryMetrics) {
        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.25
        panel.add(metric("🏆 Overall Health", "${s.overallScore}/100", getScoreColor(s.overallScore)), gbc.apply { gbc.gridx = 0 })
        panel.add(metric("🔍 Methods Analyzed", "${s.methodsAnalyzed}", UIUtil.getLabelForeground()), gbc.apply { gbc.gridx = 1 })
        panel.add(metric("🎯 Issues Found", "${s.issuesFound}", if (s.issuesFound > 0) Color(255, 152, 0) else Color(76, 175, 80)), gbc.apply { gbc.gridx = 2 })
        panel.add(metric("🚨 Critical Issues", "${s.criticalIssues}", if (s.criticalIssues > 0) Color(244, 67, 54) else Color(76, 175, 80)), gbc.apply { gbc.gridx = 3 })
    }

    private fun metric(title: String, value: String, color: Color): JComponent {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
            border = JBUI.Borders.compound(JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1), EmptyBorder(15, 15, 15, 15))
        }
        card.add(JBLabel(title).apply { font = font.deriveFont(12f); alignmentX = Component.CENTER_ALIGNMENT })
        card.add(Box.createVerticalStrut(5))
        card.add(JBLabel(value).apply { font = font.deriveFont(Font.BOLD, 20f); foreground = color; alignmentX = Component.CENTER_ALIGNMENT })
        return card
    }

    private fun updateCriticalIssuesPanel() {
        val panel = criticalIssuesPanel ?: return
        panel.removeAll()
        val items = getCriticalIssues()
        if (items.isEmpty()) {
            panel.add(JBLabel("✨ No critical issues found - great job!").apply { font = font.deriveFont(16f) })
            return
        }
        items.forEach { (m, list) -> panel.add(issueCard(m, list, Color(244, 67, 54))); panel.add(Box.createVerticalStrut(10)) }
    }

    private fun updateLessCriticalIssuesPanel() {
        val panel = lessIssuesPanel ?: return
        panel.removeAll()
        val items = getLessCriticalIssues()
        if (items.isEmpty()) {
            panel.add(JBLabel("No less-critical issues. Keep it up!").apply { font = font.deriveFont(16f) })
            return
        }
        items.forEach { (m, list) -> panel.add(issueCard(m, list, Color(255, 152, 0))); panel.add(Box.createVerticalStrut(10)) }
    }

    private fun issueCard(
        method: CodeHealthAnalyzer.MethodHealthResult,
        issues: List<CodeHealthAnalyzer.HealthIssue>,
        accent: Color
    ): JComponent {
        val card = fixedHeightCard(accent)
        card.add(issueLeft(method, issues, accent), BorderLayout.WEST)
        card.add(issueButtons(method, issues), BorderLayout.EAST)
        return card
    }

    private fun issueLeft(
        method: CodeHealthAnalyzer.MethodHealthResult,
        issues: List<CodeHealthAnalyzer.HealthIssue>,
        accent: Color
    ): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bgForCards()
            add(Box.createVerticalGlue())
            add(JBLabel(formatMethodName(method.fqn)).apply { font = Font(Font.MONOSPACED, Font.BOLD, 14) })
            add(Box.createVerticalStrut(5))
            add(JBLabel("${issues.size} issue(s)").apply { font = font.deriveFont(12f); foreground = accent })
            add(Box.createVerticalGlue())
        }
    }

    private fun issueButtons(
        method: CodeHealthAnalyzer.MethodHealthResult,
        issues: List<CodeHealthAnalyzer.HealthIssue>
    ): JComponent {
        return JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = bgForCards()
            add(JButton("View Details").apply { addActionListener { openIssueInEditor(method, issues) } })
        }
    }

    private fun createTrackedMethodsPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground(); border = EmptyBorder(15, 15, 15, 15)
        }
        panel.add(trackedHeader(), BorderLayout.NORTH)
        trackedMethodsTable = buildTrackedTable()
        panel.add(JBScrollPane(trackedMethodsTable), BorderLayout.CENTER)
        panel.add(trackedButtons(), BorderLayout.SOUTH)
        trackedMethodsPanel = panel
        return panel
    }

    private fun trackedHeader(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            background = UIUtil.getPanelBackground()
            add(JBLabel("📋 Currently Tracked Methods").apply { font = font.deriveFont(Font.BOLD, 16f) })
        }
    }

    private fun buildTrackedTable(): JBTable {
        val cols = arrayOf("Method", "Modifications", "Last Modified", "Review Status")
        val model = object : DefaultTableModel(cols, 0) {
            override fun getColumnClass(i: Int) = String::class.java
            override fun isCellEditable(r: Int, c: Int) = false
        }
        val apply = JBTable(model).apply {
            setShowGrid(true); gridColor = UIUtil.getBoundsColor(); rowHeight = 25
            columnModel.getColumn(0).preferredWidth = 420
            columnModel.getColumn(1).preferredWidth = 110
            columnModel.getColumn(2).preferredWidth = 160
            columnModel.getColumn(3).preferredWidth = 130
        }
        apply.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        return apply
    }

    private fun trackedButtons(): JComponent {
        return JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = UIUtil.getPanelBackground()
            add(JButton("Review All Tracked").apply { addActionListener { runNewAnalysis() } })
            add(JButton("Add Tracked Method...").apply { addActionListener { addTrackedMethodDialog() } })
            add(JButton("Untrack Selected").apply { addActionListener { untrackSelectedMethod() } })
            add(JButton("Clear All").apply { addActionListener { clearTracking() } })
        }
    }

    private fun updateTrackedMethodsPanel() {
        val table = trackedMethodsTable ?: return
        val model = table.model as DefaultTableModel
        model.rowCount = 0
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        val tracked = ProjectChangesTracker.getInstance(project).getTrackedMethods()
        val dtf = DateTimeFormatter.ofPattern("HH:mm:ss")
        tracked.forEach { (fqn, meta) ->
            val status = when {
                reviewer.getReviewedMethods().containsKey(fqn) -> "✅ Reviewed"
                reviewer.getPendingReviews().containsKey(fqn) -> "⏳ Pending"
                else -> "📝 Tracking"
            }
            val last = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(meta.lastModified), java.time.ZoneId.systemDefault()
            ).format(dtf)
            model.addRow(arrayOf(formatMethodName(fqn), "${meta.modificationCount}", last, status))
        }
        updateTrackedHeaderCount(tracked.size)
    }

    private fun updateTrackedHeaderCount(count: Int) {
        val headerPanel = (trackedMethodsPanel?.getComponent(0) as? JPanel) ?: return
        val title = headerPanel.getComponent(0) as? JBLabel ?: return
        title.text = "📋 Currently Tracked Methods ($count total)"
    }

    private fun untrackSelectedMethod() {
        val table = trackedMethodsTable ?: return
        val row = table.selectedRow
        if (row < 0) {
            Messages.showInfoMessage(project, "Select a method row to untrack.", "No Row Selected")
            return
        }
        val name = (table.model as DefaultTableModel).getValueAt(row, 0) as String
        val tracker = ProjectChangesTracker.getInstance(project)
        val fqn = tracker.getTrackedMethods().keys.find { formatMethodName(it) == name }
        if (fqn == null) {
            Messages.showErrorDialog(project, "Unable to resolve selected method.", "Untrack Failed")
            return
        }
        tracker.untrackMethod(fqn)
        refreshUI()
        Messages.showInfoMessage(project, "Stopped tracking $name", "Untracked")
    }

    private fun addTrackedMethodDialog() {
        val fqn = Messages.showInputDialog(
            project,
            "Enter fully-qualified method identifier (e.g., com.example.Foo.bar or file.ts:123):",
            "Add Tracked Method",
            null
        ) ?: return
        val trimmed = fqn.trim()
        if (trimmed.isEmpty()) {
            Messages.showInfoMessage(project, "Method identifier cannot be empty.", "Invalid Input")
            return
        }
        val tracker = ProjectChangesTracker.getInstance(project)
        if (tracker.getTrackedMethods().containsKey(trimmed)) {
            Messages.showInfoMessage(project, "Already tracking this method.", "Duplicate")
            return
        }
        tracker.trackMethodModification(trimmed)
        refreshUI()
        Messages.showInfoMessage(project, "Now tracking $trimmed", "Added")
    }

    private fun updateRecentChangesPanel() {
        val panel = recentChangesPanel ?: return
        panel.removeAll()
        val changes = ProjectChangesTracker.getInstance(project)
            .getTrackedMethods().values.sortedByDescending { it.lastModified }.take(20)
        if (changes.isEmpty()) {
            panel.add(JBLabel("No recent changes tracked").apply { font = font.deriveFont(14f) })
            return
        }
        changes.forEach { panel.add(changeCard(it)); panel.add(Box.createVerticalStrut(5)) }
    }

    private fun changeCard(method: ProjectChangesTracker.ModifiedMethod): JComponent {
        val card = fixedHeightCard(getScoreColor(70))
        val dtf = DateTimeFormatter.ofPattern("HH:mm:ss")
        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = card.background
        }
        left.add(Box.createVerticalGlue())
        left.add(JBLabel(formatMethodName(method.fqn)).apply { font = Font(Font.MONOSPACED, Font.BOLD, 12) })
        left.add(Box.createVerticalStrut(5))
        left.add(JBLabel("Modified ${method.modificationCount} times | Last: ${
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(method.lastModified),
                java.time.ZoneId.systemDefault()).format(dtf)
        }").apply { font = font.deriveFont(10f); foreground = UIUtil.getInactiveTextColor() })
        left.add(Box.createVerticalGlue())
        card.add(left, BorderLayout.WEST)
        return card
    }

    private fun coloredCard(lineColor: Color): JPanel {
        return JPanel(BorderLayout()).apply {
            background = bgForCards()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(lineColor, 3, 0, 0, 0), EmptyBorder(8, 12, 8, 12))
        }
    }

    private fun fixedHeightCard(lineColor: Color): JPanel {
        return JPanel(BorderLayout()).apply {
            background = bgForCards()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(lineColor, 3, 0, 0, 0), EmptyBorder(8, 12, 8, 12))
            preferredSize = Dimension(0, 90)
            minimumSize = Dimension(0, 90)
            maximumSize = Dimension(Integer.MAX_VALUE, 90)
        }
    }

    private fun bgForCards(): Color = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)



    private fun removeExistingIssueTabs(tp: JBTabbedPane) {
        val last = tp.tabCount - 1
        for (i in last - 1 downTo staticTabsCount) tp.removeTabAt(i)
    }

    private fun calculateSummaryMetrics(): SummaryMetrics {
        val data = currentData ?: return SummaryMetrics(0, 0, 0, 0)
        val overall = if (data.isNotEmpty()) data.map { it.healthScore }.average().toInt() else 0
        val verified = data.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        return SummaryMetrics(overall, data.size, verified.size, verified.count { it.severity >= 4 })
    }

    private fun getAllIssues(): List<Pair<CodeHealthAnalyzer.MethodHealthResult, CodeHealthAnalyzer.HealthIssue>> {
        val data = currentData ?: return emptyList()
        return data.flatMap { m ->
            m.issues.filter { it.verified && !it.falsePositive }
                .sortedByDescending { it.severity }.map { m to it }
        }
    }

    private fun getCriticalIssues():
            List<Pair<CodeHealthAnalyzer.MethodHealthResult, List<CodeHealthAnalyzer.HealthIssue>>> {
        val data = currentData ?: return emptyList()
        return data.mapNotNull { m ->
            val list = m.issues.filter { it.severity >= 4 && it.verified && !it.falsePositive }
            if (list.isNotEmpty()) m to list else null
        }.take(10)
    }

    private fun getLessCriticalIssues():
            List<Pair<CodeHealthAnalyzer.MethodHealthResult, List<CodeHealthAnalyzer.HealthIssue>>> {
        val data = currentData ?: return emptyList()
        return data.mapNotNull { m ->
            val list = m.issues.filter { it.severity in 1..3 && it.verified && !it.falsePositive }
            if (list.isNotEmpty()) m to list else null
        }.take(20)
    }

    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> Color(76, 175, 80)
            score >= 60 -> Color(255, 152, 0)
            else -> Color(244, 67, 54)
        }
    }

    private fun formatMethodName(fqn: String): String {
        if (":" in fqn) {
            val idx = fqn.lastIndexOf(":")
            val file = fqn.substring(0, idx).substringAfterLast("/").substringAfterLast("\\")
            return file + fqn.substring(idx)
        }
        return fqn
    }

    private fun openIssueInEditor(
        method: CodeHealthAnalyzer.MethodHealthResult,
        issue: List<CodeHealthAnalyzer.HealthIssue>
    ) {
        CodeHealthIssueDetailDialog(project, method, issue).show()
    }

    private fun loadRecentData() {
        currentData = storage.getImmediateReviewResults()
        if (currentData != null) return
        currentData = storage.getGitTriggeredReport()
        if (currentData != null) return
        currentData = loadMostRecentDaily() ?: loadFallbackLast7Days()
    }

    private fun loadMostRecentDaily(): List<CodeHealthAnalyzer.MethodHealthResult>? {
        val date = storage.getMostRecentReportDate() ?: return null
        return storage.getReportForDate(date)
    }

    private fun loadFallbackLast7Days(): List<CodeHealthAnalyzer.MethodHealthResult>? {
        val today = LocalDate.now()
        for (i in 0..6) storage.getReportForDate(today.minusDays(i.toLong()))?.let { return it }
        return null
    }

    private fun refreshData() {
        loadRecentData()
        refreshUI()
        Messages.showInfoMessage(project, "Code Health data refreshed", "Data Refreshed")
    }

    private fun runNewAnalysis() {
        val tracked = ProjectChangesTracker.getInstance(project).getTrackedMethods()
        if (tracked.isEmpty()) {
            Messages.showInfoMessage(project, "No methods are tracked. Add some to start.", "No Methods to Analyze")
            return
        }
        runReviewTask(
            title = "Running Code Health Analysis",
            future = BackgroundHealthReviewer.getInstance(project)
                .triggerImmediateReview(tracked.keys.toList()) {
                    ProgressManager.getInstance().progressIndicator?.text = it
                },
            successTitle = "Analysis Complete"
        )
    }

    private fun reviewAllPending() {
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        if (reviewer.getPendingReviews().isEmpty()) {
            Messages.showInfoMessage(project, "No methods are pending review.", "No Pending Reviews")
            return
        }
        runReviewTask(
            title = "Reviewing Pending Methods",
            future = reviewer.triggerImmediateReviewAll {
                ProgressManager.getInstance().progressIndicator?.text = it
            },
            successTitle = "Review Complete"
        )
    }

    private fun runReviewTask(
        title: String,
        future: java.util.concurrent.Future<List<CodeHealthAnalyzer.MethodHealthResult>>,
        successTitle: String
    ) {
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = title
                    val results = future.get()
                    ApplicationManager.getApplication().invokeLater {
                        currentData = results
                        refreshUI()
                        Messages.showInfoMessage(
                            project,
                            "${results.size} methods processed\n${results.sumOf { it.issues.size }} issues found",
                            successTitle
                        )
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Operation failed: ${e.message}", "Error")
                    }
                }
            }
        })
    }

    private fun clearTracking() {
        val ok = Messages.showYesNoDialog(
            project,
            "This will clear all tracked methods and pending reviews.\nAre you sure?",
            "Clear Tracking Data",
            Messages.getQuestionIcon()
        )
        if (ok != Messages.YES) return
        ProjectChangesTracker.getInstance(project).clearAllTracking()
        refreshUI()
        Messages.showInfoMessage(project, "All tracking data has been cleared.", "Tracking Cleared")
    }

    private fun exportReport() {
        Messages.showInfoMessage(project, "Export functionality will be implemented in a future update.", "Feature Coming Soon")
    }

    data class SummaryMetrics(
        val overallScore: Int,
        val methodsAnalyzed: Int,
        val issuesFound: Int,
        val criticalIssues: Int
    )
}