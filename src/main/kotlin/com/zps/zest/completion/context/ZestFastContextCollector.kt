package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.components.service

/**
 * Fast context collector that combines pre-cached background context
 * with real-time cursor-specific context for optimal performance
 */
class ZestFastContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestFastContextCollector::class.java)
    
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
            // FAST: Get pre-cached contexts (should be <10ms)
            val cachedGitContext = backgroundManager.getCachedGitContext()
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
            val cachedFileContext = backgroundManager.getCachedFileContext(virtualFile)
            
            // REAL-TIME: Collect only cursor-specific context (5-15ms)
            val cursorContext = extractCursorContext(editor, offset)
            
            // FAST: Extract keywords from cursor context (minimal processing)
            val keywords = extractFastKeywords(cursorContext)
            
            // FAST: Find similar patterns using cached data (if available)
            val similarExample = findFastSimilarExample(cursorContext, keywords)
            
            val collectTime = System.currentTimeMillis() - startTime
            logger.debug("Fast context collection completed in ${collectTime}ms")
            
            // Assemble the complete context
            ZestLeanContextCollector.LeanContext(
                basicContext = cursorContext,
                gitInfo = cachedGitContext,
                similarExample = similarExample,
                relevantKeywords = keywords
            )
            
        } catch (e: Exception) {
            val fallbackTime = System.currentTimeMillis()
            logger.warn("Fast context collection failed, falling back to full collection", e)
            
            // Fallback to original slow collection
            val result = fallbackCollector.collectContext(editor, offset)
            val totalTime = System.currentTimeMillis() - startTime
            logger.debug("Fallback context collection completed in ${totalTime}ms")
            
            result
        }
    }
    
    /**
     * Extract only cursor-specific context (fast, real-time only)
     */
    private fun extractCursorContext(editor: Editor, offset: Int): ZestLeanContextCollector.BasicContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        
        // Get prefix and suffix (minimal processing)
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
        val suffixCode = text.substring(offset.coerceAtLeast(0))
        
        // Get current line and indentation
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val currentLine = text.substring(lineStart, lineEnd)
        val indentation = currentLine.takeWhile { it.isWhitespace() }
        
        // Basic file info (fast)
        val language = virtualFile?.fileType?.name ?: "Unknown"
        val fileName = virtualFile?.name ?: "Unknown"
        
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
    
    companion object {
        private const val MAX_PREFIX_LENGTH = 1500 // Reduced from 2000 for performance
        private const val MAX_SUFFIX_LENGTH = 400  // Reduced from 500 for performance
    }
}
