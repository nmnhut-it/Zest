package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestLeanContextCollector

/**
 * Builds prompts for lean completion strategy with full file context
 * Updated to support structured prompts for better caching
 */
class ZestLeanPromptBuilder {

    companion object {
        // Cacheable system prompt with general instructions
        private const val LEAN_SYSTEM_PROMPT =
            """You are an expert code completion AI. Your task is to complete code at the cursor position marked as <CURSOR>.

Instructions:
1. Analyze the full file context to understand the code structure, patterns, and style
2. Complete ONLY what should come after <CURSOR> on the current line
3. If the statement naturally continues to multiple lines (e.g., method body, block), include the full logical completion
4. Follow existing code patterns, naming conventions, and formatting
5. Do NOT include any code that was already before the cursor
6. Do NOT include the <CURSOR> tag in your output
7. Output your completion inside <completion> tags

Response Format:
<completion>
[Your code completion here]
</completion>

Important:
- Complete only the current line (stop at the line break)
- If the statement naturally continues to the next line (e.g., multi-line method), include the continuation
- Match the indentation and style of the surrounding code"""
    }

    /**
     * Build structured prompt with separate system and user components
     */
    fun buildStructuredReasoningPrompt(context: ZestLeanContextCollector.LeanContext): StructuredPrompt {
        val userPrompt = buildUserPrompt(context)

        return StructuredPrompt(
            systemPrompt = LEAN_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            metadata = mapOf(
                "fileName" to context.fileName,
                "language" to context.language,
                "hasPreservedMethods" to context.preservedMethods.isNotEmpty(),
                "hasPreservedFields" to context.preservedFields.isNotEmpty(),
                "contextType" to context.contextType.name,
                "offset" to context.cursorOffset
            )
        )
    }

    /**
     * Build the user prompt with file context
     */
    private fun buildUserPrompt(context: ZestLeanContextCollector.LeanContext): String {
        val contextInfo = buildContextInfo(context)

        return """File: ${context.fileName}
Language: ${context.language}
$contextInfo

Full file with cursor position:
```${context.language.lowercase()}
${context.markedContent}
```

Complete the code at the <CURSOR> position."""
    }

    /**
     * Build additional context information if available
     */
    private fun buildContextInfo(context: ZestLeanContextCollector.LeanContext): String {
        val info = mutableListOf<String>()

        if (context.contextType != ZestLeanContextCollector.CursorContextType.UNKNOWN) {
            info.add("Context type: ${context.contextType.name.lowercase().replace('_', ' ')}")
        }

        if (context.preservedMethods.isNotEmpty()) {
            info.add("Related methods: ${context.preservedMethods.size} potentially related methods in context")
        }

        if (context.preservedFields.isNotEmpty()) {
            info.add("Related fields: ${context.preservedFields.size} fields in context")
        }

        if (context.isTruncated) {
            info.add("Note: File content has been truncated to fit context window")
        }

        return if (info.isNotEmpty()) {
            "Additional context:\n" + info.joinToString("\n")
        } else {
            ""
        }
    }

    /**
     * Build a simplified prompt focused on completing the current line
     * (Keep for backward compatibility)
     */
    fun buildReasoningPrompt(context: ZestLeanContextCollector.LeanContext): String {
        return """
You are an expert code completion AI. Complete the rest of the current line at the cursor position.

**File Context:**
- File: ${context.fileName}
- Language: ${context.language}

**Full File with Cursor Marker:**
```${context.language.lowercase()}
${context.markedContent}
```

**Instructions:**
Complete ONLY what should come after <CURSOR> on the current line. Follow the existing code style and patterns.

**Response Format:**
<completion>
[Code to insert at cursor - complete the rest of the current line]
</completion>

**Examples:**

Example 1 - Cursor in new line (complete new statement):
Given: `    <CURSOR>`
<completion>
if (user != null) {
        return user.isActive();
    }</completion>

Example 2 - Cursor in middle of line (complete method call):
Given: `String name = user.get<CURSOR>.toUpperCase();`
<completion>
Name()</completion>

Example 3 - Cursor at end of line (complete statement):
Given: `return orderRepository.findById(orderId).<CURSOR>`
<completion>
orElseThrow(() -> new OrderNotFoundException(orderId));</completion>

**Important:**
- Complete only the current line (stop at the line break)
- If the statement naturally continues to the next line (e.g., multi-line method), include the continuation
- Do NOT include any code that was already before the cursor
- Do NOT include cursor tag `<CURSOR>` 
- Do NOT add explanations or reasoning
        """.trimIndent()
    }
}