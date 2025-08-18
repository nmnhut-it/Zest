package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.components.service
import com.intellij.util.messages.MessageBusConnection

/**
 * Handles cache invalidation for background context manager
 * based on file system events and git operations
 */
class ZestCacheInvalidator(private val project: Project) {
    private val logger = Logger.getInstance(ZestCacheInvalidator::class.java)
    
    // Background context manager
    private val backgroundManager by lazy { 
        project.service<ZestBackgroundContextManager>()
    }
    
    // Message bus connection for file events
    private var messageBusConnection: MessageBusConnection? = null
    
    /**
     * Start listening for cache invalidation events
     */
    fun startListening() {
        logger.info("Starting cache invalidation listeners")
        
        // Listen to file system events
        messageBusConnection = project.messageBus.connect().apply {
            subscribe(VirtualFileManager.VFS_CHANGES, FileChangeListener())
        }
    }
    
    /**
     * Stop listening for cache invalidation events
     */
    fun stopListening() {
        logger.info("Stopping cache invalidation listeners")
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }
    
    /**
     * Handle file change events
     */
    private inner class FileChangeListener : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            events.forEach { event ->
                handleFileEvent(event)
            }
        }
    }
    
    /**
     * Process individual file events
     */
    private fun handleFileEvent(event: VFileEvent) {
        val file = event.file ?: return
        
        when (event) {
            is VFileContentChangeEvent -> {
                handleFileContentChange(file)
            }
            is VFileCreateEvent -> {
                handleFileCreate(file)
            }
            is VFileDeleteEvent -> {
                handleFileDelete(file)
            }
        }
    }
    
    /**
     * Handle file content changes
     */
    private fun handleFileContentChange(file: VirtualFile) {
        System.out.println("File content changed: ${file.path}")
        
        // Invalidate file-specific cache
        backgroundManager.invalidateFileContext(file.path)
        
        // Check if this affects git context
        if (shouldTriggerGitRefresh(file)) {
            System.out.println("Triggering git context refresh due to ${file.name}")
            backgroundManager.scheduleGitRefresh()
        }
    }
    
    /**
     * Handle file creation
     */
    private fun handleFileCreate(file: VirtualFile) {
        System.out.println("File created: ${file.path}")
        
        // New files often affect git context
        if (shouldTriggerGitRefresh(file)) {
            backgroundManager.scheduleGitRefresh()
        }
    }
    
    /**
     * Handle file deletion
     */
    private fun handleFileDelete(file: VirtualFile) {
        System.out.println("File deleted: ${file.path}")
        
        // Remove from file cache
        backgroundManager.invalidateFileContext(file.path)
        
        // Deletions often affect git context
        if (shouldTriggerGitRefresh(file)) {
            backgroundManager.scheduleGitRefresh()
        }
    }
    
    /**
     * Determine if file change should trigger git context refresh
     */
    private fun shouldTriggerGitRefresh(file: VirtualFile): Boolean {
        return when {
            // Always refresh for code files
            isCodeFile(file) -> true
            
            // Refresh for config files that might affect build
            isConfigFile(file) -> true
            
            // Refresh for git-related files
            isGitRelatedFile(file) -> true
            
            // Skip for temp files, IDE files, etc.
            else -> false
        }
    }
    
    /**
     * Check if file is a code file
     */
    private fun isCodeFile(file: VirtualFile): Boolean {
        val codeExtensions = setOf(
            "java", "kt", "js", "ts", "py", "html", "css", "xml", 
            "json", "yaml", "yml", "sql", "groovy", "scala", "go", "rs"
        )
        return codeExtensions.contains(file.extension?.lowercase())
    }
    
    /**
     * Check if file is a configuration file
     */
    private fun isConfigFile(file: VirtualFile): Boolean {
        val configFiles = setOf(
            "build.gradle", "build.gradle.kts", "pom.xml", "package.json",
            "settings.gradle", "settings.gradle.kts", "gradle.properties"
        )
        val configExtensions = setOf("properties", "conf", "config", "ini")
        
        return configFiles.contains(file.name) || 
               configExtensions.contains(file.extension?.lowercase())
    }
    
    /**
     * Check if file is git-related
     */
    private fun isGitRelatedFile(file: VirtualFile): Boolean {
        val path = file.path.lowercase()
        return path.contains("/.git/") || 
               file.name.startsWith(".git") ||
               file.name == ".gitignore" ||
               file.name == ".gitattributes"
    }
    
    /**
     * Manual cache invalidation for specific scenarios
     */
    fun invalidateGitContext() {
        System.out.println("Manual git context invalidation requested")
        backgroundManager.scheduleGitRefresh()
    }
    
    /**
     * Manual cache invalidation for all contexts
     */
    fun invalidateAllCaches() {
        System.out.println("Manual invalidation of all caches requested")
        backgroundManager.invalidateAllCaches()
    }
    
    /**
     * Invalidate context for specific file path
     */
    fun invalidateFileContext(filePath: String) {
        System.out.println("Manual file context invalidation for: $filePath")
        backgroundManager.invalidateFileContext(filePath)
    }
    
    companion object {
        // File change batching to avoid excessive refreshes
        private const val GIT_REFRESH_DEBOUNCE_MS = 1000L
    }
}
