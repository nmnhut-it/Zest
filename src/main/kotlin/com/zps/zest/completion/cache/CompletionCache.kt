package com.zps.zest.completion.cache

import com.zps.zest.completion.data.ZestInlineCompletionItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for completion results
 */
class CompletionCache {
    data class CachedCompletion(
        val fullCompletion: ZestInlineCompletionItem,
        val firstLineCompletion: ZestInlineCompletionItem,
        val contextHash: String,
        val completionId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(maxAgeMs: Long = CACHE_EXPIRY_MS): Boolean {
            return System.currentTimeMillis() - timestamp > maxAgeMs
        }
    }

    private val completionCache = ConcurrentHashMap<String, CachedCompletion>()
    private val cacheMutex = Mutex()

    suspend fun get(cacheKey: String, contextHash: String): CachedCompletion? {
        return cacheMutex.withLock {
            val cached = completionCache[cacheKey]
            if (cached != null) {
                if (!cached.isExpired() && cached.contextHash == contextHash) {
                    return@withLock cached
                } else {
                    completionCache.remove(cacheKey)
                }
            }
            null
        }
    }

    suspend fun put(
        cacheKey: String,
        contextHash: String,
        fullCompletion: ZestInlineCompletionItem
    ) {
        cacheMutex.withLock {
            val firstLine = fullCompletion.insertText.lines().firstOrNull() ?: ""
            val firstLineCompletion = fullCompletion.copy(insertText = firstLine)

            val cached = CachedCompletion(
                fullCompletion = fullCompletion,
                firstLineCompletion = firstLineCompletion,
                contextHash = contextHash,
                completionId = fullCompletion.completionId
            )

            completionCache[cacheKey] = cached

            if (completionCache.size > MAX_CACHE_SIZE) {
                cleanupCache()
            }
        }
    }

    private fun cleanupCache() {
        val expiredKeys = completionCache.entries.filter { it.value.isExpired() }.map { it.key }
        expiredKeys.forEach { completionCache.remove(it) }

        if (completionCache.size > MAX_CACHE_SIZE) {
            val oldestKeys = completionCache.entries
                .sortedBy { it.value.timestamp }
                .take(completionCache.size - MAX_CACHE_SIZE)
                .map { it.key }
            oldestKeys.forEach { completionCache.remove(it) }
        }
    }

    suspend fun clear() {
        cacheMutex.withLock {
            completionCache.clear()
        }
    }

    fun getStats(): String {
        return "Cache: ${completionCache.size} entries, expired: ${completionCache.values.count { it.isExpired() }}"
    }

    companion object {
        private const val CACHE_EXPIRY_MS = 300000L // 5 minutes
        private const val MAX_CACHE_SIZE = 50
    }
}
