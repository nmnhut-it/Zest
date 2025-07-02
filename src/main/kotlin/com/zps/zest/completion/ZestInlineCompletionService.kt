package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
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
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Simplified service for handling inline completions
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
    private val messageBusConnection = project.messageBus.connect()
    private val editorManager = FileEditorManager.getInstance(project)
    
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
    
    // Configuration manager reference
    private val configManager = ConfigurationManager.getInstance(project)
    
    // Request tracking to prevent multiple concurrent requests
    private val requestGeneration = AtomicInteger(0)
    private var activeRequestId: Int? = null
    
    // Mutex for critical sections
    private val completionMutex = Mutex()
    
    // Single debounce timer
    private var completionTimer: Job? = null
    
    // Strategy management
    fun setCompletionStrategy(strategy: ZestCompletionProvider.CompletionStrategy) {
        val oldStrategy = completionProvider.strategy
        completionProvider.setStrategy(strategy)
//        System.out.println("[ZestInlineCompletion] Strategy updated to: $strategy")
        logger.info("Completion strategy updated to: $strategy")
        
        // Clear cache when strategy changes to ensure appropriate completions
        if (oldStrategy != strategy) {
            scope.launch {
                cacheMutex.withLock {
                    completionCache.clear()
                }
            }
//            System.out.println("[ZestInlineCompletion] Cache cleared due to strategy change")
        }
    }
    
    fun getCompletionStrategy(): ZestCompletionProvider.CompletionStrategy {
        return completionProvider.strategy
    }
    
    // State management
    private var currentCompletionJob: Job? = null
    private var currentContext: CompletionContext? = null
    private var currentCompletion: ZestInlineCompletionItem? = null
    private var currentCompletionId: String? = null // Track current completion ID for metrics
    
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
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(maxAgeMs: Long = CACHE_EXPIRY_MS): Boolean {
            return System.currentTimeMillis() - timestamp > maxAgeMs
        }
    }
    
    private val completionCache = ConcurrentHashMap<String, CachedCompletion>()
    private val cacheMutex = Mutex()
    
    init {
//        System.out.println("[ZestInlineCompletion] Initializing service for project: ${project.name}")
        logger.info("Initializing simplified ZestInlineCompletionService")
        
        // Load configuration settings from ConfigurationManager
        loadConfiguration()
        
        setupEventListeners()
        
        // Log initial strategy
//        System.out.println("[ZestInlineCompletion] Initial completion strategy: ${completionProvider.strategy}")
        
//        System.out.println("[ZestInlineCompletion] Service initialization complete")
    }
    
    /**
     * Load configuration from ConfigurationManager
     */
    private fun loadConfiguration() {
        inlineCompletionEnabled = configManager.isInlineCompletionEnabled()
        autoTriggerEnabled = configManager.isAutoTriggerEnabled()
        backgroundContextEnabled = configManager.isBackgroundContextEnabled()
        
//        System.out.println("[ZestInlineCompletion] Configuration loaded:")
        System.out.println("  - inlineCompletionEnabled: $inlineCompletionEnabled")
        System.out.println("  - autoTriggerEnabled: $autoTriggerEnabled")
        System.out.println("  - backgroundContextEnabled: $backgroundContextEnabled")
        
        logger.info("Loaded configuration: inlineCompletion=$inlineCompletionEnabled, autoTrigger=$autoTriggerEnabled, backgroundContext=$backgroundContextEnabled")
    }
    
    /**
     * Update configuration from ConfigurationManager
     * Call this when settings change
     */
    fun updateConfiguration() {
//        System.out.println("[ZestInlineCompletion] Updating configuration...")
        loadConfiguration()
        
        // If inline completion is disabled, clear any current completion
        if (!inlineCompletionEnabled) {
//            System.out.println("[ZestInlineCompletion] Inline completion disabled, clearing current completion")
            clearCurrentCompletion()
        }
        
        // Clear cache when configuration changes to ensure fresh results
        clearCache()
    }
    
    /**
     * Request inline completion at the specified position
     */
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false) {
//        System.out.println("[ZestInlineCompletion] provideInlineCompletion called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - manually: $manually")
        System.out.println("  - editor: ${editor.document.text.length} chars")
        System.out.println("  - activeRequestId: $activeRequestId")
        System.out.println("  - currentCompletion: ${currentCompletion != null}")
        System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
        
        // Check if project is ready
        if (project.isDisposed || !project.isInitialized) {
//            System.out.println("[ZestInlineCompletion] Project not ready - disposed: ${project.isDisposed}, initialized: ${project.isInitialized}")
            logger.debug("Project not ready, ignoring completion request")
            return
        }
        
        // Check if editor is still valid
        if (editor.isDisposed) {
//            System.out.println("[ZestInlineCompletion] Editor is disposed, ignoring request")
            return
        }
        
        // Block all completion requests during acceptance
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Currently accepting a completion, blocking all new requests")
            return
        }
        
        // Check cooldown period unless manually triggered
        if (!manually) {
            val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
//                System.out.println("[ZestInlineCompletion] In cooldown period (${timeSinceAccept}ms < ${ACCEPTANCE_COOLDOWN_MS}ms), ignoring automatic request")
                return
            }
        }
        
        scope.launch {
            val requestId = requestGeneration.incrementAndGet()
//            System.out.println("[ZestInlineCompletion] Generated requestId: $requestId")
            
            logger.debug("Requesting completion at offset $offset, manually=$manually, requestId=$requestId")
            
            // Check if inline completion is enabled at all
            if (!inlineCompletionEnabled && !manually) {
//                System.out.println("[ZestInlineCompletion] Inline completion is disabled and not manual, ignoring request")
                logger.debug("Inline completion is disabled, ignoring request")
                return@launch
            }
            
            // Use mutex to ensure only one request is processed at a time
//            System.out.println("[ZestInlineCompletion] Acquiring completion mutex for request $requestId...")
            completionMutex.withLock {
//                System.out.println("[ZestInlineCompletion] Mutex acquired for request $requestId")
                
                // Check if this is still the latest request
                if (requestId < (activeRequestId ?: 0)) {
//                    System.out.println("[ZestInlineCompletion] Request $requestId is outdated (activeRequestId=$activeRequestId), skipping")
                    logger.debug("Request $requestId is outdated, skipping")
                    return@withLock
                }
                
                activeRequestId = requestId
//                System.out.println("[ZestInlineCompletion] Set activeRequestId to $requestId")
                
                // Cancel any existing completion request
                currentCompletionJob?.let {
//                    System.out.println("[ZestInlineCompletion] Cancelling existing completion job: ${it.isActive}")
                    it.cancel()
                }
//                System.out.println("[ZestInlineCompletion] Clearing current completion...")
                clearCurrentCompletion()
                
                // Check if auto-trigger is disabled and this is not manual
                if (!autoTriggerEnabled && !manually) {
//                    System.out.println("[ZestInlineCompletion] Auto-trigger disabled and not manual, ignoring request")
                    logger.debug("Auto-trigger disabled, ignoring automatic request")
                    activeRequestId = null
                    return@withLock
                }
                
                // Notify listeners that we're loading
//                System.out.println("[ZestInlineCompletion] Notifying loading state: true")
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)
                
                // Start completion request
                currentCompletionJob = scope.launch {
//                    System.out.println("[ZestInlineCompletion] Starting completion job for request $requestId")
                    
                    // Generate a new completion ID for metrics tracking
                val completionId = UUID.randomUUID().toString()
                currentCompletionId = completionId
                    
                    try {
//                        System.out.println("[ZestInlineCompletion] Building completion context...")
                        val context = buildCompletionContext(editor, offset, manually)
                        if (context == null) {
//                            System.out.println("[ZestInlineCompletion] Failed to build completion context - returned null")
                            logger.debug("Failed to build completion context")
                            return@launch
                        }
//                        System.out.println("[ZestInlineCompletion] Context built successfully:")
                        System.out.println("  - prefix: '${context.prefixCode.takeLast(20)}'")
                        System.out.println("  - suffix: '${context.suffixCode.take(20)}'")
                        System.out.println("  - line: ${context.offset}")
                        
                        // Check if this request is still active
                        if (activeRequestId != requestId) {
//                            System.out.println("[ZestInlineCompletion] Request $requestId is no longer active (activeRequestId=$activeRequestId)")
                            logger.debug("Request $requestId is no longer active")
                            return@launch
                        }
                        
                        currentContext = context
//                        System.out.println("[ZestInlineCompletion] Set currentContext")
                        
                        // Track completion requested metric
                        val fileType = context.language
                        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
                        val contextInfo = mapOf(
                            "manually_triggered" to manually,
                            "offset" to context.offset,
                            "file_name" to context.fileName,
                            "prefix_length" to context.prefixCode.length,
                            "suffix_length" to context.suffixCode.length
                        )
                        metricsService.trackCompletionRequested(
                            completionId = completionId,
                            strategy = completionProvider.strategy.name,
                            fileType = fileType,
                            contextInfo = contextInfo
                        )
                        
                        // Check cache first for SIMPLE and LEAN strategies
                        if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE ||
                            completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
                            
//                            System.out.println("[ZestInlineCompletion] Checking cache for ${completionProvider.strategy} strategy...")
                            val cached = getCachedCompletion(context, editor)
                            if (cached != null) {
//                                System.out.println("[ZestInlineCompletion] Using cached completion")
                                
                                // For both SIMPLE and LEAN, use full completion (they have different Tab behaviors)
                                val completionToShow = cached.fullCompletion
                                
                                // Store the FULL completion for acceptance
                                currentCompletion = cached.fullCompletion
                                
                                // Create a synthetic completion list for display
                                val displayCompletion = ZestInlineCompletionList.single(completionToShow)
                                handleCompletionResponse(editor, context, displayCompletion, requestId)
                                return@launch
                            }
                        }
                        
                        // Use background context if enabled
                        if (backgroundContextEnabled) {
//                            System.out.println("[ZestInlineCompletion] Background context is enabled")
                            logger.debug("Background context is enabled, including additional context")
                            // TODO: Implement background context gathering here
                        }
                        
//                        System.out.println("[ZestInlineCompletion] Requesting completion from provider...")
                        val startTime = System.currentTimeMillis()
                        val completions = completionProvider.requestCompletion(context)
                        val elapsed = System.currentTimeMillis() - startTime
//                        System.out.println("[ZestInlineCompletion] Provider returned in ${elapsed}ms:")
                        System.out.println("  - completions: ${completions}")
                        System.out.println("  - isEmpty: ${completions?.isEmpty()}")
                        System.out.println("  - size: ${completions?.items?.size}")
                        completions?.items?.firstOrNull()?.let {
                            System.out.println("  - first item text: '${it.insertText.take(50)}...'")
                            System.out.println("  - first item range: ${it.replaceRange}")
                        }
                        
                        // Check again if this request is still active
                        if (activeRequestId != requestId) {
//                            System.out.println("[ZestInlineCompletion] Request $requestId is no longer active after completion")
                            logger.debug("Request $requestId is no longer active after completion")
                            return@launch
                        }
                        
                        if (currentContext == context) { // Ensure request is still valid
//                            System.out.println("[ZestInlineCompletion] Context still valid, handling response...")
                            
                            // Cache the completion if it's not empty and for cacheable strategies
                            if (completions != null && !completions.isEmpty() && 
                                (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE ||
                                 completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN)) {
                                
                                val firstCompletion = completions.firstItem()!!
//                                System.out.println("[ZestInlineCompletion] Caching completion for future use...")
                                cacheCompletion(context, editor, firstCompletion)
                                
                                // For SIMPLE strategy, show full completion (line-by-line acceptance handled in accept() method)
                                val displayCompletion = completions // Show full completion for all strategies
                                
                                // Store the FULL completion for acceptance
                                currentCompletion = firstCompletion
                                
                                handleCompletionResponse(editor, context, displayCompletion, requestId)
                            } else {
                                // Non-cacheable strategy or empty completion
                                handleCompletionResponse(editor, context, completions, requestId)
                            }
                        } else {
//                            System.out.println("[ZestInlineCompletion] Context changed, ignoring response")
                        }
                    } catch (e: CancellationException) {
//                        System.out.println("[ZestInlineCompletion] Completion request cancelled for request $requestId")
                        logger.debug("Completion request cancelled")
                        throw e
                    } catch (e: Exception) {
//                        System.out.println("[ZestInlineCompletion] Completion request failed with exception: ${e.message}")
                        e.printStackTrace()
                        logger.warn("Completion request failed", e)
                    } finally {
//                        System.out.println("[ZestInlineCompletion] Completion job finished")
                        
                        // For METHOD_REWRITE, keep loading state and activeRequestId active longer
                        if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                            // Normal completion - clear immediately
//                            System.out.println("[ZestInlineCompletion] Normal completion - clearing loading state")
                            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                            
                            if (activeRequestId == requestId) {
//                                System.out.println("[ZestInlineCompletion] Clearing activeRequestId")
                                activeRequestId = null
                            }
                        } else {
                            // METHOD_REWRITE - keep active for longer to allow method rewrite to complete
//                            System.out.println("[ZestInlineCompletion] METHOD_REWRITE mode - keeping state active")
                            
                            // Clear loading state after a delay
                            scope.launch {
                                delay(5000) // 5 seconds should be enough for method rewrite to show UI
                                if (activeRequestId == requestId) {
//                                    System.out.println("[ZestInlineCompletion] Clearing loading state after delay")
                                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                                }
                            }
                            
                            // Clear activeRequestId after a shorter delay
                            scope.launch {
                                delay(2000) // 2 seconds for method rewrite service to start
                                if (activeRequestId == requestId) {
//                                    System.out.println("[ZestInlineCompletion] Clearing activeRequestId after delay")
                                    activeRequestId = null
                                }
                            }
                        }
                    }
                }
            }
