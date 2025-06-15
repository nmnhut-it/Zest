package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.zps.zest.completion.ZestMethodRewriteService

/**
 * Action to manually trigger method rewrite at cursor position
 */
class ZestTriggerMethodRewriteAction : AnAction("Trigger Method Rewrite"), HasPriority {
    private val logger = Logger.getInstance(ZestTriggerMethodRewriteAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val methodRewriteService = project.serviceOrNull<ZestMethodRewriteService>()
        
        if (methodRewriteService == null) {
            logger.warn("ZestMethodRewriteService not available")
            return
        }
        
        // Check if another rewrite is in progress
        if (methodRewriteService.isRewriteInProgress()) {
            Messages.showWarningDialog(
                project,
                "A method rewrite operation is already in progress. Please wait for it to complete or cancel it first.",
                "Method Rewrite In Progress"
            )
            return
        }
        
        val offset = editor.caretModel.primaryCaret.offset
        
        // Ask user for custom instructions (optional)
        val customInstruction = Messages.showInputDialog(
            project,
            "Enter custom instructions for the method rewrite (optional):\nLeave empty for general improvements.",
            "Method Rewrite Instructions",
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
        
        // Trigger the method rewrite
        val instruction = if (customInstruction.trim().isEmpty()) null else customInstruction.trim()
        methodRewriteService.rewriteCurrentMethod(editor, offset, instruction)
        
        logger.info("Triggered method rewrite at offset $offset with custom instruction: ${instruction != null}")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val methodRewriteService = project?.serviceOrNull<ZestMethodRewriteService>()
        
        val isAvailable = project != null && 
                         editor != null && 
                         methodRewriteService != null &&
                         !editor.isDisposed
        
        e.presentation.isEnabledAndVisible = isAvailable
        
        // Update description based on service state
        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Method Rewrite (In Progress...)"
            e.presentation.description = "A method rewrite is currently in progress"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Trigger Method Rewrite"
            e.presentation.description = "Rewrite the current method with AI improvements"
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
