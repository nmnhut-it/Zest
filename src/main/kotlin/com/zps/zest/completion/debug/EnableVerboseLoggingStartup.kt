package com.zps.zest.completion.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestCompletionProvider

/**
 * Temporary startup activity to enable verbose logging for debugging
 * Remove this after debugging is complete
 */
class EnableVerboseLoggingStartup : StartupActivity {
    override fun runActivity(project: Project) {
        // Automatically enable debug logging on startup for debugging
        // Set verbose = false by default to avoid too much spam
        println("[EnableVerboseLoggingStartup] Enabling completion logging for project: ${project.name}")
        
        // Enable normal debug logging but not verbose by default
        val completionService = project.getService(ZestInlineCompletionService::class.java)
        completionService?.setDebugLogging(enabled = true, verbose = false)
        
        // Enable debug logging in completion provider  
        val completionProvider = try {
            val providerField = ZestInlineCompletionService::class.java.getDeclaredField("completionProvider")
            providerField.isAccessible = true
            providerField.get(completionService) as? ZestCompletionProvider
        } catch (e: Exception) {
            null
        }
        
        completionProvider?.setDebugLogging(enabled = true, verbose = false)
    }
}
