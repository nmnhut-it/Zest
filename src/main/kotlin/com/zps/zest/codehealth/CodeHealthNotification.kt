package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Alternative implementation using simpler UI components to avoid HTML parsing issues
 */
object CodeHealthNotification {

    private const val NOTIFICATION_GROUP_ID = "Zest Code Health"

    fun showHealthReport(project: Project, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        if (results.isEmpty()) return

        val totalIssues = results.sumOf { it.issues.size }
        val criticalIssues = results.sumOf { result ->
            result.issues.count { it.type.severity >= 3 }
        }
        val averageScore = results.map { it.healthScore }.average().toInt()

        val title = "Code Health Check Complete"
        val content = buildString {
            append("Found $totalIssues issues")
            if (criticalIssues > 0) {
                append(" ($criticalIssues critical)")
            }
            append(" • Average health: $averageScore/100")
        }

        // Create notification with action to show details
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.WARNING)
            .addAction(object : AnAction("View Details") {
                override fun actionPerformed(e: AnActionEvent) {
                    showDetailedReport(project, results)
                }
            })

        notification.notify(project)
    }

    private fun showDetailedReport(project: Project, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        val dialog = HealthReportDialog(project, results)
        dialog.show()
    }

    /**
     * Dialog showing detailed health report using simpler components
     */
    private class HealthReportDialog(
        private val project: Project,
        private val results: List<CodeHealthAnalyzer.MethodHealthResult>
    ) : DialogWrapper(project) {

        init {
            title = "Code Health Report"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            // Create tabbed pane
            val tabbedPane = JTabbedPane()
            
            // Summary tab
            tabbedPane.addTab("Summary", createSummaryPanel())
            
            // Issues tab
            tabbedPane.addTab("Issues by Method", createIssuesPanel())
            
            // Actions tab
            tabbedPane.addTab("Suggested Actions", createActionsPanel())
            
            panel.add(tabbedPane, BorderLayout.CENTER)
            panel.preferredSize = Dimension(800, 600)
            
            return panel
        }

        private fun createSummaryPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            val totalIssues = results.sumOf { it.issues.size }
            val criticalCount = results.sumOf { r -> r.issues.count { it.type.severity >= 3 } }
            val warningCount = results.sumOf { r -> r.issues.count { it.type.severity == 2 } }
            val minorCount = results.sumOf { r -> r.issues.count { it.type.severity == 1 } }
            val avgScore = results.map { it.healthScore }.average().toInt()
            
            val summaryText = """
                CODE HEALTH REPORT SUMMARY
                ==========================
                
                Methods Analyzed: ${results.size}
                Total Issues Found: $totalIssues
                
                Issue Breakdown:
                • Critical Issues: $criticalCount
                • Warnings: $warningCount  
                • Minor Issues: $minorCount
                
                Average Health Score: $avgScore/100
                
                Top 5 Most Problematic Methods:
                ${results.sortedBy { it.healthScore }.take(5).joinToString("\n") { 
                    "• ${it.fqn} (Score: ${it.healthScore}/100, Modified: ${it.modificationCount}x)"
                }}
            """.trimIndent()
            
            val textArea = JBTextArea(summaryText).apply {
                isEditable = false
                font = UIUtil.getLabelFont()
                border = JBUI.Borders.empty(10)
            }
            
            panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
            return panel
        }

        private fun createIssuesPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            val issuesText = buildString {
                results.sortedByDescending { it.healthScore * it.modificationCount }.forEach { result ->
                    appendLine("═".repeat(60))
                    appendLine("METHOD: ${result.fqn}")
                    appendLine("Health Score: ${result.healthScore}/100 | Modified: ${result.modificationCount} times")
                    
                    if (result.issues.isNotEmpty()) {
                        appendLine("\nISSUES:")
                        result.issues.forEach { issue ->
                            val severity = when (issue.type.severity) {
                                3 -> "[CRITICAL]"
                                2 -> "[WARNING]"
                                else -> "[MINOR]"
                            }
                            appendLine("  $severity ${issue.type.displayName}")
                            appendLine("    ${issue.description}")
                            appendLine("    Fix: ${issue.suggestedPrompt}")
                            appendLine()
                        }
                    }
                    
                    if (result.impactedCallers.isNotEmpty()) {
                        appendLine("IMPACT: Called by ${result.impactedCallers.size} methods")
                        if (result.impactedCallers.size <= 10) {
                            result.impactedCallers.forEach {
                                appendLine("  • $it")
                            }
                        } else {
                            result.impactedCallers.take(5).forEach {
                                appendLine("  • $it")
                            }
                            appendLine("  ... and ${result.impactedCallers.size - 5} more")
                        }
                    }
                    appendLine()
                }
            }
            
            val textArea = JBTextArea(issuesText).apply {
                isEditable = false
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                border = JBUI.Borders.empty(10)
            }
            
            panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
            
            // Add copy button panel
            val buttonPanel = JPanel().apply {
                add(JButton("Copy Report").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(issuesText))
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(NOTIFICATION_GROUP_ID)
                            .createNotification("Report copied to clipboard", NotificationType.INFORMATION)
                            .notify(project)
                    }
                })
            }
            panel.add(buttonPanel, BorderLayout.SOUTH)
            
            return panel
        }

        private fun createActionsPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            val actionsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(10)
            }
            
            // Group issues by type
            val issuesByType = results.flatMap { it.issues }
                .groupBy { it.type }
                .toList()
                .sortedByDescending { (type, issues) -> type.severity * issues.size }
            
            issuesByType.forEach { (type, issues) ->
                val actionPanel = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(5)
                    background = UIUtil.getPanelBackground()
                }
                
                val label = JLabel("${type.displayName} (${issues.size} occurrences)").apply {
                    font = font.deriveFont(font.style or java.awt.Font.BOLD)
                }
                actionPanel.add(label, BorderLayout.WEST)
                
                val combinedPrompt = buildString {
                    appendLine("Fix ${issues.size} ${type.displayName} issues in modified methods:")
                    issues.take(10).forEach { issue ->
                        appendLine("- ${issue.description}")
                        appendLine("  Suggested fix: ${issue.suggestedPrompt}")
                    }
                    if (issues.size > 10) {
                        appendLine("... and ${issues.size - 10} more similar issues")
                    }
                }
                
                val copyButton = JButton("Copy Fix Prompt").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(combinedPrompt))
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(NOTIFICATION_GROUP_ID)
                            .createNotification("Fix prompt copied to clipboard", NotificationType.INFORMATION)
                            .notify(project)
                    }
                }
                actionPanel.add(copyButton, BorderLayout.EAST)
                
                actionsPanel.add(actionPanel)
                actionsPanel.add(Box.createVerticalStrut(5))
            }
            
            panel.add(JBScrollPane(actionsPanel), BorderLayout.CENTER)
            return panel
        }

        override fun createActions(): Array<Action> {
            return arrayOf(
                object : DialogWrapperAction("Run Analysis Again") {
                    override fun doAction(e: ActionEvent?) {
                        CodeHealthTracker.getInstance(project).checkAndNotify()
                        close(OK_EXIT_CODE)
                    }
                },
                okAction
            )
        }
    }
}
