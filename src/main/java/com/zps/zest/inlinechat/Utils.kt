package com.zps.zest.inlinechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.CodeContext
import com.zps.zest.LlmApiCallStage
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.*

// Debug flags for Utils.kt
private const val DEBUG_PROCESS_COMMAND = false
private const val DEBUG_RESPONSE_HANDLING = false

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
fun getSuggestedCommands(project: Project): Deferred<List<ChatEditCommand>> {
    val result = CompletableDeferred<List<ChatEditCommand>>()
    
    // Get useful commands based on context
    val commands = mutableListOf(
        ChatEditCommand("Generate unit test", "Generate comprehensive unit tests for this code"),
        ChatEditCommand("Explain code", "Explain what this code does in detail"),
        ChatEditCommand("Refactor code", "Suggest refactoring improvements for better readability and maintainability"),
        ChatEditCommand("Generate documentation", "Add comprehensive JavaDoc documentation to this code"),
        ChatEditCommand("Improve error handling", "Improve error handling and add proper exception management"),
        ChatEditCommand("Optimize performance", "Optimize this code for better performance"),
        ChatEditCommand("Find bugs", "Identify potential bugs or issues in this code"),
        ChatEditCommand("Add logging", "Add appropriate logging statements for debugging"),
        ChatEditCommand("Convert to stream API", "Convert loops to Java Stream API where appropriate"),
        ChatEditCommand("Extract method", "Extract complex logic into separate methods"),
        ChatEditCommand("Add null checks", "Add proper null checks and validations")
    )
    
    // Check if the selected text contains TODOs
    ReadAction.run<Throwable> {
        val editor = ApplicationManager.getApplication().runReadAction<Editor?> {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        }
        
        if (editor != null && editor.selectionModel.hasSelection()) {
            val selectedText = editor.selectionModel.selectedText ?: ""
            if (ZestContextProvider.containsTodos(selectedText)) {
                // Add TODO-specific command at the beginning
                commands.add(0, ChatEditCommand("Implement TODOs", "Implement all TODO comments in the selected code"))
            }
        }
    }
    
    result.complete(commands)
    return result
}

/**
 * Interface for LLM response providers
 */
interface LlmResponseProvider {
    suspend fun getLlmResponse(codeContext: CodeContext): String?
}

/**
 * Default implementation that calls the actual LLM
 */
class DefaultLlmResponseProvider : LlmResponseProvider {
    override suspend fun getLlmResponse(codeContext: CodeContext): String? {
        val llmStage = LlmApiCallStage()
        llmStage.process(codeContext)
        return codeContext.getApiResponse()
    }
}

/**
 * Process the command with an LLM and update code
 */
