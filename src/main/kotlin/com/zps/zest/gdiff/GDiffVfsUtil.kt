package com.zps.zest.gdiff

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.nio.charset.Charset

/**
 * Utility class for integrating GDiff with IntelliJ's Virtual File System
 */
object GDiffVfsUtil {
    
    /**
     * Compare two VirtualFiles
     */
    fun diffVirtualFiles(
        sourceFile: VirtualFile,
        targetFile: VirtualFile,
        config: GDiff.DiffConfig = GDiff.DiffConfig()
    ): GDiff.DiffResult {
        val gdiff = GDiff()
        
        val sourceContent = String(sourceFile.contentsToByteArray(), config.charset)
        val targetContent = String(targetFile.contentsToByteArray(), config.charset)
        
        val result = gdiff.diffStrings(sourceContent, targetContent, config)
        return result.copy(
            sourceFile = sourceFile.name,
            targetFile = targetFile.name
        )
    }
    
    /**
     * Compare a VirtualFile with its current document state (useful for unsaved changes)
     */
    fun diffFileWithDocument(
        file: VirtualFile,
        document: Document,
        config: GDiff.DiffConfig = GDiff.DiffConfig()
    ): GDiff.DiffResult {
        val gdiff = GDiff()
        
        val fileContent = String(file.contentsToByteArray(), config.charset)
        val documentContent = document.text
        
        val result = gdiff.diffStrings(fileContent, documentContent, config)
        return result.copy(
            sourceFile = "${file.name} (saved)",
            targetFile = "${file.name} (current)"
        )
    }
    
    /**
     * Compare current document content with a string
     */
    fun diffDocumentWithString(
        document: Document,
        targetContent: String,
        documentName: String = "document",
        config: GDiff.DiffConfig = GDiff.DiffConfig()
    ): GDiff.DiffResult {
        val gdiff = GDiff()
        
        val result = gdiff.diffStrings(document.text, targetContent, config)
        return result.copy(
            sourceFile = documentName,
            targetFile = "comparison"
        )
    }
    
    /**
     * Generate unified diff for VirtualFiles
     */
    fun generateUnifiedDiffForFiles(
        sourceFile: VirtualFile,
        targetFile: VirtualFile,
        config: GDiff.DiffConfig = GDiff.DiffConfig()
    ): String {
        val gdiff = GDiff()
        
        val sourceContent = String(sourceFile.contentsToByteArray(), config.charset)
        val targetContent = String(targetFile.contentsToByteArray(), config.charset)
        
        return gdiff.generateUnifiedDiff(
            sourceContent,
            targetContent,
            sourceFile.name,
            targetFile.name,
            config
        )
    }
    
    /**
     * Check if a file has unsaved changes by comparing with its document
     */
    fun hasUnsavedChanges(file: VirtualFile): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val fileContent = String(file.contentsToByteArray())
        return fileContent != document.text
    }
    
    /**
     * Get a quick diff summary for display in UI
     */
    fun getDiffSummary(
        sourceFile: VirtualFile,
        targetFile: VirtualFile,
        config: GDiff.DiffConfig = GDiff.DiffConfig()
    ): String {
        val result = diffVirtualFiles(sourceFile, targetFile, config)
        val stats = result.getStatistics()
        
        return when {
            result.identical -> "Files are identical"
            stats.totalChanges == 0 -> "No changes"
            else -> {
                val parts = mutableListOf<String>()
                if (stats.additions > 0) parts.add("+${stats.additions}")
                if (stats.deletions > 0) parts.add("-${stats.deletions}")
                if (stats.modifications > 0) parts.add("~${stats.modifications}")
                parts.joinToString(" ")
            }
        }
    }
}