//            System.out.println("[ZestInlineCompletion] Released completion mutex")
        }
    }
    
    /**
     * Accept the current completion with line-by-line support
     * Simple approach: accept first line, show remaining lines as new completion
     */
    fun accept(editor: Editor, offset: Int?, type: AcceptType) {
//        System.out.println("[ZestInlineCompletion] Accept called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - type: $type")
        System.out.println("  - currentContext: ${currentContext != null}")
        System.out.println("  - currentCompletion: ${currentCompletion != null}")
        System.out.println("  - strategy: ${completionProvider.strategy}")
        
        // Prevent multiple simultaneous accepts
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Already accepting a completion, ignoring")
            return
        }
        
        val context = currentContext ?: return
        val completion = currentCompletion ?: return
        val actualOffset = offset ?: context.offset
        
        if (actualOffset != context.offset) {
//            System.out.println("[ZestInlineCompletion] Invalid position for acceptance: $actualOffset != ${context.offset}")
            logger.debug("Invalid position for acceptance")
            return
        }
        
        // Handle line-by-line acceptance for Tab in LEAN strategy
        if (type == AcceptType.FULL_COMPLETION && 
            completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
            
            val lines = completion.insertText.lines()
//            System.out.println("[ZestInlineCompletion] Line splitting debug:")
            System.out.println("  - Total completion text: '${completion.insertText}'")
            System.out.println("  - Split into ${lines.size} lines:")
            lines.forEachIndexed { index, line ->
                System.out.println("    [$index]: '${line}'")
            }
            
            if (lines.size > 1) {
//                System.out.println("[ZestInlineCompletion] Multi-line completion in LEAN mode, accepting first line only")
                
                // Accept only the first line
                var firstLine = lines[0]
                val remainingLines = lines.drop(1).filter { it.isNotBlank() } // Filter out empty lines
                
                System.out.println("  - First line to accept: '${firstLine}'")
                System.out.println("  - Remaining lines (${remainingLines.size}): ${remainingLines.map { "'$it'" }}")
                
                // Ensure first line ends with newline if there are remaining lines
                if (remainingLines.isNotEmpty() && !firstLine.endsWith("\n")) {
                    firstLine = "$firstLine\n"
                    System.out.println("  - Added newline to first line: '${firstLine}'")
                }
                
                if (firstLine.isNotEmpty()) {
                    // Set accepting flag
                    isAcceptingCompletion = true
                    
                    // Start acceptance timeout guard
                    startAcceptanceTimeoutGuard()
                    // Track completion accepted metric
                    currentCompletionId?.let { completionId ->
                        val acceptType =type

                        metricsService.trackCompletionAccepted(
                            completionId = completionId,
                            completionContent = firstLine,
                            acceptType = acceptType.name,
                            isAll = remainingLines.isEmpty(),
                            userAction = "tab" // Could be enhanced to track actual key/action
                        )
                    }
                    // Clear current completion before inserting
                    clearCurrentCompletion(remainingLines.isEmpty())
                    
                    ApplicationManager.getApplication().invokeLater {
                        // Insert the first line
                        acceptCompletionText(editor, context, completion, firstLine)
                        
                        // After insertion, show remaining lines if any
                        if (remainingLines.isNotEmpty()) {
                            // Calculate new offset after insertion
                            val newOffset = actualOffset + firstLine.length
                            
                            // Create completion for remaining lines
                            val remainingText = remainingLines.joinToString("\n")
                            System.out.println("  - Remaining text to show: '${remainingText}'")
                            showRemainingLines(editor, newOffset, remainingText, completion)
                            
                            // Reset accepting flag immediately for line-by-line acceptance
                            // since the remaining lines are now displayed as a new completion
                            scope.launch {
                                delay(300) // Small delay to ensure showRemainingLines completes
                                isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()
//                                System.out.println("[ZestInlineCompletion] Reset isAcceptingCompletion=false for line-by-line acceptance")
                                System.out.println("  - timestamp: ${System.currentTimeMillis()}")
                                System.out.println("  - hasCompletion: ${currentCompletion != null}")
                            }
                        } else {
                            // No remaining lines, reset immediately
                            scope.launch {
                                delay(100) // Very short delay
                                isAcceptingCompletion = false
                                cancelAcceptanceTimeoutGuard()
//                                System.out.println("[ZestInlineCompletion] Reset isAcceptingCompletion=false (no remaining lines)")
                            }
                        }
                    }
                }
                return
            }
        }
        
        // Handle traditional acceptance (full completion for SIMPLE strategy, or other accept types)
        val textToInsert = when (type) {
            AcceptType.FULL_COMPLETION -> {
                // For SIMPLE strategy, always accept the FULL completion (traditional behavior)
                if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.SIMPLE) {
                    completion.insertText // Use the full completion text
                } else {
                    calculateAcceptedText(completion.insertText, type)
                }
            }
            else -> calculateAcceptedText(completion.insertText, type)
        }
        
