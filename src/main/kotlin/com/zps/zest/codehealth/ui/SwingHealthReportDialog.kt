package com.zps.zest.codehealth.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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

/**
 * Pure Swing implementation of the health report dialog with 3-day history
 */
class SwingHealthReportDialog(
    private val project: Project,
    private val todayResults: List<CodeHealthAnalyzer.MethodHealthResult>? = null
) : DialogWrapper(project) {

    private val storage = CodeHealthReportStorage.getInstance(project)
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    
    init {
        title = "🛡️ Code Guardian Health Report"
        setOKButtonText("Close")
        isModal = false  // Make it non-modal
        init()
    }
    
    override fun show() {
        super.show()
        // Make the window stay on top (optional)
        window?.isAlwaysOnTop = true
    }
    
    override fun doCancelAction() {
        // Ask before closing if always on top
        if (window?.isAlwaysOnTop == true) {
            val result = Messages.showYesNoDialog(
                project,
                "Close Code Guardian Report?",
                "Confirm Close",
                Messages.getQuestionIcon()
            )
            if (result == Messages.YES) {
                super.doCancelAction()
            }
        } else {
            super.doCancelAction()
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        
        // Create tabbed pane for different days
        val tabbedPane = JBTabbedPane()
        
        // Today's tab
        val today = LocalDate.now()
        val todayData = todayResults ?: storage.getReportForDate(today)
        if (todayData != null) {
            tabbedPane.addTab("Today", createDayPanel(today, todayData))
        }
        
        // Yesterday's tab
        val yesterday = today.minusDays(1)
        val yesterdayData = storage.getReportForDate(yesterday)
        if (yesterdayData != null) {
            tabbedPane.addTab("Yesterday", createDayPanel(yesterday, yesterdayData))
        }
        
        // 2 days ago tab
        val twoDaysAgo = today.minusDays(2)
        val twoDaysAgoData = storage.getReportForDate(twoDaysAgo)
        if (twoDaysAgoData != null) {
            tabbedPane.addTab("2 days ago", createDayPanel(twoDaysAgo, twoDaysAgoData))
        }
        
        // If no data available
        if (tabbedPane.tabCount == 0) {
            val emptyPanel = JPanel(BorderLayout())
            val emptyLabel = JBLabel("No health reports available. Run Code Guardian to generate a report.")
            emptyLabel.horizontalAlignment = SwingConstants.CENTER
            emptyPanel.add(emptyLabel, BorderLayout.CENTER)
            mainPanel.add(emptyPanel, BorderLayout.CENTER)
        } else {
            mainPanel.add(tabbedPane, BorderLayout.CENTER)
        }
        
        // Add bottom toolbar
        val toolbar = createToolbar()
        mainPanel.add(toolbar, BorderLayout.SOUTH)
        
        mainPanel.preferredSize = Dimension(900, 700)
        return mainPanel
    }
    
    private fun createDayPanel(date: LocalDate, results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        // Header with summary
        val headerPanel = createHeaderPanel(date, results)
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Issues list
        val issuesPanel = createIssuesPanel(results)
        val scrollPane = JBScrollPane(issuesPanel)
        scrollPane.border = JBUI.Borders.empty()
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createHeaderPanel(date: LocalDate, results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
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
        
        val summaryPanel = JPanel(GridBagLayout())
        summaryPanel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        summaryPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        summaryPanel.alignmentX = Component.LEFT_ALIGNMENT
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(5)
        
        // Health score
        gbc.gridx = 0
        gbc.gridy = 0
        summaryPanel.add(JBLabel("🏆 Overall Health Score:"), gbc)
        
        gbc.gridx = 1
        val scoreLabel = JBLabel("$averageScore/100")
        scoreLabel.font = scoreLabel.font.deriveFont(Font.BOLD, 24f)
        scoreLabel.foreground = getScoreColor(averageScore)
        summaryPanel.add(scoreLabel, gbc)
        
        // Methods scanned
        gbc.gridx = 0
        gbc.gridy = 1
        summaryPanel.add(JBLabel("🔍 Methods Scanned:"), gbc)
        
        gbc.gridx = 1
        summaryPanel.add(JBLabel(results.size.toString()), gbc)
        
        // Issues found
        gbc.gridx = 0
        gbc.gridy = 2
        summaryPanel.add(JBLabel("🎯 Issues Found:"), gbc)
        
        gbc.gridx = 1
        summaryPanel.add(JBLabel(realIssues.size.toString()), gbc)
        
        // Critical issues
        if (criticalCount > 0) {
            gbc.gridx = 0
            gbc.gridy = 3
            summaryPanel.add(JBLabel("🚨 Critical Issues:"), gbc)
            
            gbc.gridx = 1
            val criticalLabel = JBLabel(criticalCount.toString())
            criticalLabel.foreground = Color(244, 67, 54)
            summaryPanel.add(criticalLabel, gbc)
        }
        
        panel.add(summaryPanel)
        
        return panel
    }
    
    private fun createIssuesPanel(results: List<CodeHealthAnalyzer.MethodHealthResult>): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(0, 20, 20, 20)
        
        // Filter to only show methods with issues
        val methodsWithIssues = results.filter { 
            it.issues.any { issue -> issue.verified && !issue.falsePositive }
        }.sortedBy { it.healthScore }
        
        if (methodsWithIssues.isEmpty()) {
            val noIssuesLabel = JBLabel("✨ No issues found - your code is clean!")
            noIssuesLabel.font = noIssuesLabel.font.deriveFont(16f)
            noIssuesLabel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(noIssuesLabel)
        } else {
            methodsWithIssues.forEach { result ->
                val methodPanel = createMethodPanel(result)
                methodPanel.alignmentX = Component.LEFT_ALIGNMENT
                panel.add(methodPanel)
                panel.add(Box.createVerticalStrut(20))
            }
        }
        
        // Add spacer at bottom
        panel.add(Box.createVerticalGlue())
        
        return panel
    }
    
    private fun createMethodPanel(result: CodeHealthAnalyzer.MethodHealthResult): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, getHealthColor(result.healthScore)),
            EmptyBorder(15, 15, 15, 15)
        )
        panel.background = UIUtil.getPanelBackground()
        
        // Method header with navigation - using GridBagLayout for better control
        val headerPanel = JPanel(GridBagLayout())
        headerPanel.background = UIUtil.getPanelBackground()
        headerPanel.alignmentX = Component.LEFT_ALIGNMENT
        
        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        // Method label - takes up available space
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST
        val methodLabel = JBLabel(result.fqn)
        methodLabel.font = Font(Font.MONOSPACED, Font.BOLD, 14)
        headerPanel.add(methodLabel, gbc)
        
        // Button - fixed size
        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.insets(0, 10, 0, 0)
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
        
        // Method stats
        val statsLabel = JBLabel("Health: ${result.healthScore}/100 | Edits: ${result.modificationCount}x | Impact: ${result.impactedCallers.size} callers")
        statsLabel.font = statsLabel.font.deriveFont(12f)
        statsLabel.foreground = UIUtil.getInactiveTextColor()
        statsLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(statsLabel)
        panel.add(Box.createVerticalStrut(15))
        
        // Issues
        val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
            .sortedByDescending { it.severity }
        
        // Only show the first (most critical) issue
        if (verifiedIssues.isNotEmpty()) {
            val issue = verifiedIssues.first()
            val issuePanel = createIssuePanel(issue, result.fqn)
            issuePanel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(issuePanel)
        }
        
        // Set maximum size AFTER all components are added
        panel.maximumSize = Dimension(Integer.MAX_VALUE, panel.preferredSize.height)
        
        return panel
    }
    
    private fun createIssuePanel(issue: CodeHealthAnalyzer.HealthIssue, methodFqn: String): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        panel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        
        // Issue title with severity
        val titlePanel = JPanel(BorderLayout())
        titlePanel.background = panel.background
        titlePanel.alignmentX = Component.LEFT_ALIGNMENT
        
        val titleLabel = JBLabel(issue.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titlePanel.add(titleLabel, BorderLayout.WEST)
        
        val severityLabel = JBLabel("[Risk Level: ${issue.severity}/5]")
        severityLabel.foreground = getSeverityColor(issue.severity)
        severityLabel.font = severityLabel.font.deriveFont(Font.BOLD)
        titlePanel.add(severityLabel, BorderLayout.EAST)
        
        panel.add(titlePanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Issue type
        val typeLabel = JBLabel("Type: ${issue.issueCategory}")
        typeLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(typeLabel)
        panel.add(Box.createVerticalStrut(10))
        
        // Description - with width constraint
        val descLabel = JBLabel("<html><body style='width: 550px'>${issue.description}</body></html>")
        descLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(descLabel)
        panel.add(Box.createVerticalStrut(10))
        
        // Impact section
        val impactPanel = createSectionPanel("What happens if unfixed:", issue.impact)
        impactPanel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(impactPanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Fix section
        val fixPanel = createSectionPanel("How to fix:", issue.suggestedFix, 
            if (UIUtil.isUnderDarcula()) Color(45, 74, 43) else Color(232, 245, 233))
        fixPanel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(fixPanel)
        panel.add(Box.createVerticalStrut(10))
        
        // Fix now button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = panel.background
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT
        buttonPanel.maximumSize = Dimension(Integer.MAX_VALUE, buttonPanel.preferredSize.height)
        
        val fixButton = JButton("🔧 Fix now with AI")
        fixButton.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        fixButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        fixButton.addActionListener {
            handleFixNowClick(methodFqn, issue)
        }
        buttonPanel.add(fixButton)
        
        panel.add(buttonPanel)
        
        // Set maximum size after all components are added
        panel.maximumSize = Dimension(Integer.MAX_VALUE, panel.preferredSize.height)
        
        return panel
    }
    
    private fun createSectionPanel(label: String, content: String, bgColor: Color? = null): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, UIUtil.getBoundsColor()),
            EmptyBorder(10, 10, 10, 10)
        )
        panel.background = bgColor ?: if (UIUtil.isUnderDarcula()) Color(30, 30, 30) else Color(248, 248, 248)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        labelComponent.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(labelComponent)
        panel.add(Box.createVerticalStrut(5))
        
        // Use HTML with width constraint for proper wrapping
        val contentLabel = JBLabel("<html><body style='width: 520px'>${content}</body></html>")
        contentLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(contentLabel)
        
        // Constrain panel width after adding components
        panel.maximumSize = Dimension(Integer.MAX_VALUE, panel.preferredSize.height)
        
        return panel
    }
    
    private fun createToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.CENTER))
        toolbar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIUtil.getBoundsColor()),
            EmptyBorder(10, 10, 10, 10)
        )
        
        // Pin button
        val pinButton = JToggleButton("📌 Pin")
        pinButton.addActionListener {
            window?.isAlwaysOnTop = pinButton.isSelected
            pinButton.text = if (pinButton.isSelected) "📌 Unpin" else "📌 Pin"
        }
        toolbar.add(pinButton)
        
        toolbar.add(Box.createHorizontalStrut(20))
        
        val refreshButton = JButton("🔄 Refresh")
        refreshButton.addActionListener {
            // TODO: Trigger new analysis
            Messages.showInfoMessage(project, "Refreshing analysis...", "Code Guardian")
        }
        toolbar.add(refreshButton)
        
        val exportButton = JButton("📤 Export")
        exportButton.addActionListener {
            // TODO: Export functionality
            Messages.showInfoMessage(project, "Export feature coming soon!", "Code Guardian")
        }
        toolbar.add(exportButton)
        
        val settingsButton = JButton("⚙️ Settings")
        settingsButton.addActionListener {
            // TODO: Open settings
            Messages.showInfoMessage(project, "Settings coming soon!", "Code Guardian")
        }
        toolbar.add(settingsButton)
        
        return toolbar
    }
    
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
    
    private fun navigateToMethod(fqn: String) {
        try {
            // Check if it's a JS/TS file (format: filename.js:lineNumber)
            if (fqn.contains(".js:") || fqn.contains(".ts:") || 
                fqn.contains(".jsx:") || fqn.contains(".tsx:")) {
                navigateToJsTsLocation(fqn)
            } else {
                // Java method navigation
                navigateToJavaMethod(fqn)
            }
            close(OK_EXIT_CODE)
        } catch (e: Exception) {
            Messages.showMessageDialog(project, "Unable to navigate to: $fqn", "Navigation Error", Messages.getWarningIcon())
        }
    }
    
    private fun navigateToJavaMethod(fqn: String) {
        val parts = fqn.split(".")
        if (parts.size < 2) return
        
        val className = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        
        // Find the class
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
        
        if (psiClass == null) {
            Messages.showMessageDialog(project, "Class not found: $className", "Navigation Error", Messages.getWarningIcon())
            return
        }
        
        // Find the method
        val psiMethod = psiClass.methods.find { it.name == methodName }
        
        if (psiMethod == null) {
            // Navigate to class if method not found
            navigateToElement(psiClass.containingFile.virtualFile, psiClass.textOffset)
            return
        }
        
        // Navigate to the method
        navigateToElement(psiMethod.containingFile.virtualFile, psiMethod.textOffset)
    }
    
    private fun navigateToJsTsLocation(fqn: String) {
        // Format can be: 
        // - filename.js:lineNumber (single line)
        // - filename.js:startLine-endLine (range)
        val colonIndex = fqn.lastIndexOf(':')
        if (colonIndex == -1) return
        
        val filePath = fqn.substring(0, colonIndex)
        val lineInfo = fqn.substring(colonIndex + 1)
        
        // Parse line number(s)
        val lineNumber = if (lineInfo.contains('-')) {
            // For ranges, navigate to the start line
            lineInfo.substringBefore('-').toIntOrNull() ?: return
        } else {
            lineInfo.toIntOrNull() ?: return
        }
        
        // Find the file
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(filePath)
        
        if (virtualFile == null) {
            Messages.showMessageDialog(project, "File not found: $filePath", "Navigation Error", Messages.getWarningIcon())
            return
        }
        
        // Navigate to the specific line
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(
                project,
                virtualFile,
                lineNumber - 1, // Convert to 0-based
                0
            )
            
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            
            // If it's a range, optionally highlight the full range
            if (lineInfo.contains('-') && editor != null) {
                val endLine = lineInfo.substringAfter('-').toIntOrNull()
                if (endLine != null && endLine > lineNumber) {
                    // Scroll to make the line visible in the center
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
        // Generate fix prompt
        val fixPrompt = generateFixPrompt(methodFqn, issue)
        
        // Send to chat box
        sendPromptToChatBox(fixPrompt, methodFqn, issue)
        
        // Close the dialog
        close(OK_EXIT_CODE)
    }
    
    private fun generateFixPrompt(methodFqn: String, issue: CodeHealthAnalyzer.HealthIssue): String {
        // Determine file type from FQN
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
                    // Click new chat button first
                    ChatboxUtilities.clickNewChatButton(project)
                    
                    // Send the prompt and submit
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
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
