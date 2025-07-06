package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.completion.context.ZestSimpleContextCollector
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.parser.ZestLeanResponseParser
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.completion.prompt.ZestLeanPromptBuilder
import com.zps.zest.completion.prompt.ZestSimplePromptBuilder
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
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

    private val llmService by lazy {
        try {
            LLMService(project)
        } catch (e: Exception) {
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }

    private val cancellableLLM by lazy { CancellableLLMRequest(project) }
    
    // Add metrics service
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }

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
    private val leanContextCollector = ZestLeanContextCollector(project)
    private val leanPromptBuilder = ZestLeanPromptBuilder()
    private val leanResponseParser = ZestLeanResponseParser()

    // Method rewrite strategy components
    private val methodRewriteService by lazy {
        project.getService(ZestMethodRewriteService::class.java)
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

    suspend fun requestCompletion(context: CompletionContext, requestId: Int, completionId: String? = null): ZestInlineCompletionList? {
        // Use the provided completionId for metrics tracking
        val metricsCompletionId = completionId ?: requestId.toString()

        // Update current request ID for cancellation
        currentRequestId = requestId

        return when (strategy) {
            CompletionStrategy.SIMPLE -> requestSimpleCompletion(context, requestId, metricsCompletionId)
            CompletionStrategy.LEAN -> requestLeanCompletion(context, requestId, metricsCompletionId)
            CompletionStrategy.METHOD_REWRITE -> requestMethodRewrite(context, requestId, metricsCompletionId)
        }
    }

    /**
     * Original simple completion strategy
     */
    private suspend fun requestSimpleCompletion(context: CompletionContext, requestId: Int, completionId: String): ZestInlineCompletionList? {


        return try {
            logger.debug("Requesting simple completion for ${context.fileName} at offset ${context.offset}")

            val startTime = System.currentTimeMillis()

            // Get current editor and document text on EDT

            val editorFuture = java.util.concurrent.CompletableFuture<Pair<Editor?, String>>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    val ed = FileEditorManager.getInstance(project).selectedTextEditor

                    if (ed != null) {
                        val text = ed.document.text

                        editorFuture.complete(Pair(ed, text))
                    } else {
                        editorFuture.complete(Pair(null, ""))
                    }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)    } catch (e: Exception) {
                    editorFuture.complete(Pair(null, ""))
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}

            val (editor, documentText) = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Pair(null, "")
            }

            if (editor == null) {

                logger.debug("No active editor found")
                return null
            }

            // Collect simple context (thread-safe)
            val contextStartTime = System.currentTimeMillis()
            val simpleContext = simpleContextCollector.collectContext(editor, context.offset)
            val contextCollectionTime = System.currentTimeMillis() - contextStartTime
            metricsService.trackContextCollectionTime(completionId, contextCollectionTime)


            // Build prompt (thread-safe)

            val prompt = simplePromptBuilder.buildCompletionPrompt(simpleContext)



            logger.debug("Prompt built, length: ${prompt.length}")

            // Query LLM with timeout and cancellation support

            val llmStartTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                val queryParams =
                    LLMService.LLMQueryParams(prompt).useLiteCodeModel().withMaxTokens(MAX_COMPLETION_TOKENS)
                        .withTemperature(0.1)  // Low temperature for more deterministic completions
                        .withStopSequences(getStopSequences())  // Add stop sequences for Qwen FIM

                // Use cancellable request
                val cancellableRequest = cancellableLLM.createCancellableRequest(requestId) { currentRequestId }
                cancellableRequest.query(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)val llmTime = System.currentTimeMillis() - llmStartTime
            metricsService.trackLLMCallTime(completionId, llmTime)


            if (response == null) {

                logger.debug("Completion request timed out")
                return null
            }

            // Parse response with overlap detection (thread-safe, uses captured documentText)
            val parseStartTime = System.currentTimeMillis()
            val cleanedCompletion = simpleResponseParser.parseResponseWithOverlapDetection(
                response, documentText, context.offset, strategy = CompletionStrategy.SIMPLE
            )
            val parseTime = System.currentTimeMillis() - parseStartTime
            metricsService.trackResponseParsingTime(completionId, parseTime)





            if (cleanedCompletion.isBlank()) {

                logger.debug("No valid completion after parsing")
                return null
            }

            val totalTime = System.currentTimeMillis() - startTime

            // Format the completion text using IntelliJ's code style

            val formattedCompletion =
                cleanedCompletion //formatCompletionText(editor, cleanedCompletion, context.offset)


            // Create completion item with original response stored for re-processing
            val confidence = calculateConfidence(formattedCompletion)


            val item = ZestInlineCompletionItem(
                insertText = formattedCompletion, replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset, end = context.offset
                ), confidence = confidence, metadata = CompletionMetadata(
                    model = "zest-llm-simple",
                    tokens = formattedCompletion.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = response  // Store original response for re-processing overlaps
                )
            )


            val result = ZestInlineCompletionList.single(item)


            logger.info("Simple completion completed in ${totalTime}ms (llm=${llmTime}ms) for request $requestId")
            result

        } catch (e: kotlinx.coroutines.CancellationException) {
            metricsService.trackCompletionCancelled(completionId)
            logger.debug("Completion request was cancelled")
            throw e
        } catch (e: Exception) {

            e.printStackTrace()
            logger.warn("Simple completion failed", e)
            null
        }
    }

    /**
     * New lean completion strategy with full file context and reasoning
     */
    private suspend fun requestLeanCompletion(context: CompletionContext, requestId: Int, completionId: String): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting lean completion for ${context.fileName} at offset ${context.offset}")

            val startTime = System.currentTimeMillis()

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
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)    } catch (e: Exception) {
                    editorFuture.complete(Pair(null, ""))
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}

            val (editor, documentText) = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Pair(null, "")
            }

            if (editor == null) {
                logger.debug("No active editor found")
                return null
            }

            // Use async collection with timeout
            val leanContextStartTime = System.currentTimeMillis()
            var enhancedContext: ZestLeanContextCollector.LeanContext? = null
            val collectionComplete = kotlinx.coroutines.CompletableDeferred<Boolean>()

            // Start async collection - this handles EDT properly internally
            ApplicationManager.getApplication().invokeLater {
                leanContextCollector.collectWithDependencyAnalysis(editor, context.offset) { context ->
                    enhancedContext = context
                    if (!collectionComplete.isCompleted) {
                        collectionComplete.complete(true)
                    }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)    }
            }

            // Wait for initial context or timeout
            withTimeoutOrNull(10) {
                collectionComplete.await()
            }

            // Use whatever context we have - get immediate context if async failed
            val leanContext = if (enhancedContext != null) {
                enhancedContext!!
            } else {
                val contextFuture = java.util.concurrent.CompletableFuture<ZestLeanContextCollector.LeanContext>()
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val ctx = leanContextCollector.collectFullFileContext(editor, context.offset)
                        contextFuture.complete(ctx)
                    } catch (e: Exception) {
                        contextFuture.completeExceptionally(e)
                    }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)    }
                try {
                    contextFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    logger.warn("Failed to collect lean context", e)
                    return null
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}
            


            // Build reasoning prompt
            val prompt = leanPromptBuilder.buildReasoningPrompt(leanContext)
            logger.debug("Lean prompt built, length: ${prompt.length}, enhanced: ${leanContext.preservedMethods.isNotEmpty()}")

            // Query LLM with higher timeout for reasoning and cancellation support
            val llmStartTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(LEAN_COMPLETION_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt).useLiteCodeModel()  // Use full model for reasoning
                    .withMaxTokens(LEAN_MAX_COMPLETION_TOKENS)  // Limit tokens to control response length
                    .withTemperature(0.5)  // Slightly higher for creative reasoning
                    .withStopSequences(getLeanStopSequences())

                // Use cancellable request
                val cancellableRequest = cancellableLLM.createCancellableRequest(requestId) { currentRequestId }
                cancellableRequest.query(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)val llmTime = System.currentTimeMillis() - llmStartTime
            metricsService.trackLLMCallTime(completionId, llmTime)

            if (response == null) {
                logger.debug("Lean completion request timed out")
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
                    model = "zest-llm-lean",
                    tokens = formattedCompletion.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = reasoningResult.reasoning
                )
            )

            logger.info("Lean completion completed in ${totalTime}ms (llm=${llmTime}ms) with reasoning: ${reasoningResult.hasValidReasoning} for request $requestId")
            ZestInlineCompletionList.single(item)

        } catch (e: kotlinx.coroutines.CancellationException) {
            metricsService.trackCompletionCancelled(completionId)
            logger.debug("Lean completion request was cancelled")
            throw e
        } catch (e: Exception) {
            logger.warn("Lean completion failed, falling back to simple", e)
            // Fallback to simple strategy
            requestSimpleCompletion(context, requestId, completionId)
        }
    }

    private suspend fun getCurrentEditor(): Editor? {
        return try {
            val editorFuture = java.util.concurrent.CompletableFuture<Editor?>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    editorFuture.complete(FileEditorManager.getInstance(project).selectedTextEditor)
                } catch (e: Exception) {
                    editorFuture.complete(null)
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}
            
            editorFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to get current editor", e)
            null
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
     * Format the completion text using IntelliJ's code style settings
     * This ensures the inserted code follows the project's formatting rules
     */
    private fun formatCompletionText(editor: Editor, completionText: String, offset: Int): String {
        return try {
            // If completion is very short or doesn't contain code structure, return as-is
            if (completionText.length < 5 || !containsCodeStructure(completionText)) {
                return completionText
            }

            // Get the PsiFile for the current editor
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile == null) {
                logger.debug("Cannot format completion text: PsiFile is null")
                return completionText
            }

            // Create a temporary document with the completion text in context
            val documentText = editor.document.text
            val beforeText = documentText.substring(0, offset)
            val afterText = documentText.substring(offset)
            val tempText = beforeText + completionText + afterText

            // Format the region containing the completion
            var formattedText = completionText
            ApplicationManager.getApplication().runReadAction {
                try {
                    // Create a copy of the PSI file with the new text
                    val tempPsiFile = psiFile.copy() as PsiFile
                    val tempDocument = PsiDocumentManager.getInstance(project).getDocument(tempPsiFile)

                    if (tempDocument != null) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            tempDocument.setText(tempText)
                            PsiDocumentManager.getInstance(project).commitDocument(tempDocument)

                            // Format the range containing the completion
                            val codeStyleManager = CodeStyleManager.getInstance(project)
                            codeStyleManager.reformatRange(tempPsiFile, offset, offset + completionText.length)

                            // Extract the formatted completion text
                            formattedText = tempDocument.text.substring(offset, offset + completionText.length)
                        }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)        }
                } catch (e: Exception) {
                    logger.debug("Failed to format completion text in temporary file: ${e.message}")
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}

            formattedText
        } catch (e: Exception) {
            logger.warn("Failed to format completion text: ${e.message}")
            completionText
        }
    }

    /**
     * Check if the completion text contains code structure that should be formatted
     */
    private fun containsCodeStructure(text: String): Boolean {
        return text.contains('{') || text.contains('}') || text.contains('(') || text.contains(')') || text.contains(
            '\n'
        ) || text.contains(';') || text.contains('=') || text.contains("->") || text.contains("fun ") || text.contains("val ") || text.contains(
            "var "
        ) || text.contains("class ") || text.contains("if ") || text.contains("when ") || text.contains("for ")
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
    private suspend fun requestMethodRewrite(context: CompletionContext, requestId: Int, completionId: String): ZestInlineCompletionList? {


        return try {
            logger.debug("Requesting method rewrite for ${context.fileName} at offset ${context.offset}")

            // Get current editor on EDT

            val editorFuture = java.util.concurrent.CompletableFuture<Editor?>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    val ed = FileEditorManager.getInstance(project).selectedTextEditor

                    editorFuture.complete(ed)
                } catch (e: Exception) {
                    editorFuture.complete(null)
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}
            


            val editor = try {
                editorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {

                null
            }

            if (editor == null) {

                logger.debug("No active editor found for method rewrite")
                return null
            }

            // Trigger the method rewrite service (this will show floating window)


            // Make sure to trigger on EDT and don't wait for result
            ApplicationManager.getApplication().invokeLater {

                try {
                    methodRewriteService.rewriteCurrentMethod(editor, context.offset)

                } catch (e: Exception) {

                    e.printStackTrace()
                }
            
            val leanContextTime = System.currentTimeMillis() - leanContextStartTime
            metricsService.trackContextCollectionTime(completionId, leanContextTime)}

            // Return empty completion list since we're showing inline diff instead
            ZestInlineCompletionList.EMPTY

        } catch (e: Exception) {

            e.printStackTrace()
            logger.warn("Method rewrite request failed", e)
            null
        }
    }

    companion object {
        private const val COMPLETION_TIMEOUT_MS = 8000L  // 8 seconds for simple
        private const val MAX_COMPLETION_TOKENS = 16  // Small for simple completions

        private const val LEAN_COMPLETION_TIMEOUT_MS = 15000L  // 15 seconds for reasoning
        private const val LEAN_MAX_COMPLETION_TOKENS =
            128  // Limited tokens for focused completions (reasoning + completion)
    }
}
