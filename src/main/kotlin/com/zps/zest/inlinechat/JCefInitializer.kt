package com.zps.zest.inlinechat

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefApp

/**
 * Initializes JCef settings on project startup
 */
class JCefInitializer : ProjectActivity {
    companion object {
        private val LOG = Logger.getInstance(JCefInitializer::class.java)
        
        init {
            // Set JCef properties before any JCef usage
            System.setProperty("ide.browser.jcef.enabled", "true")
            System.setProperty("ide.browser.jcef.debug.port", "9222")
            System.setProperty("ide.browser.jcef.headless", "false")
            System.setProperty("ide.browser.jcef.sandbox", "false")
            
            // Enable remote debugging
            System.setProperty("ide.browser.jcef.debug", "true")
        }
    }
    
    override suspend fun execute(project: Project) {
        LOG.info("Initializing JCef settings for project: ${project.name}")
        
        // Check if JCef is supported
        if (JBCefApp.isSupported()) {
            LOG.info("JCef is supported")
            
            // Get JCef instance to ensure it's initialized
            val instance = JBCefApp.getInstance()
            LOG.info("JCef instance: $instance")
        } else {
            LOG.warn("JCef is not supported in this environment")
            LOG.warn("Please check if 'ide.browser.jcef.enabled' is set to true in Registry")
        }
    }
}
