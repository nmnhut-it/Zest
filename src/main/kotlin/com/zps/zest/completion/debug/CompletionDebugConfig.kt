package com.zps.zest.completion.debug

import com.intellij.openapi.project.Project
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestCompletionProvider

/**
 * Debug configuration for completion system
 */
object CompletionDebugConfig {
    /**
     * Enable verbose debug logging for all completion components
     */
    fun enableVerboseLogging(project: Project) {
        // Enable verbose logging in completion service
        val completionService = project.getService(ZestInlineCompletionService::class.java)
        completionService?.setDebugLogging(enabled = true, verbose = true)
        
        // Enable verbose logging in completion provider
        val completionProvider = try {
            val providerField = ZestInlineCompletionService::class.java.getDeclaredField("completionProvider")
            providerField.isAccessible = true
            providerField.get(completionService) as? ZestCompletionProvider
        } catch (e: Exception) {
            null
        }
        
        completionProvider?.setDebugLogging(enabled = true, verbose = true)
        
        println("[CompletionDebugConfig] Verbose logging enabled for project: ${project.name}")
    }
    
    /**
     * Disable verbose debug logging
     */
    fun disableVerboseLogging(project: Project) {
        val completionService = project.getService(ZestInlineCompletionService::class.java)
        completionService?.setDebugLogging(enabled = false, verbose = false)
        
        println("[CompletionDebugConfig] Verbose logging disabled for project: ${project.name}")
    }
}
