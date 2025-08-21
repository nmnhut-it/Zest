package com.zps.zest.completion.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.zps.zest.completion.rag.InlineCompletionRAG
import com.zps.zest.completion.ast.ASTPatternMatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Simple cache for expensive context collection operations
 */
@Service(Service.Level.PROJECT)
class LeanContextCache(private val project: Project) {
    
    init {
        // Schedule periodic cleanup every 60 seconds
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleAtFixedRate({ cleanup() }, 60, 60, TimeUnit.SECONDS)
    }
    
    data class CacheEntry<T>(
        val value: T,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Cached PSI analysis for a file
     */
    data class PsiAnalysisCache(
        val methods: List<MethodInfo>,
        val classes: List<ClassInfo>,
        val contextTypeRanges: List<ContextTypeRange>,
        val fileLength: Int
    ) {
        /**
         * Get context type for offset using pre-computed ranges
         */
        fun getContextTypeForOffset(offset: Int): ZestLeanContextCollectorPSI.CursorContextType {
            for (range in contextTypeRanges) {
                if (offset in range.startOffset..range.endOffset) {
                    return range.contextType
                }
            }
            return ZestLeanContextCollectorPSI.CursorContextType.UNKNOWN
        }
        
        /**
         * Get methods that should be preserved for truncation
         */
        fun getPreservedMethodsForOffset(offset: Int): Set<String> {
            val preservedMethods = mutableSetOf<String>()
            
            // Always preserve the method containing the cursor
            methods.forEach { method ->
                if (offset in method.startOffset..method.endOffset) {
                    preservedMethods.add(method.name)
                }
            }
            
            return preservedMethods
        }
    }
    
    data class MethodInfo(
        val name: String,
        val startOffset: Int,
        val endOffset: Int,
        val signature: String,
        val bodyStartOffset: Int = -1
    )
    
    data class ClassInfo(
        val name: String,
        val qualifiedName: String?,
        val startOffset: Int,
        val endOffset: Int
    )
    
    data class ContextTypeRange(
        val startOffset: Int,
        val endOffset: Int,
        val contextType: ZestLeanContextCollectorPSI.CursorContextType
    )
    
    // Cache for different operation types
    private val ragCache = ConcurrentHashMap<String, CacheEntry<List<InlineCompletionRAG.RetrievedChunk>>>()
    private val astPatternCache = ConcurrentHashMap<String, CacheEntry<ASTPatternMatcher.ASTPattern?>>()
    private val contextCache = ConcurrentHashMap<String, CacheEntry<ZestLeanContextCollectorPSI.LeanContext>>()
    private val psiAnalysisCache = ConcurrentHashMap<String, CacheEntry<PsiAnalysisCache>>()
    
    // Cache TTL (30 seconds for fast operations, 5 minutes for expensive ones)
    private val RAG_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30)
    private val AST_PATTERN_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30)
    private val CONTEXT_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(2)
    private val PSI_ANALYSIS_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)
    
    /**
     * Cache RAG retrieval results by query
     */
    fun getCachedRagChunks(query: String): List<InlineCompletionRAG.RetrievedChunk>? {
        val entry = ragCache[query]
        return if (entry != null && !isExpired(entry.timestamp, RAG_CACHE_TTL_MS)) {
            println("Cache HIT for RAG query: '${query.take(50)}...'")
            entry.value
        } else {
            ragCache.remove(query)
            null
        }
    }
    
    fun cacheRagChunks(query: String, chunks: List<InlineCompletionRAG.RetrievedChunk>) {
        ragCache[query] = CacheEntry(chunks)
        println("Cached RAG results for query: '${query.take(50)}...' (${chunks.size} chunks)")
    }
    
    /**
     * Cache AST pattern extraction by file path + modification time + offset range
     */
    fun getCachedAstPattern(filePath: String, modificationTime: Long, offsetRange: IntRange): ASTPatternMatcher.ASTPattern? {
        val key = "${filePath}_${modificationTime}_${offsetRange.first}_${offsetRange.last}"
        val entry = astPatternCache[key]
        return if (entry != null && !isExpired(entry.timestamp, AST_PATTERN_CACHE_TTL_MS)) {
            println("Cache HIT for AST pattern at ${filePath}:${offsetRange.first}")
            entry.value
        } else {
            astPatternCache.remove(key)
            null
        }
    }
    
    fun cacheAstPattern(filePath: String, modificationTime: Long, offsetRange: IntRange, pattern: ASTPatternMatcher.ASTPattern?) {
        val key = "${filePath}_${modificationTime}_${offsetRange.first}_${offsetRange.last}"
        astPatternCache[key] = CacheEntry(pattern)
        println("Cached AST pattern for ${filePath}:${offsetRange.first}")
    }
    
    /**
     * Cache immediate context by file path + modification time + offset bucket
     */
    fun getCachedContext(filePath: String, modificationTime: Long, offset: Int): ZestLeanContextCollectorPSI.LeanContext? {
        // Use offset range for cache key (±50 chars tolerance)
        val offsetBucket = (offset / 50) * 50
        val key = "${filePath}_${modificationTime}_${offsetBucket}"
        val entry = contextCache[key]
        return if (entry != null && !isExpired(entry.timestamp, CONTEXT_CACHE_TTL_MS)) {
            println("Cache HIT for context at ${filePath}:$offset (bucket $offsetBucket)")
            entry.value.copy(cursorOffset = offset) // Update exact offset
        } else {
            contextCache.remove(key)
            null
        }
    }
    
    fun cacheContext(filePath: String, modificationTime: Long, offset: Int, context: ZestLeanContextCollectorPSI.LeanContext) {
        val offsetBucket = (offset / 50) * 50
        val key = "${filePath}_${modificationTime}_${offsetBucket}"
        contextCache[key] = CacheEntry(context)
        println("Cached context for ${filePath}:$offset (bucket $offsetBucket)")
    }
    
    /**
     * Cache PSI analysis by file path + modification time
     */
    fun getCachedPsiAnalysis(filePath: String, modificationTime: Long): PsiAnalysisCache? {
        val key = "${filePath}_${modificationTime}_psi"
        val entry = psiAnalysisCache[key]
        return if (entry != null && !isExpired(entry.timestamp, PSI_ANALYSIS_CACHE_TTL_MS)) {
            println("Cache HIT for PSI analysis: $filePath")
            entry.value
        } else {
            psiAnalysisCache.remove(key)
            null
        }
    }
    
    fun cachePsiAnalysis(filePath: String, modificationTime: Long, analysis: PsiAnalysisCache) {
        val key = "${filePath}_${modificationTime}_psi"
        psiAnalysisCache[key] = CacheEntry(analysis)
        println("Cached PSI analysis for $filePath (${analysis.methods.size} methods, ${analysis.contextTypeRanges.size} ranges)")
    }
    
    private fun isExpired(timestamp: Long, ttlMs: Long): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }
    
    /**
     * Clear expired entries periodically
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        
        ragCache.entries.removeIf { now - it.value.timestamp > RAG_CACHE_TTL_MS }
        astPatternCache.entries.removeIf { now - it.value.timestamp > AST_PATTERN_CACHE_TTL_MS }
        contextCache.entries.removeIf { now - it.value.timestamp > CONTEXT_CACHE_TTL_MS }
        psiAnalysisCache.entries.removeIf { now - it.value.timestamp > PSI_ANALYSIS_CACHE_TTL_MS }
        
        println("Cache cleanup: ${ragCache.size} RAG, ${astPatternCache.size} AST, ${contextCache.size} context, ${psiAnalysisCache.size} PSI entries")
    }
    
    /**
     * Clear all caches when file is modified
     */
    fun invalidateFileCache(filePath: String) {
        val keysToRemove = mutableListOf<String>()
        
        astPatternCache.keys.forEach { key ->
            if (key.startsWith("${filePath}_")) {
                keysToRemove.add(key)
            }
        }
        contextCache.keys.forEach { key ->
            if (key.startsWith("${filePath}_")) {
                keysToRemove.add(key)
            }
        }
        psiAnalysisCache.keys.forEach { key ->
            if (key.startsWith("${filePath}_")) {
                keysToRemove.add(key)
            }
        }
        
        keysToRemove.forEach { key ->
            astPatternCache.remove(key)
            contextCache.remove(key)
            psiAnalysisCache.remove(key)
        }
        
        if (keysToRemove.isNotEmpty()) {
            println("Invalidated ${keysToRemove.size} cache entries for $filePath")
        }
    }
    
    /**
     * Clear all caches
     */
    fun clearAll() {
        ragCache.clear()
        astPatternCache.clear()
        contextCache.clear()
        psiAnalysisCache.clear()
        println("All caches cleared")
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): Map<String, Int> {
        return mapOf(
            "ragEntries" to ragCache.size,
            "astPatternEntries" to astPatternCache.size,
            "contextEntries" to contextCache.size,
            "psiAnalysisEntries" to psiAnalysisCache.size
        )
    }
}