//        System.out.println("[ZestInlineCompletion] Traditional acceptance: '${textToInsert.take(50)}...'")
        
        if (textToInsert.isNotEmpty()) {
            // Set accepting flag BEFORE clearing completion or inserting text
            isAcceptingCompletion = true
            
            // Start acceptance timeout guard
            startAcceptanceTimeoutGuard()
            
            // Clear the completion BEFORE inserting to prevent overlap handling
            clearCurrentCompletion()
            
            ApplicationManager.getApplication().invokeLater {
                acceptCompletionText(editor, context, completion, textToInsert)
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
//                System.out.println("[ZestInlineCompletion] TIMEOUT: Acceptance took too long, auto-resetting state")
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
    private fun showRemainingLines(editor: Editor, newOffset: Int, remainingText: String, originalCompletion: ZestInlineCompletionItem) {
//        System.out.println("[ZestInlineCompletion] showRemainingLines called:")
        System.out.println("  - newOffset: $newOffset")
        System.out.println("  - remainingText: '${remainingText.take(100)}...'")
        
        // Schedule showing remaining lines after a short delay to ensure insertion is complete
        scope.launch {
            delay(200) // Slightly longer delay to ensure all operations complete
            
            ApplicationManager.getApplication().invokeLater {
                try {
                    // Get current cursor position
                    val currentCaretOffset = editor.caretModel.offset
//                    System.out.println("[ZestInlineCompletion] Current caret position: $currentCaretOffset")
                    
                    // Use current cursor position as the starting point for remaining completion
                    val targetOffset = currentCaretOffset
                    
                    // Create new completion item for remaining lines
                    val remainingCompletion = ZestInlineCompletionItem(
                        insertText = remainingText,
                        replaceRange = ZestInlineCompletionItem.Range(targetOffset, targetOffset),
                        confidence = originalCompletion.confidence,
                        metadata = originalCompletion.metadata
                    )
                    
                    // Update current state
                    currentCompletion = remainingCompletion
                    currentContext = CompletionContext.from(editor, targetOffset, manually = true)
                    
                    // Show the remaining completion
//                    System.out.println("[ZestInlineCompletion] Showing remaining completion at current cursor position: $targetOffset")
                    System.out.println("  - Remaining lines: '${remainingText.lines().take(3).joinToString("; ")}'")
                    
                    renderer.show(editor, targetOffset, remainingCompletion, completionProvider.strategy) { renderingContext ->
//                        System.out.println("[ZestInlineCompletion] Remaining completion displayed successfully")
                        project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                    }
                } catch (e: Exception) {
//                    System.out.println("[ZestInlineCompletion] Error showing remaining lines: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss() {
//        System.out.println("[ZestInlineCompletion] Dismiss called")
        logger.debug("Dismissing completion")
        
        // Track completion dismissed metric
        currentCompletionId?.let { completionId ->
            metricsService.trackCompletionDismissed(
                completionId = completionId,
                reason = "user_dismissed"
            )
        }
        
        // IMPORTANT: Reset accepting flag when dismissing
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Resetting isAcceptingCompletion=false on dismiss")
            isAcceptingCompletion = false
            cancelAcceptanceTimeoutGuard()
        }
        
        clearCurrentCompletion()
    }
    
    /**
     * Check if inline completion is visible at the given position
     */
    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        val result = renderer.current?.editor == editor && 
               renderer.current?.offset == offset &&
               currentCompletion != null
//        System.out.println("[ZestInlineCompletion] isInlineCompletionVisibleAt($offset): $result")
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
//            System.out.println("[ZestInlineCompletion] STUCK STATE DETECTED: Force resetting")
            System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
            System.out.println("  - timeSinceAccept: ${timeSinceAccept}ms")
            
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
        return mapOf(
            "isAcceptingCompletion" to isAcceptingCompletion,
            "isProgrammaticEdit" to isProgrammaticEdit,
            "hasCurrentCompletion" to (currentCompletion != null),
            "activeRequestId" to (activeRequestId ?: "null"),
            "lastAcceptedTimestamp" to lastAcceptedTimestamp,
            "timeSinceAccept" to (System.currentTimeMillis() - lastAcceptedTimestamp),
            "strategy" to completionProvider.strategy.name,
            "isEnabled" to inlineCompletionEnabled,
            "autoTrigger" to autoTriggerEnabled
        )
    }
    
    /**
     * Force refresh/clear all completion state - useful for status bar refresh button
     */
    fun forceRefreshState() {
//        System.out.println("[ZestInlineCompletion] Force refresh state requested")
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
                
//                System.out.println("[ZestInlineCompletion] Force reset all flags:")
                System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
                System.out.println("  - isProgrammaticEdit: $isProgrammaticEdit")
                System.out.println("  - activeRequestId: $activeRequestId")
                
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
                
//                System.out.println("[ZestInlineCompletion] Force refresh completed")
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
    
    // Private implementation methods
    
    private suspend fun buildCompletionContext(editor: Editor, offset: Int, manually: Boolean): CompletionContext? {
//        System.out.println("[ZestInlineCompletion] Building completion context on thread: ${Thread.currentThread().name}")
        
        // Check if the project is fully initialized before attempting to build context
        if (project.isDisposed || !project.isInitialized) {
//            System.out.println("[ZestInlineCompletion] Project not ready - disposed: ${project.isDisposed}, initialized: ${project.isInitialized}")
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
//                        System.out.println("[ZestInlineCompletion] Editor is disposed")
                        future.complete(null)
                        return@invokeLater
                    }
                    
                    val caretOffset = editor.caretModel.offset
//                    System.out.println("[ZestInlineCompletion] Editor valid, caret at: $caretOffset, requested: $offset")
                    val context = CompletionContext.from(editor, offset, manually)
//                    System.out.println("[ZestInlineCompletion] Context created: ${context != null}")
                    future.complete(context)
                } catch (e: Exception) {
//                    System.out.println("[ZestInlineCompletion] Editor access failed: ${e.message}")
                    future.complete(null)
                }
            }
            
            // Wait for the result with a timeout
            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
//            System.out.println("[ZestInlineCompletion] Timeout waiting for context build")
            logger.warn("Timeout building completion context")
            null
        } catch (e: Exception) {
//            System.out.println("[ZestInlineCompletion] Failed to build context: ${e.message}")
            logger.warn("Failed to build completion context", e)
            null
        }
    }
    
    private suspend fun handleCompletionResponse(
        editor: Editor,
        context: CompletionContext,
        completions: ZestInlineCompletionList?,
        requestId: Int
    ) {
//        System.out.println("[ZestInlineCompletion] handleCompletionResponse called for request $requestId")
        System.out.println("  - completions null: ${completions == null}")
        System.out.println("  - completions empty: ${completions?.isEmpty()}")
        
        // Use mutex to ensure only one response is processed at a time
//        System.out.println("[ZestInlineCompletion] Acquiring mutex for response handling...")
        completionMutex.withLock {
//            System.out.println("[ZestInlineCompletion] Mutex acquired for response handling")
            
            // Check if this request is still active
            if (activeRequestId != requestId) {
//                System.out.println("[ZestInlineCompletion] Response for request $requestId is stale (activeRequestId=$activeRequestId), ignoring")
                logger.debug("Response for request $requestId is stale, ignoring")
                return
            }
            
            // Check if we're in method rewrite mode FIRST (before checking empty)
            if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
//                System.out.println("[ZestInlineCompletion] Method rewrite mode - inline diff should be shown")
                // Method rewrite mode handles its own UI via inline diff renderer
                // The completion provider already triggered the method rewrite service
                logger.debug("Method rewrite mode - inline diff should be shown")
                // Don't process empty completions for method rewrite - it's expected
                return
            }
            
            if (completions == null || completions.isEmpty()) {
//                System.out.println("[ZestInlineCompletion] No completions available")
                logger.debug("No completions available")
                return
            }
            
            val completion = completions.firstItem()!!
            currentCompletion = completion
//            System.out.println("[ZestInlineCompletion] Set currentCompletion:")
            System.out.println("  - text: '${completion.insertText.take(50)}...'")
            System.out.println("  - range: ${completion.replaceRange}")
            
            // Clear any existing rendering first to prevent duplicates
//            System.out.println("[ZestInlineCompletion] Clearing existing rendering...")
            ApplicationManager.getApplication().invokeAndWait {
//                System.out.println("[ZestInlineCompletion] Hiding renderer on EDT")
                renderer.hide()
            }
            
            ApplicationManager.getApplication().invokeLater {
//                System.out.println("[ZestInlineCompletion] On EDT for rendering, checking if request still active...")
                // Final check if this request is still active
                if (activeRequestId == requestId) {
//                    System.out.println("[ZestInlineCompletion] Request still active, showing completion...")
                    System.out.println("  - editor: ${editor}")
                    System.out.println("  - offset: ${context.offset}")
                    System.out.println("  - strategy: ${completionProvider.strategy}")
                    
                    renderer.show(editor, context.offset, completion, completionProvider.strategy) { renderingContext ->
//                        System.out.println("[ZestInlineCompletion] Renderer callback - completion displayed")
                        System.out.println("  - rendering id: ${renderingContext.id}")
                        System.out.println("  - inlays: ${renderingContext.inlays.size}")
                        System.out.println("  - markups: ${renderingContext.markups.size}")
                        project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                        
                        // Track completion viewed metric
                        currentCompletionId?.let { completionId ->
                            metricsService.trackCompletionViewed(
                                completionId = completionId,
                                completionLength = completion.insertText.length,
                                completionLineCount = completion.insertText.split("\n").size,
                                confidence = completion.confidence
                            )
                        }
                    }
                    logger.debug("Displayed completion: '${completion.insertText.take(50)}'")
                } else {
//                    System.out.println("[ZestInlineCompletion] Request $requestId no longer active on EDT, not showing")
                }
            }
        }
//        System.out.println("[ZestInlineCompletion] Released response handling mutex")
    }
    
    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String
    ) {
//        System.out.println("[ZestInlineCompletion] acceptCompletionText called:")
        System.out.println("  - text: '${textToInsert.take(50)}...'")
        System.out.println("  - range: ${completionItem.replaceRange}")
        
        // Set all protection flags
        isProgrammaticEdit = true
        lastAcceptedTimestamp = System.currentTimeMillis()
        lastAcceptedText = textToInsert
//        System.out.println("[ZestInlineCompletion] Set isProgrammaticEdit=true, timestamp=$lastAcceptedTimestamp")
        
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val startOffset = completionItem.replaceRange.start
                val endOffset = completionItem.replaceRange.end
                
//                System.out.println("[ZestInlineCompletion] Replacing text from $startOffset to $endOffset")
                
                // Replace the text
                document.replaceString(startOffset, endOffset, textToInsert)
                
                // Move cursor to end of inserted text
                val newCaretPosition = startOffset + textToInsert.length
                editor.caretModel.moveToOffset(newCaretPosition)
//                System.out.println("[ZestInlineCompletion] Moved caret to $newCaretPosition")
                
                // Format the inserted text to ensure proper indentation
                formatInsertedText(editor, startOffset, newCaretPosition)
                
                logger.debug("Accepted completion: inserted '$textToInsert' at offset $startOffset")

            }
            
            project.messageBus.syncPublisher(Listener.TOPIC).completionAccepted(AcceptType.FULL_COMPLETION)
            

            
        } finally {
            // Cancel timeout guard since acceptance is completing
            cancelAcceptanceTimeoutGuard()
            
            // Reset flags after a delay to ensure all document change events are processed
            scope.launch {
                // Keep isProgrammaticEdit true for longer
                delay(1000) // 1 second for programmatic edit flag
                isProgrammaticEdit = false
//                System.out.println("[ZestInlineCompletion] Reset isProgrammaticEdit=false after 1000ms delay")
                
                // For LEAN strategy line-by-line acceptance, don't use the long cooldown
                // The accepting flag will be reset by the line-by-line logic instead
                if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN) {
                    // Reset accepting flag after full cooldown period for other strategies
                    delay(ACCEPTANCE_COOLDOWN_MS - 1000) // Remaining cooldown time
                    isAcceptingCompletion = false
//                    System.out.println("[ZestInlineCompletion] Reset isAcceptingCompletion=false after full cooldown")
                }
                // For LEAN strategy, isAcceptingCompletion is managed by line-by-line logic
            }
        }
    }
    
    private fun calculateAcceptedText(completionText: String, type: AcceptType): String {
        val result = when (type) {
            AcceptType.FULL_COMPLETION -> completionText
            AcceptType.NEXT_WORD -> {
                val wordMatch = Regex("\\S+").find(completionText)
                wordMatch?.value ?: ""
            }
            AcceptType.NEXT_LINE -> {
                val firstLine = completionText.lines().firstOrNull() ?: ""
                firstLine
            }
        }
//        System.out.println("[ZestInlineCompletion] calculateAcceptedText:")
        System.out.println("  - type: $type")
        System.out.println("  - result: '${result.take(50)}...'")
        return result
    }
    
    private fun clearCurrentCompletion(isAll: Boolean = true) {
//        System.out.println("[ZestInlineCompletion] clearCurrentCompletion called")
        System.out.println("  - had timer: ${completionTimer != null}")
        System.out.println("  - had job: ${currentCompletionJob != null}")
        System.out.println("  - had context: ${currentContext != null}")
        System.out.println("  - had completion: ${currentCompletion != null}")
        System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
        
        completionTimer?.cancel()
        completionTimer = null
        currentCompletionJob?.cancel()
        currentCompletionJob = null
        currentContext = null
        currentCompletion = null
        if (isAll) {
            currentCompletionId = null // Clear completion ID
        }
        
        // IMPORTANT: Reset accepting flag when clearing completion (unless we're in the middle of line-by-line acceptance)
        // For LEAN strategy, only reset if it's been more than a short delay since acceptance
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (isAcceptingCompletion && (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.LEAN || timeSinceAccept > 1000L)) {
//            System.out.println("[ZestInlineCompletion] Resetting isAcceptingCompletion=false in clearCurrentCompletion")
            isAcceptingCompletion = false
            cancelAcceptanceTimeoutGuard()
        }
        
        // DO NOT clear activeRequestId here - it's managed by the request lifecycle
        // activeRequestId = null  // REMOVED - this was causing the bug!
        
        ApplicationManager.getApplication().invokeLater {
//            System.out.println("[ZestInlineCompletion] Hiding renderer in clearCurrentCompletion")
            renderer.hide()
        }
    }
    
    private fun setupEventListeners() {
//        System.out.println("[ZestInlineCompletion] Setting up event listeners")
        
        // Caret change listener - this is the only listener we need
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // Don't process caret changes during acceptance
                    if (isAcceptingCompletion) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
//                        System.out.println("[ZestInlineCompletion] Caret changed during acceptance, ignoring")
                        System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
                        System.out.println("  - lastAcceptedTimestamp: $lastAcceptedTimestamp")
                        System.out.println("  - currentTime: ${System.currentTimeMillis()}")
                        System.out.println("  - timeSinceAccept: $timeSinceAccept")
                        
                        // Auto-recovery: if accepting state has been stuck for too long, force reset
                        if (timeSinceAccept > 5000L) { // 5 seconds
//                            System.out.println("[ZestInlineCompletion] RECOVERY: Accepting state stuck too long, force resetting")
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
//                            System.out.println("[ZestInlineCompletion] Method rewrite cooldown active (${timeSinceAccept}ms < 2000ms), ignoring caret change")
                            return
                        }
                    }
                    
                    val currentOffset = editor.logicalPositionToOffset(event.newPosition)
                    val context = currentContext
                    
