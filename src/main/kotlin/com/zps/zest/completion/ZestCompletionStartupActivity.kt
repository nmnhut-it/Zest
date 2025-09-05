package com.zps.zest.completion

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zps.zest.completion.context.FileContextPrePopulationService

/**
 * Simplified startup activity for Zest completion services
 */
class ZestCompletionStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(ZestCompletionStartupActivity::class.java)
    
    override suspend fun execute(project: Project) {
        logger.info("Initializing simplified Zest completion services for project: ${project.name}")
        
        try {
            // Check if inline completion is enabled
            val config = com.zps.zest.ConfigurationManager.getInstance(project)
            if (!config.isInlineCompletionEnabled()) {
                logger.info("Inline completion disabled by configuration, skipping initialization")
                return
            }
            
            // Initialize the file context pre-population service
            project.serviceOrNull<FileContextPrePopulationService>()?.let {
                logger.info("File context pre-population service initialized")
            }
            
            // Initialize the completion service only if enabled
            project.serviceOrNull<ZestInlineCompletionService>()
            
            logger.info("Simplified Zest completion services initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize Zest completion services", e)
        }
    }
}
