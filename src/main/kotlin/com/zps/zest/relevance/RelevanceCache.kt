package com.zps.zest.relevance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * LRU cache with TTL for relevance scores.
 * Thread-safe implementation for concurrent access.
 */
class RelevanceCache(
    private val maxSize: Int = 1000,
    private val ttlMillis: Long = 60_000 // 1 minute default
) {
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val accessOrder = ConcurrentLinkedQueue<String>()
    private val size = AtomicInteger(0)
    
    /**
     * Get cached score if available and not expired
     */
    fun get(key: String): Double? {
        val entry = cache[key] ?: return null
        
        if (entry.isExpired()) {
            remove(key)
            return null
        }
        
        // Update access order for LRU
        accessOrder.remove(key)
        accessOrder.offer(key)
        
        return entry.score
    }
    
    /**
     * Put score in cache
     */
    fun put(key: String, score: Double) {
        // Check if we need to evict
        if (size.get() >= maxSize) {
            evictLeastRecentlyUsed()
        }
        
        val isNew = !cache.containsKey(key)
        cache[key] = CacheEntry(score, System.currentTimeMillis(), ttlMillis)
        
        if (isNew) {
            accessOrder.offer(key)
            size.incrementAndGet()
        } else {
            // Update access order
            accessOrder.remove(key)
            accessOrder.offer(key)
        }
    }
    
    /**
     * Remove entry from cache
     */
    fun remove(key: String) {
        if (cache.remove(key) != null) {
            accessOrder.remove(key)
            size.decrementAndGet()
        }
    }
    
    /**
     * Clear all cache entries
     */
    fun clear() {
        cache.clear()
        accessOrder.clear()
        size.set(0)
    }
    
    /**
     * Invalidate entries matching a pattern
     */
    fun invalidatePattern(pattern: String) {
        val regex = Regex(pattern)
        val keysToRemove = cache.keys.filter { regex.matches(it) }
        keysToRemove.forEach { remove(it) }
    }
    
    /**
     * Invalidate entries for a specific file
     */
    fun invalidateFile(filePath: String) {
        // Remove all entries that contain this file path
        val keysToRemove = cache.keys.filter { it.contains(filePath) }
        keysToRemove.forEach { remove(it) }
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        val validEntries = cache.values.count { !it.isExpired() }
        val expiredEntries = cache.size - validEntries
        
        return CacheStats(
            totalEntries = cache.size,
            validEntries = validEntries,
            expiredEntries = expiredEntries,
            hitRate = calculateHitRate()
        )
    }
    
    /**
     * Clean up expired entries
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expiredKeys = cache.entries
            .filter { it.value.isExpired(now) }
            .map { it.key }
        
        expiredKeys.forEach { remove(it) }
    }
    
    private fun evictLeastRecentlyUsed() {
        // Remove oldest entries until we're below max size
        while (size.get() >= maxSize) {
            val oldest = accessOrder.poll()
            if (oldest != null) {
                cache.remove(oldest)
                size.decrementAndGet()
            } else {
                break
            }
        }
    }
    
    private fun calculateHitRate(): Double {
        // This would need additional tracking of hits/misses
        // For now, return a placeholder
        return 0.0
    }
    
    /**
     * Cache entry with timestamp
     */
    private data class CacheEntry(
        val score: Double,
        val timestamp: Long,
        val ttl: Long
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
            return (now - timestamp) > ttl
        }
    }
    
    companion object {
        // Global instance for sharing across components
        @Volatile
        private var globalInstance: RelevanceCache? = null
        
        fun getGlobalInstance(): RelevanceCache {
            if (globalInstance == null) {
                synchronized(this) {
                    if (globalInstance == null) {
                        globalInstance = RelevanceCache()
                    }
                }
            }
            return globalInstance!!
        }
    }
}

/**
 * Cache statistics
 */
data class CacheStats(
    val totalEntries: Int,
    val validEntries: Int,
    val expiredEntries: Int,
    val hitRate: Double
)

/**
 * Cache configuration
 */
data class CacheConfig(
    val maxSize: Int = 1000,
    val ttlMillis: Long = 60_000,
    val cleanupIntervalMillis: Long = 300_000 // 5 minutes
)