//                    System.out.println("[ZestInlineCompletion] Caret position changed:")
                    System.out.println("  - new offset: $currentOffset")
                    System.out.println("  - context offset: ${context?.offset}")
                    
                    if (context != null) {
                        val offsetDiff = currentOffset - context.offset
                        System.out.println("  - offset difference: $offsetDiff")
                        
                        // More lenient dismissal logic - only dismiss if cursor moved far away
                        // or if user moved backwards (suggesting they want to edit earlier text)
                        val shouldDismiss = when {
                            offsetDiff < 0 -> {
                                // User moved backwards - check if they moved far back
                                kotlin.math.abs(offsetDiff) > 5 // Allow small backward movements
                            }
                            offsetDiff > 100 -> {
                                // User moved too far forward
                                true
                            }
                            offsetDiff > 0 -> {
                                // User moved forward but within reasonable range
                                // Check if the completion is still meaningful at this position
                                val completion = currentCompletion
                                if (completion != null) {
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
                                    
                                    System.out.println("  - user typed since completion: '$userTypedText'")
                                    System.out.println("  - completion starts with: '${completion.insertText.take(20)}...'")
                                    
                                    // Don't dismiss if user is typing text that matches the beginning of completion
                                    if (userTypedText.isNotEmpty() && completion.insertText.startsWith(userTypedText, ignoreCase = true)) {
                                        System.out.println("  - user typing matches completion start, keeping completion")
                                        false // Don't dismiss - user is typing matching text
                                    } else if (userTypedText.length > 20) {
                                        System.out.println("  - user typed too much non-matching text, dismissing")
                                        true // User typed too much non-matching text
                                    } else {
                                        System.out.println("  - user typed some non-matching text, but keeping completion for now")
                                        false // Keep completion for now
                                    }
                                } else {
                                    false // No completion to dismiss
                                }
                            }
                            else -> false // No movement, don't dismiss
                        }
                        
                        System.out.println("  - should dismiss: $shouldDismiss")
                        
                        if (shouldDismiss) {
//                            System.out.println("[ZestInlineCompletion] Caret moved significantly, dismissing completion")
                            logger.debug("Caret moved significantly, dismissing completion")
                            
                            // Track completion dismissed due to caret movement
//                            currentCompletionId?.let { completionId ->
//                                val reason = when {
//                                    offsetDiff < 0 -> "cursor_moved_backward"
//                                    offsetDiff > 100 -> "cursor_moved_far_forward"
//                                    else -> "cursor_moved_typing_mismatch"
//                                }
//                                metricsService.trackCompletionDismissed(
//                                    completionId = completionId,
//                                    reason = reason
//                                )
//                            }
                            
                            clearCurrentCompletion()
                        }
                    }
                    
                    // If auto-trigger is enabled and no completion is active, schedule a new one
                    if (autoTriggerEnabled && currentCompletion == null && activeRequestId == null) {
//                        System.out.println("[ZestInlineCompletion] Caret moved, scheduling new completion")
                        scheduleNewCompletion(editor)
                    }
                }
            }
        })
        
        // Editor selection change listener
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
//                System.out.println("[ZestInlineCompletion] Editor selection changed, clearing completion")
                
                // Track completion dismissed due to editor change
                currentCompletionId?.let { completionId ->
                    metricsService.trackCompletionDismissed(
                        completionId = completionId,
                        reason = "editor_changed"
                    )
                }
                
                // Reset accepting flag when switching editors
                if (isAcceptingCompletion) {
//                    System.out.println("[ZestInlineCompletion] Resetting isAcceptingCompletion=false on editor selection change")
                    isAcceptingCompletion = false
                    cancelAcceptanceTimeoutGuard()
                }
                clearCurrentCompletion()
            }
        })
        
        // Document change listener - reset accepting flag when user types (not during programmatic edits)
        messageBusConnection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
                if (editorManager.selectedTextEditor == editor && !isProgrammaticEdit) {
//                    System.out.println("[ZestInlineCompletion] Document changed by user (non-programmatic)")
                    
                    // If we're accepting and user types something else, cancel the acceptance
                    if (isAcceptingCompletion) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
//                        System.out.println("[ZestInlineCompletion] User typed during acceptance, timeSinceAccept: ${timeSinceAccept}ms")
                        
                        // If it's been more than a brief moment since acceptance, user is typing something new
                        if (timeSinceAccept > 500L) {
//                            System.out.println("[ZestInlineCompletion] Resetting isAcceptingCompletion=false due to user typing")
                            
                            // Track completion declined if we had one
                            currentCompletionId?.let { completionId ->
                                metricsService.trackCompletionDeclined(
                                    completionId = completionId,
                                    reason = "user_typed_during_acceptance"
                                )
                            }
                            
                            isAcceptingCompletion = false
                            cancelAcceptanceTimeoutGuard()
                            clearCurrentCompletion()
                        }
                    } else {
                        // Handle real-time overlap detection for existing completions
                        handleRealTimeOverlap(editor, event)
                    }
                }
            }
        })
        
