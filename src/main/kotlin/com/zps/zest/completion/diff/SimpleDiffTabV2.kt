package com.zps.zest.completion.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentFactory
import javax.swing.Icon

/**
 * Shows diff in a new tab using IntelliJ's standard diff viewer
 */
object SimpleDiffTabV2 {
    private val logger = Logger.getInstance(SimpleDiffTabV2::class.java)
    
    fun showDiff(
        project: Project,
        originalContent: String,
        modifiedContent: String,
        fileType: FileType,
        methodName: String,
        onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Add context lines before and after the method
                val contextLines = 100
                val extendedOriginal = addContextToMethod(originalContent, contextLines)
                val extendedModified = addContextToMethod(modifiedContent, contextLines)
                
                // Create diff contents
                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = diffContentFactory.create(extendedOriginal)
                val rightContent = diffContentFactory.create(extendedModified)
                
                // Create diff request
                val diffRequest = SimpleDiffRequest(
                    "$methodName - Method Rewrite",
                    leftContent,
                    rightContent,
                    "Original",
                    "AI Improved"
                )
                
                // Create custom actions with prominent styling
                val acceptAction = object : AnAction("Accept Changes", "Apply the AI-improved changes", AllIcons.Actions.Commit) {
                    override fun actionPerformed(e: AnActionEvent) {
                        logger.info("User accepted method rewrite")
                        onAccept()
                        // Close the diff window
                        e.project?.let { proj ->
                            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                                .getToolWindow("Diff") ?: 
                                com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                                .getToolWindow("Compare")
                            toolWindow?.hide()
                        }
                    }
                    
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isEnabledAndVisible = true
                        // Make it look like a primary action
                        e.presentation.putClientProperty("ActionButton.DEFAULT", true)
                    }
                }

                val rejectAction = object : AnAction("Reject Changes", "Discard the AI-improved changes", AllIcons.Actions.Cancel) {
                    override fun actionPerformed(e: AnActionEvent) {
                        logger.info("User rejected method rewrite")
                        onReject()
                        // Close the diff window
                        e.project?.let { proj ->
                            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                                .getToolWindow("Diff") ?: 
                                com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                                .getToolWindow("Compare")
                            toolWindow?.hide()
                        }
                    }
                    
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isEnabledAndVisible = true
                    }
                }
                
                // Add actions to the diff request
                val actions = listOf(acceptAction, rejectAction)
                diffRequest.putUserData(com.intellij.diff.util.DiffUserDataKeys.CONTEXT_ACTIONS, actions)
                
                // Show diff
                DiffManager.getInstance().showDiff(project, diffRequest)
                
            } catch (e: Exception) {
                logger.error("Failed to show diff", e)
            }
        }
    }
    
    private fun addContextToMethod(methodContent: String, contextLines: Int): String {
        // For now, just add some placeholder context
        // In a real implementation, you'd get this from the actual file
        val contextPrefix = "// ... ${contextLines} lines of context before method ...\n\n"
        val contextSuffix = "\n\n// ... ${contextLines} lines of context after method ..."
        
        return contextPrefix + methodContent + contextSuffix
    }
}