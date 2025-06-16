package com.zps.zest.gdiff

import com.intellij.openapi.diagnostic.Logger

/**
 * Enhanced GDiff that combines traditional text-based diffing with AST-based semantic diffing.
 * 
 * This class automatically chooses the most appropriate diffing strategy based on the content
 * and language, providing both granular text changes and high-level semantic insights.
 */
class EnhancedGDiff {
    private val logger = Logger.getInstance(EnhancedGDiff::class.java)
    private val textDiffer = GDiff()
    private val astDiffer = ASTDiffService()
    
    /**
     * Enhanced diff result that includes both text and semantic information
     */
    data class EnhancedDiffResult(
        val textDiff: GDiff.DiffResult,
        val astDiff: ASTDiffService.ASTDiffResult?,
        val language: String,
        val diffStrategy: DiffStrategy
    ) {
        fun hasSemanticChanges(): Boolean = astDiff?.semanticChanges?.isNotEmpty() == true
        fun hasTextChanges(): Boolean = textDiff.hasChanges()
        fun hasAnyChanges(): Boolean = hasSemanticChanges() || hasTextChanges()
        
        fun getSummary(): DiffSummary {
            val textStats = textDiff.getStatistics()
            val astStats = astDiff?.getStatistics()
            
            return DiffSummary(
                textChanges = textStats.totalChanges,
                semanticChanges = astStats?.totalChanges ?: 0,
                structuralSimilarity = astDiff?.structuralSimilarity ?: 0.0,
                hasLogicChanges = astDiff?.hasLogicChanges ?: false,
                hasStructuralChanges = astDiff?.hasStructuralChanges ?: false,
                strategy = diffStrategy
            )
        }
    }
    
    data class DiffSummary(
        val textChanges: Int,
        val semanticChanges: Int,
        val structuralSimilarity: Double,
        val hasLogicChanges: Boolean,
        val hasStructuralChanges: Boolean,
        val strategy: DiffStrategy
    )
    
    enum class DiffStrategy {
        TEXT_ONLY,          // Only text-based diffing used
        AST_ONLY,           // Only AST-based diffing used  
        HYBRID,             // Both text and AST diffing used
        AST_WITH_FALLBACK   // AST diffing with text fallback
    }
    
    /**
     * Configuration for enhanced diffing
     */
    data class EnhancedDiffConfig(
        val textConfig: GDiff.DiffConfig = GDiff.DiffConfig(),
        val preferAST: Boolean = true,
        val language: String? = null,
        val useHybridApproach: Boolean = true
    )
    
    /**
     * Perform enhanced diffing with automatic strategy selection
     */
    fun diffStrings(
        source: String,
        target: String,
        config: EnhancedDiffConfig = EnhancedDiffConfig()
    ): EnhancedDiffResult {
        logger.info("Starting enhanced diff with strategy: ${determineStrategy(config)}")
        
        val language = config.language ?: detectLanguage(source, target)
        val strategy = determineStrategy(config, language)
        
        return when (strategy) {
            DiffStrategy.TEXT_ONLY -> performTextOnlyDiff(source, target, config.textConfig, language)
            DiffStrategy.AST_ONLY -> performASTOnlyDiff(source, target, language)
            DiffStrategy.HYBRID -> performHybridDiff(source, target, config, language)
            DiffStrategy.AST_WITH_FALLBACK -> performASTWithFallback(source, target, config, language)
        }
    }
    
    /**
     * Calculate semantic changes for method rewriting - ideal for ZestMethodRewriteService
     */
    fun calculateSemanticChanges(
        originalMethod: String,
        rewrittenMethod: String,
        language: String
    ): EnhancedDiffResult {
        logger.info("Calculating semantic changes for $language method")
        
        val config = EnhancedDiffConfig(
            preferAST = true,
            language = language,
            useHybridApproach = true,
            textConfig = GDiff.DiffConfig(
                ignoreWhitespace = false, // Preserve formatting for method context
                contextLines = 5
            )
        )
        
        return diffStrings(originalMethod, rewrittenMethod, config)
    }
    
    /**
     * Determine the best diffing strategy based on configuration and content
     */
    private fun determineStrategy(
        config: EnhancedDiffConfig,
        language: String? = null
    ): DiffStrategy {
        return when {
            !config.preferAST -> DiffStrategy.TEXT_ONLY
            language != null && isASTSupportedLanguage(language) -> {
                if (config.useHybridApproach) DiffStrategy.HYBRID else DiffStrategy.AST_WITH_FALLBACK
            }
            else -> DiffStrategy.TEXT_ONLY
        }
    }
    
