package com.zps.zest.codehealth.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import java.awt.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.zps.zest.browser.utils.ChatboxUtilities
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * Panel for Code Guardian tool window with full and condensed modes
 */
class CodeGuardianReportPanel(
    private val project: Project
) : JPanel(BorderLayout()) {

    private val storage = CodeHealthReportStorage.getInstance(project)
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM dd")
    
    private var isCondensedMode = false
    private var mainContent: JComponent? = null
    private var todayResults: List<CodeHealthAnalyzer.MethodHealthResult>? = null
    
    // Width threshold for auto-switching to condensed mode
    private val CONDENSED_MODE_THRESHOLD = 400
    
    init {
        setupUI()
        setupAutoResizeListener()
    }
    
    fun updateResults(results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        todayResults = results
        refreshContent()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        add(createToolbar(), BorderLayout.NORTH)
        
        // Add main content
        refreshContent()
    }
    
    private fun setupAutoResizeListener() {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val shouldBeCondensed = width < CONDENSED_MODE_THRESHOLD && width > 0
                if (shouldBeCondensed != isCondensedMode) {
                    isCondensedMode = shouldBeCondensed
                    refreshContent()
                }
            }
        })
    }
    
    private fun createToolbar(): JComponent {
        val toolbar = JPanel(BorderLayout())
        toolbar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor()),
            EmptyBorder(5, 5, 5, 5)
        )
        
        // Title on the left
        val titleLabel = JBLabel("🛡️ Code Guardian")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        toolbar.add(titleLabel, BorderLayout.WEST)
        
        // Actions on the right
        val actionGroup = DefaultActionGroup()
        
        // Refresh action
        actionGroup.add(object : AnAction("Refresh", "Refresh health report", null) {
            override fun actionPerformed(e: AnActionEvent) {
                Messages.showInfoMessage(project, "Refreshing analysis...", "Code Guardian")
                // TODO: Trigger actual refresh
            }
        })
        
        // View mode toggle
        actionGroup.add(object : ToggleAction(
            if (isCondensedMode) "Expand View" else "Compact View",
            "Toggle between full and condensed view",
            null
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = !isCondensedMode
            
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                isCondensedMode = !state
                refreshContent()
            }
        })
        
        val actionToolbar = ActionManager.getInstance().createActionToolbar(
            "CodeGuardianToolbar",
            actionGroup,
            true
        )
        actionToolbar.targetComponent = this
        toolbar.add(actionToolbar.component, BorderLayout.EAST)
        
        return toolbar
    }
    
    private fun refreshContent() {
        // Remove old content
        mainContent?.let { remove(it) }
        
        // Create new content based on mode
        mainContent = if (isCondensedMode) {
            createCondensedContent()
        } else {
            createFullContent()
        }
        
        add(mainContent!!, BorderLayout.CENTER)
        revalidate()
        repaint()
    }
    
    private fun createFullContent(): JComponent {
        // Create tabbed pane for different days
        val tabbedPane = JBTabbedPane()
        
        // Today's tab
        val today = LocalDate.now()
        val todayData = todayResults ?: storage.getReportForDate(today)
        if (todayData != null) {
            tabbedPane.addTab("Today", createDayPanel(today, todayData, false))
        }
        
        // Yesterday's tab
        val yesterday = today.minusDays(1)
        val yesterdayData = storage.getReportForDate(yesterday)
        if (yesterdayData != null) {
            tabbedPane.addTab("Yesterday", createDayPanel(yesterday, yesterdayData, false))
        }
        
        // 2 days ago tab
        val twoDaysAgo = today.minusDays(2)
        val twoDaysAgoData = storage.getReportForDate(twoDaysAgo)
        if (twoDaysAgoData != null) {
            tabbedPane.addTab("2 days ago", createDayPanel(twoDaysAgo, twoDaysAgoData, false))
        }
        
        // If no data available
        if (tabbedPane.tabCount == 0) {
            return createEmptyPanel()
        }
        
        return tabbedPane
    }
    
    private fun createCondensedContent(): JComponent {
        val condensedPanel = JPanel(BorderLayout())
        
        // Show only today's data in condensed mode
        val today = LocalDate.now()
        val todayData = todayResults ?: storage.getReportForDate(today)
        
        if (todayData == null) {
            return createEmptyPanel()
        }
        
        return createDayPanel(today, todayData, true)
    }
    
    private fun createEmptyPanel(): JComponent {
        val emptyPanel = JPanel(GridBagLayout())
        emptyPanel.background = UIUtil.getPanelBackground()
        
        val label = JBLabel("No health reports available")
        label.foreground = UIUtil.getInactiveTextColor()
        
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        emptyPanel.add(label, gbc)
        
        gbc.gridy = 1
        gbc.insets = JBUI.insets(10, 0, 0, 0)
        val runButton = JButton("Run Analysis")
        runButton.addActionListener {
            // TODO: Trigger analysis
            Messages.showInfoMessage(project, "Starting analysis...", "Code Guardian")
        }
        emptyPanel.add(runButton, gbc)
        
        return emptyPanel
    }
    
    private fun createDayPanel(date: LocalDate, results: List<CodeHealthAnalyzer.MethodHealthResult>, condensed: Boolean): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        // Header with summary
        val headerPanel = if (condensed) createCondensedHeader(date, results) else createFullHeader(date, results)
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Issues list with proper scrolling
        val issuesPanel = if (condensed) createCondensedIssuesPanel(results) else createFullIssuesPanel(results)
        
        // Create scroll pane with vertical scrolling only
        val scrollPane = JBScrollPane(issuesPanel)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        
        // Ensure the scroll pane resizes properly
        scrollPane.preferredSize = null // Let it calculate based on parent
        
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createFullHeader(date: LocalDate, results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(20, 20, 20, 20)
        panel.background = UIUtil.getPanelBackground()
        
        // Date header
        val dateLabel = JBLabel(date.format(dateFormatter))
        dateLabel.font = dateLabel.font.deriveFont(Font.BOLD, 18f)
        dateLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(dateLabel)
        panel.add(Box.createVerticalStrut(10))
        
        // Summary stats
        val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val criticalCount = realIssues.count { it.severity >= 4 }
        val averageScore = if (results.isNotEmpty()) results.map { it.healthScore }.average().toInt() else 100
        
        val summaryPanel = createSummaryPanel(averageScore, results.size, realIssues.size, criticalCount, false)
        summaryPanel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(summaryPanel)
        
        return panel
    }
    
    private fun createCondensedHeader(date: LocalDate, results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        // Compact date
        val dateLabel = JBLabel(date.format(shortDateFormatter))
        dateLabel.font = dateLabel.font.deriveFont(Font.BOLD, 14f)
        dateLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(dateLabel)
        panel.add(Box.createVerticalStrut(5))
        
        // Compact summary
        val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val criticalCount = realIssues.count { it.severity >= 4 }
        val averageScore = if (results.isNotEmpty()) results.map { it.healthScore }.average().toInt() else 100
        
        val summaryPanel = createSummaryPanel(averageScore, results.size, realIssues.size, criticalCount, true)
        summaryPanel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(summaryPanel)
        
        return panel
    }
    
    private fun createSummaryPanel(score: Int, methodCount: Int, issueCount: Int, criticalCount: Int, condensed: Boolean): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(if (condensed) 10 else 15, if (condensed) 10 else 15, if (condensed) 10 else 15, if (condensed) 10 else 15)
        )
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(if (condensed) 2 else 5)
        
        if (condensed) {
            // Condensed layout - single row
            gbc.gridy = 0
            
            // Score
            gbc.gridx = 0
            val scoreLabel = JBLabel("$score/100")
            scoreLabel.font = scoreLabel.font.deriveFont(Font.BOLD, 16f)
            scoreLabel.foreground = getScoreColor(score)
            panel.add(scoreLabel, gbc)
            
            // Separator
            gbc.gridx = 1
            gbc.insets = JBUI.insets(0, 10)
            panel.add(JBLabel("|"), gbc)
            
            // Issues
            gbc.gridx = 2
            gbc.insets = JBUI.insets(if (condensed) 2 else 5)
            val issuesLabel = JBLabel("🎯 $issueCount")
            if (criticalCount > 0) {
                issuesLabel.text = "🎯 $issueCount (🚨 $criticalCount)"
                issuesLabel.foreground = Color(244, 67, 54)
            }
            panel.add(issuesLabel, gbc)
            
            // Methods
            gbc.gridx = 3
            gbc.weightx = 1.0
            val methodsLabel = JBLabel("| 🔍 $methodCount")
            methodsLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(methodsLabel, gbc)
            
        } else {
            // Full layout - as before
            gbc.weightx = 1.0
            
            // Health score
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.WEST
            panel.add(JBLabel("🏆 Overall Health Score:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.EAST
            val scoreLabel = JBLabel("$score/100")
            scoreLabel.font = scoreLabel.font.deriveFont(Font.BOLD, 24f)
            scoreLabel.foreground = getScoreColor(score)
            panel.add(scoreLabel, gbc)
            
            // Methods scanned
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.WEST
            panel.add(JBLabel("🔍 Methods Scanned:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.EAST
            panel.add(JBLabel(methodCount.toString()), gbc)
            
            // Issues found
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.WEST
            panel.add(JBLabel("🎯 Issues Found:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.EAST
            panel.add(JBLabel(issueCount.toString()), gbc)
            
            // Critical issues
            if (criticalCount > 0) {
                gbc.gridx = 0
                gbc.gridy = 3
                gbc.weightx = 0.0
                gbc.anchor = GridBagConstraints.WEST
                panel.add(JBLabel("🚨 Critical Issues:"), gbc)
                
                gbc.gridx = 1
                gbc.weightx = 1.0
                gbc.anchor = GridBagConstraints.EAST
                val criticalLabel = JBLabel(criticalCount.toString())
                criticalLabel.foreground = Color(244, 67, 54)
                panel.add(criticalLabel, gbc)
            }
        }
        
        return panel
    }
    
    private fun createFullIssuesPanel(results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.background = UIUtil.getPanelBackground()
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(0, 20, 20, 20)
        
        val methodsWithIssues = results.filter { 
            it.issues.any { issue -> issue.verified && !issue.falsePositive }
        }.sortedBy { it.healthScore }
        
        if (methodsWithIssues.isEmpty()) {
            val noIssuesLabel = JBLabel("✨ No issues found - your code is clean!")
            noIssuesLabel.font = noIssuesLabel.font.deriveFont(16f)
            panel.add(noIssuesLabel)
        } else {
            methodsWithIssues.forEach { result ->
                val methodPanel = createFullMethodPanel(result)
                panel.add(methodPanel)
                panel.add(Box.createVerticalStrut(20))
            }
        }
        
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }
    
    private fun createCondensedIssuesPanel(results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.background = UIUtil.getPanelBackground()
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(0, 10, 10, 10)
        
        val methodsWithIssues = results.filter { 
            it.issues.any { issue -> issue.verified && !issue.falsePositive }
        }.sortedBy { it.healthScore }
        
        if (methodsWithIssues.isEmpty()) {
            val noIssuesLabel = JBLabel("✨ Clean!")
            noIssuesLabel.font = noIssuesLabel.font.deriveFont(14f)
            panel.add(noIssuesLabel)
        } else {
            methodsWithIssues.forEach { result ->
                val methodPanel = createCondensedMethodPanel(result)
                panel.add(methodPanel)
                panel.add(Box.createVerticalStrut(10))
            }
        }
        
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }
    
    private fun createFullMethodPanel(result: CodeHealthAnalyzer.MethodHealthResult): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.background = UIUtil.getPanelBackground()
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, getHealthColor(result.healthScore)),
            EmptyBorder(15, 15, 15, 15)
        )
        panel.background = UIUtil.getPanelBackground()
        
        // Method header with navigation
        val headerPanel = JPanel(GridBagLayout())
        headerPanel.background = UIUtil.getPanelBackground()
        
        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        // Method label with text wrapping
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(0, 0, 0, 10)
        val methodText = JTextArea(result.fqn)
        methodText.isEditable = false
        methodText.isOpaque = false
        methodText.background = UIUtil.getPanelBackground()
        methodText.font = Font(Font.MONOSPACED, Font.BOLD, 14)
        methodText.lineWrap = true
        methodText.wrapStyleWord = false // Don't break in middle of method names
        headerPanel.add(methodText, gbc)
        
        // Button
        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.insets(0)
        val goToButton = JButton("📍 Go to method")
        goToButton.isContentAreaFilled = false
        goToButton.isBorderPainted = false
        goToButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        goToButton.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        goToButton.addActionListener {
            navigateToMethod(result.fqn)
        }
        headerPanel.add(goToButton, gbc)
        
        panel.add(headerPanel)
        panel.add(Box.createVerticalStrut(5))
        
        // Stats
        val statsWrapper = JPanel(BorderLayout())
        statsWrapper.background = UIUtil.getPanelBackground()
        val statsArea = JTextArea("Health: ${result.healthScore}/100 | Edits: ${result.modificationCount}x | Impact: ${result.impactedCallers.size} callers")
        statsArea.isEditable = false
        statsArea.isOpaque = false
        statsArea.background = UIUtil.getPanelBackground()
        statsArea.font = statsArea.font.deriveFont(12f)
        statsArea.foreground = UIUtil.getInactiveTextColor()
        statsArea.lineWrap = true
        statsArea.wrapStyleWord = true
        statsWrapper.add(statsArea, BorderLayout.CENTER)
        panel.add(statsWrapper)
        panel.add(Box.createVerticalStrut(15))
        
        // Issues
        val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
            .sortedByDescending { it.severity }
        
        if (verifiedIssues.isNotEmpty()) {
            val issue = verifiedIssues.first()
            val issuePanel = createFullIssuePanel(issue, result.fqn)
            panel.add(issuePanel)
        }
        
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }
    
    private fun createCondensedMethodPanel(result: CodeHealthAnalyzer.MethodHealthResult): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, getHealthColor(result.healthScore)),
            EmptyBorder(10, 10, 10, 10)
        )
        panel.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        
        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        // Method info - takes remaining space
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = panel.background
        
        // Method name with wrapping
        val methodArea = JTextArea(result.fqn)
        methodArea.isEditable = false
        methodArea.isOpaque = false
        methodArea.background = panel.background
        methodArea.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        methodArea.lineWrap = true
        methodArea.wrapStyleWord = false // Don't break method names
        methodArea.rows = 1 // Try to keep it single line if possible
        leftPanel.add(methodArea)
        
        // Compact issue info
        val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
        if (verifiedIssues.isNotEmpty()) {
            val issue = verifiedIssues.first()
            val issueLabel = JBLabel("[${issue.severity}/5] ${issue.title}")
            issueLabel.font = issueLabel.font.deriveFont(11f)
            issueLabel.foreground = getSeverityColor(issue.severity)
            leftPanel.add(issueLabel)
        }
        
        panel.add(leftPanel, gbc)
        
        // Right side - action buttons (fixed width)
        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.insets(0, 5, 0, 0)
        
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        rightPanel.background = panel.background
        
        val goButton = createSmallButton("📍", "Go to method") {
            navigateToMethod(result.fqn)
        }
        rightPanel.add(goButton)
        
        if (verifiedIssues.isNotEmpty()) {
            val fixButton = createSmallButton("🔧", "Fix with AI") {
                handleFixNowClick(result.fqn, verifiedIssues.first())
            }
            rightPanel.add(fixButton)
        }
        
        panel.add(rightPanel, gbc)
        
        return panel
    }
    
    private fun createSmallButton(text: String, tooltip: String, action: () -> Unit): JButton {
        val button = JButton(text)
        button.toolTipText = tooltip
        button.preferredSize = Dimension(30, 25)
        button.isContentAreaFilled = false
        button.isBorderPainted = true
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.addActionListener { action() }
        return button
    }
    
    private fun createFullIssuePanel(issue: CodeHealthAnalyzer.HealthIssue, methodFqn: String): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.background = UIUtil.getPanelBackground()
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        panel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        
        // Title with severity - use GridBagLayout for better responsive behavior
        val titlePanel = JPanel(GridBagLayout())
        titlePanel.background = panel.background
        
        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        // Title takes remaining space
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(0, 0, 0, 10)
        
        val titleArea = JTextArea(issue.title)
        titleArea.isEditable = false
        titleArea.isOpaque = false
        titleArea.background = panel.background
        titleArea.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        titleArea.lineWrap = true
        titleArea.wrapStyleWord = true
        titlePanel.add(titleArea, gbc)
        
        // Severity label - fixed width
        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.insets(0)
        val severityLabel = JBLabel("[Risk Level: ${issue.severity}/5]")
        severityLabel.foreground = getSeverityColor(issue.severity)
        severityLabel.font = severityLabel.font.deriveFont(Font.BOLD)
        titlePanel.add(severityLabel, gbc)
        
        panel.add(titlePanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Type
        val typeLabel = JBLabel("Type: ${issue.issueCategory}")
        panel.add(typeLabel)
        panel.add(Box.createVerticalStrut(10))
        
        // Description
        val descWrapper = JPanel(BorderLayout())
        descWrapper.background = panel.background
        val descArea = JTextArea(issue.description)
        descArea.isEditable = false
        descArea.isOpaque = false
        descArea.background = panel.background
        descArea.font = UIUtil.getLabelFont()
        descArea.lineWrap = true
        descArea.wrapStyleWord = true
        descWrapper.add(descArea, BorderLayout.CENTER)
        panel.add(descWrapper)
        panel.add(Box.createVerticalStrut(10))
        
        // Impact
        val impactPanel = createSectionPanel("What happens if unfixed:", issue.impact)
        panel.add(impactPanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Fix
        val fixPanel = createSectionPanel("How to fix:", issue.suggestedFix, 
            if (UIUtil.isUnderDarcula()) Color(45, 74, 43) else Color(232, 245, 233))
        panel.add(fixPanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = panel.background
        
        val fixButton = JButton("🔧 Fix now with AI")
        fixButton.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        fixButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        fixButton.addActionListener {
            handleFixNowClick(methodFqn, issue)
        }
        buttonPanel.add(fixButton)
        
        panel.add(buttonPanel)
        
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }
    
    private fun createSectionPanel(label: String, content: String, bgColor: Color? = null): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, UIUtil.getBoundsColor()),
            EmptyBorder(10, 10, 10, 10)
        )
        wrapper.background = bgColor ?: if (UIUtil.isUnderDarcula()) Color(30, 30, 30) else Color(248, 248, 248)
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = wrapper.background
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        panel.add(labelComponent)
        panel.add(Box.createVerticalStrut(5))
        
        // Use JTextArea for better text wrapping
        val contentArea = JTextArea(content)
        contentArea.isEditable = false
        contentArea.isOpaque = false
        contentArea.background = panel.background
        contentArea.font = UIUtil.getLabelFont()
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        
        panel.add(contentArea)
        
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }
    
    // Helper methods (same as in SwingHealthReportDialog)
    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> Color(76, 175, 80)
            score >= 60 -> Color(255, 152, 0)
            else -> Color(244, 67, 54)
        }
    }
    
    private fun getHealthColor(score: Int): Color {
        return when {
            score >= 80 -> Color(76, 175, 80)
            score >= 60 -> Color(255, 152, 0)
            else -> Color(244, 67, 54)
        }
    }
    
    private fun getSeverityColor(severity: Int): Color {
        return when (severity) {
            5, 4 -> Color(244, 67, 54)
            3 -> Color(255, 152, 0)
            else -> Color(76, 175, 80)
        }
    }
    
    // Navigation and fix methods (same as SwingHealthReportDialog)
    private fun navigateToMethod(fqn: String) {
        try {
            if (fqn.contains(".js:") || fqn.contains(".ts:") || 
                fqn.contains(".jsx:") || fqn.contains(".tsx:")) {
                navigateToJsTsLocation(fqn)
            } else {
                navigateToJavaMethod(fqn)
            }
        } catch (e: Exception) {
            Messages.showMessageDialog(project, "Unable to navigate to: $fqn", "Navigation Error", Messages.getWarningIcon())
        }
    }
    
    private fun navigateToJavaMethod(fqn: String) {
        val parts = fqn.split(".")
        if (parts.size < 2) return
        
        val className = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
        
        if (psiClass == null) {
            Messages.showMessageDialog(project, "Class not found: $className", "Navigation Error", Messages.getWarningIcon())
            return
        }
        
        val psiMethod = psiClass.methods.find { it.name == methodName }
        
        if (psiMethod == null) {
            navigateToElement(psiClass.containingFile.virtualFile, psiClass.textOffset)
            return
        }
        
        navigateToElement(psiMethod.containingFile.virtualFile, psiMethod.textOffset)
    }
    
    private fun navigateToJsTsLocation(fqn: String) {
        val colonIndex = fqn.lastIndexOf(':')
        if (colonIndex == -1) return
        
        val filePath = fqn.substring(0, colonIndex)
        val lineInfo = fqn.substring(colonIndex + 1)
        
        val lineNumber = if (lineInfo.contains('-')) {
            lineInfo.substringBefore('-').toIntOrNull() ?: return
        } else {
            lineInfo.toIntOrNull() ?: return
        }
        
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(filePath)
        
        if (virtualFile == null) {
            Messages.showMessageDialog(project, "File not found: $filePath", "Navigation Error", Messages.getWarningIcon())
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(
                project,
                virtualFile,
                lineNumber - 1,
                0
            )
            
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            
            if (lineInfo.contains('-') && editor != null) {
                val endLine = lineInfo.substringAfter('-').toIntOrNull()
                if (endLine != null && endLine > lineNumber) {
                    val lineStartOffset = editor.document.getLineStartOffset(lineNumber - 1)
                    editor.caretModel.moveToOffset(lineStartOffset)
                    editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
            }
        }
    }
    
    private fun navigateToElement(virtualFile: com.intellij.openapi.vfs.VirtualFile?, offset: Int) {
        if (virtualFile == null) return
        
        val descriptor = OpenFileDescriptor(
            project,
            virtualFile,
            offset
        )
        
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
    
    private fun handleFixNowClick(methodFqn: String, issue: CodeHealthAnalyzer.HealthIssue) {
        val fixPrompt = generateFixPrompt(methodFqn, issue)
        sendPromptToChatBox(fixPrompt, methodFqn, issue)
    }
    
    private fun generateFixPrompt(methodFqn: String, issue: CodeHealthAnalyzer.HealthIssue): String {
        val isJsTsFile = methodFqn.contains(".js:") || methodFqn.contains(".ts:") || 
                       methodFqn.contains(".jsx:") || methodFqn.contains(".tsx:")
        
        val language = when {
            methodFqn.contains(".ts:") || methodFqn.contains(".tsx:") -> "typescript"
            methodFqn.contains(".js:") || methodFqn.contains(".jsx:") -> "javascript"
            else -> "java"
        }
        
        return buildString {
            appendLine("Please help me fix this code issue detected by Code Guardian:")
            appendLine()
            
            if (isJsTsFile) {
                appendLine("**File:** `${methodFqn.substringBefore(":")}`")
                val lineRange = methodFqn.substringAfter(":")
                appendLine("**Location:** Lines $lineRange")
            } else {
                appendLine("**Method:** `$methodFqn`")
            }
            
            appendLine()
            appendLine("**Issue Type:** ${issue.issueCategory}")
            appendLine("**Severity:** ${issue.severity}/5")
            appendLine("**Title:** ${issue.title}")
            appendLine()
            appendLine("**Description:** ${issue.description}")
            appendLine()
            appendLine("**Impact if not fixed:** ${issue.impact}")
            appendLine()
            appendLine("**Suggested fix:** ${issue.suggestedFix}")
            appendLine()
            appendLine("Please provide the fixed code with explanations of the changes made.")
        }
    }
    
    private fun sendPromptToChatBox(prompt: String, methodFqn: String, issue: CodeHealthAnalyzer.HealthIssue) {
        val language = when {
            methodFqn.contains(".ts:") || methodFqn.contains(".tsx:") -> "TypeScript"
            methodFqn.contains(".js:") || methodFqn.contains(".jsx:") -> "JavaScript"
            else -> "Java"
        }
        
        val systemPrompt = "You are a helpful AI assistant that fixes $language code issues. " +
                          "Focus on the specific issue described and provide clear, working code as the solution. " +
                          "Explain what changes you made and why they fix the issue."
        
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat")
        if (toolWindow != null) {
            ApplicationManager.getApplication().invokeLater {
                toolWindow.activate {
                    ChatboxUtilities.clickNewChatButton(project)
                    ChatboxUtilities.sendTextAndSubmit(
                        project, 
                        prompt, 
                        true, 
                        systemPrompt,
                        false, 
                        ChatboxUtilities.EnumUsage.CODE_HEALTH
                    )
                }
            }
        } else {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Guardian")
                .createNotification(
                    "Unable to open chat",
                    "Please open the ZPS Chat tool window first",
                    NotificationType.WARNING
                )
                .notify(project)
        }
    }
}
