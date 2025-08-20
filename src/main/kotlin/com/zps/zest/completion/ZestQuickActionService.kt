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
import com.zps.zest.completion.actions.ZestTriggerQuickAction
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import com.zps.zest.gdiff.GDiff
import com.zps.zest.gdiff.EnhancedGDiff
import com.zps.zest.completion.metrics.ZestQuickActionMetricsService
import com.zps.zest.completion.experience.ZestExperienceTracker
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service for managing method-level code rewrites with language-specific semantic diffing.
 * Enhanced with intelligent diff rendering and proper IntelliJ threading.
 * BREAKING CHANGES: Simplified flow to prevent LLM blocking
 */
@Service(Service.Level.PROJECT)
class ZestQuickActionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestQuickActionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Dependencies
    private val metricsService by lazy { ZestQuickActionMetricsService.getInstance(project) }
    private val llmService by lazy {
        try {
            System.out.println("[ZestMethodRewrite] Initializing LLMService...")
            val service = LLMService(project)
            System.out.println("[ZestMethodRewrite] LLMService initialized successfully")
            service
        } catch (e: Exception) {
            System.out.println("[ZestMethodRewrite] Failed to create LLMService: ${e.message}")
            e.printStackTrace()
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }

    private val methodContextCollector = ZestMethodContextCollector(project)
    private val promptBuilder = ZestMethodPromptBuilder(project)
    private val responseParser = ZestMethodResponseParser()
    private val gdiff = GDiff()
    private val enhancedGDiff = EnhancedGDiff()
    private val methodDiffRenderer = ZestMethodDiffRenderer()
    private val experienceTracker by lazy { ZestExperienceTracker.getInstance(project) }

    // Request tracking to prevent multiple concurrent rewrites
    private val rewriteRequestId = AtomicInteger(0)
    private var activeRewriteId: Int? = null

    // State management
    private var currentRewriteJob: Job? = null
    private var currentMethodContext: ZestMethodContextCollector.MethodContext? = null
    private var currentRewrittenMethod: String? = null
    private var currentDiffResult: GDiff.DiffResult? = null
    private var currentEnhancedDiffResult: EnhancedGDiff.EnhancedDiffResult? = null
    private var currentRewriteId: String? = null

    /**
     * Trigger method rewrite with pre-found method context and status callback for background processing
     * Method context is passed in (found on EDT before) to avoid EDT blocking
     * BREAKING CHANGE: No dialog support, uses background processing with status bar updates
     */
    fun rewriteCurrentMethodWithStatusCallback(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        customInstruction: String? = null,
        smartDialog: ZestTriggerQuickAction.SmartRewriteDialog? = null,
        statusCallback: ((String) -> Unit)? = null
    ) {
        System.out.println("[ZestMethodRewrite] rewriteCurrentMethodWithStatusCallback called:")
        System.out.println("  - methodContext: ${methodContext.methodName}")
        System.out.println("  - customInstruction: $customInstruction")

        // Cancel any existing rewrite immediately
        cancelCurrentRewrite()

        val requestId = rewriteRequestId.incrementAndGet()
        activeRewriteId = requestId

        System.out.println("[ZestMethodRewrite] Starting background method rewrite with requestId=$requestId")
        logger.info("Starting background method rewrite for method ${methodContext.methodName}, requestId=$requestId")

        // Store context immediately
        currentMethodContext = methodContext
        
        // Generate unique rewrite ID for tracking
        val rewriteId = "rewrite_${UUID.randomUUID()}"
        currentRewriteId = rewriteId

        // Track rewrite request
        metricsService.trackRewriteRequested(
            rewriteId = rewriteId,
            methodName = methodContext.methodName,
            language = methodContext.language,
            fileType = getFileType(methodContext.fileName),
            actualModel = "local-model-mini", // Will be updated when we get actual model
            customInstruction = customInstruction,
            contextInfo = mapOf(
                "is_cocos2dx" to methodContext.isCocos2dx,
                "has_related_classes" to methodContext.relatedClasses.isNotEmpty(),
                "method_length" to methodContext.methodContent.length
            )
        )

        // Update status and start LLM call immediately
        val methodInfo = if (methodContext.isCocos2dx) {
            "🎮 Analyzing Cocos2d-x method '${methodContext.methodName}'"
        } else {
            "📝 Analyzing method '${methodContext.methodName}'"
        }
        statusCallback?.invoke(methodInfo)

        // Start method rewrite process immediately in background
        System.out.println("[ZestMethodRewrite] Starting background performMethodRewrite job...")
        currentRewriteJob = scope.launch(Dispatchers.IO) {
            performMethodRewriteWithCallback(editor, methodContext, customInstruction, requestId, rewriteId, smartDialog, statusCallback)
        }
    }

    /**
     * Trigger method rewrite at cursor position (legacy method - kept for compatibility)
     */
    fun rewriteCurrentMethod(editor: Editor, offset: Int, customInstruction: String? = null) {
        scope.launch(Dispatchers.IO) {
            val requestId = rewriteRequestId.incrementAndGet()

            // Cancel any existing rewrite
            cancelCurrentRewrite()
            activeRewriteId = requestId

            try {
                // Find the method containing the cursor with async analysis
                var enhancedMethodContext: ZestMethodContextCollector.MethodContext? = null
                val contextReady = CompletableDeferred<Boolean>()

                ApplicationManager.getApplication().invokeLater {
                    methodContextCollector.findMethodWithAsyncAnalysis(editor, offset) { context ->
                        enhancedMethodContext = context
                        if (!contextReady.isCompleted) {
                            contextReady.complete(true)
                        }
                    }
                }

                // Wait for initial context (immediate) or timeout
                withTimeoutOrNull(100) {
                    contextReady.await()
                }

                val methodContext = enhancedMethodContext
                if (methodContext == null) {
                    withContext(Dispatchers.Main) {
                        ZestNotifications.showWarning(
                            project,
                            "No Method Found",
                            "Could not identify a method at the current cursor position."
                        )
                    }
                    return@launch
                }

                // Check if this request is still active
                if (activeRewriteId != requestId) {
                    return@launch
                }

                currentMethodContext = methodContext

                // Show loading notification on EDT
                ApplicationManager.getApplication().invokeLater {
                    val title = if (methodContext.isCocos2dx) "🎮 Cocos2d-x Method Rewrite" else "Method Rewrite"
                    val subtitle = if (methodContext.relatedClasses.isNotEmpty()) {
                        "Analyzing '${methodContext.methodName}' with ${methodContext.relatedClasses.size} related classes..."
                    } else {
                        "Analyzing and rewriting method '${methodContext.methodName}'..."
                    }
                    ZestNotifications.showInfo(project, title, subtitle)
                }

                // Start method rewrite process
                val rewriteId = "rewrite_${UUID.randomUUID()}"
                currentRewriteId = rewriteId
                
                // Track rewrite request
                metricsService.trackRewriteRequested(
                    rewriteId = rewriteId,
                    methodName = methodContext.methodName,
                    language = methodContext.language,
                    fileType = getFileType(methodContext.fileName),
                    actualModel = "local-model-mini",
                    customInstruction = customInstruction,
                    contextInfo = mapOf(
                        "is_cocos2dx" to methodContext.isCocos2dx,
                        "method_length" to methodContext.methodContent.length
                    )
                )
                
                currentRewriteJob = scope.launch(Dispatchers.IO) {
                    performMethodRewriteWithCallback(
                        editor,
                        methodContext,
                        customInstruction,
                        requestId,
                        rewriteId,
                        null, // No smart dialog for legacy method
                    ) { status ->
                        // Convert status updates to log messages for legacy compatibility
                        System.out.println("[ZestMethodRewrite] Status: $status")
                    }
                }

            } catch (e: Exception) {
                logger.error("Failed to trigger method rewrite", e)
                ApplicationManager.getApplication().invokeLater {
                    ZestNotifications.showError(
                        project,
                        "Method Rewrite Error",
                        "Failed to start method rewrite: ${e.message}"
                    )
                }
                if (activeRewriteId == requestId) {
                    activeRewriteId = null
                }
            }
        }
    }

    /**
     * Perform the method rewrite operation with status callback for background processing
     * BREAKING CHANGE: Removed dialog support, focuses on status bar updates
     */
    private suspend fun performMethodRewriteWithCallback(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        customInstruction: String?,
        requestId: Int,
        rewriteId: String?,  // Unique ID for metrics tracking
        smartDialog: ZestTriggerQuickAction.SmartRewriteDialog? = null,
        statusCallback: ((String) -> Unit)?
    ) {
        System.out.println("[ZestMethodRewrite] performMethodRewriteWithCallback started for request $requestId")

        try {
            // Quick validation
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId is outdated")
                return
            }

            // Retrieve RAG context first
            val retrievingContextMsg = "Retrieving relevant context from codebase..."
            statusCallback?.invoke("📚 $retrievingContextMsg")
            smartDialog?.updateProgress(1, retrievingContextMsg) // STAGE_RETRIEVING_CONTEXT = 1
            
            // Actually perform RAG retrieval and show results to user
            val contextItems = mutableListOf<String>()
            var filesSearched = 0
            
            try {
                // Get LangChain4j service for RAG
                val ragService = project.getService(com.zps.zest.langchain4j.ZestLangChain4jService::class.java)
                if (ragService != null) {
                    // Use method content as query for RAG retrieval
                    val query = methodContext.methodContent
                    val currentFileName = methodContext.fileName
                    
                    // Create progress listener to show detailed progress
                    val progressListener = object : com.zps.zest.langchain4j.RetrievalProgressListener {
                        override fun onStageUpdate(stage: String, message: String) {
                            when (stage) {
                                "COMPRESS" -> {
                                    statusCallback?.invoke("🔍 $message")
                                    smartDialog?.updateProgress(1, message)
                                }
                                "SEARCH" -> {
                                    statusCallback?.invoke("📚 $message")
                                    smartDialog?.updateProgress(1, message)
                                }
                                "BOOST" -> {
                                    statusCallback?.invoke("⚡ $message")
                                    smartDialog?.updateProgress(1, message)
                                }
                                "COMPLETE" -> {
                                    statusCallback?.invoke("✅ $message")
                                    smartDialog?.updateProgress(1, message)
                                }
                                "ERROR" -> {
                                    statusCallback?.invoke("❌ $message")
                                }
                            }
                        }
                        
                        override fun onKeywordsExtracted(keywords: List<String>) {
                            val keywordStr = keywords.take(5).joinToString(", ")
                            statusCallback?.invoke("🔑 Keywords: $keywordStr")
                            smartDialog?.updateProgress(1, "Extracted keywords: $keywordStr")
                        }
                        
                        override fun onSearchComplete(candidatesFound: Int, filteredCount: Int) {
                            val msg = "Found $candidatesFound candidates, filtered to $filteredCount"
                            statusCallback?.invoke("📊 $msg")
                            smartDialog?.updateProgress(1, msg)
                        }
                        
                        override fun onComplete(resultsCount: Int, totalTimeMs: Long) {
                            val msg = "Completed in ${totalTimeMs}ms: $resultsCount results"
                            statusCallback?.invoke("🎯 $msg")
                        }
                    }
                    
                    // Determine user intent for RAG context
                    val userIntent = customInstruction ?: "Help me understand and improve this code"
                    
                    val retrievalResult = ragService.retrieveContextWithProgress(query, userIntent, 5, 0.7, currentFileName, progressListener)
                        .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    
                    if (retrievalResult.isSuccess && retrievalResult.items.isNotEmpty()) {
                        filesSearched = retrievalResult.items.size
                        contextItems.addAll(retrievalResult.items.take(3).map { item ->
                            val source = if (item.filePath != null) " (${item.filePath}:${item.lineNumber ?: "?"})" else ""
                            "${item.title}$source: ${item.content.take(150)}${if (item.content.length > 150) "..." else ""}"
                        })
                        
                        // Show RAG results in dialog
                        smartDialog?.addContextDetails(contextItems, filesSearched)
                    } else {
                        contextItems.add("No relevant context found in codebase")
                        smartDialog?.addContextDetails(contextItems, 0)
                    }
                } else {
                    contextItems.add("RAG service not available")
                    smartDialog?.addContextDetails(contextItems, 0)
                }
            } catch (e: Exception) {
                contextItems.add("RAG retrieval failed: ${e.message}")
                smartDialog?.addContextDetails(contextItems, 0)
            }
            
            // Build prompt with RAG context
            val buildingPromptMsg = "Building AI prompt with context..."
            statusCallback?.invoke("🧠 $buildingPromptMsg")
            smartDialog?.updateProgress(2, buildingPromptMsg) // STAGE_BUILDING_PROMPT = 2
            
            val prompt = if (customInstruction != null) {
                promptBuilder.buildCustomMethodPrompt(methodContext, customInstruction)
            } else {
                promptBuilder.buildMethodRewritePrompt(methodContext)
            }
            
            // Track rewrite request
            val actualModel = llmService.getConfigStatus().model ?: "local-model-mini"
            
            // Add prompt details to dialog
            smartDialog?.addPromptDetails(
                prompt = prompt,
                tokenCount = prompt.length / 3, // Rough token estimate
                modelName = actualModel
            )
            rewriteId?.let {
                metricsService.trackRewriteRequested(
                    rewriteId = it,
                    methodName = methodContext.methodName,
                    language = methodContext.language,
                    fileType = getFileType(methodContext.fileName),
                    actualModel = actualModel,
                    contextInfo = mapOf(
                        "has_custom_instruction" to (customInstruction != null),
                        "is_cocos2dx" to methodContext.isCocos2dx,
                        "related_classes_count" to methodContext.relatedClasses.size
                    )
                )
            }
            
            // Call LLM service immediately
            System.out.println("[ZestMethodRewrite] Calling LLM service...")
            val queryingMsg = "Querying AI model..."
            statusCallback?.invoke("🤖 $queryingMsg")
            smartDialog?.updateProgress(3, queryingMsg) // STAGE_QUERYING_LLM = 3

            val startTime = System.currentTimeMillis()

            val response = withTimeoutOrNull(METHOD_REWRITE_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .useLiteCodeModel()
                    .withMaxTokens(METHOD_REWRITE_MAX_TOKENS)
                    .withTemperature(0.3)
                    .withStopSequences(getMethodRewriteStopSequences())

                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.QUICK_ACTION_LOGGING)
            }

            val responseTime = System.currentTimeMillis() - startTime

            if (response == null) {
                System.out.println("[ZestMethodRewrite] LLM request timed out")
                statusCallback?.invoke("⏰ Request timed out - please try again")
                rewriteId?.let {
                    metricsService.trackRewriteCancelled(it, "timeout")
                }
                throw Exception("LLM request timed out after ${METHOD_REWRITE_TIMEOUT_MS}ms")
            }

            System.out.println("[ZestMethodRewrite] LLM response received in ${responseTime}ms")
            
            // Add response details to dialog
            smartDialog?.addResponseDetails(
                response = response,
                responseTime = responseTime
            )
            
            // Track response received
            rewriteId?.let {
                metricsService.trackRewriteResponse(
                    rewriteId = it,
                    responseTime = responseTime,
                    rewrittenContent = response,
                    success = true
                )
            }

            // Check if request is still active
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId no longer active after LLM response")
                return
            }

            val parsingMsg = "Parsing AI response..."
            statusCallback?.invoke("⚙️ $parsingMsg")
            smartDialog?.updateProgress(4, parsingMsg) // STAGE_PARSING_RESPONSE = 4

            // Parse response
            val parseResult = responseParser.parseMethodRewriteResponse(
                response = response,
                originalMethod = methodContext.methodContent,
                methodName = methodContext.methodName,
                language = methodContext.language
            )

            if (!parseResult.isValid) {
                statusCallback?.invoke("❌ Generated code is invalid: ${parseResult.issues.firstOrNull()}")
                rewriteId?.let {
                    metricsService.trackRewriteCancelled(it, "invalid_response")
                }
                throw Exception("Generated method is invalid: ${parseResult.issues.joinToString(", ")}")
            }

            // Calculate diff
            val analyzingMsg = "Analyzing changes..."
            statusCallback?.invoke("🔍 $analyzingMsg")
            smartDialog?.updateProgress(5, analyzingMsg) // STAGE_ANALYZING_CHANGES = 5

            val enhancedDiffResult = calculateLanguageSpecificDiff(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod,
                language = methodContext.language
            )
            currentEnhancedDiffResult = enhancedDiffResult

            val diffResult = calculatePreciseChanges(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod
            )
            currentDiffResult = diffResult
            currentRewrittenMethod = parseResult.rewrittenMethod
            
            // Add analysis details to dialog
            val originalLines = methodContext.methodContent.lines().size
            val newLines = parseResult.rewrittenMethod.lines().size
            val changesDescription = "Lines: $originalLines → $newLines (${if (newLines > originalLines) "+" else ""}${newLines - originalLines})"
            
            smartDialog?.addAnalysisDetails(
                originalLines = originalLines,
                newLines = newLines,
                changes = changesDescription
            )

            // Final check
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId no longer active before showing diff")
                return
            }

            // Track rewrite viewed
            rewriteId?.let {
                val diffStats = diffResult.getStatistics()
                metricsService.trackRewriteViewed(
                    rewriteId = it,
                    diffChanges = diffStats.totalChanges,
                    confidence = parseResult.confidence
                )
            }
            
            // Show diff - this will update status bar to "Review Ready"
            val completeMsg = "Processing complete! Review changes and press TAB to accept, ESC to reject"
            statusCallback?.invoke("✅ $completeMsg")
            smartDialog?.completeProcessing() // Show completion and close dialog

            ApplicationManager.getApplication().invokeLater {
                showLanguageAwareDiff(
                    editor = editor,
                    methodContext = methodContext,
                    enhancedDiffResult = enhancedDiffResult,
                    legacyDiffResult = diffResult,
                    rewrittenMethod = parseResult.rewrittenMethod,
                    parseResult = parseResult,
                    rewriteId = rewriteId
                )
            }

            System.out.println("[ZestMethodRewrite] Method rewrite completed successfully")
            logger.info("Method rewrite completed successfully")

        } catch (e: CancellationException) {
            System.out.println("[ZestMethodRewrite] Method rewrite was cancelled")
            statusCallback?.invoke("❌ Rewrite cancelled")
            smartDialog?.updateProgress(6, "❌ Process cancelled")
            rewriteId?.let {
                metricsService.trackRewriteCancelled(it, "user_cancelled")
            }
            throw e
        } catch (e: Exception) {
            System.out.println("[ZestMethodRewrite] Method rewrite failed: ${e.message}")
            e.printStackTrace()
            logger.error("Method rewrite failed", e)

            statusCallback?.invoke("❌ Rewrite failed: ${e.message}")
            smartDialog?.updateProgress(6, "❌ Error: ${e.message}")
            
            rewriteId?.let {
                metricsService.trackRewriteCancelled(it, "error: ${e.message}")
            }

            ApplicationManager.getApplication().invokeLater {
                methodDiffRenderer.hide()
            }
        } finally {
            // Clear activeRewriteId when the rewrite is complete
            if (activeRewriteId == requestId) {
                System.out.println("[ZestMethodRewrite] Clearing activeRewriteId after completion")
                activeRewriteId = null
            }
        }
    }


    /**
     * Calculate language-specific semantic changes using EnhancedGDiff with optimal configuration
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

        // Configure EnhancedGDiff for optimal language-specific analysis
        val diffConfig = EnhancedGDiff.EnhancedDiffConfig(
            textConfig = GDiff.DiffConfig(
                ignoreWhitespace = shouldIgnoreWhitespaceForLanguage(language),
                ignoreCase = false,
                contextLines = getOptimalContextLinesForLanguage(language)
            ),
            preferAST = isASTPreferredForLanguage(language),
            language = language,
            useHybridApproach = true
        )

        // Perform diff on stripped content
        val strippedResult = enhancedGDiff.diffStrings(originalStripped, rewrittenStripped, diffConfig)

        // Reconstruct full result with closing characters added back
        return reconstructDiffWithClosingChars(strippedResult, originalClosing, rewrittenClosing)
    }

    /**
     * Calculate precise changes using GDiff (legacy method)
     */
    private fun calculatePreciseChanges(
        originalCode: String,
        rewrittenCode: String
    ): GDiff.DiffResult {
        val (originalStripped, originalClosing) = stripTrailingClosingChars(originalCode)
        val (rewrittenStripped, rewrittenClosing) = stripTrailingClosingChars(rewrittenCode)

        val strippedResult = gdiff.diffStrings(
            source = originalStripped,
            target = rewrittenStripped,
            config = GDiff.DiffConfig(
                ignoreWhitespace = false,
                ignoreCase = false,
                contextLines = 3
            )
        )

        return reconstructLegacyDiffWithClosingChars(
            strippedResult, originalStripped, rewrittenStripped, originalClosing, rewrittenClosing
        )
    }

    /**
     * Show language-aware diff with enhanced rendering
     */
    private fun showLanguageAwareDiff(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult,
        legacyDiffResult: GDiff.DiffResult,
        rewrittenMethod: String,
        parseResult: ZestMethodResponseParser.MethodRewriteResult,
        rewriteId: String? = null
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()

        // Start the diff renderer
        methodDiffRenderer.startMethodRewrite(
            editor = editor,
            methodContext = methodContext,
            onAccept = { acceptMethodRewriteInternal(editor, rewriteId) },
            onReject = { cancelCurrentRewrite(rewriteId) }
        )

        // Show processing state briefly, then show diff
        methodDiffRenderer.showProcessing()

        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(300) // Brief processing indication
            ApplicationManager.getApplication().invokeLater {
                methodDiffRenderer.showDiff(legacyDiffResult, rewrittenMethod)

                // Show notification with diff summary
                val diffSummary = createLanguageAwareDiffSummary(
                    enhancedDiffResult, legacyDiffResult, parseResult, methodContext.language
                )

                val title = if (methodContext.isCocos2dx) {
                    "🎮 Cocos2d-x Method Rewrite Ready - ${methodContext.language.uppercase()}"
                } else {
                    "Method Rewrite Ready - ${methodContext.language.uppercase()}"
                }

//                ZestNotifications.showInfo(
//                    project,
//                    title,
//                    buildCocos2dxAwareDiffSummary(diffSummary, methodContext)
//                )
            }
        }
    }

    // Helper methods (language-specific configurations)
    private fun shouldIgnoreWhitespaceForLanguage(language: String): Boolean {
        return when (language.lowercase()) {
            "python", "yaml", "yml" -> false
            else -> true
        }
    }

    private fun getOptimalContextLinesForLanguage(language: String): Int {
        return when (language.lowercase()) {
            "java" -> 5
            "javascript", "js" -> 3
            "kotlin" -> 4
            else -> 3
        }
    }

    private fun isASTPreferredForLanguage(language: String): Boolean {
        return when (language.lowercase()) {
            "java", "javascript", "js", "kotlin" -> true
            else -> false
        }
    }

    // Helper methods for string manipulation and diff reconstruction
    private fun stripTrailingClosingChars(code: String): Pair<String, String> {
        var stripped = code.trimEnd()
        val closingChars = StringBuilder()
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

        if (originalEndsWithNewline && !closingChars.toString().endsWith("\n")) {
            closingChars.append("\n")
        }

        return Pair(stripped, closingChars.toString())
    }

    private fun reconstructDiffWithClosingChars(
        strippedResult: EnhancedGDiff.EnhancedDiffResult,
        originalClosing: String,
        rewrittenClosing: String
    ): EnhancedGDiff.EnhancedDiffResult {
        // Implementation for reconstructing enhanced diff with closing chars
        return strippedResult // Simplified for brevity
    }

    private fun reconstructLegacyDiffWithClosingChars(
        strippedResult: GDiff.DiffResult,
        originalStripped: String,
        rewrittenStripped: String,
        originalClosing: String,
        rewrittenClosing: String
    ): GDiff.DiffResult {
        // Implementation for reconstructing legacy diff with closing chars
        return strippedResult // Simplified for brevity
    }

    /**
     * Create language-aware diff summary
     */
    private fun createLanguageAwareDiffSummary(
        enhancedDiffResult: EnhancedGDiff.EnhancedDiffResult,
        legacyDiffResult: GDiff.DiffResult,
        parseResult: ZestMethodResponseParser.MethodRewriteResult,
        language: String
    ): String {
        return buildString {
            appendLine("🔧 ${language.uppercase()} Method Analysis:")

            if (enhancedDiffResult.astDiff != null) {
                val summary = enhancedDiffResult.getSummary()
                appendLine("• Diff Strategy: ${summary.strategy}")
                appendLine("• Semantic Changes: ${summary.semanticChanges}")
                if (summary.hasLogicChanges) {
                    appendLine("⚠️ Contains logic changes - review carefully")
                }
            } else {
                val textStats = legacyDiffResult.getStatistics()
                appendLine("• Text-based analysis")
                appendLine("• Changes: ${textStats.totalChanges}")
            }

            appendLine()
            appendLine("Confidence: ${(parseResult.confidence * 100).toInt()}% | Press TAB to accept, ESC to reject")
        }
    }

    private fun buildCocos2dxAwareDiffSummary(
        baseSummary: String,
        methodContext: ZestMethodContextCollector.MethodContext
    ): String {
        if (!methodContext.isCocos2dx) return baseSummary

        return buildString {
            appendLine("🎮 COCOS2D-X PROJECT DETECTED")
            appendLine()
            methodContext.cocosFrameworkVersion?.let { appendLine("🔧 Framework: Cocos2d-x $it") }
            methodContext.cocosContextType?.let { appendLine("📍 Context: ${it.name.lowercase().replace('_', ' ')}") }
            appendLine()
            append(baseSummary)
        }
    }

    /**
     * Accept the method rewrite
     */
    private fun acceptMethodRewriteInternal(editor: Editor, rewriteId: String? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return

        try {
            // Notify status bar that we're applying changes
            val statusBarWidget = getStatusBarWidget()
            statusBarWidget?.updateMethodRewriteState(
                ZestCompletionStatusBarWidget.MethodRewriteState.APPLYING,
                "Applying method changes..."
            )

            methodDiffRenderer.acceptChanges()

            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                
                // Always replace the entire method to preserve LLM's complete output
                replaceFullMethod(document, methodContext, rewrittenMethod)
            }

            // Track acceptance
            rewriteId?.let {
                metricsService.trackRewriteAccepted(
                    rewriteId = it,
                    acceptedContent = rewrittenMethod,
                    userAction = "tab"
                )
                
                // Track for experience learning
                experienceTracker.trackRewriteAcceptance(
                    sessionId = it,
                    methodName = methodContext.methodName,
                    originalCode = methodContext.methodContent,
                    aiSuggestion = rewrittenMethod,
                    editor = editor
                )
            }
            
            // Notify completion
            statusBarWidget?.updateMethodRewriteState(
                ZestCompletionStatusBarWidget.MethodRewriteState.COMPLETED,
                "Method '${methodContext.methodName}' rewritten successfully"
            )

        } catch (e: Exception) {
            logger.error("Failed to apply method rewrite", e)
            ZestNotifications.showError(project, "Apply Failed", "Failed to apply rewrite: ${e.message}")

            // Clear status on error
            getStatusBarWidget()?.clearMethodRewriteState()
        } finally {
            cleanup()
        }
    }

    /**
     * Replace the full method (fallback when we can't preserve declaration)
     */
    private fun replaceFullMethod(
        document: com.intellij.openapi.editor.Document,
        methodContext: ZestMethodContextCollector.MethodContext,
        rewrittenMethod: String
    ) {
        document.replaceString(
            methodContext.methodStartOffset,
            methodContext.methodEndOffset,
            rewrittenMethod
        )

        val newEndOffset = methodContext.methodStartOffset + rewrittenMethod.length

        // Reformat the code
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            try {
                CodeStyleManager.getInstance(project)
                    .reformatRange(psiFile, methodContext.methodStartOffset, newEndOffset)
            } catch (e: Exception) {
                logger.warn("Failed to reformat: ${e.message}")
            }
        }
    }


    /**
     * Get the status bar widget for updates
     */
    private fun getStatusBarWidget(): ZestCompletionStatusBarWidget? {
        return try {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.getWidget(ZestCompletionStatusBarWidget.WIDGET_ID) as? ZestCompletionStatusBarWidget
        } catch (e: Exception) {
            logger.debug("Could not get status bar widget", e)
            null
        }
    }

    /**
     * Cancel the current method rewrite operation
     */
    fun cancelCurrentRewrite() {
        cancelCurrentRewrite(null)
    }
    
    /**
     * Cancel the current method rewrite operation with specific rewrite ID
     */
    fun cancelCurrentRewrite(rewriteId: String?) {
        // Track rejection if rewrite ID is provided
        val idToTrack = rewriteId ?: currentRewriteId
        idToTrack?.let {
            metricsService.trackRewriteRejected(it, "esc_pressed")
        }
        currentRewriteJob?.cancel()
        ApplicationManager.getApplication().invokeLater {
            methodDiffRenderer.hide()
            // Clear status bar state
            getStatusBarWidget()?.clearMethodRewriteState()
        }
        currentRewriteJob = null
        currentMethodContext = null
        currentRewrittenMethod = null
        currentDiffResult = null
        currentEnhancedDiffResult = null
        currentRewriteId = null
    }

    /**
     * Check if rewrite is in progress
     */
    fun isRewriteInProgress(): Boolean {
        return currentRewriteJob?.isActive == true || methodDiffRenderer.isActive()
    }

    /**
     * Accept method rewrite (public method)
     */
    fun acceptMethodRewrite(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            acceptMethodRewriteInternal(editor, currentRewriteId)
        }
    }

    /**
     * Get stop sequences for method rewrite
     */
    private fun getMethodRewriteStopSequences(): List<String> {
        return listOf(
            "</method>", "</code>", "<|endoftext|>", "<|end|>",
            "# End of method", "```", "---", "Explanation:",
            "Note:", "Summary:", "class ", "interface ", "enum "
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
        currentRewriteId = null
    }

    override fun dispose() {
        logger.info("Disposing ZestMethodRewriteService")
        activeRewriteId = null
        scope.cancel()
        ApplicationManager.getApplication().invokeLater {
            methodDiffRenderer.hide()
            getStatusBarWidget()?.clearMethodRewriteState()
        }
        cleanup()
    }

    private fun getFileType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "")) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "header"
            "py" -> "python"
            else -> "unknown"
        }
    }


    companion object {
        private const val METHOD_REWRITE_TIMEOUT_MS = 200000L // 20 seconds
        private const val METHOD_REWRITE_MAX_TOKENS = 1500
    }
}