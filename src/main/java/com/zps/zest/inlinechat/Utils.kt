package com.zps.zest.inlinechat

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.CodeContext
import com.zps.zest.LlmApiCallStage
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.eclipse.lsp4j.*
import java.util.concurrent.CompletableFuture

/**
 * Location information with start offset
 */
data class LocationInfo(val location: Location, val startOffset: Int)

/**
 * Gets the current location information from the editor
 */
fun getCurrentLocation(editor: Editor): LocationInfo {
    val fileUri = editor.virtualFile.url
    val location = Location()
    location.uri = fileUri
    val selectionModel = editor.selectionModel
    val document = editor.document
    val caretOffset = editor.caretModel.offset
    var startOffset = caretOffset
    var endOffset = caretOffset
    if (selectionModel.hasSelection()) {
        startOffset = selectionModel.selectionStart
        endOffset = selectionModel.selectionEnd
    }
    val startPosition = Position(document.getLineNumber(startOffset), 0)
    val endChar = endOffset - document.getLineStartOffset(document.getLineNumber(endOffset))
    val endLine = if (endChar == 0) document.getLineNumber(endOffset) else document.getLineNumber(endOffset) + 1
    val endPosition = Position(endLine, 0)
    location.range = Range(startPosition, endPosition)
    return LocationInfo(location, startOffset)
}

/**
 * Gets suggested commands for inline chat based on context
 */
fun getSuggestedCommands(project: Project, location: Location): Deferred<List<ChatEditCommand>> {
    val result = CompletableDeferred<List<ChatEditCommand>>()
    
    // Get useful commands based on context
    val commands = listOf(
        ChatEditCommand("Generate unit test", "Generate a unit test for this method"),
        ChatEditCommand("Explain code", "Explain what this code does"),
        ChatEditCommand("Refactor code", "Suggest how to refactor this code for better readability"),
        ChatEditCommand("Generate documentation", "Add comprehensive documentation to this code"),
        ChatEditCommand("Improve error handling", "Improve error handling in this code"),
        ChatEditCommand("Optimize performance", "Optimize this code for better performance")
    )
    
    result.complete(commands)
    return result
}

/**
 * Process the command with an LLM and update code
 */
fun processInlineChatCommand(project: Project, params: ChatEditParams): Deferred<Boolean> {
    val result = CompletableDeferred<Boolean>()
    
    try {
        // Use the ZestContextProvider to create a code context with appropriate information
        val editor = ReadAction.compute<Editor,Throwable> {  com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor};
        if (editor == null) {
            ZestNotifications.showError(
                project,
                "Inline Chat Error",
                "No active editor found"
            )
            result.complete(false)
            return result
        }
        
        // Create context with our helper class that integrates with ClassAnalyzer
        val codeContext = ZestContextProvider.createCodeContext(project, editor, params.command)
        
        // Use the existing LLM API call stage to process the command
        val llmStage = LlmApiCallStage()
        codeContext.useTestWrightModel(false);
        llmStage.process(codeContext)
        
        // Store the response in the service for later use
        val service = project.serviceOrNull<InlineChatService>()
        service?.let {
            // In a real implementation, you'd store the response and show the diff
            // For now, just show a notification with a snippet of the response
            val response = codeContext.getApiResponse()
            if (response != null && response.isNotEmpty()) {
                val previewLength = minOf(100, response.length)
                val preview = response.substring(0, previewLength) + (if (response.length > previewLength) "..." else "")
                
                ZestNotifications.showInfo(
                    project,
                    "Inline Chat Response",
                    preview
                )
                
                result.complete(true)
            } else {
                ZestNotifications.showError(
                    project,
                    "Inline Chat Error",
                    "Failed to get response from LLM"
                )
                result.complete(false)
            }
        }
    } catch (e: Exception) {
        ZestNotifications.showError(
            project,
            "Inline Chat Error",
            "Error processing command: ${e.message}"
        )
        result.complete(false)
    }
    
    return result
}

/**
 * Resolve an edit (accept/discard/cancel)
 */
fun resolveInlineChatEdit(project: Project, params: ChatEditResolveParams): Deferred<Boolean> {
    val result = CompletableDeferred<Boolean>()
    
    // In a real implementation, this would apply or discard the changes
    val actionVerb = when (params.action) {
        "accept" -> "accepted"
        "discard" -> "discarded"
        "cancel" -> "cancelled"
        else -> "processed"
    }
    
    ZestNotifications.showInfo(
        project,
        "Inline Chat",
        "Edit $actionVerb"
    )
    
    result.complete(true)
    return result
}

/**
 * Command suggested by the AI for editing
 */
data class ChatEditCommand(val label: String, val command: String)

/**
 * Parameters for chat edit operation
 */
data class ChatEditParams(
    val location: Location, 
    val command: String
)

/**
 * Parameters for resolving an edit operation
 */
data class ChatEditResolveParams(val location: Location, val action: String)