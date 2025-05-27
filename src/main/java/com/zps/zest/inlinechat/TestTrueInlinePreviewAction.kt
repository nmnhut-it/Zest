package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.zps.zest.CodeContext
import kotlinx.coroutines.runBlocking

/**
 * Test action that shows true inline preview - replaces the selected text temporarily
 * with the AI-suggested version, showing diff highlights inline
 */
class TestTrueInlinePreviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Check if there's a selection
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Please select some code to test inline preview.", "No Selection")
            return
        }
        
        // Get the selected text
        val originalText = selectionModel.selectedText ?: return
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        
        // Get services
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Get the location for the inline chat service
        val locationInfo = getCurrentLocation(editor)
        inlineChatService.location = locationInfo.location
        
        // Create a fake LLM response provider
        val fakeResponseProvider = object : LlmResponseProvider {
            override suspend fun getLlmResponse(codeContext: CodeContext): String? {
                return generateFakeLlmResponse(originalText)
            }
        }
        
        // Process the command
        val params = ChatEditParams(
            location = locationInfo.location,
            command = "Add helpful comments to explain this code"
        )
        
        runBlocking {
            // Process the command - this will automatically show the inline preview
            val deferred = processInlineChatCommand(project, params, fakeResponseProvider)
            deferred.await()
            
            // The preview is now shown inline in the editor
            // Users can see the actual changes with diff highlighting
            // and use Code Vision buttons to Accept or Reject
        }
    }
    
    /**
     * Generates a fake LLM response with more realistic comments
     */
    private fun generateFakeLlmResponse(originalText: String): String {
        val lines = originalText.split("\n")
        val modifiedLines = mutableListOf<String>()
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // Add contextual comments based on the code
            when {
                trimmed.startsWith("private") && trimmed.contains("(") -> {
                    modifiedLines.add("    // Private method to handle internal logic")
                    modifiedLines.add(line)
                }
                trimmed.startsWith("String") && trimmed.contains("=") -> {
                    modifiedLines.add(line)
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    modifiedLines.add("$indent// Retrieve current state from Redis")
                }
                trimmed.startsWith("if") -> {
                    modifiedLines.add(line)
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    modifiedLines.add("$indent    // Initialize if this is the first start")
                }
                trimmed.contains("setActive") -> {
                    modifiedLines.add(line)
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    modifiedLines.add("$indent    // Activate based on the parameter")
                }
                trimmed == "} else {" -> {
                    modifiedLines.add(line)
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    modifiedLines.add("$indent    // Parse and store the activation state")
                }
                else -> {
                    modifiedLines.add(line)
                }
            }
        }
        
        val modifiedCode = modifiedLines.joinToString("\n")
        
        // Wrap in markdown code block
        return """
Here's your code with explanatory comments added:

```java
$modifiedCode
```

I've added comments to explain the key logic and flow of your code.
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
