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
        if (results.isEmpty()) return

        val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val totalIssues = realIssues.size
        val criticalIssues = realIssues.count { it.severity >= 4 }
        val highIssues = realIssues.count { it.severity == 3 }
        val averageScore = results.map { it.healthScore }.average().toInt()

        val title = "Code Health Check Complete"
        val content = buildString {
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
            
            // Show loading initially
            val loadingLabel = JBLabel("Generating report...")
            loadingLabel.horizontalAlignment = SwingConstants.CENTER
            panel.add(loadingLabel, BorderLayout.CENTER)
            
            // Generate HTML content in background
            ApplicationManager.getApplication().executeOnPooledThread {
                val htmlContent = generateHtmlReport()
                
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        panel.remove(loadingLabel)
                        
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
                        
                        panel.revalidate()
                        panel.repaint()
                    }
                }
            }
            
            return panel
        }

        private fun generateHtmlReport(): String {
            val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
            val falsePositives = results.flatMap { it.issues }.filter { it.falsePositive }
            val totalMethods = results.size
            val averageScore = results.map { it.healthScore }.average().toInt()
            
            // Group issues by category
            val issuesByCategory = realIssues.groupBy { it.issueCategory }
                .toList()
                .sortedByDescending { (_, issues) -> issues.sumOf { it.severity } }

            return """
                <!DOCTYPE html>
                <html>
                <head>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        background: #f5f5f5;
                        margin: 0;
                        padding: 20px;
                    }
                    
                    .container {
                        max-width: 100%;
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                        padding: 30px;
                    }
                    
                    h1 {
                        color: #2c3e50;
                        margin-bottom: 30px;
                        border-bottom: 3px solid #3498db;
                        padding-bottom: 10px;
                    }
                    
                    h2 {
                        color: #34495e;
                        margin-top: 30px;
                        margin-bottom: 20px;
                    }
                    
                    h3 {
                        color: #7f8c8d;
                        margin-top: 20px;
                        margin-bottom: 15px;
                    }
                    
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 20px;
                        margin-bottom: 30px;
                    }
                    
                    .summary-card {
                        background: #f8f9fa;
                        padding: 20px;
                        border-radius: 8px;
                        text-align: center;
                        border: 1px solid #e9ecef;
                    }
                    
                    .summary-card h3 {
                        margin: 0 0 10px 0;
                        font-size: 14px;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                    }
                    
                    .summary-value {
                        font-size: 36px;
                        font-weight: bold;
                        margin: 0;
                    }
                    
                    .health-score {
                        display: inline-block;
                        width: 80px;
                        height: 80px;
                        border-radius: 50%;
                        line-height: 80px;
                        text-align: center;
                        font-size: 24px;
                        font-weight: bold;
                        color: white;
                        margin: 10px 0;
                    }
                    
                    .health-good { background: #27ae60; }
                    .health-warning { background: #f39c12; }
                    .health-critical { background: #e74c3c; }
                    
                    .issue-card {
                        background: #fff;
                        border: 1px solid #e0e0e0;
                        border-radius: 8px;
                        padding: 20px;
                        margin-bottom: 20px;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.1);
                    }
                    
                    .issue-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 15px;
                    }
                    
                    .issue-title {
                        font-size: 18px;
                        font-weight: 600;
                        margin: 0;
                    }
                    
                    .severity-badge {
                        display: inline-block;
                        padding: 4px 12px;
                        border-radius: 20px;
                        font-size: 12px;
                        font-weight: 600;
                        text-transform: uppercase;
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
                        font-family: 'Consolas', 'Monaco', monospace;
                        font-size: 16px;
                        color: #2c3e50;
                        margin-bottom: 10px;
                    }
                    
                    .code-snippet {
                        background: #282c34;
                        color: #abb2bf;
                        padding: 15px;
                        border-radius: 5px;
                        font-family: 'Consolas', 'Monaco', monospace;
                        font-size: 14px;
                        overflow-x: auto;
                        margin: 10px 0;
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
                    
                    .confidence-bar {
                        display: inline-block;
                        width: 100px;
                        height: 8px;
                        background: #e0e0e0;
                        border-radius: 4px;
                        overflow: hidden;
                        margin-left: 10px;
                    }
                    
                    .confidence-fill {
                        height: 100%;
                        background: #3498db;
                        transition: width 0.3s ease;
                    }
                    
                    .false-positive {
                        opacity: 0.5;
                        border-color: #ccc;
                    }
                    
                    .false-positive .issue-title {
                        text-decoration: line-through;
                    }
                    
                    .category-section {
                        margin-bottom: 40px;
                    }
                    
                    .category-header {
                        background: #ecf0f1;
                        padding: 15px 20px;
                        border-radius: 8px 8px 0 0;
                        margin-bottom: 0;
                    }
                    
                    .stats-inline {
                        display: flex;
                        gap: 20px;
                        margin-top: 10px;
                        font-size: 14px;
                        color: #666;
                    }
                </style>
                </head>
                <body>
                <div class="container">
                    <h1>🏥 Code Health Report</h1>
                    
                    <div class="summary-grid">
                        <div class="summary-card">
                            <h3>Health Score</h3>
                            <div class="health-score ${getHealthClass(averageScore)}">$averageScore</div>
                        </div>
                        <div class="summary-card">
                            <h3>Methods Analyzed</h3>
                            <p class="summary-value">$totalMethods</p>
                        </div>
                        <div class="summary-card">
                            <h3>Verified Issues</h3>
                            <p class="summary-value">${realIssues.size}</p>
                        </div>
                        <div class="summary-card">
                            <h3>False Positives</h3>
                            <p class="summary-value">${falsePositives.size}</p>
                        </div>
                    </div>
                    
                    <h2>📊 Issues by Category</h2>
                    ${generateCategorySummary(issuesByCategory)}
                    
                    <h2>📋 Detailed Analysis by Method</h2>
                    ${generateMethodDetails()}
                    
                    ${if (falsePositives.isNotEmpty()) """
                        <h2>❌ False Positives (Filtered Out)</h2>
                        <p style="color: #666;">These issues were initially detected but verified as false positives:</p>
                        ${generateFalsePositives(falsePositives)}
                    """ else ""}
                </div>
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

        private fun generateCategorySummary(issuesByCategory: List<Pair<String, List<CodeHealthAnalyzer.HealthIssue>>>): String {
            return issuesByCategory.joinToString("") { (category, issues) ->
                val avgSeverity = issues.map { it.severity }.average()
                val criticalCount = issues.count { it.severity >= 4 }
                
                """
                <div class="category-section">
                    <div class="category-header">
                        <h3 style="margin: 0;">$category</h3>
                        <div class="stats-inline">
                            <span><strong>${issues.size}</strong> issues</span>
                            <span>Avg severity: <strong>${String.format("%.1f", avgSeverity)}/5</strong></span>
                            ${if (criticalCount > 0) "<span style='color: #e74c3c;'><strong>$criticalCount</strong> critical</span>" else ""}
                        </div>
                    </div>
                </div>
                """.trimIndent()
            }
        }

        private fun generateMethodDetails(): String {
            return results.filter { it.issues.any { issue -> issue.verified && !issue.falsePositive } }
                .sortedBy { it.healthScore }
                .joinToString("") { result ->
                    val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                    
                    """
                    <div class="method-section">
                        <div class="method-name">📄 ${result.fqn}</div>
                        <div class="stats-inline">
                            <span>Health Score: <strong>${result.healthScore}/100</strong></span>
                            <span>Modified: <strong>${result.modificationCount}</strong> times</span>
                            <span>Called by: <strong>${result.impactedCallers.size}</strong> methods</span>
                        </div>
                        
                        ${if (result.summary.isNotBlank()) "<p style='margin-top: 15px;'><em>${result.summary}</em></p>" else ""}
                        
                        ${verifiedIssues.joinToString("") { issue ->
                            """
                            <div class="issue-card">
                                <div class="issue-header">
                                    <h4 class="issue-title">${issue.title}</h4>
                                    <span class="severity-badge severity-${issue.severity}">Severity ${issue.severity}/5</span>
                                </div>
                                
                                <p><strong>Category:</strong> ${issue.issueCategory}</p>
                                <p>${issue.description}</p>
                                
                                ${if (issue.codeSnippet != null) """
                                    <div class="code-snippet">${escapeHtml(issue.codeSnippet)}</div>
                                """ else ""}
                                
                                ${if (issue.callerSnippets.isNotEmpty()) """
                                    <div style="margin-top: 15px;">
                                        <strong>Usage Examples:</strong>
                                        ${issue.callerSnippets.joinToString("") { snippet ->
                                            """
                                            <div style="margin-top: 10px; background: #f5f5f5; padding: 10px; border-radius: 5px;">
                                                <div style="font-weight: bold; font-size: 12px; color: #666;">
                                                    Called by: ${snippet.callerFqn} (${snippet.context})
                                                </div>
                                                <div class="code-snippet" style="margin-top: 5px; font-size: 12px;">
                                                    ${escapeHtml(snippet.snippet)}
                                                </div>
                                            </div>
                                            """.trimIndent()
                                        }}
                                    </div>
                                """ else ""}
                                
                                <div class="impact-box">
                                    <strong>Impact:</strong> ${issue.impact}
                                </div>
                                
                                <div class="fix-box">
                                    <strong>Suggested Fix:</strong> ${issue.suggestedFix}
                                </div>
                                
                                <div style="margin-top: 10px;">
                                    <small>Confidence: ${(issue.confidence * 100).toInt()}%</small>
                                    <div class="confidence-bar">
                                        <div class="confidence-fill" style="width: ${(issue.confidence * 100).toInt()}%"></div>
                                    </div>
                                </div>
                                
                                ${if (issue.verificationReason != null) """
                                    <p style="margin-top: 10px; font-size: 14px; color: #666;">
                                        <strong>Verification:</strong> ${issue.verificationReason}
                                    </p>
                                """ else ""}
                            </div>
                            """.trimIndent()
                        }}
                    </div>
                    """.trimIndent()
                }
        }

        private fun generateFalsePositives(falsePositives: List<CodeHealthAnalyzer.HealthIssue>): String {
            return falsePositives.joinToString("") { issue ->
                """
                <div class="issue-card false-positive">
                    <h4 class="issue-title">${issue.title}</h4>
                    <p>${issue.description}</p>
                    <p style="color: #666;"><strong>Reason for false positive:</strong> ${issue.verificationReason ?: "Verified as not a real issue"}</p>
                </div>
                """.trimIndent()
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
            
            return buildString {
                appendLine("CODE HEALTH REPORT")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Summary:")
                appendLine("- Methods Analyzed: ${results.size}")
                appendLine("- Total Verified Issues: ${realIssues.size}")
                appendLine("- Average Health Score: ${results.map { it.healthScore }.average().toInt()}/100")
                appendLine()
                
                results.forEach { result ->
                    val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                    if (verifiedIssues.isNotEmpty()) {
                        appendLine("-".repeat(50))
                        appendLine("Method: ${result.fqn}")
                        appendLine("Health Score: ${result.healthScore}/100 | Modified: ${result.modificationCount}x")
                        appendLine()
                        
                        verifiedIssues.forEach { issue ->
                            appendLine("  [${issue.issueCategory}] ${issue.title}")
                            appendLine("  Severity: ${issue.severity}/5 | Confidence: ${(issue.confidence * 100).toInt()}%")
                            appendLine("  ${issue.description}")
                            appendLine("  Impact: ${issue.impact}")
                            appendLine("  Fix: ${issue.suggestedFix}")
                            appendLine()
                        }
                    }
                }
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
