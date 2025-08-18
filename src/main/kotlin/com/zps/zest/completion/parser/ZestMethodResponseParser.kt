package com.zps.zest.completion.parser

/**
 * Parses AI responses for method rewrite operations
 * Extracts clean, ready-to-use rewritten methods from AI responses
 */
class ZestMethodResponseParser {
    
    private val overlapDetector = ZestCompletionOverlapDetector()
    
    data class MethodRewriteResult(
        val rewrittenMethod: String,
        val isValid: Boolean,
        val confidence: Float,
        val issues: List<String> = emptyList(),
        val improvements: List<String> = emptyList(),
        val hasSignificantChanges: Boolean = false
    )
    
    /**
     * Parse the AI response and extract rewritten method
     */
    fun parseMethodRewriteResponse(
        response: String,
        originalMethod: String,
        methodName: String,
        language: String
    ): MethodRewriteResult {
        if (response.isBlank()) {
            return MethodRewriteResult("", false, 0.0f, listOf("Empty response"))
        }
        
        // Clean and extract the method code
        var cleanedMethod = extractMethodFromResponse(response, language)
        
        if (cleanedMethod.isBlank()) {
            return MethodRewriteResult("", false, 0.0f, listOf("No method found in response"))
        }
        
        // Apply overlap detection to handle prefix/suffix duplicates
        cleanedMethod = removeOverlappingPrefixSuffix(cleanedMethod, originalMethod)
        
        // Validate the rewritten method
        val validationResult = validateRewrittenMethod(cleanedMethod, originalMethod, methodName, language)
        
        // Detect improvements made
        val improvements = detectImprovements(cleanedMethod, originalMethod, language)
        
        // Calculate confidence based on various factors
        val confidence = calculateConfidence(cleanedMethod, originalMethod, validationResult, improvements)
        
        // Check for significant changes
        val hasSignificantChanges = detectSignificantChanges(cleanedMethod, originalMethod)
        
        return MethodRewriteResult(
            rewrittenMethod = cleanedMethod,
            isValid = validationResult.isValid,
            confidence = confidence,
            issues = validationResult.issues,
            improvements = improvements,
            hasSignificantChanges = hasSignificantChanges
        )
    }
    
    /**
     * Remove overlapping prefix and suffix between generated and original method
     */
    private fun removeOverlappingPrefixSuffix(generatedMethod: String, originalMethod: String): String {
        var cleaned = generatedMethod
        
        // Find common prefix
        val commonPrefixLength = findCommonPrefixLength(originalMethod, generatedMethod)
        if (commonPrefixLength > 0) {
            // Check if the common prefix is a complete syntactic unit (like method signature)
            val commonPrefix = originalMethod.take(commonPrefixLength)
            if (isSyntacticUnit(commonPrefix)) {
                cleaned = generatedMethod.substring(commonPrefixLength).trimStart()
            }
        }
        
        // Find common suffix
        val commonSuffixLength = findCommonSuffixLength(originalMethod, cleaned)
        if (commonSuffixLength > 0) {
            // Check if the common suffix is a complete syntactic unit (like closing braces)
            val commonSuffix = originalMethod.takeLast(commonSuffixLength)
            if (isSyntacticUnit(commonSuffix)) {
                cleaned = cleaned.substring(0, cleaned.length - commonSuffixLength).trimEnd()
            }
        }
        
        // If we removed too much, return original
        if (cleaned.length < generatedMethod.length / 3) {
            return generatedMethod
        }
        
        return cleaned
    }
    
    /**
     * Find the length of common prefix between two strings
     */
    private fun findCommonPrefixLength(str1: String, str2: String): Int {
        var commonLength = 0
        val minLength = minOf(str1.length, str2.length)
        
        for (i in 0 until minLength) {
            if (str1[i] == str2[i]) {
                commonLength++
            } else {
                break
            }
        }
        
        return commonLength
    }
    
    /**
     * Find the length of common suffix between two strings
     */
    private fun findCommonSuffixLength(str1: String, str2: String): Int {
        var commonLength = 0
        val minLength = minOf(str1.length, str2.length)
        
        for (i in 1..minLength) {
            if (str1[str1.length - i] == str2[str2.length - i]) {
                commonLength++
            } else {
                break
            }
        }
        
        return commonLength
    }
    
