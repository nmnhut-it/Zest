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
 * Includes context blocks before and after for better AI understanding
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
        val fullFileContent: String,
        // Enhanced context for better AI understanding
        val beforeBlock: String?,
        val afterBlock: String?,
        val extendedContext: String,
        val beforeBlockOffset: Int?,
        val afterBlockOffset: Int?
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
     * Collect context for block-level rewriting with enhanced before/after context
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
        
        // Collect enhanced context: before and after blocks
        val contextInfo = collectEnhancedContext(fullFileContent, blockInfo, psiFile)
        
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
            fullFileContent = fullFileContent,
            beforeBlock = contextInfo.beforeBlock,
            afterBlock = contextInfo.afterBlock,
            extendedContext = contextInfo.extendedContext,
            beforeBlockOffset = contextInfo.beforeBlockOffset,
            afterBlockOffset = contextInfo.afterBlockOffset
        )
    }
    
    /**
     * Collect enhanced context including blocks before and after the target block
     */
    private fun collectEnhancedContext(
        fullContent: String,
        blockInfo: BlockInfo,
        psiFile: PsiFile?
    ): EnhancedContextInfo {
        val lines = fullContent.lines()
        val blockStartLine = fullContent.substring(0, blockInfo.startOffset).count { it == '\n' }
        val blockEndLine = fullContent.substring(0, blockInfo.endOffset).count { it == '\n' }
        
        // Try to find logical blocks before and after using PSI if available
        val beforeBlockInfo = findBlockBefore(lines, blockStartLine, psiFile, blockInfo)
        val afterBlockInfo = findBlockAfter(lines, blockEndLine, psiFile, blockInfo)
        
        // Create extended context that includes before + current + after
        val extendedStartLine = beforeBlockInfo?.startLine ?: maxOf(0, blockStartLine - 5)
        val extendedEndLine = afterBlockInfo?.endLine ?: minOf(lines.size - 1, blockEndLine + 5)
        
        val extendedContext = lines.subList(extendedStartLine, extendedEndLine + 1).joinToString("\n")
        
        return EnhancedContextInfo(
            beforeBlock = beforeBlockInfo?.blockText,
            afterBlock = afterBlockInfo?.blockText,
            extendedContext = extendedContext,
            beforeBlockOffset = beforeBlockInfo?.startOffset,
            afterBlockOffset = afterBlockInfo?.startOffset
        )
    }
    
    /**
     * Find a logical block before the target block
     */
    private fun findBlockBefore(
        lines: List<String>,
        targetBlockStartLine: Int,
        psiFile: PsiFile?,
        targetBlockInfo: BlockInfo
    ): BlockInfo? {
        // Search backwards for a logical block
        for (i in targetBlockStartLine - 1 downTo maxOf(0, targetBlockStartLine - 10)) {
            val candidate = detectBlockAtLine(lines, i, psiFile)
            if (candidate != null && candidate.endLine < targetBlockStartLine) {
                return candidate
            }
        }
        
        // Fallback: grab previous 3-5 lines as context
        val startLine = maxOf(0, targetBlockStartLine - 5)
        val endLine = targetBlockStartLine - 1
        if (startLine <= endLine) {
            val blockText = lines.subList(startLine, endLine + 1).joinToString("\n")
            val startOffset = lines.take(startLine).sumOf { it.length + 1 }
            return BlockInfo(
                type = BlockType.LINE,
                blockText = blockText,
                startOffset = startOffset,
                endOffset = startOffset + blockText.length,
                description = "Context before",
                startLine = startLine,
                endLine = endLine
            )
        }
        
        return null
    }
    
    /**
     * Find a logical block after the target block
     */
    private fun findBlockAfter(
        lines: List<String>,
        targetBlockEndLine: Int,
        psiFile: PsiFile?,
        targetBlockInfo: BlockInfo
    ): BlockInfo? {
        // Search forwards for a logical block
        for (i in targetBlockEndLine + 1 until minOf(lines.size, targetBlockEndLine + 10)) {
            val candidate = detectBlockAtLine(lines, i, psiFile)
            if (candidate != null && candidate.startLine > targetBlockEndLine) {
                return candidate
            }
        }
        
        // Fallback: grab next 3-5 lines as context
        val startLine = targetBlockEndLine + 1
        val endLine = minOf(lines.size - 1, targetBlockEndLine + 5)
        if (startLine <= endLine && startLine < lines.size) {
            val blockText = lines.subList(startLine, endLine + 1).joinToString("\n")
            val startOffset = lines.take(startLine).sumOf { it.length + 1 }
            return BlockInfo(
                type = BlockType.LINE,
                blockText = blockText,
                startOffset = startOffset,
                endOffset = startOffset + blockText.length,
                description = "Context after",
                startLine = startLine,
                endLine = endLine
            )
        }
        
        return null
    }
    
    /**
     * Try to detect a block starting at a specific line
     */
    private fun detectBlockAtLine(lines: List<String>, lineNumber: Int, psiFile: PsiFile?): BlockInfo? {
        if (lineNumber >= lines.size || lineNumber < 0) return null
        
        val line = lines[lineNumber].trim()
        
        // Look for method signatures
        val methodPatterns = listOf(
            Regex("""^\s*(public|private|protected|static|final)?\s*\w+\s+\w+\s*\([^)]*\)\s*\{?\s*$"""),
            Regex("""^\s*fun\s+\w+\s*\([^)]*\).*\{?\s*$"""),
            Regex("""^\s*function\s+\w+\s*\([^)]*\)\s*\{?\s*$"""),
            Regex("""^\s*def\s+\w+\s*\([^)]*\).*:?\s*$""")
        )
        
        if (methodPatterns.any { it.matches(line) }) {
            // Try to find the end of this method
            val methodEnd = findBlockEnd(lines, lineNumber)
            if (methodEnd != null) {
                val blockText = lines.subList(lineNumber, methodEnd + 1).joinToString("\n")
                val startOffset = lines.take(lineNumber).sumOf { it.length + 1 }
                return BlockInfo(
                    type = BlockType.METHOD,
                    blockText = blockText,
                    startOffset = startOffset,
                    endOffset = startOffset + blockText.length,
                    description = "Method",
                    startLine = lineNumber,
                    endLine = methodEnd
                )
            }
        }
        
        // Look for class signatures
        if (line.matches(Regex("""^\s*(public|private|protected)?\s*(class|interface|enum)\s+\w+.*"""))) {
            val classEnd = findBlockEnd(lines, lineNumber)
            if (classEnd != null) {
                val blockText = lines.subList(lineNumber, classEnd + 1).joinToString("\n")
                val startOffset = lines.take(lineNumber).sumOf { it.length + 1 }
                return BlockInfo(
                    type = BlockType.CLASS,
                    blockText = blockText,
                    startOffset = startOffset,
                    endOffset = startOffset + blockText.length,
                    description = "Class",
                    startLine = lineNumber,
                    endLine = classEnd
                )
            }
        }
        
        return null
    }
    
    /**
     * Find the end of a block starting at the given line
     */
    private fun findBlockEnd(lines: List<String>, startLine: Int): Int? {
        var braceCount = 0
        var foundOpenBrace = false
        
        for (i in startLine until lines.size) {
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
                            return i
                        }
                    }
                }
            }
        }
        
        return null
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
                    description = "Method: ${method.name}",
                    startLine = fullFileContent.substring(0, startOffset).count { it == '\n' },
                    endLine = fullFileContent.substring(0, endOffset).count { it == '\n' }
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
                    description = "Code block",
                    startLine = fullFileContent.substring(0, startOffset).count { it == '\n' },
                    endLine = fullFileContent.substring(0, endOffset).count { it == '\n' }
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
                    description = "Statement",
                    startLine = fullFileContent.substring(0, startOffset).count { it == '\n' },
                    endLine = fullFileContent.substring(0, endOffset).count { it == '\n' }
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
                    description = "Class: ${psiClass.name}",
                    startLine = fullFileContent.substring(0, startOffset).count { it == '\n' },
                    endLine = fullFileContent.substring(0, endOffset).count { it == '\n' }
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
                description = "Method (detected textually)",
                startLine = methodBoundaries.first,
                endLine = methodBoundaries.second
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
                description = "Code block (detected textually)",
                startLine = blockBoundaries.first,
                endLine = blockBoundaries.second
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
            description = "Current line",
            startLine = offsetToLine,
            endLine = offsetToLine
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
        val description: String,
        val startLine: Int,
        val endLine: Int
    )
    
    private data class EnhancedContextInfo(
        val beforeBlock: String?,
        val afterBlock: String?,
        val extendedContext: String,
        val beforeBlockOffset: Int?,
        val afterBlockOffset: Int?
    )
}
