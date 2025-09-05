package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.zps.zest.ConfigurationManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that proactively pre-populates context cache for files
 * to eliminate completion delays from expensive PSI analysis.
 */
@Service(Service.Level.PROJECT)
class FileContextPrePopulationService(private val project: Project) {
    
    private val logger = Logger.getInstance(FileContextPrePopulationService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache and analysis components
    private val cache = project.getService(LeanContextCache::class.java)
    private val contextCollector = ZestLeanContextCollectorPSI(project)
    
    // File processing queue with priority
    private val analysisQueue = PriorityBlockingQueue<FileAnalysisTask>()
    private val processingFiles = ConcurrentHashMap<String, Long>()
    private val isProcessing = AtomicBoolean(false)
    
    // File priority tracking
    private val recentlyEditedFiles = ConcurrentHashMap<String, Long>()
    private val activeFiles = ConcurrentHashMap<String, Long>()
    
    data class FileAnalysisTask(
        val filePath: String,
        val virtualFile: VirtualFile,
        val priority: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<FileAnalysisTask> {
        override fun compareTo(other: FileAnalysisTask): Int {
            // Higher priority first, then newer timestamp
            return if (priority != other.priority) {
                other.priority.compareTo(priority) // Reverse for higher first
            } else {
                other.timestamp.compareTo(timestamp) // Newer first
            }
        }
    }
    
    init {
        setupFileListeners()
        startBackgroundProcessor()
        logger.info("FileContextPrePopulationService initialized for project: ${project.name}")
    }
    
    /**
     * Set up listeners for file events to trigger cache pre-population
     */
    private fun setupFileListeners() {
        val connection = project.messageBus.connect()
        
        // Listen for file editor changes (file opened/focused)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let { virtualFile ->
                    if (isJavaFile(virtualFile)) {
                        scheduleFileAnalysis(virtualFile, priority = 100) // High priority for active files
                        activeFiles[virtualFile.path] = System.currentTimeMillis()
                    }
                }
            }
        })
        
        // Listen for file content changes (file saved/modified)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                events.forEach { event ->
                    event.file?.let { virtualFile ->
                        if (isJavaFile(virtualFile)) {
                            // Invalidate existing cache
                            cache.invalidateFileCache(virtualFile.path)
                            
                            // Schedule re-analysis with high priority
                            scheduleFileAnalysis(virtualFile, priority = 90)
                            recentlyEditedFiles[virtualFile.path] = System.currentTimeMillis()
                        }
                    }
                }
            }
        })
        
        // Pre-populate cache for currently open files
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFiles.forEach { virtualFile ->
                if (isJavaFile(virtualFile)) {
                    scheduleFileAnalysis(virtualFile, priority = 80)
                }
            }
        }
    }
    
    /**
     * Schedule analysis for a file with given priority
     */
    private fun scheduleFileAnalysis(virtualFile: VirtualFile, priority: Int) {
        val filePath = virtualFile.path
        
        // Skip if already being processed recently
        val lastProcessed = processingFiles[filePath]
        if (lastProcessed != null && System.currentTimeMillis() - lastProcessed < 5000) {
            return
        }
        
        val task = FileAnalysisTask(filePath, virtualFile, priority)
        analysisQueue.offer(task)
        
        logger.debug("Scheduled analysis for: $filePath (priority: $priority)")
    }
    
    /**
     * Start background processor for file analysis queue
     */
    private fun startBackgroundProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    val task = analysisQueue.poll()
                    if (task != null && !processingFiles.containsKey(task.filePath)) {
                        processFileAnalysisTask(task)
                    } else {
                        // Wait a bit if no tasks
                        delay(1000)
                    }
                } catch (e: Exception) {
                    logger.warn("Error in background file analysis processor", e)
                    delay(5000) // Back off on errors
                }
            }
        }
    }
    
    /**
     * Process a single file analysis task
     */
    private suspend fun processFileAnalysisTask(task: FileAnalysisTask) {
        val filePath = task.filePath
        val virtualFile = task.virtualFile
        
        // Mark as being processed
        processingFiles[filePath] = System.currentTimeMillis()
        
        try {
            logger.debug("Pre-populating context cache for: $filePath")
            
            // Get editor for the file
            val editor = withContext(Dispatchers.Main) {
                FileEditorManager.getInstance(project).openFiles
                    .firstOrNull { it.path == filePath }
                    ?.let { FileEditorManager.getInstance(project).getSelectedEditor(it) }
                    ?.let { FileEditorManager.getInstance(project).selectedTextEditor }
            }
            
            if (editor != null) {
                // Pre-analyze multiple positions in the file for common completion points
                analyzeFileHotZones(editor, virtualFile)
            } else {
                // File not open in editor - analyze based on file content
                analyzeFileStructure(virtualFile)
            }
            
            logger.debug("Completed cache pre-population for: $filePath")
            
        } catch (e: Exception) {
            logger.warn("Failed to pre-populate context for: $filePath", e)
        } finally {
            processingFiles.remove(filePath)
        }
    }
    
    /**
     * Analyze hot zones in an open file (likely completion points)
     */
    private suspend fun analyzeFileHotZones(editor: Editor, virtualFile: VirtualFile) {
        val document = editor.document
        val text = document.text
        
        // Find potential completion points (end of lines, after braces, etc.)
        val hotZones = findCompletionHotZones(text)
        
        // Pre-populate cache for each hot zone
        hotZones.forEach { offset ->
            try {
                // Use existing context collector to populate cache
                contextCollector.collectFullFileContext(editor, offset)
                // The collector will automatically cache the result
            } catch (e: Exception) {
                logger.debug("Failed to analyze hot zone at offset $offset: ${e.message}")
            }
        }
        
        logger.debug("Pre-populated ${hotZones.size} hot zones for: ${virtualFile.path}")
    }
    
    /**
     * Analyze file structure without editor (for closed files)
     */
    private suspend fun analyzeFileStructure(virtualFile: VirtualFile) {
        withContext(Dispatchers.Main) {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is PsiJavaFile) {
                    // Use ClassAnalyzer to pre-analyze file structure
                    try {
                        com.zps.zest.ClassAnalyzer.collectClassContext(psiFile.classes.firstOrNull())
                        logger.debug("Pre-analyzed file structure for: ${virtualFile.path}")
                    } catch (e: Exception) {
                        logger.debug("Failed to analyze file structure: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Find likely completion points in code
     */
    private fun findCompletionHotZones(text: String): List<Int> {
        val hotZones = mutableListOf<Int>()
        val lines = text.lines()
        
        var currentOffset = 0
        lines.forEach { line ->
            val trimmed = line.trim()
            
            // Add hot zones for:
            // - End of method signatures
            // - After opening braces
            // - End of statements
            // - After field declarations
            when {
                trimmed.matches(Regex(".*\\)\\s*\\{?\\s*")) -> {
                    // Method signature - add completion point after opening brace
                    val braceIndex = line.indexOf('{')
                    if (braceIndex >= 0) {
                        hotZones.add(currentOffset + braceIndex + 1)
                    }
                }
                trimmed.endsWith(";") -> {
                    // Statement end - add completion point on next line
                    hotZones.add(currentOffset + line.length)
                }
                trimmed.matches(Regex(".*\\{\\s*")) -> {
                    // Opening brace - add completion point inside
                    hotZones.add(currentOffset + line.length)
                }
            }
            
            currentOffset += line.length + 1 // +1 for newline
        }
        
        return hotZones.take(20) // Limit to top 20 hot zones per file
    }
    
    /**
     * Check if file is a Java file worth analyzing
     */
    private fun isJavaFile(virtualFile: VirtualFile): Boolean {
        return virtualFile.extension == "java" && 
               !virtualFile.path.contains("/build/") && 
               !virtualFile.path.contains(".class")
    }
    
    /**
     * Force re-analysis of a specific file
     */
    fun forceAnalyzeFile(virtualFile: VirtualFile) {
        if (isJavaFile(virtualFile)) {
            cache.invalidateFileCache(virtualFile.path)
            scheduleFileAnalysis(virtualFile, priority = 100)
        }
    }
    
    /**
     * Get cache statistics for monitoring
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "queueSize" to analysisQueue.size,
            "processingFiles" to processingFiles.size,
            "activeFiles" to activeFiles.size,
            "recentlyEditedFiles" to recentlyEditedFiles.size
        ) + cache.getStats()
    }
    
    fun dispose() {
        scope.cancel()
        processingFiles.clear()
        analysisQueue.clear()
        logger.info("FileContextPrePopulationService disposed")
    }
}