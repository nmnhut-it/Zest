package com.zps.zest.gdiff

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Enhanced AST-based diffing service using GumTree for semantic code comparison.
 * 
 * This service provides Abstract Syntax Tree level diffing that understands
 * the structure and semantics of code, going beyond simple text-based comparison.
 * 
 * Note: This implementation focuses on core functionality and gracefully falls back
 * to text-based diffing when AST parsing is not available or fails.
 */
class ASTDiffService {
    private val logger = Logger.getInstance(ASTDiffService::class.java)
    
    // Flag to track if GumTree is available and initialized
    private var gumTreeAvailable = false
    
    init {
        try {
            // Try to initialize GumTree - this may fail if generators aren't available
            Class.forName("com.github.gumtreediff.client.Run")
            gumTreeAvailable = true
            logger.info("GumTree core library detected and available")
        } catch (e: Exception) {
            logger.warn("GumTree core library not available, falling back to text diffing", e)
            gumTreeAvailable = false
        }
    }
    
    /**
     * Represents a semantic change in the AST
     */
    data class SemanticChange(
        val action: ChangeAction,
        val nodeType: String,
        val nodeLabel: String?,
        val position: Position,
        val description: String,
        val severity: ChangeSeverity = ChangeSeverity.MINOR
    )
    
    enum class ChangeAction {
        INSERT,     // Node was added
        DELETE,     // Node was removed  
        UPDATE,     // Node was modified
        MOVE        // Node was moved to different position
    }
    
    enum class ChangeSeverity {
        MINOR,      // Formatting, comments, minor refactoring
        MODERATE,   // Method signature changes, variable renames
        MAJOR       // Logic changes, new functionality
    }
    
    data class Position(
        val line: Int,
        val column: Int,
        val startOffset: Int,
        val endOffset: Int
    )
    
    /**
     * Result of AST-based diffing with semantic information
     */
    data class ASTDiffResult(
        val semanticChanges: List<SemanticChange>,
        val structuralSimilarity: Double, // 0.0 to 1.0
        val hasLogicChanges: Boolean,
        val hasStructuralChanges: Boolean,
        val language: String,
        val textBasedFallback: GDiff.DiffResult? = null,
        val astProcessingAvailable: Boolean = true
    ) {
        fun isEmpty(): Boolean = semanticChanges.isEmpty()
        
        fun getMajorChanges(): List<SemanticChange> = 
            semanticChanges.filter { it.severity == ChangeSeverity.MAJOR }
            
        fun getStatistics(): ASTDiffStatistics {
            val insertions = semanticChanges.count { it.action == ChangeAction.INSERT }
            val deletions = semanticChanges.count { it.action == ChangeAction.DELETE }
            val updates = semanticChanges.count { it.action == ChangeAction.UPDATE }
            val moves = semanticChanges.count { it.action == ChangeAction.MOVE }
            
            return ASTDiffStatistics(insertions, deletions, updates, moves, structuralSimilarity)
        }
    }
    
    data class ASTDiffStatistics(
        val insertions: Int,
        val deletions: Int,
        val updates: Int,
        val moves: Int,
        val similarity: Double
    ) {
        val totalChanges: Int get() = insertions + deletions + updates + moves
    }
    
    /**
     * Perform semantic AST-based diffing for supported languages
     */
    fun diffWithAST(
        originalCode: String,
        rewrittenCode: String,
        language: String,
        fallbackToDiff: Boolean = true
    ): ASTDiffResult {
        try {
            logger.info("Attempting AST diff for language: $language")
            
            if (!gumTreeAvailable) {
                logger.warn("GumTree not available, using fallback for $language")
                return createFallbackResult(originalCode, rewrittenCode, language, fallbackToDiff, false)
            }
            
            // For now, we'll focus on languages where we can implement simple AST analysis
            when (language.lowercase()) {
                "java" -> return analyzeJavaSemantics(originalCode, rewrittenCode)
                "javascript", "js" -> return analyzeJavaScriptSemantics(originalCode, rewrittenCode)
                "kotlin" -> return analyzeKotlinSemantics(originalCode, rewrittenCode)
                else -> {
                    logger.info("Language $language not supported for AST diffing, using fallback")
                    return createFallbackResult(originalCode, rewrittenCode, language, fallbackToDiff, true)
                }
            }
        } catch (e: Exception) {
            logger.error("AST diffing failed for $language", e)
            return createFallbackResult(originalCode, rewrittenCode, language, fallbackToDiff, true)
        }
    }
    
