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
        val fileContext = context.currentFileContext
        val methodContext = detectMethodContext(basic.prefixCode)
        val localContext = analyzeLocalContext(basic.prefixCode)
        
        // Detect if we have a clean method context
        val hasMethodSignature = basic.prefixCode.contains(Regex("""(public|private|protected)[\s\w<>\[\],]*\s+\w+\s*\([^)]*\)\s*\{"""))
        val methodName = extractMethodName(basic.prefixCode)
        
        val currentFileSection = buildCurrentFileSection(fileContext)
        val gitChangesSection = buildGitChangesSection(gitInfo)
        val modifiedFilesSection = buildModifiedFilesSection(gitInfo)
        val similarExampleSection = buildSimilarExampleSection(context.similarExample)
        val keywordsSection = buildKeywordsSection(context.relevantKeywords)
        
        return """
Context: ${basic.language} development in ${basic.fileName}
Location: $methodContext${if (methodName != null) " ($methodName)" else ""}
${if (hasMethodSignature) "✓ Clean method context detected" else "⚠ Fragmented context - be careful"}
${if (localContext.needsResultProcessing) "Note: Previous line assigned to 'result' variable - likely needs validation/processing" else ""}

${currentFileSection}

${gitChangesSection}

${modifiedFilesSection}

${similarExampleSection}

${keywordsSection}

# Primary task: ${if (methodContext == "INSIDE_METHOD") "Continue current method logic" else "Complete code following established patterns"}
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
1. ${if (methodContext == "INSIDE_METHOD") "You are INSIDE a method - continue the method logic" else "You are at class level - add appropriate declarations"}
2. Provide brief reasoning (MAXIMUM 8 words) about completion intent
3. Generate raw code completion (MAXIMUM 64 tokens):
   - NO markdown code blocks (no ``` or language tags)
   - NO XML tags or HTML formatting
   - NO explanatory text or comments
   - ONLY exact code for cursor position
   - Match existing code style and patterns
   - ${if (methodContext == "INSIDE_METHOD") "Continue current method - do NOT start new methods" else "Add class-level elements"}
   - ${if (localContext.needsResultProcessing) "Process the 'result' variable from previous statement" else "Follow logical code flow"}

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
        val prefix = basic.prefixCode.lines().takeLast(5).joinToString(" ").trim()
        val localContext = analyzeLocalContext(basic.prefixCode)
        
        return when {
            localContext.needsResultProcessing -> "Process and validate result from previous operation"
            keywords.contains("assignment_pattern") -> "Complete variable assignment"
            currentLine.contains("=") -> "Complete assignment statement"
            currentLine.endsWith("(") -> "Complete method call parameters"
            currentLine.endsWith("{") -> "Complete code block"
            currentLine.endsWith(";") -> "Start new statement"
            keywords.any { it.contains("COUNT") } -> "Complete counter variable"
            keywords.any { it.contains("Leaderboard") } -> "Complete Leaderboard instantiation"
            prefix.contains("static") -> "Complete static member"
            prefix.contains("List<Object> result") -> "Process Redis operation result"
            currentLine.isBlank() && localContext.isAfterComment -> "Implement logic following comment"
            currentLine.isBlank() -> "Add new code line"
            else -> "Complete current statement"
        }
    }
    
    /**
     * Build current file context section with class structure
     */
    private fun buildCurrentFileSection(fileContext: ZestLeanContextCollector.CurrentFileContext?): String {
        return buildString {
            fileContext?.let { context ->
                append("CURRENT FILE STRUCTURE:\n")
                
                // Show imports (relevant ones)
                if (context.imports.isNotEmpty()) {
                    append("Imports:\n")
                    context.imports.take(10).forEach { import ->
                        append("  $import\n")
                    }
                    if (context.imports.size > 10) {
                        append("  ... and ${context.imports.size - 10} more imports\n")
                    }
                    append("\n")
                }
                
                // Show class structure
                context.classStructure?.let { structure ->
                    append("Class: ${structure.className}\n")
                    
                    if (structure.fields.isNotEmpty()) {
                        append("Fields:\n")
                        structure.fields.take(5).forEach { field ->
                            append("  $field\n")
                        }
                        if (structure.fields.size > 5) {
                            append("  ... and ${structure.fields.size - 5} more fields\n")
                        }
                        append("\n")
                    }
                    
                    if (structure.methods.isNotEmpty()) {
                        append("Available Methods:\n")
                        structure.methods.forEach { method ->
                            val marker = if (method.isCurrentMethod) " <- CURRENT METHOD" else ""
                            append("  ${method.signature}$marker\n")
                        }
                        append("\n")
                    }
                }
                
                // Show cursor location
                val cursorMethod = context.cursorPosition.insideMethod
                if (cursorMethod != null) {
                    append("Cursor Position: Inside method '$cursorMethod' at line ${context.cursorPosition.lineNumber}\n")
                } else {
                    append("Cursor Position: At class level, line ${context.cursorPosition.lineNumber}\n")
                }
                append("\n")
            }
        }
    }
    
    /**
     * Build git changes section with actual diffs
     */
    private fun buildGitChangesSection(gitInfo: ZestCompleteGitContext.CompleteGitInfo?): String {
        return buildString {
            gitInfo?.let { git ->
                if (git.actualDiffs.isNotEmpty()) {
                    append("ACTUAL CODE CHANGES IN PROJECT:\n")
                    git.recentCommitMessage?.let { 
                        append("Last commit: $it\n\n") 
                    }
                    
                    git.actualDiffs.take(3).forEach { diff -> // Limit to 3 most relevant diffs
                        append("=== ${diff.filePath} (${diff.status}) ===\n")
                        
                        // Clean up and limit diff content
                        val diffLines = diff.diffContent.lines()
                        val relevantLines = diffLines.filter { line ->
                            // Include meaningful diff lines, skip noise
                            line.startsWith("@@") || 
                            line.startsWith("+") || 
                            line.startsWith("-") || 
                            (line.startsWith(" ") && line.trim().isNotEmpty())
                        }.take(20) // Limit lines per diff
                        
                        relevantLines.forEach { line ->
                            append("$line\n")
                        }
                        
                        if (diffLines.size > relevantLines.size) {
                            append("... (diff truncated)\n")
                        }
                        append("\n")
                    }
                    
                    if (git.actualDiffs.size > 3) {
                        append("... and ${git.actualDiffs.size - 3} more changed files\n\n")
                    }
                }
            }
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
        val methodContext = detectMethodContext(prefixCode)
        val localContext = analyzeLocalContext(prefixCode)
        
        return """
