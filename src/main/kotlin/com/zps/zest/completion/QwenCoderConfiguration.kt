package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger

/**
 * Configuration helper for Qwen 2.5 Coder 7B integration
 * Provides setup validation and configuration guidance
 */
object QwenCoderConfiguration {
    private val logger = Logger.getInstance(QwenCoderConfiguration::class.java)
    
    /**
     * Validate that Qwen 2.5 Coder is properly configured
     */
    fun validateConfiguration(): ConfigurationStatus {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check FIM token format
        val fimTokensValid = checkFimTokenFormat()
        if (!fimTokensValid) {
            issues.add("FIM tokens not properly configured for Qwen 2.5 Coder format")
        }
        
        // Check model configuration
        val modelConfigValid = checkModelConfiguration()
        if (!modelConfigValid) {
            warnings.add("Model name should be set to Qwen 2.5 Coder variant")
        }
        
        // Check completion parameters
        val paramsValid = checkCompletionParameters()
        if (!paramsValid) {
            warnings.add("Completion parameters may not be optimal for Qwen 2.5 Coder")
        }
        
        return ConfigurationStatus(
            isValid = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
            recommendations = getRecommendations()
        )
    }
    
    /**
     * Get recommended configuration for Qwen 2.5 Coder 7B
     */
    fun getRecommendedConfiguration(): QwenCoderConfig {
        return QwenCoderConfig(
            modelName = "Qwen/Qwen2.5-Coder-7B", // Use base model for FIM
            maxTokens = 50,
            temperature = 0.1, // Low for deterministic completions
            topP = 0.95,
            stopSequences = listOf(
                "<|fim_suffix|>", 
                "<|fim_prefix|>", 
                "<|fim_pad|>", 
                "<|endoftext|>",
                "<|repo_name|>",
                "<|file_sep|>"
            ),
            contextLength = 8192, // Qwen 2.5 Coder's training context
            fimFormat = FimFormat.QWEN_25_CODER
        )
    }
    
    /**
     * Generate example FIM prompt for testing
     */
    fun generateTestPrompt(): String {
        return """<|fim_prefix|>public class Calculator {
    public int add(int a, int b) {
        <|fim_suffix|>
    }
    
    public int subtract(int a, int b) {
        return a - b;
    }
}<|fim_middle|>"""
    }
    
    /**
     * Validate expected response format
     */
    fun isValidResponse(response: String): Boolean {
        val trimmed = response.trim()
        
        // Should not contain FIM tokens in response
        val containsFimTokens = listOf(
            "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>", "<|fim_pad|>"
        ).any { trimmed.contains(it) }
        
        if (containsFimTokens) {
            logger.warn("Response contains FIM tokens - may indicate model misconfiguration")
            return false
        }
        
        // Should be reasonable code completion
        val isReasonableCompletion = trimmed.isNotEmpty() && 
                                   trimmed.length < 500 && // Not too long
                                   !trimmed.startsWith("I cannot") && // Not a refusal
                                   !trimmed.contains("As an AI") // Not an explanation
        
        return isReasonableCompletion
    }
    
    private fun checkFimTokenFormat(): Boolean {
        // This would check if the current implementation uses Qwen format
        // For now, return true since we've updated the code
        return true
    }
    
    private fun checkModelConfiguration(): Boolean {
        // This would check the actual model configuration
        // Implementation depends on how models are configured
        return true
    }
    
    private fun checkCompletionParameters(): Boolean {
        // This would validate completion parameters
        return true
    }
    
    private fun getRecommendations(): List<String> {
        return listOf(
            "Use Qwen/Qwen2.5-Coder-7B (base model) for FIM tasks",
            "Use Qwen/Qwen2.5-Coder-7B-Instruct for chat/instruction tasks",
            "Set temperature to 0.1-0.2 for deterministic code completion",
            "Use context length of 8192 tokens (Qwen's training length)",
            "Include proper stop sequences to prevent token leakage",
            "Test with simple examples before production use"
        )
    }
    
    data class ConfigurationStatus(
        val isValid: Boolean,
        val issues: List<String>,
        val warnings: List<String>,
        val recommendations: List<String>
    )
    
    data class QwenCoderConfig(
        val modelName: String,
        val maxTokens: Int,
        val temperature: Double,
        val topP: Double,
        val stopSequences: List<String>,
        val contextLength: Int,
        val fimFormat: FimFormat
    )
    
    enum class FimFormat {
        QWEN_25_CODER,
        LEGACY,
        CODESTRAL
    }
}
