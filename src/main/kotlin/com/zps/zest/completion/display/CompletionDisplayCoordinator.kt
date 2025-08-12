package com.zps.zest.completion.display

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionRenderer
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import com.zps.zest.completion.state.CompletionStateMachine
import kotlinx.coroutines.CoroutineScope

/**
 * Coordinates completion display, rendering, and user interactions
 * Handles the orchestration between completion display and acceptance
 */
class CompletionDisplayCoordinator(
    private val project: Project,
    private val stateMachine: CompletionStateMachine,
    private val renderer: ZestInlineCompletionRenderer,
    private val scope: CoroutineScope
) {
    private val logger = Logger.getInstance(CompletionDisplayCoordinator::class.java)
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }
    
    /**
     * Callbacks for completion events
     */
    interface CompletionDisplayListener {
        fun onCompletionDisplayed(completion: ZestInlineCompletionItem, context: CompletionContext)
        fun onCompletionDismissed(completion: ZestInlineCompletionItem, reason: String)
        fun onDisplayError(reason: String)
    }
    
    private var displayListener: CompletionDisplayListener? = null
    
    fun setDisplayListener(listener: CompletionDisplayListener) {
        this.displayListener = listener
    }
    
    /**
     * Display a completion result from the provider
     */
    fun displayCompletion(
        editor: Editor,
        context: CompletionContext, 
        completions: ZestInlineCompletionList?,
        requestId: Int,
        strategy: ZestCompletionProvider.CompletionStrategy
    ) {
        log("displayCompletion called for request $requestId")
        log("  completions: ${completions?.items?.size ?: 0} items")
        
        // Validate request is still active
        if (stateMachine.activeRequestId != requestId) {
            log("Request $requestId is no longer active, skipping display")
            return
        }
        
        // Handle empty/null completions
        if (completions == null || completions.isEmpty()) {
            log("No completions to display")
            handleEmptyCompletion(requestId, "no_completions")
            return
        }
        
        // Handle method rewrite strategy (no inline display)
        if (strategy == ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE) {
            log("Method rewrite mode - inline diff should be shown elsewhere")
            // Method rewrite displays its own UI, so we don't show inline completion
            return
        }
        
        val completion = completions.firstItem()!!
        log("Displaying completion: '${completion.insertText.take(30)}...' (${completion.insertText.length} chars)")
        
        // Update state machine with the completion
        if (!stateMachine.handleEvent(
                CompletionStateMachine.Event.CompletionReceived(completion, context)
            )) {
            log("Failed to update state machine with completion")
            handleEmptyCompletion(requestId, "state_machine_error")
            return
        }
        
        // Clear any existing rendering first
        ApplicationManager.getApplication().invokeAndWait {
            renderer.hide()
        }
        
        // Display the completion
        ApplicationManager.getApplication().invokeLater {
            displayCompletionOnEDT(editor, context, completion, strategy, requestId)
        }
    }
    
    /**
     * Display completion on EDT with proper error handling
     */
    private fun displayCompletionOnEDT(
        editor: Editor,
        context: CompletionContext,
        completion: ZestInlineCompletionItem,
        strategy: ZestCompletionProvider.CompletionStrategy,
        requestId: Int
    ) {
        log("=== Displaying completion on EDT ===")
        
        // Final validation
        if (stateMachine.activeRequestId != requestId) {
            log("Request $requestId no longer active on EDT")
            return
        }
        
        if (!stateMachine.isDisplaying) {
            log("State machine not in displaying state: ${stateMachine.currentState}")
            return
        }
        
        try {
            renderer.show(
                editor, context.offset, completion, strategy,
                callback = { renderingContext ->
                    log("Completion successfully rendered")
                    
                    // Track completion viewed metric
                    completion.completionId.let { completionId ->
                        metricsService.trackCompletionViewed(
                            completionId = completionId,
                            completionLength = completion.insertText.length,
                            completionLineCount = completion.insertText.split("\n").size,
                            confidence = completion.confidence
                        )
                    }
                    
                    // Notify listener
                    displayListener?.onCompletionDisplayed(completion, context)
                    
                    // Publish to message bus
                    project.messageBus.syncPublisher(CompletionDisplayEvents.TOPIC)
                        .completionDisplayed(renderingContext)
                },
                errorCallback = { reason ->
                    log("Renderer failed with reason: $reason")
                    
                    // Track the dismissal
                    completion.completionId.let { completionId ->
                        metricsService.trackCompletionDismissed(
                            completionId = completionId,
                            reason = "renderer_cancelled_$reason"
                        )
                    }
                    
                    // Update state machine
                    stateMachine.handleEvent(
                        CompletionStateMachine.Event.Error("Renderer failed: $reason")
                    )
                    
                    // Notify listener
                    displayListener?.onDisplayError(reason)
                }
            )
            
        } catch (e: Exception) {
            log("ERROR displaying completion: ${e.message}")
            e.printStackTrace()
            
            // Track error
            completion.completionId.let { completionId ->
                metricsService.trackCompletionDismissed(
                    completionId = completionId,
                    reason = "display_error_${e.javaClass.simpleName}"
                )
            }
            
            // Update state machine
            stateMachine.handleEvent(
                CompletionStateMachine.Event.Error("Display failed: ${e.message}")
            )
            
            displayListener?.onDisplayError(e.message ?: "Unknown display error")
        }
    }
    
    /**
     * Handle empty completion results
     */
    private fun handleEmptyCompletion(requestId: Int, reason: String) {
        log("Handling empty completion: $reason")
        
        // Update state machine back to idle
        stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss(reason))
        
        displayListener?.onDisplayError("No completion available: $reason")
    }
    
    /**
     * Dismiss current completion
     */
    fun dismissCompletion(reason: String) {
        log("dismissCompletion: $reason")
        
        val currentCompletion = stateMachine.currentCompletion
        
        // Hide renderer
        ApplicationManager.getApplication().invokeLater {
            renderer.hide()
        }
        
        // Track dismissal if we have a completion
        currentCompletion?.let { completion ->
            metricsService.trackCompletionDismissed(
                completionId = completion.completionId,
                reason = reason
            )
            
            displayListener?.onCompletionDismissed(completion, reason)
        }
        
        // Update state machine
        stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss(reason))
    }
    
    /**
     * Check if completion is currently visible at the given position
     */
    fun isCompletionVisibleAt(editor: Editor, offset: Int): Boolean {
        return renderer.current?.editor == editor && 
               renderer.current?.offset == offset && 
               stateMachine.isDisplaying
    }
    
    /**
     * Update completion display when user types (adjust remaining text)
     */
    fun updateCompletionForTyping(
        editor: Editor,
        currentOffset: Int
    ): Boolean {
        val completion = stateMachine.currentCompletion ?: return false
        val context = stateMachine.currentContext ?: return false
        
        log("updateCompletionForTyping: offset $currentOffset vs context offset ${context.offset}")
        
        // Check if typed text still matches completion
        if (!isTypedTextMatchingCompletion(context, currentOffset, completion, editor)) {
            log("Typed text no longer matches completion")
            dismissCompletion("user_typed_mismatch")
            return false
        }
        
        // Calculate remaining text to display
        val typedLength = currentOffset - context.offset
        if (typedLength > 0 && typedLength < completion.insertText.length) {
            try {
                val documentText = editor.document.text
                val typedText = documentText.substring(context.offset, currentOffset)
                val completionText = completion.insertText
                
                // Find the best match position in the completion
                var matchPosition = -1
                
                // Try exact match first
                if (completionText.startsWith(typedText)) {
                    matchPosition = typedLength
                } else {
                    // Try to find where the typed content matches
                    val typedContentOnly = typedText.trimStart()
                    if (typedContentOnly.isNotEmpty()) {
                        val index = completionText.indexOf(typedContentOnly, ignoreCase = true)
                        if (index >= 0) {
                            matchPosition = index + typedContentOnly.length
                        }
                    }
                }
                
                if (matchPosition > 0 && matchPosition < completionText.length) {
                    // Create updated completion with remaining text
                    val remainingText = completionText.substring(matchPosition)
                    val updatedCompletion = completion.copy(
                        insertText = remainingText,
                        replaceRange = ZestInlineCompletionItem.Range(currentOffset, currentOffset)
                    )
                    
                    // Update state machine
                    val updatedContext = CompletionContext.from(editor, currentOffset, context.manually)
                    if (stateMachine.handleEvent(
                            CompletionStateMachine.Event.CompletionReceived(updatedCompletion, updatedContext)
                        )) {
                        
                        // Re-render at new position
                        ApplicationManager.getApplication().invokeLater {
                            renderer.hide()
                            renderer.show(
                                editor, currentOffset, updatedCompletion,
                                ZestCompletionProvider.CompletionStrategy.SIMPLE,
                                { renderingContext ->
                                    log("Updated completion display after typing")
                                    project.messageBus.syncPublisher(CompletionDisplayEvents.TOPIC)
                                        .completionDisplayed(renderingContext)
                                }
                            )
                        }
                        
                        return true
                    }
                }
            } catch (e: Exception) {
                log("Error updating completion display: ${e.message}")
                dismissCompletion("update_error")
                return false
            }
        }
        
        return true
    }
    
    /**
     * Check if typed text matches the beginning of the completion
     */
    private fun isTypedTextMatchingCompletion(
        context: CompletionContext,
        currentOffset: Int,
        completion: ZestInlineCompletionItem,
        editor: Editor
    ): Boolean {
        val typedLength = currentOffset - context.offset
        
        if (typedLength <= 0) return true
        if (typedLength > 50) return false
        
        return try {
            val documentText = editor.document.text
            val typedText = documentText.substring(context.offset, currentOffset)
            val completionText = completion.insertText
            
            // Check exact prefix match
            if (completionText.startsWith(typedText)) {
                return true
            }
            
            // Check content-only match (ignoring leading whitespace)
            val typedContentOnly = typedText.trimStart()
            val completionContentOnly = completionText.trimStart()
            
            if (typedContentOnly.isEmpty()) {
                return true
            }
            
            if (completionContentOnly.startsWith(typedContentOnly, ignoreCase = true)) {
                return true
            }
            
            // Check first line content match
            val completionFirstLine = completionText.lines().firstOrNull() ?: ""
            val completionFirstLineContent = completionFirstLine.trimStart()
            
            completionFirstLineContent.startsWith(typedContentOnly, ignoreCase = true)
            
        } catch (e: Exception) {
            log("Error checking typed text match: ${e.message}")
            true // Default to keeping completion on error
        }
    }
    
    /**
     * Get display state information
     */
    fun getDisplayInfo(): Map<String, Any> {
        val currentCompletion = stateMachine.currentCompletion
        return mapOf(
            "isDisplaying" to stateMachine.isDisplaying,
            "hasCurrentCompletion" to (currentCompletion != null),
            "completionLength" to (currentCompletion?.insertText?.length ?: 0),
            "rendererActive" to (renderer.current != null)
        )
    }
    
    /**
     * Force hide any displayed completion
     */
    fun forceHide() {
        log("forceHide called")
        ApplicationManager.getApplication().invokeLater {
            renderer.hide()
        }
    }
    
    private fun log(message: String, level: Int = 0) {
        val prefix = if (level > 0) "[VERBOSE]" else ""
        println("$prefix[DisplayCoordinator] $message")
        logger.debug("$prefix[DisplayCoordinator] $message")
    }
}

/**
 * Message bus topics for completion display events
 */
interface CompletionDisplayEvents {
    fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext)
    
    companion object {
        @com.intellij.util.messages.Topic.ProjectLevel
        val TOPIC = com.intellij.util.messages.Topic(
            CompletionDisplayEvents::class.java,
            com.intellij.util.messages.Topic.BroadcastDirection.NONE
        )
    }
}