package com.zps.zest.completion.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionRenderer
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import kotlinx.coroutines.CoroutineScope

/**
 * Adapter that implements StateContext to bridge the new state machine
 * with the existing service infrastructure.
 */
class StateContextAdapter(
    private val project: Project,
    override val renderer: ZestInlineCompletionRenderer,
    private val metricsService: ZestInlineCompletionMetricsService,
    private val scope: CoroutineScope,
    private val getEditor: () -> Editor?,
    private val getStrategy: () -> ZestCompletionProvider.CompletionStrategy,
    private val updateStatusBarCallback: (String) -> Unit,
    private val logCallback: (String, String, Int) -> Unit = { msg, tag, _ -> println("[$tag] $msg") }
) : StateContext {
    
    private lateinit var stateMachine: CompletionStateMachine
    
    fun setStateMachine(machine: CompletionStateMachine) {
        this.stateMachine = machine
    }
    
    override fun updateStatusBar(message: String) {
        updateStatusBarCallback(message)
    }
    
    override fun handleEvent(event: CompletionEvent): Boolean {
        return stateMachine.handleEvent(event)
    }
    
    override fun displayCompletion(
        completion: ZestInlineCompletionItem,
        context: CompletionContext,
        onDisplayed: () -> Unit
    ) {
        val editor = getEditor() ?: run {
            logCallback("No editor available for display", "Display", 0)
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            try {
                renderer.show(
                    editor,
                    context.offset,
                    completion,
                    getStrategy(),
                    callback = { renderingContext ->
                        logCallback("Completion displayed: ${renderingContext.id}", "Display", 1)
                        onDisplayed()
                    },
                    errorCallback = { reason ->
                        logCallback("Display failed: $reason", "Display", 0)
                        handleEvent(CompletionEvent.Error("Display failed: $reason"))
                    }
                )
            } catch (e: Exception) {
                logCallback("Exception displaying completion: ${e.message}", "Display", 0)
                handleEvent(CompletionEvent.Error("Display exception: ${e.message}"))
            }
        }
    }
    
    override fun performTextInsertion(
        completion: ZestInlineCompletionItem,
        context: CompletionContext,
        acceptType: String,
        onComplete: (Boolean) -> Unit
    ) {
        val editor = getEditor() ?: run {
            logCallback("No editor available for text insertion", "Accept", 0)
            onComplete(false)
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editor.document
                    val startOffset = completion.replaceRange.start
                    val endOffset = completion.replaceRange.end
                    
                    logCallback("Inserting text at $startOffset-$endOffset: '${completion.insertText.take(50)}...'", "Accept", 1)
                    
                    // Replace or insert the text
                    if (endOffset > startOffset) {
                        document.replaceString(startOffset, endOffset, completion.insertText)
                    } else {
                        document.insertString(startOffset, completion.insertText)
                    }
                    
                    // Move cursor to end of inserted text
                    val newCaretPosition = startOffset + completion.insertText.length
                    editor.caretModel.moveToOffset(newCaretPosition)
                    
                    logCallback("Text inserted successfully, cursor at $newCaretPosition", "Accept", 1)
                }
                onComplete(true)
            } catch (e: Exception) {
                logCallback("Failed to insert text: ${e.message}", "Accept", 0)
                onComplete(false)
            }
        }
    }
    
    // Metrics tracking methods
    
    override fun trackRequestStarted(requestId: Int, context: CompletionContext) {
        try {
            metricsService.trackCompletionRequested(
                completionId = requestId.toString(),
                strategy = getStrategy().name,
                fileType = context.fileName.substringAfterLast('.'),
                actualModel = "default" // Could be improved to get actual model
            )
            logCallback("Tracked request started: $requestId", "Metrics", 2)
        } catch (e: Exception) {
            logCallback("Failed to track request started: ${e.message}", "Metrics", 1)
        }
    }
    
    override fun trackCompletionReady(completion: ZestInlineCompletionItem, context: CompletionContext) {
        try {
            // Track that completion is ready for viewing
            metricsService.trackCompletionViewed(
                completionId = completion.completionId,
                completionLength = completion.insertText.length,
                completionLineCount = completion.insertText.lines().size,
                confidence = completion.confidence
            )
            logCallback("Tracked completion ready: ${completion.completionId}", "Metrics", 2)
        } catch (e: Exception) {
            logCallback("Failed to track completion ready: ${e.message}", "Metrics", 1)
        }
    }
    
    override fun trackAcceptanceStarted(completion: ZestInlineCompletionItem, acceptType: String) {
        try {
            // Track when user starts accepting - metrics service doesn't have this method
            // so we'll just log it
            logCallback("Tracked acceptance started: ${completion.completionId}, type=$acceptType", "Metrics", 2)
        } catch (e: Exception) {
            logCallback("Failed to track acceptance started: ${e.message}", "Metrics", 1)
        }
    }
    
    override fun trackAcceptanceCompleted(
        completion: ZestInlineCompletionItem, 
        acceptType: String, 
        timeMs: Long
    ) {
        try {
            // Track successful completion acceptance
            metricsService.trackCompletionAccepted(
                completionId = completion.completionId,
                completionContent = completion.insertText,
                isAll = true, // Assuming full acceptance for now
                acceptType = acceptType,
                userAction = "tab"
            )
            logCallback("Tracked acceptance completed: ${completion.completionId}, time=${timeMs}ms", "Metrics", 2)
        } catch (e: Exception) {
            logCallback("Failed to track acceptance completed: ${e.message}", "Metrics", 1)
        }
    }
}