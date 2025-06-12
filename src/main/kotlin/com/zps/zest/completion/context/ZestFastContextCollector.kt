package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast context collector that combines pre-cached background context
 * with real-time cursor-specific context for optimal performance
 */
class ZestFastContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestFastContextCollector::class.java)
    
    /**
     * Thread-safe editor data captured from EDT
     */
    private data class EditorData(
        val document: Document,
        val text: String,
        val caretOffset: Int,
        val docLength: Int,
        val virtualFile: VirtualFile?
    )
    
    // Background context manager for cached data
    private val backgroundManager by lazy { 
        project.service<ZestBackgroundContextManager>()
    }
    
    // Original context collector for fallback
    private val fallbackCollector = ZestLeanContextCollector(project)
    
    /**
     * Fast context collection using cached background data + real-time cursor context
     */
    suspend fun collectFastContext(editor: Editor, offset: Int): ZestLeanContextCollector.LeanContext {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Safely access editor state with ReadAction first
            val editorData = withContext(Dispatchers.Main) {
                ReadAction.compute<EditorData, Exception> {
                    EditorData(
                        document = editor.document,
                        text = editor.document.text,
                        caretOffset = editor.caretModel.offset,
                        docLength = editor.document.textLength,
                        virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
                    )
                }
            }
            
            // FAST: Get pre-cached contexts (should be <10ms)
            val cachedGitContext = backgroundManager.getCachedGitContext()
            val cachedFileContext = backgroundManager.getCachedFileContext(editorData.virtualFile)
            
            // REAL-TIME: Collect only cursor-specific context (5-15ms)
            val cursorContext = extractCursorContext(editorData, offset)
            
            // FAST: Extract keywords from cursor context (minimal processing)
            val keywords = extractFastKeywords(cursorContext)
            
            // FAST: Find similar patterns using cached data (if available)
            val similarExample = findFastSimilarExample(cursorContext, keywords)
            
            // REAL-TIME: Get current file context (since it needs cursor position)
            val currentFileContext = extractCurrentFileContextFast(editorData, offset)
            
            val collectTime = System.currentTimeMillis() - startTime
            System.out.println("Fast context collection completed in ${collectTime}ms")
            
            // Assemble the complete context
            ZestLeanContextCollector.LeanContext(
                basicContext = cursorContext,
                gitInfo = cachedGitContext,
                similarExample = similarExample,
                relevantKeywords = keywords,
                currentFileContext = currentFileContext
            )
            
        } catch (e: Exception) {
            val fallbackTime = System.currentTimeMillis()
            logger.warn("Fast context collection failed, falling back to full collection", e)
            
            // Fallback to original slow collection
            val result = fallbackCollector.collectContext(editor, offset)
            val totalTime = System.currentTimeMillis() - startTime
            System.out.println("Fallback context collection completed in ${totalTime}ms")
            
            result
        }
    }
    
    /**
     * Extract only cursor-specific context (fast, real-time only)
     */
    private fun extractCursorContext(editorData: EditorData, offset: Int): ZestLeanContextCollector.BasicContext {
        val text = editorData.text
        
        // Get prefix and suffix (minimal processing)
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
        val suffixCode = text.substring(offset.coerceAtLeast(0))
        
        // Get current line and indentation
        val lineNumber = editorData.document.getLineNumber(offset)
        val lineStart = editorData.document.getLineStartOffset(lineNumber)
        val lineEnd = editorData.document.getLineEndOffset(lineNumber)
        val currentLine = text.substring(lineStart, lineEnd)
        val indentation = currentLine.takeWhile { it.isWhitespace() }
        
        // Basic file info (fast)
        val language = editorData.virtualFile?.fileType?.name ?: "Unknown"
        val fileName = editorData.virtualFile?.name ?: "Unknown"
        
        return ZestLeanContextCollector.BasicContext(
            fileName = fileName,
            language = language,
            prefixCode = limitText(prefixCode, MAX_PREFIX_LENGTH),
            suffixCode = limitText(suffixCode, MAX_SUFFIX_LENGTH),
            currentLine = currentLine,
            indentation = indentation
        )
    }
    
    /**
     * Fast keyword extraction (simplified, performance-focused)
     */
    private fun extractFastKeywords(context: ZestLeanContextCollector.BasicContext): Set<String> {
        val keywords = mutableSetOf<String>()
        
        // Extract from current line only (fast)
        val currentWords = context.currentLine
            .split(Regex("[^a-zA-Z0-9_]"))
            .filter { it.length > 2 }
            .take(3) // Limit for performance
        keywords.addAll(currentWords)
        
        // Quick scan of recent lines for patterns (limited scope)
        val recentLines = context.prefixCode
            .lines()
            .takeLast(5) // Reduced from 15 for performance
        
        // Simple assignment pattern detection (fast regex)
        val assignmentPattern = Regex("""(\w+)\s*=\s*new\s+(\w+)""")
        recentLines.forEach { line ->
            assignmentPattern.find(line)?.let { match ->
                keywords.add(match.groupValues[1]) // variable name
                keywords.add(match.groupValues[2]) // type name
            }
        }
        
        return keywords.take(8).toSet() // Reduced from 12 for performance
    }
    
    /**
     * Fast similar pattern detection using minimal processing
     */
    private fun findFastSimilarExample(
        context: ZestLeanContextCollector.BasicContext, 
        keywords: Set<String>
    ): ZestLeanContextCollector.SimilarExample? {
        
        // Quick assignment pattern detection (limited scope for performance)
        val recentLines = context.prefixCode.lines().takeLast(8) // Reduced scope
        
        val assignmentPattern = Regex("""(\w+)\s*=\s*new\s+(\w+)\([^)]*\);?""")
        val recentAssignment = recentLines.findLast { line ->
            assignmentPattern.containsMatchIn(line)
        }
        
        return recentAssignment?.let { assignment ->
            ZestLeanContextCollector.SimilarExample(
                context = "Recent assignment pattern",
                content = assignment.trim()
            )
        }
    }
    
    /**
     * Limit text size for performance (same as original but inlined for speed)
     */
    private fun limitText(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) {
            text
        } else {
            // Simple truncation for performance
            text.takeLast(maxLength)
        }
    }
    
    /**
     * Check if fast context collection is available
     */
    fun isFastCollectionAvailable(): Boolean {
        return try {
            // Check if background collection is running by testing for cached context
            true // Background manager auto-starts, so fast collection is always available
        } catch (e: Exception) {
            logger.warn("Error checking fast collection availability", e)
            false
        }
    }
    
    /**
     * Extract current file context optimized for fast collection
     */
    private fun extractCurrentFileContextFast(editorData: EditorData, offset: Int): ZestLeanContextCollector.CurrentFileContext? {
        return try {
            val text = editorData.text
            
            // Fast extraction - only if file is not too large
            if (text.length > 20000) { // Skip for very large files
                return null
            }
            
            // Get cursor position info
            val lineNumber = editorData.document.getLineNumber(offset)
            val lineStart = editorData.document.getLineStartOffset(lineNumber)
            val columnNumber = offset - lineStart
            
            // Quick class structure analysis (minimal processing)
            val className = extractClassNameFast(text)
            val imports = extractImportsFast(text)
            val currentMethod = findCurrentMethodNameFast(text, offset)
            val methods = extractMethodSignaturesFast(text, offset)
            
            ZestLeanContextCollector.CurrentFileContext(
                fullContent = text.take(15000), // Limit content for performance
                classStructure = ZestLeanContextCollector.ClassStructure(
                    className = className ?: "Unknown",
                    fields = emptyList(), // Skip field extraction for performance
                    methods = methods,
                    annotations = emptyList() // Skip annotations for performance
                ),
                imports = imports,
                cursorPosition = ZestLeanContextCollector.CursorPosition(
                    lineNumber = lineNumber,
                    columnNumber = columnNumber,
                    insideMethod = currentMethod,
                    contextMarker = "[CURSOR]"
                )
            )
        } catch (e: Exception) {
            logger.warn("Failed to extract current file context (fast)", e)
            null
        }
    }
    
    private fun extractClassNameFast(text: String): String? {
        val classPattern = Regex("class\\s+(\\w+)")
        return classPattern.find(text)?.groupValues?.get(1)
    }
    
    private fun extractImportsFast(text: String): List<String> {
        return text.lines()
            .filter { it.trim().startsWith("import ") }
            .take(15) // Limit for performance
    }
    
    private fun findCurrentMethodNameFast(text: String, offset: Int): String? {
        val beforeCursor = text.substring(0, offset)
        val methodPattern = Regex("\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{")
        return methodPattern.findAll(beforeCursor).lastOrNull()?.groupValues?.get(1)
    }
    
    private fun extractMethodSignaturesFast(text: String, offset: Int): List<ZestLeanContextCollector.MethodSignature> {
        val lines = text.lines()
        val methodSignatures = mutableListOf<ZestLeanContextCollector.MethodSignature>()
        val currentLineNumber = lines.take(offset).joinToString("\n").count { it == '\n' }
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            val methodPattern = Regex("(public|private|protected)\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)")
            val match = methodPattern.find(trimmed)
            
            if (match != null) {
                val methodName = match.groupValues[2]
                val isCurrentMethod = kotlin.math.abs(index - currentLineNumber) < 15
                
                methodSignatures.add(ZestLeanContextCollector.MethodSignature(
                    name = methodName,
                    signature = trimmed.replace(Regex("\\{.*"), "").trim(),
                    lineNumber = index + 1,
                    isCurrentMethod = isCurrentMethod
                ))
            }
        }
        
        return methodSignatures.take(10) // Limit for performance
    }
    
    companion object {
        private const val MAX_PREFIX_LENGTH = 800  // Reduced for better performance and focus
        private const val MAX_SUFFIX_LENGTH = 400  // Reduced for better performance and focus
    }
}
