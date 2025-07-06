package com.zps.zest.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Action to display completion timing debug information
 */
class ZestCompletionTimingDebugAction : AnAction("Zest Completion Timing Debug") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
        
        val debugReport = metricsService.generateTimingDebugReport()
        
        // Show dialog with the debug report
        val dialog = TimingDebugDialog(debugReport)
        dialog.show()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    
    private class TimingDebugDialog(private val debugReport: String) : DialogWrapper(true) {
        init {
            title = "Zest Completion Timing Debug"
            init()
        }
        
        override fun createCenterPanel(): JComponent {
            val textArea = JTextArea(debugReport).apply {
                isEditable = false
                font = font.deriveFont(12f)
            }
            
            return JScrollPane(textArea).apply {
                preferredSize = java.awt.Dimension(800, 600)
            }
        }
    }
}
