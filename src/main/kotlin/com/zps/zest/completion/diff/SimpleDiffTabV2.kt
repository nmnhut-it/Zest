package com.zps.zest.completion.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentFactory

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
                // Create diff contents
                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = diffContentFactory.create(originalContent)
                val rightContent = diffContentFactory.create(modifiedContent)
                
                // Create diff request
                val diffRequest = SimpleDiffRequest(
                    "$methodName - Method Rewrite",
                    leftContent,
                    rightContent,
                    "Original",
                    "AI Improved"
                )
                
                // Create custom actions
                val acceptAction = object : AnAction("Accept Changes") {
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
                }
                
                val rejectAction = object : AnAction("Reject") {
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
}