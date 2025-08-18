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
     * Validate the rewritten code (loosened validation)
     */
    private fun validateRewrittenCode(
        rewrittenCode: String,
        originalCode: String,
        blockType: ZestBlockContextCollector.BlockType,
        language: String
    ): ValidationResult {
        val issues = mutableListOf<String>()
        
        // Very basic validation - only catch obvious errors
        if (rewrittenCode.length < 3) {
            issues.add("Rewritten code is too short to be meaningful")
        }
        
        // Much more lenient length checks - only flag extreme cases
        if (rewrittenCode.length < originalCode.length / 200) {
            issues.add("Rewritten code is extremely short compared to original (potential extraction error)")
        }
        
        if (rewrittenCode.length > originalCode.length * 1000) {
            issues.add("Rewritten code is extremely long compared to original (potential duplication)")
        }
        
        // Loosened language-specific validation - only basic sanity checks
        when (language.lowercase()) {
            "java", "kotlin", "scala" -> validateJvmLanguageLoose(rewrittenCode, blockType, issues)
            "javascript", "typescript" -> validateJavaScriptLoose(rewrittenCode, blockType, issues)
            "python" -> validatePythonLoose(rewrittenCode, blockType, issues)
        }
        
        // Much more lenient structure validation
        validateBlockStructureLoose(rewrittenCode, blockType, issues)
        
        // Accept the code unless there are serious structural issues
        val isValid = issues.none { it.contains("extremely") || it.contains("too short") }
        
        return ValidationResult(isValid, issues)
    }
    
    /**
     * Very loose JVM language validation - only catch obvious errors
     */
    private fun validateJvmLanguageLoose(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                // Just check if it's completely malformed - allow flexible method structures
                if (!code.contains("(") && !code.contains(")")) {
                    // Even this might be too strict - could be a property or field
                    // issues.add("Method-like code should typically have parentheses")
                }
            }
            ZestBlockContextCollector.BlockType.CLASS -> {
                // Very loose - just check it's not completely empty
                if (code.trim().length < 10) {
                    issues.add("Class code seems too minimal")
                }
            }
            else -> { 
                // For other types, no specific validation - trust the AI
            }
        }
    }
    
    /**
     * Very loose JavaScript validation
     */
    private fun validateJavaScriptLoose(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                // Only check for completely malformed code
                if (code.trim().length < 5) {
                    issues.add("Function code seems too minimal")
                }
            }
            else -> { 
                // No validation for other types
            }
        }
    }
    
    /**
     * Very loose Python validation
     */
    private fun validatePythonLoose(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> {
                // Only check for completely malformed code
                if (code.trim().length < 5) {
                    issues.add("Function code seems too minimal")
                }
            }
            else -> { 
                // No validation for other types
            }
        }
    }
    
    /**
     * Very loose block structure validation
     */
    private fun validateBlockStructureLoose(
        code: String,
        blockType: ZestBlockContextCollector.BlockType,
        issues: MutableList<String>
    ) {
        when (blockType) {
            ZestBlockContextCollector.BlockType.METHOD,
            ZestBlockContextCollector.BlockType.CODE_BLOCK -> {
                val openBraces = code.count { it == '{' }
                val closeBraces = code.count { it == '}' }
                
                // Only flag major mismatches - allow some flexibility for partial rewrites
                val braceDiff = kotlin.math.abs(openBraces - closeBraces)
                if (braceDiff > 2) {
                    issues.add("Significant brace mismatch: $openBraces opening, $closeBraces closing (difference: $braceDiff)")
                }
            }
            ZestBlockContextCollector.BlockType.LINE -> {
                // Be much more lenient - allow multi-line improvements of single lines
                if (code.lines().size > 20) {
                    issues.add("Single line rewrite resulted in many lines (${code.lines().size})")
                }
            }
            else -> { 
                // No structure validation for other types
            }
        }
    }
    
    /**
     * Calculate confidence based on various factors (more lenient)
     */
    private fun calculateConfidence(
        rewrittenCode: String,
        originalCode: String,
        validationResult: ValidationResult
    ): Float {
        var confidence = 0.8f // Start with higher base confidence
        
        // Only reduce confidence for serious validation issues
        val seriousIssues = validationResult.issues.count { 
            it.contains("extremely") || it.contains("too short") || it.contains("too minimal")
        }
        confidence -= seriousIssues * 0.1f
        
        // Less penalty for minor issues
        val minorIssues = validationResult.issues.size - seriousIssues
        confidence -= minorIssues * 0.05f
        
        // Reward reasonable length changes more generously
        val lengthRatio = rewrittenCode.length.toFloat() / originalCode.length
        when {
            lengthRatio in 0.3f..5.0f -> confidence += 0.1f // Much wider acceptable range
            lengthRatio in 0.1f..10.0f -> confidence += 0.05f // Still acceptable
            else -> confidence -= 0.1f // Only penalize extreme cases
        }
        
        // Reward structured code
        if (rewrittenCode.contains("{") && rewrittenCode.contains("}")) {
            confidence += 0.05f
        }
        
        // Reward meaningful improvements more generously
        if (detectImprovementIndicators(rewrittenCode, originalCode)) {
            confidence += 0.2f // Higher reward for improvements
        }
        
        // Reward non-trivial changes
        if (rewrittenCode.trim() != originalCode.trim()) {
            confidence += 0.05f
        }
        
        return confidence.coerceIn(0.1f, 1.0f) // Keep minimum confidence higher
    }
    
    /**
     * Detect significant changes between original and rewritten code (more generous)
     */
    private fun detectSignificantChanges(rewrittenCode: String, originalCode: String): Boolean {
        // Simple change detection - be more generous about what counts as "significant"
        val lengthChange = kotlin.math.abs(rewrittenCode.length - originalCode.length)
        val lengthChangeRatio = lengthChange.toFloat() / maxOf(originalCode.length, 1)
        
        // Consider it significant if length changed by more than 10% (was 20%)
        if (lengthChangeRatio > 0.1f) return true
        
        // Check for structural changes - be more sensitive to line differences
        val originalLines = originalCode.lines().filter { it.trim().isNotEmpty() }
        val rewrittenLines = rewrittenCode.lines().filter { it.trim().isNotEmpty() }
        
        // Any line count difference is significant
        if (originalLines.size != rewrittenLines.size) return true
        
        // Check for textual differences - if more than 30% of words are different
        val originalWords = originalCode.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val rewrittenWords = rewrittenCode.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        
        val commonWords = originalWords.intersect(rewrittenWords)
        val totalUniqueWords = originalWords.union(rewrittenWords).size
        val similarityRatio = commonWords.size.toFloat() / maxOf(totalUniqueWords, 1)
        
        if (similarityRatio < 0.7f) return true // 30% or more words changed
        
        // Check for addition of any improvement patterns (more generous)
        val improvements = listOf(
            "try", "catch", "finally", "throw", "error", "exception",
            "null", "undefined", "validate", "check", "assert",
            "log", "debug", "info", "warn", "trace", "console",
            "const", "let", "final", "readonly",
            "private", "public", "protected"
        )
        
        val originalImprovements = improvements.count { originalCode.lowercase().contains(it) }
        val rewrittenImprovements = improvements.count { rewrittenCode.lowercase().contains(it) }
        
        // Any addition of improvement patterns counts as significant
        if (rewrittenImprovements > originalImprovements) return true
        
        // If we get here, changes are probably not significant
        return false
    }
    
    /**
     * Detect indicators that the code was meaningfully improved (more generous detection)
     */
    private fun detectImprovementIndicators(rewrittenCode: String, originalCode: String): Boolean {
        val rewrittenLower = rewrittenCode.lowercase()
        val originalLower = originalCode.lowercase()
        
        // Look for addition of error handling
        val errorHandlingKeywords = listOf("try", "catch", "throw", "error", "exception", "validate", "check")
        val addedErrorHandling = errorHandlingKeywords.count { rewrittenLower.contains(it) } >
                                 errorHandlingKeywords.count { originalLower.contains(it) }
        
        // Look for addition of null checks and safety
        val nullSafetyKeywords = listOf("null", "undefined", "?.", "!!", "nullable", "optional")
        val addedNullSafety = nullSafetyKeywords.count { rewrittenLower.contains(it) } >
                             nullSafetyKeywords.count { originalLower.contains(it) }
        
        // Look for addition of documentation and comments
        val hasMoreComments = rewrittenCode.count { it == '/' } > originalCode.count { it == '/' } ||
                             rewrittenCode.count { it == '*' } > originalCode.count { it == '*' }
        
        // Look for better naming (longer, more descriptive variable names)
        val rewrittenWords = rewrittenCode.split(Regex("\\W+")).filter { it.length > 2 }
        val originalWords = originalCode.split(Regex("\\W+")).filter { it.length > 2 }
        val hasBetterNaming = rewrittenWords.any { word -> 
            word.length > 5 && !originalWords.contains(word) && word.matches(Regex("[a-zA-Z]+"))
        }
        
        // Look for additional logging or debugging
        val loggingKeywords = listOf("log", "debug", "info", "warn", "trace", "print", "console")
        val addedLogging = loggingKeywords.count { rewrittenLower.contains(it) } >
                          loggingKeywords.count { originalLower.contains(it) }
        
        // Look for better structure (more lines with meaningful content)
        val originalMeaningfulLines = originalCode.lines().count { it.trim().isNotEmpty() && it.trim().length > 5 }
        val rewrittenMeaningfulLines = rewrittenCode.lines().count { it.trim().isNotEmpty() && it.trim().length > 5 }
        val hasMoreStructure = rewrittenMeaningfulLines > originalMeaningfulLines
        
        // Look for improved formatting/readability (more whitespace, better spacing)
        val hasImprovedFormatting = rewrittenCode.count { it == '\n' } > originalCode.count { it == '\n' } ||
                                   rewrittenCode.count { it == ' ' } > originalCode.count { it == ' ' } * 1.2
        
        // Consider it an improvement if any of these indicators are present
        return addedErrorHandling || addedNullSafety || hasMoreComments || hasBetterNaming || 
               addedLogging || hasMoreStructure || hasImprovedFormatting
    }
    
    private data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )
}
