package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * UI component that shows balloon notifications and detailed health reports
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
     * Dialog showing detailed health report
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

            // Create HTML content
            val htmlContent = buildHtmlReport()

            // Create HTML editor pane
            val editorPane = JEditorPane("text/html", htmlContent).apply {
                isEditable = false
                addHyperlinkListener { event ->
                    if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                        handleHyperlinkClick(event.description)
                    }
                }
            }

            // Wrap in scroll pane
            val scrollPane = JBScrollPane(editorPane).apply {
                preferredSize = Dimension(800, 600)
                border = JBUI.Borders.empty()
            }

            panel.add(scrollPane, BorderLayout.CENTER)

            return panel
        }

        private fun buildHtmlReport(): String {
            return buildString {
                append(
                    """
                    <html>
                    <head>
                        <style>
                            body { 
                                font-family: ${UIManager.getFont("Label.font").family}; 
                                font-size: 13px;
                                padding: 10px;
                            }
                            h1 { color: #2B65EC; margin-bottom: 10px; }
                            h2 { color: #333; margin-top: 20px; margin-bottom: 10px; }
                            .method { 
                                background: #f5f5f5; 
                                border: 1px solid #ddd;
                                border-radius: 4px;
                                padding: 10px;
                                margin-bottom: 15px;
                            }
                            .method-header {
                                font-weight: bold;
                                margin-bottom: 5px;
                            }
                            .health-score {
                                float: right;
                                padding: 2px 8px;
                                border-radius: 3px;
                                font-weight: bold;
                            }
                            .score-good { background: #4CAF50; color: white; }
                            .score-warning { background: #FF9800; color: white; }
                            .score-critical { background: #F44336; color: white; }
                            .issue {
                                margin: 5px 0;
                                padding: 5px;
                                background: #fff;
                                border-left: 3px solid #ddd;
                            }
                            .issue-critical { border-left-color: #F44336; }
                            .issue-warning { border-left-color: #FF9800; }
                            .issue-minor { border-left-color: #2196F3; }
                            .prompt-button {
                                background: #2196F3;
                                color: white;
                                text-decoration: none;
                                padding: 2px 6px;
                                border-radius: 3px;
                                font-size: 11px;
                                margin-left: 10px;
                            }
                            .impact {
                                margin-top: 5px;
                                font-size: 12px;
                                color: #666;
                            }
                            .summary {
                                background: #e3f2fd;
                                padding: 10px;
                                border-radius: 4px;
                                margin-bottom: 20px;
                            }
                        </style>
                    </head>
                    <body>
                """.trimIndent()
                )

                // Summary section
                val totalIssues = results.sumOf { it.issues.size }
                val criticalCount = results.sumOf { r -> r.issues.count { it.type.severity >= 3 } }
                val avgScore = results.map { it.healthScore }.average().toInt()

                append(
                    """
                    <h1>Code Health Report</h1>
                    <div class="summary">
                        <strong>Summary:</strong> ${results.size} methods analyzed<br>
                        <strong>Total Issues:</strong> $totalIssues 
                        (Critical: $criticalCount, 
                         Warning: ${results.sumOf { r -> r.issues.count { it.type.severity == 2 } }},
                         Minor: ${results.sumOf { r -> r.issues.count { it.type.severity == 1 } }})<br>
                        <strong>Average Health Score:</strong> $avgScore/100
                    </div>
                """.trimIndent()
                )

                // Methods with issues
                append("<h2>Methods Requiring Attention</h2>")

                results.sortedByDescending { it.healthScore * it.modificationCount }.forEach { result ->
                    val scoreClass = when {
                        result.healthScore >= 70 -> "score-good"
                        result.healthScore >= 40 -> "score-warning"
                        else -> "score-critical"
                    }

                    append(
                        """
                        <div class="method">
                            <div class="method-header">
                                ${result.fqn}
                                <span class="health-score $scoreClass">${result.healthScore}/100</span>
                            </div>
                            <div style="font-size: 12px; color: #666;">
                                Modified ${result.modificationCount} times today
                            </div>
                    """.trimIndent()
                    )

                    // Issues
                    if (result.issues.isNotEmpty()) {
                        result.issues.forEach { issue ->
                            val issueClass = when (issue.type.severity) {
                                3 -> "issue-critical"
                                2 -> "issue-warning"
                                else -> "issue-minor"
                            }

                            append(
                                """
                                <div class="issue $issueClass">
                                    <strong>${issue.type.displayName}:</strong> ${issue.description}
                                    <a href="copy:${issue.suggestedPrompt}" class="prompt-button">Copy Fix Prompt</a>
                                </div>
                            """.trimIndent()
                            )
                        }
                    }

                    // Impact
                    if (result.impactedCallers.isNotEmpty()) {
                        append(
                            """
                            <div class="impact">
                                <strong>Impact:</strong> Called by ${result.impactedCallers.size} methods
                                ${
                                if (result.impactedCallers.size <= 5) {
                                    "(${result.impactedCallers.joinToString(", ")})"
                                } else {
                                    "(${
                                        result.impactedCallers.take(3).joinToString(", ")
                                    } and ${result.impactedCallers.size - 3} more)"
                                }
                            }
                            </div>
                        """.trimIndent()
                        )
                    }

                    append("</div>")
                }

                // Actionable prompts section
                append("<h2>Suggested Actions</h2>")
                append("<div class='summary'>")

                // Group issues by type
                val issuesByType = results.flatMap { it.issues }
                    .groupBy { it.type }
                    .toList()
                    .sortedByDescending { (type, issues) -> type.severity * issues.size }

                issuesByType.forEach { (type, issues) ->
                    val combinedPrompt = "Fix ${issues.size} ${type.displayName} issues in modified methods:\n" +
                            issues.take(5).joinToString("\n") { "- ${it.description}" }

                    append(
                        """
                        <div style="margin: 5px 0;">
                            <strong>${type.displayName} (${issues.size} occurrences)</strong>
                            <a href="copy:$combinedPrompt" class="prompt-button">Copy Batch Fix Prompt</a>
                        </div>
                    """.trimIndent()
                    )
                }

                append("</div>")
                append("</body></html>")
            }
        }

        private fun handleHyperlinkClick(description: String) {
            if (description.startsWith("copy:")) {
                val textToCopy = description.substring(5)
                CopyPasteManager.getInstance().setContents(StringSelection(textToCopy))

                // Show feedback
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("Copied to clipboard", NotificationType.INFORMATION)
                    .notify(project)
            }
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