Context: ${language} development in ${fileName}
Location: $methodContext
${if (localContext.needsResultProcessing) "Note: Previous statement involves 'result' processing" else ""}

# Primary task: ${if (methodContext == "INSIDE_METHOD") "Continue current method logic" else "Complete code at cursor position"}
# Code style: Match existing patterns and conventions
# Limit: Maximum 64 tokens

CURRENT CONTEXT:
```${getMarkdownLanguage(language)}
${prefixCode}[CURSOR]${suffixCode}
```

INSTRUCTIONS:
${if (methodContext == "INSIDE_METHOD") "You are INSIDE a method - continue the method logic, do NOT start new methods" else "You are at class/file level"}
Generate raw code completion (MAXIMUM 64 tokens):
- NO markdown code blocks (no ``` or language tags)  
- NO XML tags or HTML formatting
- NO explanatory text or comments
- ONLY exact code for cursor position
- ${if (localContext.needsResultProcessing) "Focus on processing/validating the 'result' variable" else "Keep completion SHORT and focused"}

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
    
    /**
     * Detect if cursor is inside a method or at class level
     */
    private fun detectMethodContext(prefixCode: String): String {
        val lines = prefixCode.lines()
        val recentLines = lines.takeLast(20) // Look at more lines for better context
        
        var braceLevel = 0
        var foundMethod = false
        
        // Scan backwards to determine context
        for (line in recentLines.reversed()) {
            val trimmed = line.trim()
            
            // Count braces (reverse logic since we're going backwards)
            braceLevel += trimmed.count { it == '}' }
            braceLevel -= trimmed.count { it == '{' }
            
            // Look for method signatures
            val methodPattern = Regex("""(public|private|protected|static)?\s*[\w<>\[\],\s]+\s+\w+\s*\([^)]*\)\s*(\{.*)?$""")
            if (methodPattern.containsMatchIn(trimmed) && braceLevel <= 0) {
                foundMethod = true
                break
            }
            
            // If we hit class declaration, we're at class level
            if (trimmed.matches(Regex("""(public\s+)?(class|interface|enum)\s+\w+.*""")) && braceLevel <= 0) {
                break
            }
        }
        
        return if (foundMethod) "INSIDE_METHOD" else "CLASS_LEVEL"
    }
    
    /**
     * Analyze immediate local context for better completion hints
     */
    private fun analyzeLocalContext(prefixCode: String): LocalContext {
        val lines = prefixCode.lines()
        val recentLines = lines.takeLast(5)
        val lastNonEmptyLine = recentLines.findLast { it.trim().isNotEmpty() }?.trim() ?: ""
        val currentIndent = lines.lastOrNull()?.takeWhile { it.isWhitespace() }?.length ?: 0
        
        return LocalContext(
            lastStatement = lastNonEmptyLine,
            needsResultProcessing = lastNonEmptyLine.contains("result =") || 
                                   lastNonEmptyLine.contains("List<Object> result") ||
                                   recentLines.any { it.contains("// validate result") },
            currentIndentLevel = currentIndent,
            isAfterAssignment = lastNonEmptyLine.contains(" = "),
            isAfterMethodCall = lastNonEmptyLine.contains("(") && lastNonEmptyLine.contains(")"),
            isAfterComment = recentLines.any { it.trim().startsWith("//") }
        )
    }
    
    /**
     * Extract method name from context for better debugging
     */
    private fun extractMethodName(prefixCode: String): String? {
        val methodPattern = Regex("""(public|private|protected|static)[\s\w<>\[\],]*\s+(\w+)\s*\([^)]*\)\s*(\{|throws[^{]*\{)""")
        val matches = methodPattern.findAll(prefixCode)
        return matches.lastOrNull()?.groupValues?.get(2)
    }
    
    /**
     * Local context information for better completion targeting
     */
    data class LocalContext(
        val lastStatement: String,
        val needsResultProcessing: Boolean,
        val currentIndentLevel: Int,
        val isAfterAssignment: Boolean = false,
        val isAfterMethodCall: Boolean = false,
        val isAfterComment: Boolean = false
    )
}
