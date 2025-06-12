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
            
            // Smart context extraction based on method boundaries
            val (prefixCode, suffixCode) = extractSmartContext(document, offset, language)
            
            return CompletionContext(
                fileName = fileName,
                language = language,
                prefixCode = prefixCode,
                suffixCode = suffixCode,
                offset = offset,
                manually = manually
            )
        }
        
        private fun extractSmartContext(document: Document, offset: Int, language: String): Pair<String, String> {
            val text = document.text
            
            // For code languages, try to find method boundaries
            if (isCodeLanguage(language)) {
                val methodBoundaries = findCurrentMethodBoundaries(text, offset)
                
                if (methodBoundaries != null) {
                    // We're inside a method - show focused method context
                    val (methodStart, methodEnd) = methodBoundaries
                    
                    // Include method signature and content up to cursor
                    val methodPrefix = text.substring(methodStart, offset)
                    
                    // Include limited content after cursor within method
                    val remainingInMethod = minOf(methodEnd, offset + MAX_SUFFIX_IN_METHOD)
                    val methodSuffix = text.substring(offset, remainingInMethod)
                    
                    return Pair(methodPrefix, methodSuffix)
                }
            }
            
            // Fallback to improved boundary detection
            val prefixStart = findGoodPrefixStart(text, offset)
            val suffixEnd = findGoodSuffixEnd(text, offset)
            
            return Pair(
                text.substring(prefixStart, offset),
                text.substring(offset, suffixEnd)
            )
        }
        
        private fun findCurrentMethodBoundaries(text: String, offset: Int): Pair<Int, Int>? {
            // Look backwards for method signature (including annotations)
            val beforeCursor = text.substring(0, offset)
            val methodPattern = Regex(
                """(@\w+[^)]*\)[\s\n]*)*\s*(public|private|protected|static|final)[\s\w<>\[\],]*\s+\w+\s*\([^)]*\)\s*(\{|throws[^{]*\{)""",
                RegexOption.MULTILINE
            )
            
            val methodMatches = methodPattern.findAll(beforeCursor).toList()
            val lastMethodMatch = methodMatches.lastOrNull() ?: return null
            
            val methodStart = lastMethodMatch.range.start
            
            // Find method end by counting braces
            val methodEnd = findMatchingBrace(text, lastMethodMatch.range.endInclusive)
            
            return if (methodEnd != null && methodEnd > offset) {
                Pair(methodStart, methodEnd)
            } else null
        }
        
        private fun findMatchingBrace(text: String, startPos: Int): Int? {
            var braceLevel = 0
            var foundOpenBrace = false
            
            for (i in startPos until text.length) {
                when (text[i]) {
                    '{' -> {
                        braceLevel++
                        foundOpenBrace = true
                    }
                    '}' -> {
                        braceLevel--
                        if (foundOpenBrace && braceLevel == 0) {
                            return i + 1
                        }
                    }
                }
            }
            return null
        }
        
        private fun findGoodPrefixStart(text: String, offset: Int): Int {
            val maxStart = maxOf(0, offset - MAX_PREFIX_LENGTH)
            
            // Try to start at a complete line
            for (i in maxStart until offset) {
                if (text[i] == '\n') {
                    return i + 1
                }
            }
            
            return maxStart
        }
        
        private fun findGoodSuffixEnd(text: String, offset: Int): Int {
            val maxEnd = minOf(text.length, offset + MAX_SUFFIX_LENGTH)
            
            // Try to end at a complete line
            for (i in (offset + 1) until maxEnd) {
                if (text[i] == '\n' && i < maxEnd - 50) { // Leave some buffer
                    return i
                }
            }
            
            return maxEnd
        }
        
        private fun isCodeLanguage(language: String): Boolean {
            val codeLanguages = setOf(
                "java", "kotlin", "javascript", "typescript", "python", 
                "csharp", "cpp", "c", "go", "rust", "scala", "groovy"
            )
            return codeLanguages.contains(language.lowercase())
        }
        
        private const val MAX_PREFIX_LENGTH = 800   // Characters before cursor
        private const val MAX_SUFFIX_LENGTH = 400   // Characters after cursor  
        private const val MAX_SUFFIX_IN_METHOD = 200 // Smaller suffix when inside method
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
