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
        You are completing ${basic.language} code. Based on the context below, first provide a brief reasoning about what the user is likely trying to type, then provide the completion.
        
        ${modifiedFilesSection}${similarExampleSection}${keywordsSection}CURRENT FILE: ${basic.fileName}
        
        Code before cursor:
        ${basic.prefixCode}
        
        Code after cursor:
        ${basic.suffixCode}
        
        INSTRUCTIONS:
        1. First, provide a brief reasoning (1-2 sentences) about what you think the user is trying to write based on:
           - The recent changes in other files and git context
           - The current code context and visible patterns
           - Similar patterns in the nearby code
           - The current line structure, indentation, and partial text
           - The class/method structure and naming conventions
        
        2. Then provide ONLY the raw code completion with NO formatting:
           - NO markdown code blocks (no ``` or language tags)
           - NO XML tags or HTML formatting
           - NO explanatory text or comments
           - ONLY the exact code that should be inserted at cursor position
        
        Format your response as:
        REASONING: [your brief reasoning here]
        COMPLETION: [raw code only - no backticks, no language tags, no formatting]
        """.trimIndent()
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
        Complete the following ${language} code. Provide ONLY the raw code completion with NO formatting:
        - NO markdown code blocks (no ``` or language tags)
        - NO XML tags or HTML formatting
        - NO explanatory text or comments
        - ONLY the exact code that should be inserted at cursor position
        
        File: ${fileName}
        
        Code before cursor:
        ${prefixCode}
        
        Code after cursor:
        ${suffixCode}
        
        Complete at cursor position:
        """.trimIndent()
    }
}
