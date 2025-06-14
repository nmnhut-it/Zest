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
import com.intellij.util.messages.Topic
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*

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
    
    // Configuration
    private var autoTriggerEnabled = true
    
    // State management
    private var currentCompletionJob: Job? = null
    private var currentContext: CompletionContext? = null
    private var currentCompletion: ZestInlineCompletionItem? = null
    
    init {
        logger.info("Initializing simplified ZestInlineCompletionService")
        setupEventListeners()
    }
    
    /**
     * Request inline completion at the specified position
     */
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false) {
        scope.launch {
            logger.debug("Requesting completion at offset $offset, manually=$manually")
            
            // Cancel any existing completion request
            currentCompletionJob?.cancel()
            clearCurrentCompletion()
            
            // Check if auto-trigger is disabled and this is not manual
            if (!autoTriggerEnabled && !manually) {
                logger.debug("Auto-trigger disabled, ignoring automatic request")
                return@launch
            }
            
            // Notify listeners that we're loading
            project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)
            
            // Start completion request
            currentCompletionJob = scope.launch {
                try {
                    val context = buildCompletionContext(editor, offset, manually)
                    if (context == null) {
                        logger.debug("Failed to build completion context")
                        return@launch
                    }
                    
                    currentContext = context
                    
                    val completions = completionProvider.requestCompletion(context)
                    
                    if (currentContext == context) { // Ensure request is still valid
                        handleCompletionResponse(editor, context, completions)
                    }
                } catch (e: CancellationException) {
                    logger.debug("Completion request cancelled")
                    throw e
                } catch (e: Exception) {
                    logger.warn("Completion request failed", e)
                } finally {
                    project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                }
            }
        }
    }
    
    /**
     * Accept the current completion
     */
    fun accept(editor: Editor, offset: Int?, type: AcceptType) {
        val context = currentContext ?: return
        val completion = currentCompletion ?: return
        
        val actualOffset = offset ?: context.offset
        
        if (actualOffset != context.offset) {
            logger.debug("Invalid position for acceptance")
            return
        }
        
        val textToInsert = calculateAcceptedText(completion.insertText, type)
        
        if (textToInsert.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                acceptCompletionText(editor, context, completion, textToInsert)
            }
        }
        
        clearCurrentCompletion()
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss() {
        logger.debug("Dismissing completion")
        clearCurrentCompletion()
    }
    
    /**
     * Check if inline completion is visible at the given position
     */
    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        return renderer.current?.editor == editor && 
               renderer.current?.offset == offset &&
               currentCompletion != null
    }
    
    /**
     * Get the current completion item being displayed
     */
    fun getCurrentCompletion(): ZestInlineCompletionItem? {
        return currentCompletion
    }
    
    /**
     * Enable or disable auto-trigger
     */
    fun setAutoTriggerEnabled(enabled: Boolean) {
        autoTriggerEnabled = enabled
        logger.info("Auto-trigger ${if (enabled) "enabled" else "disabled"}")
    }
    
    // Private implementation methods
    
    private suspend fun buildCompletionContext(editor: Editor, offset: Int, manually: Boolean): CompletionContext? {
        return try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                try {
                    // Try to access editor to check if it's still valid
                    editor.caretModel.offset
                    CompletionContext.from(editor, offset, manually)
                } catch (e: Exception) {
                    // Editor is likely disposed or invalid
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to build completion context", e)
            null
        }
    }
    
    private suspend fun handleCompletionResponse(
        editor: Editor,
        context: CompletionContext,
        completions: ZestInlineCompletionList?
    ) {
        if (completions == null || completions.isEmpty()) {
            logger.debug("No completions available")
            return
        }
        
        val completion = completions.firstItem()!!
        currentCompletion = completion
        
        ApplicationManager.getApplication().invokeLater {
            renderer.show(editor, context.offset, completion) { renderingContext ->
                project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
            }
        }
        
        logger.debug("Displayed completion: '${completion.insertText.take(50)}'")
    }
    
    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val startOffset = completionItem.replaceRange.start
            val endOffset = completionItem.replaceRange.end
            
            // Replace the text
            document.replaceString(startOffset, endOffset, textToInsert)
            
            // Move cursor to end of inserted text
            val newCaretPosition = startOffset + textToInsert.length
            editor.caretModel.moveToOffset(newCaretPosition)
            
            logger.debug("Accepted completion: inserted '$textToInsert' at offset $startOffset")
        }
        
        project.messageBus.syncPublisher(Listener.TOPIC).completionAccepted(AcceptType.FULL_COMPLETION)
    }
    
    private fun calculateAcceptedText(completionText: String, type: AcceptType): String {
        return when (type) {
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
    }
    
    private fun clearCurrentCompletion() {
        currentCompletionJob?.cancel()
        currentContext = null
        currentCompletion = null
        renderer.hide()
    }
    
    private fun setupEventListeners() {
        // Document change listener
        messageBusConnection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: com.intellij.openapi.editor.Document, editor: Editor, event: DocumentEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // SIMPLIFIED: Only handle real-time overlap, disable auto-trigger during active completion
                    // This prevents conflicting completion requests that cause blinking
                    if (currentCompletion != null) {
                        // Handle real-time overlap for existing completion
                        handleRealTimeOverlap(editor, event)
                    } else if (autoTriggerEnabled) {
                        // Only schedule new completion if no completion is active
                        scheduleNewCompletion(editor)
                    }
                }
            }
        })
        
        // Caret change listener
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    val currentOffset = editor.logicalPositionToOffset(event.newPosition)
                    val context = currentContext
                    
                    if (context != null && currentOffset != context.offset) {
                        logger.debug("Caret moved, dismissing completion")
                        clearCurrentCompletion()
                    }
                }
            }
        })
        
        // Editor selection change listener
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                clearCurrentCompletion()
            }
        })
    }
    
    /**
     * Handle real-time overlap detection and completion adjustment as user types
     * SIMPLIFIED: Reduce frequency to prevent blinking
     */
    private fun handleRealTimeOverlap(editor: Editor, event: DocumentEvent) {
        val completion = currentCompletion ?: return
        val context = currentContext ?: return
        
        // Cancel any existing job to prevent race conditions
        currentCompletionJob?.cancel()
        
        currentCompletionJob = scope.launch {
            try {
                // LONGER delay to reduce frequency and blinking
                delay(150) // Increased from 50ms to 150ms
                
                val currentOffset = withContext(Dispatchers.Main) { 
                    try {
                        editor.caretModel.offset
                    } catch (e: Exception) {
                        -1 // Editor is disposed or invalid
                    }
                }
                
                if (currentOffset == -1) return@launch
                
                // Only handle if cursor is near the completion position (within reasonable range)
                if (kotlin.math.abs(currentOffset - context.offset) > 100) {
                    clearCurrentCompletion()
                    return@launch
                }
                
                val documentText = withContext(Dispatchers.Main) { 
                    try {
                        editor.document.text
                    } catch (e: Exception) {
                        "" // Editor is disposed or invalid
                    }
                }
                
                if (documentText.isEmpty()) return@launch
                
                // Re-parse the original completion with current document state
                // Use the ORIGINAL response stored in metadata if available, otherwise use current text
                val originalResponse = completion.metadata?.let { meta ->
                    // Store original response in metadata for re-processing
                    meta.reasoning ?: completion.insertText
                } ?: completion.insertText
                
                val adjustedCompletion = responseParser.parseResponseWithOverlapDetection(
                    originalResponse,
                    documentText,
                    currentOffset
                )
                
                withContext(Dispatchers.Main) {
                    // Check if we're still the active completion (not replaced by IntelliJ)
                    if (currentCompletion == completion) {
                        try {
                            // Try to access editor to verify it's still valid
                            editor.caretModel.offset
                            
                            when {
                                adjustedCompletion.isBlank() -> {
                                    // No meaningful completion left, dismiss
                                    logger.debug("Completion became empty after overlap detection, dismissing")
                                    clearCurrentCompletion()
                                }
                                adjustedCompletion != completion.insertText -> {
                                    // Only update if change is significant to reduce blinking
                                    val changeRatio = adjustedCompletion.length.toDouble() / completion.insertText.length
                                    if (changeRatio > 0.2) { // Reduced threshold from 0.3 to 0.2 for better overlap handling
                                        logger.debug("Updating completion: '${completion.insertText}' -> '$adjustedCompletion'")
                                        updateDisplayedCompletion(editor, currentOffset, adjustedCompletion)
                                    } else {
                                        // Only clear if the remaining text is truly meaningless
                                        if (adjustedCompletion.trim().length < 2) {
                                            clearCurrentCompletion()
                                        } else {
                                            // Keep the completion even if it's small - user might want it
                                            logger.debug("Keeping small completion: '$adjustedCompletion'")
                                            updateDisplayedCompletion(editor, currentOffset, adjustedCompletion)
                                        }
                                    }
                                }
                                // Otherwise keep current completion as-is
                            }
                        } catch (e: Exception) {
                            // Editor is disposed, clear completion
                            clearCurrentCompletion()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation, don't log
                throw e
            } catch (e: Exception) {
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
        scope.launch {
            delay(AUTO_TRIGGER_DELAY_MS)
            ApplicationManager.getApplication().invokeLater {
                try {
                    // Only trigger if no completion is currently active to prevent blinking
                    if (currentCompletion == null) {
                        val currentOffset = editor.caretModel.offset
                        provideInlineCompletion(editor, currentOffset, manually = false)
                    }
                } catch (e: Exception) {
                    // Editor is disposed, do nothing
                }
            }
        }
    }
    
    /**
     * Update the currently displayed completion with new text
     * SIMPLIFIED: Reduce blinking by avoiding hide/show cycles
     */
    private fun updateDisplayedCompletion(editor: Editor, offset: Int, newText: String) {
        // Check if current completion is still valid (not interfered with by IntelliJ)
        val currentRendering = renderer.current
        if (currentRendering != null && currentRendering.editor == editor) {
            // Check if any inlays have been disposed (sign of interference)
            val hasDisposedInlays = currentRendering.inlays.any { inlay ->
                try {
                    !inlay.isValid
                } catch (e: Exception) {
                    true // Assume disposed if we can't check
                }
            }
            if (hasDisposedInlays) {
                System.out.println("Detected IntelliJ interference - inlays disposed, clearing completion")
                clearCurrentCompletion()
                return
            }
        }
        
        // Only update if the new text is significantly different to avoid unnecessary updates
        val currentText = currentCompletion?.insertText ?: ""
        if (newText == currentText) {
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
            
            // Re-render with new text
            renderer.hide()
            renderer.show(editor, offset, updatedCompletion) { renderingContext ->
                project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
            }
            return
        }
        
        // Only clear if remaining text is truly too small
        if (newText.trim().length < 2) {
            clearCurrentCompletion()
            return
        }
    }
    
    override fun dispose() {
        logger.info("Disposing simplified ZestInlineCompletionService")
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
    }
}
