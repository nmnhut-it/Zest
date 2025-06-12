package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
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
import com.intellij.util.messages.Topic
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.events.ZestCaretListener
import com.zps.zest.events.ZestDocumentListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Main service for handling inline completions
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestInlineCompletionService::class.java)
    private val messageBusConnection = project.messageBus.connect()
    private val editorManager = FileEditorManager.getInstance(project)
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val completionProvider = ZestCompletionProvider(project)
    private val completionProcessor = ZestCompletionProcessor()
    private val renderer = ZestInlineCompletionRenderer()
    
    // Configuration
    private var autoTriggerEnabled = true
    private var isManualRequest = false
    
    // State management
    private val stateMutex = Mutex()
    private var currentCompletionJob: Job? = null
    private var currentContext: CompletionContext? = null
    private var currentCompletions: ZestInlineCompletionList? = null
    private var currentCompletionIndex = 0
    
    init {
        logger.info("Initializing ZestInlineCompletionService")
        setupEventListeners()
    }
    
    /**
     * Request inline completion at the specified position
     */
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false) {
        scope.launch {
            stateMutex.withLock {
                logger.debug("Requesting completion at offset $offset, manually=$manually")
                
                // Cancel any existing completion requestc
                currentCompletionJob?.cancel()

                currentContext = null
                currentCompletions = null
                currentCompletionIndex = 0
                isManualRequest = manually
                
                // Hide current rendering
                renderer.hide()
                
                // Check if auto-trigger is disabled and this is not manual
                if (!autoTriggerEnabled && !manually) {
                    logger.debug("Auto-trigger disabled, ignoring automatic request")
                    return@withLock
                }
                
                // Notify listeners that we're loading
                project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(true)
                
                // Start completion request
                currentCompletionJob = scope.launch {
                    try {
                        // Build completion context safely using invokeLater
                        val context = buildCompletionContext(editor, offset, manually)
                        if (context == null) {
                            logger.debug("Failed to build completion context")
                            return@launch
                        }
                        
                        stateMutex.withLock {
                            currentContext = context
                        }
                        
                        val completions = requestCompletionInternal(context)
                        
                        stateMutex.withLock {
                            if (currentContext == context) { // Ensure request is still valid
                                handleCompletionResponse(editor, context, completions)
                            }
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Completion request cancelled")
                        throw e // Rethrow CancellationException as required
                    } catch (e: Exception) {
                        logger.warn("Completion request failed", e)
                    } finally {
                        project.messageBus.syncPublisher(Listener.TOPIC).loadingStateChanged(false)
                    }
                }
            }
        }
    }
    
    /**
     * Accept the current completion
     */
    fun accept(editor: Editor, offset: Int?, type: AcceptType) {
        scope.launch {
            stateMutex.withLock {
                logger.debug("Accepting completion: type=$type, offset=$offset")
                
                val context = currentContext ?: return@withLock
                val completions = currentCompletions ?: return@withLock
                
                // Use provided offset or context offset
                val actualOffset = offset ?: context.offset
                
                if (actualOffset != context.offset) {
                    logger.debug("Invalid position for acceptance")
                    return@withLock
                }
                
                val completionItem = completions.getItem(currentCompletionIndex) ?: return@withLock
                val textToInsert = calculateAcceptedText(completionItem.insertText, type)
                
                if (textToInsert.isNotEmpty()) {
                    invokeLater {
                        acceptCompletionText(editor, context, completionItem, textToInsert, type)
                    }
                }
                
                // Clear state unless it's partial acceptance
                if (type == AcceptType.FULL_COMPLETION) {
                    clearCurrentCompletion()
                } else {
                    // For partial acceptance, update the context
                    invokeLater {
                        updateContextAfterPartialAcceptance(editor, textToInsert.length)
                    }
                }
            }
        }
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss() {
        scope.launch {
            stateMutex.withLock {
                logger.debug("Dismissing completion")
                clearCurrentCompletion()
            }
        }
    }
    
    /**
     * Cycle through available completions
     */
    fun cycle(editor: Editor, offset: Int?, direction: CycleDirection) {
        scope.launch {
            stateMutex.withLock {
                val context = currentContext ?: return@withLock
                val completions = currentCompletions ?: return@withLock
                
                // Get offset safely
                val actualOffset = offset ?: context.offset
                
                if (actualOffset != context.offset) {
                    logger.debug("Invalid position for cycling")
                    return@withLock
                }
                
                if (completions.items.size <= 1) {
                    logger.debug("Cannot cycle: only ${completions.items.size} completion(s)")
                    return@withLock
                }
                
                // Calculate new index
                val newIndex = when (direction) {
                    CycleDirection.NEXT -> (currentCompletionIndex + 1) % completions.items.size
                    CycleDirection.PREVIOUS -> if (currentCompletionIndex == 0) {
                        completions.items.size - 1
                    } else {
                        currentCompletionIndex - 1
                    }
                }
                
                currentCompletionIndex = newIndex
                
                // Show the new completion
                val newItem = completions.getItem(newIndex)
                if (newItem != null) {
                    invokeLater {
                        renderer.show(editor, context.offset, newItem)
                        logger.debug("Cycled to completion $newIndex: '${newItem.insertText.take(30)}'")
                    }
                }
            }
        }
    }
    
    /**
     * Check if inline completion is visible at the given position
     */
    fun isInlineCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        return renderer.current?.editor == editor && renderer.current?.offset == offset
    }
    
    /**
     * Check if the current completion starts with indentation
     */
    fun isCompletionStartingWithIndentation(): Boolean {
        val renderingContext = renderer.current ?: return false
        val completion = renderingContext.completionItem.insertText
        return completion.matches(Regex("^\\s+.*"))
    }
    
    /**
     * Alias for isCompletionStartingWithIndentation() to match action expectations
     */
    fun isInlineCompletionStartWithIndentation(): Boolean {
        return isCompletionStartingWithIndentation()
    }
    
    /**
     * Get the current completion item being displayed.
     * Used by actions to analyze completion content for context-aware behavior.
     */
    fun getCurrentCompletion(): ZestInlineCompletionItem? {
        val completions = currentCompletions ?: return null
        return completions.getItem(currentCompletionIndex)
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
            withContext(Dispatchers.Main) {
                if (editor.isDisposed) {
                    null
                } else {
                    CompletionContext.from(editor, offset, manually)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to build completion context", e)
            null
        }
    }
    
    private suspend fun requestCompletionInternal(context: CompletionContext): ZestInlineCompletionList? {
        val rawCompletions = completionProvider.requestCompletion(context)
        return if (rawCompletions != null) {
            completionProcessor.processCompletions(rawCompletions, context)
        } else {
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
        
        currentCompletions = completions
        currentCompletionIndex = 0
        
        val firstCompletion = completions.firstItem()!!
        invokeLater {
            renderer.show(editor, context.offset, firstCompletion) { renderingContext ->
                // Notify listeners about the completion display
                project.messageBus.syncPublisher(Listener.TOPIC).completionDisplayed(renderingContext)
            }
        }
        
        logger.debug("Displayed completion: '${firstCompletion.insertText.take(50)}'")
    }
    
    private fun acceptCompletionText(
        editor: Editor,
        context: CompletionContext,
        completionItem: ZestInlineCompletionItem,
        textToInsert: String,
        acceptType: AcceptType
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
        
        // Notify listeners
        project.messageBus.syncPublisher(Listener.TOPIC).completionAccepted(acceptType)
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
                if (firstLine.isEmpty() && completionText.lines().size > 1) {
                    // If first line is empty, take the first two lines
                    completionText.lines().take(2).joinToString("\n")
                } else {
                    firstLine
                }
            }
        }
    }
    
    private fun updateContextAfterPartialAcceptance(editor: Editor, acceptedLength: Int) {
        // This method is called from EDT via invokeLater, so it's safe to access editor state
        val context = currentContext ?: return
        val newOffset = context.offset + acceptedLength
        
        // Update current context
        currentContext = CompletionContext.from(editor, newOffset, isManualRequest)
        
        // Keep showing remaining completion if any
        val remainingText = currentCompletions?.getItem(currentCompletionIndex)?.insertText?.drop(acceptedLength)
        if (!remainingText.isNullOrEmpty()) {
            val updatedItem = ZestInlineCompletionItem(
                insertText = remainingText,
                replaceRange = ZestInlineCompletionItem.Range(newOffset, newOffset)
            )
            renderer.show(editor, newOffset, updatedItem)
        } else {
            clearCurrentCompletion()
        }
    }
    
    private fun isValidPosition(editor: Editor, offset: Int, context: CompletionContext): Boolean {
        return editor.caretModel.offset == offset && offset == context.offset
    }
    
    private fun isValidPositionSync(editor: Editor, offset: Int, context: CompletionContext): Boolean {
        // This version can be called from EDT without launching coroutines
        return offset == context.offset
    }
    
    private fun clearCurrentCompletion() {
        currentCompletionJob?.cancel()
        currentContext = null
        currentCompletions = null
        currentCompletionIndex = 0
        renderer.hide()
    }
    
    private fun setupEventListeners() {
        // Document change listener
        messageBusConnection.subscribe(ZestDocumentListener.TOPIC, object : ZestDocumentListener {
            override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
                if (editorManager.selectedTextEditor == editor && autoTriggerEnabled) {
                    // Debounce automatic triggers
                    scope.launch {
                        delay(AUTO_TRIGGER_DELAY_MS)
                        invokeLater {
                            if (!editor.isDisposed) {
                                val currentOffset = editor.caretModel.offset
                                provideInlineCompletion(editor, currentOffset, manually = false)
                            }
                        }
                    }
                }
            }
        })
        
        // Caret change listener
        messageBusConnection.subscribe(ZestCaretListener.TOPIC, object : ZestCaretListener {
            override fun caretPositionChanged(editor: Editor, event: CaretEvent) {
                if (editorManager.selectedTextEditor == editor) {
                    // Get offset on EDT thread where the event handler runs
                    val currentOffset = editor.logicalPositionToOffset(event.newPosition)
                    
                    // Check if we should dismiss without launching a coroutine
                    val context = currentContext
                    if (context != null && !isValidPositionSync(editor, currentOffset, context)) {
                        logger.debug("Caret moved, dismissing completion")
                        scope.launch {
                            stateMutex.withLock {
                                clearCurrentCompletion()
                            }
                        }
                    }
                }
            }
        })
        
        // Editor selection change listener
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                scope.launch {
                    stateMutex.withLock {
                        clearCurrentCompletion()
                    }
                }
            }
        })
    }
    
    override fun dispose() {
        logger.info("Disposing ZestInlineCompletionService")
        scope.cancel()
        renderer.hide()
        messageBusConnection.dispose()
    }
    
    // Enums and interfaces
    
    enum class AcceptType {
        FULL_COMPLETION, NEXT_WORD, NEXT_LINE
    }
    
    enum class CycleDirection {
        NEXT, PREVIOUS
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
        private const val AUTO_TRIGGER_DELAY_MS = 500L // Debounce delay for auto-trigger
    }
}
