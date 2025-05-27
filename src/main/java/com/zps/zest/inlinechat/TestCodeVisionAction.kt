package com.zps.zest.inlinechat

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

/**
 * Test action to verify Code Vision is working and force enable buttons
 */
class TestCodeVisionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        System.out.println("=== TestCodeVisionAction ===")
        
        // Get the inline chat service
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Clear any existing state
        inlineChatService.clearState()
        
        // Force enable the buttons
        System.out.println("Setting diff action states...")
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Discard"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Loading"] = true
        inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Cancel"] = true
        
        System.out.println("Diff action states: ${inlineChatService.inlineChatDiffActionState}")
        
        // Add some dummy diff segments so there's something to show
        inlineChatService.diffSegments.add(
            DiffSegment(0, 0, DiffSegmentType.HEADER, "Test Header")
        )
        
        // Force refresh
        ApplicationManager.getApplication().invokeLater {
            // Force code analyzer refresh
            DaemonCodeAnalyzer.getInstance(project).restart()
            
            // Force editor repaint
            editor.contentComponent.repaint()
            
            System.out.println("Forced DaemonCodeAnalyzer restart")
            
            // Try to force Code Vision refresh by marking file as dirty
            try {
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(editor.virtualFile)
                if (psiFile != null) {
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                    System.out.println("Committed document to force refresh")
                }
            } catch (e: Exception) {
                System.out.println("Error forcing document refresh: ${e.message}")
            }
            
            // Show status
            Messages.showInfoMessage(
                project,
                "Code Vision test complete!\n\n" +
                "Buttons enabled:\n" +
                "  Accept: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Accept"]}\n" +
                "  Discard: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Discard"]}\n" +
                "  Loading: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Loading"]}\n" +
                "  Cancel: ${inlineChatService.inlineChatDiffActionState["Zest.InlineChat.Cancel"]}\n\n" +
                "If buttons don't appear:\n" +
                "1. Go to Settings → Editor → Inlay Hints → Code Vision\n" +
                "2. Enable 'Code vision'\n" +
                "3. Restart IntelliJ IDEA\n\n" +
                "Check the top of the file for Code Vision buttons.",
                "Code Vision Test"
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