//        System.out.println("[ZestInlineCompletion] Event listeners setup complete")
    }
    
    /**
     * Handle real-time overlap detection and completion adjustment as user types
     * SIMPLIFIED: Reduce frequency to prevent blinking
     */
    private fun handleRealTimeOverlap(editor: Editor, event: DocumentEvent) {
//        System.out.println("[ZestInlineCompletion] handleRealTimeOverlap called")
        
        // Don't handle overlap during acceptance
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Currently accepting, skipping overlap handling")
            return
        }
        
        // Don't handle overlap during cooldown period
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
//            System.out.println("[ZestInlineCompletion] In cooldown, skipping overlap handling")
            return
        }
        
        val completion = currentCompletion
        val context = currentContext
        
        if (completion == null || context == null) {
//            System.out.println("[ZestInlineCompletion] No completion or context, returning")
            return
        }
        
        // Don't create new jobs if one is already running
        if (currentCompletionJob?.isActive == true) {
//            System.out.println("[ZestInlineCompletion] Completion job already active, skipping overlap handling")
            return
        }
        
        currentCompletionJob = scope.launch {
//            System.out.println("[ZestInlineCompletion] Starting overlap handling job")
            try {
                // LONGER delay to reduce frequency and blinking
                delay(150) // Increased from 50ms to 150ms
                
                val currentOffset = try {
                    val offsetFuture = java.util.concurrent.CompletableFuture<Int>()
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            offsetFuture.complete(editor.caretModel.offset)
                        } catch (e: Exception) {
                            offsetFuture.complete(-1)
                        }
                    }
                    offsetFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
//                    System.out.println("[ZestInlineCompletion] Failed to get caret offset: ${e.message}")
                    -1
                }
                
