package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.codehealth.AICodeReviewer
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI
import com.zps.zest.completion.context.FileContextPrePopulationService
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.parser.ZestLeanResponseParser
import com.zps.zest.completion.prompt.ZestLeanPromptBuilder
import com.zps.zest.completion.config.PromptCachingConfig
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.metrics.PromptCachingMetrics
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import com.zps.zest.settings.ZestGlobalSettings
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * Completion provider with multiple strategies
 * - LEAN: Full file context with reasoning prompts
 * - METHOD_REWRITE: Method-level rewrite with floating window preview
 */
class ZestCompletionProvider(private val project: Project) {
    private val logger = Logger.getInstance(ZestCompletionProvider::class.java)

    private val cancellableLLM by lazy { CancellableLLMRequest(project) }

    // Add metrics service
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }
    private val cachingMetrics by lazy { PromptCachingMetrics.getInstance(project) }
    
    // Pre-population service for cache warming
    private val prePopulationService by lazy { project.getService(FileContextPrePopulationService::class.java) }

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
                updateStatusBar(message)
                updateStatusBarWidget(message)
            }
        } catch (e: Exception) {
            log("Status: $message", "Widget")
        }
    }
    
    private fun updateStatusBar(message: String) {
        val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
        statusBar?.info = message
    }
    
    private fun updateStatusBarWidget(message: String) {
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


    // Lean strategy components
    private val leanContextCollector = ZestLeanContextCollectorPSI(project)
    private val leanPromptBuilder = ZestLeanPromptBuilder(project)
    private val leanResponseParser = ZestLeanResponseParser()


    // Configuration
    var strategy: CompletionStrategy = CompletionStrategy.LEAN // Default to LEAN mode
        private set

    enum class CompletionStrategy {
        LEAN  // Full-file with reasoning approach
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

        log("Using LEAN strategy", "Provider")
        val result = requestLeanCompletion(context, requestId, metricsCompletionId)

        log("requestCompletion result: ${result?.items?.size ?: 0} items", "Provider")
        return result
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
            
            val (editor, documentText) = getEditorAndDocument()
            if (editor == null) {
                logger.debug("No active editor found")
                return null
            }

            val (leanContext, leanContextTime) = collectLeanContext(editor, context.offset, completionId)


            val (systemPrompt, userPrompt, promptBuildTime) = buildLeanPrompt(leanContext)
            logEnhancedContext(leanContext)

            val (response, llmTime, actualModel) = queryLeanLLM(
                systemPrompt, userPrompt, requestId, completionId
            )


            if (response == null) {
                logger.debug("Lean completion request timed out or was cancelled")
                updateStatusBarText("No completion available")
                return null
            }
            
            return processLeanResponse(
                response, documentText, context, actualModel,
                startTime, leanContextTime, promptBuildTime, llmTime, requestId, completionId
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            metricsService.trackCompletionCancelled(completionId)
            logger.debug("Lean completion request was cancelled after ${System.currentTimeMillis() - startTime}ms")
            throw e
        } catch (e: Exception) {
            logger.warn("Lean completion failed", e)
            null
        }
    }
    
    /**
     * Get editor and document text on EDT
     */
    private suspend fun getEditorAndDocument(): Pair<Editor?, String> {
        return suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater {
                try {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val text = editor?.document?.text ?: ""
                    continuation.resume(Pair(editor, text))
                } catch (e: Exception) {
                    continuation.resume(Pair(null, ""))
                }
            }
        }
    }
    
    /**
     * Collect lean context with dependency analysis
     */
    private suspend fun collectLeanContext(
        editor: Editor,
        offset: Int,
        completionId: String
    ): Pair<ZestLeanContextCollectorPSI.LeanContext, Long> {
        val startTime = System.currentTimeMillis()
        log("Starting lean context collection...", "Lean")
        
        val leanContext = leanContextCollector.collectWithDependencyAnalysis(editor, offset)
        
        val contextTime = System.currentTimeMillis() - startTime
        metricsService.trackContextCollectionTime(completionId, contextTime)
        
        logContextDetails(leanContext, contextTime)
        return Pair(leanContext, contextTime)
    }
    
    /**
     * Log context collection details
     */
    private fun logContextDetails(
        leanContext: ZestLeanContextCollectorPSI.LeanContext, 
        contextTime: Long
    ) {
        log("Lean context collected in ${contextTime}ms", "Lean")
        log("  Has related classes: ${leanContext.relatedClassContents.isNotEmpty()}", "Lean")
        log("  Called methods: ${leanContext.calledMethods.size}", "Lean")
        log("  Used classes: ${leanContext.usedClasses.size}", "Lean")
        log("  Preserved methods: ${leanContext.preservedMethods.size}", "Lean")
    }
    
    /**
     * Build lean prompt with structured or legacy approach
     */
    private fun buildLeanPrompt(
        leanContext: ZestLeanContextCollectorPSI.LeanContext
    ): Triple<String, String, Long> {
        val startTime = System.currentTimeMillis()
        val config = PromptCachingConfig.getInstance(project)
        
        val (systemPrompt, userPrompt) = if (config.enableStructuredPrompts && config.enableForLeanStrategy) {
            buildStructuredPrompt(leanContext, config)
        } else {
            buildLegacyPrompt()
        }
        
        val buildTime = System.currentTimeMillis() - startTime
        logger.debug("Lean prompt built in ${buildTime}ms, user prompt length: ${userPrompt.length}")
        
        return Triple(systemPrompt, userPrompt, buildTime)
    }
    
    /**
     * Build structured prompt
     */
    private fun buildStructuredPrompt(
        leanContext: ZestLeanContextCollectorPSI.LeanContext,
        config: PromptCachingConfig
    ): Pair<String, String> {
        return try {
            val structuredPrompt = leanPromptBuilder.buildStructuredReasoningPrompt(leanContext)
            logger.debug("Using structured lean prompt")
            
            cachingMetrics.recordStructuredRequest(
                structuredPrompt.systemPrompt,
                structuredPrompt.userPrompt
            )
            
            Pair(structuredPrompt.systemPrompt, structuredPrompt.userPrompt)
        } catch (e: Exception) {
            logger.debug("Error building structured lean prompt: ${e.message}")
            Pair("", "")
        }
    }
    
    /**
     * Build legacy prompt
     */
    private fun buildLegacyPrompt(): Pair<String, String> {
        logger.debug("Using legacy lean prompt builder")
        cachingMetrics.recordLegacyRequest()
        return Pair("", "")
    }
    
    /**
     * Log enhanced context information
     */
    private fun logEnhancedContext(leanContext: ZestLeanContextCollectorPSI.LeanContext) {
        if (leanContext.relatedClassContents.isNotEmpty()) {
            logger.debug("Enhanced context includes ${leanContext.relatedClassContents.size} related classes")
            logger.debug("Called methods: ${leanContext.calledMethods.take(5).joinToString(", ")}")
            logger.debug("Used classes: ${leanContext.usedClasses.take(3).joinToString(", ")}")
        }
    }
    
    /**
     * Query LLM for lean completion
     */
    private suspend fun queryLeanLLM(
        systemPrompt: String,
        userPrompt: String,
        requestId: Int,
        completionId: String
    ): Triple<String?, Long, String> {
        val startTime = System.currentTimeMillis()
        var actualModel = "local-model-mini"
        
        val response = try {
            withTimeoutOrNull(LEAN_COMPLETION_TIMEOUT_MS) {
                val queryParams = createLeanQueryParams(systemPrompt, userPrompt)
                actualModel = queryParams.getModel()
                log("Using model for LEAN: $actualModel", "Lean")
                
                val cancellableRequest = cancellableLLM.createCancellableRequest(requestId) {
                    currentRequestId
                }
                cancellableRequest.query(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
        } finally {
            val llmTime = System.currentTimeMillis() - startTime
            if (llmTime > 0) {
                metricsService.trackLLMCallTime(completionId, llmTime)
            }
        }
        
        val llmTime = System.currentTimeMillis() - startTime
        return Triple(response, llmTime, actualModel)
    }
    
    /**
     * Create query parameters for lean completion
     */
    private fun createLeanQueryParams(
        systemPrompt: String,
        userPrompt: String
    ): NaiveLLMService.LLMQueryParams {
        return NaiveLLMService.LLMQueryParams(userPrompt)
            .withSystemPrompt(systemPrompt)
            .useLiteCodeModel()
            .withMaxTokens(LEAN_MAX_COMPLETION_TOKENS)
            .withTemperature(0.5)
            .withStopSequences(getLeanStopSequences())
    }
    
    /**
     * Process lean completion response
     */
    private fun processLeanResponse(
        response: String,
        documentText: String,
        context: CompletionContext,
        actualModel: String,
        startTime: Long,
        leanContextTime: Long,
        promptBuildTime: Long,
        llmTime: Long,
        requestId: Int,
        completionId: String
    ): ZestInlineCompletionList? {
        val parseStartTime = System.currentTimeMillis()
        var reasoningResult = leanResponseParser.parseReasoningResponse(
            response, documentText, context.offset
        )
        val parseTime = System.currentTimeMillis() - parseStartTime
        metricsService.trackResponseParsingTime(completionId, parseTime)

        if (reasoningResult.completionText.isBlank()) {
            logger.debug("No valid completion after lean parsing")
            updateStatusBarText("No completion available")
            return null
        }

        // AI Self-Review for Code Quality
        var wasReviewed = false
        var wasImproved = false
        val settings = ZestGlobalSettings.getInstance()

        if (settings.aiSelfReviewEnabled && reasoningResult.completionText.length > 20) {
            try {
                val reviewer = AICodeReviewer.getInstance(project)
                val review = reviewer.reviewCode(reasoningResult.completionText)

                wasReviewed = true
                val linesOfCode = reasoningResult.completionText.lines().size

                // If review failed, try to improve
                if (!review.isPassed()) {
                    logger.info("Code review failed (score: ${review.getStyleComplianceScore()}), attempting improvement")
                    val improved = reviewer.improveCode(reasoningResult.completionText, review.getFeedback())

                    if (improved != null && improved.isNotBlank()) {
                        reasoningResult = reasoningResult.copy(completionText = improved)
                        wasImproved = true
                        logger.info("Code improved after review")
                    }
                }

                // Track code quality metrics
                metricsService.trackCodeQuality(
                    completionId = completionId,
                    linesOfCode = linesOfCode,
                    styleComplianceScore = review.getStyleComplianceScore(),
                    selfReviewPassed = review.isPassed(),
                    compilationErrors = 0,  // TODO: Add compilation check
                    logicBugsDetected = 0,  // TODO: Add bug detection
                    wasReviewed = wasReviewed,
                    wasImproved = wasImproved
                )

                logger.debug("Code quality tracked: score=${review.getStyleComplianceScore()}, passed=${review.isPassed()}, improved=$wasImproved")
            } catch (e: Exception) {
                logger.warn("Error during AI code review", e)
            }
        }

        return createLeanCompletionItem(
            reasoningResult, context, actualModel,
            startTime, leanContextTime, promptBuildTime, llmTime, parseTime, requestId
        )
    }
    
    /**
     * Create completion item from reasoning result
     */
    private fun createLeanCompletionItem(
        reasoningResult: ZestLeanResponseParser.LeanReasoningResult,
        context: CompletionContext,
        actualModel: String,
        startTime: Long,
        leanContextTime: Long,
        promptBuildTime: Long,
        llmTime: Long,
        parseTime: Long,
        requestId: Int
    ): ZestInlineCompletionList {
        val totalTime = System.currentTimeMillis() - startTime
        val formattedCompletion = reasoningResult.completionText
        
        val item = ZestInlineCompletionItem(
            insertText = formattedCompletion,
            replaceRange = ZestInlineCompletionItem.Range(
                start = context.offset,
                end = context.offset
            ),
            confidence = reasoningResult.confidence,
            metadata = CompletionMetadata(
                model = actualModel,
                tokens = formattedCompletion.split("\\s+".toRegex()).size,
                latency = totalTime,
                requestId = UUID.randomUUID().toString(),
                reasoning = reasoningResult.reasoning
            )
        )
        
        logger.info("Lean completion completed in ${totalTime}ms (context=${leanContextTime}ms, prompt=${promptBuildTime}ms, llm=${llmTime}ms, parse=${parseTime}ms) with reasoning: ${reasoningResult.hasValidReasoning} for request $requestId")
        return ZestInlineCompletionList.single(item)
    }


    /**
     * Get stop sequences for lean strategy (structured output)
     */
    private fun getLeanStopSequences(): List<String> {
        return listOf(
            "</code>", "</suffix>", "<|endoftext|>", "<|end|>", "# End of file"
        )
    }


    companion object {

        private const val LEAN_COMPLETION_TIMEOUT_MS = 150000L  // 15 seconds for reasoning
        private const val LEAN_MAX_COMPLETION_TOKENS =
            32  // Limited tokens for focused completions (reasoning + completion)
    }
}
