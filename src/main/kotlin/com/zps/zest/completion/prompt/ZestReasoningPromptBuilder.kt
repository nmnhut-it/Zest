package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.completion.context.ZestCompleteGitContext

/**
 * Enhanced prompt builder that includes reasoning requests and complete git context
 */
class ZestReasoningPromptBuilder {
    
    fun buildReasoningPrompt(context: ZestLeanContextCollector.LeanContext): String {
        val basic = context.basicContext
        val gitInfo = context.gitInfo
        
        val modifiedFilesSection = buildModifiedFilesSection(gitInfo)
        val similarExampleSection = buildSimilarExampleSection(context.similarExample)
        val keywordsSection = buildKeywordsSection(context.relevantKeywords)
        
        return """
Context: ${basic.language} development in ${basic.fileName}

${modifiedFilesSection}${similarExampleSection}${keywordsSection}# Primary task: Complete code following established patterns
# Pattern reference: ${context.similarExample?.content?.take(50) ?: "No pattern detected"}
# Code style: Match existing naming and structure conventions
# Context clues: ${context.relevantKeywords.take(3).joinToString(", ")}

BEFORE:
```${getMarkdownLanguage(basic.language)}
${basic.prefixCode}
```

COMPLETE:
# Task: ${inferTaskFromContext(basic, context.relevantKeywords)}
# Expected: Code completion following detected pattern
# Style: Match indentation and naming conventions
# Limit: Maximum 64 tokens
[COMPLETE HERE]

AFTER:
```${getMarkdownLanguage(basic.language)}
${basic.suffixCode}
```

INSTRUCTIONS:
1. Provide brief reasoning (MAXIMUM 8 words) about completion intent
2. Generate raw code completion (MAXIMUM 64 tokens):
   - NO markdown code blocks (no ``` or language tags)
   - NO XML tags or HTML formatting
   - NO explanatory text or comments
   - ONLY exact code for cursor position
   - Match existing code style and patterns

Format: REASONING: [8 words max] → COMPLETION: [raw code, 64 tokens max]
        """.trimIndent()
    }
    
    /**
     * Infer the likely task from context and keywords
     */
    private fun inferTaskFromContext(
        basic: ZestLeanContextCollector.BasicContext, 
        keywords: Set<String>
    ): String {
        val currentLine = basic.currentLine.trim()
        val prefix = basic.prefixCode.lines().takeLast(3).joinToString(" ").trim()
        
        return when {
            keywords.contains("assignment_pattern") -> "Complete variable assignment"
            currentLine.contains("=") -> "Complete assignment statement"
            currentLine.endsWith("(") -> "Complete method call parameters"
            currentLine.endsWith("{") -> "Complete code block"
            currentLine.endsWith(";") -> "Start new statement"
            keywords.any { it.contains("COUNT") } -> "Complete counter variable"
            keywords.any { it.contains("Leaderboard") } -> "Complete Leaderboard instantiation"
            prefix.contains("static") -> "Complete static member"
            currentLine.isBlank() -> "Add new code line"
            else -> "Complete current statement"
        }
    }
    
    private fun buildModifiedFilesSection(gitInfo: ZestCompleteGitContext.CompleteGitInfo?): String {
        return buildString {
            gitInfo?.let { git ->
                if (git.allModifiedFiles.isNotEmpty()) {
                    append("RECENT CHANGES IN PROJECT:\n")
                    git.recentCommitMessage?.let { 
                        append("Last commit: $it\n") 
                    }
                    append("Currently modified files:\n")
                    
                    // Limit to most relevant files to avoid overwhelming the prompt
                    git.allModifiedFiles.take(8).forEach { file ->
                        append("- ${file.path} (${file.status})")
                        file.summary?.let { summary ->
                            // Format the summary nicely
                            val formattedSummary = when {
                                summary.contains("+") && summary.contains("-") -> {
                                    // Enhanced summary with actual changes
                                    " - $summary"
                                }
                                summary.length > 50 -> {
                                    // Truncate very long summaries
                                    " - ${summary.take(47)}..."
                                }
                                else -> {
                                    " - $summary"
                                }
                            }
                            append(formattedSummary)
                        }
                        append("\n")
                    }
                    
                    if (git.allModifiedFiles.size > 8) {
                        append("... and ${git.allModifiedFiles.size - 8} more files\n")
                    }
                    append("\n")
                }
            }
        }
    }
    
    private fun buildSimilarExampleSection(similarExample: ZestLeanContextCollector.SimilarExample?): String {
        return buildString {
            similarExample?.let { similar ->
                append("SIMILAR PATTERN:\n")
                append("Context: ${similar.context}\n")
                
                // Limit content length but keep it meaningful
                val content = if (similar.content.length > 200) {
                    similar.content.take(180) + "..."
                } else {
                    similar.content
                }
                append("Pattern: $content\n\n")
            }
        }
    }
    
    private fun buildKeywordsSection(relevantKeywords: Set<String>): String {
        return if (relevantKeywords.isNotEmpty()) {
            "CURRENT CONTEXT: ${relevantKeywords.joinToString(", ")}\n\n"
        } else ""
    }
    
    fun buildSimplePrompt(
        language: String,
        fileName: String,
        prefixCode: String,
        suffixCode: String
    ): String {
        return """
Context: ${language} development in ${fileName}

# Primary task: Complete code at cursor position
# Code style: Match existing patterns and conventions
# Limit: Maximum 64 tokens

BEFORE:
```${getMarkdownLanguage(language)}
${prefixCode}
```

COMPLETE:
# Task: Complete current statement or expression
# Expected: Code continuation following context
# Style: Match indentation and syntax
[COMPLETE HERE]

AFTER:
```${getMarkdownLanguage(language)}
${suffixCode}
```

INSTRUCTIONS:
Generate raw code completion (MAXIMUM 64 tokens):
- NO markdown code blocks (no ``` or language tags)  
- NO XML tags or HTML formatting
- NO explanatory text or comments
- ONLY exact code for cursor position
- Keep completion SHORT and focused

Format: [raw code, 64 tokens max]
        """.trimIndent()
    }
    
    /**
     * Map language names to proper markdown language identifiers
     */
    private fun getMarkdownLanguage(language: String): String {
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
            "powershell" -> "powershell"
            else -> language.lowercase()
        }
    }
}
