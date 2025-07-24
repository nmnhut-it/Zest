package com.zps.zest.completion.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.zps.zest.completion.util.IndentationNormalizer

/**
 * Alternative approach using LightVirtualFile for better syntax highlighting
 */
object MethodRewriteDiffDialogV2 {
    private val logger = Logger.getInstance(MethodRewriteDiffDialogV2::class.java)
    
    fun show(
        project: Project,
        editor: Editor,
        methodStartOffset: Int,
        methodEndOffset: Int,
        originalContent: String,
        modifiedContent: String,
        fileType: FileType,
        methodName: String,
        onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Normalize indentation
                val (normalizedOriginal, normalizedModified) = IndentationNormalizer.normalizeForDiff(
                    originalContent,
                    modifiedContent
                )
                
                // Create virtual files with proper file type
                val originalFile = LightVirtualFile("${methodName}_original.${fileType.defaultExtension}", fileType, normalizedOriginal)
                val modifiedFile = LightVirtualFile("${methodName}_modified.${fileType.defaultExtension}", fileType, normalizedModified)
                
                // Create diff contents from virtual files
                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = diffContentFactory.create(project, originalFile)
                val rightContent = diffContentFactory.create(project, modifiedFile)
                
                // Create diff request
                val diffRequest = SimpleDiffRequest(
                    "$methodName - Method Rewrite",
                    leftContent,
                    rightContent,
                    "Original",
                    "AI Improved"
                )
                
                // Add custom actions
                val acceptAction = object : com.intellij.openapi.actionSystem.AnAction(
                    "Accept Changes",
                    "Apply the AI-improved changes",
                    com.intellij.icons.AllIcons.Actions.Commit
                ) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        logger.info("User accepted method rewrite")
                        onAccept()
                        closeWindow(e)
                    }
                }
                
                val rejectAction = object : com.intellij.openapi.actionSystem.AnAction(
                    "Reject Changes",
                    "Discard the AI-improved changes",
                    com.intellij.icons.AllIcons.Actions.Cancel
                ) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        logger.info("User rejected method rewrite")
                        onReject()
                        closeWindow(e)
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
    
    private fun closeWindow(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        e.project?.let { proj ->
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                .getToolWindow("Diff") ?: 
                com.intellij.openapi.wm.ToolWindowManager.getInstance(proj)
                .getToolWindow("Compare")
            toolWindow?.hide()
        }
    }
}