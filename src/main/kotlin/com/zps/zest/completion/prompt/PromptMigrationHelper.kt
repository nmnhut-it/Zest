package com.zps.zest.completion.prompt

import com.intellij.openapi.diagnostic.Logger

/**
 * Helper class to manage the migration from monolithic prompts to structured prompts.
 * This allows A/B testing and gradual rollout.
 */
object PromptMigrationHelper {
    private val logger = Logger.getInstance(PromptMigrationHelper::class.java)
    
    // Feature flags for gradual rollout
    var enableStructuredPromptsForSimple = true
    var enableStructuredPromptsForLean = true
    var logPromptComparison = true
    
    /**
     * Log prompt comparison for debugging and validation
     */
    fun logPromptStructure(
        strategy: String,
        systemPrompt: String,
        userPrompt: String,
        combinedPrompt: String? = null
    ) {
        if (!logPromptComparison) return
        
        logger.info("""
            |=== Prompt Structure Comparison [$strategy] ===
            |System Prompt (${systemPrompt.length} chars):
            |${systemPrompt.take(200)}${if (systemPrompt.length > 200) "..." else ""}
            |
            |User Prompt (${userPrompt.length} chars):
            |${userPrompt.take(200)}${if (userPrompt.length > 200) "..." else ""}
            |
            |Combined would be: ${(systemPrompt.length + userPrompt.length)} chars
            |Original prompt was: ${combinedPrompt?.length ?: "N/A"} chars
            |===========================================
        """.trimMargin())
    }
    
    /**
     * Calculate potential token savings from caching system prompts
     */
    fun calculateTokenSavings(systemPrompt: String, estimatedRequestsPerDay: Int = 1000): TokenSavings {
        // Rough estimation: 1 token ≈ 4 characters
        val systemTokens = systemPrompt.length / 4
        val savedTokensPerDay = systemTokens * estimatedRequestsPerDay
        
        return TokenSavings(
            systemPromptTokens = systemTokens,
            savedTokensPerDay = savedTokensPerDay,
            percentageReduction = if (systemPrompt.isNotEmpty()) {
                (systemTokens.toFloat() / (systemTokens + 100)) * 100 // Assuming avg user prompt is 100 tokens
            } else 0f
        )
    }
    
    data class TokenSavings(
        val systemPromptTokens: Int,
        val savedTokensPerDay: Int,
        val percentageReduction: Float
    )
}
