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
        
        // Aggressively analyze all currently open files on startup
        scope.launch {
            delay(2000) // Wait for IDE to settle
            analyzeAllOpenFiles()
        }
        
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
                        // Immediate high-priority analysis for active files
                        scope.launch {
                            logger.debug("File activated: ${virtualFile.path} - starting immediate context analysis")
                            performImmediateFileAnalysis(virtualFile)
                        }
                        
                        // Also schedule for background queue in case immediate analysis fails
                        scheduleFileAnalysis(virtualFile, priority = 100)
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
     * Perform immediate comprehensive analysis for newly opened/activated files
     * This front-loads the expensive file-scoped context collection
     */
    private suspend fun performImmediateFileAnalysis(virtualFile: VirtualFile) {
        val filePath = virtualFile.path
        
        try {
            // Skip if we've already analyzed this recently
            val lastAnalysis = processingFiles[filePath]
            if (lastAnalysis != null && (System.currentTimeMillis() - lastAnalysis) < 30_000) {
                logger.debug("Skipping immediate analysis - recently processed: $filePath")
                return
            }
            
            logger.info("Starting immediate file-scoped analysis: $filePath")
            val startTime = System.currentTimeMillis()
            
            // Get editor for the file (EDT operation)
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            
            if (editor != null) {
                // Collect comprehensive file-scoped context at multiple strategic positions
                // This gives us full coverage without waiting for user cursor movements
                analyzeComprehensiveFileContext(editor, virtualFile)
                
                // Pre-analyze all open files for cross-file dependencies
                analyzeRelatedOpenFiles(virtualFile)
                
                val elapsedTime = System.currentTimeMillis() - startTime
                logger.info("Completed immediate file analysis in ${elapsedTime}ms: $filePath")
                
            } else {
                logger.debug("No editor available for immediate analysis: $filePath")
            }
            
        } catch (e: Exception) {
            logger.warn("Failed immediate file analysis: $filePath", e)
        }
    }
    
    /**
     * Comprehensive file context analysis - collect context at strategic positions
     */
    private suspend fun analyzeComprehensiveFileContext(editor: Editor, virtualFile: VirtualFile) {
        val document = editor.document
        val text = document.text
        val lines = text.lines()
        
        // Find all strategic positions where completions are likely needed
        val analysisPoints = findStrategicAnalysisPoints(text)
        logger.debug("Found ${analysisPoints.size} strategic analysis points in ${virtualFile.path}")
        
        // Collect context at each strategic point
        analysisPoints.take(10).forEach { offset -> // Limit to prevent excessive analysis
            try {
                // Use async context collection to prevent freezes
                scope.launch {
                    contextCollector.collectFullFileContext(editor, offset)
                    // Results are automatically cached by the collector
                }
            } catch (e: Exception) {
                logger.debug("Failed to analyze point at offset $offset: ${e.message}")
            }
        }
    }
    
    /**
     * Find strategic positions for comprehensive context analysis
     * These are points where users are most likely to request completions
     */
    private fun findStrategicAnalysisPoints(text: String): List<Int> {
        val points = mutableListOf<Int>()
        val lines = text.lines()
        
        var currentOffset = 0
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            
            // Strategic completion points:
            when {
                // 1. Start of method bodies (after opening brace)
                trimmed.matches(Regex(".*\\{\\s*")) -> {
                    points.add(currentOffset + line.indexOf('{') + 1)
                }
                // 2. Empty lines inside methods/classes
                trimmed.isEmpty() && isInsideMethod(lineIndex, lines) -> {
                    points.add(currentOffset)
                }
                // 3. After method calls (semicolon positions)
                trimmed.endsWith(';') -> {
                    points.add(currentOffset + line.length)
                }
                // 4. After field declarations
                trimmed.matches(Regex(".*\\s+(\\w+\\s+)*\\w+\\s*;\\s*")) -> {
                    points.add(currentOffset + line.length)
                }
                // 5. After import statements (for new import suggestions)
                trimmed.startsWith("import ") && trimmed.endsWith(';') -> {
                    points.add(currentOffset + line.length)
                }
            }
            
            currentOffset += line.length + 1 // +1 for newline
        }
        
        return points.distinct().sorted()
    }
    
    /**
     * Simple heuristic to detect if a line is inside a method body
     */
    private fun isInsideMethod(lineIndex: Int, lines: List<String>): Boolean {
        var braceCount = 0
        var foundMethodStart = false
        
        // Look backwards from current line
        for (i in lineIndex downTo 0) {
            val line = lines[i].trim()
            
            // Count braces
            braceCount += line.count { it == '}' }
            braceCount -= line.count { it == '{' }
            
            // Look for method signatures
            if (line.matches(Regex(".*\\)\\s*\\{.*"))) {
                foundMethodStart = true
                break
            }
            
            // If we've closed all braces, we're not in a method
            if (braceCount > 0) break
        }
        
        return foundMethodStart && braceCount <= 0
    }
    
    /**
     * Analyze related files that are currently open to build cross-file context
     */
    private suspend fun analyzeRelatedOpenFiles(currentFile: VirtualFile) {
        try {
            val openFiles = FileEditorManager.getInstance(project).openFiles
                .filter { it != currentFile && isJavaFile(it) }
                .take(5) // Limit to avoid overwhelming
            
            logger.debug("Analyzing ${openFiles.size} related open files for cross-file context")
            
            openFiles.forEach { relatedFile ->
                // Schedule background analysis for related files if not recently done
                val lastProcessed = processingFiles[relatedFile.path]
                if (lastProcessed == null || (System.currentTimeMillis() - lastProcessed) > 60_000) {
                    scheduleFileAnalysis(relatedFile, priority = 70) // Medium priority
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to analyze related open files: ${e.message}")
        }
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
            
            // Get editor for the file (EDT operation)
            val editor = FileEditorManager.getInstance(project).openFiles
                .firstOrNull { it.path == filePath }
                ?.let { FileEditorManager.getInstance(project).getSelectedEditor(it) }
                ?.let { FileEditorManager.getInstance(project).selectedTextEditor }
            
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
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runReadAction {
                // Check if the virtual file is still valid before accessing it
                if (!virtualFile.isValid) {
                    logger.debug("Skipping invalid virtual file: ${virtualFile.path}")
                    return@runReadAction
                }
                
                try {
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile is PsiJavaFile) {
                        // Use ClassAnalyzer to pre-analyze file structure
                        com.zps.zest.ClassAnalyzer.collectClassContext(psiFile.classes.firstOrNull())
                        logger.debug("Pre-analyzed file structure for: ${virtualFile.path}")
                    }
                } catch (e: com.intellij.openapi.vfs.InvalidVirtualFileAccessException) {
                    logger.debug("Virtual file became invalid during analysis: ${virtualFile.path}")
                } catch (e: Exception) {
                    logger.debug("Failed to analyze file structure: ${e.message}")
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
     * Aggressively analyze all currently open files for comprehensive context caching
     * This front-loads context collection to minimize completion delays
     */
    private suspend fun analyzeAllOpenFiles() {
        try {
            val openFiles = FileEditorManager.getInstance(project).openFiles
                .filter { isJavaFile(it) }
            
            logger.info("Starting aggressive analysis of ${openFiles.size} open files for context pre-population")
            val startTime = System.currentTimeMillis()
            
            // Analyze each open file with high priority
            openFiles.forEachIndexed { index, virtualFile ->
                try {
                    logger.debug("Analyzing open file ${index + 1}/${openFiles.size}: ${virtualFile.path}")
                    
                    // Immediate analysis for comprehensive context
                    performImmediateFileAnalysis(virtualFile)
                    
                    // Small delay to prevent overwhelming the system
                    if (index < openFiles.size - 1) {
                        delay(100) // 100ms between files
                    }
                    
                } catch (e: Exception) {
                    logger.warn("Failed to analyze open file: ${virtualFile.path}", e)
                }
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            logger.info("Completed aggressive analysis of ${openFiles.size} files in ${elapsedTime}ms")
            logger.info("Context cache is now pre-populated for immediate completions")
            
        } catch (e: Exception) {
            logger.error("Failed aggressive file analysis", e)
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