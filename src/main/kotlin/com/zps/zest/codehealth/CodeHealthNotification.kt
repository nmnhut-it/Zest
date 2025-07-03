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

    private const val NOTIFICATION_GROUP_ID = "Zest Code Guardian"

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

        // Only show balloon notification if there are critical issues
        if (criticalIssues > 0) {
            val title = "🚨 Zest Guardian Alert: Critical Risk Detected"
            val content = "🔥 $criticalIssues Critical Risks - Fix Now to Prevent Crashes"
            
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, NotificationType.ERROR)
                .addAction(object : AnAction("🚀 Fix Now") {
                    override fun actionPerformed(e: AnActionEvent) {
                        println("[CodeHealthNotification] User clicked View Details")
                        showDetailedReport(project, results)
                    }
                })
            
            notification.notify(project)
        } else if (totalIssues > 0) {
            // For non-critical issues, just update status bar without balloon
            println("[CodeHealthNotification] Non-critical issues found, status bar updated")
            
            // Optionally show a subtle notification that auto-dismisses quickly
            if (totalIssues > 5) {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        "⚡ Zest Code Guardian",
                        "💡 $totalIssues Quick Wins Found - 5 Min to Review",
                        NotificationType.WARNING
                    )
                
                notification.notify(project)
                
                // Auto-expire after 3 seconds
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(3000)
                    ApplicationManager.getApplication().invokeLater {
                        notification.expire()
                    }
                }
            }
        }
        
        // Always show the detailed report dialog if there are issues
        if (totalIssues > 0) {
            showDetailedReport(project, results)
        }
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
            title = "🛡️ Zest Code Guardian Report"
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
                add(JButton("📋 Quick Copy for PR").apply {
                    addActionListener {
                        val textReport = generateTextReport()
                        CopyPasteManager.getInstance().setContents(StringSelection(textReport))
                        showCopiedNotification()
                    }
                })
                add(JButton("📝 Copy as Markdown").apply {
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
            val totalMethods = results.size
            val averageScore = if (results.isNotEmpty()) results.map { it.healthScore }.average().toInt() else 100
            
            // Group issues by severity for summary
            val criticalCount = realIssues.count { it.severity >= 4 }
            val mediumCount = realIssues.count { it.severity == 3 }
            val lowCount = realIssues.count { it.severity <= 2 }
            
            // Determine if dark theme
            val isDarkTheme = UIUtil.isUnderDarcula()
            val bgColor = if (isDarkTheme) "#2b2b2b" else "#ffffff"
            val textColor = if (isDarkTheme) "#bbbbbb" else "#333333"
            val borderColor = if (isDarkTheme) "#3c3f41" else "#d0d0d0"
            val sectionBg = if (isDarkTheme) "#3c3f41" else "#f5f5f5"
            val codeBg = if (isDarkTheme) "#1e1e1e" else "#f8f8f8"
            val linkColor = if (isDarkTheme) "#6897bb" else "#2470b3"
            
            // Score colors
            val scoreColor = when {
                averageScore >= 80 -> "#4caf50"
                averageScore >= 60 -> "#ff9800"
                else -> "#f44336"
            }

            return """
                <html>
                <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        font-size: 14px;
                        color: $textColor;
                        background-color: $bgColor;
                        margin: 0;
                        padding: 20px;
                    }
                    
                    h1 {
                        font-size: 24px;
                        font-weight: normal;
                        margin: 0 0 20px 0;
                        padding-bottom: 10px;
                        border-bottom: 2px solid $borderColor;
                    }
                    
                    h2 {
                        font-size: 18px;
                        font-weight: normal;
                        margin: 30px 0 15px 0;
                        color: $linkColor;
                    }
                    
                    h3 {
                        font-size: 16px;
                        font-weight: bold;
                        margin: 20px 0 10px 0;
                    }
                    
                    .summary {
                        background-color: $sectionBg;
                        padding: 15px;
                        margin-bottom: 20px;
                        border: 1px solid $borderColor;
                    }
                    
                    .summary-row {
                        margin: 5px 0;
                    }
                    
                    .score {
                        font-size: 36px;
                        font-weight: bold;
                        color: $scoreColor;
                        margin: 10px 0;
                    }
                    
                    .method {
                        margin-bottom: 30px;
                        border-left: 3px solid $linkColor;
                        padding-left: 15px;
                    }
                    
                    .method-header {
                        font-family: monospace;
                        font-size: 14px;
                        font-weight: bold;
                        margin-bottom: 5px;
                    }
                    
                    .method-stats {
                        font-size: 12px;
                        color: ${if (isDarkTheme) "#999999" else "#666666"};
                        margin-bottom: 15px;
                    }
                    
                    .issue {
                        background-color: $sectionBg;
                        border: 1px solid $borderColor;
                        padding: 15px;
                        margin-bottom: 15px;
                    }
                    
                    .issue-header {
                        font-weight: bold;
                        margin-bottom: 10px;
                    }
                    
                    .severity {
                        font-weight: bold;
                        margin-left: 10px;
                    }
                    
                    .severity-5, .severity-4 { color: #f44336; }
                    .severity-3 { color: #ff9800; }
                    .severity-2, .severity-1 { color: #4caf50; }
                    
                    .section {
                        margin: 10px 0;
                        padding: 10px;
                        background-color: $codeBg;
                        border-left: 3px solid $borderColor;
                    }
                    
                    .label {
                        font-weight: bold;
                        margin-right: 5px;
                    }
                    
                    table {
                        width: 100%;
                        margin: 10px 0;
                    }
                    
                    td {
                        padding: 5px;
                        vertical-align: top;
                    }
                    
                    .stat-value {
                        text-align: right;
                        font-weight: bold;
                    }
                </style>
                </head>
                <body>
                    <h1>🛡️ Zest Code Guardian Report</h1>
                    
                    <div class="summary">
                        <table>
                            <tr>
                                <td>🏆 Overall Health Score:</td>
                                <td class="stat-value"><span class="score">$averageScore</span>/100</td>
                            </tr>
                            <tr>
                                <td>🔍 Methods Scanned:</td>
                                <td class="stat-value">$totalMethods</td>
                            </tr>
                            <tr>
                                <td>🎯 Issues Found:</td>
                                <td class="stat-value">${realIssues.size}</td>
                            </tr>
                            ${if (criticalCount > 0) """
                            <tr>
                                <td>🚨 Critical/High Priority:</td>
                                <td class="stat-value" style="color: #f44336;">$criticalCount</td>
                            </tr>
                            """ else ""}
                            ${if (mediumCount > 0) """
                            <tr>
                                <td>⚠️ Medium Priority:</td>
                                <td class="stat-value" style="color: #ff9800;">$mediumCount</td>
                            </tr>
                            """ else ""}
                            ${if (lowCount > 0) """
                            <tr>
                                <td>💡 Quick Wins:</td>
                                <td class="stat-value" style="color: #4caf50;">$lowCount</td>
                            </tr>
                            """ else ""}
                        </table>
                    </div>
                    
                    ${if (results.any { it.issues.any { issue -> issue.verified && !issue.falsePositive } }) """
                        <h2>🔍 Detailed Findings</h2>
                        
                        ${results.filter { it.issues.any { issue -> issue.verified && !issue.falsePositive } }
                            .sortedBy { it.healthScore }
                            .joinToString("") { result ->
                                val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                                
                                """
                                <div class="method">
                                    <div class="method-header">${escapeHtml(result.fqn)}</div>
                                    <div class="method-stats">
                                        Health: ${result.healthScore}/100 | 
                                        Edits: ${result.modificationCount}x | 
                                        Impact: ${result.impactedCallers.size} callers | 
                                        Found: ${verifiedIssues.size} issues
                                    </div>
                                    
                                    ${verifiedIssues.sortedByDescending { it.severity }.joinToString("") { issue ->
                                        """
                                        <div class="issue">
                                            <div class="issue-header">
                                                ${escapeHtml(issue.title)}
                                                <span class="severity severity-${issue.severity}">[Risk Level: ${issue.severity}/5]</span>
                                            </div>
                                            
                                            <div style="margin: 5px 0;">
                                                <span class="label">Type:</span> ${escapeHtml(issue.issueCategory)}
                                            </div>
                                            
                                            <div style="margin: 10px 0;">
                                                ${escapeHtml(issue.description)}
                                            </div>
                                            
                                            <div class="section">
                                                <span class="label">What happens if unfixed:</span><br/>
                                                ${escapeHtml(issue.impact)}
                                            </div>
                                            
                                            <div class="section" style="background-color: ${if (isDarkTheme) "#2d4a2b" else "#e8f5e9"};">
                                                <span class="label">How to fix:</span><br/>
                                                ${escapeHtml(issue.suggestedFix)}
                                            </div>
                                        </div>
                                        """.trimIndent()
                                    }}
                                </div>
                                """.trimIndent()
                            }}
                    """ else """
                        <h2>🎉 Perfect Score!</h2>
                        <p>Your code is bulletproof - no issues detected!</p>
                    """}
                    
                    <div style="margin-top: 40px; padding-top: 20px; border-top: 1px solid $borderColor; font-size: 12px; color: ${if (isDarkTheme) "#999999" else "#666666"};">
                        Generated by Zest Code Guardian • ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
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
                appendLine("🛡️ ZEST CODE GUARDIAN REPORT")
                appendLine("=".repeat(80))
                appendLine()
                appendLine("📊 QUICK SUMMARY")
                appendLine("-".repeat(80))
                appendLine("🔍 Methods Scanned:  $totalMethods")
                appendLine("🎯 Issues Found:     $totalIssues")
                appendLine("❤️ Health Score:     $averageScore/100")
                appendLine()
                
                if (realIssues.isNotEmpty()) {
                    appendLine("📈 ISSUES BY TYPE")
                    appendLine("-".repeat(80))
                    val issuesByCategory = realIssues.groupBy { it.issueCategory }
                    issuesByCategory.forEach { (category, issues) ->
                        appendLine("$category: ${issues.size} issues")
                    }
                    appendLine()
                }
                
                appendLine("🔧 DETAILED FINDINGS")
                appendLine("=".repeat(80))
                
                results.forEach { result ->
                    val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                    
                    appendLine()
                    appendLine("METHOD: ${result.fqn}")
                    appendLine("-".repeat(80))
                    appendLine("❤️ Health Score:     ${result.healthScore}/100")
                    appendLine("✏️ Times Edited:     ${result.modificationCount}")
                    appendLine("🔗 Used By:          ${result.impactedCallers.size} methods")
                    if (result.summary.isNotBlank()) {
                        appendLine("Summary:            ${result.summary}")
                    }
                    appendLine()
                    
                    if (verifiedIssues.isEmpty()) {
                        appendLine("  ✅ Clean code - no issues!")
                    } else {
                        appendLine("  ⚠️ FOUND ${verifiedIssues.size} ISSUES:")
                        verifiedIssues.forEachIndexed { index, issue ->
                            appendLine()
                            appendLine("  ${index + 1}. [${issue.issueCategory}] ${issue.title}")
                            appendLine("     Risk Level:  ${getSeverityText(issue.severity)}")
                            appendLine("     What:        ${issue.description}")
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
                appendLine("Generated by Zest Code Guardian at: ${java.time.LocalDateTime.now()}")
            }
        }
        
        private fun getSeverityText(severity: Int): String {
            return when (severity) {
                5 -> "🚨 CRITICAL"
                4 -> "🔥 HIGH"
                3 -> "⚠️ MEDIUM"
                2 -> "💡 LOW"
                1 -> "💭 MINOR"
                else -> "❓ UNKNOWN"
            }
        }

        private fun generateMarkdownReport(): String {
            val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
            
            return buildString {
                appendLine("# 🛡️ Zest Code Guardian Report")
                appendLine()
                appendLine("## 📊 Summary")
                appendLine("- **🔍 Methods Scanned:** ${results.size}")
                appendLine("- **🎯 Issues Found:** ${realIssues.size}")
                appendLine("- **❤️ Health Score:** ${results.map { it.healthScore }.average().toInt()}/100")
                appendLine()
                
                appendLine("## 🔧 Detailed Issues")
                
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
                            appendLine("- **Type:** ${issue.issueCategory}")
                            appendLine("- **Risk:** ${issue.severity}/5")
                            appendLine("- **Confidence:** ${(issue.confidence * 100).toInt()}%")
                            appendLine("- **What:** ${issue.description}")
                            appendLine("- **Impact:** ${issue.impact}")
                            appendLine("- **Fix:** ${issue.suggestedFix}")
                            appendLine()
                        }
                    }
                }
            }
        }

        private fun showCopiedNotification() {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification("✅ Copied! Ready to share in your PR", NotificationType.INFORMATION)
                .notify(project)
        }

        override fun createActions(): Array<Action> {
            return arrayOf(
                object : DialogWrapperAction("🔄 Run Again") {
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
