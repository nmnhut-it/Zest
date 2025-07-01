package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Code Health notification system with HTML/CSS-based UI
 */
object CodeHealthNotification {

    private const val NOTIFICATION_GROUP_ID = "Zest Code Health"

    fun showHealthReport(project: Project, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        if (results.isEmpty()) {
            println("[CodeHealthNotification] No results to show")
            return
        }

        val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val totalIssues = realIssues.size
        val criticalIssues = realIssues.count { it.severity >= 4 }
        val highIssues = realIssues.count { it.severity == 3 }
        val averageScore = results.map { it.healthScore }.average().toInt()

        println("[CodeHealthNotification] Showing report: ${results.size} methods, $totalIssues verified issues")

        val title = "Code Health Check Complete"
        val content = buildString {
            append("Analyzed ${results.size} methods • ")
            append("Found $totalIssues verified issues")
            if (criticalIssues > 0) {
                append(" ($criticalIssues critical, $highIssues high)")
            }
            append(" • Average health: $averageScore/100")
        }

        // Create notification with action to show details
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, getNotificationType(averageScore))
            .addAction(object : AnAction("View Details") {
                override fun actionPerformed(e: AnActionEvent) {
                    println("[CodeHealthNotification] User clicked View Details")
                    showDetailedReport(project, results)
                }
            })

