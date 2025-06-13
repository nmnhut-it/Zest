package com.zps.zest.completion

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Simplified startup activity for Zest completion services
 */
class ZestCompletionStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(ZestCompletionStartupActivity::class.java)
    
    override suspend fun execute(project: Project) {
        logger.info("Initializing simplified Zest completion services for project: ${project.name}")
        
        try {
            // Initialize the completion service (simplified - no background processing)
            project.serviceOrNull<ZestInlineCompletionService>()
            
            logger.info("Simplified Zest completion services initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize Zest completion services", e)
        }
    }
}
