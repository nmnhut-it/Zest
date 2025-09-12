package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.notification.NotificationType
import com.zps.zest.ConfigurationManager
import com.zps.zest.completion.cache.CompletionCache
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.state.*
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Simplified inline completion service using the new self-managing state machine.
 * This version focuses on clean state management and reliable completion display.
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    
    companion object {
        private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
        
        @JvmStatic
        fun getInstance(project: Project): ZestInlineCompletionService {
            return project.getService(ZestInlineCompletionService::class.java)
        }
        
        // For compatibility with Java code that might try to notify configuration changes
        @JvmStatic
        fun notifyConfigurationChanged() {
            // Configuration changes are now handled internally by each service instance
            // This method is kept for backward compatibility but does nothing
        }
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Core components
    private val completionProvider = ZestCompletionProvider(project)
    private val renderer = ZestInlineCompletionRenderer()
    private val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
    private val configManager = ConfigurationManager.getInstance(project)
    private val completionCache = CompletionCache()
    
    // New state machine with self-managing states
    private lateinit var stateMachine: CompletionStateMachine
    private lateinit var stateContext: StateContextAdapter
    
    // Trigger manager for debouncing (initialized after state machine)
    private lateinit var triggerManager: com.zps.zest.completion.trigger.SimplifiedTriggerManager
    
    // Configuration
    private var inlineCompletionEnabled = true
    private var autoTriggerEnabled = true
    private var debugLoggingEnabled = true
    
    // Tracking
    @Volatile private var lastRequestId: Int? = null
    
    init {
        setupStateMachine()
        setupTriggerManager()
        loadConfiguration()
        setupEventListeners()
        
        logger.info("ZestInlineCompletionService initialized for project: ${project.name}")
    }
    
    private fun setupStateMachine() {
        stateContext = createStateContext()
        stateMachine = CompletionStateMachine(stateContext)
        stateContext.setStateMachine(stateMachine)
        addStateMachineListener()
    }
    
    private fun createStateContext(): StateContextAdapter {
        return StateContextAdapter(
            project = project,
            renderer = renderer,
            metricsService = metricsService,
            scope = scope,
            getEditor = { FileEditorManager.getInstance(project).selectedTextEditor },
            getStrategy = { completionProvider.strategy },
            updateStatusBarCallback = { message -> updateStatusBarText(message) },
            logCallback = { msg, tag, level -> log(msg, tag, level) }
        )
    }
    
    private fun addStateMachineListener() {
        stateMachine.addListener(object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionState,
                newState: CompletionState,
                event: CompletionEvent
            ) {
                logStateTransition(oldState, newState, event)
                publishStateChange(newState)
            }
        })
    }
    
    private fun logStateTransition(
        oldState: CompletionState,
        newState: CompletionState,
        event: CompletionEvent
    ) {
        log("State transition: $oldState -> $newState (${event.javaClass.simpleName})", "StateMachine")
    }
    
    private fun publishStateChange(newState: CompletionState) {
        val isLoading = newState is CompletionState.Requesting
        val shouldPublish = when (newState) {
            is CompletionState.Requesting -> true
            is CompletionState.Ready, is CompletionState.Displaying, CompletionState.Idle -> true
            else -> false
        }
        
        if (shouldPublish) {
            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(isLoading)
        }
    }
    
    private fun setupTriggerManager() {
        // Initialize the trigger manager with scope and state checks
        triggerManager = com.zps.zest.completion.trigger.SimplifiedTriggerManager(
            scope = scope,
            canTrigger = { stateMachine.canTrigger },
            isAccepting = { stateMachine.currentState is CompletionState.Accepting }
        )
        
        // Set the trigger callback
        triggerManager.setTriggerCallback { editor, offset, manually ->
            requestCompletion(editor, offset, manually)
        }
        
        // Configure trigger settings - reduced delays due to pre-populated context
        triggerManager.setConfiguration(
            autoTriggerEnabled = autoTriggerEnabled,
            primaryDelayMs = 1000L,  // Reduced from 300ms - context is pre-cached
            secondaryDelayMs = 1500L // Reduced from 800ms - only cursor context needed
        )
    }
    
    private fun setupEventListeners() {
        val connection = project.messageBus.connect()
        subscribeToCaretChanges(connection)
        subscribeToDocumentChanges(connection)
        subscribeToEditorChanges(connection)
    }
    
    private fun subscribeToCaretChanges(connection: com.intellij.util.messages.MessageBusConnection) {
        connection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (isSelectedEditor(editor)) {
                    handleCaretChange(editor, event)
                }
            }
        })
    }
    
    private fun subscribeToDocumentChanges(connection: com.intellij.util.messages.MessageBusConnection) {
        connection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(
                document: com.intellij.openapi.editor.Document,
                editor: Editor,
                event: DocumentEvent
            ) {
                if (isSelectedEditor(editor)) {
                    handleDocumentChange(editor, event)
                }
            }
        })
    }
    
    private fun subscribeToEditorChanges(connection: com.intellij.util.messages.MessageBusConnection) {
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                handleEditorChange()
            }
        })
    }
    
    private fun isSelectedEditor(editor: Editor): Boolean {
        return FileEditorManager.getInstance(project).selectedTextEditor == editor
    }
    
    private fun handleCaretChange(editor: Editor, event: CaretEvent) {
        // Dismiss completion if caret moved significantly
        val currentOffset = editor.caretModel.offset
        val completionOffset = stateMachine.currentContext?.offset ?: -1
        
        if (completionOffset != -1 && kotlin.math.abs(currentOffset - completionOffset) > 0) {
            log("Caret moved away from completion position", "Caret")
            stateMachine.dismiss()
        }
    }
    
    private fun handleDocumentChange(editor: Editor, event: DocumentEvent) {
        // Document changed - dismiss current completion and potentially trigger new one
        stateMachine.dismiss()
        
        if (autoTriggerEnabled && inlineCompletionEnabled) {
            log("Document changed, scheduling new completion", "Document")
            triggerManager.scheduleCompletionAfterActivity(editor, "document_changed")
        }
    }
    
    private fun handleEditorChange() {
        // Editor changed - dismiss any active completion
        stateMachine.dismiss()
    }
    
    /**
     * Request a completion at the specified position
     */
    fun requestCompletion(editor: Editor, offset: Int, manually: Boolean) {
        if (!canRequestCompletion(manually)) {
            return
        }
        
        scope.launch {
            performCompletionRequest(editor, offset, manually)
        }
    }
    
    private fun canRequestCompletion(manually: Boolean): Boolean {
        if (!inlineCompletionEnabled && !manually) {
            log("Inline completion disabled", "Request")
            return false
        }
        return true
    }
    
    private suspend fun performCompletionRequest(
        editor: Editor,
        offset: Int,
        manually: Boolean
    ) {
        try {
            val context = CompletionContext.from(editor, offset, manually)
            val requestId = initializeRequest(context) ?: return
            
            val cached = checkCache(context)
            if (cached != null) {
                log("Using cached completion", "Cache")
                handleCompletionResult(cached, requestId)
                return
            }
            
            fetchAndHandleCompletion(context, requestId)
            
        } catch (e: CancellationException) {
            handleRequestCancellation()
        } catch (e: Exception) {
            handleRequestError(e)
        }
    }
    
    private fun initializeRequest(context: CompletionContext): Int? {
        val requestId = stateMachine.generateRequestId()
        lastRequestId = requestId
        
        if (!stateMachine.handleEvent(CompletionEvent.RequestCompletion(requestId, context))) {
            log("Cannot start completion request - invalid state", "Request")
            return null
        }
        return requestId
    }
    
    private suspend fun fetchAndHandleCompletion(
        context: CompletionContext,
        requestId: Int
    ) {
        val result = withTimeoutOrNull(30_000L) {
            completionProvider.requestCompletion(context, requestId)
        }
        
        if (result != null) {
            cacheCompletionResult(result, context)
            handleCompletionResult(result, requestId)
        } else {
            handleRequestTimeout()
        }
    }
    
    private fun cacheCompletionResult(
        result: ZestInlineCompletionList,
        context: CompletionContext
    ) {
        result.firstItem()?.let { completion ->
            scope.launch {
                completionCache.put(
                    generateCacheKey(context),
                    context.toString().hashCode().toString(),
                    completion
                )
            }
        }
    }
    
    private fun handleRequestCancellation() {
        log("Completion request cancelled", "Request")
        stateMachine.handleEvent(CompletionEvent.Dismiss)
    }
    
    private fun handleRequestError(e: Exception) {
        logger.error("Error requesting completion", e)
        stateMachine.handleEvent(CompletionEvent.Error(e.message ?: "Unknown error"))
    }
    
    private fun handleRequestTimeout() {
        log("Completion request timed out", "Request")
        stateMachine.handleEvent(CompletionEvent.Error("Request timed out"))
    }
    
    private fun handleCompletionResult(result: ZestInlineCompletionList, requestId: Int) {
        val completion = result.firstItem()
        if (completion == null || completion.insertText.isBlank()) {
            log("No valid completion in result", "Request")
            stateMachine.handleEvent(CompletionEvent.Error("No valid completion"))
            return
        }
        
        // Only process if this is still the active request
        if (requestId != lastRequestId) {
            log("Ignoring stale completion for request $requestId", "Request")
            return
        }
        
        // Send completion to state machine
        stateMachine.handleEvent(CompletionEvent.CompletionReceived(completion, requestId))
    }
    
    /**
     * Accept the current completion
     */
    fun acceptCompletion(acceptType: String = "TAB") {
        if (!stateMachine.canAccept) {
            log("Cannot accept - no completion ready", "Accept")
            return
        }
        
        stateMachine.acceptCompletion(acceptType)
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss() {
        stateMachine.dismiss()
    }
    
    /**
     * Check if completion can be accepted
     */
    fun canAcceptCompletion(): Boolean = stateMachine.canAccept
    
    /**
     * Get current completion if available
     */
    fun getCurrentCompletion(): ZestInlineCompletionItem? = stateMachine.currentCompletion
    
    /**
     * Check if we're currently showing a completion at the given position
     */
    fun isCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        val context = stateMachine.currentContext ?: return false
        return stateMachine.canAccept && context.offset == offset
    }
    
    // Configuration methods
    
    private fun loadConfiguration() {
        inlineCompletionEnabled = configManager.isInlineCompletionEnabled
        autoTriggerEnabled = configManager.isAutoTriggerEnabled
        debugLoggingEnabled = true // Can be made configurable
        
        completionProvider.setDebugLogging(debugLoggingEnabled)
        renderer.setDebugLogging(debugLoggingEnabled)
    }
    
    fun updateConfiguration() {
        loadConfiguration()
        updateTriggerConfiguration()
        dismissIfDisabled()
    }
    
    private fun updateTriggerConfiguration() {
        triggerManager.setConfiguration(
            autoTriggerEnabled = autoTriggerEnabled,
            primaryDelayMs = 50L,
            secondaryDelayMs = 150L
        )
    }
    
    private fun dismissIfDisabled() {
        if (!inlineCompletionEnabled) {
            dismiss()
        }
    }
    
    fun setCompletionStrategy(strategy: ZestCompletionProvider.CompletionStrategy) {
        completionProvider.setStrategy(strategy)
        scope.launch {
            completionCache.clear()
        }
        dismiss()
    }
    
    fun getCompletionStrategy(): ZestCompletionProvider.CompletionStrategy = completionProvider.strategy
    
    // Compatibility methods for status bar widget
    
    fun isEnabled(): Boolean = inlineCompletionEnabled
    
    fun getDetailedState(): Map<String, Any> {
        return mapOf(
            "currentRequestState" to getStateString(),
            "hasCompletion" to hasCurrentCompletion(),
            "requestsInLastMinute" to 0,
            "timeSinceLastRequest" to 0L,
            "completionOffset" to getCompletionOffset()
        )
    }
    
    private fun getStateString(): String {
        return when (val state = stateMachine.currentState) {
            is CompletionState.Idle -> "IDLE"
            is CompletionState.Requesting -> "REQUESTING"
            is CompletionState.Ready -> "READY"
            is CompletionState.Displaying -> "DISPLAYING"
            is CompletionState.Accepting -> "ACCEPTING"
        }
    }
    
    private fun hasCurrentCompletion(): Boolean {
        return stateMachine.currentCompletion != null
    }
    
    private fun getCompletionOffset(): Int {
        return stateMachine.currentContext?.offset ?: -1
    }
    
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to completionCache.size(),
            "cacheHits" to 0, // TODO: track cache hits
            "cacheMisses" to 0 // TODO: track cache misses
        )
    }
    
    fun forceRefreshState() {
        dismiss()
    }
    
    fun checkAndFixStuckState(): Boolean {
        // V2 state machine shouldn't get stuck, but check anyway
        val state = stateMachine.currentState
        if (state is CompletionState.Requesting) {
            // Check if we've been requesting for too long
            // For now, just return false
            return false
        }
        return false
    }
    
    // Helper methods
    
    private suspend fun checkCache(context: CompletionContext): ZestInlineCompletionList? {
        val key = generateCacheKey(context)
        val contextHash = context.toString().hashCode().toString()
        return completionCache.get(key, contextHash)?.let { cached ->
            ZestInlineCompletionList.single(cached.fullCompletion)
        }
    }
    
    private fun generateCacheKey(context: CompletionContext): String {
        return "${context.fileName}:${context.offset}:${context.prefixCode.takeLast(50)}"
    }
    
    // Add cache size method for compatibility
    private fun com.zps.zest.completion.cache.CompletionCache.size(): Int {
        // This is a simple implementation - the actual cache class might need updating
        return 0 // TODO: implement actual size tracking in CompletionCache
    }
    
    private fun updateStatusBarText(message: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                statusBar?.info = message
                
                // Also update the widget if available
                try {
                    val widget = statusBar?.getWidget("ZestCompletionStatus")
                    if (widget is com.zps.zest.completion.ui.ZestCompletionStatusBarWidget) {
                        widget.updateDebugStatus(message)
                    }
                } catch (e: Exception) {
                    // Widget might not be available
                }
            }
        } catch (e: Exception) {
            // Ignore UI update errors
        }
    }
    
    private fun log(message: String, tag: String = "Service", level: Int = 0) {
        if (debugLoggingEnabled || level == 0) {
            val prefix = when (level) {
                0 -> ""
                1 -> "[VERBOSE]"
                else -> "[DEBUG]"
            }
            println("$prefix[$tag] $message")
            
            if (level == 0) {
                logger.debug("[$tag] $message")
            }
        }
    }
    
    override fun dispose() {
        scope.cancel()
        triggerManager.dispose()
        renderer.hide()
        logger.info("ZestInlineCompletionService disposed")
    }
    
    // Message bus interface
    interface Listener {
        companion object {
            val TOPIC = com.intellij.util.messages.Topic.create(
                "Zest.InlineCompletion",
                Listener::class.java
            )
        }
        
        fun loadingStateChanged(isLoading: Boolean)
        fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext)
    }
}