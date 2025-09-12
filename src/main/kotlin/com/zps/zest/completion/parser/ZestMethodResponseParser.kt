package com.zps.zest.completion.parser

/**
 * Parses AI responses for method rewrite operations.
 * Extracts clean, ready-to-use rewritten methods from AI responses.
 */
class ZestMethodResponseParser {

    data class MethodRewriteResult(
        val rewrittenMethod: String,
        val isValid: Boolean,
        val confidence: Float,
        val issues: List<String> = emptyList(),
        val improvements: List<String> = emptyList(),
        val hasSignificantChanges: Boolean = false
    )

    private data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )

    fun parseMethodRewriteResponse(
        response: String,
        originalMethod: String,
        methodName: String,
        language: String
    ): MethodRewriteResult {
        if (response.isBlank()) {
            return MethodRewriteResult("", false, 0.0f, listOf("Empty response"))
        }

        val cleanedMethod = extractMethodFromResponse(response, language).trim()
        if (cleanedMethod.isBlank()) {
            return MethodRewriteResult("", false, 0.0f, listOf("No method found in response"))
        }

        val validation = validateRewrittenMethod(cleanedMethod, originalMethod, methodName, language)
        val improvements = detectImprovements(cleanedMethod, originalMethod, language)
        val confidence = calculateConfidence(cleanedMethod, originalMethod, validation, improvements)
        val significant = detectSignificantChanges(cleanedMethod, originalMethod)

        return MethodRewriteResult(
            rewrittenMethod = cleanedMethod,
            isValid = validation.isValid,
            confidence = confidence,
            issues = validation.issues,
            improvements = improvements,
            hasSignificantChanges = significant
        )
    }

    // Extraction

    private fun extractMethodFromResponse(response: String, language: String): String {
        var cleaned = response.trim()
        cleaned = removeCommonPrefixes(cleaned)
        cleaned = stripMarkdownCodeBlocks(cleaned)
        cleaned = stripXmlLikeTags(cleaned)
        cleaned = removeTrailingExplanations(cleaned, language)
        return cleaned.trim()
    }

    private fun removeCommonPrefixes(text: String): String {
        val patterns = listOf(
            Regex("^.*?improved method.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?here.*?is.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?rewritten.*?:?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?updated.*?:?\\s*", RegexOption.IGNORE_CASE)
        )
        var result = text
        patterns.forEach { result = it.replace(result, "") }
        return result
    }

    private fun stripMarkdownCodeBlocks(text: String): String {
        return text
            .replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
    }

    private fun stripXmlLikeTags(text: String): String {
        val tags = listOf("<method>", "</method>", "<code>", "</code>", "<improved>", "</improved>")
        var result = text
        tags.forEach { result = result.replace(it, "", ignoreCase = true) }
        return result
    }

    private fun removeTrailingExplanations(text: String, language: String): String {
        val lines = text.lines().toMutableList()
        val explanations = getExplanationPatterns()
        while (lines.isNotEmpty()) {
            val last = lines.last().trim()
            if (last.isEmpty()) {
                lines.removeAt(lines.lastIndex); continue
            }
            if (explanations.any { it.matches(last) }) {
                lines.removeAt(lines.lastIndex); continue
            }
            if (looksLikeMethodCode(last, language)) break
            lines.removeAt(lines.lastIndex)
        }
        return lines.joinToString("\n")
    }

    private fun getExplanationPatterns(): List<Regex> {
        return listOf(
            Regex("^(this|the|note|explanation|improvement).*", RegexOption.IGNORE_CASE),
            Regex("^(changes made|key improvements|benefits).*", RegexOption.IGNORE_CASE),
            Regex("^(the method above|this implementation).*", RegexOption.IGNORE_CASE)
        )
    }

    private fun looksLikeMethodCode(line: String, language: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t == "}" || t == "};") return true
        val patterns = getMethodSignaturePatterns(language)
        if (patterns.any { it.containsMatchIn(t) }) return true
        if (t.contains(Regex("[{}();=\\[\\]]"))) return true
        if (t.matches(Regex(".*\\w+\\s*\\(.*\\).*"))) return true
        if (isExplanatoryStarter(t)) return false
        return true
    }

    private fun getMethodSignaturePatterns(language: String): List<Regex> {
        return when (language.lowercase()) {
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
                Regex("\\s{4,}.*")
            )
            else -> listOf(
                Regex(".*\\{$"),
                Regex(".*}$"),
                Regex("return\\s+.*;?")
            )
        }
    }

    private fun isExplanatoryStarter(line: String): Boolean {
        val starters = listOf(
            "this", "the", "note", "explanation", "improvement", "benefit",
            "change", "update", "enhancement", "optimization"
        )
        return starters.any { line.lowercase().startsWith(it) }
    }

    // Validation

    private fun validateRewrittenMethod(
        rewrittenMethod: String,
        originalMethod: String,
        methodName: String,
        language: String
    ): ValidationResult {
        val issues = mutableListOf<String>()
        addBasicValidation(issues, rewrittenMethod)
        addSignatureValidation(issues, rewrittenMethod, methodName, language)
        addLanguageSpecificValidation(issues, rewrittenMethod, language)
        addBraceBalanceIfNeeded(issues, rewrittenMethod, language)
        val isValid = issues.isEmpty() || issues.all { it.contains("may") || it.contains("might") }
        return ValidationResult(isValid, issues)
    }

    private fun addBasicValidation(issues: MutableList<String>, rewritten: String) {
        if (rewritten.length < 10) issues.add("Rewritten method is too short")
    }

    private fun addSignatureValidation(
        issues: MutableList<String>,
        methodCode: String,
        methodName: String,
        language: String
    ) {
        if (!containsMethodSignature(methodCode, methodName, language)) {
            issues.add("Method signature may have been altered")
        }
    }

    private fun addLanguageSpecificValidation(
        issues: MutableList<String>,
        methodCode: String,
        language: String
    ) {
        when (language.lowercase()) {
            "java", "kotlin", "scala" -> validateJvmMethod(methodCode, issues)
            "javascript", "typescript" -> validateJavaScriptMethod(methodCode, issues)
            "python" -> validatePythonMethod(methodCode, issues)
        }
    }

    private fun addBraceBalanceIfNeeded(
        issues: MutableList<String>,
        methodCode: String,
        language: String
    ) {
        if (!language.equals("python", ignoreCase = true)) validateBraceBalance(methodCode, issues)
    }

    private fun containsMethodSignature(methodCode: String, methodName: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "kotlin" -> methodCode.contains(Regex("\\b$methodName\\s*\\(")) ||
                    methodCode.contains(Regex("fun\\s+$methodName\\s*\\("))
            "javascript", "typescript" -> methodCode.contains(Regex("function\\s+$methodName\\s*\\(")) ||
                    methodCode.contains(Regex("\\b$methodName\\s*:\\s*function")) ||
                    methodCode.contains(Regex("\\b$methodName\\s*=.*function"))
            "python" -> methodCode.contains(Regex("def\\s+$methodName\\s*\\("))
            else -> methodCode.contains(methodName)
        }
    }

    private fun validateJvmMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("(") || !methodCode.contains(")")) {
            issues.add("Method signature may be malformed")
        }
    }

    private fun validateJavaScriptMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("(") || !methodCode.contains(")")) {
            issues.add("Function signature may be malformed")
        }
    }

    private fun validatePythonMethod(methodCode: String, issues: MutableList<String>) {
        if (!methodCode.contains("def ")) issues.add("Python method definition may be missing")
        if (!methodCode.contains(":")) issues.add("Python method may be missing colon")
    }

    private fun validateBraceBalance(methodCode: String, issues: MutableList<String>) {
        val opens = methodCode.count { it == '{' }
        val closes = methodCode.count { it == '}' }
        if (kotlin.math.abs(opens - closes) > 1) {
            issues.add("Brace mismatch detected: $opens opening, $closes closing")
        }
    }

    // Improvements

    private fun detectImprovements(rewritten: String, original: String, language: String): List<String> {
        val res = mutableListOf<String>()
        res.addIf(hasMoreErrorHandling(rewritten, original), "Added error handling")
        res.addIf(hasMoreNullChecks(rewritten, original), "Added null safety checks")
        res.addIf(hasMoreComments(rewritten, original), "Added documentation")
        res.addIf(hasPerformanceImprovements(rewritten, original, language), "Performance optimizations")
        res.addIf(hasReadabilityImprovements(rewritten, original), "Improved readability")
        return res
    }

    private fun MutableList<String>.addIf(condition: Boolean, label: String) {
        if (condition) add(label)
    }

    private fun hasMoreErrorHandling(rewritten: String, original: String): Boolean {
        val keys = listOf("try", "catch", "throw", "exception", "error")
        return countKeywordOccurrences(rewritten, keys) > countKeywordOccurrences(original, keys)
    }

    private fun hasMoreNullChecks(rewritten: String, original: String): Boolean {
        val keys = listOf("null", "nil", "none", "undefined", "?.", "!!", "isNull", "isEmpty")
        return countKeywordOccurrences(rewritten, keys) > countKeywordOccurrences(original, keys)
    }

    private fun hasMoreComments(rewritten: String, original: String): Boolean {
        val rw = rewritten.count { it == '/' } + rewritten.count { it == '#' }
        val ow = original.count { it == '/' } + original.count { it == '#' }
        return rw > ow
    }

    private fun hasPerformanceImprovements(rewritten: String, original: String, language: String): Boolean {
        val keys = getPerformanceKeywords(language)
        return countKeywordOccurrences(rewritten, keys) > countKeywordOccurrences(original, keys)
    }

    private fun getPerformanceKeywords(language: String): List<String> {
        return when (language.lowercase()) {
            "java" -> listOf("stream", "optional", "var ", "final")
            "kotlin" -> listOf("lazy", "sequence", "inline", "suspend")
            "javascript" -> listOf("const ", "let ", "=>", "async", "await")
            "python" -> listOf("comprehension", "generator", "async", "await")
            else -> listOf("const", "final", "cache")
        }
    }

    private fun countKeywordOccurrences(text: String, keywords: List<String>): Int {
        val lower = text.lowercase()
        return keywords.sumOf { key -> lower.split(key).size - 1 }
    }

    private fun hasReadabilityImprovements(rewritten: String, original: String): Boolean {
        val rewrittenLines = rewritten.lines().count { it.trim().isNotEmpty() }
        val originalLines = original.lines().count { it.trim().isNotEmpty() }
        return rewrittenLines > originalLines * 1.2 || hasLongerVariableNames(rewritten, original)
    }

    private fun hasLongerVariableNames(rewritten: String, original: String): Boolean {
        val rw = rewritten.split(Regex("\\W+")).filter { it.length > 3 }
        val ow = original.split(Regex("\\W+")).filter { it.length > 3 }
        return rw.size > ow.size
    }

    // Confidence

    private fun calculateConfidence(
        rewrittenMethod: String,
        originalMethod: String,
        validationResult: ValidationResult,
        improvements: List<String>
    ): Float {
        var c = 0.7f
        c = adjustForValidation(c, validationResult)
        c = adjustForImprovements(c, improvements)
        c = adjustForLength(c, rewrittenMethod, originalMethod)
        return c.coerceIn(0.1f, 1.0f)
    }

    private fun adjustForValidation(c: Float, validation: ValidationResult): Float {
        return if (validation.isValid) c + 0.2f else c - validation.issues.size * 0.1f
    }

    private fun adjustForImprovements(c: Float, improvements: List<String>): Float {
        return c + improvements.size * 0.05f
    }

    private fun adjustForLength(c: Float, rewritten: String, original: String): Float {
        val ratio = rewritten.length.toFloat() / maxOf(original.length, 1)
        return when {
            ratio in 0.5f..3.0f -> c + 0.1f
            ratio in 0.3f..5.0f -> c + 0.05f
            else -> c - 0.1f
        }
    }

    // Significant changes

    private fun detectSignificantChanges(rewrittenMethod: String, originalMethod: String): Boolean {
        if (hasSignificantLengthChange(rewrittenMethod, originalMethod)) return true
        if (hasSignificantLineCountChange(rewrittenMethod, originalMethod)) return true
        return hasLowWordSimilarity(rewrittenMethod, originalMethod)
    }

    private fun hasSignificantLengthChange(rewritten: String, original: String): Boolean {
        val change = kotlin.math.abs(rewritten.length - original.length)
        val ratio = change.toFloat() / maxOf(original.length, 1)
        return ratio > 0.2f
    }

    private fun hasSignificantLineCountChange(rewritten: String, original: String): Boolean {
        val o = original.lines().count { it.trim().isNotEmpty() }
        val r = rewritten.lines().count { it.trim().isNotEmpty() }
        return kotlin.math.abs(o - r) > 2
    }

    private fun hasLowWordSimilarity(rewritten: String, original: String): Boolean {
        val o = original.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val r = rewritten.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val common = o.intersect(r).size
        val total = o.union(r).size
        val similarity = common.toFloat() / maxOf(total, 1)
        return similarity < 0.6f
    }
}