package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.codehealth.CodeHealthConfigurable
import com.zps.zest.codehealth.CodeHealthTracker

/**
 * Test action for JS/TS health tracking
 */
class TestJsTsHealthAction : AnAction("Test JS/TS Health") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Enable JS/TS support
        CodeHealthConfigurable.ENABLE_JS_TS_SUPPORT = true
        
        // Get tracker
        val tracker = CodeHealthTracker.getInstance(project)
        
        // Add a test JS method
        tracker.trackMethodModification("test.js:123")
        tracker.trackMethodModification("test.ts:456")
        
        // Get all tracked methods
        val methods = tracker.getModifiedMethodDetails()
        
        val message = buildString {
            appendLine("JS/TS Health Test")
            appendLine("=================")
            appendLine("Enabled: ${CodeHealthConfigurable.ENABLE_JS_TS_SUPPORT}")
            appendLine("Total tracked: ${methods.size}")
            methods.forEach { method ->
                appendLine("- ${method.fqn} (${method.modificationCount}x)")
            }
        }
        
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Guardian")
            .createNotification("JS/TS Test", message, NotificationType.INFORMATION)
            .notify(project)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
