package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.zps.zest.CodeContext
import kotlinx.coroutines.runBlocking

/**
 * Test action that simulates inline chat with a fake LLM response.
 * This action adds a comment to each line of the selected code.
 */
class TestInlineChatAction : AnAction() {

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
        
        // Get the location for the inline chat service
        val locationInfo = getCurrentLocation(editor)
        val inlineChatService = project.getService(InlineChatService::class.java)
        inlineChatService.location = locationInfo.location
        
        // Create a fake LLM response provider
        val fakeResponseProvider = object : LlmResponseProvider {
            override suspend fun getLlmResponse(codeContext: CodeContext): String? {
                return generateFakeLlmResponse(originalText)
            }
        }
        
        // Process the command with our fake response
        val params = ChatEditParams(
            location = locationInfo.location,
            command = "Add analytical comments to each line of code"
        )
        
        // Run the process with our fake provider
        runBlocking {
            val deferred = processInlineChatCommand(project, params, fakeResponseProvider)
            deferred.await()
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
                modifiedLines.add("$indent// AI: Line ${index + 1} analyzed - This line contains: ${analyzeLineContent(line)}")
            }
        }
        
        // Wrap in markdown code block like a real LLM would
        return """
            Based on my analysis, here's the code with added comments:
            
            ```java
            ${modifiedLines.joinToString("\n")}
            ```
            
            I've added analytical comments to help understand each line of code better.
        """.trimIndent()
    }
    
    /**
     * Analyzes the content of a line and returns a description
     */
    private fun analyzeLineContent(line: String): String {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("package") -> "package declaration"
            trimmed.startsWith("import") -> "import statement"
            trimmed.startsWith("public class") || trimmed.startsWith("class") -> "class declaration"
            trimmed.startsWith("public interface") || trimmed.startsWith("interface") -> "interface declaration"
            trimmed.startsWith("public") || trimmed.startsWith("private") || trimmed.startsWith("protected") -> {
                when {
                    trimmed.contains("(") && trimmed.contains(")") -> "method declaration"
                    else -> "field declaration"
                }
            }
            trimmed.startsWith("if") -> "conditional statement"
            trimmed.startsWith("for") -> "for loop"
            trimmed.startsWith("while") -> "while loop"
            trimmed.startsWith("return") -> "return statement"
            trimmed.startsWith("//") -> "existing comment"
            trimmed == "{" -> "block start"
            trimmed == "}" -> "block end"
            trimmed.contains("=") && !trimmed.contains("==") -> "assignment statement"
            trimmed.contains(".") && trimmed.contains("(") -> "method call"
            else -> "code statement"
        }
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