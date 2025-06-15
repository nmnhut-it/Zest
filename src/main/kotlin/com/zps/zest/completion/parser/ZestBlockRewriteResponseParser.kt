package com.zps.zest.completion.parser

import com.zps.zest.completion.context.ZestBlockContextCollector

/**
 * Parses AI responses for block rewrite operations
 * Extracts clean, ready-to-use rewritten code from AI responses
 */
class ZestBlockRewriteResponseParser {
    
    data class BlockRewriteResult(
        val rewrittenCode: String,
        val isValid: Boolean,
        val confidence: Float,
        val issues: List<String> = emptyList(),
        val hasSignificantChanges: Boolean = false
    )
    
    /**
     * Parse the AI response and extract rewritten code
     */
    fun parseBlockRewriteResponse(
        response: String,
        originalBlock: String,
        blockType: ZestBlockContextCollector.BlockType,
        language: String
    ): BlockRewriteResult {
        if (response.isBlank()) {
            return BlockRewriteResult("", false, 0.0f, listOf("Empty response"))
        }
        
        // Clean and extract the code
        val cleanedCode = extractCodeFromResponse(response)
        
        if (cleanedCode.isBlank()) {
            return BlockRewriteResult("", false, 0.0f, listOf("No code found in response"))
        }
        
        // Validate the rewritten code
        val validationResult = validateRewrittenCode(cleanedCode, originalBlock, blockType, language)
        
        // Calculate confidence based on various factors
        val confidence = calculateConfidence(cleanedCode, originalBlock, validationResult)
        
        // Check for significant changes
        val hasSignificantChanges = detectSignificantChanges(cleanedCode, originalBlock)
        
        return BlockRewriteResult(
            rewrittenCode = cleanedCode,
            isValid = validationResult.isValid,
            confidence = confidence,
            issues = validationResult.issues,
            hasSignificantChanges = hasSignificantChanges
        )
    }
    