        notification.notify(project)
    }

    private fun getNotificationType(healthScore: Int): NotificationType {
        return when {
            healthScore >= 80 -> NotificationType.INFORMATION
            healthScore >= 60 -> NotificationType.WARNING
            else -> NotificationType.ERROR
        }
    }

    private fun showDetailedReport(project: Project, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        // Create and show dialog on EDT
        val dialog = HealthReportDialog(project, results)
        dialog.show()
    }

    /**
     * Dialog showing detailed health report with HTML/CSS formatting
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
            
            // Generate HTML content directly (synchronously)
            val htmlContent = try {
                generateHtmlReport()
            } catch (e: Exception) {
                println("[CodeHealthNotification] Error generating HTML: ${e.message}")
                e.printStackTrace()
                "<html><body><h1>Error generating report</h1><p>${e.message}</p></body></html>"
            }
            
            // Use JEditorPane for HTML rendering
            val editorPane = JEditorPane().apply {
                contentType = "text/html"
                text = htmlContent
                isEditable = false
                caretPosition = 0
            }
            
            val scrollPane = JBScrollPane(editorPane).apply {
                preferredSize = Dimension(900, 700)
            }
            
            panel.add(scrollPane, BorderLayout.CENTER)
            
            // Add action buttons at bottom
            val buttonPanel = JPanel().apply {
                add(JButton("Copy Report as Text").apply {
                    addActionListener {
                        val textReport = generateTextReport()
                        CopyPasteManager.getInstance().setContents(StringSelection(textReport))
                        showCopiedNotification()
                    }
                })
                add(JButton("Copy as Markdown").apply {
                    addActionListener {
                        val markdownReport = generateMarkdownReport()
                        CopyPasteManager.getInstance().setContents(StringSelection(markdownReport))
                        showCopiedNotification()
                    }
                })
            }
            panel.add(buttonPanel, BorderLayout.SOUTH)
            
            return panel
        }

        private fun generateHtmlReport(): String {
            val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
            val falsePositives = results.flatMap { it.issues }.filter { it.falsePositive }
            val totalMethods = results.size
            val averageScore = if (results.isNotEmpty()) results.map { it.healthScore }.average().toInt() else 100
            
            // Group issues by category
            val issuesByCategory = realIssues.groupBy { it.issueCategory }
                .toList()
                .sortedByDescending { (_, issues) -> issues.sumOf { it.severity } }

            return """
                <html>
                <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        color: #333;
                        background: #f5f5f5;
                        margin: 20px;
                    }
                    
                    h1 {
                        color: #2c3e50;
                        border-bottom: 3px solid #3498db;
                        padding-bottom: 10px;
                    }
                    
                    h2 {
                        color: #34495e;
                        margin-top: 30px;
                    }
                    
                    h3 {
                        color: #7f8c8d;
                        margin: 10px 0;
                    }
                    
                    .summary-card {
                        background: #f8f9fa;
                        padding: 20px;
                        border: 1px solid #e9ecef;
                        margin: 10px;
                        text-align: center;
                        width: 150px;
                        float: left;
                    }
                    
                    .summary-value {
                        font-size: 36px;
                        font-weight: bold;
                        margin: 10px 0;
                    }
                    
                    .health-score {
                        font-size: 24px;
                        font-weight: bold;
                        color: white;
                        padding: 20px;
                        margin: 10px 0;
                    }
                    
                    .health-good { background: #27ae60; }
                    .health-warning { background: #f39c12; }
                    .health-critical { background: #e74c3c; }
                    
                    .issue-card {
                        background: #fff;
                        border: 1px solid #e0e0e0;
                        padding: 20px;
                        margin-bottom: 20px;
                    }
                    
                    .issue-title {
                        font-size: 18px;
                        font-weight: bold;
                        margin: 0 0 10px 0;
                    }
                    
                    .severity-badge {
                        background: #e0e0e0;
                        padding: 4px 12px;
                        font-size: 12px;
                        font-weight: bold;
                        float: right;
                    }
                    
                    .severity-1 { background: #e3f2fd; color: #1976d2; }
                    .severity-2 { background: #fff3e0; color: #f57c00; }
                    .severity-3 { background: #fbe9e7; color: #d84315; }
                    .severity-4 { background: #ffebee; color: #c62828; }
                    .severity-5 { background: #f3e5f5; color: #6a1b9a; }
                    
                    .method-section {
                        background: #f5f5f5;
                        border-left: 4px solid #3498db;
                        padding: 20px;
                        margin-bottom: 30px;
                    }
                    
                    .method-name {
                        font-family: monospace;
                        font-size: 16px;
                        color: #2c3e50;
                        margin-bottom: 10px;
                    }
                    
                    .impact-box {
                        background: #fff8dc;
                        border-left: 4px solid #ff9800;
                        padding: 15px;
                        margin: 15px 0;
                    }
                    
                    .fix-box {
                        background: #e8f5e9;
                        border-left: 4px solid #4caf50;
                        padding: 15px;
                        margin: 15px 0;
                    }
                    
                    .clear { clear: both; }
                </style>
                </head>
                <body>
                    <h1>Code Health Report</h1>
                    
                    <div>
                        <div class="summary-card">
                            <h3>Health Score</h3>
                            <div class="health-score ${getHealthClass(averageScore)}">$averageScore</div>
                        </div>
                        <div class="summary-card">
                            <h3>Methods</h3>
                            <div class="summary-value">$totalMethods</div>
                        </div>
                        <div class="summary-card">
                            <h3>Issues</h3>
                            <div class="summary-value">${realIssues.size}</div>
                        </div>
                        <div class="clear"></div>
                    </div>
                    
                    ${if (issuesByCategory.isNotEmpty()) """
                        <h2>Issues by Category</h2>
                        <ul>
                        ${issuesByCategory.joinToString("") { (category, issues) ->
                            "<li><strong>$category</strong>: ${issues.size} issues</li>"
                        }}
                        </ul>
                    """ else ""}
                    
                    <h2>Detailed Analysis</h2>
                    ${results.filter { it.issues.any { issue -> issue.verified && !issue.falsePositive } }
                        .sortedBy { it.healthScore }
                        .joinToString("") { result ->
                            val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                            
                            """
                            <div class="method-section">
                                <div class="method-name">${escapeHtml(result.fqn)}</div>
                                <p>
                                    Health Score: <strong>${result.healthScore}/100</strong> | 
                                    Modified: <strong>${result.modificationCount}</strong> times | 
                                    Called by: <strong>${result.impactedCallers.size}</strong> methods
                                </p>
                                
                                ${verifiedIssues.joinToString("") { issue ->
                                    """
                                    <div class="issue-card">
                                        <span class="severity-badge severity-${issue.severity}">Severity ${issue.severity}/5</span>
                                        <h4 class="issue-title">${escapeHtml(issue.title)}</h4>
                                        
                                        <p><strong>Category:</strong> ${escapeHtml(issue.issueCategory)}</p>
                                        <p>${escapeHtml(issue.description)}</p>
                                        
                                        <div class="impact-box">
                                            <strong>Impact:</strong> ${escapeHtml(issue.impact)}
                                        </div>
                                        
                                        <div class="fix-box">
                                            <strong>Suggested Fix:</strong> ${escapeHtml(issue.suggestedFix)}
                                        </div>
                                    </div>
                                    """.trimIndent()
                                }}
                            </div>
                            """.trimIndent()
                        }}
                </body>
                </html>
            """.trimIndent()
        }

        private fun getHealthClass(score: Int): String {
            return when {
                score >= 80 -> "health-good"
                score >= 60 -> "health-warning"
                else -> "health-critical"
            }
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }

        private fun generateTextReport(): String {
            val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
            val totalMethods = results.size
            val totalIssues = realIssues.size
            val averageScore = if (results.isNotEmpty()) results.map { it.healthScore }.average().toInt() else 100
            
            return buildString {
                appendLine("CODE HEALTH REPORT")
                appendLine("=".repeat(80))
                appendLine()
                appendLine("SUMMARY")
                appendLine("-".repeat(80))
                appendLine("Methods Analyzed:    $totalMethods")
                appendLine("Total Issues Found:  $totalIssues")
                appendLine("Average Health:      $averageScore/100")
                appendLine()
                
                if (realIssues.isNotEmpty()) {
                    appendLine("ISSUES BY CATEGORY")
                    appendLine("-".repeat(80))
                    val issuesByCategory = realIssues.groupBy { it.issueCategory }
                    issuesByCategory.forEach { (category, issues) ->
                        appendLine("$category: ${issues.size} issues")
                    }
                    appendLine()
                }
                
                appendLine("DETAILED ANALYSIS")
                appendLine("=".repeat(80))
                
                results.forEach { result ->
                    val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                    
                    appendLine()
                    appendLine("METHOD: ${result.fqn}")
                    appendLine("-".repeat(80))
                    appendLine("Health Score:        ${result.healthScore}/100")
                    appendLine("Times Modified:      ${result.modificationCount}")
                    appendLine("Callers:            ${result.impactedCallers.size}")
                    if (result.summary.isNotBlank()) {
                        appendLine("Summary:            ${result.summary}")
                    }
                    appendLine()
                    
                    if (verifiedIssues.isEmpty()) {
                        appendLine("  ✓ No issues found")
                    } else {
                        appendLine("  ISSUES (${verifiedIssues.size}):")
                        verifiedIssues.forEachIndexed { index, issue ->
                            appendLine()
                            appendLine("  ${index + 1}. [${issue.issueCategory}] ${issue.title}")
                            appendLine("     Severity:    ${getSeverityText(issue.severity)}")
                            appendLine("     Description: ${issue.description}")
                            appendLine("     Impact:      ${issue.impact}")
                            appendLine("     Fix:         ${issue.suggestedFix}")
                            if (issue.confidence < 1.0) {
                                appendLine("     Confidence:  ${(issue.confidence * 100).toInt()}%")
                            }
                        }
                    }
                    appendLine()
                }
                
                appendLine("-".repeat(80))
                appendLine("Report generated at: ${java.time.LocalDateTime.now()}")
            }
        }
        
        private fun getSeverityText(severity: Int): String {
            return when (severity) {
                5 -> "CRITICAL (5/5)"
                4 -> "HIGH (4/5)"
                3 -> "MEDIUM (3/5)"
                2 -> "LOW (2/5)"
                1 -> "MINOR (1/5)"
                else -> "UNKNOWN"
            }
        }

        private fun generateMarkdownReport(): String {
            val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
            
            return buildString {
                appendLine("# Code Health Report")
                appendLine()
                appendLine("## Summary")
                appendLine("- **Methods Analyzed:** ${results.size}")
                appendLine("- **Total Verified Issues:** ${realIssues.size}")
                appendLine("- **Average Health Score:** ${results.map { it.healthScore }.average().toInt()}/100")
                appendLine()
                
                appendLine("## Detailed Issues")
                
                results.forEach { result ->
                    val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                    if (verifiedIssues.isNotEmpty()) {
                        appendLine()
                        appendLine("### `${result.fqn}`")
                        appendLine("- Health Score: ${result.healthScore}/100")
                        appendLine("- Modified: ${result.modificationCount} times")
                        appendLine("- Called by: ${result.impactedCallers.size} methods")
                        appendLine()
                        
                        verifiedIssues.forEach { issue ->
                            appendLine("#### ${issue.title}")
                            appendLine("- **Category:** ${issue.issueCategory}")
                            appendLine("- **Severity:** ${issue.severity}/5")
                            appendLine("- **Confidence:** ${(issue.confidence * 100).toInt()}%")
                            appendLine("- **Description:** ${issue.description}")
                            appendLine("- **Impact:** ${issue.impact}")
                            appendLine("- **Suggested Fix:** ${issue.suggestedFix}")
                            appendLine()
                        }
                    }
                }
            }
        }

        private fun showCopiedNotification() {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification("Report copied to clipboard", NotificationType.INFORMATION)
                .notify(project)
        }

        override fun createActions(): Array<Action> {
            return arrayOf(
                object : DialogWrapperAction("Run Analysis Again") {
                    override fun doAction(e: java.awt.event.ActionEvent?) {
                        CodeHealthTracker.getInstance(project).checkAndNotify()
                        close(OK_EXIT_CODE)
                    }
                },
                okAction
            )
        }
    }
}