    /**
     * Check if a string fragment represents a complete syntactic unit
     */
    private fun isSyntacticUnit(fragment: String): Boolean {
        val trimmed = fragment.trim()
        
        // Complete method signatures
        if (trimmed.matches(Regex(".*\\)\\s*\\{?$"))) return true
        
        // Complete closing blocks
        if (trimmed.matches(Regex("^\\s*}+\\s*$"))) return true
        
        // Complete statements
        if (trimmed.endsWith(";")) return true
        
        // Complete annotations
        if (trimmed.matches(Regex("^@\\w+.*$"))) return true
        
        // Access modifiers or keywords on their own line
        if (trimmed.matches(Regex("^(public|private|protected|static|final|abstract|override)\\s*$"))) return true
        
        return false
    }
    
    /**
     * Extract clean method code from the AI response
     */
    private fun extractMethodFromResponse(response: String, language: String): String {
        var cleaned = response.trim()
        
        // Remove common AI response prefixes
        val prefixPatterns = listOf(
            Regex("^.*?improved method.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?here.*?is.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?rewritten.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?updated.*?:?\\s*", RegexOption.IGNORE_CASE)
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
            "<method>", "</method>",
            "<code>", "</code>",
            "<improved>", "</improved>"
        )
        
        xmlTags.forEach { tag ->
            cleaned = cleaned.replace(tag, "", ignoreCase = true)
        }
        
        // Remove trailing explanations
        cleaned = removeTrailingExplanations(cleaned, language)
        
        return cleaned.trim()
    }
    
    /**
     * Remove explanations that aren't part of the method code
     */
    private fun removeTrailingExplanations(text: String, language: String): String {
        val lines = text.lines().toMutableList()
        
        // Remove lines that look like explanations
        val explanationPatterns = listOf(
            Regex("^(this|the|note|explanation|improvement).*", RegexOption.IGNORE_CASE),
            Regex("^(changes made|key improvements|benefits).*", RegexOption.IGNORE_CASE),
            Regex("^(the method above|this implementation).*", RegexOption.IGNORE_CASE)
        )
        
        // Work backwards from the end to find where method ends
        while (lines.isNotEmpty()) {
            val lastLine = lines.last().trim()
            
            if (lastLine.isEmpty()) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            // Check if it's an explanation
            if (explanationPatterns.any { it.matches(lastLine) }) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            // Check if it looks like method code
            if (looksLikeMethodCode(lastLine, language)) {
                break
            } else {
                lines.removeAt(lines.size - 1)
            }
        }
        
        return lines.joinToString("\n")
    }
    
    /**
     * Check if a line looks like method code rather than explanation
     */
    private fun looksLikeMethodCode(line: String, language: String): Boolean {
        val trimmed = line.trim()
        
        // Empty lines are neutral
        if (trimmed.isEmpty()) return true
        
        // Closing braces definitely indicate method end
        if (trimmed == "}" || trimmed == "};") return true
        
        // Method signature patterns
        val methodSignaturePatterns = when (language.lowercase()) {
            "java", "kotlin" -> listOf(
                Regex("(public|private|protected|static|final|override|fun).*\\(.*\\)"),
                Regex("\\w+\\s*\\(.*\\)\\s*\\{?"),
                Regex("return\\s+.*;?"),
                Regex(".*\\{$"),
                Regex(".*}$")
            )
            "javascript", "typescript" -> listOf(
                Regex("function\\s+\\w+\\s*\\("),
                Regex("\\w+\\s*:\\s*function"),
                Regex("\\w+\\s*=>"),
                Regex("return\\s+.*;?"),
                Regex(".*\\{$"),
                Regex(".*}$")
            )
            "python" -> listOf(
                Regex("def\\s+\\w+\\s*\\("),
                Regex("return\\s+.*"),
                Regex("\\s{4,}.*")  // Indented lines
            )
            else -> listOf(
                Regex(".*\\{$"),
                Regex(".*}$"),
                Regex("return\\s+.*;?")
            )
        }
        
        if (methodSignaturePatterns.any { it.containsMatchIn(trimmed) }) return true
        
        // Code-like patterns
        if (trimmed.contains(Regex("[{}();=\\[\\]]"))) return true
        if (trimmed.matches(Regex(".*\\w+\\s*\\(.*\\).*"))) return true
        
        // Lines that start with explanatory words are likely not code
        val explanatoryStarters = listOf(
            "this", "the", "note", "explanation", "improvement", "benefit",
            "change", "update", "enhancement", "optimization"
        )
        
        if (explanatoryStarters.any { trimmed.lowercase().startsWith(it.lowercase()) }) return false
        
        // Default to considering it code if uncertain
        return true
    }
    
