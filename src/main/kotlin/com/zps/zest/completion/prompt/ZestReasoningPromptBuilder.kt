package com.zps.zest.completion.prompt

import com.zps.zest.completion.data.CompletionContext

/**
 * Simple prompt builder for inline code completion using prefix/suffix format
 */
class ZestSimplePromptBuilder {
    
    /**
     * Build a simple FIM-style prompt
     */
    fun buildSimplePrompt(context: CompletionContext): String {
        return """
<fim_prefix>
${context.prefixCode}
<fim_suffix>
${context.suffixCode}
<fim_middle>
        """.trimIndent()
    }
    
    /**
     * Build a prompt with minimal instructions for non-FIM models
     */
    fun buildInstructionPrompt(context: CompletionContext): String {
        return """
Complete the code at the cursor position.

```${getLanguageId(context.language)}
${context.prefixCode}[CURSOR]${context.suffixCode}
```

Generate only the code that should be inserted at [CURSOR]. No explanations, no markdown blocks in response.
        """.trimIndent()
    }
    
    private fun getLanguageId(language: String): String {
        return when (language.lowercase()) {
            "java" -> "java"
            "kotlin" -> "kotlin"
            "javascript" -> "javascript"
            "typescript" -> "typescript"
            "python" -> "python"
            "html" -> "html"
            "css" -> "css"
            "xml" -> "xml"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "sql" -> "sql"
            "groovy" -> "groovy"
            "scala" -> "scala"
            "go" -> "go"
            "rust" -> "rust"
            "c++" -> "cpp"
            "c" -> "c"
            "shell", "bash" -> "bash"
            else -> language.lowercase()
        }
    }
}
