package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lean context collector that gathers relevant information for code completion
 * without being too heavy on performance
 */
class ZestLeanContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestLeanContextCollector::class.java)
    private val gitContext = ZestCompleteGitContext(project)
    
    data class LeanContext(
        val basicContext: BasicContext,
        val gitInfo: ZestCompleteGitContext.CompleteGitInfo?,
        val similarExample: SimilarExample?,
        val relevantKeywords: Set<String>
    )
    
    data class BasicContext(
        val fileName: String,
        val language: String,
        val prefixCode: String,
        val suffixCode: String,
        val currentLine: String,
        val indentation: String
    )
    
    data class SimilarExample(
        val context: String,
        val content: String
    )
    
    suspend fun collectContext(editor: Editor, offset: Int): LeanContext {
        return try {
            // Collect context using safe, non-PSI methods
            val document = editor.document
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            
            // Get basic context without PSI access
            val basicContext = extractBasicContextSafe(editor, offset, virtualFile)
            
            // Get git information (safe to do on background thread)
            val gitInfo = try {
                gitContext.getAllModifiedFiles()
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.debug("Git context collection was cancelled")
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to get git context", e)
                null
            }
            
            // Extract relevant keywords from current context
            val keywords = extractRelevantKeywords(basicContext)
            
            // Find similar examples (simplified for now)
            val similarExample = findSimilarExample(basicContext, keywords)
            
            LeanContext(
                basicContext = basicContext,
                gitInfo = gitInfo,
                similarExample = similarExample,
                relevantKeywords = keywords
            )
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("Context collection was cancelled (normal behavior)")
            throw e // Rethrow CancellationException as required
        } catch (e: Exception) {
            logger.warn("Failed to collect lean context", e)
            createEmptyContext()
        }
    }
    
    private fun extractBasicContextSafe(editor: Editor, offset: Int, virtualFile: com.intellij.openapi.vfs.VirtualFile?): BasicContext {
        val document = editor.document
        val text = document.text
        
        // Get prefix and suffix
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
        val suffixCode = text.substring(offset.coerceAtLeast(0))
        
        // Get current line and indentation
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val currentLine = text.substring(lineStart, lineEnd)
        val indentation = currentLine.takeWhile { it.isWhitespace() }
        
        // Get language and filename safely from virtual file
        val language = virtualFile?.fileType?.name ?: "Unknown"
        val fileName = virtualFile?.name ?: "Unknown"
        
        return BasicContext(
            fileName = fileName,
            language = language,
            prefixCode = limitText(prefixCode, MAX_PREFIX_LENGTH),
            suffixCode = limitText(suffixCode, MAX_SUFFIX_LENGTH),
            currentLine = currentLine,
            indentation = indentation
        )
    }
    
    private fun extractBasicContext(editor: Editor, offset: Int, psiFile: PsiFile?): BasicContext {
        val document = editor.document
        val text = document.text
        
        // Get prefix and suffix
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
        val suffixCode = text.substring(offset.coerceAtLeast(0))
        
        // Get current line and indentation
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val currentLine = text.substring(lineStart, lineEnd)
        val indentation = currentLine.takeWhile { it.isWhitespace() }
        
        // Get language and filename safely
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val language = psiFile?.language?.displayName ?: virtualFile?.fileType?.name ?: "Unknown"
        val fileName = psiFile?.name ?: virtualFile?.name ?: "Unknown"
        
        return BasicContext(
            fileName = fileName,
            language = language,
            prefixCode = limitText(prefixCode, MAX_PREFIX_LENGTH),
            suffixCode = limitText(suffixCode, MAX_SUFFIX_LENGTH),
            currentLine = currentLine,
            indentation = indentation
        )
    }
    
    private fun extractRelevantKeywords(context: BasicContext): Set<String> {
        val keywords = mutableSetOf<String>()
        
        // Extract from current line
        val currentWords = context.currentLine
            .split(Regex("[^a-zA-Z0-9_]"))
            .filter { it.length > 2 }
            .take(5)
        keywords.addAll(currentWords)
        
        // Look for patterns in recent lines that might indicate what user is typing
        val recentLines = context.prefixCode
            .lines()
            .takeLast(15)
        
        // Find variable/field declarations and assignments that might be patterns
        val assignmentPattern = Regex("""(\w+)\s*=\s*new\s+(\w+)\([^)]*\)""")
        val declarationPattern = Regex("""(static|final|private|public)?\s*(\w+)\s+(\w+)""")
        
        recentLines.forEach { line ->
            // Look for assignment patterns
            assignmentPattern.findAll(line).forEach { match ->
                keywords.add("assignment_pattern")
                keywords.add(match.groupValues[1]) // variable name
                keywords.add(match.groupValues[2]) // constructor type
            }
            
            // Look for field declarations
            declarationPattern.findAll(line).forEach { match ->
                if (match.groupValues[3].isNotEmpty()) {
                    keywords.add(match.groupValues[3]) // field name
                }
            }
        }
        
        // Extract class/method names from recent lines
        val recentLinesText = recentLines.joinToString(" ")
        val namePattern = Regex("""(class|interface|function|def|public|private)\s+(\w+)""")
        namePattern.findAll(recentLinesText).forEach { match ->
            keywords.add(match.groupValues[2])
        }
        
        return keywords.take(12).toSet()
    }
    
    private fun findSimilarExample(context: BasicContext, keywords: Set<String>): SimilarExample? {
        // Look for assignment patterns in the recent code
        val recentLines = context.prefixCode.lines().takeLast(20)
        
        // Find similar assignment patterns
        val assignmentPattern = Regex("""(\w+)\s*=\s*new\s+(\w+)\([^)]*\);?""")
        
        val similarAssignments = recentLines.mapNotNull { line ->
            assignmentPattern.find(line)?.value
        }.filter { it.isNotBlank() }
        
        if (similarAssignments.isNotEmpty()) {
            val mostRecent = similarAssignments.last()
            return SimilarExample(
                context = "Recent assignment pattern in static block",
                content = mostRecent
            )
        }
        
        // Look for field declaration patterns
        val fieldPattern = Regex("""static\s+\w+\s+(\w+);""")
        val fieldDeclarations = recentLines.mapNotNull { line ->
            fieldPattern.find(line)?.value
        }
        
        if (fieldDeclarations.isNotEmpty()) {
            return SimilarExample(
                context = "Static field declaration pattern",
                content = fieldDeclarations.joinToString("; ")
            )
        }
        
        // Fallback: look for any constructor calls
        val constructorPattern = Regex("""new\s+\w+\([^)]*\)""")
        val constructorCalls = recentLines.mapNotNull { line ->
            constructorPattern.find(line)?.value
        }.take(2)
        
        if (constructorCalls.isNotEmpty()) {
            return SimilarExample(
                context = "Constructor pattern",
                content = constructorCalls.joinToString(", ")
            )
        }
        
        return null
    }
    
    private fun limitText(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) {
            text
        } else {
            // Try to break at line boundaries when possible
            val truncated = text.takeLast(maxLength)
            val firstNewline = truncated.indexOf('\n')
            if (firstNewline > 0 && firstNewline < maxLength / 2) {
                truncated.substring(firstNewline + 1)
            } else {
                truncated
            }
        }
    }
    
    private fun createEmptyContext(): LeanContext {
        return LeanContext(
            basicContext = BasicContext(
                fileName = "Unknown",
                language = "Unknown", 
                prefixCode = "",
                suffixCode = "",
                currentLine = "",
                indentation = ""
            ),
            gitInfo = null,
            similarExample = null,
            relevantKeywords = emptySet()
        )
    }
    
    companion object {
        private const val MAX_PREFIX_LENGTH = 2000
        private const val MAX_SUFFIX_LENGTH = 500
    }
}
