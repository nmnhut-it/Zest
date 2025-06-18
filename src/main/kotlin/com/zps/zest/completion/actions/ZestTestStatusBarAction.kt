package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Test action to verify status bar widget functionality
 */
class ZestTestStatusBarAction : AnAction("Test Status Bar Widget", "Test the Zest completion status bar widget", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        val widget = statusBar?.getWidget(ZestCompletionStatusBarWidget.WIDGET_ID) as? ZestCompletionStatusBarWidget
        
        if (widget != null) {
            // Test state transitions
            testStatusBarStates(project, widget)
            
            Messages.showInfoMessage(
                project,
                "Status bar widget test completed. Check the status bar for state changes.",
                "Zest Status Bar Test"
            )
        } else {
            Messages.showErrorDialog(
                project,
                "Status bar widget not found. Make sure the plugin is loaded correctly.",
                "Zest Status Bar Test Error"
            )
        }
    }
    
    private fun testStatusBarStates(project: Project, widget: ZestCompletionStatusBarWidget) {
        val completionService = project.getService(ZestInlineCompletionService::class.java)
        
        // Test state transitions with delays to see the changes
        widget.updateState(ZestCompletionStatusBarWidget.CompletionState.REQUESTING)
        
        Thread.sleep(1000)
        widget.updateState(ZestCompletionStatusBarWidget.CompletionState.WAITING)
        
        Thread.sleep(1000)
        widget.updateState(ZestCompletionStatusBarWidget.CompletionState.ACCEPTING)
        
        Thread.sleep(1000)
        widget.updateState(ZestCompletionStatusBarWidget.CompletionState.IDLE)
        
        // Show debug info
        val stateInfo = completionService.getCompletionStateInfo()
        println("=== Status Bar Test ===")
        println("Current state info: $stateInfo")
        println("Cache stats: ${completionService.getCacheStats()}")
    }
}
