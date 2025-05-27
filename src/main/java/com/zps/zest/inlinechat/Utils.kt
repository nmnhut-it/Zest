package com.zps.zest.inlinechat

import com.intellij.openapi.application.ApplicationManager
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
        // We need to get the editor on the UI thread to avoid read access issues
        val editor = ApplicationManager.getApplication().runReadAction <Editor?> {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        }
        
        if (editor == null) {
            ZestNotifications.showInfo(
                project,
                "Inline Chat Error",
                "No active editor found"
            )
            result.complete(false)
            return result
        }
        
        // Show progress notification
        ZestNotifications.showInfo(
            project,
            "Inline Chat",
            "Processing your request..."
        )
        
        // Get the original text to use for diffing
        val originalText = ReadAction.compute<String, Throwable> {
            val document = editor.document
            val selectionModel = editor.selectionModel
            
            if (selectionModel.hasSelection()) {
                selectionModel.selectedText ?: ""
            } else {
                // If no selection, get the entire document content
                document.text
            }
        }
        
        // Create context with our helper class that integrates with ClassAnalyzer
        val codeContext = ZestContextProvider.createCodeContext(project, editor, params.command)
        
        // Use the code model instead of test model
        codeContext.useTestWrightModel(false)
        
        // Store original text in service
        val inlineChatService = project.getService(InlineChatService::class.java)
        inlineChatService.originalCode = originalText
        
        // Use the existing LLM API call stage to process the command
        val llmStage = LlmApiCallStage()
        llmStage.process(codeContext)
        
        // Process the response
        val response = codeContext.getApiResponse()
        if (response != null && response.isNotEmpty()) {
            // Process the response and update diff highlighting
            inlineChatService.processLlmResponse(response, originalText)
            
            // Check if we extracted code
            if (inlineChatService.extractedCode != null) {
                ZestNotifications.showInfo(
                    project,
                    "Inline Chat",
                    "Changes suggested! Review the highlighted code and accept or discard."
                )
                
                // Force editor refresh to show highlights
                ApplicationManager.getApplication().invokeLater {
                    editor.contentComponent.repaint()
                }
            } else {
                // Show a notification with a snippet of the response
                val previewLength = minOf(100, response.length)
                val preview = response.substring(0, previewLength) + (if (response.length > previewLength) "..." else "")
                
                ZestNotifications.showInfo(
                    project,
                    "Inline Chat Response",
                    preview
                )
            }
            
            result.complete(true)
        } else {
            ZestNotifications.showError(
                project,
                "Inline Chat Error",
                "Failed to get response from LLM"
            )
            result.complete(false)
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
    
    try {
        val inlineChatService = project.getService(InlineChatService::class.java)
        val editor = ApplicationManager.getApplication().runReadAction<Editor?> {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        }
        
        if (editor == null) {
            ZestNotifications.showInfo(
                project,
                "Inline Chat Error",
                "No active editor found"
            )
            result.complete(false)
            return result
        }
        
        when (params.action) {
            "accept" -> {
                // Apply the changes to the document
                val newCode = inlineChatService.applyChanges()
                if (newCode != null) {
                    ApplicationManager.getApplication().runWriteAction {
                        // Apply changes to the document
                        val document = editor.document
                        val selectionModel = editor.selectionModel
                        
                        if (selectionModel.hasSelection()) {
                            val startOffset = selectionModel.selectionStart
                            val endOffset = selectionModel.selectionEnd
                            document.replaceString(startOffset, endOffset, newCode)
                        } else {
                            // If no selection, replace the entire document
                            document.setText(newCode)
                        }
                    }
                    
                    ZestNotifications.showInfo(
                        project,
                        "Inline Chat",
                        "Changes applied successfully"
                    )
                } else {
                    ZestNotifications.showError(
                        project,
                        "Inline Chat",
                        "No changes to apply"
                    )
                }
            }
            "discard" -> {
                ZestNotifications.showInfo(
                    project,
                    "Inline Chat",
                    "Changes discarded"
                )
            }
            "cancel" -> {
                ZestNotifications.showInfo(
                    project,
                    "Inline Chat",
                    "Operation cancelled"
                )
            }
        }
        
        // Clear state and reset highlights
        inlineChatService.clearState()
        
        // Force editor refresh to clear highlights
        ApplicationManager.getApplication().invokeLater {
            editor.contentComponent.repaint()
        }
        
        result.complete(true)
    } catch (e: Exception) {
        ZestNotifications.showError(
            project,
            "Inline Chat Error",
            "Error resolving edit: ${e.message}"
        )
        result.complete(false)
    }
    
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