package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager

/**
 * Test action that directly replaces selected text with modified version
 * to test if the diff highlighting is working properly.
 */
class TestDirectDiffAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Check if there's a selection
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Please select some code to test diff.", "No Selection")
            return
        }
        
        // Get the selected text
        val originalText = selectionModel.selectedText ?: return
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        val selectionStartLine = editor.document.getLineNumber(startOffset)
        
        System.out.println("=== TestDirectDiffAction ===")
        System.out.println("Original text length: ${originalText.length}")
        System.out.println("Selection range: $startOffset-$endOffset")
        System.out.println("Selection start line: $selectionStartLine")
        
        // Create modified version (add comments to each line)
        val modifiedText = createModifiedText(originalText)
        
        System.out.println("Modified text length: ${modifiedText.length}")
        System.out.println("Modified text preview: ${modifiedText.take(200)}...")
        
        // Get the InlineChatService
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Clear any existing state
        inlineChatService.clearState()
        
        // Process the fake LLM response
        val fakeLlmResponse = """
            Based on my analysis, here's the code with added comments:
            
            ```java
            $modifiedText
            ```
            
            I've added analytical comments to help understand each line of code better.
        """.trimIndent()
        
        System.out.println("Fake LLM response created")
        
        // Process the response
        inlineChatService.processLlmResponse(fakeLlmResponse, originalText, selectionStartLine)
        
        System.out.println("LLM response processed")
        System.out.println("Extracted code: ${inlineChatService.extractedCode != null}")
        System.out.println("Diff segments: ${inlineChatService.diffSegments.size}")
        System.out.println("Diff action states: ${inlineChatService.inlineChatDiffActionState}")
        
        // DO NOT replace the text immediately - the diff highlighting should show the proposed changes
        // The user should accept/reject the changes using the Code Vision buttons
        
        System.out.println("Diff segments prepared - check the highlighting in the editor")
        
        // Force editor refresh
        ApplicationManager.getApplication().invokeLater {
            editor.contentComponent.repaint()
            System.out.println("Editor repainted")
            
            // Show status
            Messages.showInfoMessage(
                project,
                "Direct diff test applied!\n" +
                "Segments: ${inlineChatService.diffSegments.size}\n" +
                "Accept button: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Accept"]}\n" +
                "Discard button: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Discard"]}\n\n" +
                "The diff highlighting should now show the proposed changes.\n" +
                "Use the Accept/Discard buttons to apply or reject the changes.",
                "Test Complete"
            )
        }
    }
    
    /**
     * Creates a modified version of the text by adding comments
     */
    private fun createModifiedText(originalText: String): String {
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
                modifiedLines.add("$indent// AI: Line ${index + 1} analyzed")
            }
        }
        
        return modifiedLines.joinToString("\n")
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
