package com.zps.zest.gdiff.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.zps.zest.gdiff.GDiff
import com.zps.zest.gdiff.GDiffVfsUtil

/**
 * IntelliJ Action to compare two selected files using GDiff
 */
class CompareFilesAction : AnAction("Compare Files with GDiff") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        
        when (selectedFiles.size) {
            2 -> {
                compareFiles(selectedFiles[0], selectedFiles[1])
            }
            1 -> {
                // Compare with clipboard or show diff with saved version
                compareFileWithSaved(selectedFiles[0])
            }
            else -> {
                Messages.showInfoMessage(
                    "Please select 1 or 2 files to compare",
                    "GDiff"
                )
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val enabled = selectedFiles != null && 
                     selectedFiles.size in 1..2 && 
                     selectedFiles.all { !it.isDirectory }
        
        e.presentation.isEnabled = enabled
    }
    
    private fun compareFiles(file1: VirtualFile, file2: VirtualFile) {
        try {
            val config = GDiff.DiffConfig(
                ignoreWhitespace = false,
                ignoreCase = false
            )
            
            val result = GDiffVfsUtil.diffVirtualFiles(file1, file2, config)
            val stats = result.getStatistics()
            
            val message = if (result.identical) {
                "Files are identical!"
            } else {
                buildString {
                    appendLine("Comparison Results:")
                    appendLine("${file1.name} vs ${file2.name}")
                    appendLine()
                    appendLine("Statistics:")
                    appendLine("• Added lines: ${stats.additions}")
                    appendLine("• Deleted lines: ${stats.deletions}")
                    appendLine("• Modified lines: ${stats.modifications}")
                    appendLine("• Total changes: ${stats.totalChanges}")
                    appendLine()
                    appendLine("Unified Diff:")
                    appendLine(GDiffVfsUtil.generateUnifiedDiffForFiles(file1, file2, config))
                }
            }
            
            Messages.showInfoMessage(message, "GDiff Results")
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                "Error comparing files: ${e.message}",
                "GDiff Error"
            )
        }
    }
    
    private fun compareFileWithSaved(file: VirtualFile) {
        try {
            if (GDiffVfsUtil.hasUnsavedChanges(file)) {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance()
                    .getDocument(file)
                
                if (document != null) {
                    val result = GDiffVfsUtil.diffFileWithDocument(file, document)
                    val stats = result.getStatistics()
                    
                    val message = buildString {
                        appendLine("Unsaved Changes in ${file.name}:")
                        appendLine()
                        appendLine("Statistics:")
                        appendLine("• Added lines: ${stats.additions}")
                        appendLine("• Deleted lines: ${stats.deletions}")
                        appendLine("• Modified lines: ${stats.modifications}")
                        appendLine("• Total changes: ${stats.totalChanges}")
                    }
                    
                    Messages.showInfoMessage(message, "GDiff - Unsaved Changes")
                } else {
                    Messages.showInfoMessage("File has no unsaved changes", "GDiff")
                }
            } else {
                Messages.showInfoMessage("File has no unsaved changes", "GDiff")
            }
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                "Error checking file changes: ${e.message}",
                "GDiff Error"
            )
        }
    }
}
