package com.zps.zest.completion.parser

/**
 * Simple response parser that cleans up LLM responses for code completion
 */
class ZestSimpleResponseParser {
    
    fun parseResponse(response: String): String {
        if (response.isBlank()) return ""
        
        return response
            .trim()
            .let { cleanMarkdownFormatting(it) }
            .let { cleanXmlTags(it) }
            .let { removeExplanations(it) }
            .let { limitLength(it) }
            .trim()
    }
    
    private fun cleanMarkdownFormatting(text: String): String {
        var cleaned = text
        
        // Remove code blocks
        cleaned = cleaned.replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        
        // Remove language tags on their own lines
        val lines = cleaned.lines().toMutableList()
        if (lines.isNotEmpty() && lines.first().trim().matches(Regex("^(java|kotlin|javascript|typescript|python|html|css|xml|json|yaml|sql)$", RegexOption.IGNORE_CASE))) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && lines.last().trim().matches(Regex("^```$"))) {
            lines.removeAt(lines.size - 1)
        }
        
        return lines.joinToString("\n")
    }
    
    private fun cleanXmlTags(text: String): String {
        var cleaned = text
        
        val xmlTags = listOf(
            "<code>", "</code>",
            "<completion>", "</completion>",
            "<answer>", "</answer>",
            "<fim_middle>", "</fim_middle>",
            "<r>", "</r>"
        )
        
        xmlTags.forEach { tag ->
            cleaned = cleaned.replace(tag, "", ignoreCase = true)
        }
        
        return cleaned
    }
    
    private fun removeExplanations(text: String): String {
        val lines = text.lines()
        val codeLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip explanation lines
            if (trimmed.startsWith("Here") || 
                trimmed.startsWith("The completion") ||
                trimmed.startsWith("This will") ||
                trimmed.startsWith("This code") ||
                trimmed.contains("explanation", ignoreCase = true)) {
                continue
            }
            
            codeLines.add(line)
        }
        
        return codeLines.joinToString("\n")
    }
    
    private fun limitLength(text: String): String {
        val maxTokens = 50
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        
        return if (tokens.size <= maxTokens) {
            text
        } else {
            tokens.take(maxTokens).joinToString(" ")
        }
    }
}
