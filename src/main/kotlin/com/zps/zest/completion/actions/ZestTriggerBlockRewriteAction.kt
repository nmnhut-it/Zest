package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.zps.zest.completion.ZestBlockRewriteService

/**
 * Action to manually trigger block rewrite at cursor position
 */
class ZestTriggerBlockRewriteAction : AnAction("Trigger Block Rewrite"), HasPriority {
    private val logger = Logger.getInstance(ZestTriggerBlockRewriteAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val blockRewriteService = project.serviceOrNull<ZestBlockRewriteService>()
        
        if (blockRewriteService == null) {
            logger.warn("ZestBlockRewriteService not available")
            return
        }
        
        // Check if another rewrite is in progress
        if (blockRewriteService.isRewriteInProgress()) {
            Messages.showWarningDialog(
                project,
                "A block rewrite operation is already in progress. Please wait for it to complete or cancel it first.",
                "Block Rewrite In Progress"
            )
            return
        }
        
        val offset = editor.caretModel.primaryCaret.offset
        
        // Ask user for custom instructions (optional)
        val customInstruction = Messages.showInputDialog(
            project,
            "Enter custom instructions for the block rewrite (optional):\nLeave empty for general improvements.",
            "Block Rewrite Instructions",
            Messages.getQuestionIcon(),
            "",
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean = true
                override fun canClose(inputString: String): Boolean = true
            }
        )
        
        // If user cancelled, don't proceed
        if (customInstruction == null) {
            return
        }
        
        // Trigger the block rewrite
        val instruction = if (customInstruction.trim().isEmpty()) null else customInstruction.trim()
        blockRewriteService.triggerBlockRewrite(editor, offset, instruction)
        
        logger.info("Triggered block rewrite at offset $offset with custom instruction: ${instruction != null}")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val blockRewriteService = project?.serviceOrNull<ZestBlockRewriteService>()
        
        val isAvailable = project != null && 
                         editor != null && 
                         blockRewriteService != null &&
                         !editor.isDisposed
        
        e.presentation.isEnabledAndVisible = isAvailable
        
        // Update description based on service state
        if (blockRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Block Rewrite (In Progress...)"
            e.presentation.description = "A block rewrite is currently in progress"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Trigger Block Rewrite"
            e.presentation.description = "Rewrite the current code block/function with AI improvements"
            e.presentation.isEnabled = isAvailable
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
    
    /**
     * High priority for manual triggers
     */
    override val priority: Int = 17
}
