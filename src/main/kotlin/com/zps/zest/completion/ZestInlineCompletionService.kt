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
import com.zps.zest.completion.cache.CompletionCache
import com.zps.zest.completion.state.CompletionRequestState
import com.zps.zest.completion.state.CompletionAcceptanceState
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/**
 * Simplified service for handling inline completions
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
    private val messageBusConnection = project.messageBus.connect()
    private val editorManager = FileEditorManager.getInstance(project)
    
    // State management using dedicated classes
    private val requestState = CompletionRequestState()
    private val acceptanceState = CompletionAcceptanceState()
    private val completionCache = CompletionCache()
    
    // Notification group for debug balloons
    private val notificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("Zest Completion Debug")
    
    // Debug logging flags
    private var debugLoggingEnabled = true
    private var verboseLoggingEnabled = false // NEW: Even more detailed logging
    
    /**
     * Internal debug logging function
     * @param message The message to log
     * @param tag Optional tag for categorizing logs (default: "ZestService")
     * @param level Log level (0=normal, 1=verbose)
     */
    private fun log(message: String, tag: String = "ZestService", level: Int = 0) {
        if (debugLoggingEnabled && (level == 0 || verboseLoggingEnabled)) {
            val prefix = if (level > 0) "[VERBOSE]" else ""
            println("$prefix[$tag] $message")
            logger.debug("$prefix[$tag] $message")
        }
    }
    
    /**
     * Show balloon notification in status bar for debugging
     */
    private fun showDebugBalloon(title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        if (debugLoggingEnabled) {
            notificationGroup
                .createNotification(title, content, type)
                .notify(project)
        }
    }
    
    /**
     * Enable or disable debug logging
     */
    fun setDebugLogging(enabled: Boolean, verbose: Boolean = false) {
        debugLoggingEnabled = enabled
        verboseLoggingEnabled = verbose
        log("Debug logging ${if (enabled) "enabled" else "disabled"}, verbose: $verbose")
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
        log("Strategy changed: $oldStrategy -> $strategy", "Strategy")

        // Clear cache when strategy changes to ensure appropriate completions
        if (oldStrategy != strategy) {
            scope.launch {
                completionCache.clear()
                log("Cache cleared due to strategy change", "Cache")
            }
        }
        
        // Show balloon notification for strategy change
        showDebugBalloon("Strategy Changed", "Switched to $strategy mode", NotificationType.INFORMATION)
    }

    fun getCompletionStrategy(): ZestCompletionProvider.CompletionStrategy {
        return completionProvider.strategy
    }

    // State management
    private var currentCompletionJob: Job? = null
    private var currentContext: CompletionContext? = null
    private var currentCompletion: ZestInlineCompletionItem? = null

    init {
        log("=== Initializing ZestInlineCompletionService ===", "Init")
        logger.info("Initializing simplified ZestInlineCompletionService")

        // Load configuration settings from ConfigurationManager
        loadConfiguration()

        setupEventListeners()

        // Log initial strategy
        log("Initial strategy: ${completionProvider.strategy}", "Init")
        showDebugBalloon("Zest Initialized", "Strategy: ${completionProvider.strategy}", NotificationType.INFORMATION)
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
        log("=== provideInlineCompletion called ===", "Request")
        log("  offset: $offset, manually: $manually", "Request")
//        log("  editor: ${editor.document.file?.name}", "Request", 1)

        // Cancel any pending timer FIRST
        completionTimer?.let {
            log("Cancelling pending timer", "Timer")
            it.cancel()
            completionTimer = null
        }

        // Check rate limiting (unless manually triggered)
        if (!manually && requestState.isRateLimited()) {
            log("Request rate limited!", "RateLimit")
            showDebugBalloon("Rate Limited", "Too many requests, please wait", NotificationType.WARNING)
            return
        }

        // Check if project is ready
        if (project.isDisposed || !project.isInitialized) {
            log("Project not ready, ignoring completion request", "Project")
            logger.debug("Project not ready, ignoring completion request")
            return
        }

        // Check if editor is still valid
        if (editor.isDisposed) {
            log("Editor is disposed", "Editor")
            return
        }

        // Block all completion requests during acceptance
        if (acceptanceState.isAcceptingCompletion) {
            log("Currently accepting completion, blocking request", "Acceptance")
            return
        }

        // Check cooldown period unless manually triggered
        if (!manually && acceptanceState.isInCooldown()) {
            log("In acceptance cooldown period (${acceptanceState.getTimeSinceLastAcceptance()}ms)", "Cooldown")
            return
        }

        scope.launch {
            val requestId = requestState.generateNewRequestId()
            log("Generated new request ID: $requestId", "Request")

            logger.debug("Requesting completion at offset $offset, manually=$manually, requestId=$requestId")

            // Check if inline completion is enabled at all
            if (!inlineCompletionEnabled && !manually) {
                log("Inline completion is disabled, ignoring request", "Config")
                logger.debug("Inline completion is disabled, ignoring request")
                return@launch
            }

            // Cancel any existing request BEFORE acquiring mutex
            if (requestState.currentRequestState == CompletionRequestState.RequestState.REQUESTING && requestState.activeRequestId != null) {
                log("Cancelling existing request: ${requestState.activeRequestId}", "Cancel")
                completionProvider.setCurrentRequestId(null) // Signal cancellation to provider
            }

            currentCompletionJob?.let {
                log("Cancelling current completion job", "Job")
                it.cancel()
                currentCompletionJob = null
            }

            // Use mutex to ensure only one request is processed at a time
            log("Acquiring completion mutex...", "Mutex", 1)
            completionMutex.withLock {
                log("Mutex acquired for request $requestId", "Mutex", 1)

                // Check if this is still the latest request
                if (requestId < (requestState.activeRequestId ?: 0)) {
                    log("Request $requestId is outdated (current: ${requestState.activeRequestId}), skipping", "Request")
                    logger.debug("Request $requestId is outdated, skipping")
                    return@withLock
                }

                requestState.activeRequestId = requestId
                log("Set active request ID to $requestId", "Request", 1)

                // Clear any existing completion request (moved after state update)
                log("Clearing current completion", "Clear")
                clearCurrentCompletion()

                // Check if auto-trigger is disabled and this is not manual
                if (!autoTriggerEnabled && !manually) {
                    log("Auto-trigger disabled, ignoring automatic request", "Config")
                    logger.debug("Auto-trigger disabled, ignoring automatic request")
                    requestState.activeRequestId = null
                    return@withLock
                }

                // NOW we're actually going to request - update state
                requestState.currentRequestState = CompletionRequestState.RequestState.REQUESTING
                requestState.recordRequest() // Record this request for rate limiting
                log("Request state -> REQUESTING", "State")

                // Notify listeners that we're loading
                log("Notifying loading state changed (true)", "Event")
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)

                // Start completion request
                currentCompletionJob = scope.launch {
                    log("=== Starting completion job for request $requestId ===", "Job")

                    // Generate a new completion ID for metrics tracking
                    val completionId = UUID.randomUUID().toString()
                    log("Generated completion ID: $completionId", "Metrics")

                    try {
                        log("Building completion context...", "Context")
                        val context = buildCompletionContext(editor, offset, manually)
                        if (context == null) {
                            log("Failed to build completion context", "Context")
                            logger.debug("Failed to build completion context")
                            return@launch
                        }
                        log("Context built successfully: ${context.fileName} @ ${context.offset}", "Context")

                        // Check if this request is still active
                        if (requestState.activeRequestId != requestId) {
                            log("Request $requestId is no longer active (current: ${requestState.activeRequestId})", "Request")
                            logger.debug("Request $requestId is no longer active")
                            return@launch
                        }

                        currentContext = context
                        log("Set current context", "Context", 1)

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
                        log("Context info: $contextInfo", "Metrics", 1)

                        // Check cache first for SIMPLE and LEAN strategies
                        if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE || 
                            completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
                            log("Checking cache for ${completionProvider.strategy} strategy...", "Cache")

                            val cacheKey = generateCacheKey(context)
                            val contextHash = generateContextHash(editor, context.offset)
                            val cached = completionCache.get(cacheKey, contextHash)
                            
                            if (cached != null) {
                                log("Cache HIT! Using cached completion", "Cache")
                                showDebugBalloon("Cache Hit", "Using cached completion", NotificationType.INFORMATION)

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
                            } else {
                                log("Cache MISS - will request from provider", "Cache")
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
                            log("Background context is enabled, including additional context", "Context")
                            logger.debug("Background context is enabled, including additional context")
                            // TODO: Implement background context gathering here
                        }

                        // Tell the provider about the new request ID for cancellation
                        completionProvider.setCurrentRequestId(requestId)
                        log("Set provider request ID to $requestId", "Provider", 1)

                        log("Calling completion provider...", "Provider")
                        showDebugBalloon("Requesting Completion", "Calling ${completionProvider.strategy} provider", NotificationType.INFORMATION)
                        
                        val startTime = System.currentTimeMillis()
                        val completions = completionProvider.requestCompletion(context, requestId, completionId)
                        val elapsed = System.currentTimeMillis() - startTime
                        
                        log("Provider returned in ${elapsed}ms", "Provider")

                        var insertText = ""
                        completions?.items?.firstOrNull()?.let {
                            insertText = it.insertText
                            log("Got completion text: '${insertText.take(50)}...' (${insertText.length} chars)", "Provider")
                        }

                        if (completions == null) {
                            log("Provider returned NULL completions!", "Provider")
                            showDebugBalloon("No Completion", "Provider returned null", NotificationType.WARNING)
                        } else if (completions.isEmpty()) {
                            log("Provider returned EMPTY completions!", "Provider")
                            showDebugBalloon("Empty Completion", "Provider returned empty list", NotificationType.WARNING)
                        }

                        // Track completion completed (response received)
                        metricsService.trackCompletionCompleted(
                            completionId = completionId, completionContent = insertText, responseTime = elapsed
                        )

                        // Check again if this request is still active
                        if (requestState.activeRequestId != requestId) {
                            log("Request $requestId is no longer active after completion", "Request")
                            logger.debug("Request $requestId is no longer active after completion")
                            return@launch
                        }

                        if (currentContext == context) { // Ensure request is still valid
                            log("Request context is still valid", "Context", 1)

                            // Update the completion with our tracking ID
                            val completionsWithId = completions?.let { list ->
                                val items = list.items.map { item ->
                                    item.copy(completionId = completionId)
                                }
                                ZestInlineCompletionList(list.isIncomplete, items)
                            }
                            log("Updated completions with tracking ID", "Metrics", 1)

                            // Cache the completion if it's not empty and for cacheable strategies
                            if (completionsWithId != null && !completionsWithId.isEmpty() && 
                                (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE || 
                                 completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN)) {
                                log("Caching completion for future use", "Cache")
                                val firstCompletion = completionsWithId.firstItem()!!

                                cacheCompletion(context, editor, firstCompletion)

                                // For SIMPLE strategy, show full completion (line-by-line acceptance handled in accept() method)
                                val displayCompletion = completionsWithId // Show full completion for all strategies

                                // Store the FULL completion for acceptance
                                currentCompletion = firstCompletion
                                log("Stored current completion", "State", 1)

                                handleCompletionResponse(editor, context, displayCompletion, requestId)
                            } else {
                                log("Non-cacheable completion or empty result", "Cache")
                                // Non-cacheable strategy or empty completion
                                handleCompletionResponse(editor, context, completionsWithId, requestId)
                            }
                        } else {
                            log("Context changed during request!", "Context")
                        }
                    } catch (e: CancellationException) {
                        log("Completion request cancelled", "Cancel")
                        logger.debug("Completion request cancelled")

                        // IMPORTANT: Clear activeRequestId if this was the cancelled request
                        if (requestState.activeRequestId == requestId) {
                            log("Clearing active request ID on cancellation", "Cancel", 1)
                            requestState.activeRequestId = null
                            requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                        }

                        throw e
                    } catch (e: Exception) {
                        log("ERROR in completion request: ${e.message}", "Error")
                        e.printStackTrace()
                        logger.warn("Completion request failed", e)
                        showDebugBalloon("Completion Error", e.message ?: "Unknown error", NotificationType.ERROR)
                    } finally {
                        log("=== Completion job cleanup for request $requestId ===", "Job")

                        // For METHOD_REWRITE, keep loading state and activeRequestId active longer
                        if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                            // Normal completion - clear immediately
                            log("Notifying loading state changed (false)", "Event")
                            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)

                            // Update state based on outcome
                            if (requestState.activeRequestId == requestId) {
                                when {
                                    currentCompletion != null -> {
                                        // Completion will be displayed, activeRequestId cleared in handleCompletionResponse
                                        requestState.currentRequestState = CompletionRequestState.RequestState.DISPLAYING
                                        log("Request state -> DISPLAYING", "State")
                                    }
                                    else -> {
                                        // No completion, clear everything
                                        requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                                        requestState.activeRequestId = null
                                        log("Request state -> IDLE (no completion)", "State")
                                    }
                                }
                            }
                        } else {
                            // METHOD_REWRITE - keep active for longer to allow method rewrite to complete
                            log("Method rewrite mode - keeping state active", "MethodRewrite")

                            // Clear loading state after a delay
                            scope.launch {
                                delay(5000) // 5 seconds should be enough for method rewrite to show UI
                                if (requestState.activeRequestId == requestId) {
                                    log("Clearing method rewrite loading state", "MethodRewrite")
                                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                                    requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                                }
                            }

                            // Clear activeRequestId after a shorter delay
                            scope.launch {
                                delay(2000) // 2 seconds for method rewrite service to start
                                if (requestState.activeRequestId == requestId) {
                                    log("Clearing method rewrite active request ID", "MethodRewrite")
                                    requestState.activeRequestId = null
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
        log("=== accept() called ===", "Accept")
        log("  type: $type", "Accept")
        log("  offset: $offset", "Accept")

        // Prevent multiple simultaneous accepts
        if (acceptanceState.isAcceptingCompletion) {
            log("Already accepting completion, blocking", "Accept")
            return
        }

        val context = currentContext ?: return
        val completion = currentCompletion ?: return
        val actualOffset = offset ?: context.offset

        if (actualOffset != context.offset) {
            log("Invalid position for acceptance (expected: ${context.offset}, actual: $actualOffset)", "Accept")
            logger.debug("Invalid position for acceptance")
            return
        }

        // Handle line-by-line acceptance for Ctrl+Tab in LEAN strategy
        if (type == AcceptType.CTRL_TAB_COMPLETION && completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
            log("LEAN line-by-line acceptance", "Accept")
            val lines = completion.insertText.lines()

            log("Completion has ${lines.size} lines", "Accept", 1)

            lines.forEachIndexed { index, line ->
                log("  Line $index: '${line.take(50)}...'", "Accept", 1)
            }

            if (lines.size > 1) {
                log("Multi-line completion - accepting first line only", "Accept")

                // Accept only the first line
                var firstLine = lines[0]
                val remainingLines = lines.drop(1).filter { it.isNotBlank() } // Filter out empty lines

                log("First line: '$firstLine'", "Accept", 1)
                log("Remaining lines: ${remainingLines.size}", "Accept", 1)

                // Ensure first line ends with newline if there are remaining lines
                if (remainingLines.isNotEmpty() && !firstLine.endsWith("\n")) {
                    firstLine = "$firstLine\n"
                    log("Added newline to first line", "Accept", 1)
                }

                if (firstLine.isNotEmpty()) {
                    // Set accepting flag
                    acceptanceState.isAcceptingCompletion = true

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
                            
                            log("Showing remaining lines at offset $newOffset", "Accept")
                            showRemainingLines(editor, newOffset, remainingText, completion)

                            // Reset accepting flag immediately for line-by-line acceptance
                            // since the remaining lines are now displayed as a new completion
                            scope.launch {
                                delay(300) // Small delay to ensure showRemainingLines completes
                                acceptanceState.isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()
                                log("Reset accepting flag after showing remaining lines", "Accept", 1)
                            }
                        } else {
                            // No remaining lines, reset immediately
                            scope.launch {
                                delay(100) // Very short delay
                                acceptanceState.isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()
                                log("Reset accepting flag (no remaining lines)", "Accept", 1)
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

        log("Text to insert: '${textToInsert.take(50)}...' (${textToInsert.length} chars)", "Accept")

        if (textToInsert.isNotEmpty()) {
            // Set accepting flag BEFORE clearing completion or inserting text
            acceptanceState.isAcceptingCompletion = true

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
        acceptanceState.acceptanceTimeoutJob?.cancel()

        acceptanceState.acceptanceTimeoutJob = scope.launch {
            delay(acceptanceState.getTimeoutMs())

            if (acceptanceState.isAcceptingCompletion) {
                log("WARNING: Acceptance timeout reached, auto-resetting state", "Timeout")
                logger.warn("Acceptance timeout reached, auto-resetting state")

                // Force reset all flags and state
                acceptanceState.reset()

                // Notify status bar of error state
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                showDebugBalloon("Timeout", "Acceptance timeout - state reset", NotificationType.WARNING)
            }
        }
    }

    /**
     * Cancel the acceptance timeout guard (called when acceptance completes normally)
     */
    private fun cancelAcceptanceTimeoutGuard() {
        acceptanceState.acceptanceTimeoutJob?.cancel()
        acceptanceState.acceptanceTimeoutJob = null
    }

    /**
     * Show remaining lines as a new completion after accepting one line
     * Simplified approach for better reliability
     */
    private fun showRemainingLines(
        editor: Editor, newOffset: Int, remainingText: String, originalCompletion: ZestInlineCompletionItem
    ) {
        log("=== showRemainingLines called ===", "Remaining")
        log("  newOffset: $newOffset", "Remaining")
        log("  remainingText: '${remainingText.take(50)}...' (${remainingText.length} chars)", "Remaining")

        // Schedule showing remaining lines after a short delay to ensure insertion is complete
        scope.launch {
            delay(200) // Slightly longer delay to ensure all operations complete

            ApplicationManager.getApplication().invokeLater {
                try {
                    // Get current cursor position
                    val currentCaretOffset = editor.caretModel.offset
                    log("Current caret offset: $currentCaretOffset", "Remaining", 1)

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
                    log("Showing remaining completion at offset $targetOffset", "Remaining")

                    renderer.show(
                        editor, targetOffset, remainingCompletion, completionProvider.strategy
                    ) { renderingContext ->
                        log("Remaining completion displayed", "Remaining", 1)
                        project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                    }
                    
                    showDebugBalloon("Line Accepted", "Remaining lines ready", NotificationType.INFORMATION)
                } catch (e: Exception) {
                    log("ERROR showing remaining lines: ${e.message}", "Remaining")
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Dismiss the current completion (ESC key)
     */
    fun dismiss() {
        log("=== dismiss() called ===", "Dismiss")
        logger.debug("Dismissing completion")

        // Track completion declined (ESC pressed)
        currentCompletion?.completionId?.let { completionId ->
            // Check if this was after a partial acceptance
            val timeSinceAccept = acceptanceState.getTimeSinceLastAcceptance()
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
        if (acceptanceState.isAcceptingCompletion) {
            log("Resetting acceptance state on dismiss", "Dismiss")
            acceptanceState.reset()
        }

        clearCurrentCompletion()
        showDebugBalloon("Dismissed", "Completion dismissed", NotificationType.INFORMATION)
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
        val wasStuck = acceptanceState.checkAndFixStuckState()
        
        if (wasStuck) {
            log("WARNING: Force reset stuck acceptance state", "StuckState")
            logger.warn("Force reset stuck acceptance state after ${acceptanceState.getTimeSinceLastAcceptance()}ms")
            showDebugBalloon("State Reset", "Fixed stuck acceptance state", NotificationType.WARNING)
        }
        
        return wasStuck
    }

    /**
     * Get detailed state for debugging
     */
    fun getDetailedState(): Map<String, Any> {
        val now = System.currentTimeMillis()
        
        return mapOf(
            "isAcceptingCompletion" to acceptanceState.isAcceptingCompletion,
            "isProgrammaticEdit" to acceptanceState.isProgrammaticEdit,
            "hasCurrentCompletion" to (currentCompletion != null),
            "activeRequestId" to (requestState.activeRequestId ?: "null"),
            "currentRequestState" to requestState.currentRequestState.name,
            "lastAcceptedTimestamp" to acceptanceState.lastAcceptedTimestamp,
            "timeSinceAccept" to acceptanceState.getTimeSinceLastAcceptance(),
            "lastRequestTimestamp" to requestState.getTimeSinceLastRequest(),
            "timeSinceLastRequest" to requestState.getTimeSinceLastRequest(),
            "requestsInLastMinute" to requestState.getRequestCount(),
            "strategy" to completionProvider.strategy.name,
            "isEnabled" to inlineCompletionEnabled,
            "autoTrigger" to autoTriggerEnabled
        )
    }

    /**
     * Force refresh/clear all completion state - useful for status bar refresh button
     */
    fun forceRefreshState() {
        log("=== Force refreshing completion state ===", "Refresh")
        logger.info("Force refreshing completion state")

        scope.launch {
            completionMutex.withLock {
                // Cancel all active operations
                currentCompletionJob?.cancel()
                completionTimer?.cancel()
                
                // Reset state objects
                requestState.reset()
                acceptanceState.reset()
                
                log("Reset all state flags", "Refresh")

                // Clear all state
                currentContext = null
                currentCompletion = null

                // Clear cache
                completionCache.clear()
                log("Cleared completion cache", "Refresh")

                // Hide renderer
                ApplicationManager.getApplication().invokeLater {
                    renderer.hide()

                    // Notify listeners of reset state
                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                }

                log("Force refresh complete", "Refresh")
                showDebugBalloon("State Refreshed", "All completion state cleared", NotificationType.INFORMATION)
            }
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
        log("=== handleCompletionResponse called ===", "Response")
        log("  requestId: $requestId", "Response")
        log("  completions: ${completions?.items?.size ?: 0} items", "Response")

        // Use mutex to ensure only one response is processed at a time
        log("Acquiring completion mutex for response...", "Mutex", 1)
        completionMutex.withLock {
            log("Mutex acquired for response $requestId", "Mutex", 1)

            // Check if this request is still active
            if (requestState.activeRequestId != requestId) {
                log("Response for request $requestId is stale (current: ${requestState.activeRequestId}), ignoring", "Response")
                logger.debug("Response for request $requestId is stale, ignoring")
                return
            }

            // Check if we're in method rewrite mode FIRST (before checking empty)
            if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                log("Method rewrite mode - inline diff should be shown", "MethodRewrite")
                // Method rewrite mode handles its own UI via inline diff renderer
                // The completion provider already triggered the method rewrite service
                logger.debug("Method rewrite mode - inline diff should be shown")
                // Don't process empty completions for method rewrite - it's expected
                return
            }

            if (completions == null || completions.isEmpty()) {
                log("No completions available (null: ${completions == null})", "Response")
                logger.debug("No completions available")
                showDebugBalloon("No Completion", "No suggestions available", NotificationType.WARNING)
                return
            }

            val completion = completions.firstItem()!!
            currentCompletion = completion
            log("Got completion: '${completion.insertText.take(30)}...'", "Response")
            log("  Length: ${completion.insertText.length} chars", "Response", 1)
            log("  Lines: ${completion.insertText.lines().size}", "Response", 1)
            log("  Confidence: ${completion.confidence}", "Response", 1)

            // Clear any existing rendering first to prevent duplicates
            log("Clearing existing renderer", "Renderer", 1)
            try {
                ApplicationManager.getApplication().invokeAndWait {
                    log("Hiding renderer on EDT", "Renderer", 1)
                    renderer.hide()
                }
            } catch (e: Exception) {
                log("ERROR hiding renderer: ${e.message}", "Renderer")
                e.printStackTrace()
            }

            log("Scheduling completion display on EDT", "Display")
            ApplicationManager.getApplication().invokeLater {
                log("=== Displaying completion on EDT ===", "Display")

                // Final check if this request is still active
                if (requestState.activeRequestId != null && requestState.activeRequestId == requestId) {
                    log("Request $requestId is still active, showing completion", "Display")

                    try {
                        log("Calling renderer.show()...", "Renderer")
                        renderer.show(
                            editor, context.offset, completion, completionProvider.strategy
                        ) { renderingContext ->
                            log("Renderer callback - completion displayed", "Renderer")
                            log("  Rendering context: $renderingContext", "Renderer", 1)

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
                        showDebugBalloon("Completion Ready", "Press Tab to accept", NotificationType.INFORMATION)

                        // Clear activeRequestId after successful display
                        if (requestState.activeRequestId == requestId) {
                            log("Clearing active request ID after display", "State", 1)
                            requestState.activeRequestId = null
                            requestState.currentRequestState = CompletionRequestState.RequestState.DISPLAYING
                        }
                    } catch (e: Exception) {
                        log("ERROR displaying completion: ${e.message}", "Display")
                        e.printStackTrace()
                        showDebugBalloon("Display Error", e.message ?: "Failed to show completion", NotificationType.ERROR)

                        // Clear activeRequestId on error too
                        if (requestState.activeRequestId == requestId) {
                            requestState.activeRequestId = null
                            requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                        }
                    }
                } else {
                    log("Request $requestId is no longer active on EDT", "Display")
                    // Clear activeRequestId if it matches this stale request
                    if (requestState.activeRequestId == requestId) {
                        requestState.activeRequestId = null
                        requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                    }
                }
            }
        }

        log("=== handleCompletionResponse done ===", "Response")
    }

    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String,
        acceptType: AcceptType = AcceptType.FULL_COMPLETION,
        userAction: String = "tab"
    ) {
        log("=== acceptCompletionText called ===", "AcceptText")
        log("  textToInsert: '${textToInsert.take(50)}...' (${textToInsert.length} chars)", "AcceptText")
        log("  acceptType: $acceptType", "AcceptText")

        // Set all protection flags
        acceptanceState.startAcceptance(textToInsert)
        log("Started acceptance state", "AcceptText", 1)

        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val startOffset = completionItem.replaceRange.start
                val endOffset = completionItem.replaceRange.end

                log("Replacing text: offset $startOffset-$endOffset", "AcceptText", 1)

                // Replace the text
                document.replaceString(startOffset, endOffset, textToInsert)

                // Move cursor to end of inserted text
                val newCaretPosition = startOffset + textToInsert.length
                editor.caretModel.moveToOffset(newCaretPosition)

                log("Text inserted, cursor at $newCaretPosition", "AcceptText", 1)

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
            showDebugBalloon("Accepted", "Completion accepted ($acceptType)", NotificationType.INFORMATION)

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
                log("Continuous completion enabled - scheduling next completion", "Continuous")

                // Schedule next completion after a short delay
                scope.launch {
                    // Wait for the acceptance to complete and editor to stabilize
                    delay(500) // Half second delay

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            // Get the new cursor position after acceptance
                            val newOffset = editor.caretModel.offset

                            log("Triggering next completion at offset $newOffset", "Continuous")
                            // Trigger next completion manually (bypasses cooldown)
                            provideInlineCompletion(editor, newOffset, manually = true)
                        } catch (e: Exception) {
                            log("ERROR triggering next completion: ${e.message}", "Continuous")
                        }
                    }
                }
            }

            log("Acceptance complete", "AcceptText")

        } finally {
            // Cancel timeout guard since acceptance is completing
            cancelAcceptanceTimeoutGuard()

            // Reset flags after a delay to ensure all document change events are processed
            scope.launch {
                // Keep isProgrammaticEdit true for longer
                delay(1000) // 1 second for programmatic edit flag
                acceptanceState.isProgrammaticEdit = false
                log("Reset programmatic edit flag", "AcceptText", 1)

                // For LEAN strategy line-by-line acceptance, don't use the long cooldown
                // The accepting flag will be reset by the line-by-line logic instead
                if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN) {
                    // Reset accepting flag after full cooldown period for other strategies
                    delay(acceptanceState.getCooldownMs() - 1000) // Remaining cooldown time
                    acceptanceState.isAcceptingCompletion = false
                    log("Reset accepting flag after cooldown", "AcceptText", 1)
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

        log("Calculated accepted text for $type: '${result.take(50)}...' (${result.length} chars)", "Calculate", 1)

        return result
    }

    private fun clearCurrentCompletion(isAll: Boolean = true) {
        log("Clearing current completion (isAll: $isAll)", "Clear")

        completionTimer?.cancel()
        completionTimer = null
        currentCompletionJob?.cancel()
        currentCompletionJob = null
        currentContext = null
        currentCompletion = null

        // Update state to IDLE if we're clearing
        if (requestState.currentRequestState == CompletionRequestState.RequestState.DISPLAYING) {
            requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
        }

        // IMPORTANT: Reset accepting flag when clearing completion (unless we're in the middle of line-by-line acceptance)
        // For LEAN strategy, only reset if it's been more than a short delay since acceptance
        val timeSinceAccept = acceptanceState.getTimeSinceLastAcceptance()
        if (acceptanceState.isAcceptingCompletion && 
            (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN || timeSinceAccept > 1000L)) {
            log("Resetting acceptance flags", "Clear", 1)
            acceptanceState.isAcceptingCompletion = false
            cancelAcceptanceTimeoutGuard()
        }

        // DO NOT clear activeRequestId here - it's managed by the request lifecycle
        // activeRequestId = null  // REMOVED - this was causing the bug!

        ApplicationManager.getApplication().invokeLater {
            log("Hiding renderer", "Clear", 1)
            renderer.hide()
        }
    }

    private fun setupEventListeners() {
        log("Setting up event listeners", "Init")

        // Caret change listener - only schedule completion if not actively typing
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // Don't process caret changes during acceptance
                    if (acceptanceState.isAcceptingCompletion) {
                        val timeSinceAccept = acceptanceState.getTimeSinceLastAcceptance()
                        
                        log("Caret moved during acceptance (${timeSinceAccept}ms since accept)", "Caret", 1)

                        // Auto-recovery: if accepting state has been stuck for too long, force reset
                        if (timeSinceAccept > 5000L) { // 5 seconds
                            log("WARNING: Auto-recovery - resetting stuck acceptance state", "Caret")
                            acceptanceState.reset()
                            clearCurrentCompletion()
                        } else {
                            return
                        }
                    }

                    // For method rewrite, also check if we're in the post-accept cooldown
                    if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                        val timeSinceAccept = acceptanceState.getTimeSinceLastAcceptance()
                        if (timeSinceAccept < 2000L) { // 2 seconds cooldown for method rewrite
                            log("Method rewrite cooldown active", "Caret", 1)
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
                    log("Editor selection changed", "Editor")

                    // Track completion dismissed due to editor change
                    currentCompletion?.completionId?.let { completionId ->
                        metricsService.trackCompletionDismissed(
                            completionId = completionId, reason = "editor_changed"
                        )
                    }

                    // Reset accepting flag when switching editors
                    if (acceptanceState.isAcceptingCompletion) {
                        log("Resetting acceptance state on editor change", "Editor")
                        acceptanceState.reset()
                    }
                    clearCurrentCompletion()
                }
            })

        // Document change listener - cancel timers and reset state when user types
        messageBusConnection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
                if (editorManager.selectedTextEditor == editor && !acceptanceState.isProgrammaticEdit) {
                    log("Document changed (non-programmatic)", "Document", 1)

                    // Cancel any pending timer on document change
                    completionTimer?.let {
                        log("Cancelling pending timer", "Document", 1)
                        it.cancel()
                        completionTimer = null
                        if (requestState.currentRequestState == CompletionRequestState.RequestState.WAITING) {
                            requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                        }
                    }

                    // Cancel any active request if user is typing
                    if (requestState.currentRequestState == CompletionRequestState.RequestState.REQUESTING && 
                        requestState.activeRequestId != null) {
                        log("Cancelling active request due to typing", "Document")
                        currentCompletionJob?.cancel()
                        // Don't null out activeRequestId here - let the job handle it
                        requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                    }

                    // Clear any displayed completion when user types
                    if (currentCompletion != null && !acceptanceState.isAcceptingCompletion) {
                        log("User typed - clearing displayed completion", "Document")

                        // Track completion dismissed due to user typing
                        currentCompletion?.completionId?.let { completionId ->
                            metricsService.trackCompletionDismissed(
                                completionId = completionId, reason = "user_typed"
                            )
                        }

                        clearCurrentCompletion()
                    }

                    // If we're accepting and user types something else, cancel the acceptance
                    if (acceptanceState.isAcceptingCompletion) {
                        val timeSinceAccept = acceptanceState.getTimeSinceLastAcceptance()
                        
                        log("User typed during acceptance (${timeSinceAccept}ms since accept)", "Document", 1)

                        // If it's been more than a brief moment since acceptance, user is typing something new
                        if (timeSinceAccept > 500L) {
                            log("Cancelling acceptance - user typed after delay", "Document")

                            // Track completion dismissed if we had one
                            currentCompletion?.completionId?.let { completionId ->
                                metricsService.trackCompletionDismissed(
                                    completionId = completionId, reason = "user_typed_during_acceptance"
                                )
                            }

                            acceptanceState.reset()
                            clearCurrentCompletion()
                        }
                    }

                    // Schedule new completion after user stops typing (if auto-trigger enabled)
                    if (autoTriggerEnabled && !acceptanceState.isAcceptingCompletion) {
                        val caretOffset = try {
                            editor.caretModel.offset
                        } catch (e: Exception) {
                            -1
                        }

                        if (caretOffset >= 0) {
                            log("Scheduling new completion after typing", "Document", 1)
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
        log("scheduleNewCompletion called", "Schedule", 1)

        // Don't schedule during acceptance
        if (acceptanceState.isAcceptingCompletion) {
            log("Currently accepting, not scheduling", "Schedule", 1)
            return
        }

        // Don't schedule if already waiting or requesting
        if (requestState.currentRequestState == CompletionRequestState.RequestState.WAITING || 
            requestState.currentRequestState == CompletionRequestState.RequestState.REQUESTING) {
            log("Already waiting/requesting, not scheduling", "Schedule", 1)
            return
        }

        // Cancel any existing timer
        completionTimer?.let {
            log("Cancelling existing timer", "Schedule", 1)
            it.cancel()
        }

        // Don't schedule during cooldown period
        if (acceptanceState.isInCooldown()) {
            log("In cooldown period, not scheduling", "Schedule", 1)
            return
        }
        
        // Don't schedule if a request is already active
        if (requestState.activeRequestId != null) {
            log("Active request exists, not scheduling", "Schedule", 1)
            requestState.currentRequestState = CompletionRequestState.RequestState.IDLE // Reset state since we're not scheduling
            return
        }

        // Update state to WAITING
        requestState.currentRequestState = CompletionRequestState.RequestState.WAITING
        log("State -> WAITING, scheduling timer", "Schedule")

        completionTimer = scope.launch {
            log("Timer started (${AUTO_TRIGGER_DELAY_MS}ms)", "Timer", 1)
            delay(AUTO_TRIGGER_DELAY_MS)
            
            log("Timer fired", "Timer", 1)

            // Check if currently accepting
            if (acceptanceState.isAcceptingCompletion) {
                log("Currently accepting, cancelling timer", "Timer", 1)
                requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                return@launch
            }

            // Final check for cooldown period
            if (acceptanceState.isInCooldown()) {
                log("Still in cooldown, cancelling timer", "Timer", 1)
                requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                return@launch
            }

            // Check again if no completion is active
            if (currentCompletion == null && 
                requestState.currentRequestState != CompletionRequestState.RequestState.REQUESTING) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset
                        log("Timer triggering completion at offset $currentOffset", "Timer")
                        provideInlineCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {
                        log("ERROR in timer: ${e.message}", "Timer")
                        requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
                        // Editor is disposed, do nothing
                    }
                }
            } else {
                log("Completion active or already requesting, cancelling timer", "Timer", 1)
                requestState.currentRequestState = CompletionRequestState.RequestState.IDLE
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
     * Cache a completion result for future use
     */
    private suspend fun cacheCompletion(
        context: CompletionContext, editor: Editor, fullCompletion: ZestInlineCompletionItem
    ) {
        try {
            val cacheKey = generateCacheKey(context)
            val contextHash = generateContextHash(editor, context.offset)
            
            completionCache.put(cacheKey, contextHash, fullCompletion)
            log("Cached completion (key: ${cacheKey.take(20)}...)", "Cache")
        } catch (e: Exception) {
            log("ERROR caching completion: ${e.message}", "Cache")
        }
    }

    /**
     * Clear the completion cache
     */
    private fun clearCache() {
        scope.launch {
            completionCache.clear()
            log("Cache cleared", "Cache")
        }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        return completionCache.getStats()
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
