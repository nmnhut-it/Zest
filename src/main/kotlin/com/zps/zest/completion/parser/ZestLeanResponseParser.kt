package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger

/**
 * Lean response parser that extracts reasoning and uses diff library to find changes
 * Falls back to simple parsing if diff library is not available
 */
class ZestLeanResponseParser {
    private val logger = Logger.getInstance(ZestLeanResponseParser::class.java)
    
    data class ReasoningResult(
        val reasoning: String,
        val completionText: String,
        val confidence: Float,
        val hasValidReasoning: Boolean
    ) {
        companion object {
            fun empty() = ReasoningResult("", "", 0.0f, false)
        }
    }
    
    fun parseReasoningResponse(
        response: String, 
        originalFile: String, 
        cursorPosition: Int
    ): ReasoningResult {
        try {
            // Extract reasoning and code sections
            val reasoning = extractReasoning(response)
            val code = extractCode(response)
            
            if (code.isBlank()) {
                logger.debug("No code section found in response")
                return ReasoningResult.empty()
            }
            
            // Try to use diff library, fall back to simple approach
            val changes = try {
                calculateDiffChanges(originalFile, code, cursorPosition)
            } catch (e: ClassNotFoundException) {
                logger.info("Diff library not available, using simple parsing")
                calculateSimpleChanges(originalFile, code, cursorPosition)
            } catch (e: Exception) {
                logger.warn("Diff calculation failed, using simple parsing", e)
                calculateSimpleChanges(originalFile, code, cursorPosition)
            }
            
            return ReasoningResult(
                reasoning = reasoning,
                completionText = changes,
                confidence = calculateConfidenceFromReasoning(reasoning, changes),
                hasValidReasoning = reasoning.isNotBlank() && reasoning.length > 20
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse reasoning response", e)
            return ReasoningResult.empty()
        }
    }
    
    private fun extractReasoning(response: String): String {
        val reasoningPattern = Regex("<reasoning>(.*?)</reasoning>", RegexOption.DOT_MATCHES_ALL)
        return reasoningPattern.find(response)?.groupValues?.get(1)?.trim() ?: ""
    }
    
    private fun extractCode(response: String): String {
        val codePattern = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL)
        val codeMatch = codePattern.find(response)?.groupValues?.get(1)?.trim()
        
        // Clean code section
        return codeMatch
            ?.replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
            ?.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
            ?.replace("[CURSOR]", "") // Remove cursor marker
            ?.trim() ?: ""
    }
    
    private fun calculateDiffChanges(originalFile: String, aiGeneratedFile: String, cursorPosition: Int): String {
        // Try to use diff library
        try {
            Class.forName("com.github.difflib.DiffUtils")
            return calculateDiffWithLibrary(originalFile, aiGeneratedFile, cursorPosition)
        } catch (e: ClassNotFoundException) {
            throw e // Re-throw to trigger fallback
        }
    }
    
    private fun calculateDiffWithLibrary(originalFile: String, aiGeneratedFile: String, cursorPosition: Int): String {
        val originalLines = originalFile.lines()
        val newLines = aiGeneratedFile.lines()
        
        // Calculate cursor line for context
        val cursorLine = originalFile.substring(0, cursorPosition).count { it == '\n' }
        
        // Use reflection to call DiffUtils.diff since we can't guarantee the library is available
        val diffUtilsClass = Class.forName("com.github.difflib.DiffUtils")
        val diffMethod = diffUtilsClass.getMethod("diff", List::class.java, List::class.java)
        val patch = diffMethod.invoke(null, originalLines, newLines)
        
        // Get deltas using reflection
        val getDeltasMethod = patch.javaClass.getMethod("getDeltas")
        val deltas = getDeltasMethod.invoke(patch) as List<*>
        
        if (deltas.isEmpty()) {
            logger.debug("No differences found between original and generated file")
            return ""
        }
        
        // Extract relevant changes near cursor position
        val relevantChanges = mutableListOf<String>()
        
        for (delta in deltas) {
            val deltaTypeField = delta!!.javaClass.getField("type")
            val deltaType = deltaTypeField.get(delta).toString()
            
            val targetField = delta.javaClass.getField("target")
            val target = targetField.get(delta)
            
            val positionField = target.javaClass.getMethod("getPosition")
            val linesField = target.javaClass.getMethod("getLines")
            
            val insertPosition = positionField.invoke(target) as Int
            val insertLines = linesField.invoke(target) as List<String>
            
            when (deltaType) {
                "INSERT" -> {
                    if (isNearCursor(insertPosition, cursorLine, CURSOR_PROXIMITY_LINES)) {
                        relevantChanges.addAll(insertLines)
                        logger.debug("Found INSERT delta at line $insertPosition with ${insertLines.size} lines")
                    }
                }
                "CHANGE" -> {
                    if (isNearCursor(insertPosition, cursorLine, CURSOR_PROXIMITY_LINES)) {
                        relevantChanges.addAll(insertLines)
                        logger.debug("Found CHANGE delta at line $insertPosition")
                    }
                }
            }
        }
        
        // If no changes near cursor, take the first significant change
        if (relevantChanges.isEmpty() && deltas.isNotEmpty()) {
            val firstDelta = deltas.first()!!
            val targetField = firstDelta.javaClass.getField("target")
            val target = targetField.get(firstDelta)
            val linesField = target.javaClass.getMethod("getLines")
            val lines = linesField.invoke(target) as List<String>
            
            relevantChanges.addAll(lines)
            logger.debug("No changes near cursor, using first delta")
        }
        
        // Join changes and clean up
        val result = relevantChanges.joinToString("\n").trim()
        return cleanupCompletion(result)
    }
    
