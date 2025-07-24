package com.zps.zest.completion.util

/**
 * Utility to detect and normalize indentation in code
 * This helps ensure AI-generated code matches the existing code style
 */
object IndentationNormalizer {
    
    /**
     * Detects the indentation style used in the original code
     */
    data class IndentationStyle(
        val useTabs: Boolean,
        val indentSize: Int,
        val baseIndent: String
    )
    
    /**
     * Detect indentation style from code sample
     */
    fun detectIndentationStyle(code: String): IndentationStyle {
        val lines = code.lines()
        
        // Find first indented line to detect style
        var useTabs = false
        var indentSize = 4 // default
        var baseIndent = ""
        
        for (line in lines) {
            if (line.isBlank()) continue
            
            val leadingWhitespace = line.takeWhile { it.isWhitespace() }
            if (leadingWhitespace.isNotEmpty()) {
                // Check if tabs are used
                useTabs = leadingWhitespace.contains('\t')
                
                if (!useTabs) {
                    // Count spaces for indent size
                    val spaces = leadingWhitespace.count { it == ' ' }
                    if (spaces > 0) {
                        // Try to detect common indent sizes (2, 4, 8)
                        indentSize = when {
                            spaces % 2 == 0 && spaces <= 8 -> 2
                            spaces % 4 == 0 -> 4
                            else -> spaces
                        }
                    }
                }
                
                // Get the base indent (minimum indent level)
                if (baseIndent.isEmpty() || leadingWhitespace.length < baseIndent.length) {
                    baseIndent = leadingWhitespace
                }
                
                // We found indentation, stop searching
                if (baseIndent.isNotEmpty()) break
            }
        }
        
        return IndentationStyle(useTabs, indentSize, baseIndent)
    }
    
    /**
     * Normalize indentation of AI-generated code to match the original style
     */
    fun normalizeIndentation(
        aiGeneratedCode: String,
        originalStyle: IndentationStyle
    ): String {
        val lines = aiGeneratedCode.lines()
        val normalizedLines = mutableListOf<String>()
        
        for (line in lines) {
            if (line.isBlank()) {
                normalizedLines.add(line)
                continue
            }
            
            // Count the indent level (assuming AI uses spaces)
            val leadingSpaces = line.takeWhile { it == ' ' }.length
            val indentLevel = if (leadingSpaces > 0) leadingSpaces / 4 else 0 // Assume AI uses 4 spaces
            
            // Create new indent based on original style
            val newIndent = if (originalStyle.useTabs) {
                originalStyle.baseIndent + "\t".repeat(indentLevel)
            } else {
                originalStyle.baseIndent + " ".repeat(indentLevel * originalStyle.indentSize)
            }
            
            // Replace leading whitespace with normalized indent
            val content = line.trimStart()
            normalizedLines.add(newIndent + content)
        }
        
        return normalizedLines.joinToString("\n")
    }
    
    /**
     * Ensure both original and modified code have consistent indentation for better diff view
     */
    fun normalizeForDiff(
        originalCode: String,
        modifiedCode: String
    ): Pair<String, String> {
        // Detect style from original
        val style = detectIndentationStyle(originalCode)
        
        // Normalize the modified code to match
        val normalizedModified = normalizeIndentation(modifiedCode, style)
        
        // Also normalize original to ensure consistency
        val normalizedOriginal = normalizeIndentation(originalCode, style.copy(baseIndent = ""))
        
        return Pair(normalizedOriginal, normalizedModified)
    }
    
    /**
     * Extract method body and normalize its indentation
     */
    fun extractAndNormalizeMethodBody(methodCode: String): String {
        val lines = methodCode.lines()
        if (lines.isEmpty()) return methodCode
        
        // Find the minimum indentation (excluding empty lines)
        val minIndent = lines
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it.isWhitespace() }.length }
            .minOrNull() ?: 0
        
        // Remove the minimum indentation from all lines
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) {
                line
            } else {
                if (line.length > minIndent) {
                    line.substring(minIndent)
                } else {
                    line.trimStart()
                }
            }
        }
    }
}