fun processInlineChatCommand(
    project: Project, 
    params: ChatEditParams,
    responseProvider: LlmResponseProvider = DefaultLlmResponseProvider()
): Deferred<Boolean> {
    val result = CompletableDeferred<Boolean>()
    
    if (DEBUG_PROCESS_COMMAND) {
        System.out.println("=== processInlineChatCommand ===")
        System.out.println("Command: ${params.command}")
    }
    
    try {
        // We need to get the editor on the UI thread to avoid read access issues
        val editor = ApplicationManager.getApplication().runReadAction <Editor?> {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        }
        
        if (editor == null) {
            ZestNotifications.showError(
                project,
                "Inline Chat Error",
                "No active editor found"
            )
            result.complete(false)
            return result
        }
        
//        // Show progress notification
//        ZestNotifications.showInfo(
//            project,
//            "Inline Chat",
//            "Processing your request..."
//        )
//
        // Get the original text to use for diffing
        val selectionInfo = ReadAction.compute<SelectionInfo, Throwable> {
            val document = editor.document
            val selectionModel = editor.selectionModel
            
            // If there's a selection, use it
            if (selectionModel.hasSelection()) {
                val startLine = document.getLineNumber(selectionModel.selectionStart)
                SelectionInfo(
                    selectionModel.selectedText ?: "", 
                    startLine,
                    selectionModel.selectionStart,
                    selectionModel.selectionEnd
                )
            } else {
                // If selection was cleared but we have stored values in service, use those
                val inlineChatService = project.getService(InlineChatService::class.java)
                if (inlineChatService.originalCode != null && 
                    inlineChatService.selectionStartOffset > 0 && 
                    inlineChatService.selectionEndOffset > inlineChatService.selectionStartOffset) {
                    
                    val startLine = document.getLineNumber(inlineChatService.selectionStartOffset)
                    SelectionInfo(
                        inlineChatService.originalCode!!, 
                        startLine,
                        inlineChatService.selectionStartOffset,
                        inlineChatService.selectionEndOffset
                    )
                } else {
                    // If no selection and no stored values, don't process anything
                    SelectionInfo("", 0, 0, 0)
                }
            }
        }
        
        val originalText = selectionInfo.text
        val selectionStartLine = selectionInfo.startLine
        val selectionStartOffset = selectionInfo.startOffset
        val selectionEndOffset = selectionInfo.endOffset
        
        if (originalText.isEmpty()) {
            ZestNotifications.showWarning(
                project,
                "Inline Chat",
                "Please select some code first"
            )
            result.complete(false)
            return result
        }
        
        if (DEBUG_PROCESS_COMMAND) {
            System.out.println("Original text length: ${originalText.length}")
            System.out.println("Selection start line: $selectionStartLine")
        }
        
        // Create context with our helper class that integrates with ClassAnalyzer
        val codeContext = ZestContextProvider.createCodeContext(project, editor, params.command)
        
        // Use the code model instead of test model
        codeContext.useTestWrightModel(false)
        
        // Store original text in service
        val inlineChatService = project.getService(InlineChatService::class.java)
        inlineChatService.originalCode = originalText
        inlineChatService.selectionStartOffset = selectionStartOffset
        inlineChatService.selectionEndOffset = selectionEndOffset
        
        // Use the response provider to get the LLM response
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (DEBUG_RESPONSE_HANDLING) {
                    System.out.println("=== Getting LLM response ===")
                }
                
                val response = responseProvider.getLlmResponse(codeContext)
                
                if (DEBUG_RESPONSE_HANDLING) {
                    System.out.println("LLM response received: ${response != null}")
                    System.out.println("Response length: ${response?.length ?: 0}")
                }
                
                if (response != null && response.isNotEmpty()) {
                    // Process the response and update diff highlighting
                    inlineChatService.processLlmResponse(response, originalText, selectionStartLine)
                    
                    // Check if we extracted code
                    if (inlineChatService.extractedCode != null) {
                        if (DEBUG_RESPONSE_HANDLING) {
                            System.out.println("Code extracted successfully!")
                            System.out.println("Diff segments: ${inlineChatService.diffSegments.size}")
                            System.out.println("Diff actions enabled: ${inlineChatService.inlineChatDiffActionState}")
                        }
                        
                        // Validate the implementation
                        val isValid = validateImplementation(originalText, inlineChatService.extractedCode!!)
                        
                        if (!isValid) {
                            ZestNotifications.showWarning(
                                project,
                                "Inline Chat Warning",
                                "The suggested changes may have significantly altered the code structure. Please review carefully."
                            )
                        }
                        
                        // Show inline preview directly in the editor
                        ApplicationManager.getApplication().invokeLater {
                            // Create and show inline preview
                            val preview = InlineChatEditorPreview(project, editor)
                            inlineChatService.editorPreview = preview
                            
                            preview.showPreview(
                                originalText,
                                inlineChatService.extractedCode!!,
                                selectionStartOffset,
                                selectionEndOffset
                            )
                            
                            if (DEBUG_RESPONSE_HANDLING) {
                                System.out.println("Inline preview shown in editor")
                            }
                            
                            // Show floating toolbar for Accept/Reject actions
                            inlineChatService.showFloatingToolbar(editor)
                        }
                        
                        ZestNotifications.showInfo(
                            project,
                            "Inline Chat",
                            "Preview shown in editor. Use Code Vision buttons to Accept or Reject."
                        )
                        
                        // Force editor refresh to show highlights
                        ApplicationManager.getApplication().invokeLater {
                            if (DEBUG_RESPONSE_HANDLING) {
                                System.out.println("Forcing editor refresh...")
                            }
                            editor.contentComponent.repaint()
                            
                            // Force Code Vision refresh
                            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                            
                            if (DEBUG_RESPONSE_HANDLING) {
                                System.out.println("DaemonCodeAnalyzer restarted for Code Vision refresh")
                            }
                            
                            // Commit document to ensure changes are visible
                            try {
                                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                                if (DEBUG_RESPONSE_HANDLING) {
                                    System.out.println("Document committed")
                                }
                            } catch (e: Exception) {
                                if (DEBUG_RESPONSE_HANDLING) {
                                    System.out.println("Error committing document: ${e.message}")
                                }
                            }
                            
                            // Note: Code Vision refresh happens automatically when
                            // inlineChatDiffActionState is updated. If buttons don't appear,
                            // ensure Code Vision is enabled in settings.
                        }
                    } else {
                        if (DEBUG_RESPONSE_HANDLING) {
                            System.out.println("No code extracted from response!")
                        }
                        
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
                    if (DEBUG_RESPONSE_HANDLING) {
                        System.out.println("ERROR: No response from LLM!")
                    }
                    
                    ZestNotifications.showError(
                        project,
                        "Inline Chat Error",
                        "Failed to get response from LLM"
                    )
                    result.complete(false)
                }
            } catch (e: Exception) {
                if (DEBUG_RESPONSE_HANDLING) {
                    System.out.println("ERROR in coroutine: ${e.message}")
                    e.printStackTrace()
                }
                
                ZestNotifications.showError(
                    project,
                    "Inline Chat Error",
                    "Error in coroutine: ${e.message}"
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
 * Validates that the implemented code maintains the overall structure of the original code.
 * This helps ensure the LLM hasn't drastically altered the code beyond the requested changes.
 */
private fun validateImplementation(originalCode: String, implementedCode: String): Boolean {
    // Remove whitespace and comments for comparison
    val normalizedOriginal = normalizeForComparison(originalCode)
    val normalizedImplemented = normalizeForComparison(implementedCode)
    
    // Check if the implemented code has roughly similar structure
    // by comparing size (allowing for reasonable expansion)
    val originalSize = normalizedOriginal.length
    val implementedSize = normalizedImplemented.length
    
    // Implementation should not be smaller than original (unless it's refactoring)
    if (implementedSize < originalSize * 0.7) {
        return false
    }
    
    // Implementation should not be drastically larger (allowing for reasonable expansion)
    if (implementedSize > originalSize * 5) {
        return false
    }
    
    // Check that key structural elements are preserved
    val originalStructure = extractStructuralElements(originalCode)
    val implementedStructure = extractStructuralElements(implementedCode)
    
    // Count how many structural elements are preserved
    var preservedCount = 0
    for (element in originalStructure) {
        if (implementedStructure.contains(element)) {
            preservedCount++
        }
    }
    
    // At least 60% of structural elements should be preserved
    return if (originalStructure.isNotEmpty()) {
        preservedCount.toDouble() / originalStructure.size >= 0.6
    } else {
        true // If no structural elements found, consider it valid
    }
}

/**
 * Normalizes code for comparison by removing whitespace and comments.
 */
private fun normalizeForComparison(code: String): String {
    // Remove single-line comments
    var normalized = code.replace(Regex("//.*?$", RegexOption.MULTILINE), "")
    
    // Remove multi-line comments
    normalized = normalized.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    
    // Remove extra whitespace
    normalized = normalized.replace(Regex("\\s+"), " ")
    
    // Trim
    return normalized.trim()
}

/**
 * Extracts structural elements (class names, method signatures) from code.
 */
private fun extractStructuralElements(code: String): Set<String> {
    val elements = mutableSetOf<String>()
    
    // Extract class declarations
    val classPattern = Regex("\\b(class|interface|enum)\\s+(\\w+)")
    classPattern.findAll(code).forEach { match ->
        elements.add("${match.groupValues[1]}:${match.groupValues[2]}")
    }
    
    // Extract method signatures (simplified)
    val methodPattern = Regex("\\b(public|private|protected)?\\s*(static)?\\s*\\w+\\s+(\\w+)\\s*\\(")
    methodPattern.findAll(code).forEach { match ->
        elements.add("method:${match.groupValues[3]}")
    }
    
    // Extract field declarations
    val fieldPattern = Regex("\\b(public|private|protected)?\\s*(static)?\\s*(final)?\\s*\\w+\\s+(\\w+)\\s*[=;]")
    fieldPattern.findAll(code).forEach { match ->
        elements.add("field:${match.groupValues[4]}")
    }
    
    return elements
}

/**
 * Resolve an edit (accept/discard/cancel)
 */
fun resolveInlineChatEdit(project: Project, params: ChatEditResolveParams): Deferred<Boolean> {
    val result = CompletableDeferred<Boolean>()
    
    try {
        // Get the InlineChatService reference
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Get the editor, but ensure we're doing it on EDT
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                
                if (editor == null) {
                    ZestNotifications.showError(
                        project,
                        "Inline Chat Error",
                        "No active editor found"
                    )
                    result.complete(false)
                    return@invokeAndWait
                }
                
                when (params.action) {
                    "accept" -> {
                        // If we have an inline preview, accept it
                        val preview = inlineChatService.editorPreview
                        if (preview != null && preview.isPreviewActive()) {
                            preview.acceptPreview()
                            // Clear the selection after accepting changes
                            editor.selectionModel.removeSelection()
                            ZestNotifications.showInfo(
                                project,
                                "Inline Chat",
                                "Changes accepted"
                            )
                        } else {
                            // Fallback to old behavior if no preview
                            val newCode = inlineChatService.applyChanges()
                            if (newCode != null) {
                                // Ensure document modifications are done in a write action on EDT
                                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Apply Inline Chat Changes", null, Runnable {
                                    val document = editor.document
                                    val selectionModel = editor.selectionModel
                                    
                                    if (selectionModel.hasSelection()) {
                                        val startOffset = selectionModel.selectionStart
                                        val endOffset = selectionModel.selectionEnd
                                        document.replaceString(startOffset, endOffset, newCode)
                                        // Clear the selection after replacing
                                        editor.selectionModel.removeSelection()
                                    } else {
                                        document.setText(newCode)
                                    }
                                })
                                
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
                    }
                    "discard" -> {
                        // If we have an inline preview, hide it
                        val preview = inlineChatService.editorPreview
                        if (preview != null && preview.isPreviewActive()) {
                            preview.hidePreview()
                        }
                        
                        // Clear the selection after we're done processing
                        editor.selectionModel.removeSelection()
                        
                        ZestNotifications.showInfo(
                            project,
                            "Inline Chat",
                            "Changes discarded"
                        )
                    }
                    "cancel" -> {
                        // If we have an inline preview, hide it
                        val preview = inlineChatService.editorPreview
                        if (preview != null && preview.isPreviewActive()) {
                            preview.hidePreview()
                        }
                        
                        // Clear the selection after we're done processing
                        editor.selectionModel.removeSelection()
                        
                        // This is an explicit cancel action, so hide all UI elements immediately
                        inlineChatService.floatingToolbar?.hide()
                        
                        ZestNotifications.showInfo(
                            project,
                            "Inline Chat",
                            "Operation cancelled"
                        )
                    }
                }
                
                // Clear state and reset highlights
                inlineChatService.clearState()
                
                // Force clear all highlights (this will run on EDT from within the method)
                inlineChatService.forceClearAllHighlights()
                
                result.complete(true)
            } catch (e: Exception) {
                ZestNotifications.showError(
                    project,
                    "Inline Chat Error",
                    "Error resolving edit: ${e.message}"
                )
                result.complete(false)
            }
        }
    } catch (e: Exception) {
        // Handle any exceptions that might occur before we reach the invokeAndWait
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

// Helper data class for returning multiple values
private data class SelectionInfo(
    val text: String,
    val startLine: Int,
    val startOffset: Int,
    val endOffset: Int
)