//                System.out.println("[ZestInlineCompletion] Current offset after delay: $currentOffset")
                
                if (currentOffset == -1) return@launch
                
                // Only handle if cursor is near the completion position (within reasonable range)
                val distance = kotlin.math.abs(currentOffset - context.offset)
//                System.out.println("[ZestInlineCompletion] Distance from original: $distance")
                if (distance > 100) {
//                    System.out.println("[ZestInlineCompletion] Cursor too far, clearing completion")
                    clearCurrentCompletion()
                    return@launch
                }
                
                val documentText = try {
                    val textFuture = java.util.concurrent.CompletableFuture<String>()
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            textFuture.complete(editor.document.text)
                        } catch (e: Exception) {
                            textFuture.complete("")
                        }
                    }
                    textFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
//                    System.out.println("[ZestInlineCompletion] Failed to get document text: ${e.message}")
                    ""
                }
                
                if (documentText.isEmpty()) {
//                    System.out.println("[ZestInlineCompletion] Document text empty, returning")
                    return@launch
                }
                
                // Re-parse the original completion with current document state
                // Use the ORIGINAL response stored in metadata if available, otherwise use current text
                val originalResponse = completion.metadata?.let { meta ->
                    // Store original response in metadata for re-processing
                    meta.reasoning ?: completion.insertText
                } ?: completion.insertText
                
