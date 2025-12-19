package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.zps.zest.codehealth.ProjectChangesTracker
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Test action to show the current status of the review queue
 */
class ShowReviewQueueStatusAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StatusDialog(project).show()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
    
    private class StatusDialog(private val project: com.intellij.openapi.project.Project) : DialogWrapper(project) {
        
        init {
            title = "Code Health Review Queue Status"
            init()
        }
        
        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            val reviewer = BackgroundHealthReviewer.getInstance(project)
            val tracker = ProjectChangesTracker.getInstance(project)
            val stats = reviewer.getQueueStats()
            val reviewedMethods = reviewer.getReviewedMethods()
            val trackedMethods = tracker.getModifiedMethodDetails()
            
            val content = buildString {
                appendLine("BACKGROUND REVIEW QUEUE")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Queue Statistics:")
                appendLine("  - Pending review: ${stats.pendingCount} methods")
                appendLine("  - Ready for review (10+ min inactive): ${stats.readyForReviewCount} methods")
                appendLine("  - Already reviewed: ${stats.reviewedCount} methods")
                appendLine("  - Total issues found: ${stats.totalIssuesFound}")
                appendLine()
                
                appendLine("TRACKED METHODS")
                appendLine("=".repeat(50))
                appendLine("  - Total tracked: ${trackedMethods.size} methods")
                appendLine()
                
                if (reviewedMethods.isNotEmpty()) {
                    appendLine("PRE-REVIEWED METHODS (Cached for 13h report)")
                    appendLine("=".repeat(50))
                    reviewedMethods.forEach { (fqn, result) ->
                        appendLine()
                        appendLine("Method: $fqn")
                        appendLine("  - Health Score: ${result.healthScore}/100")
                        appendLine("  - Issues: ${result.issues.size}")
                        appendLine("  - Verified Issues: ${result.issues.count { it.shouldDisplay() }}")
                        if (result.issues.isNotEmpty()) {
                            result.issues.filter { it.shouldDisplay() }.forEach { issue ->
                                appendLine("    * [${issue.issueCategory}] ${issue.title} (Severity: ${issue.severity}/5)")
                            }
                        }
                    }
                    appendLine()
                }
                
                if (trackedMethods.isNotEmpty()) {
                    appendLine("ALL MODIFIED METHODS")
                    appendLine("=".repeat(50))
                    trackedMethods.forEach { method ->
                        val isReviewed = reviewedMethods.containsKey(method.fqn)
                        val timeSinceModified = (System.currentTimeMillis() - method.lastModified) / 1000 / 60 // minutes
                        
                        appendLine()
                        appendLine("${method.fqn}")
                        appendLine("  - Modified: ${method.modificationCount} times")
                        appendLine("  - Last modified: $timeSinceModified minutes ago")
                        appendLine("  - Status: ${if (isReviewed) "✓ Pre-reviewed" else if (timeSinceModified >= 10) "Ready for review" else "Waiting ($timeSinceModified/10 min)"}")
                    }
                }
                
                appendLine()
                appendLine("-".repeat(50))
                appendLine("Next scheduled report: ${tracker.state.checkTime}")
                appendLine("Analysis running: ${tracker.isAnalysisRunning.get()}")
            }
            
            val textArea = JTextArea(content).apply {
                isEditable = false
                font = UIUtil.getLabelFont()
            }
            
            val scrollPane = JBScrollPane(textArea).apply {
                preferredSize = Dimension(800, 600)
            }
            
            panel.add(scrollPane, BorderLayout.CENTER)
            
            return panel
        }
        
        override fun createActions(): Array<Action> {
            return arrayOf(okAction)
        }
    }
}
