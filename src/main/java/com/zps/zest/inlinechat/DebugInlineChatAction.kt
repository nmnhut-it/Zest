package com.zps.zest.inlinechat

import com.intellij.codeInsight.codeVision.CodeVisionInitializer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

/**
 * Debug action to show the current state of inline chat and force refresh
 */
class DebugInlineChatAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Get the inline chat service
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Build debug information
        val debugInfo = StringBuilder()
        debugInfo.append("=== INLINE CHAT DEBUG INFO ===\n\n")
        
        // Service state
        debugInfo.append("SERVICE STATE:\n")
        debugInfo.append("  Input visible: ${inlineChatService.inlineChatInputVisible}\n")
        debugInfo.append("  Has diff action: ${inlineChatService.hasDiffAction}\n")
        debugInfo.append("  Location: ${inlineChatService.location}\n")
        debugInfo.append("  Original code length: ${inlineChatService.originalCode?.length ?: "null"}\n")
        debugInfo.append("  Extracted code length: ${inlineChatService.extractedCode?.length ?: "null"}\n")
        debugInfo.append("  LLM response length: ${inlineChatService.llmResponse?.length ?: "null"}\n")
        debugInfo.append("\n")
        
        // Diff action states
        debugInfo.append("DIFF ACTION STATES:\n")
        inlineChatService.inlineChatDiffActionState.forEach { (key, value) ->
            debugInfo.append("  $key = $value\n")
        }
        debugInfo.append("\n")
        
        // Diff segments
        debugInfo.append("DIFF SEGMENTS (${inlineChatService.diffSegments.size} total):\n")
        inlineChatService.diffSegments.forEachIndexed { index, segment ->
            debugInfo.append("  [$index] ${segment.type} lines ${segment.startLine}-${segment.endLine}\n")
            if (segment.content.length > 50) {
                debugInfo.append("       Content: ${segment.content.take(50)}...\n")
            } else {
                debugInfo.append("       Content: ${segment.content}\n")
            }
        }
        debugInfo.append("\n")
        
        // Code Vision status
//        debugInfo.append("CODE VISION:\n")
//        val codeVisionEnabled = CodeVisionInitializer.getInstance(project).isEnabled
//        debugInfo.append("  Code Vision enabled: $codeVisionEnabled\n")
        
        // Show the debug info
        Messages.showMessageDialog(
            project,
            debugInfo.toString(),
            "Inline Chat Debug Information",
            Messages.getInformationIcon()
        )
        
        // Also print to console
        System.out.println("\n" + debugInfo.toString())
        
        // Force refresh
        ApplicationManager.getApplication().invokeLater {
            // Force code analyzer refresh
            DaemonCodeAnalyzer.getInstance(project).restart()
            
            // Force editor repaint
            editor.contentComponent.repaint()
            
            System.out.println("Forced refresh of code analyzer and editor")
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
