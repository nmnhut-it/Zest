package com.zps.zest.codehealth

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.wm.WindowManager

/**
 * Startup activity to ensure Code Guardian status bar widget is initialized
 */
class CodeGuardianStartupActivity : StartupActivity {
    
    override fun runActivity(project: Project) {
        // Ensure the status bar widget is visible
        try {
            val windowManager = WindowManager.getInstance()
            val statusBar = windowManager.getStatusBar(project)
            
            if (statusBar != null) {
                // The widget should be automatically created by the factory
                // Just log that we're ready
                println("[CodeGuardian] Status bar widget ready for project: ${project.name}")
            }
        } catch (e: Exception) {
            println("[CodeGuardian] Error initializing status bar widget: ${e.message}")
        }
    }
}
