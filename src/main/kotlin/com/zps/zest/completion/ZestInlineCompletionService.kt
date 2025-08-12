package com.zps.zest.completion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.messages.Topic
import com.zps.zest.ConfigurationManager
import com.zps.zest.completion.cache.CompletionCache
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.display.CompletionDisplayCoordinator
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.state.CompletionStateMachine
import com.zps.zest.completion.trigger.CompletionTriggerManager
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Clean, focused completion service using state machine architecture
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    
    companion object {
        private const val CONTEXT_BUILD_TIMEOUT_SECONDS = 2L
        private const val ACCEPTANCE_DELAY_MS = 500L
        private const val REMAINING_LINES_DELAY_MS = 300L
        
        @JvmStatic
        fun notifyConfigurationChanged() {
            // Configuration change notification logic
            // This is called from ConfigurationManager when settings change
        }
    }

    private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
    
    // Debug logging infrastructure (preserved for metrics)
    private var debugLoggingEnabled = false
    private val notificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("Zest Completion Debug")

    // Core components
    private val stateMachine = CompletionStateMachine()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val triggerManager = CompletionTriggerManager(stateMachine, scope)
    private val completionProvider = ZestCompletionProvider(project)
    private val renderer = ZestInlineCompletionRenderer()
    val currentRendererContext get() = renderer.current
    private val displayCoordinator = CompletionDisplayCoordinator(project, stateMachine, renderer, scope)
    private val completionCache = CompletionCache()
    private val completionMutex = Mutex()

    // Services
    private val configManager = ConfigurationManager.getInstance(project)
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }

    // Configuration
    private var inlineCompletionEnabled = false
    private var continuousCompletionEnabled = true

    init {
        logger.info("Initializing clean ZestInlineCompletionService")
        setupComponents()
        loadConfiguration()
        setupEventListeners()
        log("Initialization complete - Strategy: ${completionProvider.strategy}")
    }

    // =================================
    // Debug Logging (preserved for metrics)
    // =================================
    
    private fun log(message: String, tag: String = "ZestService", level: Int = 0) {
        if (debugLoggingEnabled && (level == 0 || level <= 1)) {
            val prefix = if (level > 0) "[VERBOSE]" else ""
            println("$prefix[$tag] $message")
            logger.debug("$prefix[$tag] $message")
        }
    }

    fun setDebugLogging(enabled: Boolean, verbose: Boolean = false) {
        debugLoggingEnabled = enabled
        log("Debug logging ${if (enabled) "enabled" else "disabled"}")
    }

    private fun showDebugBalloon(title: String, content: String, type: NotificationType) {
        if (debugLoggingEnabled) {
            updateStatusBarText("$title: $content")
        }
    }

    private fun updateStatusBarText(message: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                statusBar?.info = message

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
            log("Status update failed: ${e.message}")
        }
    }

    // =================================
    // Public API
    // =================================

    fun setCompletionStrategy(strategy: ZestCompletionProvider.CompletionStrategy) {
        val oldStrategy = completionProvider.strategy
        completionProvider.setStrategy(strategy)
        
        logger.info("Completion strategy updated to: $strategy")
        log("Strategy changed: $oldStrategy -> $strategy", "Strategy")
        
        if (oldStrategy != strategy) {
            scope.launch { 
                completionCache.clear()
                log("Cache cleared due to strategy change", "Cache")
            }
        }
        
        showDebugBalloon("Strategy Changed", "Switched to $strategy mode", NotificationType.INFORMATION)
    }

    fun getCompletionStrategy(): ZestCompletionProvider.CompletionStrategy = completionProvider.strategy

    fun accept(editor: Editor, offset: Int?, type: AcceptType) {
        log("=== accept() called ===", "Accept")
        log("  type: $type, offset: $offset", "Accept")

        val completion = stateMachine.currentCompletion ?: return
        val context = stateMachine.currentContext ?: return
        val actualOffset = offset ?: context.offset

        if (actualOffset != context.offset) {
            log("Invalid position for acceptance", "Accept")
            return
        }

        if (!stateMachine.handleEvent(CompletionStateMachine.Event.StartAccepting(type.name))) {
            log("Cannot start acceptance - invalid state transition", "Accept")
            return
        }

        if (type == AcceptType.CTRL_TAB_COMPLETION && 
            completionProvider.strategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
            log("LEAN line-by-line acceptance", "Accept")
            handleLineByLineAcceptance(editor, completion, context, actualOffset)
        } else {
            val textToInsert = calculateAcceptedText(completion.insertText, type)
            if (textToInsert.isNotEmpty()) {
                acceptCompletionText(editor, context, completion, textToInsert, type)
            }
        }
    }

    fun dismiss() {
        log("=== dismiss() called ===", "Dismiss")
        
        stateMachine.currentCompletion?.let { completion ->
            metricsService.trackCompletionDeclined(completion.completionId, "esc_pressed")
        }
        
        displayCoordinator.dismissCompletion("user_dismissed")
        showDebugBalloon("Dismissed", "Completion dismissed", NotificationType.INFORMATION)
    }

    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean =
        displayCoordinator.isCompletionVisibleAt(editor, offset)

    fun getCurrentCompletion(): ZestInlineCompletionItem? = stateMachine.currentCompletion

    fun updateConfiguration() {
        loadConfiguration()
        scope.launch { completionCache.clear() }
        if (!inlineCompletionEnabled) {
            displayCoordinator.dismissCompletion("inline_completion_disabled")
        }
    }

    // =================================
    // Component Setup
    // =================================

    private fun setupComponents() {
        setupStateMachineListeners()
        setupTriggerManager()
        setupDisplayCoordinator()
    }

    private fun setupStateMachineListeners() {
        stateMachine.addListener(object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                log("State: ${oldState.javaClass.simpleName} -> ${newState.javaClass.simpleName} (${event.javaClass.simpleName})", "State")
                
                when (newState) {
                    is CompletionStateMachine.State.Requesting -> {
                        project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)
                        updateStatusBarText("Requesting completion...")
                        log("Notifying loading state changed (true)", "Event")
                    }
                    is CompletionStateMachine.State.Displaying -> {
                        project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                        updateStatusBarText("Completion ready - Press Tab")
                        log("Notifying loading state changed (false)", "Event")
                    }
                    is CompletionStateMachine.State.Idle -> {
                        project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                        updateStatusBarText("")
                        log("Notifying loading state changed (false)", "Event")
                    }
                    is CompletionStateMachine.State.Error -> {
                        project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                        updateStatusBarText("Error: ${newState.message}")
                        showDebugBalloon("Completion Error", newState.message, NotificationType.ERROR)
                    }
                    is CompletionStateMachine.State.Accepting -> {
                        updateStatusBarText("Accepting completion...")
                    }
                    is CompletionStateMachine.State.Cooldown -> {
                        updateStatusBarText("Cooldown...")
                    }
                    is CompletionStateMachine.State.Waiting -> {
                        updateStatusBarText("Waiting...")
                    }
                }
            }
        })
    }

    private fun setupTriggerManager() {
        triggerManager.setTriggerCallback { editor, offset, manually ->
            requestCompletion(editor, offset, manually)
        }
        updateTriggerConfiguration()
    }

    private fun setupDisplayCoordinator() {
        displayCoordinator.setDisplayListener(object : CompletionDisplayCoordinator.CompletionDisplayListener {
            override fun onCompletionDisplayed(completion: ZestInlineCompletionItem, context: CompletionContext) {
                log("Completion displayed: ${completion.insertText.take(30)}...", "Display")
                showDebugBalloon("Completion Ready", "Press Tab to accept", NotificationType.INFORMATION)
            }

            override fun onCompletionDismissed(completion: ZestInlineCompletionItem, reason: String) {
                log("Completion dismissed: $reason", "Display")
            }

            override fun onDisplayError(reason: String) {
                log("Display error: $reason", "Display")
                showDebugBalloon("Display Error", reason, NotificationType.ERROR)
            }
        })
    }

    private fun loadConfiguration() {
        inlineCompletionEnabled = configManager.isInlineCompletionEnabled
        continuousCompletionEnabled = configManager.isContinuousCompletionEnabled
        updateTriggerConfiguration()
        
        logger.info("Configuration loaded: inline=$inlineCompletionEnabled, continuous=$continuousCompletionEnabled")
    }

    private fun updateTriggerConfiguration() {
        triggerManager.setConfiguration(
            autoTriggerEnabled = configManager.isAutoTriggerEnabled,
            aggressiveRetryEnabled = true
        )
    }

    // =================================
    // Event Handling
    // =================================

    private fun setupEventListeners() {
        val connection = project.messageBus.connect()

        connection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (FileEditorManager.getInstance(project).selectedTextEditor == editor) {
                    handleCaretChange(editor, event)
                }
            }
        })

        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                log("Editor selection changed", "Editor")
                handleEditorChange()
            }
        })

        connection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
                if (FileEditorManager.getInstance(project).selectedTextEditor == editor) {
                    handleDocumentChanged(editor)
                }
            }
        })
    }

    private fun handleCaretChange(editor: Editor, event: CaretEvent) {
        if (stateMachine.isAccepting) {
            log("Processing during acceptance, ignoring caret change", "Caret", 1)
            return
        }

        val currentOffset = editor.logicalPositionToOffset(event.newPosition)
        val context = stateMachine.currentContext ?: return

        if (stateMachine.isDisplaying) {
            val offsetDiff = currentOffset - context.offset
            val shouldDismiss = offsetDiff < -3 || offsetDiff > 100 || 
                (offsetDiff > 0 && !displayCoordinator.updateCompletionForTyping(editor, currentOffset))

            if (shouldDismiss) {
                val reason = when {
                    offsetDiff < 0 -> "cursor_moved_backward"
                    offsetDiff > 100 -> "cursor_moved_far_forward"
                    else -> "cursor_moved_typing_mismatch"
                }
                log("Caret moved significantly, dismissing completion: $reason", "Caret")
                displayCoordinator.dismissCompletion(reason)
            }
        }
    }

    private fun handleEditorChange() {
        stateMachine.currentCompletion?.let { completion ->
            metricsService.trackCompletionDismissed(completion.completionId, "editor_changed")
        }
        stateMachine.forceReset()
        displayCoordinator.forceHide()
        triggerManager.cancelAllTimers()
    }

    private fun handleDocumentChanged(editor: Editor) {
        if (stateMachine.isAccepting) {
            log("Document changed during acceptance, ignoring", "Document", 1)
            return
        }

        log("Document changed (user typing)", "Document", 1)

        if (stateMachine.isDisplaying) {
            val currentOffset = editor.caretModel.offset
            if (!displayCoordinator.updateCompletionForTyping(editor, currentOffset)) {
                return
            }
        }

        if (shouldScheduleCompletion()) {
            log("Scheduling new completion after typing", "Document", 1)
            triggerManager.scheduleCompletionAfterActivity(editor, "document_changed")
        }
    }

    private fun shouldScheduleCompletion(): Boolean =
        inlineCompletionEnabled && !stateMachine.isAccepting && 
        !stateMachine.isInCooldown && stateMachine.canTriggerCompletion()

    // =================================
    // Core Completion Logic
    // =================================

    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean) {
        requestCompletion(editor, offset, manually)
    }
    
    private fun requestCompletion(editor: Editor, offset: Int, manually: Boolean) {
        log("=== requestCompletion called ===", "Request")
        log("  offset: $offset, manually: $manually", "Request")

        if (!inlineCompletionEnabled && !manually) {
            log("Inline completion disabled", "Config")
            return
        }

        if (project.isDisposed || !project.isInitialized || editor.isDisposed) {
            log("Project or editor not ready", "Project")
            return
        }

        scope.launch {
            completionMutex.withLock {
                val requestId = stateMachine.generateRequestId()
                log("Generated request ID: $requestId", "Request")

                if (!stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId))) {
                    log("Cannot start requesting - invalid state transition", "Request")
                    return@withLock
                }

                try {
                    val completionId = UUID.randomUUID().toString()
                    log("Generated completion ID: $completionId", "Metrics")
                    
                    val context = buildCompletionContext(editor, offset, manually) ?: run {
                        log("Failed to build completion context", "Context")
                        stateMachine.handleEvent(CompletionStateMachine.Event.Error("Failed to build context"))
                        return@withLock
                    }
                    log("Context built successfully: ${context.fileName} @ ${context.offset}", "Context")

                    val cached = checkCache(context, editor)
                    if (cached != null) {
                        log("Cache HIT - using cached completion", "Cache")
                        showDebugBalloon("Cache Hit", "Using cached completion", NotificationType.INFORMATION)
                        displayCoordinator.displayCompletion(editor, context, cached, requestId, completionProvider.strategy)
                        return@withLock
                    } else {
                        log("Cache MISS - will request from provider", "Cache")
                    }

                    trackCompletionRequested(completionId, context, manually)

                    completionProvider.setCurrentRequestId(requestId)
                    log("Calling completion provider...", "Provider")
                    showDebugBalloon("Requesting Completion", "Calling ${completionProvider.strategy} provider", NotificationType.INFORMATION)
                    
                    val startTime = System.currentTimeMillis()
                    val completions = completionProvider.requestCompletion(context, requestId, completionId)
                    val elapsed = System.currentTimeMillis() - startTime

                    log("Provider returned in ${elapsed}ms", "Provider")

                    val insertText = completions?.firstItem()?.insertText ?: ""
                    if (completions == null) {
                        log("Provider returned NULL completions!", "Provider")
                        showDebugBalloon("No Completion", "Provider returned null", NotificationType.WARNING)
                    } else if (completions.isEmpty()) {
                        log("Provider returned EMPTY completions!", "Provider")
                        showDebugBalloon("Empty Completion", "Provider returned empty list", NotificationType.WARNING)
                    } else {
                        log("Got completion text: '${insertText.take(50)}...' (${insertText.length} chars)", "Provider")
                    }

                    trackCompletionCompleted(completionId, insertText, elapsed)

                    completions?.firstItem()?.let { 
                        log("Caching completion for future use", "Cache")
                        cacheCompletion(context, editor, it) 
                    }
                    
                    displayCoordinator.displayCompletion(editor, context, completions, requestId, completionProvider.strategy)

                } catch (e: CancellationException) {
                    log("Request cancelled", "Cancel")
                    stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss("cancelled"))
                } catch (e: Exception) {
                    log("Request failed: ${e.message}", "Error")
                    stateMachine.handleEvent(CompletionStateMachine.Event.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    // =================================
    // Acceptance Logic
    // =================================

    private fun handleLineByLineAcceptance(
        editor: Editor,
        completion: ZestInlineCompletionItem,
        context: CompletionContext,
        offset: Int
    ) {
        val lines = completion.insertText.lines()
        if (lines.size <= 1) {
            acceptCompletionText(editor, context, completion, completion.insertText, AcceptType.CTRL_TAB_COMPLETION)
            return
        }

        log("Line-by-line acceptance - ${lines.size} lines", "Accept")

        var firstLine = lines[0]
        val remainingLines = lines.drop(1).filter { it.isNotBlank() }

        log("First line: '$firstLine'", "Accept", 1)
        log("Remaining lines: ${remainingLines.size}", "Accept", 1)

        if (remainingLines.isNotEmpty() && !firstLine.endsWith("\n")) {
            firstLine = "$firstLine\n"
            log("Added newline to first line", "Accept", 1)
        }

        acceptCompletionText(editor, context, completion, firstLine, AcceptType.CTRL_TAB_COMPLETION)

        if (remainingLines.isNotEmpty()) {
            scope.launch {
                delay(REMAINING_LINES_DELAY_MS)
                ApplicationManager.getApplication().invokeLater {
                    log("Showing remaining lines at offset ${offset + firstLine.length}", "Accept")
                    showRemainingLines(editor, offset + firstLine.length, remainingLines, completion)
                }
            }
        }
    }

    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completion: ZestInlineCompletionItem,
        textToInsert: String,
        acceptType: AcceptType
    ) {
        log("=== acceptCompletionText ===", "AcceptText")
        log("  textToInsert: '${textToInsert.take(50)}...' (${textToInsert.length} chars)", "AcceptText")

        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val startOffset = completion.replaceRange.start
                val endOffset = completion.replaceRange.end

                log("Replacing text: offset $startOffset-$endOffset", "AcceptText", 1)
                document.replaceString(startOffset, endOffset, textToInsert)
                
                val newCaretPosition = startOffset + textToInsert.length
                editor.caretModel.moveToOffset(newCaretPosition)
                log("Text inserted, cursor at $newCaretPosition", "AcceptText", 1)
                
                formatInsertedText(editor, startOffset, newCaretPosition)
            }

            trackCompletionAccepted(completion, textToInsert, acceptType)
            stateMachine.handleEvent(CompletionStateMachine.Event.AcceptanceComplete)
            
            showDebugBalloon("Accepted", "Completion accepted ($acceptType)", NotificationType.INFORMATION)
            updateStatusBarText("")

            if (continuousCompletionEnabled && textToInsert == completion.insertText) {
                log("Continuous completion enabled - scheduling next completion", "Continuous")
                scheduleContinuousCompletion(editor)
            }

            log("Acceptance complete", "AcceptText")

        } catch (e: Exception) {
            log("ERROR accepting completion: ${e.message}", "AcceptText")
            stateMachine.handleEvent(CompletionStateMachine.Event.Error("Acceptance failed: ${e.message}"))
        }
    }

    private fun showRemainingLines(
        editor: Editor,
        offset: Int,
        remainingLines: List<String>,
        originalCompletion: ZestInlineCompletionItem
    ) {
        log("=== showRemainingLines called ===", "Remaining")
        log("  offset: $offset, remainingLines: ${remainingLines.size}", "Remaining")

        val remainingText = remainingLines.joinToString("\n")
        val remainingCompletion = ZestInlineCompletionItem(
            insertText = remainingText,
            replaceRange = ZestInlineCompletionItem.Range(offset, offset),
            confidence = originalCompletion.confidence,
            metadata = originalCompletion.metadata,
            completionId = originalCompletion.completionId
        )

        val context = CompletionContext.from(editor, offset, manually = true)

        if (stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(remainingCompletion, context))) {
            log("Showing remaining completion at offset $offset", "Remaining")
            renderer.show(editor, offset, remainingCompletion, completionProvider.strategy, { renderingContext ->
                log("Remaining lines displayed", "Remaining", 1)
                project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
            })
            showDebugBalloon("Line Accepted", "Remaining lines ready", NotificationType.INFORMATION)
        }
    }

    private fun scheduleContinuousCompletion(editor: Editor) {
        scope.launch {
            delay(ACCEPTANCE_DELAY_MS)
            ApplicationManager.getApplication().invokeLater {
                val newOffset = editor.caretModel.offset
                log("Triggering next completion at offset $newOffset", "Continuous")
                triggerManager.requestCompletionNow(editor, newOffset)
            }
        }
    }

    // =================================
    // Helper Methods
    // =================================

    private suspend fun buildCompletionContext(editor: Editor, offset: Int, manually: Boolean): CompletionContext? {
        return try {
            val future = CompletableFuture<CompletionContext?>()

            ApplicationManager.getApplication().invokeLater {
                try {
                    val context = if (editor.isDisposed) null else CompletionContext.from(editor, offset, manually)
                    future.complete(context)
                } catch (e: Exception) {
                    future.complete(null)
                }
            }

            future.get(CONTEXT_BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to build completion context", e)
            null
        }
    }

    private suspend fun checkCache(context: CompletionContext, editor: Editor) =
        if (completionProvider.strategy in listOf(ZestCompletionProvider.CompletionStrategy.SIMPLE, ZestCompletionProvider.CompletionStrategy.LEAN)) {
            val cacheKey = generateCacheKey(context)
            val contextHash = generateContextHash(editor, context.offset)
            completionCache.get(cacheKey, contextHash)?.let { 
                com.zps.zest.completion.data.ZestInlineCompletionList.single(it.fullCompletion) 
            }
        } else null

    private suspend fun cacheCompletion(context: CompletionContext, editor: Editor, completion: ZestInlineCompletionItem) {
        try {
            val cacheKey = generateCacheKey(context)
            val contextHash = generateContextHash(editor, context.offset)
            completionCache.put(cacheKey, contextHash, completion)
            log("Cached completion (key: ${cacheKey.take(20)}...)", "Cache", 1)
        } catch (e: Exception) {
            log("ERROR caching completion: ${e.message}", "Cache")
        }
    }

    private fun calculateAcceptedText(completionText: String, type: AcceptType) = when (type) {
        AcceptType.FULL_COMPLETION, AcceptType.CTRL_TAB_COMPLETION -> completionText
        AcceptType.NEXT_WORD -> Regex("\\S+").find(completionText)?.value ?: ""
        AcceptType.NEXT_LINE -> completionText.lines().firstOrNull() ?: ""
    }

    private fun formatInsertedText(editor: Editor, startOffset: Int, endOffset: Int) {
        try {
            val document = editor.document
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val psiFile = psiDocumentManager.getPsiFile(document)

            if (psiFile != null) {
                psiDocumentManager.commitDocument(document)
                val codeStyleManager = CodeStyleManager.getInstance(project)
                codeStyleManager.reformatRange(psiFile, startOffset, endOffset)
                logger.debug("Formatted inserted text from offset $startOffset to $endOffset")
            }
        } catch (e: Exception) {
            logger.warn("Failed to format inserted text: ${e.message}")
        }
    }

    // =================================
    // Metrics and Cache
    // =================================

    private fun generateCacheKey(context: CompletionContext) =
        "${context.fileName}:${context.offset}:${context.prefixCode.hashCode()}:${context.suffixCode.hashCode()}"

    private fun generateContextHash(editor: Editor, offset: Int) = try {
        val text = editor.document.text
        val prefixEnd = minOf(offset + 100, text.length)
        val suffixStart = maxOf(offset - 100, 0)
        "${text.substring(suffixStart, offset)}|${text.substring(offset, prefixEnd)}".hashCode().toString()
    } catch (e: Exception) {
        "invalid"
    }

    private fun trackCompletionRequested(completionId: String, context: CompletionContext, manually: Boolean) {
        val contextInfo = mapOf(
            "manually_triggered" to manually,
            "offset" to context.offset,
            "file_name" to context.fileName,
            "prefix_length" to context.prefixCode.length,
            "suffix_length" to context.suffixCode.length
        )
        log("Context info: $contextInfo", "Metrics", 1)

        metricsService.trackCompletionRequested(
            completionId = completionId,
            strategy = completionProvider.strategy.name,
            fileType = context.language,
            actualModel = "local-model",
            contextInfo = contextInfo
        )
    }

    private fun trackCompletionCompleted(completionId: String, insertText: String, elapsed: Long) {
        metricsService.trackCompletionCompleted(completionId, insertText, elapsed)
    }

    private fun trackCompletionAccepted(completion: ZestInlineCompletionItem, textToInsert: String, acceptType: AcceptType) {
        metricsService.trackCompletionAccepted(
            completionId = completion.completionId,
            completionContent = textToInsert,
            isAll = textToInsert == completion.insertText,
            acceptType = acceptType.name,
            userAction = when (acceptType) {
                AcceptType.FULL_COMPLETION -> "tab"
                AcceptType.CTRL_TAB_COMPLETION -> "ctrl_tab"
                else -> acceptType.name.lowercase()
            }
        )
    }

    // =================================
    // Public Status Methods
    // =================================

    fun getDetailedState() = stateMachine.getStateInfo() + 
        displayCoordinator.getDisplayInfo() + 
        triggerManager.getTriggerInfo() +
        mapOf(
            "strategy" to completionProvider.strategy.name,
            "isEnabled" to inlineCompletionEnabled,
            "cacheStats" to completionCache.getStats(),
            "debugLogging" to debugLoggingEnabled
        )

    fun forceRefreshState() {
        log("=== Force refreshing state ===", "Refresh")
        logger.info("Force refreshing completion state")
        
        stateMachine.forceReset()
        triggerManager.cancelAllTimers()
        displayCoordinator.forceHide()
        
        scope.launch {
            completionCache.clear()
            log("Cleared completion cache", "Refresh")
        }
        
        log("Force refresh complete", "Refresh")
        showDebugBalloon("State Refreshed", "All state cleared", NotificationType.INFORMATION)
    }

    fun checkAndFixStuckState(): Boolean {
        // This method exists for compatibility with status bar widget
        return false
    }

    fun isEnabled() = inlineCompletionEnabled
    fun isAutoTriggerEnabled() = configManager.isAutoTriggerEnabled
    fun isContinuousCompletionEnabled() = continuousCompletionEnabled
    fun getCacheStats() = completionCache.getStats()

    // =================================
    // Cleanup
    // =================================

    override fun dispose() {
        log("=== Disposing ZestInlineCompletionService ===", "Dispose")
        logger.info("Disposing clean ZestInlineCompletionService")

        stateMachine.forceReset()
        triggerManager.dispose()
        displayCoordinator.forceHide()
        scope.cancel()

        scope.launch {
            completionCache.clear()
        }

        log("Disposal complete", "Dispose")
    }

    // =================================
    // Types
    // =================================

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
}