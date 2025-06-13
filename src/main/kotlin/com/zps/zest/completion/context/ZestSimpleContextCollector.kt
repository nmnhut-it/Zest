package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Simple context collector that extracts only prefix and suffix text around the cursor
 */
class ZestSimpleContextCollector {
    
    data class SimpleContext(
        val fileName: String,
        val language: String,
        val prefixCode: String,
        val suffixCode: String,
        val offset: Int
    )
    
    fun collectContext(editor: Editor, offset: Int): SimpleContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        
        // Extract prefix and suffix
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
            .takeLast(MAX_PREFIX_LENGTH) // Limit prefix length
            
        val suffixCode = text.substring(offset.coerceAtLeast(0))
            .take(MAX_SUFFIX_LENGTH) // Limit suffix length
        
        return SimpleContext(
            fileName = virtualFile?.name ?: "unknown",
            language = virtualFile?.fileType?.name ?: "text",
            prefixCode = prefixCode,
            suffixCode = suffixCode,
            offset = offset
        )
    }
    
    companion object {
        private const val MAX_PREFIX_LENGTH = 2000
        private const val MAX_SUFFIX_LENGTH = 500
    }
}
