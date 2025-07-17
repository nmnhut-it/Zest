package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

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
        
        // Get the actual model from the first result (they should all use the same model)
        val actualModel = results.firstOrNull()?.actualModel ?: "local-model-mini"

        val realIssues = results.flatMap { it.issues }.filter { it.verified && !it.falsePositive }
        val totalIssues = realIssues.size
        val criticalIssues = realIssues.count { it.severity >= 4 }
        val highIssues = realIssues.count { it.severity == 3 }
        val averageScore = results.map { it.healthScore }.average().toInt()

        println("[CodeHealthNotification] Showing report: ${results.size} methods, $totalIssues verified issues")

        // Generate catchy notification using LLM
        val llmService = project.service<com.zps.zest.langchain4j.util.LLMService>()
        
        try {
            // Generate notification content based on severity
            val (title, content) = when {
                criticalIssues > 0 -> generateCriticalNotification(llmService, criticalIssues, totalIssues)
                totalIssues > 0 -> generateWarningNotification(llmService, totalIssues, averageScore)
                else -> generateSuccessNotification(llmService, results.size, averageScore)
            }
            
            // Show the notification
            val notificationType = when {
                criticalIssues > 0 -> NotificationType.ERROR
                totalIssues > 0 -> NotificationType.WARNING
                else -> NotificationType.INFORMATION
            }
            
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, notificationType)
            
            // Add action button only if there are issues
            if (totalIssues > 0) {
                val actionText = if (criticalIssues > 0) "🚀 Fix Now" else "👀 View Details"
                notification.addAction(object : AnAction(actionText) {
                    override fun actionPerformed(e: AnActionEvent) {
                        println("[CodeHealthNotification] User clicked $actionText")
                        
                        // Track user viewing details
                        sendHealthCheckViewMetrics(project, results, criticalIssues)
                        
                        showDetailedReport(project, results)
                    }
                })
            }
            
            notification.notify(project)
            
        } catch (e: Exception) {
            // Fallback to hardcoded messages if LLM fails
            println("[CodeHealthNotification] LLM generation failed, using fallback: ${e.message}")
            showFallbackNotification(project, results, totalIssues, criticalIssues)
        }
        
        // Send metrics for the health check
        sendHealthCheckMetrics(project, results, totalIssues, criticalIssues, highIssues, averageScore, actualModel)
    }
    
    /**
     * Generate catchy notification for critical issues
     */
    private fun generateCriticalNotification(
        llmService: com.zps.zest.langchain4j.util.LLMService,
        criticalCount: Int,
        totalCount: Int
    ): Pair<String, String> {
        val prompt = """
            Generate a catchy, urgent notification for a code health check that found critical issues.
            
            Context:
            - Found $criticalCount critical issues (severity 4-5)
            - Total $totalCount issues found
            - These could cause crashes, data loss, or security breaches
            
            Requirements:
            - Title: 15-30 chars, start with an emoji (🚨, 🔥, ⚡, 💣, 🆘)
            - Content: 30-50 chars, urgent but not scary, mention the critical count
            - Be creative, vary the messages
            - Make developers want to click "Fix Now"
            
            Return ONLY valid JSON:
            {
                "title": "emoji Title here",
                "content": "Short urgent message mentioning $criticalCount critical risks"
            }
        """.trimIndent()
        
        val params = com.zps.zest.langchain4j.util.LLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(200)
            .withTemperature(0.8) // More creativity
        
        val response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
        
        return if (response != null) {
            parseLLMNotification(response) ?: Pair(
                "🚨 Critical Risk Alert",
                "🔥 $criticalCount Critical Risks - Fix Now to Prevent Crashes"
            )
        } else {
            Pair(
                "🚨 Critical Risk Alert", 
                "🔥 $criticalCount Critical Risks - Fix Now to Prevent Crashes"
            )
        }
    }
    
    /**
     * Generate catchy notification for non-critical issues
     */
    private fun generateWarningNotification(
        llmService: com.zps.zest.langchain4j.util.LLMService,
        totalCount: Int,
        averageScore: Int
    ): Pair<String, String> {
        val prompt = """
            Generate a catchy, encouraging notification for a code health check that found some issues.
            
            Context:
            - Found $totalCount non-critical issues
            - Average health score: $averageScore/100
            - These are opportunities for improvement
            
            Requirements:
            - Title: 15-25 chars, start with an emoji (⚡, 💡, 🎯, 🔧, 🛠️)
            - Content: 30-50 chars, positive tone, mention quick wins
            - Be creative and motivating
            - Make it sound like an opportunity, not a problem
            
            Return ONLY valid JSON:
            {
                "title": "emoji Title here",
                "content": "Encouraging message about $totalCount improvements"
            }
        """.trimIndent()
        
        val params = com.zps.zest.langchain4j.util.LLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(200)
            .withTemperature(0.8)
        
        val response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
        
        return if (response != null) {
            parseLLMNotification(response) ?: Pair(
                "⚡ Code Guardian Alert",
                "💡 $totalCount Quick Wins Available - Level Up Your Code!"
            )
        } else {
            Pair(
                "⚡ Code Guardian Alert",
                "💡 $totalCount Quick Wins Available - Level Up Your Code!"
            )
        }
    }
    
    /**
     * Generate catchy notification for perfect score
     */
    private fun generateSuccessNotification(
        llmService: com.zps.zest.langchain4j.util.LLMService,
        methodCount: Int,
        averageScore: Int
    ): Pair<String, String> {
        val prompt = """
            Generate a celebratory notification for perfect code health.
            
            Context:
            - Analyzed $methodCount methods
            - Average score: $averageScore/100
            - No issues found - code is clean!
            
            Requirements:
            - Title: 15-25 chars, start with celebration emoji (✨, 🎉, 🏆, 💎, 🌟)
            - Content: 30-50 chars, very positive, celebrate the achievement
            - Be creative and fun
            - Make the developer feel proud
            
            Return ONLY valid JSON:
            {
                "title": "emoji Title here",
                "content": "Celebratory message about clean code"
            }
        """.trimIndent()
        
        val params = com.zps.zest.langchain4j.util.LLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(200)
            .withTemperature(0.8)
        
        val response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
        
        return if (response != null) {
            parseLLMNotification(response) ?: Pair(
                "✨ Code Guardian Victory",
                "🏆 Your code is pristine - Zero issues found!"
            )
        } else {
            Pair(
                "✨ Code Guardian Victory",
                "🏆 Your code is pristine - Zero issues found!"
            )
        }
    }
    
    /**
     * Parse LLM response for notification content
     */
    private fun parseLLMNotification(response: String): Pair<String, String>? {
        return try {
            val gson = com.google.gson.Gson()
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            
            if (jsonStart == -1 || jsonEnd == -1) return null
            
            val jsonContent = response.substring(jsonStart, jsonEnd + 1)
            val jsonObject = gson.fromJson(jsonContent, com.google.gson.JsonObject::class.java)
            
            val title = jsonObject.get("title")?.asString ?: return null
            val content = jsonObject.get("content")?.asString ?: return null
            
            Pair(title, content)
        } catch (e: Exception) {
            println("[CodeHealthNotification] Error parsing LLM response: ${e.message}")
            null
        }
    }
    
    /**
     * Fallback notification when LLM fails
     */
    private fun showFallbackNotification(
        project: Project,
        results: List<CodeHealthAnalyzer.MethodHealthResult>,
        totalIssues: Int,
        criticalIssues: Int
    ) {
        val title = when {
            criticalIssues > 0 -> "🚨 Zest Guardian Alert: Critical Risk Detected"
            totalIssues > 0 -> "⚡ Zest Code Guardian"
            else -> "✨ Zest Code Guardian"
        }
        
        val content = when {
            criticalIssues > 0 -> "🔥 $criticalIssues Critical Risks - Fix Now to Prevent Crashes"
            totalIssues > 0 -> "💡 $totalIssues ${if (totalIssues == 1) "Issue" else "Issues"} Found - Quick Wins Available"
            else -> "🏆 Your code is clean - no issues detected!"
        }
        
        val notificationType = when {
            criticalIssues > 0 -> NotificationType.ERROR
            totalIssues > 0 -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, notificationType)
        
        if (totalIssues > 0) {
            val actionText = if (criticalIssues > 0) "🚀 Fix Now" else "👀 View Details"
            notification.addAction(object : AnAction(actionText) {
                override fun actionPerformed(e: AnActionEvent) {
                    sendHealthCheckViewMetrics(project, results, criticalIssues)
                    showDetailedReport(project, results)
                }
            })
        }
        
        notification.notify(project)
    }

    /**
     * Send health check metrics to remote server
     */
    private fun sendHealthCheckMetrics(
        project: Project,
        results: List<CodeHealthAnalyzer.MethodHealthResult>,
        totalIssues: Int,
        criticalIssues: Int,
        highIssues: Int,
        averageScore: Int,
        actualModel: String
    ) {
        try {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            
            // Create a unique ID for this health check
            val healthCheckId = "health_check_${System.currentTimeMillis()}"
            
            // Collect detailed metrics
            val issuesByCategory = results.flatMap { it.issues }
                .filter { it.verified && !it.falsePositive }
                .groupBy { it.issueCategory }
                .mapValues { it.value.size }
            
            val metadata = mutableMapOf<String, Any>(
                "critical_issues" to criticalIssues,
                "high_issues" to highIssues,
                "total_issues" to totalIssues,
                "methods_analyzed" to results.size,
                "average_health_score" to averageScore,
                "issues_by_category" to issuesByCategory
            )
            
            // Send view event (report shown)
            metricsService.trackCustomEvent(
                eventId = healthCheckId,
                eventType = "CODE_HEALTH_LOGGING|response",
                actualModel = actualModel,
                metadata = metadata
            )
            
            println("[CodeHealthNotification] Sent health check metrics: critical=$criticalIssues, total=$totalIssues")
            
        } catch (e: Exception) {
            // Don't fail the health check if metrics fail
            println("[CodeHealthNotification] Failed to send metrics: ${e.message}")
        }
    }

    private fun getNotificationType(healthScore: Int): NotificationType {
        return when {
            healthScore >= 80 -> NotificationType.INFORMATION
            healthScore >= 60 -> NotificationType.WARNING
            else -> NotificationType.ERROR
        }
    }

    /**
     * Send metrics when user views detailed report
     */
    private fun sendHealthCheckViewMetrics(
        project: Project,
        results: List<CodeHealthAnalyzer.MethodHealthResult>,
        criticalIssues: Int
    ) {
        try {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            
            val healthCheckId = "health_check_view_${System.currentTimeMillis()}"
            
            val metadata = mapOf(
                "critical_issues" to criticalIssues,
                "methods_with_issues" to results.count { it.issues.any { issue -> issue.verified && !issue.falsePositive } },
                "user_action" to "fix_now_clicked"
            )
            
            metricsService.trackCustomEvent(
                eventId = healthCheckId,
                eventType = "CODE_HEALTH_LOGGING|view",
                actualModel = "local-model-mini",
                metadata = metadata
            )
            
        } catch (e: Exception) {
            println("[CodeHealthNotification] Failed to send view metrics: ${e.message}")
        }
    }

    private fun showDetailedReport(project: Project, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        // Store the results first
        CodeHealthReportStorage.getInstance(project).storeReport(results)
        
        // Show the tool window
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Guardian")
            if (toolWindow != null) {
                toolWindow.show()
                
                // Update with the new results
                com.zps.zest.codehealth.ui.CodeGuardianToolWindowFactory.getPanel(project)?.updateResults(results)
            } else {
                // Fallback to dialog if tool window not available
                val dialog = com.zps.zest.codehealth.ui.SwingHealthReportDialog(project, results)
                dialog.show()
            }
        }
    }

    // Old HTML-based dialog - kept for reference, replaced by SwingHealthReportDialog
    /*
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
                
                // Add hyperlink listener for "Fix now" links
                addHyperlinkListener(object : HyperlinkListener {
                    override fun hyperlinkUpdate(e: HyperlinkEvent) {
                        println("[CodeHealthNotification] Hyperlink event: ${e.eventType}, URL: ${e.url}, Description: ${e.description}")
                        
                        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            val url = e.description ?: e.url?.toString() ?: return
                            println("[CodeHealthNotification] Hyperlink activated: $url")
                            
                            when {
                                url.startsWith("fix://") -> handleFixNowClick(url)
                                url.startsWith("goto://") -> handleGoToClick(url)
                            }
                        }
                    }
                })
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
        
        /**
         * Handle "Go to" button clicks
         */
        private fun handleGoToClick(url: String) {
            println("[CodeHealthNotification] handleGoToClick called with URL: $url")
            
            try {
                // Extract FQN from URL (format: goto://fqn)
                val fqn = url.removePrefix("goto://")
                println("[CodeHealthNotification] Navigating to: $fqn")
                
                // Check if it's a JS/TS file (format: filename.js:lineNumber)
                if (fqn.contains(".js:") || fqn.contains(".ts:") || 
                    fqn.contains(".jsx:") || fqn.contains(".tsx:")) {
                    navigateToJsTsLocation(fqn)
                } else {
                    // Java method navigation
                    navigateToJavaMethod(fqn)
                }
                
                // Close the dialog after navigation
                close(OK_EXIT_CODE)
                
            } catch (e: Exception) {
                println("[CodeHealthNotification] Error navigating: ${e.message}")
                e.printStackTrace()
                
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("Unable to navigate to method", NotificationType.WARNING)
                    .notify(project)
            }
        }
        
        /**
         * Navigate to a Java method
         */
        private fun navigateToJavaMethod(fqn: String) {
            val parts = fqn.split(".")
            if (parts.size < 2) return
            
            val className = parts.dropLast(1).joinToString(".")
            val methodName = parts.last()
            
            // Find the class
            val psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project))
            
            if (psiClass == null) {
                println("[CodeHealthNotification] Class not found: $className")
                return
            }
            
            // Find the method
            val psiMethod = psiClass.methods.find { it.name == methodName }
            
            if (psiMethod == null) {
                println("[CodeHealthNotification] Method not found: $methodName in $className")
                // Navigate to class if method not found
                navigateToElement(psiClass.containingFile.virtualFile, psiClass.textOffset)
                return
            }
            
            // Navigate to the method
            navigateToElement(psiMethod.containingFile.virtualFile, psiMethod.textOffset)
        }
        
        /**
         * Navigate to a JS/TS location
         */
        private fun navigateToJsTsLocation(fqn: String) {
            // Format: filename.js:lineNumber
            val parts = fqn.split(":")
            if (parts.size != 2) return
            
            val filePath = parts[0]
            val lineNumber = parts[1].toIntOrNull() ?: return
            
            // Find the file
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(filePath)
            
            if (virtualFile == null) {
                println("[CodeHealthNotification] File not found: $filePath")
                return
            }
            
            // Navigate to the line
            val descriptor = OpenFileDescriptor(
                project,
                virtualFile,
                lineNumber - 1, // Convert to 0-based
                0
            )
            
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
        
        /**
         * Navigate to a specific element
         */
        private fun navigateToElement(virtualFile: VirtualFile?, offset: Int) {
            if (virtualFile == null) return
            
            val descriptor = OpenFileDescriptor(
                project,
                virtualFile,
                offset
            )
            
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
        
        /**
         * Handle "Fix now" button clicks
         */
        private fun handleFixNowClick(url: String) {
            println("[CodeHealthNotification] handleFixNowClick called with URL: $url")
            
            try {
                // Extract issue ID from URL (format: fix://resultIndex_issueIndex)
                val issueId = url.removePrefix("fix://")
                println("[CodeHealthNotification] Extracted issue ID: $issueId")
                
                val parts = issueId.split("_")
                if (parts.size != 2) {
                    println("[CodeHealthNotification] Invalid issue ID format: $issueId")
                    return
                }
                
                val resultIndex = parts[0].toIntOrNull() ?: run {
                    println("[CodeHealthNotification] Invalid result index: ${parts[0]}")
                    return
                }
                val issueIndex = parts[1].toIntOrNull() ?: run {
                    println("[CodeHealthNotification] Invalid issue index: ${parts[1]}")
                    return
                }
                
                println("[CodeHealthNotification] Result index: $resultIndex, Issue index: $issueIndex")
                
                // Get the specific issue
                if (resultIndex >= results.size) {
                    println("[CodeHealthNotification] Result index out of bounds: $resultIndex >= ${results.size}")
                    return
                }
                val result = results[resultIndex]
                val verifiedIssues = result.issues.filter { it.verified && !it.falsePositive }
                if (issueIndex >= verifiedIssues.size) {
                    println("[CodeHealthNotification] Issue index out of bounds: $issueIndex >= ${verifiedIssues.size}")
                    return
                }
                
                val issue = verifiedIssues.sortedByDescending { it.severity }[issueIndex]
                println("[CodeHealthNotification] Found issue: ${issue.title}")
                
                // Generate fix prompt
                val fixPrompt = generateFixPrompt(result, issue)
                println("[CodeHealthNotification] Generated fix prompt (${fixPrompt.length} chars)")
                
                // Send to chat box
                sendPromptToChatBox(fixPrompt, result.fqn, issue)
                
                // Track metrics
                trackFixNowClick(issue)
                
                // Close the dialog
                println("[CodeHealthNotification] Closing dialog")
                close(OK_EXIT_CODE)
                
            } catch (e: Exception) {
                println("[CodeHealthNotification] Error handling fix click: ${e.message}")
                e.printStackTrace()
            }
        }
        
        /**
         * Generate a prompt for fixing a specific issue
         */
        private fun generateFixPrompt(result: CodeHealthAnalyzer.MethodHealthResult, issue: CodeHealthAnalyzer.HealthIssue): String {
            // Determine file type from FQN or context
            val isJsTsFile = result.fqn.contains(".js:") || result.fqn.contains(".ts:") || 
                           result.fqn.contains(".jsx:") || result.fqn.contains(".tsx:") ||
                           result.fqn.contains(".mjs:") || result.fqn.contains(".mts:")
            
            val isCocosFile = result.codeContext.contains("cc.") || 
                            result.codeContext.contains("cocos2d") ||
                            result.fqn.contains("cocos")
            
            val language = when {
                result.fqn.contains(".ts:") || result.fqn.contains(".tsx:") || result.fqn.contains(".mts:") -> "typescript"
                result.fqn.contains(".js:") || result.fqn.contains(".jsx:") || result.fqn.contains(".mjs:") -> if (isCocosFile) "cocos2d-x-js" else "javascript"
                result.fqn.endsWith(".java") || result.fqn.contains(".java.") -> "java"
                result.fqn.endsWith(".kt") || result.fqn.contains(".kt.") -> "kotlin"
                else -> "java" // default
            }
            
            return buildString {
                appendLine("Please help me fix this code issue detected by Code Guardian:")
                appendLine()
                
                if (isJsTsFile) {
                    // For JS/TS files, the FQN is in format: filename:line-range
                    appendLine("**File:** `${result.fqn.substringBefore(":")}`")
                    val lineRange = result.fqn.substringAfter(":")
                    appendLine("**Location:** Lines $lineRange")
                    
                    if (isCocosFile) {
                        appendLine("**Framework:** Cocos2d-x JavaScript")
                        appendLine()
                        appendLine("**Important:** Use OLD VERSION cocos2d-x-js syntax:")
                        appendLine("- Use `cc.Node()` instead of `cc.Node.create()`")
                        appendLine("- Use `cc.Sprite()` instead of `cc.Sprite.create()`")
                        appendLine("- Use `.extend()` pattern for class inheritance")
                        appendLine("- Use object literal methods: `methodName: function() {...}`")
                    }
                } else {
                    // For Java/Kotlin, it's a method FQN
                    appendLine("**Method:** `${result.fqn}`")
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
                
                if (result.codeContext.isNotBlank()) {
                    appendLine("**Current code:**")
                    appendLine("```$language")
                    appendLine(result.codeContext)
                    appendLine("```")
                    appendLine()
                }
                
                if (issue.codeSnippet != null && issue.codeSnippet.isNotBlank()) {
                    appendLine("**Problematic code snippet:**")
                    appendLine("```$language")
                    appendLine(issue.codeSnippet)
                    appendLine("```")
                    appendLine()
                }
                
                if (issue.lineNumbers.isNotEmpty()) {
                    appendLine("**Affected lines:** ${issue.lineNumbers.joinToString(", ")}")
                    appendLine()
                }
                
                if (!isJsTsFile && issue.callerSnippets.isNotEmpty()) {
                    // Caller snippets are only available for Java/Kotlin
                    appendLine("**Example usage in callers:**")
                    issue.callerSnippets.take(2).forEach { snippet ->
                        appendLine("- Called by `${snippet.callerFqn}`: ${snippet.context}")
                    }
                    appendLine()
                }
                
                appendLine("Please provide the fixed code with explanations of the changes made.")
            }
        }
        
        /**
         * Send the fix prompt to chat box
         */
        private fun sendPromptToChatBox(prompt: String, methodFqn: String, issue: CodeHealthAnalyzer.HealthIssue) {
            println("[CodeHealthNotification] Sending fix prompt to chat box for: $methodFqn - ${issue.title}")
            
            // Check if it's a Cocos2d-x file
            val isCocosFile = prompt.contains("cocos2d-x") || prompt.contains("cc.") || methodFqn.contains("cocos")
            
            // Determine the language from the FQN
            val language = when {
                isCocosFile -> "Cocos2d-x JavaScript"
                methodFqn.contains(".ts:") || methodFqn.contains(".tsx:") || methodFqn.contains(".mts:") -> "TypeScript"
                methodFqn.contains(".js:") || methodFqn.contains(".jsx:") || methodFqn.contains(".mjs:") -> "JavaScript"
                methodFqn.endsWith(".java") || methodFqn.contains(".java.") -> "Java"
                methodFqn.endsWith(".kt") || methodFqn.contains(".kt.") -> "Kotlin"
                else -> "code"
            }
            
            val systemPrompt = if (isCocosFile) {
                "You are a helpful AI assistant that fixes Cocos2d-x JavaScript code issues. " +
                "Always use OLD VERSION cocos2d-x-js syntax: use cc.Node() instead of cc.Node.create(), " +
                "use cc.Sprite() instead of cc.Sprite.create(), use .extend() pattern for class inheritance. " +
                "Focus on the specific issue described and provide clear, working code as the solution. " +
                "Explain what changes you made and why they fix the issue."
            } else {
                "You are a helpful AI assistant that fixes $language code issues. " +
                "Focus on the specific issue described and provide clear, working code as the solution. " +
                "Explain what changes you made and why they fix the issue."
            }
            
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
            }
        }
        
        /**
         * Track metrics for fix now click
         */
        private fun trackFixNowClick(issue: CodeHealthAnalyzer.HealthIssue) {
            try {
                val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
                
                val metadata = mapOf(
                    "issue_category" to issue.issueCategory,
                    "severity" to issue.severity,
                    "issue_title" to issue.title,
                    "user_action" to "fix_now_clicked"
                )
                
                metricsService.trackCustomEvent(
                    eventId = "health_fix_${System.currentTimeMillis()}",
                    eventType = "CODE_HEALTH_LOGGING|fix",
                    actualModel = "local-model-mini",
                    metadata = metadata
                )
                
            } catch (e: Exception) {
                println("[CodeHealthNotification] Failed to track fix click: ${e.message}")
            }
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
                    
                    a {
                        color: ${if (isDarkTheme) "#6897bb" else "#2470b3"};
                        text-decoration: none;
                        font-weight: bold;
                    }
                    
                    a:hover {
                        text-decoration: underline;
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
                                    <div style="display: flex; justify-content: space-between; align-items: center;">
                                        <div class="method-header">${escapeHtml(result.fqn)}</div>
                                        <a href="goto://${result.fqn}" style="color: $linkColor; font-size: 14px;">📍 Go to method</a>
                                    </div>
                                    <div class="method-stats">
                                        Health: ${result.healthScore}/100 | 
                                        Edits: ${result.modificationCount}x | 
                                        Impact: ${result.impactedCallers.size} callers | 
                                        Found: ${verifiedIssues.size} issues
                                    </div>
                                    
                                    ${verifiedIssues.sortedByDescending { it.severity }.mapIndexed { issueIndex, issue ->
                                        // Create a unique ID for this issue
                                        val issueId = "${results.indexOf(result)}_$issueIndex"
                                        
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
                                            
                                            <div style="text-align: right; margin-top: 10px;">
                                                <b><a href="fix://$issueId" style="color: ${if (isDarkTheme) "#4a9eff" else "#0066cc"}; font-size: 14px;">🔧 Fix now with AI</a></b>
                                            </div>
                                        </div>
                                        """.trimIndent()
                                    }.joinToString("")}
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
    */
}
