package com.zps.zest.codehealth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Manual trigger action for code health check
 */
class CheckHealthAction : AnAction("Check Code Health Now") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Trigger immediate health check
        CodeHealthTracker.getInstance(project).checkAndNotify()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
