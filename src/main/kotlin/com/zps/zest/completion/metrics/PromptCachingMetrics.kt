package com.zps.zest.completion.metrics

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Service to track prompt caching metrics and potential savings
 */
@Service(Service.Level.PROJECT)
class PromptCachingMetrics {
    
    data class CacheMetrics(
        val systemPromptHash: String,
        val systemPromptLength: Int,
        val hitCount: AtomicLong = AtomicLong(0),
        val firstSeen: Long = System.currentTimeMillis(),
        val lastUsed: AtomicLong = AtomicLong(System.currentTimeMillis())
    ) {
        fun recordHit() {
            hitCount.incrementAndGet()
            lastUsed.set(System.currentTimeMillis())
        }
        
        fun getTokensSaved(tokensPerChar: Float = 0.25f): Int {
            // Rough estimation: 1 token ≈ 4 characters
            return ((systemPromptLength * tokensPerChar) * (hitCount.get() - 1)).toInt()
        }
    }
    
    private val systemPromptCache = ConcurrentHashMap<String, CacheMetrics>()
    private val totalRequests = AtomicLong(0)
    private val structuredRequests = AtomicLong(0)
    
    /**
     * Record a completion request with structured prompts
     */
    fun recordStructuredRequest(systemPrompt: String, userPrompt: String) {
        totalRequests.incrementAndGet()
        structuredRequests.incrementAndGet()
        
        if (systemPrompt.isNotEmpty()) {
            val hash = systemPrompt.hashCode().toString()
            systemPromptCache.compute(hash) { _, existing ->
                if (existing != null) {
                    existing.recordHit()
                    existing
                } else {
                    CacheMetrics(hash, systemPrompt.length)
                }
            }
        }
    }
    
    /**
     * Record a legacy (non-structured) request
     */
    fun recordLegacyRequest() {
        totalRequests.incrementAndGet()
    }
    
    /**
     * Get current metrics summary
     */
    fun getMetricsSummary(): MetricsSummary {
        val totalTokensSaved = systemPromptCache.values.sumOf { it.getTokensSaved() }
        val uniqueSystemPrompts = systemPromptCache.size
        val avgCacheHits = if (uniqueSystemPrompts > 0) {
            systemPromptCache.values.map { it.hitCount.get() }.average()
        } else 0.0
        
        val structuredRatio = if (totalRequests.get() > 0) {
            structuredRequests.get().toDouble() / totalRequests.get()
        } else 0.0
        
        return MetricsSummary(
            totalRequests = totalRequests.get(),
            structuredRequests = structuredRequests.get(),
            structuredRatio = structuredRatio,
            uniqueSystemPrompts = uniqueSystemPrompts,
            totalTokensSaved = totalTokensSaved,
            avgCacheHitsPerPrompt = avgCacheHits,
            topCachedPrompts = getTopCachedPrompts(5)
        )
    }
    
    /**
     * Get top cached prompts by hit count
     */
    private fun getTopCachedPrompts(limit: Int): List<PromptCacheInfo> {
        return systemPromptCache.values
            .sortedByDescending { it.hitCount.get() }
            .take(limit)
            .map { metrics ->
                PromptCacheInfo(
                    hash = metrics.systemPromptHash,
                    length = metrics.systemPromptLength,
                    hitCount = metrics.hitCount.get(),
                    tokensSaved = metrics.getTokensSaved(),
                    ageMinutes = (System.currentTimeMillis() - metrics.firstSeen) / 60000
                )
            }
    }
    
    /**
     * Clear all metrics
     */
    fun reset() {
        systemPromptCache.clear()
        totalRequests.set(0)
        structuredRequests.set(0)
    }
    
    data class MetricsSummary(
        val totalRequests: Long,
        val structuredRequests: Long,
        val structuredRatio: Double,
        val uniqueSystemPrompts: Int,
        val totalTokensSaved: Int,
        val avgCacheHitsPerPrompt: Double,
        val topCachedPrompts: List<PromptCacheInfo>
    )
    
    data class PromptCacheInfo(
        val hash: String,
        val length: Int,
        val hitCount: Long,
        val tokensSaved: Int,
        val ageMinutes: Long
    )
    
    companion object {
        @JvmStatic
        fun getInstance(project: Project): PromptCachingMetrics {
            return project.getService(PromptCachingMetrics::class.java)
        }
    }
}