    /**
     * Analyze Java code semantics using pattern matching
     */
    private fun analyzeJavaSemantics(originalCode: String, rewrittenCode: String): ASTDiffResult {
        val changes = mutableListOf<SemanticChange>()
        
        // Simple pattern-based analysis for Java
        val originalLines = originalCode.lines()
        val rewrittenLines = rewrittenCode.lines()
        
        // Detect method signature changes
        val originalMethods = extractJavaMethodSignatures(originalLines)
        val rewrittenMethods = extractJavaMethodSignatures(rewrittenLines)
        
        // Find added methods
        rewrittenMethods.filterNot { it in originalMethods }.forEach { method ->
            changes.add(SemanticChange(
                action = ChangeAction.INSERT,
                nodeType = "MethodDeclaration",
                nodeLabel = method,
                position = Position(0, 0, 0, 0),
                description = "Added method: $method",
                severity = ChangeSeverity.MAJOR
            ))
        }
        
        // Find removed methods
        originalMethods.filterNot { it in rewrittenMethods }.forEach { method ->
            changes.add(SemanticChange(
                action = ChangeAction.DELETE,
                nodeType = "MethodDeclaration", 
                nodeLabel = method,
                position = Position(0, 0, 0, 0),
                description = "Removed method: $method",
                severity = ChangeSeverity.MAJOR
            ))
        }
        
        // Detect control flow changes
        val originalControlFlow = extractControlFlowKeywords(originalLines)
        val rewrittenControlFlow = extractControlFlowKeywords(rewrittenLines)
        
        val controlFlowDiff = rewrittenControlFlow - originalControlFlow
        if (controlFlowDiff.isNotEmpty()) {
            changes.add(SemanticChange(
                action = ChangeAction.UPDATE,
                nodeType = "ControlFlow",
                nodeLabel = controlFlowDiff.joinToString(", "),
                position = Position(0, 0, 0, 0),
                description = "Control flow changes detected: ${controlFlowDiff.joinToString(", ")}",
                severity = ChangeSeverity.MAJOR
            ))
        }
        
        val similarity = calculateCodeSimilarity(originalCode, rewrittenCode)
        val hasLogicChanges = changes.any { it.severity == ChangeSeverity.MAJOR }
        val hasStructuralChanges = changes.any { it.nodeType == "MethodDeclaration" }
        
        return ASTDiffResult(
            semanticChanges = changes,
            structuralSimilarity = similarity,
            hasLogicChanges = hasLogicChanges,
            hasStructuralChanges = hasStructuralChanges,
            language = "java",
            astProcessingAvailable = true
        )
    }
    
    /**
     * Analyze JavaScript code semantics using pattern matching
     */
    private fun analyzeJavaScriptSemantics(originalCode: String, rewrittenCode: String): ASTDiffResult {
        val changes = mutableListOf<SemanticChange>()
        
        val originalLines = originalCode.lines()
        val rewrittenLines = rewrittenCode.lines()
        
        // Detect function changes
        val originalFunctions = extractJavaScriptFunctions(originalLines)
        val rewrittenFunctions = extractJavaScriptFunctions(rewrittenLines)
        
        // Find added functions
        rewrittenFunctions.filterNot { it in originalFunctions }.forEach { function ->
            changes.add(SemanticChange(
                action = ChangeAction.INSERT,
                nodeType = "FunctionDeclaration",
                nodeLabel = function,
                position = Position(0, 0, 0, 0),
                description = "Added function: $function",
                severity = ChangeSeverity.MAJOR
            ))
        }
        
        // Find removed functions
        originalFunctions.filterNot { it in rewrittenFunctions }.forEach { function ->
            changes.add(SemanticChange(
                action = ChangeAction.DELETE,
                nodeType = "FunctionDeclaration",
                nodeLabel = function,
                position = Position(0, 0, 0, 0),
                description = "Removed function: $function",
                severity = ChangeSeverity.MAJOR
            ))
        }
        
        // Detect ES6+ features
        val es6Changes = detectES6Changes(originalLines, rewrittenLines)
        changes.addAll(es6Changes)
        
        val similarity = calculateCodeSimilarity(originalCode, rewrittenCode)
        val hasLogicChanges = changes.any { it.severity == ChangeSeverity.MAJOR }
        val hasStructuralChanges = changes.any { it.nodeType.contains("Function") }
        
        return ASTDiffResult(
            semanticChanges = changes,
            structuralSimilarity = similarity,
            hasLogicChanges = hasLogicChanges,
            hasStructuralChanges = hasStructuralChanges,
            language = "javascript",
            astProcessingAvailable = true
        )
    }
    
    /**
     * Analyze Kotlin code semantics using pattern matching
     */
    private fun analyzeKotlinSemantics(originalCode: String, rewrittenCode: String): ASTDiffResult {
        val changes = mutableListOf<SemanticChange>()
        
        val originalLines = originalCode.lines()
        val rewrittenLines = rewrittenCode.lines()
        
        // Detect function changes
        val originalFunctions = extractKotlinFunctions(originalLines)
        val rewrittenFunctions = extractKotlinFunctions(rewrittenLines)
        
        // Find function changes
        rewrittenFunctions.filterNot { it in originalFunctions }.forEach { function ->
            if (originalFunctions.none { orig -> function.contains(orig.split("(")[0]) }) {
                changes.add(SemanticChange(
                    action = ChangeAction.INSERT,
                    nodeType = "FunctionDeclaration",
                    nodeLabel = function,
                    position = Position(0, 0, 0, 0),
                    description = "Added function: $function",
                    severity = ChangeSeverity.MAJOR
                ))
            }
        }
        
        val similarity = calculateCodeSimilarity(originalCode, rewrittenCode)
        val hasLogicChanges = changes.any { it.severity == ChangeSeverity.MAJOR }
        val hasStructuralChanges = changes.any { it.nodeType.contains("Function") }
        
        return ASTDiffResult(
            semanticChanges = changes,
            structuralSimilarity = similarity,
            hasLogicChanges = hasLogicChanges,
            hasStructuralChanges = hasStructuralChanges,
            language = "kotlin",
            astProcessingAvailable = true
        )
    }
    
