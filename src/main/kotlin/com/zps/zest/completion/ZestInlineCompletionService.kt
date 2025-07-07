package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.messages.Topic
import com.zps.zest.ConfigurationManager
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simplified service for handling inline completions
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
    private val messageBusConnection = project.messageBus.connect()
    private val editorManager = FileEditorManager.getInstance(project)
    
    // Debug logging flag
    private var debugLoggingEnabled = true
    
    /**
     * Internal debug logging function
     * @param message The message to log
     * @param tag Optional tag for categorizing logs (default: "ZestService")
     */
    private fun log(message: String, tag: String = "ZestService") {
        if (debugLoggingEnabled) {
            println("[$tag] $message")
        }
    }
    
    /**
     * Enable or disable debug logging
     */
    fun setDebugLogging(enabled: Boolean) {
        debugLoggingEnabled = enabled
        log("Debug logging ${if (enabled) "enabled" else "disabled"}")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val completionProvider = ZestCompletionProvider(project)
    private val renderer = ZestInlineCompletionRenderer()
    private val responseParser = ZestSimpleResponseParser() // Add response parser for real-time processing

    // Method rewrite service integration
    private val methodRewriteService by lazy { project.getService(ZestMethodRewriteService::class.java) }

    // Metrics service integration
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }

    // Configuration
    private var autoTriggerEnabled = false
    private var inlineCompletionEnabled = false
    private var backgroundContextEnabled = false
    private var continuousCompletionEnabled = true // NEW: Auto-trigger next completion after acceptance

    // Configuration manager reference
    private val configManager = ConfigurationManager.getInstance(project)

    // Request tracking to prevent multiple concurrent requests
    private val requestGeneration = AtomicInteger(0)
    private var activeRequestId: Int? = null

    // Request state machine
    enum class RequestState {
        IDLE,           // No request active
        WAITING,        // Timer active, will request soon
        REQUESTING,     // Provider call in progress
        DISPLAYING      // Completion shown
    }

    @Volatile
    private var currentRequestState = RequestState.IDLE

    // Track the last request time for rate limiting
    @Volatile
    private var lastRequestTimestamp = 0L
    private val MIN_REQUEST_INTERVAL_MS = 500L // Minimum 500ms between requests

    // Dynamic rate limiting (thread-safe)
    private val requestHistory = Collections.synchronizedList(mutableListOf<Long>())
    private val REQUEST_HISTORY_WINDOW_MS = 60_000L // 1 minute window
    private val MAX_REQUESTS_PER_MINUTE = 30

    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()

        // Clean up old entries (thread-safe)
        synchronized(requestHistory) {
            requestHistory.removeAll { now - it > REQUEST_HISTORY_WINDOW_MS }

            // Check if we've exceeded the limit
            if (requestHistory.size >= MAX_REQUESTS_PER_MINUTE) {

                return true
            }
        }

        // Check minimum interval
        val timeSinceLastRequest = now - lastRequestTimestamp
        return timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS
    }

    private fun recordRequest() {
        val now = System.currentTimeMillis()
        lastRequestTimestamp = now
        synchronized(requestHistory) {
            requestHistory.add(now)
        }
    }

    // Mutex for critical sections
    private val completionMutex = Mutex()

    // Single debounce timer
    private var completionTimer: Job? = null

    // Strategy management
    // SIMPLE: Traditional behavior - Tab accepts full completion
    // LEAN: Tab accepts full completion, Ctrl+Tab accepts line-by-line
    // METHOD_REWRITE: Special mode for method rewriting
    fun setCompletionStrategy(strategy: ZestCompletionProvider.CompletionStrategy) {
        val oldStrategy = completionProvider.strategy
        completionProvider.setStrategy(strategy)

        logger.info("Completion strategy updated to: $strategy")

        // Clear cache when strategy changes to ensure appropriate completions
        if (oldStrategy != strategy) {
            scope.launch {
                cacheMutex.withLock {
                    completionCache.clear()
                }
            }

        }
    }

    fun getCompletionStrategy(): ZestCompletionProvider.CompletionStrategy {
        return completionProvider.strategy
    }

    // State management
    private var currentCompletionJob: Job? = null
    private var currentContext: CompletionContext? = null
    private var currentCompletion: ZestInlineCompletionItem? = null

    // Flag to track programmatic edits (e.g., when accepting completions)
    @Volatile
    private var isProgrammaticEdit = false

    // Timestamp of last accepted completion to implement cooldown
    @Volatile
    private var lastAcceptedTimestamp = 0L
    private val ACCEPTANCE_COOLDOWN_MS = 3000L // 3 seconds cooldown after accepting (increased from 2s)

    // Track the last accepted text to avoid re-suggesting the same completion
    @Volatile
    private var lastAcceptedText: String? = null

    // Flag to completely disable all completion activities during acceptance
    @Volatile
    private var isAcceptingCompletion = false

    // Acceptance timeout protection - auto-reset if acceptance takes too long
    private val ACCEPTANCE_TIMEOUT_MS = 10000L // 10 seconds max acceptance time
    private var acceptanceTimeoutJob: Job? = null

    // Completion cache for simple & lean mode
    private data class CachedCompletion(
        val fullCompletion: ZestInlineCompletionItem,
        val firstLineCompletion: ZestInlineCompletionItem,
        val contextHash: String,
        val completionId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(maxAgeMs: Long = CACHE_EXPIRY_MS): Boolean {
            return System.currentTimeMillis() - timestamp > maxAgeMs
        }
    }

    private val completionCache = ConcurrentHashMap<String, CachedCompletion>()
    private val cacheMutex = Mutex()

    init {

        logger.info("Initializing simplified ZestInlineCompletionService")

        // Load configuration settings from ConfigurationManager
        loadConfiguration()

        setupEventListeners()

        // Log initial strategy


    }

    /**
     * Load configuration from ConfigurationManager
     */
    private fun loadConfiguration() {
        inlineCompletionEnabled = configManager.isInlineCompletionEnabled
        autoTriggerEnabled = configManager.isAutoTriggerEnabled
        backgroundContextEnabled = configManager.isBackgroundContextEnabled
        continuousCompletionEnabled = configManager.isContinuousCompletionEnabled







        logger.info("Loaded configuration: inlineCompletion=$inlineCompletionEnabled, autoTrigger=$autoTriggerEnabled, backgroundContext=$backgroundContextEnabled, continuousCompletion=$continuousCompletionEnabled")
    }

    /**
     * Update configuration from ConfigurationManager
     * Call this when settings change
     */
    fun updateConfiguration() {

        loadConfiguration()

        // If inline completion is disabled, clear any current completion
        if (!inlineCompletionEnabled) {

            clearCurrentCompletion()
        }

        // Clear cache when configuration changes to ensure fresh results
        clearCache()
    }

    /**
     * Request inline completion at the specified position
     */
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false) {


        // Cancel any pending timer FIRST
        completionTimer?.let {

            it.cancel()
            completionTimer = null
        }

        // Check rate limiting (unless manually triggered)
        if (!manually && isRateLimited()) {

            return
        }

        // Check if project is ready
        if (project.isDisposed || !project.isInitialized) {

            logger.debug("Project not ready, ignoring completion request")
            return
        }

        // Check if editor is still valid
        if (editor.isDisposed) {

            return
        }

        // Block all completion requests during acceptance
        if (isAcceptingCompletion) {

            return
        }

        // Check cooldown period unless manually triggered
        if (!manually) {
            val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {

                return
            }
        }

        scope.launch {
            val requestId = requestGeneration.incrementAndGet()


            logger.debug("Requesting completion at offset $offset, manually=$manually, requestId=$requestId")

            // Check if inline completion is enabled at all
            if (!inlineCompletionEnabled && !manually) {

                logger.debug("Inline completion is disabled, ignoring request")
                return@launch
            }

            // Cancel any existing request BEFORE acquiring mutex
            if (currentRequestState == RequestState.REQUESTING && activeRequestId != null) {

                completionProvider.setCurrentRequestId(null) // Signal cancellation to provider
            }

            currentCompletionJob?.let {

                it.cancel()
                currentCompletionJob = null
            }

            // Use mutex to ensure only one request is processed at a time

            completionMutex.withLock {


                // Check if this is still the latest request
                if (requestId < (activeRequestId ?: 0)) {

                    logger.debug("Request $requestId is outdated, skipping")
                    return@withLock
                }

                activeRequestId = requestId


                // Clear any existing completion request (moved after state update)

                clearCurrentCompletion()

                // Check if auto-trigger is disabled and this is not manual
                if (!autoTriggerEnabled && !manually) {

                    logger.debug("Auto-trigger disabled, ignoring automatic request")
                    activeRequestId = null
                    return@withLock
                }

                // NOW we're actually going to request - update state
                currentRequestState = RequestState.REQUESTING
                recordRequest() // Record this request for rate limiting

                // Notify listeners that we're loading

                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)

                // Start completion request
                currentCompletionJob = scope.launch {


                    // Generate a new completion ID for metrics tracking
                    val completionId = UUID.randomUUID().toString()

                    try {

                        val context = buildCompletionContext(editor, offset, manually)
                        if (context == null) {

                            logger.debug("Failed to build completion context")
                            return@launch
                        }


                        // Check if this request is still active
                        if (activeRequestId != requestId) {

                            logger.debug("Request $requestId is no longer active")
                            return@launch
                        }

                        currentContext = context


                        // Track completion requested metric
                        val fileType = context.language
                        FileDocumentManager.getInstance().getFile(editor.document)
                        val contextInfo = mapOf(
                            "manually_triggered" to manually,
                            "offset" to context.offset,
                            "file_name" to context.fileName,
                            "prefix_length" to context.prefixCode.length,
                            "suffix_length" to context.suffixCode.length
                        )

                        // Check cache first for SIMPLE and LEAN strategies
                        if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE || completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {


                            val cached = getCachedCompletion(context, editor)
                            if (cached != null) {


                                // For both SIMPLE and LEAN, use full completion (they have different Tab behaviors)
                                val completionToShow = cached.fullCompletion

                                // Store the FULL completion for acceptance
                                currentCompletion = cached.fullCompletion

                                // Track completion requested with the cached completion's ID
                                metricsService.trackCompletionRequested(
                                    completionId = cached.fullCompletion.completionId,
                                    strategy = completionProvider.strategy.name,
                                    fileType = fileType,
                                    contextInfo = contextInfo + mapOf("from_cache" to true)
                                )

                                // Create a synthetic completion list for display
                                val displayCompletion = ZestInlineCompletionList.single(completionToShow)
                                handleCompletionResponse(editor, context, displayCompletion, requestId)
                                return@launch
                            }
                        }

                        // Only track new request if not from cache
                        metricsService.trackCompletionRequested(
                            completionId = completionId,
                            strategy = completionProvider.strategy.name,
                            fileType = fileType,
                            contextInfo = contextInfo + mapOf("from_cache" to false)
                        )

                        // Use background context if enabled
                        if (backgroundContextEnabled) {

                            logger.debug("Background context is enabled, including additional context")
                            // TODO: Implement background context gathering here
                        }

//                        // Tell the provider about the new request ID for cancellation
                        completionProvider.setCurrentRequestId(requestId)


                        val startTime = System.currentTimeMillis()
                        val completions = completionProvider.requestCompletion(context, requestId, completionId)
                        val elapsed = System.currentTimeMillis() - startTime


                        var insertText = ""
                        completions?.items?.firstOrNull()?.let {
                            insertText = it.insertText


                        }

                        // Track completion completed (response received)
                        metricsService.trackCompletionCompleted(
                            completionId = completionId, completionContent = insertText, responseTime = elapsed
                        )

                        // Check again if this request is still active
                        if (activeRequestId != requestId) {

                            logger.debug("Request $requestId is no longer active after completion")
                            return@launch
                        }

                        if (currentContext == context) { // Ensure request is still valid


                            // Update the completion with our tracking ID
                            val completionsWithId = completions?.let { list ->
                                val items = list.items.map { item ->
                                    item.copy(completionId = completionId)
                                }
                                ZestInlineCompletionList(list.isIncomplete, items)
                            }


                            // Cache the completion if it's not empty and for cacheable strategies
                            if (completionsWithId != null && !completionsWithId.isEmpty() && (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE || completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN)) {

                                val firstCompletion = completionsWithId.firstItem()!!


                                cacheCompletion(context, editor, firstCompletion)

                                // For SIMPLE strategy, show full completion (line-by-line acceptance handled in accept() method)
                                val displayCompletion = completionsWithId // Show full completion for all strategies

                                // Store the FULL completion for acceptance
                                currentCompletion = firstCompletion


                                handleCompletionResponse(editor, context, displayCompletion, requestId)
                            } else {

                                // Non-cacheable strategy or empty completion
                                handleCompletionResponse(editor, context, completionsWithId, requestId)
                            }
                        } else {


                        }
                    } catch (e: CancellationException) {

                        logger.debug("Completion request cancelled")

                        // IMPORTANT: Clear activeRequestId if this was the cancelled request
                        if (activeRequestId == requestId) {

                            activeRequestId = null
                            currentRequestState = RequestState.IDLE
                        }

                        throw e
                    } catch (e: Exception) {

                        e.printStackTrace()
                        logger.warn("Completion request failed", e)
                    } finally {


                        // For METHOD_REWRITE, keep loading state and activeRequestId active longer
                        if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                            // Normal completion - clear immediately

                            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)

                            // Update state based on outcome
                            if (activeRequestId == requestId) {
                                when {
                                    currentCompletion != null -> {
                                        // Completion will be displayed, activeRequestId cleared in handleCompletionResponse
                                        currentRequestState = RequestState.DISPLAYING
                                    }

                                    else -> {
                                        // No completion, clear everything
                                        currentRequestState = RequestState.IDLE
                                        activeRequestId = null

                                    }
                                }
                            }
                        } else {
                            // METHOD_REWRITE - keep active for longer to allow method rewrite to complete


                            // Clear loading state after a delay
                            scope.launch {
                                delay(5000) // 5 seconds should be enough for method rewrite to show UI
                                if (activeRequestId == requestId) {

                                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                                    currentRequestState = RequestState.IDLE
                                }
                            }

                            // Clear activeRequestId after a shorter delay
                            scope.launch {
                                delay(2000) // 2 seconds for method rewrite service to start
                                if (activeRequestId == requestId) {

                                    activeRequestId = null
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * Accept the current completion
     * Tab (FULL_COMPLETION): Always accepts full completion
     * Ctrl+Tab (CTRL_TAB_COMPLETION): Line-by-line in LEAN mode, full in other modes
     */
    fun accept(editor: Editor, offset: Int?, type: AcceptType) {


        // Prevent multiple simultaneous accepts
        if (isAcceptingCompletion) {

            return
        }

        val context = currentContext ?: return
        val completion = currentCompletion ?: return
        val actualOffset = offset ?: context.offset

        if (actualOffset != context.offset) {

            logger.debug("Invalid position for acceptance")
            return
        }

        // Handle line-by-line acceptance for Ctrl+Tab in LEAN strategy
        if (type == AcceptType.CTRL_TAB_COMPLETION && completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {

            val lines = completion.insertText.lines()



            lines.forEachIndexed { index, line ->

            }

            if (lines.size > 1) {


                // Accept only the first line
                var firstLine = lines[0]
                val remainingLines = lines.drop(1).filter { it.isNotBlank() } // Filter out empty lines


                // Ensure first line ends with newline if there are remaining lines
                if (remainingLines.isNotEmpty() && !firstLine.endsWith("\n")) {
                    firstLine = "$firstLine\n"

                }

                if (firstLine.isNotEmpty()) {
                    // Set accepting flag
                    isAcceptingCompletion = true

                    // Start acceptance timeout guard
                    startAcceptanceTimeoutGuard()
                    // Track completion accepted metric
                    completion.completionId.let { completionId ->
                        val acceptType = AcceptType.CTRL_TAB_COMPLETION // Line-by-line acceptance
                        lines.take(1).size // First line = 1
                        completion.insertText.lines().size

                        metricsService.trackCompletionAccepted(
                            completionId = completionId,
                            completionContent = firstLine,
                            acceptType = acceptType.name,
                            isAll = remainingLines.isEmpty(),
                            userAction = "ctrl_tab" // Updated to reflect Ctrl+Tab
                        )
                    }
                    // Clear current completion before inserting
                    clearCurrentCompletion(remainingLines.isEmpty())

                    ApplicationManager.getApplication().invokeLater {
                        // Insert the first line
                        acceptCompletionText(
                            editor, context, completion, firstLine, AcceptType.CTRL_TAB_COMPLETION, "ctrl_tab"
                        )

                        // After insertion, show remaining lines if any
                        if (remainingLines.isNotEmpty()) {
                            // Calculate new offset after insertion
                            val newOffset = actualOffset + firstLine.length

                            // Create completion for remaining lines
                            val remainingText = remainingLines.joinToString("\n")

                            showRemainingLines(editor, newOffset, remainingText, completion)

                            // Reset accepting flag immediately for line-by-line acceptance
                            // since the remaining lines are now displayed as a new completion
                            scope.launch {
                                delay(300) // Small delay to ensure showRemainingLines completes
                                isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()


                            }
                        } else {
                            // No remaining lines, reset immediately
                            scope.launch {
                                delay(100) // Very short delay
                                isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()

                            }
                        }
                    }
                }
                return
            }
        }

        // Handle traditional acceptance (full completion for all strategies with Tab)
        val textToInsert = when (type) {
            AcceptType.FULL_COMPLETION -> {
                // Tab always accepts full completion now
                completion.insertText
            }

            AcceptType.CTRL_TAB_COMPLETION -> {
                // Ctrl+Tab uses traditional behavior (was the old Tab behavior)
                if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE) {
                    completion.insertText // Full completion for SIMPLE
                } else {
                    calculateAcceptedText(completion.insertText, AcceptType.FULL_COMPLETION)
                }
            }

            else -> calculateAcceptedText(completion.insertText, type)
        }



        if (textToInsert.isNotEmpty()) {
            // Set accepting flag BEFORE clearing completion or inserting text
            isAcceptingCompletion = true

            // Start acceptance timeout guard
            startAcceptanceTimeoutGuard()

            // Store the accept type and user action for metrics
            val finalAcceptType = type
            val userAction = when (type) {
                AcceptType.FULL_COMPLETION -> "tab"
                AcceptType.CTRL_TAB_COMPLETION -> "ctrl_tab"
                AcceptType.NEXT_WORD -> "word_accept"
                AcceptType.NEXT_LINE -> "line_accept"
            }

            // Clear the completion BEFORE inserting to prevent overlap handling
            clearCurrentCompletion()

            ApplicationManager.getApplication().invokeLater {
                acceptCompletionText(editor, context, completion, textToInsert, finalAcceptType, userAction)
            }
        }
    }

    /**
     * Start a timeout guard to automatically reset acceptance state if it gets stuck
     */
    private fun startAcceptanceTimeoutGuard() {
        // Cancel any existing timeout guard
        acceptanceTimeoutJob?.cancel()

        acceptanceTimeoutJob = scope.launch {
            delay(ACCEPTANCE_TIMEOUT_MS)

            if (isAcceptingCompletion) {

                logger.warn("Acceptance timeout reached, auto-resetting state")

                // Force reset all flags and state
                isAcceptingCompletion = false
                isProgrammaticEdit = false

                // Notify status bar of error state
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
            }
        }
    }

    /**
     * Cancel the acceptance timeout guard (called when acceptance completes normally)
     */
    private fun cancelAcceptanceTimeoutGuard() {
        acceptanceTimeoutJob?.cancel()
        acceptanceTimeoutJob = null
    }

    /**
     * Show remaining lines as a new completion after accepting one line
     * Simplified approach for better reliability
     */
    private fun showRemainingLines(
        editor: Editor, newOffset: Int, remainingText: String, originalCompletion: ZestInlineCompletionItem
    ) {


        // Schedule showing remaining lines after a short delay to ensure insertion is complete
        scope.launch {
            delay(200) // Slightly longer delay to ensure all operations complete

            ApplicationManager.getApplication().invokeLater {
                try {
                    // Get current cursor position
                    val currentCaretOffset = editor.caretModel.offset


                    // Use current cursor position as the starting point for remaining completion
                    val targetOffset = currentCaretOffset

                    // Create new completion item for remaining lines
                    val remainingCompletion = ZestInlineCompletionItem(
                        insertText = remainingText,
                        replaceRange = ZestInlineCompletionItem.Range(targetOffset, targetOffset),
                        confidence = originalCompletion.confidence,
                        metadata = originalCompletion.metadata,
                        completionId = originalCompletion.completionId // Use same completion ID!
                    )

                    // Update current state
                    currentCompletion = remainingCompletion
                    currentContext = CompletionContext.from(editor, targetOffset, manually = true)

                    // Show the remaining completion


                    renderer.show(
                        editor, targetOffset, remainingCompletion, completionProvider.strategy
                    ) { renderingContext ->

                        project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                    }
                } catch (e: Exception) {

                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Dismiss the current completion (ESC key)
     */
    fun dismiss() {

        logger.debug("Dismissing completion")

        // Track completion declined (ESC pressed)
        currentCompletion?.completionId?.let { completionId ->
            // Check if this was after a partial acceptance
            val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (timeSinceAccept < 5000L && completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
                metricsService.trackCompletionFinalized(
                    completionId = completionId, reason = "esc_after_partial_acceptance"
                )
            } else {
                metricsService.trackCompletionDeclined(
                    completionId = completionId, reason = "esc_pressed"
                )
            }
        }

        // IMPORTANT: Reset accepting flag when dismissing
        if (isAcceptingCompletion) {

            isAcceptingCompletion = false
            cancelAcceptanceTimeoutGuard()
        }

        clearCurrentCompletion()
    }

    /**
     * Check if inline completion is visible at the given position
     */
    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        val result =
            renderer.current?.editor == editor && renderer.current?.offset == offset && currentCompletion != null

        return result
    }

    /**
     * Get the current completion item being displayed
     */
    fun getCurrentCompletion(): ZestInlineCompletionItem? {
        return currentCompletion
    }

    /**
     * Check and fix any stuck acceptance states (called by status bar widget)
     */
    fun checkAndFixStuckState(): Boolean {
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        val isStuck = isAcceptingCompletion && timeSinceAccept > 3000L // 3 seconds is stuck

        if (isStuck) {


            // Force reset all flags
            isAcceptingCompletion = false
            isProgrammaticEdit = false
            cancelAcceptanceTimeoutGuard()

            logger.warn("Force reset stuck acceptance state after ${timeSinceAccept}ms")
            return true
        }

        return false
    }

    /**
     * Get detailed state for debugging
     */
    fun getDetailedState(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val requestCount = synchronized(requestHistory) {
            requestHistory.removeAll { now - it > REQUEST_HISTORY_WINDOW_MS }
            requestHistory.size
        }

        return mapOf(
            "isAcceptingCompletion" to isAcceptingCompletion,
            "isProgrammaticEdit" to isProgrammaticEdit,
            "hasCurrentCompletion" to (currentCompletion != null),
            "activeRequestId" to (activeRequestId ?: "null"),
            "currentRequestState" to currentRequestState.name,
            "lastAcceptedTimestamp" to lastAcceptedTimestamp,
            "timeSinceAccept" to (now - lastAcceptedTimestamp),
            "lastRequestTimestamp" to lastRequestTimestamp,
            "timeSinceLastRequest" to (now - lastRequestTimestamp),
            "requestsInLastMinute" to requestCount,
            "strategy" to completionProvider.strategy.name,
            "isEnabled" to inlineCompletionEnabled,
            "autoTrigger" to autoTriggerEnabled
        )
    }

    /**
     * Force refresh/clear all completion state - useful for status bar refresh button
     */
    fun forceRefreshState() {

        logger.info("Force refreshing completion state")

        scope.launch {
            completionMutex.withLock {
                // Cancel all active operations
                currentCompletionJob?.cancel()
                completionTimer?.cancel()
                acceptanceTimeoutJob?.cancel()

                // Reset ALL flags (this is the key fix)
                isAcceptingCompletion = false
                isProgrammaticEdit = false
                activeRequestId = null
                lastAcceptedTimestamp = 0L
                lastAcceptedText = null
                currentRequestState = RequestState.IDLE


                // Clear all state
                currentContext = null
                currentCompletion = null

                // Clear cache
                cacheMutex.withLock {
                    completionCache.clear()
                }

                // Hide renderer
                ApplicationManager.getApplication().invokeLater {
                    renderer.hide()

                    // Notify listeners of reset state
                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                }


            }
        }
    }

    /**
     * Get current completion state for status bar
     */
    fun getCompletionStateInfo(): String {
        return buildString {
            append("Strategy: ${completionProvider.strategy.name}, ")
            append("Enabled: $inlineCompletionEnabled, ")
            append("Auto-trigger: $autoTriggerEnabled, ")
            append("Has completion: ${currentCompletion != null}, ")
            append("Active request: ${activeRequestId != null}, ")
            append("Accepting: $isAcceptingCompletion")
        }
    }

    /**
     * Check if inline completion is enabled
     */
    fun isEnabled(): Boolean {
        return inlineCompletionEnabled
    }

    /**
     * Check if auto-trigger is enabled
     */
    fun isAutoTriggerEnabled(): Boolean {
        return autoTriggerEnabled
    }

    /**
     * Check if continuous completion is enabled
     */
    fun isContinuousCompletionEnabled(): Boolean {
        return continuousCompletionEnabled
    }

    // Private implementation methods

    private suspend fun buildCompletionContext(editor: Editor, offset: Int, manually: Boolean): CompletionContext? {


        // Check if the project is fully initialized before attempting to build context
        if (project.isDisposed || !project.isInitialized) {

            return null
        }

        return try {
            // Use ApplicationManager.invokeLater with a CompletableFuture instead of Dispatchers.Main
            // to avoid the Main dispatcher initialization issue
            val future = java.util.concurrent.CompletableFuture<CompletionContext?>()

            ApplicationManager.getApplication().invokeLater {
                try {
                    // Check if editor is still valid
                    if (editor.isDisposed) {

                        future.complete(null)
                        return@invokeLater
                    }

                    editor.caretModel.offset

                    val context = CompletionContext.from(editor, offset, manually)

                    future.complete(context)
                } catch (e: Exception) {

                    future.complete(null)
                }
            }

            // Wait for the result with a timeout
            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {

            logger.warn("Timeout building completion context")
            null
        } catch (e: Exception) {

            logger.warn("Failed to build completion context", e)
            null
        }
    }

    private suspend fun handleCompletionResponse(
        editor: Editor, context: CompletionContext, completions: ZestInlineCompletionList?, requestId: Int
    ) {


        // Use mutex to ensure only one response is processed at a time

        completionMutex.withLock {


            // Check if this request is still active
            if (activeRequestId != requestId) {

                logger.debug("Response for request $requestId is stale, ignoring")
                return
            }

            // Check if we're in method rewrite mode FIRST (before checking empty)
            if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {

                // Method rewrite mode handles its own UI via inline diff renderer
                // The completion provider already triggered the method rewrite service
                logger.debug("Method rewrite mode - inline diff should be shown")
                // Don't process empty completions for method rewrite - it's expected
                return
            }

            if (completions == null || completions.isEmpty()) {

                logger.debug("No completions available")
                return
            }

            val completion = completions.firstItem()!!
            currentCompletion = completion


            // Clear any existing rendering first to prevent duplicates

            try {
                ApplicationManager.getApplication().invokeAndWait {

                    renderer.hide()
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }


            ApplicationManager.getApplication().invokeLater {


                // Final check if this request is still active
                if (activeRequestId != null && activeRequestId == requestId) {


                    try {
                        renderer.show(
                            editor, context.offset, completion, completionProvider.strategy
                        ) { renderingContext ->


                            project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)

                            // Track completion viewed metric
                            completion.completionId.let { completionId ->
                                metricsService.trackCompletionViewed(
                                    completionId = completionId,
                                    completionLength = completion.insertText.length,
                                    completionLineCount = completion.insertText.split("\n").size,
                                    confidence = completion.confidence
                                )
                            }
                        }

                        logger.debug("Displayed completion: '${completion.insertText.take(50)}'")

                        // Clear activeRequestId after successful display
                        if (activeRequestId == requestId) {

                            activeRequestId = null
                            currentRequestState = RequestState.DISPLAYING
                        }
                    } catch (e: Exception) {

                        e.printStackTrace()

                        // Clear activeRequestId on error too
                        if (activeRequestId == requestId) {
                            activeRequestId = null
                            currentRequestState = RequestState.IDLE
                        }
                    }
                } else {

                    // Clear activeRequestId if it matches this stale request
                    if (activeRequestId == requestId) {
                        activeRequestId = null
                        currentRequestState = RequestState.IDLE
                    }
                }
            }

        }


    }

    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String,
        acceptType: AcceptType = AcceptType.FULL_COMPLETION,
        userAction: String = "tab"
    ) {


        // Set all protection flags
        isProgrammaticEdit = true
        lastAcceptedTimestamp = System.currentTimeMillis()
        lastAcceptedText = textToInsert


        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val startOffset = completionItem.replaceRange.start
                val endOffset = completionItem.replaceRange.end


                // Replace the text
                document.replaceString(startOffset, endOffset, textToInsert)

                // Move cursor to end of inserted text
                val newCaretPosition = startOffset + textToInsert.length
                editor.caretModel.moveToOffset(newCaretPosition)


                // Format the inserted text to ensure proper indentation
                formatInsertedText(editor, startOffset, newCaretPosition)

                logger.debug("Accepted completion: inserted '$textToInsert' at offset $startOffset")

            }

            // Track completion accepted metric
            completionItem.completionId.let { completionId ->
                // Determine if this is accepting all of the completion
                val isFullCompletion = textToInsert == completionItem.insertText

                metricsService.trackCompletionAccepted(
                    completionId = completionId,
                    completionContent = textToInsert,
                    isAll = isFullCompletion,
                    acceptType = acceptType.name,
                    userAction = userAction
                )
            }

            project.messageBus.syncPublisher(Listener.TOPIC).completionAccepted(AcceptType.FULL_COMPLETION)

            // NEW: Schedule next completion for full acceptance (not line-by-line)
            val shouldTriggerNext = continuousCompletionEnabled && when {
                // Only trigger for full completion acceptance (Tab)
                acceptType == AcceptType.FULL_COMPLETION -> true
                // Also trigger for Ctrl+Tab in SIMPLE mode (which is full completion)
                acceptType == AcceptType.CTRL_TAB_COMPLETION && completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE -> true
                // Don't trigger for line-by-line or partial acceptances
                else -> false
            }

            if (shouldTriggerNext) {


                // Schedule next completion after a short delay
                scope.launch {
                    // Wait for the acceptance to complete and editor to stabilize
                    delay(500) // Half second delay

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            // Get the new cursor position after acceptance
                            val newOffset = editor.caretModel.offset


                            // Trigger next completion manually (bypasses cooldown)
                            provideInlineCompletion(editor, newOffset, manually = true)
                        } catch (e: Exception) {

                        }
                    }
                }
            }


        } finally {
            // Cancel timeout guard since acceptance is completing
            cancelAcceptanceTimeoutGuard()

            // Reset flags after a delay to ensure all document change events are processed
            scope.launch {
                // Keep isProgrammaticEdit true for longer
                delay(1000) // 1 second for programmatic edit flag
                isProgrammaticEdit = false


                // For LEAN strategy line-by-line acceptance, don't use the long cooldown
                // The accepting flag will be reset by the line-by-line logic instead
                if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN) {
                    // Reset accepting flag after full cooldown period for other strategies
                    delay(ACCEPTANCE_COOLDOWN_MS - 1000) // Remaining cooldown time
                    isAcceptingCompletion = false

                }
                // For LEAN strategy, isAcceptingCompletion is managed by line-by-line logic
            }
        }
    }

    private fun calculateAcceptedText(completionText: String, type: AcceptType): String {
        val result = when (type) {
            AcceptType.FULL_COMPLETION -> completionText
            AcceptType.CTRL_TAB_COMPLETION -> completionText // For non-LEAN modes
            AcceptType.NEXT_WORD -> {
                val wordMatch = Regex("\\S+").find(completionText)
                wordMatch?.value ?: ""
            }

            AcceptType.NEXT_LINE -> {
                val firstLine = completionText.lines().firstOrNull() ?: ""
                firstLine
            }
        }



        return result
    }

    private fun clearCurrentCompletion(isAll: Boolean = true) {


        completionTimer?.cancel()
        completionTimer = null
        currentCompletionJob?.cancel()
        currentCompletionJob = null
        currentContext = null
        currentCompletion = null

        // Update state to IDLE if we're clearing
        if (currentRequestState == RequestState.DISPLAYING) {
            currentRequestState = RequestState.IDLE
        }

        // IMPORTANT: Reset accepting flag when clearing completion (unless we're in the middle of line-by-line acceptance)
        // For LEAN strategy, only reset if it's been more than a short delay since acceptance
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (isAcceptingCompletion && (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN || timeSinceAccept > 1000L)) {

            isAcceptingCompletion = false
            cancelAcceptanceTimeoutGuard()
        }

        // DO NOT clear activeRequestId here - it's managed by the request lifecycle
        // activeRequestId = null  // REMOVED - this was causing the bug!

        ApplicationManager.getApplication().invokeLater {

            renderer.hide()
        }
    }

    private fun setupEventListeners() {


        // Caret change listener - only schedule completion if not actively typing
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // Don't process caret changes during acceptance
                    if (isAcceptingCompletion) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp


                        // Auto-recovery: if accepting state has been stuck for too long, force reset
                        if (timeSinceAccept > 5000L) { // 5 seconds

                            isAcceptingCompletion = false
                            isProgrammaticEdit = false
                            cancelAcceptanceTimeoutGuard()
                            clearCurrentCompletion()
                        } else {
                            return
                        }
                    }

                    // For method rewrite, also check if we're in the post-accept cooldown
                    if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
                        if (timeSinceAccept < 2000L) { // 2 seconds cooldown for method rewrite

                            return
                        }
                    }

                    val currentOffset = editor.logicalPositionToOffset(event.newPosition)
                    val context = currentContext





                    if (context != null) {
                        val offsetDiff = currentOffset - context.offset


                        // More lenient dismissal logic - only dismiss if cursor moved far away
                        // or if user moved backwards (suggesting they want to edit earlier text)
                        val shouldDismiss = when {
                            offsetDiff < 0 -> {
                                // User moved backwards - check if they moved far back
                                kotlin.math.abs(offsetDiff) > 100 // Allow small backward movements
                            }

                            offsetDiff > 200 -> {
                                // User moved too far forward
                                true
                            }

                            offsetDiff > 0 -> {
                                // User moved forward but within reasonable range
                                // Check if the completion is still meaningful at this position
                                val completion = currentCompletion
                                if (completion != null && completion.insertText.isNotEmpty()) {
                                    // Check if user is typing characters that could match the completion
                                    val userTypedText = try {
                                        val documentText = editor.document.text
                                        val lineStart = documentText.lastIndexOf('\n', currentOffset - 1) + 1
                                        val currentLine = documentText.substring(lineStart, currentOffset)
                                        val originalLineStart = documentText.lastIndexOf('\n', context.offset - 1) + 1
                                        val originalLine = documentText.substring(originalLineStart, context.offset)

                                        // Get what the user has typed since the original completion position
                                        if (currentLine.startsWith(originalLine)) {
                                            currentLine.substring(originalLine.length)
                                        } else {
                                            "" // Lines don't match, user probably edited
                                        }
                                    } catch (e: Exception) {
                                        ""
                                    }


                                    // Don't dismiss if user is typing text that matches the beginning of completion
                                    if (userTypedText.isNotEmpty() && completion.insertText.trim().startsWith(
                                            userTypedText.trim(), ignoreCase = true
                                        )
                                    ) {

                                        false // Don't dismiss - user is typing matching text
                                    } else if (userTypedText.length > 20) {

                                        true // User typed too much non-matching text
                                    } else {

                                        false // Keep completion for now
                                    }
                                } else {
                                    false // No completion to dismiss
                                }
                            }

                            else -> false // No movement, don't dismiss
                        }

                        if (shouldDismiss) {

                            logger.debug("Caret moved significantly, dismissing completion")

                            // Track completion dismissed due to caret movement
                            currentCompletion?.completionId?.let { completionId ->
                                val reason = when {
                                    offsetDiff < 0 -> "cursor_moved_backward"
                                    offsetDiff > 100 -> "cursor_moved_far_forward"
                                    else -> "cursor_moved_typing_mismatch"
                                }

                                // Check if this was a partial acceptance scenario
                                val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
                                if (timeSinceAccept < 5000L) { // Within 5 seconds of last acceptance
                                    metricsService.trackCompletionFinalized(
                                        completionId = completionId, reason = "partial_acceptance_then_$reason"
                                    )
                                } else {
                                    metricsService.trackCompletionDismissed(
                                        completionId = completionId, reason = reason
                                    )
                                }
                            }

                            clearCurrentCompletion()
                        }
                    }

                    // Don't schedule completion on every caret move - only when there's no completion
                    // and no active request, and user has stopped typing
                    // The document listener will handle scheduling after typing stops
                }
            }
        })

        // Editor selection change listener
        messageBusConnection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {


                    // Track completion dismissed due to editor change
                    currentCompletion?.completionId?.let { completionId ->
                        metricsService.trackCompletionDismissed(
                            completionId = completionId, reason = "editor_changed"
                        )
                    }

                    // Reset accepting flag when switching editors
                    if (isAcceptingCompletion) {

                        isAcceptingCompletion = false
                        cancelAcceptanceTimeoutGuard()
                    }
                    clearCurrentCompletion()
                }
            })

        // Document change listener - cancel timers and reset state when user types
        messageBusConnection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
                if (editorManager.selectedTextEditor == editor && !isProgrammaticEdit) {


                    // Cancel any pending timer on document change
                    completionTimer?.let {

                        it.cancel()
                        completionTimer = null
                        if (currentRequestState == RequestState.WAITING) {
                            currentRequestState = RequestState.IDLE
                        }
                    }

                    // Cancel any active request if user is typing
                    if (currentRequestState == RequestState.REQUESTING && activeRequestId != null) {

                        currentCompletionJob?.cancel()
                        // Don't null out activeRequestId here - let the job handle it
                        currentRequestState = RequestState.IDLE
                    }

                    // Clear any displayed completion when user types
                    if (currentCompletion != null && !isAcceptingCompletion) {


                        // Track completion dismissed due to user typing
                        currentCompletion?.completionId?.let { completionId ->
                            metricsService.trackCompletionDismissed(
                                completionId = completionId, reason = "user_typed"
                            )
                        }

                        clearCurrentCompletion()
                    }

                    // If we're accepting and user types something else, cancel the acceptance
                    if (isAcceptingCompletion) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp


                        // If it's been more than a brief moment since acceptance, user is typing something new
                        if (timeSinceAccept > 500L) {


                            // Track completion dismissed if we had one
                            currentCompletion?.completionId?.let { completionId ->
                                metricsService.trackCompletionDismissed(
                                    completionId = completionId, reason = "user_typed_during_acceptance"
                                )
                            }

                            isAcceptingCompletion = false
                            cancelAcceptanceTimeoutGuard()
                            clearCurrentCompletion()
                        }
                    }

                    // Schedule new completion after user stops typing (if auto-trigger enabled)
                    if (autoTriggerEnabled && !isAcceptingCompletion) {
                        val caretOffset = try {
                            editor.caretModel.offset
                        } catch (e: Exception) {
                            -1
                        }

                        if (caretOffset >= 0) {

                            scheduleNewCompletion(editor)
                        }
                    }
                }
            }
        })


    }


    /**
     * Schedule a new completion request with debouncing
     * SIMPLIFIED: Longer delay to prevent conflicts with active completions
     */
    private fun scheduleNewCompletion(editor: Editor) {


        // Don't schedule during acceptance
        if (isAcceptingCompletion) {

            return
        }

        // Don't schedule if already waiting or requesting
        if (currentRequestState == RequestState.WAITING || currentRequestState == RequestState.REQUESTING) {

            return
        }

        // Cancel any existing timer
        completionTimer?.let {

            it.cancel()
        }


        // Don't schedule during cooldown period
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {

            return
        }
        // Don't schedule if a request is already active
        if (activeRequestId != null) {

            currentRequestState = RequestState.IDLE // Reset state since we're not scheduling
            return
        }

        // Update state to WAITING
        currentRequestState = RequestState.WAITING

        completionTimer = scope.launch {

            delay(AUTO_TRIGGER_DELAY_MS)


            // Check if currently accepting
            if (isAcceptingCompletion) {

                currentRequestState = RequestState.IDLE
                return@launch
            }

            // Final check for cooldown period
            val currentTimeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (currentTimeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {

                currentRequestState = RequestState.IDLE
                return@launch
            }

            // Check again if no completion is active
            if (currentCompletion == null && currentRequestState != RequestState.REQUESTING) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset

                        provideInlineCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {

                        currentRequestState = RequestState.IDLE
                        // Editor is disposed, do nothing
                    }
                }
            } else {

                currentRequestState = RequestState.IDLE
            }
        }
    }


    /**
     * Format the inserted completion text using IntelliJ's code style
     * This ensures the accepted completion follows the project's formatting rules
     * Enhanced for lean & simple mode with better PSI synchronization
     */
    private fun formatInsertedText(editor: Editor, startOffset: Int, endOffset: Int) {

        try {
            val document = editor.document
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val psiFile = psiDocumentManager.getPsiFile(document)

            if (psiFile != null) {
                // Ensure PSI is synchronized with document changes
                psiDocumentManager.commitDocument(document)

                // Wait for PSI to be ready
                if (psiDocumentManager.isUncommited(document)) {

                    psiDocumentManager.commitAndRunReadAction {
                        performFormatting(psiFile, startOffset, endOffset)
                    }
                } else {
                    performFormatting(psiFile, startOffset, endOffset)
                }


                logger.debug("Formatted inserted text from offset $startOffset to $endOffset")
            } else {

                logger.debug("Cannot format inserted text: PsiFile is null")
            }
        } catch (e: Exception) {

            logger.warn("Failed to format inserted text: ${e.message}")
            // Don't fail the acceptance if formatting fails
        }
    }

    /**
     * Perform the actual formatting operation
     */
    private fun performFormatting(psiFile: com.intellij.psi.PsiFile, startOffset: Int, endOffset: Int) {
        try {
            val codeStyleManager = CodeStyleManager.getInstance(project)
            // Use reformatRange for proper indentation and formatting
            codeStyleManager.reformatRange(psiFile, startOffset, endOffset)
        } catch (e: Exception) {

            logger.debug("Formatting operation failed", e)
        }
    }

    // Cache management methods

    /**
     * Generate a cache key for the current context
     */
    private fun generateCacheKey(context: CompletionContext): String {
        return "${context.fileName}:${context.offset}:${context.prefixCode.hashCode()}:${context.suffixCode.hashCode()}"
    }

    /**
     * Generate context hash for cache validation
     */
    private fun generateContextHash(editor: Editor, offset: Int): String {
        return try {
            val document = editor.document
            val text = document.text
            val prefixEnd = minOf(offset + 100, text.length)
            val suffixStart = maxOf(offset - 100, 0)
            "${text.substring(suffixStart, offset)}|${text.substring(offset, prefixEnd)}".hashCode().toString()
        } catch (e: Exception) {
            "invalid"
        }
    }

    /**
     * Check if we have a valid cached completion for this context
     */
    private suspend fun getCachedCompletion(context: CompletionContext, editor: Editor): CachedCompletion? {
        return cacheMutex.withLock {
            val cacheKey = generateCacheKey(context)
            val cached = completionCache[cacheKey]

            if (cached != null) {
                // Verify context hasn't changed significantly
                val currentHash = generateContextHash(editor, context.offset)
                if (!cached.isExpired() && cached.contextHash == currentHash) {

                    return@withLock cached
                } else {

                    completionCache.remove(cacheKey)
                }
            }
            null
        }
    }

    /**
     * Cache a completion result for future use
     */
    private suspend fun cacheCompletion(
        context: CompletionContext, editor: Editor, fullCompletion: ZestInlineCompletionItem
    ) {
        cacheMutex.withLock {
            try {
                // Create first-line version for simple display
                val firstLine = fullCompletion.insertText.lines().firstOrNull() ?: ""
                val firstLineCompletion = fullCompletion.copy(insertText = firstLine)

                val cacheKey = generateCacheKey(context)
                val contextHash = generateContextHash(editor, context.offset)

                val cached = CachedCompletion(
                    fullCompletion = fullCompletion,
                    firstLineCompletion = firstLineCompletion,
                    contextHash = contextHash,
                    completionId = fullCompletion.completionId
                )

                completionCache[cacheKey] = cached


                // Clean up old cache entries if we exceed max size
                if (completionCache.size > MAX_CACHE_SIZE) {
                    cleanupCache()
                }
            } catch (e: Exception) {

            }
        }
    }

    /**
     * Clean up expired or excess cache entries
     */
    private fun cleanupCache() {
        // This method is called from within cacheMutex.withLock, so no additional locking needed
        val expiredKeys = completionCache.entries.filter { it.value.isExpired() }.map { it.key }

        expiredKeys.forEach { completionCache.remove(it) }


        // If still too many, remove oldest entries
        if (completionCache.size > MAX_CACHE_SIZE) {
            val oldestKeys =
                completionCache.entries.sortedBy { it.value.timestamp }.take(completionCache.size - MAX_CACHE_SIZE)
                    .map { it.key }

            oldestKeys.forEach { completionCache.remove(it) }

        }
    }

    /**
     * Clear the completion cache
     */
    private fun clearCache() {
        scope.launch {
            cacheMutex.withLock {
                completionCache.clear()

            }
        }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        return "Cache: ${completionCache.size} entries, " + "expired: ${completionCache.values.count { it.isExpired() }}"
    }

    override fun dispose() {

        logger.info("Disposing simplified ZestInlineCompletionService")
        activeRequestId = null
        completionTimer?.cancel()
        acceptanceTimeoutJob?.cancel() // Cancel timeout guard
        scope.cancel()
        renderer.hide()
        clearCache() // Clear completion cache
        messageBusConnection.dispose()
    }

    // Enums and interfaces

    enum class AcceptType {
        FULL_COMPLETION, NEXT_WORD, NEXT_LINE, CTRL_TAB_COMPLETION
    }

    interface Listener {
        fun loadingStateChanged(loading: Boolean) {}
        fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext) {}
        fun completionAccepted(type: AcceptType) {}

        companion object {
            @Topic.ProjectLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    companion object {
        private const val AUTO_TRIGGER_DELAY_MS = 30L // 30ms after user stops typing
        private const val CACHE_EXPIRY_MS = 300000L // 5 minutes cache expiry
        private const val MAX_CACHE_SIZE = 50 // Maximum number of cached completions

        /**
         * Notify all active inline completion services that configuration has changed
         */
        fun notifyConfigurationChanged() {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    project.getService(ZestInlineCompletionService::class.java)?.updateConfiguration()
                }
            }
        }
    }
}