    /**
     * Validate the rewritten method
     */
    private fun validateRewrittenMethod(
        rewrittenMethod: String,
        originalMethod: String,
        methodName: String,
        language: String
    ): ValidationResult {
        val issues = mutableListOf<String>()
        
        // Basic validation
        if (rewrittenMethod.length < 10) {
            issues.add("Rewritten method is too short")
        }
        
        // Check for method signature preservation
        if (!containsMethodSignature(rewrittenMethod, methodName, language)) {
            issues.add("Method signature may have been altered")
        }
        
        // Language-specific validation
        when (language.lowercase()) {
            "java", "kotlin", "scala" -> validateJvmMethod(rewrittenMethod, issues)
            "javascript", "typescript" -> validateJavaScriptMethod(rewrittenMethod, issues)
            "python" -> validatePythonMethod(rewrittenMethod, issues)
        }
        
        // Check for balanced braces (for languages that use them)
        if (!language.lowercase().equals("python")) {
            validateBraceBalance(rewrittenMethod, issues)
        }
        
        val isValid = issues.isEmpty() || issues.all { it.contains("may") || it.contains("might") }
        
        return ValidationResult(isValid, issues)
    }
    
    /**
     * Check if method signature is preserved
     */
    private fun containsMethodSignature(methodCode: String, methodName: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "kotlin" -> {
                methodCode.contains(Regex("\\b$methodName\\s*\\(")) ||
                methodCode.contains(Regex("fun\\s+$methodName\\s*\\("))
            }
            "javascript", "typescript" -> {
                methodCode.contains(Regex("function\\s+$methodName\\s*\\(")) ||
                methodCode.contains(Regex("\\b$methodName\\s*:\\s*function")) ||
                methodCode.contains(Regex("\\b$methodName\\s*=.*function"))
            }
            "python" -> {
                methodCode.contains(Regex("def\\s+$methodName\\s*\\("))
            }
            else -> methodCode.contains(methodName)
        }
    }
    
    /**
     * Validate JVM language methods
     */
    private fun validateJvmMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("(") || !methodCode.contains(")")) {
            issues.add("Method signature may be malformed")
        }
    }
    
    /**
     * Validate JavaScript methods
     */
    private fun validateJavaScriptMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("(") || !methodCode.contains(")")) {
            issues.add("Function signature may be malformed")
        }
    }
    
    /**
     * Validate Python methods
     */
    private fun validatePythonMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("def ")) {
            issues.add("Python method definition may be missing")
        }
        if (!methodCode.contains(":")) {
            issues.add("Python method may be missing colon")
        }
    }
    
    /**
     * Validate brace balance
     */
    private fun validateBraceBalance(methodCode: String, issues: MutableList<String>) {
        val openBraces = methodCode.count { it == '{' }
        val closeBraces = methodCode.count { it == '}' }
        
        if (kotlin.math.abs(openBraces - closeBraces) > 1) {
            issues.add("Brace mismatch detected: $openBraces opening, $closeBraces closing")
        }
    }
    
    /**
     * Detect improvements made to the method
     */
    private fun detectImprovements(rewrittenMethod: String, originalMethod: String, language: String): List<String> {
        val improvements = mutableListOf<String>()
        
        // Error handling improvements
        if (hasMoreErrorHandling(rewrittenMethod, originalMethod)) {
            improvements.add("Added error handling")
        }
        
        // Null safety improvements
        if (hasMoreNullChecks(rewrittenMethod, originalMethod)) {
            improvements.add("Added null safety checks")
        }
        
        // Documentation improvements
        if (hasMoreComments(rewrittenMethod, originalMethod)) {
            improvements.add("Added documentation")
        }
        
        // Performance improvements
        if (hasPerformanceImprovements(rewrittenMethod, originalMethod, language)) {
            improvements.add("Performance optimizations")
        }
        
        // Readability improvements
        if (hasReadabilityImprovements(rewrittenMethod, originalMethod)) {
            improvements.add("Improved readability")
        }
        
        return improvements
    }
    
    /**
     * Check if error handling was added
     */
    private fun hasMoreErrorHandling(rewritten: String, original: String): Boolean {
        val errorHandlingKeywords = listOf("try", "catch", "throw", "exception", "error")
        val rewrittenCount = errorHandlingKeywords.sumOf { rewritten.lowercase().split(it).size - 1 }
        val originalCount = errorHandlingKeywords.sumOf { original.lowercase().split(it).size - 1 }
        return rewrittenCount > originalCount
    }
    
    /**
     * Check if null checks were added
     */
    private fun hasMoreNullChecks(rewritten: String, original: String): Boolean {
        val nullKeywords = listOf("null", "nil", "none", "undefined", "?.", "!!", "isNull", "isEmpty")
        val rewrittenCount = nullKeywords.sumOf { rewritten.lowercase().split(it).size - 1 }
        val originalCount = nullKeywords.sumOf { original.lowercase().split(it).size - 1 }
        return rewrittenCount > originalCount
    }
    
    /**
     * Check if comments were added
     */
    private fun hasMoreComments(rewritten: String, original: String): Boolean {
        val rewrittenComments = rewritten.count { it == '/' } + rewritten.count { it == '#' }
        val originalComments = original.count { it == '/' } + original.count { it == '#' }
        return rewrittenComments > originalComments
    }
    
    /**
     * Check for performance improvements
     */
    private fun hasPerformanceImprovements(rewritten: String, original: String, language: String): Boolean {
        // Look for modern language features that improve performance
        val performanceKeywords = when (language.lowercase()) {
            "java" -> listOf("stream", "optional", "var ", "final")
            "kotlin" -> listOf("lazy", "sequence", "inline", "suspend")
            "javascript" -> listOf("const ", "let ", "=>", "async", "await")
            "python" -> listOf("comprehension", "generator", "async", "await")
            else -> listOf("const", "final", "cache")
        }
        
        val rewrittenCount = performanceKeywords.sumOf { rewritten.lowercase().split(it).size - 1 }
        val originalCount = performanceKeywords.sumOf { original.lowercase().split(it).size - 1 }
        return rewrittenCount > originalCount
    }
    
    /**
     * Check for readability improvements
     */
    private fun hasReadabilityImprovements(rewritten: String, original: String): Boolean {
        val rewrittenLines = rewritten.lines().count { it.trim().isNotEmpty() }
        val originalLines = original.lines().count { it.trim().isNotEmpty() }
        
        // Better structure might mean more lines (extracted methods, better formatting)
        return rewrittenLines > originalLines * 1.2 || 
               hasLongerVariableNames(rewritten, original)
    }
    
    /**
     * Check if variable names are more descriptive
     */
    private fun hasLongerVariableNames(rewritten: String, original: String): Boolean {
        val rewrittenWords = rewritten.split(Regex("\\W+")).filter { it.length > 3 }
        val originalWords = original.split(Regex("\\W+")).filter { it.length > 3 }
        return rewrittenWords.size > originalWords.size
    }
    
    /**
     * Calculate confidence based on various factors
     */
    private fun calculateConfidence(
        rewrittenMethod: String,
        originalMethod: String,
        validationResult: ValidationResult,
        improvements: List<String>
    ): Float {
        var confidence = 0.7f // Start with reasonable base confidence
        
        // Validation affects confidence
        if (validationResult.isValid) {
            confidence += 0.2f
        } else {
            confidence -= validationResult.issues.size * 0.1f
        }
        
        // Improvements increase confidence
        confidence += improvements.size * 0.05f
        
        // Reasonable size changes
        val lengthRatio = rewrittenMethod.length.toFloat() / originalMethod.length
        when {
            lengthRatio in 0.5f..3.0f -> confidence += 0.1f
            lengthRatio in 0.3f..5.0f -> confidence += 0.05f
            else -> confidence -= 0.1f
        }
        
        return confidence.coerceIn(0.1f, 1.0f)
    }
    
    /**
     * Detect significant changes between original and rewritten method
     */
    private fun detectSignificantChanges(rewrittenMethod: String, originalMethod: String): Boolean {
        val lengthChange = kotlin.math.abs(rewrittenMethod.length - originalMethod.length)
        val lengthChangeRatio = lengthChange.toFloat() / maxOf(originalMethod.length, 1)
        
        // Significant if length changed by more than 20%
        if (lengthChangeRatio > 0.2f) return true
        
        // Check for structural changes
        val originalLines = originalMethod.lines().filter { it.trim().isNotEmpty() }
        val rewrittenLines = rewrittenMethod.lines().filter { it.trim().isNotEmpty() }
        
        // Significant if line count changed substantially
        if (kotlin.math.abs(originalLines.size - rewrittenLines.size) > 2) return true
        
        // Check for significant word changes
        val originalWords = originalMethod.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val rewrittenWords = rewrittenMethod.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        
        val commonWords = originalWords.intersect(rewrittenWords)
        val totalUniqueWords = originalWords.union(rewrittenWords).size
        val similarityRatio = commonWords.size.toFloat() / maxOf(totalUniqueWords, 1)
        
        // Significant if less than 60% of words are common
        return similarityRatio < 0.6f
    }
    
    private data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )
}
