package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.zps.zest.CodeContext
import kotlinx.coroutines.runBlocking

/**
 * Test action that shows inline chat with proper preview before applying changes
 */
class TestInlineChatWithPreviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Check if there's a selection
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Please select some code to test inline chat.", "No Selection")
            return
        }
        
        // Get the selected text
        val originalText = selectionModel.selectedText ?: return
        
        // Get services
        val inlineChatService = project.getService(InlineChatService::class.java)
        val previewService = project.getService(InlineChatPreviewService::class.java)
        
        // Get the location for the inline chat service
        val locationInfo = getCurrentLocation(editor)
        inlineChatService.location = locationInfo.location
        
        // Create a fake LLM response provider
        val fakeResponseProvider = object : LlmResponseProvider {
            override suspend fun getLlmResponse(codeContext: CodeContext): String? {
                return generateFakeLlmResponse(originalText)
            }
        }
        
        // Process the command to get the modified text
        val params = ChatEditParams(
            location = locationInfo.location,
            command = "Add analytical comments to each line of code"
        )
        
        runBlocking {
            // Process the command
            val deferred = processInlineChatCommand(project, params, fakeResponseProvider)
            deferred.await()
            
            // Get the extracted code
            val modifiedText = inlineChatService.extractedCode
            
            if (modifiedText != null) {
                // Show preview dialog with diff
                previewService.showSideBySideDiff(
                    editor,
                    originalText,
                    modifiedText,
                    "Inline Chat Preview - Review AI Suggestions"
                )
                
                // Alternative: Show inline preview
                // previewService.showInlinePreview(editor, originalText, modifiedText, selectionStartLine)
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to extract modified code from LLM response",
                    "Inline Chat Error"
                )
            }
        }
    }
    
    /**
     * Generates a fake LLM response that adds a comment to each line
     */
    private fun generateFakeLlmResponse(originalText: String): String {
        val lines = originalText.split("\n")
        val modifiedLines = mutableListOf<String>()
        
        lines.forEachIndexed { index, line ->
            // Skip empty lines
            if (line.trim().isEmpty()) {
                modifiedLines.add(line)
            } else {
                // Add the original line
                modifiedLines.add(line)
                // Add a comment after each non-empty line
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                modifiedLines.add("$indent// AI: Analyzed line ${index + 1}")
            }
        }
        
        val modifiedCode = modifiedLines.joinToString("\n")
        
        // Wrap in markdown code block like a real LLM would
        return """
Based on my analysis, here's the code with added comments:

```java
$modifiedCode
```

I've added analytical comments to help understand each line of code better.
        """.trimIndent()
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() ?: false
        
        e.presentation.isEnabled = project != null && editor != null && hasSelection
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
