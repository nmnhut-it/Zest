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
        
        // Generate fake LLM response
        val fakeResponse = generateFakeLlmResponse(originalText)
        
        // Process the fake response through the inline chat service
        processFakeInlineChatResponse(project, editor, originalText, fakeResponse)
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
    
    /**
     * Process the fake response through the inline chat service
     */
    private fun processFakeInlineChatResponse(project: Project, editor: Editor, originalText: String, fakeResponse: String) {
        // Get the inline chat service
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Clear any existing state
        inlineChatService.clearState()
        
        // Process the fake LLM response
        inlineChatService.processLlmResponse(fakeResponse, originalText)
        
        // Enable diff actions
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Discard"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Loading"] = false
        
        // Force document update to refresh highlighting
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        
        // Force editor highlighting refresh
        ApplicationManager.getApplication().invokeLater {
            editor.contentComponent.repaint()
            
            // Show a notification about the test
            Messages.showInfoMessage(
                project,
                "Test inline chat applied! Comments have been added to each line.\n" +
                "Use the Accept/Discard buttons to apply or reject changes.",
                "Test Inline Chat"
            )
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