package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.prompt.ZestMethodPromptBuilder
import com.zps.zest.completion.parser.ZestMethodResponseParser
import com.zps.zest.inlinechat.FloatingCodeWindow
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import com.zps.zest.gdiff.GDiff
import com.zps.zest.gdiff.EnhancedGDiff
import kotlinx.coroutines.*

/**
 * Service for managing method-level code rewrites with language-specific semantic diffing.
 * Enhanced with intelligent diff rendering and proper IntelliJ threading.
 */
@Service(Service.Level.PROJECT)
class ZestMethodRewriteService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestMethodRewriteService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Dependencies
    private val llmService by lazy { 
        try {
            LLMService(project)
        } catch (e: Exception) {
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }
    
    private val methodContextCollector = ZestMethodContextCollector(project)
    private val promptBuilder = ZestMethodPromptBuilder()
    private val responseParser = ZestMethodResponseParser()
    private val gdiff = GDiff()
    private val enhancedGDiff = EnhancedGDiff()
    private val inlineDiffRenderer = ZestInlineMethodDiffRenderer()
    
    // State management
    private var currentRewriteJob: Job? = null
    private var currentMethodContext: ZestMethodContextCollector.MethodContext? = null
    private var currentRewrittenMethod: String? = null
    private var currentDiffResult: GDiff.DiffResult? = null
    private var currentEnhancedDiffResult: EnhancedGDiff.EnhancedDiffResult? = null
    
    /**
     * Trigger method rewrite at cursor position
     */
    fun rewriteCurrentMethod(editor: Editor, offset: Int, customInstruction: String? = null) {
        scope.launch {
            try {
                logger.info("Starting method rewrite at offset $offset")
                
                // Cancel any existing rewrite
                cancelCurrentRewrite()
                
                // Find the method containing the cursor - must be done on EDT
                val methodContext = withContext(Dispatchers.Main) {
                    methodContextCollector.findMethodAtCursor(editor, offset)
                }
                
                if (methodContext == null) {
                    withContext(Dispatchers.Main) {
                        ZestNotifications.showWarning(
                            project,
                            "No Method Found",
                            "Could not identify a method at the current cursor position. " +
                            "Place cursor inside a method to rewrite it."
                        )
                    }
                    return@launch
                }
                
                currentMethodContext = methodContext
                
                logger.info("Found method: ${methodContext.methodName} (${methodContext.language})")
                
                // Show loading notification on EDT
                withContext(Dispatchers.Main) {
                    ZestNotifications.showInfo(
                        project,
                        "Method Rewrite",
                        "Analyzing and rewriting method '${methodContext.methodName}' using ${methodContext.language} semantics..."
                    )
                }
                
                // Start method rewrite process in background
                currentRewriteJob = scope.launch {
                    performMethodRewrite(editor, methodContext, customInstruction)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to trigger method rewrite", e)
                withContext(Dispatchers.Main) {
                    ZestNotifications.showError(
                        project,
                        "Method Rewrite Error",
                        "Failed to start method rewrite: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Perform the method rewrite operation with language-specific semantic analysis
     */
    private suspend fun performMethodRewrite(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        customInstruction: String?
    ) {
        try {
            logger.info("Performing method rewrite for ${methodContext.methodName} in ${methodContext.language}")
            
            // Build the method-specific prompt
            val prompt = if (customInstruction != null) {
                promptBuilder.buildCustomMethodPrompt(methodContext, customInstruction)
            } else {
                promptBuilder.buildMethodRewritePrompt(methodContext)
            }
            
            logger.debug("Generated method prompt length: ${prompt.length}")
            
            // Call LLM service in background thread
            val startTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(METHOD_REWRITE_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .withModel("local-model-mini") // Use full model for method rewrites
                    .withMaxTokens(METHOD_REWRITE_MAX_TOKENS)
                    .withTemperature(0.3) // Slightly creative but focused
                    .withStopSequences(getMethodRewriteStopSequences())
                
                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            val responseTime = System.currentTimeMillis() - startTime
            
            if (response == null) {
                throw Exception("LLM request timed out after ${METHOD_REWRITE_TIMEOUT_MS}ms")
            }
            
            logger.info("Received LLM response in ${responseTime}ms")
            
            // Parse the method rewrite response in background
            val parseResult = responseParser.parseMethodRewriteResponse(
                response = response,
                originalMethod = methodContext.methodContent,
                methodName = methodContext.methodName,
                language = methodContext.language
            )
            
            if (!parseResult.isValid) {
                logger.warn("Invalid method rewrite response: ${parseResult.issues}")
                throw Exception("Generated method is invalid: ${parseResult.issues.joinToString(", ")}")
            }
            
            // Perform language-specific semantic analysis in background
            val enhancedDiffResult = calculateLanguageSpecificDiff(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod,
                language = methodContext.language
            )
            currentEnhancedDiffResult = enhancedDiffResult
            
            // Also maintain legacy diff for compatibility  
            val diffResult = calculatePreciseChanges(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod
            )
            currentDiffResult = diffResult
            // Store the complete rewritten method (with any closing characters)
            currentRewrittenMethod = parseResult.rewrittenMethod
            
            // Show diff rendering on EDT
            withContext(Dispatchers.Main) {
                showLanguageAwareDiff(
                    editor = editor,
                    methodContext = methodContext,
                    enhancedDiffResult = enhancedDiffResult,
                    legacyDiffResult = diffResult,
                    rewrittenMethod = parseResult.rewrittenMethod,
                    parseResult = parseResult
                )
            }
            
            logger.info("Method rewrite completed successfully (confidence: ${parseResult.confidence}, language: ${methodContext.language})")
            
        } catch (e: CancellationException) {
            logger.debug("Method rewrite was cancelled")
            throw e
        } catch (e: Exception) {
            logger.error("Method rewrite failed", e)
            
            withContext(Dispatchers.Main) {
                inlineDiffRenderer.hide()
                ZestNotifications.showError(
                    project,
                    "Method Rewrite Failed",
                    "Failed to rewrite method: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Calculate language-specific semantic changes using EnhancedGDiff with optimal configuration
     * Strips trailing closing characters to focus diff on method content
     */
    private fun calculateLanguageSpecificDiff(
        originalCode: String,
        rewrittenCode: String,
        language: String
    ): EnhancedGDiff.EnhancedDiffResult {
        logger.info("Calculating language-specific diff for $language")
        
        // Strip trailing closing characters for cleaner diff
        val (originalStripped, originalClosing) = stripTrailingClosingChars(originalCode)
        val (rewrittenStripped, rewrittenClosing) = stripTrailingClosingChars(rewrittenCode)
        
        logger.debug("Stripped original closing: '$originalClosing', rewritten closing: '$rewrittenClosing'")
        
        // Configure EnhancedGDiff for optimal language-specific analysis
        val diffConfig = EnhancedGDiff.EnhancedDiffConfig(
            textConfig = GDiff.DiffConfig(
                ignoreWhitespace = shouldIgnoreWhitespaceForLanguage(language),
                ignoreCase = false,
                contextLines = getOptimalContextLinesForLanguage(language)
            ),
            preferAST = isASTPreferredForLanguage(language),
            language = language,
            useHybridApproach = true // Always use hybrid for best results
        )
        
        // Perform diff on stripped content
        val strippedResult = enhancedGDiff.diffStrings(originalStripped, rewrittenStripped, diffConfig)
        
        // Reconstruct full result with closing characters added back
        val reconstructedResult = reconstructDiffWithClosingChars(
            strippedResult, 
            originalClosing, 
            rewrittenClosing
        )
        
        logger.info("Language-specific diff completed - Strategy: ${reconstructedResult.diffStrategy}, " +
                   "Semantic changes: ${reconstructedResult.astDiff?.semanticChanges?.size ?: 0}, " +
                   "Structural similarity: ${(reconstructedResult.astDiff?.structuralSimilarity ?: 0.0) * 100}%")
        
        return reconstructedResult
    }
    
    /**
     * Show language-aware diff with enhanced rendering and semantic hints
     */
    private fun showLanguageAwareDiff(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult,
        legacyDiffResult: GDiff.DiffResult,
        rewrittenMethod: String,
        parseResult: ZestMethodResponseParser.MethodRewriteResult
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        
        // Show the inline diff renderer
        inlineDiffRenderer.show(
            editor = editor,
            methodContext = methodContext,
            diffResult = legacyDiffResult,
            rewrittenMethod = rewrittenMethod,
            onAccept = { acceptMethodRewriteInternal(editor) },
            onReject = { cancelCurrentRewrite() }
        )
        
        // Generate language-specific diff summary
        val diffSummary = createLanguageAwareDiffSummary(
            enhancedDiffResult = enhancedDiffResult,
            legacyDiffResult = legacyDiffResult,
            parseResult = parseResult,
            language = methodContext.language
        )
        
        // Show enhanced notification with semantic analysis
        ZestNotifications.showInfo(
            project,
            "Method Rewrite Ready - ${methodContext.language.uppercase()}",
            diffSummary
        )
    }
    
    /**
     * Create language-aware diff summary with semantic hints
     */
    private fun createLanguageAwareDiffSummary(
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult,
        legacyDiffResult: GDiff.DiffResult,
        parseResult: ZestMethodResponseParser.MethodRewriteResult,
        language: String
    ): String {
        return buildString {
            appendLine("🔧 ${language.uppercase()} Method Analysis:")
            
            // Show semantic analysis if available
            if (enhancedDiffResult.astDiff != null) {
                val astDiff = enhancedDiffResult.astDiff
                val summary = enhancedDiffResult.getSummary()
                
                appendLine("• Diff Strategy: ${summary.strategy}")
                appendLine("• Structural Similarity: ${(astDiff.structuralSimilarity * 100).toInt()}%")
                
                if (astDiff.semanticChanges.isNotEmpty()) {
                    appendLine("• Semantic Changes: ${astDiff.semanticChanges.size}")
                    
                    // Group semantic changes by severity
                    val majorChanges = astDiff.semanticChanges.filter { it.severity == com.zps.zest.gdiff.ASTDiffService.ChangeSeverity.MAJOR }
                    val moderateChanges = astDiff.semanticChanges.filter { it.severity == com.zps.zest.gdiff.ASTDiffService.ChangeSeverity.MODERATE }
                    
                    if (majorChanges.isNotEmpty()) {
                        appendLine("  🔴 ${majorChanges.size} major changes (logic/structure)")
                    }
                    if (moderateChanges.isNotEmpty()) {
                        appendLine("  🟡 ${moderateChanges.size} moderate changes (signatures/names)")
                    }
                    
                    // Show specific semantic changes for supported languages
                    astDiff.semanticChanges.take(3).forEach { change ->
                        appendLine("  • ${change.description}")
                    }
                    
                    if (astDiff.semanticChanges.size > 3) {
                        appendLine("  • ... and ${astDiff.semanticChanges.size - 3} more changes")
                    }
                }
                
                if (summary.hasLogicChanges) {
                    appendLine("⚠️ Contains logic changes - review carefully")
                }
                if (summary.hasStructuralChanges) {
                    appendLine("🏗️ Contains structural changes")
                }
            } else {
                // Fallback to text-based analysis
                val textStats = legacyDiffResult.getStatistics()
                appendLine("• Text-based analysis (${enhancedDiffResult.diffStrategy})")
                appendLine("• Changes: ${textStats.totalChanges} (${textStats.additions} additions, ${textStats.deletions} deletions)")
            }
            
            // Show diffing mode based on language configuration
            val isWordLevel = shouldIgnoreWhitespaceForLanguage(language)
            if (isWordLevel) {
                val textStats = legacyDiffResult.getStatistics()
                appendLine()
                appendLine("📊 Word-Level Analysis:")
                appendLine("• ✨ Using semantic word/token diffing (ignoring whitespace)")
                appendLine("• ${textStats.additions} word/token additions")
                appendLine("• ${textStats.deletions} word/token deletions")
                appendLine("• ${textStats.modifications} word/token modifications")
            } else {
                appendLine("• Using character-level text diffing (preserving whitespace)")
            }
            
            // Language-specific hints
            appendLine()
            appendLine(getLanguageSpecificHints(language, enhancedDiffResult))
            
            appendLine()
            appendLine("Confidence: ${(parseResult.confidence * 100).toInt()}% | Press TAB to accept, ESC to reject")
        }
    }
    
    /**
     * Get language-specific hints for the diff
     */
    private fun getLanguageSpecificHints(
        language: String,
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult
    ): String {
        return when (language.lowercase()) {
            "java" -> {
                val hints = mutableListOf<String>()
                
                if (enhancedDiffResult.astDiff?.semanticChanges?.any { 
                    it.nodeType == "MethodDeclaration" 
                } == true) {
                    hints.add("Method signature changes detected")
                }
                
                if (enhancedDiffResult.astDiff?.semanticChanges?.any { 
                    it.description.contains("try", true) || it.description.contains("catch", true) 
                } == true) {
                    hints.add("Exception handling modified")
                }
                
                hints.add("💡 Java: Check imports, exceptions, and access modifiers")
                hints.joinToString(" • ")
            }
            
            "javascript", "js" -> {
                val hints = mutableListOf<String>()
                
                if (enhancedDiffResult.astDiff?.semanticChanges?.any { 
                    it.nodeType == "ES6Feature" 
                } == true) {
                    hints.add("ES6+ features added/modified")
                }
                
                if (enhancedDiffResult.astDiff?.semanticChanges?.any { 
                    it.description.contains("async", true) || it.description.contains("await", true) 
                } == true) {
                    hints.add("Async/await pattern changes")
                }
                
                hints.add("💡 JavaScript: Verify async patterns, ES6 syntax, and closures")
                hints.joinToString(" • ")
            }
            
            "kotlin" -> {
                val hints = mutableListOf<String>()
                
                if (enhancedDiffResult.astDiff?.semanticChanges?.any { 
                    it.nodeType == "FunctionDeclaration" 
                } == true) {
                    hints.add("Function signature changes")
                }
                
                hints.add("💡 Kotlin: Check null safety, extension functions, and coroutines")
                hints.joinToString(" • ")
            }
            
            else -> "💡 Review changes for language-specific patterns and conventions"
        }
    }
    
    /**
     * Determine if whitespace should be ignored for the given language
     */
    private fun shouldIgnoreWhitespaceForLanguage(language: String): Boolean {
        return when (language.lowercase()) {
            "python" -> false // Python is whitespace-sensitive  
            "yaml", "yml" -> false // YAML is whitespace-sensitive
            else -> true // Use word-level diffing for most languages to focus on semantic changes
        }
    }
    
    /**
     * Get optimal context lines for language-specific diffing
     */
    private fun getOptimalContextLinesForLanguage(language: String): Int {
        return when (language.lowercase()) {
            "java" -> 5 // Java methods tend to be longer
            "javascript", "js" -> 3 // JavaScript functions are often more concise
            "kotlin" -> 4 // Kotlin is somewhere in between
            else -> 3
        }
    }
    
    /**
     * Determine if AST diffing is preferred for the language
     */
    private fun isASTPreferredForLanguage(language: String): Boolean {
        return when (language.lowercase()) {
            "java", "javascript", "js", "kotlin" -> true // Languages with good AST support
            else -> false // Fall back to text diffing for unsupported languages
        }
    }
    
    /**
     * Calculate precise changes using GDiff (legacy method maintained for compatibility)
     * Strips trailing closing characters to focus diff on method content
     */
    private fun calculatePreciseChanges(
        originalCode: String,
        rewrittenCode: String
    ): GDiff.DiffResult {
        // Strip trailing closing characters for cleaner diff
        val (originalStripped, originalClosing) = stripTrailingClosingChars(originalCode)
        val (rewrittenStripped, rewrittenClosing) = stripTrailingClosingChars(rewrittenCode)
        
        logger.debug("Legacy diff - stripped original closing: '$originalClosing', rewritten closing: '$rewrittenClosing'")
        
        // Perform diff on stripped content
        val strippedResult = gdiff.diffStrings(
            source = originalStripped,
            target = rewrittenStripped,
            config = GDiff.DiffConfig(
                ignoreWhitespace = false, // Preserve formatting changes
                ignoreCase = false,
                contextLines = 3
            )
        )
        
        // For legacy diff, we reconstruct by updating the target in changes
        return reconstructLegacyDiffWithClosingChars(
            strippedResult,
            originalStripped,
            rewrittenStripped,
            originalClosing,
            rewrittenClosing
        )
    }
    
    /**
     * Strip trailing closing characters from method code
     * Returns pair of (strippedCode, closingChars)
     */
    private fun stripTrailingClosingChars(code: String): Pair<String, String> {
        var stripped = code.trimEnd()
        val closingChars = StringBuilder()
        
        // Extract trailing closing characters (}, ], ), ;) and whitespace
        // But preserve the last newline if it exists
        val originalEndsWithNewline = code.endsWith("\n")
        
        while (stripped.isNotEmpty()) {
            val lastChar = stripped.last()
            if (lastChar in "}]);" || lastChar.isWhitespace()) {
                closingChars.insert(0, lastChar)
                stripped = stripped.dropLast(1)
            } else {
                break
            }
        }
        
        // If original ended with newline, ensure we preserve it
        if (originalEndsWithNewline && !closingChars.toString().endsWith("\n")) {
            closingChars.append("\n")
        }
        
        return Pair(stripped, closingChars.toString())
    }
    
    /**
     * Reconstruct EnhancedGDiff result with closing characters added back
     */
    private fun reconstructDiffWithClosingChars(
        strippedResult: EnhancedGDiff.EnhancedDiffResult,
        originalClosing: String,
        rewrittenClosing: String
    ): EnhancedGDiff.EnhancedDiffResult {
        // If closing characters are the same, just preserve the diff as-is
        val reconstructedTextDiff = if (originalClosing == rewrittenClosing) {
            // Same closing - no additional changes needed
            strippedResult.textDiff
        } else {
            // Different closing - this is actually a change we should track
            logger.info("Closing characters differ: '$originalClosing' -> '$rewrittenClosing'")
            
            // Add closing character difference to the changes
            val modifiedChanges = strippedResult.textDiff.changes.toMutableList()
            
            if (originalClosing != rewrittenClosing) {
                // Calculate appropriate line numbers for the closing change
                val sourceLines = strippedResult.textDiff.changes.lastOrNull()?.sourceLineNumber ?: 1
                val targetLines = strippedResult.textDiff.changes.lastOrNull()?.targetLineNumber ?: 1
                
                modifiedChanges.add(
                    GDiff.DiffChange(
                        type = if (originalClosing.isEmpty()) GDiff.ChangeType.INSERT 
                               else if (rewrittenClosing.isEmpty()) GDiff.ChangeType.DELETE
                               else GDiff.ChangeType.CHANGE,
                        sourceLineNumber = sourceLines + 1,
                        targetLineNumber = targetLines + 1,
                        sourceLines = if (originalClosing.isEmpty()) emptyList() else listOf(originalClosing.trim()),
                        targetLines = if (rewrittenClosing.isEmpty()) emptyList() else listOf(rewrittenClosing.trim())
                    )
                )
            }
            
            GDiff.DiffResult(
                changes = modifiedChanges,
                identical = false,
                sourceFile = strippedResult.textDiff.sourceFile,
                targetFile = strippedResult.textDiff.targetFile
            )
        }
        
        return EnhancedGDiff.EnhancedDiffResult(
            textDiff = reconstructedTextDiff,
            astDiff = strippedResult.astDiff, // AST diff should work on stripped content
            language = strippedResult.language,
            diffStrategy = strippedResult.diffStrategy
        )
    }
    
    /**
     * Reconstruct legacy GDiff result with closing characters
     */
    private fun reconstructLegacyDiffWithClosingChars(
        strippedResult: GDiff.DiffResult,
        originalStripped: String,
        rewrittenStripped: String,
        originalClosing: String,
        rewrittenClosing: String
    ): GDiff.DiffResult {
        // If it's identical after stripping and closing chars are same, mark as identical
        if (strippedResult.identical && originalClosing == rewrittenClosing) {
            return GDiff.DiffResult(
                changes = emptyList(),
                identical = true
            )
        }
        
        // If closing characters differ, we need to add that as a change
        val finalChanges = if (originalClosing != rewrittenClosing && originalClosing.isNotEmpty() || rewrittenClosing.isNotEmpty()) {
            val modifiedChanges = strippedResult.changes.toMutableList()
            
            // Add closing character change at the end
            val lastSourceLine = originalStripped.lines().size
            val lastTargetLine = rewrittenStripped.lines().size
            
            if (originalClosing != rewrittenClosing) {
                modifiedChanges.add(
                    GDiff.DiffChange(
                        type = if (originalClosing.isEmpty()) GDiff.ChangeType.INSERT 
                               else if (rewrittenClosing.isEmpty()) GDiff.ChangeType.DELETE
                               else GDiff.ChangeType.CHANGE,
                        sourceLineNumber = lastSourceLine + 1,
                        targetLineNumber = lastTargetLine + 1,
                        sourceLines = if (originalClosing.isEmpty()) emptyList() else listOf(originalClosing),
                        targetLines = if (rewrittenClosing.isEmpty()) emptyList() else listOf(rewrittenClosing)
                    )
                )
            }
            
            modifiedChanges
        } else {
            strippedResult.changes
        }
        
        return GDiff.DiffResult(
            changes = finalChanges,
            identical = strippedResult.identical && originalClosing == rewrittenClosing,
            sourceFile = strippedResult.sourceFile,
            targetFile = strippedResult.targetFile
        )
    }
    
    /**
     * Internal method to accept the current method rewrite and apply it using enhanced diffing
     * Must be called on EDT
     */
    private fun acceptMethodRewriteInternal(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        
        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return
        val enhancedDiffResult = currentEnhancedDiffResult
        val diffResult = currentDiffResult
        
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                
                // Apply the rewritten method
                document.replaceString(
                    methodContext.methodStartOffset,
                    methodContext.methodEndOffset,
                    rewrittenMethod
                )
                
                // Calculate the new end offset after replacement
                val newEndOffset = methodContext.methodStartOffset + rewrittenMethod.length
                
                // Reformat the replaced code using IntelliJ's code style
                val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)
                if (psiFile != null) {
                    // Commit the document to sync PSI
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
                    
                    // Reformat the replaced range
                    val codeStyleManager = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                    try {
                        codeStyleManager.reformatRange(psiFile, methodContext.methodStartOffset, newEndOffset)
                        logger.info("Reformatted replaced method range")
                    } catch (e: Exception) {
                        logger.warn("Failed to reformat replaced method: ${e.message}")
                        // Continue even if reformatting fails
                    }
                }
                
                logger.info("Applied method rewrite successfully using ${enhancedDiffResult?.diffStrategy} strategy")
            }
            
            // Hide the inline diff
            inlineDiffRenderer.hide()
            
            // Show success message with language-specific summary
            val message = createSuccessMessage(methodContext, enhancedDiffResult, diffResult)
            ZestNotifications.showInfo(
                project,
                "Method Rewrite Applied",
                message
            )
            
        } catch (e: Exception) {
            logger.error("Failed to apply method rewrite", e)
            ZestNotifications.showError(
                project,
                "Apply Method Rewrite Failed",
                "Failed to apply the rewritten method: ${e.message}"
            )
        } finally {
            cleanup()
        }
    }
    
    /**
     * Create success message with diff summary
     */
    private fun createSuccessMessage(
        methodContext: ZestMethodContextCollector.MethodContext,
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult?,
        diffResult: GDiff.DiffResult?
    ): String {
        return if (enhancedDiffResult != null) {
            val summary = enhancedDiffResult.getSummary()
            val semanticInfo = if (enhancedDiffResult.astDiff != null) {
                " with ${summary.semanticChanges} semantic changes"
            } else ""
            
            "Method '${methodContext.methodName}' rewritten successfully using ${summary.strategy} diffing$semanticInfo"
        } else {
            val stats = diffResult?.getStatistics()
            if (stats != null) {
                "Method '${methodContext.methodName}' rewritten successfully with ${stats.totalChanges} changes"
            } else {
                "Method '${methodContext.methodName}' has been successfully rewritten"
            }
        }
    }
    
    /**
     * Cancel the current method rewrite operation
     */
    fun cancelCurrentRewrite() {
        currentRewriteJob?.cancel()
        ApplicationManager.getApplication().invokeLater {
            inlineDiffRenderer.hide()
        }
        cleanup()
    }
    
    /**
     * Check if a method rewrite operation is currently in progress
     */
    fun isRewriteInProgress(): Boolean {
        return currentRewriteJob?.isActive == true || inlineDiffRenderer.isActive()
    }
    
    /**
     * Accept the method rewrite (public method for tab acceptance)
     * Thread-safe - ensures EDT execution
     */
    fun acceptMethodRewrite(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            acceptMethodRewriteInternal(editor)
        }
    }
    
    /**
     * Get stop sequences for method rewrite operations
     */
    private fun getMethodRewriteStopSequences(): List<String> {
        return listOf(
            "</method>",
            "</code>",
            "<|endoftext|>",
            "<|end|>",
            "# End of method",
            "```",
            "---",
            "Explanation:",
            "Note:",
            "Summary:",
            "**Explanation:**",
            "**Note:**",
            "class ", // Stop before starting another class/method
            "interface ",
            "enum "
        )
    }
    
    /**
     * Clean up resources
     */
    private fun cleanup() {
        currentRewriteJob = null
        currentMethodContext = null
        currentRewrittenMethod = null
        currentDiffResult = null
        currentEnhancedDiffResult = null
    }
    
    override fun dispose() {
        logger.info("Disposing ZestMethodRewriteService")
        scope.cancel()
        ApplicationManager.getApplication().invokeLater {
            inlineDiffRenderer.hide()
        }
        cleanup()
    }
    
    companion object {
        private const val METHOD_REWRITE_TIMEOUT_MS = 20000L // 20 seconds for method rewrites
        private const val METHOD_REWRITE_MAX_TOKENS = 1500 // Allow larger responses for methods
    }
}