    /**
     * Extract clean code from the AI response
     */
    private fun extractCodeFromResponse(response: String): String {
        var cleaned = response.trim()
        
        // Remove common AI response prefixes
        val prefixPatterns = listOf(
            Regex("^.*?rewritten.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?here.*?is.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?improved.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?updated.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?output.*?:?\\s*", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in prefixPatterns) {
            cleaned = pattern.replace(cleaned, "")
        }
        
        // Remove markdown code blocks
        cleaned = cleaned
            .replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        
        // Remove XML/HTML-like tags
        val xmlTags = listOf(
            "<code>", "</code>",
            "<rewritten>", "</rewritten>",
            "<output>", "</output>",
            "<result>", "</result>"
        )
        
        xmlTags.forEach { tag ->
            cleaned = cleaned.replace(tag, "", ignoreCase = true)
        }
        
        // Remove trailing explanations or comments
        cleaned = removeTrailingExplanations(cleaned)
        
        return cleaned.trim()
    }
    
    /**
     * Remove trailing explanations that aren't part of the code
     */
    private fun removeTrailingExplanations(text: String): String {
        val lines = text.lines().toMutableList()
        
        // Remove lines that look like explanations
        val explanationPatterns = listOf(
            Regex("^(this|the|i|note|explanation|summary).*", RegexOption.IGNORE_CASE),
            Regex("^(changes made|improvements|key changes).*", RegexOption.IGNORE_CASE),
            Regex("^(the code above|this implementation).*", RegexOption.IGNORE_CASE)
        )
        
        // Work backwards from the end
        while (lines.isNotEmpty()) {
            val lastLine = lines.last().trim()
            
            if (lastLine.isEmpty()) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            if (explanationPatterns.any { it.matches(lastLine) }) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            // If the line doesn't look like code, remove it
            if (!looksLikeCode(lastLine)) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            break
        }
        
        return lines.joinToString("\n")
    }
    
    /**
     * Check if a line looks like code rather than explanation
     */
    private fun looksLikeCode(line: String): Boolean {
        val trimmed = line.trim()
        
        // Empty lines are neutral
        if (trimmed.isEmpty()) return true
        
        // Lines with code-like characters
        if (trimmed.contains(Regex("[{}();=\\[\\]]"))) return true
        
        // Variable declarations or assignments
        if (trimmed.matches(Regex(".*\\s*=\\s*.*"))) return true
        
        // Method calls
        if (trimmed.matches(Regex(".*\\w+\\s*\\(.*\\).*"))) return true
        
        // Keywords that indicate code
        val codeKeywords = listOf(
            "public", "private", "protected", "static", "final", "class", "interface",
            "function", "def", "var", "val", "let", "const", "if", "else", "for",
            "while", "try", "catch", "throw", "return", "import", "package"
        )
        
        if (codeKeywords.any { trimmed.lowercase().startsWith(it.lowercase()) }) return true
        
        // Lines that start with explanatory words are likely not code
        val explanatoryStarters = listOf(
            "this", "the", "note", "explanation", "summary", "key", "changes",
            "improvements", "benefits", "advantages", "here", "above", "below"
        )
        
        if (explanatoryStarters.any { trimmed.lowercase().startsWith(it.lowercase()) }) return false
        
        // Default to considering it code if uncertain
        return true
    }
    
    /**
     * Validate the rewritten code
     */
    private fun validateRewrittenCode(
        rewrittenCode: String,
        originalCode: String,
        blockType: ZestBlockContextCollector.BlockType,
        language: String
    ): ValidationResult {
        val issues = mutableListOf<String>()
        
        // Basic validation
        if (rewrittenCode.length < originalCode.length / 10) {
            issues.add("Rewritten code is significantly shorter than original")
        }
        
        if (rewrittenCode.length > originalCode.length * 5) {
            issues.add("Rewritten code is significantly longer than original")
        }
        
        // Language-specific validation
        when (language.lowercase()) {
            "java", "kotlin", "scala" -> validateJvmLanguage(rewrittenCode, blockType, issues)
            "javascript", "typescript" -> validateJavaScript(rewrittenCode, blockType, issues)
            "python" -> validatePython(rewrittenCode, blockType, issues)
        }
        
        // Check for proper structure based on block type
        validateBlockStructure(rewrittenCode, blockType, issues)
        
        return ValidationResult(issues.isEmpty(), issues)
    }
    
    /**
     * Validate JVM language code
     */
    private fun validateJvmLanguage(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                if (!code.contains("{") || !code.contains("}")) {
                    issues.add("Method should have opening and closing braces")
                }
                if (!code.matches(Regex(".*\\w+\\s*\\([^)]*\\).*", RegexOption.DOT_MATCHES_ALL))) {
                    issues.add("Method should have a proper signature with parentheses")
                }
            }
            ZestBlockContextCollector.BlockType.CLASS -> {
                if (!code.contains("class ") && !code.contains("interface ")) {
                    issues.add("Class code should contain 'class' or 'interface' keyword")
                }
            }
            else -> { /* Other validations */ }
        }
    }
    
    /**
     * Validate JavaScript code
     */
    private fun validateJavaScript(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                if (!code.contains("function") && !code.contains("=>") && !code.contains("(")) {
                    issues.add("JavaScript function should contain 'function' keyword or arrow syntax")
                }
            }
            else -> { /* Other validations */ }
        }
    }
    
    /**
     * Validate Python code
     */
    private fun validatePython(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                if (!code.contains("def ")) {
                    issues.add("Python method should contain 'def' keyword")
                }
                if (!code.contains(":")) {
                    issues.add("Python method should end with colon")
                }
            }
            else -> { /* Other validations */ }
        }
    }
    
    /**
     * Validate block structure
     */
    private fun validateBlockStructure(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD,
            ZestBlockContextCollector.BlockType.CODE_BLOCK -> {
                val openBraces = code.count { it == '{' }
                val closeBraces = code.count { it == '}' }
                if (openBraces != closeBraces) {
                    issues.add("Mismatched braces: $openBraces opening, $closeBraces closing")
                }
            }
            ZestBlockContextCollector.BlockType.LINE -> {
                if (code.lines().size > 3) {
                    issues.add("Line rewrite should not result in multiple lines")
                }
            }
            else -> { /* Other structure validations */ }
        }
    }
    
    /**
     * Calculate confidence based on various factors
     */
    private fun calculateConfidence(
        rewrittenCode: String,
        originalCode: String,
        validationResult: ValidationResult
    ): Float {
        var confidence = 0.7f
        
        // Reduce confidence for validation issues
        confidence -= validationResult.issues.size * 0.1f
        
        // Increase confidence for reasonable length changes
        val lengthRatio = rewrittenCode.length.toFloat() / originalCode.length
        if (lengthRatio in 0.5f..3.0f) {
            confidence += 0.1f
        }
        
        // Increase confidence for structured code
        if (rewrittenCode.contains("{") && rewrittenCode.contains("}")) {
            confidence += 0.05f
        }
        
        // Increase confidence for meaningful improvements
        if (detectImprovementIndicators(rewrittenCode, originalCode)) {
            confidence += 0.15f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Detect significant changes between original and rewritten code
     */
    private fun detectSignificantChanges(rewrittenCode: String, originalCode: String): Boolean {
        // Simple change detection
        val lengthChange = kotlin.math.abs(rewrittenCode.length - originalCode.length)
        val lengthChangeRatio = lengthChange.toFloat() / originalCode.length
        
        // Consider it significant if length changed by more than 20%
        if (lengthChangeRatio > 0.2f) return true
        
        // Check for structural changes
        val originalLines = originalCode.lines().filter { it.trim().isNotEmpty() }
        val rewrittenLines = rewrittenCode.lines().filter { it.trim().isNotEmpty() }
        
        if (kotlin.math.abs(originalLines.size - rewrittenLines.size) > 2) return true
        
        // Check for addition of common improvement patterns
        val improvements = listOf(
            "try", "catch", "finally", "throw",
            "null", "error", "exception", "validate",
            "log", "debug", "info", "warn"
        )
        
        val originalImprovements = improvements.count { originalCode.lowercase().contains(it) }
        val rewrittenImprovements = improvements.count { rewrittenCode.lowercase().contains(it) }
        
        return rewrittenImprovements > originalImprovements + 1
    }
    
    /**
     * Detect indicators that the code was meaningfully improved
     */
    private fun detectImprovementIndicators(rewrittenCode: String, originalCode: String): Boolean {
        val rewrittenLower = rewrittenCode.lowercase()
        val originalLower = originalCode.lowercase()
        
        // Look for addition of error handling
        val errorHandlingKeywords = listOf("try", "catch", "throw", "error", "exception")
        val addedErrorHandling = errorHandlingKeywords.count { rewrittenLower.contains(it) } >
                                 errorHandlingKeywords.count { originalLower.contains(it) }
        
        // Look for addition of null checks
        val nullSafetyKeywords = listOf("null", "undefined", "?.", "!!")
        val addedNullSafety = nullSafetyKeywords.count { rewrittenLower.contains(it) } >
                             nullSafetyKeywords.count { originalLower.contains(it) }
        
        // Look for addition of documentation
        val hasMoreComments = rewrittenCode.count { it == '/' } > originalCode.count { it == '/' }
        
        return addedErrorHandling || addedNullSafety || hasMoreComments
    }
    
    private data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )
}
