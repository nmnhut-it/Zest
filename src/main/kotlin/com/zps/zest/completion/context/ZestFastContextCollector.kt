package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast context collector that combines pre-cached background context
 * with real-time cursor-specific context for optimal performance
 * 
 * Note: This is a simplified version that works with the new lean context structure
 */
class ZestFastContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestFastContextCollector::class.java)
    
    /**
     * Thread-safe editor data captured from EDT
     */
    private data class EditorData(
        val document: Document,
        val text: String,
        val caretOffset: Int,
        val docLength: Int,
        val virtualFile: VirtualFile?
    )
    
    // Background context manager for cached data
    private val backgroundManager by lazy { 
        project.service<ZestBackgroundContextManager>()
    }
    
    // Lean context collector for actual context collection
    private val leanCollector = ZestLeanContextCollector(project)
    
    /**
     * Fast context collection using cached background data + real-time cursor context
     * Returns LeanContext since that's what the system expects now
     */
    suspend fun collectFastContext(editor: Editor, offset: Int): ZestLeanContextCollector.LeanContext {
        val startTime = System.currentTimeMillis()
        
        return try {
            // For now, delegate to the lean collector
            // TODO: Implement actual fast collection with cached data
            val context = leanCollector.collectFullFileContext(editor, offset)
            
            val collectTime = System.currentTimeMillis() - startTime
            System.out.println("Fast context collection completed in ${collectTime}ms")
            
            context
            
        } catch (e: Exception) {
            val fallbackTime = System.currentTimeMillis()
            logger.warn("Fast context collection failed, falling back to lean collection", e)
            
            // Fallback to lean collection
            val result = leanCollector.collectFullFileContext(editor, offset)
            val totalTime = System.currentTimeMillis() - startTime
            System.out.println("Fallback context collection completed in ${totalTime}ms")
            
            result
        }
    }
    
    /**
     * Check if fast context collection is available
     */
    fun isFastCollectionAvailable(): Boolean {
        return try {
            // For now, always return true since we have the lean fallback
            true
        } catch (e: Exception) {
            logger.warn("Error checking fast collection availability", e)
            false
        }
    }
}
