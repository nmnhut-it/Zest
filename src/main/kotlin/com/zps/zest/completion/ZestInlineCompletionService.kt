package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
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
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

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
        completionProvider.setStrategy(strategy)
        System.out.println("[ZestInlineCompletion] Strategy updated to: $strategy")
        logger.info("Completion strategy updated to: $strategy")
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
    
    init {
        System.out.println("[ZestInlineCompletion] Initializing service for project: ${project.name}")
        logger.info("Initializing simplified ZestInlineCompletionService")
        
        // Load configuration settings from ConfigurationManager
        loadConfiguration()
        
        setupEventListeners()
        
        // Log initial strategy
        System.out.println("[ZestInlineCompletion] Initial completion strategy: ${completionProvider.strategy}")
        
        System.out.println("[ZestInlineCompletion] Service initialization complete")
    }
    
    /**
     * Load configuration from ConfigurationManager
     */
    private fun loadConfiguration() {
        inlineCompletionEnabled = configManager.isInlineCompletionEnabled()
        autoTriggerEnabled = configManager.isAutoTriggerEnabled()
        backgroundContextEnabled = configManager.isBackgroundContextEnabled()
        
        System.out.println("[ZestInlineCompletion] Configuration loaded:")
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
        System.out.println("[ZestInlineCompletion] Updating configuration...")
        loadConfiguration()
        
        // If inline completion is disabled, clear any current completion
        if (!inlineCompletionEnabled) {
            System.out.println("[ZestInlineCompletion] Inline completion disabled, clearing current completion")
            clearCurrentCompletion()
        }
    }
    
    /**
     * Request inline completion at the specified position
     */
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false) {
        System.out.println("[ZestInlineCompletion] provideInlineCompletion called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - manually: $manually")
        System.out.println("  - editor: ${editor.document.text.length} chars")
        System.out.println("  - activeRequestId: $activeRequestId")
        System.out.println("  - currentCompletion: ${currentCompletion != null}")
        System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
        
        // Block all completion requests during acceptance
        if (isAcceptingCompletion) {
            System.out.println("[ZestInlineCompletion] Currently accepting a completion, blocking all new requests")
            return
        }
        
        // Check cooldown period unless manually triggered
        if (!manually) {
            val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
                System.out.println("[ZestInlineCompletion] In cooldown period (${timeSinceAccept}ms < ${ACCEPTANCE_COOLDOWN_MS}ms), ignoring automatic request")
                return
            }
        }
        
        scope.launch {
            val requestId = requestGeneration.incrementAndGet()
            System.out.println("[ZestInlineCompletion] Generated requestId: $requestId")
            
            logger.debug("Requesting completion at offset $offset, manually=$manually, requestId=$requestId")
            
            // Check if inline completion is enabled at all
            if (!inlineCompletionEnabled && !manually) {
                System.out.println("[ZestInlineCompletion] Inline completion is disabled and not manual, ignoring request")
                logger.debug("Inline completion is disabled, ignoring request")
                return@launch
            }
            
            // Use mutex to ensure only one request is processed at a time
            System.out.println("[ZestInlineCompletion] Acquiring completion mutex for request $requestId...")
            completionMutex.withLock {
                System.out.println("[ZestInlineCompletion] Mutex acquired for request $requestId")
                
                // Check if this is still the latest request
                if (requestId < (activeRequestId ?: 0)) {
                    System.out.println("[ZestInlineCompletion] Request $requestId is outdated (activeRequestId=$activeRequestId), skipping")
                    logger.debug("Request $requestId is outdated, skipping")
                    return@withLock
                }
                
                activeRequestId = requestId
                System.out.println("[ZestInlineCompletion] Set activeRequestId to $requestId")
                
                // Cancel any existing completion request
                currentCompletionJob?.let {
                    System.out.println("[ZestInlineCompletion] Cancelling existing completion job: ${it.isActive}")
                    it.cancel()
                }
                System.out.println("[ZestInlineCompletion] Clearing current completion...")
                clearCurrentCompletion()
                
                // Check if auto-trigger is disabled and this is not manual
                if (!autoTriggerEnabled && !manually) {
                    System.out.println("[ZestInlineCompletion] Auto-trigger disabled and not manual, ignoring request")
                    logger.debug("Auto-trigger disabled, ignoring automatic request")
                    activeRequestId = null
                    return@withLock
                }
                
                // Notify listeners that we're loading
                System.out.println("[ZestInlineCompletion] Notifying loading state: true")
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)
                
                // Start completion request
                currentCompletionJob = scope.launch {
                    System.out.println("[ZestInlineCompletion] Starting completion job for request $requestId")
                    try {
                        System.out.println("[ZestInlineCompletion] Building completion context...")
                        val context = buildCompletionContext(editor, offset, manually)
                        if (context == null) {
                            System.out.println("[ZestInlineCompletion] Failed to build completion context - returned null")
                            logger.debug("Failed to build completion context")
                            return@launch
                        }
                        System.out.println("[ZestInlineCompletion] Context built successfully:")
                        System.out.println("  - prefix: '${context.prefixCode.takeLast(20)}'")
                        System.out.println("  - suffix: '${context.suffixCode.take(20)}'")
                        System.out.println("  - line: ${context.offset}")
//                        System.out.println("  - column: ${context.column}")
                        
                        // Check if this request is still active
                        if (activeRequestId != requestId) {
                            System.out.println("[ZestInlineCompletion] Request $requestId is no longer active (activeRequestId=$activeRequestId)")
                            logger.debug("Request $requestId is no longer active")
                            return@launch
                        }
                        
                        currentContext = context
                        System.out.println("[ZestInlineCompletion] Set currentContext")
                        
                        // Use background context if enabled
                        if (backgroundContextEnabled) {
                            System.out.println("[ZestInlineCompletion] Background context is enabled")
                            logger.debug("Background context is enabled, including additional context")
                            // TODO: Implement background context gathering here
                        }
                        
                        System.out.println("[ZestInlineCompletion] Requesting completion from provider...")
                        val startTime = System.currentTimeMillis()
                        val completions = completionProvider.requestCompletion(context)
                        val elapsed = System.currentTimeMillis() - startTime
                        System.out.println("[ZestInlineCompletion] Provider returned in ${elapsed}ms:")
                        System.out.println("  - completions: ${completions}")
                        System.out.println("  - isEmpty: ${completions?.isEmpty()}")
                        System.out.println("  - size: ${completions?.items?.size}")
                        completions?.items?.firstOrNull()?.let {
                            System.out.println("  - first item text: '${it.insertText.take(50)}...'")
                            System.out.println("  - first item range: ${it.replaceRange}")
                        }
                        
                        // Check again if this request is still active
                        if (activeRequestId != requestId) {
                            System.out.println("[ZestInlineCompletion] Request $requestId is no longer active after completion")
                            logger.debug("Request $requestId is no longer active after completion")
                            return@launch
                        }
                        
                        if (currentContext == context) { // Ensure request is still valid
                            System.out.println("[ZestInlineCompletion] Context still valid, handling response...")
                            handleCompletionResponse(editor, context, completions, requestId)
                        } else {
                            System.out.println("[ZestInlineCompletion] Context changed, ignoring response")
                        }
                    } catch (e: CancellationException) {
                        System.out.println("[ZestInlineCompletion] Completion request cancelled for request $requestId")
                        logger.debug("Completion request cancelled")
                        throw e
                    } catch (e: Exception) {
                        System.out.println("[ZestInlineCompletion] Completion request failed with exception: ${e.message}")
                        e.printStackTrace()
                        logger.warn("Completion request failed", e)
                    } finally {
                        System.out.println("[ZestInlineCompletion] Completion job finished")
                        
                        // For METHOD_REWRITE, keep loading state and activeRequestId active longer
                        if (completionProvider.strategy != ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                            // Normal completion - clear immediately
                            System.out.println("[ZestInlineCompletion] Normal completion - clearing loading state")
                            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                            
                            if (activeRequestId == requestId) {
                                System.out.println("[ZestInlineCompletion] Clearing activeRequestId")
                                activeRequestId = null
                            }
                        } else {
                            // METHOD_REWRITE - keep active for longer to allow method rewrite to complete
                            System.out.println("[ZestInlineCompletion] METHOD_REWRITE mode - keeping state active")
                            
                            // Clear loading state after a delay
                            scope.launch {
                                delay(5000) // 5 seconds should be enough for method rewrite to show UI
                                if (activeRequestId == requestId) {
                                    System.out.println("[ZestInlineCompletion] Clearing loading state after delay")
                                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                                }
                            }
                            
                            // Clear activeRequestId after a shorter delay
                            scope.launch {
                                delay(2000) // 2 seconds for method rewrite service to start
                                if (activeRequestId == requestId) {
                                    System.out.println("[ZestInlineCompletion] Clearing activeRequestId after delay")
                                    activeRequestId = null
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("[ZestInlineCompletion] Released completion mutex")
        }
    }
    
    /**
     * Accept the current completion
     */
    fun accept(editor: Editor, offset: Int?, type: AcceptType) {
        System.out.println("[ZestInlineCompletion] Accept called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - type: $type")
        System.out.println("  - currentContext: ${currentContext != null}")
        System.out.println("  - currentCompletion: ${currentCompletion != null}")
        
        // Prevent multiple simultaneous accepts
        if (isAcceptingCompletion) {
            System.out.println("[ZestInlineCompletion] Already accepting a completion, ignoring")
            return
        }
        
        val context = currentContext ?: return
        val completion = currentCompletion ?: return
        
        val actualOffset = offset ?: context.offset
        
        if (actualOffset != context.offset) {
            System.out.println("[ZestInlineCompletion] Invalid position for acceptance: $actualOffset != ${context.offset}")
            logger.debug("Invalid position for acceptance")
            return
        }
        
        val textToInsert = calculateAcceptedText(completion.insertText, type)
        System.out.println("[ZestInlineCompletion] Calculated text to insert: '${textToInsert.take(50)}...'")
        
        if (textToInsert.isNotEmpty()) {
            // Set accepting flag BEFORE clearing completion or inserting text
            isAcceptingCompletion = true
            
            // Clear the completion BEFORE inserting to prevent overlap handling
            clearCurrentCompletion()
            
            ApplicationManager.getApplication().invokeLater {
                acceptCompletionText(editor, context, completion, textToInsert)
            }
        }
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss() {
        System.out.println("[ZestInlineCompletion] Dismiss called")
        logger.debug("Dismissing completion")
        clearCurrentCompletion()
    }
    
    /**
     * Check if inline completion is visible at the given position
     */
    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        val result = renderer.current?.editor == editor && 
               renderer.current?.offset == offset &&
               currentCompletion != null
        System.out.println("[ZestInlineCompletion] isInlineCompletionVisibleAt($offset): $result")
        return result
    }
    
    /**
     * Get the current completion item being displayed
     */
    fun getCurrentCompletion(): ZestInlineCompletionItem? {
        return currentCompletion
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
        System.out.println("[ZestInlineCompletion] Building completion context on thread: ${Thread.currentThread().name}")
        return try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                System.out.println("[ZestInlineCompletion] Switched to EDT: ${ApplicationManager.getApplication().isDispatchThread}")
                try {
                    // Try to access editor to check if it's still valid
                    val caretOffset = editor.caretModel.offset
                    System.out.println("[ZestInlineCompletion] Editor valid, caret at: $caretOffset, requested: $offset")
                    val context = CompletionContext.from(editor, offset, manually)
                    System.out.println("[ZestInlineCompletion] Context created: ${context != null}")
                    context
                } catch (e: Exception) {
                    System.out.println("[ZestInlineCompletion] Editor access failed: ${e.message}")
                    // Editor is likely disposed or invalid
                    null
                }
            }
        } catch (e: Exception) {
            System.out.println("[ZestInlineCompletion] Failed to build context: ${e.message}")
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
        System.out.println("[ZestInlineCompletion] handleCompletionResponse called for request $requestId")
        System.out.println("  - completions null: ${completions == null}")
        System.out.println("  - completions empty: ${completions?.isEmpty()}")
        
        // Use mutex to ensure only one response is processed at a time
        System.out.println("[ZestInlineCompletion] Acquiring mutex for response handling...")
        completionMutex.withLock {
            System.out.println("[ZestInlineCompletion] Mutex acquired for response handling")
            
            // Check if this request is still active
            if (activeRequestId != requestId) {
                System.out.println("[ZestInlineCompletion] Response for request $requestId is stale (activeRequestId=$activeRequestId), ignoring")
                logger.debug("Response for request $requestId is stale, ignoring")
                return
            }
            
            // Check if we're in method rewrite mode FIRST (before checking empty)
            if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                System.out.println("[ZestInlineCompletion] Method rewrite mode - inline diff should be shown")
                // Method rewrite mode handles its own UI via inline diff renderer
                // The completion provider already triggered the method rewrite service
                logger.debug("Method rewrite mode - inline diff should be shown")
                // Don't process empty completions for method rewrite - it's expected
                return
            }
            
            if (completions == null || completions.isEmpty()) {
                System.out.println("[ZestInlineCompletion] No completions available")
                logger.debug("No completions available")
                return
            }
            
            val completion = completions.firstItem()!!
            currentCompletion = completion
            System.out.println("[ZestInlineCompletion] Set currentCompletion:")
            System.out.println("  - text: '${completion.insertText.take(50)}...'")
            System.out.println("  - range: ${completion.replaceRange}")
            
            // Clear any existing rendering first to prevent duplicates
            System.out.println("[ZestInlineCompletion] Clearing existing rendering...")
            ApplicationManager.getApplication().invokeAndWait {
                System.out.println("[ZestInlineCompletion] Hiding renderer on EDT")
                renderer.hide()
            }
            
            ApplicationManager.getApplication().invokeLater {
                System.out.println("[ZestInlineCompletion] On EDT for rendering, checking if request still active...")
                // Final check if this request is still active
                if (activeRequestId == requestId) {
                    System.out.println("[ZestInlineCompletion] Request still active, showing completion...")
                    System.out.println("  - editor: ${editor}")
                    System.out.println("  - offset: ${context.offset}")
                    System.out.println("  - strategy: ${completionProvider.strategy}")
                    
                    renderer.show(editor, context.offset, completion, completionProvider.strategy) { renderingContext ->
                        System.out.println("[ZestInlineCompletion] Renderer callback - completion displayed")
                        System.out.println("  - rendering id: ${renderingContext.id}")
                        System.out.println("  - inlays: ${renderingContext.inlays.size}")
                        System.out.println("  - markups: ${renderingContext.markups.size}")
                        project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                    }
                    logger.debug("Displayed completion: '${completion.insertText.take(50)}'")
                } else {
                    System.out.println("[ZestInlineCompletion] Request $requestId no longer active on EDT, not showing")
                }
            }
        }
        System.out.println("[ZestInlineCompletion] Released response handling mutex")
    }
    
    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String
    ) {
        System.out.println("[ZestInlineCompletion] acceptCompletionText called:")
        System.out.println("  - text: '${textToInsert.take(50)}...'")
        System.out.println("  - range: ${completionItem.replaceRange}")
        
        // Set all protection flags
        isProgrammaticEdit = true
        lastAcceptedTimestamp = System.currentTimeMillis()
        lastAcceptedText = textToInsert
        System.out.println("[ZestInlineCompletion] Set isProgrammaticEdit=true, timestamp=$lastAcceptedTimestamp")
        
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val startOffset = completionItem.replaceRange.start
                val endOffset = completionItem.replaceRange.end
                
                System.out.println("[ZestInlineCompletion] Replacing text from $startOffset to $endOffset")
                
                // Replace the text
                document.replaceString(startOffset, endOffset, textToInsert)
                
                // Move cursor to end of inserted text
                val newCaretPosition = startOffset + textToInsert.length
                editor.caretModel.moveToOffset(newCaretPosition)
                System.out.println("[ZestInlineCompletion] Moved caret to $newCaretPosition")
                
                // Format the inserted text to ensure proper indentation
                formatInsertedText(editor, startOffset, newCaretPosition)
                
                logger.debug("Accepted completion: inserted '$textToInsert' at offset $startOffset")
            }
            
            project.messageBus.syncPublisher(Listener.TOPIC).completionAccepted(AcceptType.FULL_COMPLETION)
            
        } finally {
            // Reset flags after a delay to ensure all document change events are processed
            scope.launch {
                // Keep isProgrammaticEdit true for longer
                delay(1000) // 1 second for programmatic edit flag
                isProgrammaticEdit = false
                System.out.println("[ZestInlineCompletion] Reset isProgrammaticEdit=false after 1000ms delay")
                
                // Reset accepting flag after full cooldown period
                delay(ACCEPTANCE_COOLDOWN_MS - 1000) // Remaining cooldown time
                isAcceptingCompletion = false
                System.out.println("[ZestInlineCompletion] Reset isAcceptingCompletion=false after full cooldown")
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
        System.out.println("[ZestInlineCompletion] calculateAcceptedText:")
        System.out.println("  - type: $type")
        System.out.println("  - result: '${result.take(50)}...'")
        return result
    }
    
    private fun clearCurrentCompletion() {
        System.out.println("[ZestInlineCompletion] clearCurrentCompletion called")
        System.out.println("  - had timer: ${completionTimer != null}")
        System.out.println("  - had job: ${currentCompletionJob != null}")
        System.out.println("  - had context: ${currentContext != null}")
        System.out.println("  - had completion: ${currentCompletion != null}")
        
        completionTimer?.cancel()
        completionTimer = null
        currentCompletionJob?.cancel()
        currentCompletionJob = null
        currentContext = null
        currentCompletion = null
        
        // DO NOT clear activeRequestId here - it's managed by the request lifecycle
        // activeRequestId = null  // REMOVED - this was causing the bug!
        
        ApplicationManager.getApplication().invokeLater {
            System.out.println("[ZestInlineCompletion] Hiding renderer in clearCurrentCompletion")
            renderer.hide()
        }
    }
    
    private fun setupEventListeners() {
        System.out.println("[ZestInlineCompletion] Setting up event listeners")
        
        // Caret change listener - this is the only listener we need
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // Don't process caret changes during acceptance
                    if (isAcceptingCompletion) {
                        System.out.println("[ZestInlineCompletion] Caret changed during acceptance, ignoring")
                        return
                    }
                    
                    // For method rewrite, also check if we're in the post-accept cooldown
                    if (completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
                        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
                        if (timeSinceAccept < 2000L) { // 2 seconds cooldown for method rewrite
                            System.out.println("[ZestInlineCompletion] Method rewrite cooldown active (${timeSinceAccept}ms < 2000ms), ignoring caret change")
                            return
                        }
                    }
                    
                    val currentOffset = editor.logicalPositionToOffset(event.newPosition)
                    val context = currentContext
                    
                    System.out.println("[ZestInlineCompletion] Caret position changed:")
                    System.out.println("  - new offset: $currentOffset")
                    System.out.println("  - context offset: ${context?.offset}")
                    System.out.println("  - should dismiss: ${context != null && currentOffset != context.offset}")
                    
                    if (context != null && currentOffset != context.offset) {
                        System.out.println("[ZestInlineCompletion] Caret moved, dismissing completion")
                        logger.debug("Caret moved, dismissing completion")
                        clearCurrentCompletion()
                    }
                    
                    // If auto-trigger is enabled and no completion is active, schedule a new one
                    if (autoTriggerEnabled && currentCompletion == null && activeRequestId == null) {
                        System.out.println("[ZestInlineCompletion] Caret moved, scheduling new completion")
                        scheduleNewCompletion(editor)
                    }
                }
            }
        })
        
        // Editor selection change listener
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                System.out.println("[ZestInlineCompletion] Editor selection changed, clearing completion")
                clearCurrentCompletion()
            }
        })
        
        System.out.println("[ZestInlineCompletion] Event listeners setup complete")
    }
    
    /**
     * Handle real-time overlap detection and completion adjustment as user types
     * SIMPLIFIED: Reduce frequency to prevent blinking
     */
    private fun handleRealTimeOverlap(editor: Editor, event: DocumentEvent) {
        System.out.println("[ZestInlineCompletion] handleRealTimeOverlap called")
        
        // Don't handle overlap during acceptance
        if (isAcceptingCompletion) {
            System.out.println("[ZestInlineCompletion] Currently accepting, skipping overlap handling")
            return
        }
        
        // Don't handle overlap during cooldown period
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
            System.out.println("[ZestInlineCompletion] In cooldown, skipping overlap handling")
            return
        }
        
        val completion = currentCompletion
        val context = currentContext
        
        if (completion == null || context == null) {
            System.out.println("[ZestInlineCompletion] No completion or context, returning")
            return
        }
        
        // Don't create new jobs if one is already running
        if (currentCompletionJob?.isActive == true) {
            System.out.println("[ZestInlineCompletion] Completion job already active, skipping overlap handling")
            return
        }
        
        currentCompletionJob = scope.launch {
            System.out.println("[ZestInlineCompletion] Starting overlap handling job")
            try {
                // LONGER delay to reduce frequency and blinking
                delay(150) // Increased from 50ms to 150ms
                
                val currentOffset = withContext(Dispatchers.Main) { 
                    try {
                        editor.caretModel.offset
                    } catch (e: Exception) {
                        System.out.println("[ZestInlineCompletion] Failed to get caret offset: ${e.message}")
                        -1 // Editor is disposed or invalid
                    }
                }
                
                System.out.println("[ZestInlineCompletion] Current offset after delay: $currentOffset")
                
                if (currentOffset == -1) return@launch
                
                // Only handle if cursor is near the completion position (within reasonable range)
                val distance = kotlin.math.abs(currentOffset - context.offset)
                System.out.println("[ZestInlineCompletion] Distance from original: $distance")
                if (distance > 100) {
                    System.out.println("[ZestInlineCompletion] Cursor too far, clearing completion")
                    clearCurrentCompletion()
                    return@launch
                }
                
                val documentText = withContext(Dispatchers.Main) { 
                    try {
                        editor.document.text
                    } catch (e: Exception) {
                        System.out.println("[ZestInlineCompletion] Failed to get document text: ${e.message}")
                        "" // Editor is disposed or invalid
                    }
                }
                
                if (documentText.isEmpty()) {
                    System.out.println("[ZestInlineCompletion] Document text empty, returning")
                    return@launch
                }
                
                // Re-parse the original completion with current document state
                // Use the ORIGINAL response stored in metadata if available, otherwise use current text
                val originalResponse = completion.metadata?.let { meta ->
                    // Store original response in metadata for re-processing
                    meta.reasoning ?: completion.insertText
                } ?: completion.insertText
                
                System.out.println("[ZestInlineCompletion] Parsing with overlap detection...")
                val adjustedCompletion = responseParser.parseResponseWithOverlapDetection(
                    originalResponse,
                    documentText,
                    currentOffset,
                    strategy = completionProvider.strategy
                )
                System.out.println("[ZestInlineCompletion] Adjusted completion: '${adjustedCompletion.take(50)}...'")
                
                withContext(Dispatchers.Main) {
                    // Check if we're still the active completion (not replaced by IntelliJ)
                    if (currentCompletion == completion) {
                        try {
                            // Try to access editor to verify it's still valid
                            editor.caretModel.offset
                            
                            when {
                                adjustedCompletion.isBlank() -> {
                                    System.out.println("[ZestInlineCompletion] Completion became empty, dismissing")
                                    // No meaningful completion left, dismiss
                                    logger.debug("Completion became empty after overlap detection, dismissing")
                                    clearCurrentCompletion()
                                }
                                adjustedCompletion != completion.insertText -> {
                                    // Only update if change is significant to reduce blinking
                                    val changeRatio = adjustedCompletion.length.toDouble() / completion.insertText.length
                                    System.out.println("[ZestInlineCompletion] Change ratio: $changeRatio")
                                    
                                    if (changeRatio > 0.2) { // Reduced threshold from 0.3 to 0.2 for better overlap handling
                                        System.out.println("[ZestInlineCompletion] Significant change, updating display")
                                        logger.debug("Updating completion: '${completion.insertText}' -> '$adjustedCompletion'")
                                        updateDisplayedCompletion(editor, currentOffset, adjustedCompletion)
                                    } else {
                                        // Only clear if the remaining text is truly meaningless
                                        if (adjustedCompletion.trim().length < 2) {
                                            System.out.println("[ZestInlineCompletion] Text too small, clearing")
                                            clearCurrentCompletion()
                                        } else {
                                            // Keep the completion even if it's small - user might want it
                                            System.out.println("[ZestInlineCompletion] Keeping small completion")
                                            logger.debug("Keeping small completion: '$adjustedCompletion'")
                                            updateDisplayedCompletion(editor, currentOffset, adjustedCompletion)
                                        }
                                    }
                                }
                                else -> {
                                    System.out.println("[ZestInlineCompletion] No change needed")
                                }
                            }
                        } catch (e: Exception) {
                            System.out.println("[ZestInlineCompletion] Editor disposed during overlap handling: ${e.message}")
                            // Editor is disposed, clear completion
                            clearCurrentCompletion()
                        }
                    } else {
                        System.out.println("[ZestInlineCompletion] Completion changed during overlap handling")
                    }
                }
            } catch (e: CancellationException) {
                System.out.println("[ZestInlineCompletion] Overlap handling cancelled")
                // Normal cancellation, don't log
                throw e
            } catch (e: Exception) {
                System.out.println("[ZestInlineCompletion] Error in overlap handling: ${e.message}")
                logger.debug("Error in real-time overlap handling", e)
                // On error, clear completion to be safe
                withContext(Dispatchers.Main) {
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
        System.out.println("[ZestInlineCompletion] scheduleNewCompletion called")
        
        // Don't schedule during acceptance
        if (isAcceptingCompletion) {
            System.out.println("[ZestInlineCompletion] Currently accepting, not scheduling")
            return
        }
        
        // Cancel any existing timer
        completionTimer?.let {
            System.out.println("[ZestInlineCompletion] Cancelling existing timer")
            it.cancel()
        }
        
        // Don't schedule if a request is already active
        if (activeRequestId != null) {
            System.out.println("[ZestInlineCompletion] Request already active (id=$activeRequestId), not scheduling")
            return
        }
        
        // Don't schedule during cooldown period
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        if (timeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
            System.out.println("[ZestInlineCompletion] In cooldown period, not scheduling")
            return
        }
        
        completionTimer = scope.launch {
            System.out.println("[ZestInlineCompletion] Timer started, waiting ${AUTO_TRIGGER_DELAY_MS}ms...")
            delay(AUTO_TRIGGER_DELAY_MS)
            
            System.out.println("[ZestInlineCompletion] Timer fired, checking conditions...")
            System.out.println("  - currentCompletion: ${currentCompletion != null}")
            System.out.println("  - activeRequestId: $activeRequestId")
            System.out.println("  - isAcceptingCompletion: $isAcceptingCompletion")
            
            // Check if currently accepting
            if (isAcceptingCompletion) {
                System.out.println("[ZestInlineCompletion] Currently accepting after delay, not triggering")
                return@launch
            }
            
            // Final check for cooldown period
            val currentTimeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
            if (currentTimeSinceAccept < ACCEPTANCE_COOLDOWN_MS) {
                System.out.println("[ZestInlineCompletion] Still in cooldown after delay, not triggering")
                return@launch
            }
            
            // Check again if no completion is active and no request is in progress
            if (currentCompletion == null && activeRequestId == null) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset
                        System.out.println("[ZestInlineCompletion] Triggering completion at offset $currentOffset")
                        provideInlineCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {
                        System.out.println("[ZestInlineCompletion] Failed to trigger completion: ${e.message}")
                        // Editor is disposed, do nothing
                    }
                }
            } else {
                System.out.println("[ZestInlineCompletion] Conditions not met, not triggering")
            }
        }
    }
    
    /**
     * Update the currently displayed completion with new text
     * SIMPLIFIED: Reduce blinking by avoiding hide/show cycles
     */
    private fun updateDisplayedCompletion(editor: Editor, offset: Int, newText: String) {
        System.out.println("[ZestInlineCompletion] updateDisplayedCompletion called:")
        System.out.println("  - offset: $offset")
        System.out.println("  - newText: '${newText.take(50)}...'")
        
        // Don't update during acceptance
        if (isAcceptingCompletion) {
            System.out.println("[ZestInlineCompletion] Currently accepting, not updating display")
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
            System.out.println("[ZestInlineCompletion] Disposed inlays: $disposedCount / ${currentRendering.inlays.size}")
            
            if (disposedCount > 0) {
                System.out.println("Detected IntelliJ interference - inlays disposed, clearing completion")
                clearCurrentCompletion()
                return
            }
        }
        
        // Only update if the new text is significantly different to avoid unnecessary updates
        val currentText = currentCompletion?.insertText ?: ""
        if (newText == currentText) {
            System.out.println("[ZestInlineCompletion] Text unchanged, no update needed")
            return // No change needed
        }
        
        // Don't clear small but meaningful completions
        if (newText.trim().length >= 2) {
            // Always update if we have at least 2 characters of meaningful content
            val updatedCompletion = currentCompletion?.copy(
                insertText = newText,
                replaceRange = ZestInlineCompletionItem.Range(offset, offset)
            ) ?: return
            
            currentCompletion = updatedCompletion
            currentContext = currentContext?.copy(offset = offset)
            
            System.out.println("[ZestInlineCompletion] Re-rendering with updated text")
            
            // Re-render with new text - using mutex to prevent concurrent rendering
            scope.launch {
                completionMutex.withLock {
                    ApplicationManager.getApplication().invokeAndWait {
                        System.out.println("[ZestInlineCompletion] Hiding renderer for update")
                        renderer.hide()
                    }
                    ApplicationManager.getApplication().invokeLater {
                        System.out.println("[ZestInlineCompletion] Showing updated completion")
                        renderer.show(editor, offset, updatedCompletion, completionProvider.strategy) { renderingContext ->
                            System.out.println("[ZestInlineCompletion] Updated completion displayed")
                            project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
                        }
                    }
                }
            }
            return
        }
        
        // Only clear if remaining text is truly too small
        if (newText.trim().length < 2) {
            System.out.println("[ZestInlineCompletion] Text too small, clearing completion")
            clearCurrentCompletion()
            return
        }
    }
    
    /**
     * Format the inserted completion text using IntelliJ's code style
     * This ensures the accepted completion follows the project's formatting rules
     * Enhanced for lean & simple mode with better PSI synchronization
     */
    private fun formatInsertedText(editor: Editor, startOffset: Int, endOffset: Int) {
        System.out.println("[ZestInlineCompletion] formatInsertedText called: $startOffset to $endOffset")
        try {
            val document = editor.document
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val psiFile = psiDocumentManager.getPsiFile(document)
            
            if (psiFile != null) {
                // Ensure PSI is synchronized with document changes
                psiDocumentManager.commitDocument(document)
                
                // Wait for PSI to be ready
                if (psiDocumentManager.isUncommited(document)) {
                    System.out.println("[ZestInlineCompletion] Waiting for PSI synchronization...")
                    psiDocumentManager.commitAndRunReadAction {
                        performFormatting(psiFile, startOffset, endOffset)
                    }
                } else {
                    performFormatting(psiFile, startOffset, endOffset)
                }
                
                System.out.println("[ZestInlineCompletion] Successfully formatted inserted text")
                logger.debug("Formatted inserted text from offset $startOffset to $endOffset")
            } else {
                System.out.println("[ZestInlineCompletion] Cannot format - PsiFile is null")
                logger.debug("Cannot format inserted text: PsiFile is null")
            }
        } catch (e: Exception) {
            System.out.println("[ZestInlineCompletion] Failed to format: ${e.message}")
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
            System.out.println("[ZestInlineCompletion] Formatting operation failed: ${e.message}")
            logger.debug("Formatting operation failed", e)
        }
    }
    
    override fun dispose() {
        System.out.println("[ZestInlineCompletion] Disposing service")
        logger.info("Disposing simplified ZestInlineCompletionService")
        activeRequestId = null
        completionTimer?.cancel()
        scope.cancel()
        renderer.hide()
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