    /**
     * Simple fallback when diff library is not available
     */
    private fun calculateSimpleChanges(originalFile: String, aiGeneratedFile: String, cursorPosition: Int): String {
        val originalLines = originalFile.lines()
        val newLines = aiGeneratedFile.lines()
        
        // Calculate cursor line for context
        val cursorLine = originalFile.substring(0, cursorPosition).count { it == '\n' }
        
        // Simple heuristic: find lines that are different
        val maxLines = maxOf(originalLines.size, newLines.size)
        val changedLines = mutableListOf<String>()
        
        for (i in 0 until maxLines) {
            val originalLine = originalLines.getOrNull(i) ?: ""
            val newLine = newLines.getOrNull(i) ?: ""
            
            if (originalLine != newLine) {
                // This line changed
                if (isNearCursor(i, cursorLine, CURSOR_PROXIMITY_LINES)) {
                    if (newLine.isNotBlank()) {
                        changedLines.add(newLine)
                    }
                }
            }
        }
        
        // If we found changes, return them
        if (changedLines.isNotEmpty()) {
            return cleanupCompletion(changedLines.joinToString("\n"))
        }
        
        // Fallback: look for new content around cursor position
        val startLine = maxOf(0, cursorLine - 5)
        val endLine = minOf(newLines.size, cursorLine + 10)
        
        val contextLines = newLines.subList(startLine, endLine)
        val contextOriginalLines = originalLines.subList(
            startLine, 
            minOf(originalLines.size, endLine)
        )
        
        // Find first significant difference
        for (i in contextLines.indices) {
            val newLine = contextLines[i]
            val originalLine = contextOriginalLines.getOrNull(i) ?: ""
            
            if (newLine != originalLine && newLine.trim().isNotBlank()) {
                // Found a meaningful change
                return cleanupCompletion(newLine)
            }
        }
        
        return ""
    }
    
    private fun isNearCursor(changeLineNumber: Int, cursorLine: Int, proximityLines: Int): Boolean {
        return kotlin.math.abs(changeLineNumber - cursorLine) <= proximityLines
    }
    
    private fun cleanupCompletion(completion: String): String {
        var cleaned = completion
        
        // Remove any remaining cursor markers
        cleaned = cleaned.replace("[CURSOR]", "")
        
        // Remove excessive blank lines
        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")
        
        // Trim whitespace
        cleaned = cleaned.trim()
        
        // If it's too long, take only the first few lines
        val lines = cleaned.lines()
        if (lines.size > MAX_COMPLETION_LINES) {
            cleaned = lines.take(MAX_COMPLETION_LINES).joinToString("\n")
            logger.debug("Truncated completion from ${lines.size} to $MAX_COMPLETION_LINES lines")
        }
        
        return cleaned
    }
    
    private fun calculateConfidenceFromReasoning(reasoning: String, completion: String): Float {
        var confidence = 0.5f
        
        // Higher confidence if reasoning is detailed
        if (reasoning.length > 100) confidence += 0.2f
        if (reasoning.length > 300) confidence += 0.1f
        
        // Check for analysis keywords in reasoning
        val analysisKeywords = listOf(
            "analyze", "consider", "pattern", "structure", "logic", 
            "appropriate", "implement", "purpose", "context"
        )
        val keywordCount = analysisKeywords.count { 
            reasoning.contains(it, ignoreCase = true) 
        }
        confidence += keywordCount * 0.05f
        
        // Higher confidence for structured completions
        if (completion.contains('\n')) confidence += 0.1f
        if (completion.contains('{') || completion.contains('(')) confidence += 0.1f
        
        // Lower confidence for very short completions
        if (completion.length < 10) confidence -= 0.2f
        
        // Higher confidence if completion looks like real code
        if (completion.matches(Regex(".*[a-zA-Z_][a-zA-Z0-9_]*.*"))) confidence += 0.1f
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    companion object {
        private const val CURSOR_PROXIMITY_LINES = 10 // How close to cursor to consider changes
        private const val MAX_COMPLETION_LINES = 20 // Maximum lines to return as completion
    }
}