//                System.out.println("[ZestInlineCompletion] Parsing with overlap detection...")
                val adjustedCompletion = responseParser.parseResponseWithOverlapDetection(
                    originalResponse,
                    documentText,
                    currentOffset,
                    strategy = completionProvider.strategy
                )
//                System.out.println("[ZestInlineCompletion] Adjusted completion: '${adjustedCompletion.take(50)}...'")
                
                // Use invokeLater instead of withContext to avoid dispatcher issues
                ApplicationManager.getApplication().invokeLater {
                    // Check if we're still the active completion (not replaced by IntelliJ)
                    if (currentCompletion == completion) {
                        try {
                            // Try to access editor to verify it's still valid
                            editor.caretModel.offset
                            
                            when {
                                adjustedCompletion.isBlank() -> {
//                                    System.out.println("[ZestInlineCompletion] Completion became empty, dismissing")
                                    // No meaningful completion left, dismiss
                                    logger.debug("Completion became empty after overlap detection, dismissing")
                                        // Track completion declined due to user typing
                                        currentCompletionId?.let { completionId ->
                                            metricsService.trackCompletionDeclined(
                                                completionId = completionId,
                                                reason = "user_typed_different"
                                            )
                                        }

                                    
                                    clearCurrentCompletion()
                                }
                                adjustedCompletion != completion.insertText -> {
                                    // Always update if there's a change - don't filter by change ratio
                                    // This prevents completions from disappearing when user types matching characters
//                                    System.out.println("[ZestInlineCompletion] Completion changed, updating display")
                                    logger.debug("Updating completion: '${completion.insertText}' -> '$adjustedCompletion'")
                                    updateDisplayedCompletion(editor, currentOffset, adjustedCompletion)
                                }
                                else -> {
//                                    System.out.println("[ZestInlineCompletion] No change needed")
                                }
                            }
                        } catch (e: Exception) {
//                            System.out.println("[ZestInlineCompletion] Editor disposed during overlap handling: ${e.message}")
                            // Editor is disposed, clear completion
                            clearCurrentCompletion()
                        }
                    } else {
//                        System.out.println("[ZestInlineCompletion] Completion changed during overlap handling")
                    }
                }
            } catch (e: CancellationException) {
//                System.out.println("[ZestInlineCompletion] Overlap handling cancelled")
                // Normal cancellation, don't log
                throw e
            } catch (e: Exception) {
//                System.out.println("[ZestInlineCompletion] Error in overlap handling: ${e.message}")
                logger.debug("Error in real-time overlap handling", e)
                // On error, clear completion to be safe
                ApplicationManager.getApplication().invokeLater {
                    clearCurrentCompletion()
                }
            }
        }
    }
    
    /**
     * Schedule a new completion request with debouncing
     * SIMPLIFIED: Longer delay to prevent conflicts with active completions
     */
    private fun scheduleNewCompletion(editor: Editor) {
//        System.out.println("[ZestInlineCompletion] scheduleNewCompletion called")
        
        // Don't schedule during acceptance
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Currently accepting, not scheduling")
            return
        }
        
        // Cancel any existing timer
        completionTimer?.let {
//            System.out.println("[ZestInlineCompletion] Cancelling existing timer")
            it.cancel()
        }
        
        // Don't schedule if a request is already active
        if (activeRequestId != null) {
//            System.out.println("[ZestInlineCompletion] Request already active (id=$activeRequestId), not scheduling")
            return
        }
        
        // Don't schedule during cooldown period
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
//            System.out.println("[ZestInlineCompletion] In cooldown period, not scheduling")
            return
        }
        
        completionTimer = scope.launch {
//            System.out.println("[ZestInlineCompletion] Timer started, waiting ${AUTO_TRIGGER_DELAY_MS}ms...")
            delay(AUTO_TRIGGER_DELAY_MS)
            
//            System.out.println("[ZestInlineCompletion] Timer fired, checking conditions...")
            System.out.println("  - currentCompletion: ${currentCompletion != null}")
            System.out.println("  - activeRequestId: $activeRequestId")
            System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
            
            // Check if currently accepting
            if (isAcceptingCompletion) {
//                System.out.println("[ZestInlineCompletion] Currently accepting after delay, not triggering")
                return@launch
            }
            
            // Final check for cooldown period
            val currentTimeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (currentTimeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
//                System.out.println("[ZestInlineCompletion] Still in cooldown after delay, not triggering")
                return@launch
            }
            
            // Check again if no completion is active and no request is in progress
            if (currentCompletion == null && activeRequestId == null) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset
//                        System.out.println("[ZestInlineCompletion] Triggering completion at offset $currentOffset")
                        provideInlineCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {
//                        System.out.println("[ZestInlineCompletion] Failed to trigger completion: ${e.message}")
                        // Editor is disposed, do nothing
                    }
                }
            } else {
//                System.out.println("[ZestInlineCompletion] Conditions not met, not triggering")
            }
        }
    }
    
    /**
     * Update the currently displayed completion with new text
     * ENHANCED: More permissive about small completions to prevent hints from disappearing
     */
    private fun updateDisplayedCompletion(editor: Editor, offset: Int, newText: String) {
//        System.out.println("[ZestInlineCompletion] updateDisplayedCompletion called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - newText: '${newText.take(50)}...'")
        
        // Don't update during acceptance
        if (isAcceptingCompletion) {
//            System.out.println("[ZestInlineCompletion] Currently accepting, not updating display")
            return
        }
        
        // Check if current completion is still valid (not interfered with by IntelliJ)
        val currentRendering = renderer.current
        if (currentRendering != null && currentRendering.editor == editor) {
            // Check if any inlays have been disposed (sign of interference)
            val disposedCount = currentRendering.inlays.count { inlay ->
                try {
                    !inlay.isValid
                } catch (e: Exception) {
                    true // Assume disposed if we can't check
                }
            }
//            System.out.println("[ZestInlineCompletion] Disposed inlays: $disposedCount / ${currentRendering.inlays.size}")
            
            if (disposedCount > 0) {
                System.out.println("Detected IntelliJ interference - inlays disposed, clearing completion")
                clearCurrentCompletion()
                return
            }
        }
        
        // Only update if the new text is significantly different to avoid unnecessary updates
        val currentText = currentCompletion?.insertText ?: ""
        if (newText == currentText) {
//            System.out.println("[ZestInlineCompletion] Text unchanged, no update needed")
            return // No change needed
        }
        
        // ENHANCED: Be more permissive about small completions
        // Keep completions even if they're just 1 character, as long as they're meaningful
        val trimmedText = newText.trim()
        if (trimmedText.isNotEmpty()) {
            // Check if it's a meaningful completion (not just whitespace or punctuation)
            val isMeaningful = trimmedText.any { it.isLetterOrDigit() } || 
                               trimmedText.any { it in "(){}[]<>=\"';:.,!?-+*/\\|&^%$#@" }
            
            if (isMeaningful) {
//                System.out.println("[ZestInlineCompletion] Keeping meaningful completion: '$trimmedText'")
                
                val updatedCompletion = currentCompletion?.copy(
                    insertText = newText,
                    replaceRange = ZestInlineCompletionItem.Range(offset, offset)
                ) ?: return
                
                currentCompletion = updatedCompletion
                currentContext = currentContext?.copy(offset = offset)
                
//                System.out.println("[ZestInlineCompletion] Re-rendering with updated text")
                
                // Re-render with new text - using mutex to prevent concurrent rendering
                scope.launch {
                    completionMutex.withLock {
                        ApplicationManager.getApplication().invokeAndWait {
//                            System.out.println("[ZestInlineCompletion] Hiding renderer for update")
                            renderer.hide()
                        }
                        ApplicationManager.getApplication().invokeLater {
//                            System.out.println("[ZestInlineCompletion] Showing updated completion")
                            renderer.show(editor, offset, updatedCompletion, completionProvider.strategy) { renderingContext ->
//                                System.out.println("[ZestInlineCompletion] Updated completion displayed")
                                project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                            }
                        }
                    }
                }
                return
            }
        }
        
        // Only clear if the text is truly empty or meaningless
//        System.out.println("[ZestInlineCompletion] Text is empty or meaningless, clearing completion")
        clearCurrentCompletion()
    }
    
    /**
     * Format the inserted completion text using IntelliJ's code style
     * This ensures the accepted completion follows the project's formatting rules
     * Enhanced for lean & simple mode with better PSI synchronization
     */
    private fun formatInsertedText(editor: Editor, startOffset: Int, endOffset: Int) {
//        System.out.println("[ZestInlineCompletion] formatInsertedText called: $startOffset to $endOffset")
        try {
            val document = editor.document
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val psiFile = psiDocumentManager.getPsiFile(document)
            
            if (psiFile != null) {
                // Ensure PSI is synchronized with document changes
                psiDocumentManager.commitDocument(document)
                
                // Wait for PSI to be ready
                if (psiDocumentManager.isUncommited(document)) {
//                    System.out.println("[ZestInlineCompletion] Waiting for PSI synchronization...")
                    psiDocumentManager.commitAndRunReadAction {
                        performFormatting(psiFile, startOffset, endOffset)
                    }
                } else {
                    performFormatting(psiFile, startOffset, endOffset)
                }
                
//                System.out.println("[ZestInlineCompletion] Successfully formatted inserted text")
                logger.debug("Formatted inserted text from offset $startOffset to $endOffset")
            } else {
//                System.out.println("[ZestInlineCompletion] Cannot format - PsiFile is null")
                logger.debug("Cannot format inserted text: PsiFile is null")
            }
        } catch (e: Exception) {
//            System.out.println("[ZestInlineCompletion] Failed to format: ${e.message}")
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
//            System.out.println("[ZestInlineCompletion] Formatting operation failed: ${e.message}")
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
                    System.out.println("[ZestCache] Cache hit for key: $cacheKey")
                    return@withLock cached
                } else {
                    System.out.println("[ZestCache] Cache miss - expired or context changed: $cacheKey")
                    completionCache.remove(cacheKey)
                }
            }
            null
        }
    }
    
    /**
     * Cache a completion result for future use
     */
    private suspend fun cacheCompletion(context: CompletionContext, editor: Editor, fullCompletion: ZestInlineCompletionItem) {
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
                    contextHash = contextHash
                )
                
                completionCache[cacheKey] = cached
                System.out.println("[ZestCache] Cached completion for key: $cacheKey")
                System.out.println("  - Full text: '${fullCompletion.insertText.take(50)}...'")
                System.out.println("  - First line: '${firstLine.take(50)}...'")
                
                // Clean up old cache entries if we exceed max size
                if (completionCache.size > MAX_CACHE_SIZE) {
                    cleanupCache()
                }
            } catch (e: Exception) {
                System.out.println("[ZestCache] Failed to cache completion: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up expired or excess cache entries
     */
    private fun cleanupCache() {
        // This method is called from within cacheMutex.withLock, so no additional locking needed
        val expiredKeys = completionCache.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        
        expiredKeys.forEach { completionCache.remove(it) }
        System.out.println("[ZestCache] Cleaned up ${expiredKeys.size} expired entries")
        
        // If still too many, remove oldest entries
        if (completionCache.size > MAX_CACHE_SIZE) {
            val oldestKeys = completionCache.entries
                .sortedBy { it.value.timestamp }
                .take(completionCache.size - MAX_CACHE_SIZE)
                .map { it.key }
            
            oldestKeys.forEach { completionCache.remove(it) }
            System.out.println("[ZestCache] Cleaned up ${oldestKeys.size} oldest entries")
        }
    }
    
    /**
     * Clear the completion cache
     */
    private fun clearCache() {
        scope.launch {
            cacheMutex.withLock {
                completionCache.clear()
                System.out.println("[ZestCache] Cache cleared")
            }
        }
    }
    
    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        return "Cache: ${completionCache.size} entries, " +
               "expired: ${completionCache.values.count { it.isExpired() }}"
    }
    
    override fun dispose() {
//        System.out.println("[ZestInlineCompletion] Disposing service")
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
        FULL_COMPLETION, NEXT_WORD, NEXT_LINE
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
        private const val AUTO_TRIGGER_DELAY_MS = 500L // Increased delay to reduce blinking
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
