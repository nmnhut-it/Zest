package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI
import com.zps.zest.completion.context.ZestSimpleContextCollector
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.parser.ZestLeanResponseParser
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.completion.prompt.ZestLeanPromptBuilder
import com.zps.zest.completion.prompt.ZestSimplePromptBuilder
import com.zps.zest.completion.prompt.PromptMigrationHelper
import com.zps.zest.completion.config.PromptCachingConfig
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.metrics.PromptCachingMetrics
import com.zps.zest.langchain4j.util.LLMService
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*

/**
 * Completion provider with multiple strategies (A/B testing)
 * - SIMPLE: Basic prefix/suffix context (original)
 * - LEAN: Full file context with reasoning prompts (new)
 * - METHOD_REWRITE: Method-level rewrite with floating window preview
 */
class ZestCompletionProvider(private val project: Project) {
    private val logger = Logger.getInstance(ZestCompletionProvider::class.java)

    private val cancellableLLM by lazy { CancellableLLMRequest(project) }

    // Add metrics service
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }
    private val cachingMetrics by lazy { PromptCachingMetrics.getInstance(project) }

    // Notification group for debug
    private val notificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("Zest Completion Debug")

    // Debug logging flags
    private var debugLoggingEnabled = true
    private var verboseLoggingEnabled = false

    /**
     * Internal debug logging function
     */
    private fun log(message: String, tag: String = "Provider", level: Int = 0) {
        if (debugLoggingEnabled && (level == 0 || verboseLoggingEnabled)) {
            val prefix = if (level > 0) "[VERBOSE]" else ""
            println("$prefix[$tag] $message")
            logger.debug("$prefix[$tag] $message")
        }
    }

    /**
     * Show balloon notification for debugging
     */
    private fun showDebugBalloon(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        if (debugLoggingEnabled) {
            // Update status bar instead of showing balloon
            updateStatusBarText("$title: $content")
        }
    }
    
    /**
     * Update status bar widget text
     */
    private fun updateStatusBarText(message: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                statusBar?.info = message
                
                // Update the widget with specific status
                try {
                    val widget = com.intellij.openapi.wm.WindowManager.getInstance()
                        .getStatusBar(project)
                        ?.getWidget("ZestCompletionStatus")
                    
                    if (widget is com.zps.zest.completion.ui.ZestCompletionStatusBarWidget) {
                        widget.updateDebugStatus(message)
                    }
                } catch (e: Exception) {
                    // Widget might not be available yet
                }
            }
        } catch (e: Exception) {
            // Fallback to console logging
            log("Status: $message", "Widget")
        }
    }

    fun setDebugLogging(enabled: Boolean, verbose: Boolean = false) {
        debugLoggingEnabled = enabled
        verboseLoggingEnabled = verbose
        log("Debug logging ${if (enabled) "enabled" else "disabled"}, verbose: $verbose")
    }

    // Request tracking for cancellation
    @Volatile
    private var currentRequestId: Int? = null

    fun setCurrentRequestId(requestId: Int?) {
        currentRequestId = requestId
    }

    // Strategy components
    private val simpleContextCollector = ZestSimpleContextCollector()
    private val simplePromptBuilder = ZestSimplePromptBuilder()
    private val simpleResponseParser = ZestSimpleResponseParser()

    // Lean strategy components
    private val leanContextCollector = ZestLeanContextCollectorPSI(project)
    private val leanPromptBuilder = ZestLeanPromptBuilder(project)
    private val leanResponseParser = ZestLeanResponseParser()

    // Method rewrite strategy components
    private val methodRewriteService by lazy {
        project.getService(ZestQuickActionService::class.java)
    }

    // Configuration
    var strategy: CompletionStrategy = CompletionStrategy.LEAN // Default to LEAN mode
        private set

    enum class CompletionStrategy {
        SIMPLE,         // Original FIM-based approach
        LEAN,           // Full-file with reasoning approach
        METHOD_REWRITE  // Method-level rewrite with floating window preview
    }

    /**
     * Switch completion strategy for A/B testing
     */
    fun setStrategy(newStrategy: CompletionStrategy) {
        logger.info("Switching completion strategy from $strategy to $newStrategy")
        strategy = newStrategy
    }

    suspend fun requestCompletion(
        context: CompletionContext,
        requestId: Int,
        completionId: String? = null
    ): ZestInlineCompletionList? {
        log("=== requestCompletion called ===", "Provider")
        log("  strategy: $strategy", "Provider")
        log("  requestId: $requestId", "Provider")
        log("  context: ${context.fileName} @ ${context.offset}", "Provider")

        // Use the provided completionId for metrics tracking
        val metricsCompletionId = completionId ?: requestId.toString()

        // Update current request ID for cancellation
        currentRequestId = requestId
        log("Set currentRequestId to $requestId", "Provider", 1)

        val result = when (strategy) {
            CompletionStrategy.SIMPLE -> {
                log("Using SIMPLE strategy", "Provider")
                requestSimpleCompletion(context, requestId, metricsCompletionId)
            }

            CompletionStrategy.LEAN -> {
                log("Using LEAN strategy", "Provider")
                requestLeanCompletion(context, requestId, metricsCompletionId)
            }

            CompletionStrategy.METHOD_REWRITE -> {
                log("Using METHOD_REWRITE strategy", "Provider")
                requestMethodRewrite(context, requestId, metricsCompletionId)
            }
        }

        log("requestCompletion result: ${result?.items?.size ?: 0} items", "Provider")
        return result
    }

    /**
     * Original simple completion strategy
     */
    private suspend fun requestSimpleCompletion(
        context: CompletionContext,
        requestId: Int,
        completionId: String
    ): ZestInlineCompletionList? {
        val startTime = System.currentTimeMillis()
        log("=== requestSimpleCompletion started ===", "Simple")

        return try {
            logger.debug("Requesting simple completion for ${context.fileName} at offset ${context.offset}")

            // Get current editor and document text on EDT
            log("Getting editor and document text...", "Simple", 1)
            val editorFuture = java.util.concurrent.CompletableFuture<Pair<Editor?, String>>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    val ed = FileEditorManager.getInstance(project).selectedTextEditor
                    log("Editor found: ${ed != null}", "Simple", 1)
                    if (ed != null) {
                        val text = ed.document.text
                        log("Document text length: ${text.length}", "Simple", 1)
                        editorFuture.complete(Pair(ed, text))
                    } else {
                        editorFuture.complete(Pair(null, ""))
                    }
                } catch (e: Exception) {
                    log("ERROR getting editor: ${e.message}", "Simple")
                    editorFuture.complete(Pair(null, ""))
                }
            }

            val (editor, documentText) = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                log("ERROR waiting for editor: ${e.message}", "Simple")
                Pair(null, "")
            }

            if (editor == null) {
                log("No active editor found", "Simple")
                logger.debug("No active editor found")
                return null
            }

            // Collect simple context (thread-safe)
            log("Collecting context...", "Simple")
            val contextStartTime = System.currentTimeMillis()
            val simpleContext = simpleContextCollector.collectContext(editor, context.offset)
            val contextCollectionTime = System.currentTimeMillis() - contextStartTime
            metricsService.trackContextCollectionTime(completionId, contextCollectionTime)
            log("Context collected in ${contextCollectionTime}ms", "Simple")
            log("  Prefix length: ${simpleContext.prefixCode.length}", "Simple", 1)
            log("  Suffix length: ${simpleContext.suffixCode.length}", "Simple", 1)

            // Build prompt (thread-safe)
            log("Building prompt...", "Simple")
            val promptStartTime = System.currentTimeMillis()
            
            val config = PromptCachingConfig.getInstance(project)
            
            // Use structured prompt if enabled, fallback to old method
            val (systemPrompt, userPrompt) = if (config.enableStructuredPrompts && config.enableForSimpleStrategy) {
                try {
                    val structuredPrompt = simplePromptBuilder.buildStructuredPrompt(simpleContext)
                    log("Using structured prompt - system: ${structuredPrompt.systemPrompt.length} chars, user: ${structuredPrompt.userPrompt.length} chars", "Simple")
                    
                    // Log comparison for analysis
                    if (config.logPromptComparison) {
                        val oldPrompt = simplePromptBuilder.buildCompletionPrompt(simpleContext)
                        PromptMigrationHelper.logPromptStructure("SIMPLE", structuredPrompt.systemPrompt, structuredPrompt.userPrompt, oldPrompt)
                    }
                    
                    // Track metrics
                    cachingMetrics.recordStructuredRequest(structuredPrompt.systemPrompt, structuredPrompt.userPrompt)
                    
                    Pair(structuredPrompt.systemPrompt, structuredPrompt.userPrompt)
                } catch (e: Exception) {
                    log("Error building structured prompt: ${e.message}", "Simple")
                    val prompt = simplePromptBuilder.buildCompletionPrompt(simpleContext)
                    Pair("", prompt)
                }
            } else {
                log("Using legacy prompt builder (structured prompts disabled)", "Simple")
                val prompt = simplePromptBuilder.buildCompletionPrompt(simpleContext)
                cachingMetrics.recordLegacyRequest()
                Pair("", prompt)
            }
            
            val promptBuildTime = System.currentTimeMillis() - promptStartTime
            logger.debug("Prompt built in ${promptBuildTime}ms, user prompt length: ${userPrompt.length}")
            log("Prompt built in ${promptBuildTime}ms, user prompt length: ${userPrompt.length}", "Simple")
            log("User prompt preview: '${userPrompt.take(200)}...'", "Simple", 1)

            // Query LLM with timeout and cancellation support
            log("Querying LLM...", "Simple")
            showDebugBalloon("LLM Query", "Sending request to LLM...", NotificationType.INFORMATION)

            val llmStartTime = System.currentTimeMillis()
            var llmTime = 0L
            var actualModel = "local-model-mini" // Default model
            val responseWithModel = try {
                withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                    val queryParams = LLMService.LLMQueryParams(userPrompt)
                            .withSystemPrompt(systemPrompt)
                            .useLiteCodeModel()
                            .withMaxTokens(MAX_COMPLETION_TOKENS)
                            .withTemperature(0.1)  // Low temperature for more deterministic completions
                            .withStopSequences(getStopSequences())  // Add stop sequences for Qwen FIM

                    // Track the actual model being used
                    actualModel = queryParams.getModel()
                    log("Using model: $actualModel", "Simple")

                    log("LLM params: maxTokens=$MAX_COMPLETION_TOKENS, temp=0.1, hasSystemPrompt=${systemPrompt.isNotEmpty()}, model=$actualModel", "Simple", 1)

                    // Use cancellable request
                    val cancellableRequest = cancellableLLM.createCancellableRequest(requestId) { currentRequestId }
                    val result = cancellableRequest.query(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
                    log("LLM returned: ${result != null}", "Simple")
                    result
                }
            } finally {
                // Always track LLM time, even if cancelled
                llmTime = System.currentTimeMillis() - llmStartTime
                log("LLM query took ${llmTime}ms", "Simple")
                if (llmTime > 0) {
                    metricsService.trackLLMCallTime(completionId, llmTime)
                }
            }
            
            val response = responseWithModel

            if (response == null) {
                log("LLM response is NULL - timeout or cancelled", "Simple")
                logger.debug("Completion request timed out or was cancelled")
                showDebugBalloon("LLM Failed", "No response from LLM", NotificationType.WARNING)
                updateStatusBarText("No completion available")
                return null
            }

            log("LLM response received: '${response.take(100)}...' (${response.length} chars)", "Simple")
            showDebugBalloon("LLM Response", "Got ${response.length} chars", NotificationType.INFORMATION)

            // Parse response with overlap detection (thread-safe, uses captured documentText)
            log("Parsing response...", "Simple")
            val parseStartTime = System.currentTimeMillis()
            val cleanedCompletion = simpleResponseParser.parseResponseWithOverlapDetection(
                response, documentText, context.offset, strategy = CompletionStrategy.SIMPLE
            )
            val parseTime = System.currentTimeMillis() - parseStartTime
            metricsService.trackResponseParsingTime(completionId, parseTime)

            log("Parsed in ${parseTime}ms", "Simple")
            log("Cleaned completion: '${cleanedCompletion}' (${cleanedCompletion.length} chars)", "Simple")

            if (cleanedCompletion.isBlank()) {
                log("WARNING: Cleaned completion is BLANK", "Simple")
                logger.debug("No valid completion after parsing")
                showDebugBalloon("Parse Failed", "No valid completion after parsing", NotificationType.WARNING)
                updateStatusBarText("No completion available")
                return null
            }

            val totalTime = System.currentTimeMillis() - startTime

            // Format the completion text using IntelliJ's code style
            val formattedCompletion = cleanedCompletion
            log("Formatted completion: '${formattedCompletion}'", "Simple", 1)

            // Create completion item with original response stored for re-processing
            val confidence = calculateConfidence(formattedCompletion)
            log("Calculated confidence: $confidence", "Simple", 1)

            val item = ZestInlineCompletionItem(
                insertText = formattedCompletion,
                replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset, end = context.offset
                ),
                confidence = confidence,
                metadata = CompletionMetadata(
                    model = actualModel,  // Use the actual model instead of hardcoded
                    tokens = formattedCompletion.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = response  // Store original response for re-processing overlaps
                )
            )

            val result = ZestInlineCompletionList.single(item)
            log("Created completion list with 1 item", "Simple")

            logger.info("Simple completion completed in ${totalTime}ms (llm=${llmTime}ms) for request $requestId")
            showDebugBalloon(
                "Completion Ready",
                "Got: ${formattedCompletion.take(50)}...",
                NotificationType.INFORMATION
            )

            result

        } catch (e: kotlinx.coroutines.CancellationException) {
            log("Completion CANCELLED", "Simple")
            metricsService.trackCompletionCancelled(completionId)
            logger.debug("Completion request was cancelled after ${System.currentTimeMillis() - startTime}ms")
            throw e
        } catch (e: Exception) {
            log("ERROR in simple completion: ${e.message}", "Simple")
            e.printStackTrace()
            logger.warn("Simple completion failed", e)
            showDebugBalloon("Error", e.message ?: "Unknown error", NotificationType.ERROR)
            null
        }
    }

    /**
     * New lean completion strategy with full file context and reasoning
     */
    private suspend fun requestLeanCompletion(
        context: CompletionContext,
        requestId: Int,
        completionId: String
    ): ZestInlineCompletionList? {
        val startTime = System.currentTimeMillis()

        return try {
            logger.debug("Requesting lean completion for ${context.fileName} at offset ${context.offset}")

            // Get current editor and full document text on EDT
            val editorFuture = java.util.concurrent.CompletableFuture<Pair<Editor?, String>>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    val ed = FileEditorManager.getInstance(project).selectedTextEditor
                    if (ed != null) {
                        editorFuture.complete(Pair(ed, ed.document.text))
                    } else {
                        editorFuture.complete(Pair(null, ""))
                    }

                } catch (e: Exception) {
                    editorFuture.complete(Pair(null, ""))
                }
            }

            val (editor, documentText) = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Pair(null, "")
            }

            if (editor == null) {
                logger.debug("No active editor found")
                return null
            }

            // Use async collection with preemptive analysis
            val leanContextStartTime = System.currentTimeMillis()
            log("Starting lean context collection...", "Lean")
            
            // Collect context with dependency analysis (will use preemptive cache if available)
            val contextDeferred = kotlinx.coroutines.CompletableDeferred<ZestLeanContextCollectorPSI.LeanContext?>()
            
            ApplicationManager.getApplication().invokeLater {
                leanContextCollector.collectWithDependencyAnalysis(editor, context.offset) { ctx ->
                    log("Got context with ${ctx.relatedClassContents.size} related classes", "Lean")
                    log("  Called methods: ${ctx.calledMethods.take(5).joinToString(", ")}", "Lean")
                    log("  Used classes: ${ctx.usedClasses.take(5).joinToString(", ")}", "Lean")
                    if (!contextDeferred.isCompleted) {
                        contextDeferred.complete(ctx)
                    }
                }
            }
            
            // Wait for context with timeout
            val leanContext = try {
                withTimeoutOrNull(2000) {  // 2 seconds max wait
                    contextDeferred.await()
                }
            } catch (e: Exception) {
                log("Exception waiting for context: ${e.message}", "Lean")
                null
            }
            
            if (leanContext == null) {
                log("No context available, returning null", "Lean")
                return null
            }

            // Track context collection time once after collection is complete
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)
            
            log("Lean context collected in ${leanContextTime}ms", "Lean")
            log("  Has related classes: ${leanContext.relatedClassContents.isNotEmpty()}", "Lean")
            log("  Called methods: ${leanContext.calledMethods.size}", "Lean")
            log("  Used classes: ${leanContext.usedClasses.size}", "Lean")
            log("  Preserved methods: ${leanContext.preservedMethods.size}", "Lean")


            // Build reasoning prompt
            val promptStartTime = System.currentTimeMillis()
            
            val config = PromptCachingConfig.getInstance(project)
            
            // Use structured prompt if enabled, fallback to old method
            val (systemPrompt, userPrompt) = if (config.enableStructuredPrompts && config.enableForLeanStrategy) {
                try {
                    val structuredPrompt = leanPromptBuilder.buildStructuredReasoningPrompt(leanContext)
                    logger.debug("Using structured lean prompt - system: ${structuredPrompt.systemPrompt.length} chars, user: ${structuredPrompt.userPrompt.length} chars")
                    
                    // Log comparison for analysis
                    if (config.logPromptComparison) {
//                        val oldPrompt = leanPromptBuilder.buildReasoningPrompt(leanContext)
//                        PromptMigrationHelper.logPromptStructure("LEAN", structuredPrompt.systemPrompt, structuredPrompt.userPrompt, oldPrompt)
                    }
                    
                    // Track metrics
                    cachingMetrics.recordStructuredRequest(structuredPrompt.systemPrompt, structuredPrompt.userPrompt)
                    
                    Pair(structuredPrompt.systemPrompt, structuredPrompt.userPrompt)
                } catch (e: Exception) {
                    logger.debug("Error building structured lean prompt: ${e.message}")
//                    val prompt = leanPromptBuilder.buildReasoningPrompt(leanContext)
                    Pair("", "")

                }
            } else {
                logger.debug("Using legacy lean prompt builder (structured prompts disabled)")
//                val prompt = leanPromptBuilder.buildReasoningPrompt(leanContext)
                cachingMetrics.recordLegacyRequest()
                Pair("", "")
            }
            
            val promptBuildTime = System.currentTimeMillis() - promptStartTime
            logger.debug("Lean prompt built in ${promptBuildTime}ms, user prompt length: ${userPrompt.length}, enhanced: ${leanContext.preservedMethods.isNotEmpty()}")
            
            // Log enhanced context info
            if (leanContext.relatedClassContents.isNotEmpty()) {
                logger.debug("Enhanced context includes ${leanContext.relatedClassContents.size} related classes")
                logger.debug("Called methods: ${leanContext.calledMethods.take(5).joinToString(", ")}")
                logger.debug("Used classes: ${leanContext.usedClasses.take(3).joinToString(", ")}")
            }

            // Query LLM with higher timeout for reasoning and cancellation support
            val llmStartTime = System.currentTimeMillis()
            var llmTime = 0L
            var actualModel = "local-model-mini" // Default model
            val response = try {
                withTimeoutOrNull(LEAN_COMPLETION_TIMEOUT_MS) {
                    val queryParams = LLMService.LLMQueryParams(userPrompt)
                            .withSystemPrompt(systemPrompt)
                            .useLiteCodeModel()  // Use full model for reasoning
                            .withMaxTokens(LEAN_MAX_COMPLETION_TOKENS)  // Limit tokens to control response length
                            .withTemperature(0.5); // Slightly higher for creative reasoning
//                            .withStopSequences(getLeanStopSequences())
                    
                    // Track the actual model being used
                    actualModel = queryParams.getModel()
                    log("Using model for LEAN: $actualModel", "Lean")

                    // Use cancellable request
                    val cancellableRequest = cancellableLLM.createCancellableRequest(requestId) { currentRequestId }
                    cancellableRequest.query(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
                }
            } finally {
                // Always track LLM time, even if cancelled
                llmTime = System.currentTimeMillis() - llmStartTime
                if (llmTime > 0) {
                    metricsService.trackLLMCallTime(completionId, llmTime)
                }
            }


            if (response == null) {
                logger.debug("Lean completion request timed out or was cancelled")
                updateStatusBarText("No completion available")
                return null
            }

            // Parse response with diff-based extraction
            val parseStartTime = System.currentTimeMillis()
            val reasoningResult = leanResponseParser.parseReasoningResponse(
                response, documentText, context.offset
            )
            val parseTime = System.currentTimeMillis() - parseStartTime
            metricsService.trackResponseParsingTime(completionId, parseTime)







            if (reasoningResult.completionText.isBlank()) {
                logger.debug("No valid completion after lean parsing")
                updateStatusBarText("No completion available")
                return null
            }

            val totalTime = System.currentTimeMillis() - startTime

            // Format the completion text using IntelliJ's code style
            val formattedCompletion =
                reasoningResult.completionText // (editor, reasoningResult.completionText, context.offset)

            // Create completion item with reasoning metadata


            val item = ZestInlineCompletionItem(
                insertText = formattedCompletion, replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset, end = context.offset
                ), confidence = reasoningResult.confidence, metadata = CompletionMetadata(
                    model = actualModel,  // Use the actual model
                    tokens = formattedCompletion.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = reasoningResult.reasoning
                )
            )

            logger.info("Lean completion completed in ${totalTime}ms (context=${leanContextTime}ms, prompt=${promptBuildTime}ms, llm=${llmTime}ms, parse=${parseTime}ms) with reasoning: ${reasoningResult.hasValidReasoning} for request $requestId")
            ZestInlineCompletionList.single(item)

        } catch (e: kotlinx.coroutines.CancellationException) {
            metricsService.trackCompletionCancelled(completionId)
            logger.debug("Lean completion request was cancelled after ${System.currentTimeMillis() - startTime}ms")
            throw e
        } catch (e: Exception) {
            logger.warn("Lean completion failed, falling back to simple", e)
            // Fallback to simple strategy
            requestSimpleCompletion(context, requestId, completionId)
        }
    }

    private fun calculateConfidence(completion: String): Float {
        var confidence = 0.7f

        // Increase confidence for longer completions
        if (completion.length > 10) {
            confidence += 0.1f
        }

        // Increase confidence for structured completions
        if (completion.contains('\n') || completion.contains('{') || completion.contains('(')) {
            confidence += 0.1f
        }

        // Decrease confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.2f
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * Get stop sequences for Qwen 2.5 Coder FIM format (simple strategy)
     */
    private fun getStopSequences(): List<String> {
        return listOf(
            "<|fim_suffix|>", "<|fim_prefix|>", "<|fim_pad|>", "<|endoftext|>", "<|repo_name|>", "<|file_sep|>"
        )
    }

    /**
     * Get stop sequences for lean strategy (structured output)
     */
    private fun getLeanStopSequences(): List<String> {
        return listOf(
            "</code>", "</suffix>", "<|endoftext|>", "<|end|>", "# End of file"
        )
    }

    /**
     * Method rewrite strategy - shows floating window with method rewrites
     */
    private fun requestMethodRewrite(
        context: CompletionContext,
        requestId: Int,
        completionId: String
    ): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting method rewrite for ${context.fileName} at offset ${context.offset}")

            val startTime = System.currentTimeMillis()

            // Get current editor on EDT
            val editorFuture = java.util.concurrent.CompletableFuture<Editor?>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    val ed = FileEditorManager.getInstance(project).selectedTextEditor
                    editorFuture.complete(ed)
                } catch (e: Exception) {
                    editorFuture.complete(null)
                }
            }

            val editor = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                null
            }

            if (editor == null) {
                logger.debug("No active editor found for method rewrite")
                return null
            }

            // Track method rewrite as a special completion
            val rewriteStartTime = System.currentTimeMillis()

            // Trigger the method rewrite service (this will show floating window)
            // Make sure to trigger on EDT and don't wait for result
            ApplicationManager.getApplication().invokeLater {
                try {
                    methodRewriteService.rewriteCurrentMethod(editor, context.offset)

                    // Track completion time for method rewrite
                    val rewriteTime = System.currentTimeMillis() - rewriteStartTime
                    metricsService.trackLLMCallTime(completionId, rewriteTime)
                } catch (e: Exception) {
                    logger.warn("Method rewrite failed", e)
                    e.printStackTrace()
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            logger.info("Method rewrite triggered in ${totalTime}ms for request $requestId")

            // Return empty completion list since we're showing inline diff instead
            ZestInlineCompletionList.EMPTY

        } catch (e: kotlinx.coroutines.CancellationException) {

            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            logger.warn("Method rewrite request failed", e)
            null
        }
    }

    companion object {
        private const val COMPLETION_TIMEOUT_MS = 8000L  // 8 seconds for simple
        private const val MAX_COMPLETION_TOKENS = 16  // Small for simple completions

        private const val LEAN_COMPLETION_TIMEOUT_MS = 150000L  // 15 seconds for reasoning
        private const val LEAN_MAX_COMPLETION_TOKENS =
            32  // Limited tokens for focused completions (reasoning + completion)
    }
}
