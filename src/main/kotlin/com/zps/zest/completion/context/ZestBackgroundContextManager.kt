package com.zps.zest.completion.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages background collection and caching of completion context
 * to improve inline completion performance
 */
@Service(Service.Level.PROJECT)
class ZestBackgroundContextManager(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestBackgroundContextManager::class.java)
    
    // Background processing scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheMutex = Mutex()
    
    // Context collectors
    private val gitContext = ZestCompleteGitContext(project)
    
    // Cached contexts with timestamps
    private var cachedGitContext: TimestampedContext<ZestCompleteGitContext.CompleteGitInfo>? = null
    private var cachedFileContexts: ConcurrentHashMap<String, TimestampedContext<FileContext>> = ConcurrentHashMap()
    
    // Background processing state
    private val isStarted = AtomicBoolean(false)
    private var gitRefreshJob: Job? = null
    private var fileProcessingJob: Job? = null
    
    // Configuration
    private val gitContextTtlMs = 30_000L // 30 seconds
    private val fileContextTtlMs = 300_000L // 5 minutes
    private val maxCachedFiles = 50 // LRU eviction threshold
    
    init {
        logger.info("Initializing ZestBackgroundContextManager for project: ${project.name}")
    }
    
    /**
     * Start background context collection
     */
    fun startBackgroundCollection() {
        if (isStarted.compareAndSet(false, true)) {
            logger.info("Starting background context collection")
            startGitContextRefresh()
            startFileContextMonitoring()
        }
    }
    
    /**
     * Stop background context collection
     */
    fun stopBackgroundCollection() {
        if (isStarted.compareAndSet(true, false)) {
            logger.info("Stopping background context collection")
            gitRefreshJob?.cancel()
            fileProcessingJob?.cancel()
        }
    }
    
    /**
     * Get cached git context or collect if expired/missing
     */
    suspend fun getCachedGitContext(): ZestCompleteGitContext.CompleteGitInfo? {
        return cacheMutex.withLock {
            val cached = cachedGitContext
            
            when {
                cached == null -> {
                    System.out.println("No cached git context, collecting immediately")
                    collectAndCacheGitContext()
                }
                cached.isExpired() -> {
                    System.out.println("Git context expired, refreshing")
                    collectAndCacheGitContext()
                }
                else -> {
                    System.out.println("Using cached git context")
                    cached.data
                }
            }
        }
    }
    
    /**
     * Get cached file context or collect if expired/missing
     */
    suspend fun getCachedFileContext(file: VirtualFile?): FileContext? {
        if (file == null) return null
        
        val filePath = file.path
        return cacheMutex.withLock {
            val cached = cachedFileContexts[filePath]
            
            when {
                cached == null -> {
                    System.out.println("No cached file context for $filePath, collecting")
                    collectAndCacheFileContext(file)
                }
                cached.isExpired() -> {
                    System.out.println("File context expired for $filePath, refreshing")
                    collectAndCacheFileContext(file)
                }
                else -> {
                    System.out.println("Using cached file context for $filePath")
                    cached.data
                }
            }
        }
    }
    
    /**
     * Force immediate git context refresh
     */
    suspend fun forceGitRefresh() {
        System.out.println("Forcing git context refresh")
        cacheMutex.withLock {
            collectAndCacheGitContext()
        }
    }
    
    /**
     * Schedule git context refresh (non-blocking)
     */
    fun scheduleGitRefresh() {
        if (isStarted.get()) {
            scope.launch {
                delay(1000) // Small delay to batch multiple changes
                forceGitRefresh()
            }
        }
    }
    
    /**
     * Invalidate file context for specific file
     */
    fun invalidateFileContext(filePath: String) {
        System.out.println("Invalidating file context for $filePath")
        cachedFileContexts.remove(filePath)
    }
    
    /**
     * Invalidate all cached contexts
     */
    fun invalidateAllCaches() {
        System.out.println("Invalidating all cached contexts")
        scope.launch {
            cacheMutex.withLock {
                cachedGitContext = null
                cachedFileContexts.clear()
            }
        }
    }
    
    // Private implementation methods
    
    private fun startGitContextRefresh() {
        gitRefreshJob = scope.launch {
            // Initial collection
            delay(2000) // Wait for project to fully load
            forceGitRefresh()
            
            // Periodic refresh
            while (isActive) {
                delay(gitContextTtlMs)
                try {
                    val cached = cacheMutex.withLock { cachedGitContext }
                    if (cached?.isExpired() != false) {
                        forceGitRefresh()
                    }
                } catch (e: Exception) {
                    logger.warn("Error during periodic git context refresh", e)
                }
            }
        }
    }
    
    private fun startFileContextMonitoring() {
        fileProcessingJob = scope.launch {
            while (isActive) {
                try {
                    // Clean up expired file contexts
                    cleanupExpiredFileContexts()
                    
                    // Enforce cache size limits
                    enforceFileCacheLimit()
                    
                    delay(60_000) // Clean up every minute
                } catch (e: Exception) {
                    logger.warn("Error during file context monitoring", e)
                }
            }
        }
    }
    
    private suspend fun collectAndCacheGitContext(): ZestCompleteGitContext.CompleteGitInfo? {
        return try {
            val startTime = System.currentTimeMillis()
            val gitInfo = gitContext.getAllModifiedFiles()
            val collectTime = System.currentTimeMillis() - startTime
            
            cachedGitContext = TimestampedContext(gitInfo, gitContextTtlMs)
            System.out.println("Collected and cached git context in ${collectTime}ms")
            
            gitInfo
        } catch (e: Exception) {
            logger.warn("Failed to collect git context", e)
            null
        }
    }
    
    private suspend fun collectAndCacheFileContext(file: VirtualFile): FileContext? {
        return try {
            val startTime = System.currentTimeMillis()
            val fileContext = FileContext(
                fileName = file.name,
                language = file.fileType.name,
                isCodeFile = isCodeFile(file),
                lastModified = file.timeStamp
            )
            val collectTime = System.currentTimeMillis() - startTime
            
            cachedFileContexts[file.path] = TimestampedContext(fileContext, fileContextTtlMs)
            System.out.println("Collected and cached file context for ${file.name} in ${collectTime}ms")
            
            fileContext
        } catch (e: Exception) {
            logger.warn("Failed to collect file context for ${file.path}", e)
            null
        }
    }
    
    private fun cleanupExpiredFileContexts() {
        val expiredKeys = cachedFileContexts.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { cachedFileContexts.remove(it) }
            System.out.println("Cleaned up ${expiredKeys.size} expired file contexts")
        }
    }
    
    private fun enforceFileCacheLimit() {
        if (cachedFileContexts.size > maxCachedFiles) {
            // Remove oldest entries (simple LRU approximation)
            val sortedEntries = cachedFileContexts.entries
                .sortedBy { it.value.timestamp }
                .take(cachedFileContexts.size - maxCachedFiles)
            
            sortedEntries.forEach { cachedFileContexts.remove(it.key) }
            System.out.println("Evicted ${sortedEntries.size} old file contexts to enforce cache limit")
        }
    }
    
    private fun isCodeFile(file: VirtualFile): Boolean {
        val codeExtensions = setOf("java", "kt", "js", "ts", "py", "html", "css", "xml", "json", "yaml", "sql")
        return codeExtensions.contains(file.extension?.lowercase())
    }
    
    override fun dispose() {
        logger.info("Disposing ZestBackgroundContextManager")
        stopBackgroundCollection()
        scope.cancel()
        cachedFileContexts.clear()
        cachedGitContext = null
    }
    
    // Data classes for cached contexts
    
    /**
     * Wrapper for cached context data with TTL
     */
    data class TimestampedContext<T>(
        val data: T,
        val ttlMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
        
        fun ageMs(): Long = System.currentTimeMillis() - timestamp
    }
    
    /**
     * Cached file-level context information
     */
    data class FileContext(
        val fileName: String,
        val language: String,
        val isCodeFile: Boolean,
        val lastModified: Long
    )
    
    companion object {
        private const val MAX_BACKGROUND_TASKS = 2
    }
}
