package com.zps.zest.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zps.zest.completion.debug.CompletionDebugConfig

/**
 * Action to toggle verbose debug logging for completion
 */
class ToggleCompletionDebugAction : AnAction("Toggle Completion Debug Logging") {
    private var verboseEnabled = false
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        verboseEnabled = !verboseEnabled
        
        if (verboseEnabled) {
            CompletionDebugConfig.enableVerboseLogging(project)
        } else {
            CompletionDebugConfig.disableVerboseLogging(project)
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.text = if (verboseEnabled) {
            "Disable Completion Debug Logging"
        } else {
            "Enable Completion Debug Logging"
        }
    }
}
