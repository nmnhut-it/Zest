package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestSimpleContextCollector

/**
 * Simple prompt builder that creates basic completion prompts using only prefix/suffix context
 */
class ZestSimplePromptBuilder {
    
    fun buildCompletionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return when {
            isCodeLanguage(context.language) -> buildCodeCompletionPrompt(context)
            else -> buildGenericCompletionPrompt(context)
        }
    }
    
    private fun buildCodeCompletionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return """
Complete the ${context.language} code at the cursor position. 

File: ${context.fileName}
Language: ${context.language}

Code before cursor:
```${getLanguageTag(context.language)}
${context.prefixCode}
```

Code after cursor:
```${getLanguageTag(context.language)}
${context.suffixCode}
```

Provide ONLY the code completion that should be inserted at the cursor position.
Requirements:
- Maximum 50 tokens
- NO markdown code blocks or formatting
- NO explanations or comments
- ONLY the exact code to insert
- Match the existing code style and indentation

Completion:
        """.trimIndent()
    }
    
    private fun buildGenericCompletionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return """
Complete the text at the cursor position.

Text before cursor:
${context.prefixCode}

Text after cursor:
${context.suffixCode}

Provide ONLY the text completion (maximum 50 tokens):
        """.trimIndent()
    }
    
    private fun isCodeLanguage(language: String): Boolean {
        val codeLanguages = setOf(
            "java", "kotlin", "javascript", "typescript", "python", 
            "html", "css", "xml", "json", "yaml", "sql", "groovy", "scala"
        )
        return codeLanguages.contains(language.lowercase())
    }
    
    private fun getLanguageTag(language: String): String {
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
            else -> "text"
        }
    }
}