    /**
     * Check if language supports AST diffing
     */
    private fun isASTSupportedLanguage(language: String): Boolean {
        return when (language.lowercase()) {
            "java", "javascript", "js", "kotlin" -> true
            else -> false
        }
    }
    
    /**
     * Detect language from code content (basic heuristics)
     */
    private fun detectLanguage(source: String, target: String): String {
        val combined = source + target
        
        return when {
            combined.contains("public class") || 
            combined.contains("private ") || 
            combined.contains("@Override") ||
            combined.contains("import java.") -> "java"
            
            combined.contains("function ") || 
            combined.contains("const ") || 
            combined.contains("=> ") ||
            combined.contains("var ") ||
            combined.contains("let ") -> "javascript"
            
            combined.contains("fun ") ||
            combined.contains("class ") && combined.contains("val ") ||
            combined.contains("import kotlin.") -> "kotlin"
            
            else -> "unknown"
        }
    }
    
    /**
     * Perform text-only diffing
     */
    private fun performTextOnlyDiff(
        source: String,
        target: String,
        textConfig: GDiff.DiffConfig,
        language: String
    ): EnhancedDiffResult {
        val textDiff = textDiffer.diffStrings(source, target, textConfig)
        
        return EnhancedDiffResult(
            textDiff = textDiff,
            astDiff = null,
            language = language,
            diffStrategy = DiffStrategy.TEXT_ONLY
        )
    }
    
    /**
     * Perform AST-only diffing
     */
    private fun performASTOnlyDiff(
        source: String,
        target: String,
        language: String
    ): EnhancedDiffResult {
        val astDiff = astDiffer.diffWithAST(source, target, language, fallbackToDiff = false)
        
        // Create minimal text diff for compatibility
        val basicTextDiff = GDiff.DiffResult(
            changes = emptyList(),
            identical = astDiff.isEmpty()
        )
        
        return EnhancedDiffResult(
            textDiff = basicTextDiff,
            astDiff = astDiff,
            language = language,
            diffStrategy = DiffStrategy.AST_ONLY
        )
    }
    
    /**
     * Perform hybrid diffing (both text and AST)
     */
    private fun performHybridDiff(
        source: String,
        target: String,
        config: EnhancedDiffConfig,
        language: String
    ): EnhancedDiffResult {
        val textDiff = textDiffer.diffStrings(source, target, config.textConfig)
        val astDiff = astDiffer.diffWithAST(source, target, language, fallbackToDiff = false)
        
        return EnhancedDiffResult(
            textDiff = textDiff,
            astDiff = astDiff,
            language = language,
            diffStrategy = DiffStrategy.HYBRID
        )
    }
    
    /**
     * Perform AST diffing with text fallback
     */
    private fun performASTWithFallback(
        source: String,
        target: String,
        config: EnhancedDiffConfig,
        language: String
    ): EnhancedDiffResult {
        val astDiff = astDiffer.diffWithAST(source, target, language, fallbackToDiff = true)
        
        val textDiff = if (astDiff.textBasedFallback != null) {
            astDiff.textBasedFallback
        } else {
            textDiffer.diffStrings(source, target, config.textConfig)
        }
        
        return EnhancedDiffResult(
            textDiff = textDiff,
            astDiff = astDiff,
            language = language,
            diffStrategy = DiffStrategy.AST_WITH_FALLBACK
        )
    }
    
    /**
     * Generate enhanced unified diff that includes semantic annotations
     */
    fun generateEnhancedUnifiedDiff(
        source: String,
        target: String,
        sourceFileName: String = "source",
        targetFileName: String = "target",
        config: EnhancedDiffConfig = EnhancedDiffConfig()
    ): String {
        val result = diffStrings(source, target, config)
        
        val textDiff = textDiffer.generateUnifiedDiff(
            source, target, sourceFileName, targetFileName, config.textConfig
        )
        
        val semanticSummary = if (result.astDiff != null) {
            buildString {
                appendLine("# Semantic Analysis Summary")
                appendLine("# Language: ${result.language}")
                appendLine("# Structural Similarity: ${(result.astDiff.structuralSimilarity * 100).toInt()}%")
                appendLine("# Logic Changes: ${result.astDiff.hasLogicChanges}")
                appendLine("# Structural Changes: ${result.astDiff.hasStructuralChanges}")
                appendLine("# Total Semantic Changes: ${result.astDiff.semanticChanges.size}")
                
                if (result.astDiff.semanticChanges.isNotEmpty()) {
                    appendLine("#")
                    appendLine("# Semantic Changes:")
                    result.astDiff.semanticChanges.forEach { change ->
                        appendLine("# - ${change.description} (${change.severity})")
                    }
                }
                appendLine("")
            }
        } else ""
        
        return semanticSummary + textDiff
    }
}
