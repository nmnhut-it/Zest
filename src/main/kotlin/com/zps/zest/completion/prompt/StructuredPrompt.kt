package com.zps.zest.completion.prompt

/**
 * Data class to hold structured prompts with separate system and user components.
 * This enables better caching on the backend LLM side.
 * 
 * @property systemPrompt Static instructions that can be cached by the backend
 * @property userPrompt Dynamic content specific to each completion request
 * @property metadata Additional information about the prompt context
 */
data class StructuredPrompt(
    val systemPrompt: String,
    val userPrompt: String,
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Get the combined prompt for backwards compatibility
     */
    fun getCombinedPrompt(): String {
        return if (systemPrompt.isNotEmpty()) {
            "$systemPrompt\n\n$userPrompt"
        } else {
            userPrompt
        }
    }
}
