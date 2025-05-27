package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager

/**
 * Test action that directly enables the Accept/Reject buttons for testing the UI.
 * This bypasses the LLM call and just shows the buttons.
 */
class TestInlineChatButtonsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Get the inline chat service
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Clear any existing state
        inlineChatService.clearState()
        
        // Set some dummy data
        inlineChatService.originalCode = "// Original code"
        inlineChatService.extractedCode = "// Modified code"
        inlineChatService.llmResponse = "Test response"
        
        // Enable the accept/discard buttons
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Discard"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Loading"] = false
        
        // Add some diff segments for visual feedback
        inlineChatService.diffSegments.add(
            DiffSegment(0, 0, DiffSegmentType.HEADER, "Test Diff")
        )
        inlineChatService.diffSegments.add(
            DiffSegment(1, 1, DiffSegmentType.INSERTED, "// This is a test")
        )
        
        // Force document update
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        
        // Force editor and code vision refresh
        ApplicationManager.getApplication().invokeLater {
            // Repaint editor
            editor.contentComponent.repaint()
            
            // Show instructions for enabling Code Vision
            Messages.showMessageDialog(
                project,
                "The inline chat state has been set up for testing.\n\n" +
                "To see the Accept/Discard buttons:\n" +
                "1. Go to Settings → Editor → Inlay Hints → Code Vision\n" +
                "2. Make sure 'Code Vision' is enabled\n" +
                "3. Restart IntelliJ IDEA\n\n" +
                "The buttons should appear at the top of the file.\n" +
                "You can also use the actions directly from the menu.",
                "Test Inline Chat Buttons",
                Messages.getInformationIcon()
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabled = project != null && editor != null
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}