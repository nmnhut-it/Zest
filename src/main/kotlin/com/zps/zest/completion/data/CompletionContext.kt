package com.zps.zest.completion.data

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Context information for completion requests
 */
data class CompletionContext(
    val fileName: String,
    val language: String,
    val prefixCode: String,
    val suffixCode: String,
    val offset: Int,
    val manually: Boolean,
    val requestTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun from(editor: Editor, offset: Int, manually: Boolean = false): CompletionContext {
            val document = editor.document
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            val fileName = virtualFile?.name ?: "unknown"
            val language = virtualFile?.fileType?.name ?: "text"
            
            // Extract context around the cursor
            val prefixStart = maxOf(0, offset - MAX_PREFIX_LENGTH)
            val suffixEnd = minOf(document.textLength, offset + MAX_SUFFIX_LENGTH)
            
            val prefixCode = document.getText(TextRange(prefixStart, offset))
            val suffixCode = document.getText(TextRange(offset, suffixEnd))
            
            return CompletionContext(
                fileName = fileName,
                language = language,
                prefixCode = prefixCode,
                suffixCode = suffixCode,
                offset = offset,
                manually = manually
            )
        }
        
        private const val MAX_PREFIX_LENGTH = 2000  // Characters before cursor
        private const val MAX_SUFFIX_LENGTH = 1000  // Characters after cursor
    }
    
    /**
     * Get the line content at the cursor position
     */
    fun getCurrentLine(document: Document): String {
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        return document.getText(TextRange(lineStart, lineEnd))
    }
    
    /**
     * Get the text before cursor on the current line
     */
    fun getLinePrefixText(document: Document): String {
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        return document.getText(TextRange(lineStart, offset))
    }
    
    /**
     * Check if the cursor is at the beginning of a line
     */
    fun isAtLineStart(document: Document): Boolean {
        return getLinePrefixText(document).isBlank()
    }
}
