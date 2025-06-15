package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiCodeBlock
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Collects context for block-level code rewrites
 * Identifies and extracts complete code blocks, functions, or lines for rewriting
 */
class ZestBlockContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestBlockContextCollector::class.java)
    
    data class BlockContext(
        val fileName: String,
        val language: String,
        val cursorOffset: Int,
        val blockType: BlockType,
        val originalBlock: String,
        val blockStartOffset: Int,
        val blockEndOffset: Int,
        val surroundingCode: String,
        val contextDescription: String,
        val fullFileContent: String
    )
    
    enum class BlockType {
        METHOD,
        CLASS,
        STATEMENT,
        LINE,
        SELECTION,
        CODE_BLOCK
    }
    
    /**
     * Collect context for block-level rewriting
     */
    fun collectBlockContext(editor: Editor, offset: Int): BlockContext {
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = virtualFile?.fileType?.name ?: "text"
        val fullFileContent = document.text
        
        // Try to get PSI information for better block detection
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        
        val blockInfo = if (psiFile != null) {
            detectBlockWithPsi(psiFile, offset, fullFileContent)
        } else {
            detectBlockTextually(fullFileContent, offset)
        }
        
        return BlockContext(
            fileName = fileName,
            language = language,
            cursorOffset = offset,
            blockType = blockInfo.type,
            originalBlock = blockInfo.blockText,
            blockStartOffset = blockInfo.startOffset,
            blockEndOffset = blockInfo.endOffset,
            surroundingCode = extractSurroundingCode(fullFileContent, blockInfo.startOffset, blockInfo.endOffset),
            contextDescription = blockInfo.description,
            fullFileContent = fullFileContent
        )
    }
    
    /**
     * Use PSI to detect the appropriate code block
     */
    private fun detectBlockWithPsi(psiFile: PsiFile, offset: Int, fullFileContent: String): BlockInfo {
        try {
            val elementAtCursor = psiFile.findElementAt(offset)
            if (elementAtCursor == null) {
                return detectBlockTextually(fullFileContent, offset)
            }
            
            // Try to find the most appropriate parent element
            val method = PsiTreeUtil.getParentOfType(elementAtCursor, PsiMethod::class.java)
            if (method != null) {
                val startOffset = method.textRange.startOffset
                val endOffset = method.textRange.endOffset
                return BlockInfo(
                    type = BlockType.METHOD,
                    blockText = method.text,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    description = "Method: ${method.name}"
                )
            }
            
            // Try to find a code block
            val codeBlock = PsiTreeUtil.getParentOfType(elementAtCursor, PsiCodeBlock::class.java)
            if (codeBlock != null) {
                val startOffset = codeBlock.textRange.startOffset
                val endOffset = codeBlock.textRange.endOffset
                return BlockInfo(
                    type = BlockType.CODE_BLOCK,
                    blockText = codeBlock.text,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    description = "Code block"
                )
            }
            
            // Try to find a statement
            val statement = PsiTreeUtil.getParentOfType(elementAtCursor, PsiStatement::class.java)
            if (statement != null) {
                val startOffset = statement.textRange.startOffset
                val endOffset = statement.textRange.endOffset
                return BlockInfo(
                    type = BlockType.STATEMENT,
                    blockText = statement.text,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    description = "Statement"
                )
            }
            
            // Try to find a class
            val psiClass = PsiTreeUtil.getParentOfType(elementAtCursor, PsiClass::class.java)
            if (psiClass != null) {
                val startOffset = psiClass.textRange.startOffset
                val endOffset = psiClass.textRange.endOffset
                return BlockInfo(
                    type = BlockType.CLASS,
                    blockText = psiClass.text,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    description = "Class: ${psiClass.name}"
                )
            }
            
        } catch (e: Exception) {
            logger.warn("Failed to detect block with PSI", e)
        }
        
        // Fallback to textual detection
        return detectBlockTextually(fullFileContent, offset)
    }
    
    /**
     * Fallback textual block detection when PSI is not available or fails
     */
    private fun detectBlockTextually(text: String, offset: Int): BlockInfo {
        val lines = text.lines()
        val offsetToLine = text.substring(0, offset).count { it == '\n' }
        
        // Try to detect method boundaries
        val methodBoundaries = detectMethodBoundaries(lines, offsetToLine)
        if (methodBoundaries != null) {
            val startOffset = lines.take(methodBoundaries.first).sumOf { it.length + 1 }
            val endOffset = lines.take(methodBoundaries.second + 1).sumOf { it.length + 1 } - 1
            val blockText = lines.subList(methodBoundaries.first, methodBoundaries.second + 1).joinToString("\n")
            
            return BlockInfo(
                type = BlockType.METHOD,
                blockText = blockText,
                startOffset = startOffset,
                endOffset = endOffset,
                description = "Method (detected textually)"
            )
        }
        
        // Try to detect code block boundaries
        val blockBoundaries = detectCodeBlockBoundaries(lines, offsetToLine)
        if (blockBoundaries != null) {
            val startOffset = lines.take(blockBoundaries.first).sumOf { it.length + 1 }
            val endOffset = lines.take(blockBoundaries.second + 1).sumOf { it.length + 1 } - 1
            val blockText = lines.subList(blockBoundaries.first, blockBoundaries.second + 1).joinToString("\n")
            
            return BlockInfo(
                type = BlockType.CODE_BLOCK,
                blockText = blockText,
                startOffset = startOffset,
                endOffset = endOffset,
                description = "Code block (detected textually)"
            )
        }
        
        // Fallback to current line
        val currentLine = lines.getOrNull(offsetToLine) ?: ""
        val lineStartOffset = lines.take(offsetToLine).sumOf { it.length + 1 }
        val lineEndOffset = lineStartOffset + currentLine.length
        
        return BlockInfo(
            type = BlockType.LINE,
            blockText = currentLine,
            startOffset = lineStartOffset,
            endOffset = lineEndOffset,
            description = "Current line"
        )
    }
    
    /**
     * Detect method boundaries using textual patterns
     */
    private fun detectMethodBoundaries(lines: List<String>, cursorLine: Int): Pair<Int, Int>? {
        // Look for method signatures (simplified patterns)
        val methodPatterns = listOf(
            Regex("""^\s*(public|private|protected|static|final)?\s*\w+\s+\w+\s*\([^)]*\)\s*\{?\s*$"""),
            Regex("""^\s*fun\s+\w+\s*\([^)]*\).*\{?\s*$"""),
            Regex("""^\s*function\s+\w+\s*\([^)]*\)\s*\{?\s*$"""),
            Regex("""^\s*def\s+\w+\s*\([^)]*\).*:?\s*$""")
        )
        
        // Search backwards for method start
        var methodStart = cursorLine
        for (i in cursorLine downTo 0) {
            val line = lines[i].trim()
            if (methodPatterns.any { it.matches(line) }) {
                methodStart = i
                break
            }
        }
        
        // Search forwards for method end (matching braces)
        var braceCount = 0
        var methodEnd = methodStart
        var foundOpenBrace = false
        
        for (i in methodStart until lines.size) {
            val line = lines[i]
            
            for (char in line) {
                when (char) {
                    '{' -> {
                        braceCount++
                        foundOpenBrace = true
                    }
                    '}' -> {
                        braceCount--
                        if (foundOpenBrace && braceCount == 0) {
                            methodEnd = i
                            return Pair(methodStart, methodEnd)
                        }
                    }
                }
            }
        }
        
        // If no closing brace found, include next few lines
        if (foundOpenBrace && methodEnd == methodStart) {
            methodEnd = minOf(methodStart + 10, lines.size - 1)
            return Pair(methodStart, methodEnd)
        }
        
        return null
    }
    
    /**
     * Detect code block boundaries using brace matching
     */
    private fun detectCodeBlockBoundaries(lines: List<String>, cursorLine: Int): Pair<Int, Int>? {
        // Look for opening brace above cursor
        var blockStart = cursorLine
        for (i in cursorLine downTo 0) {
            val line = lines[i]
            if (line.trim().endsWith("{")) {
                blockStart = i
                break
            }
        }
        
        // Find matching closing brace
        var braceCount = 0
        var blockEnd = blockStart
        
        for (i in blockStart until lines.size) {
            val line = lines[i]
            
            for (char in line) {
                when (char) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            blockEnd = i
                            return Pair(blockStart, blockEnd)
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract surrounding code for context
     */
    private fun extractSurroundingCode(text: String, blockStart: Int, blockEnd: Int): String {
        val lines = text.lines()
        val startLine = text.substring(0, blockStart).count { it == '\n' }
        val endLine = text.substring(0, blockEnd).count { it == '\n' }
        
        val contextStart = maxOf(0, startLine - 10)
        val contextEnd = minOf(lines.size - 1, endLine + 10)
        
        return lines.subList(contextStart, contextEnd + 1).joinToString("\n")
    }
    
    private data class BlockInfo(
        val type: BlockType,
        val blockText: String,
        val startOffset: Int,
        val endOffset: Int,
        val description: String
    )
}
