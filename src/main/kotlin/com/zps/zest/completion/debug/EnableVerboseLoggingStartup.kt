package com.zps.zest.completion.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Temporary startup activity to enable verbose logging for debugging
 * Remove this after debugging is complete
 */
class EnableVerboseLoggingStartup : StartupActivity {
    override fun runActivity(project: Project) {
        // Automatically enable verbose logging on startup for debugging
        println("[EnableVerboseLoggingStartup] Enabling verbose completion logging for project: ${project.name}")
        CompletionDebugConfig.enableVerboseLogging(project)
    }
}