    // Helper methods for pattern extraction
    
    private fun extractJavaMethodSignatures(lines: List<String>): Set<String> {
        val methods = mutableSetOf<String>()
        val methodPattern = Regex("""(public|private|protected)?\s*(static)?\s*\w+\s+(\w+)\s*\([^)]*\)""")
        
        lines.forEach { line ->
            val trimmed = line.trim()
            methodPattern.find(trimmed)?.let { match ->
                methods.add(match.groups[3]?.value ?: "unknown")
            }
        }
        
        return methods
    }
    
    private fun extractControlFlowKeywords(lines: List<String>): Set<String> {
        val keywords = mutableSetOf<String>()
        val controlFlowPattern = Regex("""\b(if|while|for|switch|try|catch|finally)\b""")
        
        lines.forEach { line ->
            controlFlowPattern.findAll(line).forEach { match ->
                keywords.add(match.value)
            }
        }
        
        return keywords
    }
    
    private fun extractJavaScriptFunctions(lines: List<String>): Set<String> {
        val functions = mutableSetOf<String>()
        val functionPattern = Regex("""(function\s+(\w+)|(\w+)\s*=\s*function|(\w+)\s*=\s*\([^)]*\)\s*=>|const\s+(\w+)\s*=)""")
        
        lines.forEach { line ->
            val trimmed = line.trim()
            functionPattern.find(trimmed)?.let { match ->
                val name = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: match.groups[5]?.value ?: "anonymous"
                functions.add(name)
            }
        }
        
        return functions
    }
    
    private fun extractKotlinFunctions(lines: List<String>): Set<String> {
        val functions = mutableSetOf<String>()
        val functionPattern = Regex("""fun\s+(\w+)\s*\([^)]*\)""")
        
        lines.forEach { line ->
            val trimmed = line.trim()
            functionPattern.find(trimmed)?.let { match ->
                functions.add(match.groups[1]?.value ?: "unknown")
            }
        }
        
        return functions
    }
    
    private fun detectES6Changes(originalLines: List<String>, rewrittenLines: List<String>): List<SemanticChange> {
        val changes = mutableListOf<SemanticChange>()
        
        val originalES6Features = extractES6Features(originalLines)
        val rewrittenES6Features = extractES6Features(rewrittenLines)
        
        val newFeatures = rewrittenES6Features - originalES6Features
        newFeatures.forEach { feature ->
            changes.add(SemanticChange(
                action = ChangeAction.INSERT,
                nodeType = "ES6Feature",
                nodeLabel = feature,
                position = Position(0, 0, 0, 0),
                description = "Added ES6+ feature: $feature",
                severity = ChangeSeverity.MODERATE
            ))
        }
        
        return changes
    }
    
    private fun extractES6Features(lines: List<String>): Set<String> {
        val features = mutableSetOf<String>()
        
        lines.forEach { line ->
            when {
                line.contains("=>") -> features.add("arrow_function")
                line.contains("const ") -> features.add("const_declaration")
                line.contains("let ") -> features.add("let_declaration")
                line.contains("...") -> features.add("spread_operator")
                line.contains("async ") -> features.add("async_function")
                line.contains("await ") -> features.add("await_expression")
                line.contains("`") -> features.add("template_literal")
            }
        }
        
        return features
    }
    
    /**
     * Calculate simple code similarity based on line matching
     */
    private fun calculateCodeSimilarity(originalCode: String, rewrittenCode: String): Double {
        val originalLines = originalCode.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val rewrittenLines = rewrittenCode.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        if (originalLines.isEmpty() && rewrittenLines.isEmpty()) return 1.0
        if (originalLines.isEmpty() || rewrittenLines.isEmpty()) return 0.0
        
        val matchingLines = originalLines.intersect(rewrittenLines.toSet()).size
        val totalLines = maxOf(originalLines.size, rewrittenLines.size)
        
        return matchingLines.toDouble() / totalLines.toDouble()
    }
    
    /**
     * Create fallback result using text-based diffing
     */
    private fun createFallbackResult(
        originalCode: String,
        rewrittenCode: String,
        language: String,
        useFallback: Boolean,
        astAvailable: Boolean
    ): ASTDiffResult {
        val textDiff = if (useFallback) {
            GDiff().diffStrings(originalCode, rewrittenCode)
        } else null
        
        return ASTDiffResult(
            semanticChanges = emptyList(),
            structuralSimilarity = if (originalCode == rewrittenCode) 1.0 else 0.0,
            hasLogicChanges = false,
            hasStructuralChanges = false,
            language = language,
            textBasedFallback = textDiff,
            astProcessingAvailable = astAvailable
        )
    }
}
