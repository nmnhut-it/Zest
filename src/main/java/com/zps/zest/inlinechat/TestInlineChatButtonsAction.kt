package com.zps.zest.inlinechat

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionProvider
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
            
            // Try to force code vision refresh
            try {
                val codeVisionHost = editor.getUserData(CodeVisionHost.key)
                if (codeVisionHost != null) {
                    // Invalidate all inline chat providers
                    codeVisionHost.invalidateProvider(CodeVisionProvider.providerId<InlineChatAcceptCodeVisionProvider>())
                    codeVisionHost.invalidateProvider(CodeVisionProvider.providerId<InlineChatDiscardCodeVisionProvider>())
                    
                    Messages.showInfoMessage(
                        project,
                        "Code Vision buttons should now appear at the top of the file.\n\n" +
                        "If you still don't see them:\n" +
                        "1. Go to Settings → Editor → Inlay Hints → Code Vision\n" +
                        "2. Make sure 'Code Vision' is enabled\n" +
                        "3. Restart IntelliJ if needed",
                        "Test Inline Chat Buttons"
                    )
                } else {
                    Messages.showWarningMessage(
                        project,
                        "Code Vision host not found. Please ensure:\n" +
                        "1. Code Vision is enabled in Settings → Editor → Inlay Hints → Code Vision\n" +
                        "2. You may need to restart IntelliJ after enabling it",
                        "Code Vision Not Available"
                    )
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Error refreshing Code Vision: ${e.message}\n\n" +
                    "Please check that Code Vision is enabled in settings.",
                    "Code Vision Error"
                )
            }
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