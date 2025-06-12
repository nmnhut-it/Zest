package com.zps.zest.completion

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zps.zest.completion.context.ZestBackgroundContextManager
import com.zps.zest.completion.context.ZestCacheInvalidator

/**
 * Startup activity to initialize Zest completion services and background context collection
 */
class ZestCompletionStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(ZestCompletionStartupActivity::class.java)
    
    override suspend fun execute(project: Project) {
        logger.info("Initializing Zest completion services for project: ${project.name}")
        
        try {
            // Initialize the completion service
            project.serviceOrNull<ZestInlineCompletionService>()
            
            // Initialize and start background context manager
            val backgroundManager = project.serviceOrNull<ZestBackgroundContextManager>()
            backgroundManager?.startBackgroundCollection()
            
            // Initialize cache invalidator
            val cacheInvalidator = ZestCacheInvalidator(project)
            cacheInvalidator.startListening()
            
            logger.info("Zest completion services initialized successfully with background context collection")
        } catch (e: Exception) {
            logger.error("Failed to initialize Zest completion services", e)
        }
    }
}
