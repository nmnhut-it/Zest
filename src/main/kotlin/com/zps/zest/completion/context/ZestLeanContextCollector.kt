package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
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
    
    data class LeanContext(
        val basicContext: BasicContext,
        val gitInfo: ZestCompleteGitContext.CompleteGitInfo?,
        val similarExample: SimilarExample?,
        val relevantKeywords: Set<String>,
        val currentFileContext: CurrentFileContext? // NEW: Full file context
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
    
    data class CurrentFileContext(
        val fullContent: String,
        val classStructure: ClassStructure?,
        val imports: List<String>,
        val cursorPosition: CursorPosition
    )
    
    data class ClassStructure(
        val className: String,
        val fields: List<String>,
        val methods: List<MethodSignature>,
        val annotations: List<String>
    )
    
    data class MethodSignature(
        val name: String,
        val signature: String,
        val lineNumber: Int,
        val isCurrentMethod: Boolean = false
    )
    
    data class CursorPosition(
        val lineNumber: Int,
        val columnNumber: Int,
        val insideMethod: String?,
        val contextMarker: String // Marks cursor position in full file
    )
    
    suspend fun collectContext(editor: Editor, offset: Int): LeanContext {
        return try {
            // Safely access editor state with ReadAction
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
            
            // Debug: Track offset usage in context collection (using safe data)
            System.out.println("=== LEAN CONTEXT COLLECTION ===")
            System.out.println("Received offset: $offset")
            System.out.println("Editor caret offset: ${editorData.caretOffset}")
            System.out.println("Document length: ${editorData.docLength}")
            
            if (offset > editorData.docLength) {
                logger.warn("Offset $offset exceeds document length ${editorData.docLength}")
            }
            
            // Get basic context using safe data (no direct editor access)
            val basicContext = extractBasicContextSafe(editorData, offset)
            
            // Get comprehensive current file context using safe data
            val currentFileContext = extractCurrentFileContext(editorData, offset)
            
            // Get git information with actual diffs (safe to do on background thread)
            val gitInfo = try {
                gitContext.getAllModifiedFiles()
            } catch (e: kotlinx.coroutines.CancellationException) {
                System.out.println("Git context collection was cancelled")
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
                relevantKeywords = keywords,
                currentFileContext = currentFileContext
            )
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            System.out.println("Context collection was cancelled (normal behavior)")
            throw e // Rethrow CancellationException as required
        } catch (e: Exception) {
            logger.warn("Failed to collect lean context", e)
            createEmptyContext()
        }
    }
    
    private fun extractBasicContextSafe(editorData: EditorData, offset: Int): BasicContext {
        val text = editorData.text
        
        // Get prefix and suffix
        val prefixCode = text.substring(0, offset.coerceAtMost(text.length))
        val suffixCode = text.substring(offset.coerceAtLeast(0))
        
        // Get current line and indentation
        val lineNumber = editorData.document.getLineNumber(offset)
        val lineStart = editorData.document.getLineStartOffset(lineNumber)
        val lineEnd = editorData.document.getLineEndOffset(lineNumber)
        val currentLine = text.substring(lineStart, lineEnd)
        val indentation = currentLine.takeWhile { it.isWhitespace() }
        
        // Get language and filename safely from virtual file
        val language = editorData.virtualFile?.fileType?.name ?: "Unknown"
        val fileName = editorData.virtualFile?.name ?: "Unknown"
        
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
    
    private fun extractCurrentFileContext(editorData: EditorData, offset: Int): CurrentFileContext? {
        return try {
            val text = editorData.text
            
            // Get cursor position info
            val lineNumber = editorData.document.getLineNumber(offset)
            val lineStart = editorData.document.getLineStartOffset(lineNumber)
            val columnNumber = offset - lineStart
            
            // Create cursor marker in text
            val markedText = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)
            
            // Extract class structure (basic analysis without PSI)
            val classStructure = extractClassStructure(text, offset)
            
            // Extract imports
            val imports = extractImports(text)
            
            // Determine current method
            val currentMethod = findCurrentMethodName(text, offset)
            
            CurrentFileContext(
                fullContent = if (text.length > MAX_FILE_CONTENT_LENGTH) {
                    // If file is too large, include cursor area + class structure
                    extractRelevantFileContent(text, offset)
                } else {
                    markedText
                },
                classStructure = classStructure,
                imports = imports,
                cursorPosition = CursorPosition(
                    lineNumber = lineNumber,
                    columnNumber = columnNumber,
                    insideMethod = currentMethod,
                    contextMarker = "[CURSOR]"
                )
            )
        } catch (e: Exception) {
            logger.warn("Failed to extract current file context", e)
            null
        }
    }
    
    private fun extractRelevantFileContent(text: String, offset: Int): String {
        val lines = text.lines()
        val cursorLine = text.substring(0, offset).count { it == '\n' }
        
        // Include imports, class declaration, and area around cursor
        val importLines = lines.takeWhile { 
            it.trim().startsWith("import") || it.trim().startsWith("package") || it.trim().isEmpty() 
        }
        
        val classDeclarationIndex = lines.indexOfFirst { it.trim().matches(Regex(".*class\\s+\\w+.*")) }
        val classLines = if (classDeclarationIndex >= 0) {
            lines.drop(classDeclarationIndex).take(5) // Class declaration + some context
        } else emptyList()
        
        // Area around cursor
        val cursorStart = maxOf(0, cursorLine - 20)
        val cursorEnd = minOf(lines.size, cursorLine + 20)
        val cursorArea = lines.subList(cursorStart, cursorEnd).mapIndexed { index, line ->
            if (cursorStart + index == cursorLine) {
                val lineStart = lines.take(cursorLine).sumOf { it.length + 1 }
                val cursorCol = offset - lineStart
                line.substring(0, minOf(cursorCol, line.length)) + "[CURSOR]" + line.substring(minOf(cursorCol, line.length))
            } else line
        }
        
        return buildString {
            appendLine("// === FILE STRUCTURE ===")
            importLines.forEach { appendLine(it) }
            appendLine()
            classLines.forEach { appendLine(it) }
            appendLine("// ... other methods ...")
            appendLine()
            appendLine("// === CURSOR AREA ===")
            cursorArea.forEach { appendLine(it) }
        }
    }
    
    private fun extractClassStructure(text: String, offset: Int): ClassStructure? {
        return try {
            val lines = text.lines()
            
            // Find class name
            val classLine = lines.find { it.trim().matches(Regex(".*class\\s+\\w+.*")) }
            val className = classLine?.let {
                Regex("class\\s+(\\w+)").find(it)?.groupValues?.get(1)
            } ?: "Unknown"
            
            // Extract fields (simple heuristic)
            val fields = lines.filter { line ->
                val trimmed = line.trim()
                trimmed.matches(Regex("(private|public|protected|static|final)\\s+\\w+\\s+\\w+.*[;=].*")) &&
                !trimmed.contains("(") // Not a method
            }.take(10) // Limit to avoid huge lists
            
            // Extract method signatures
            val methods = extractMethodSignatures(lines, offset)
            
            // Extract class-level annotations
            val annotations = lines.filter { 
                it.trim().startsWith("@") && !it.trim().contains("(") 
            }.take(5)
            
            ClassStructure(
                className = className,
                fields = fields,
                methods = methods,
                annotations = annotations
            )
        } catch (e: Exception) {
            logger.warn("Failed to extract class structure", e)
            null
        }
    }
    
    private fun extractMethodSignatures(lines: List<String>, offset: Int): List<MethodSignature> {
        val methods = mutableListOf<MethodSignature>()
        val currentLineNumber = lines.take(offset).joinToString("\n").count { it == '\n' }
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            val methodPattern = Regex("(public|private|protected|static|final)?\\s*[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(throws[^{]*)?\\s*\\{?")
            val match = methodPattern.find(trimmed)
            
            if (match != null) {
                val methodName = match.groupValues[2]
                val isCurrentMethod = kotlin.math.abs(index - currentLineNumber) < 20 // Within 20 lines of cursor
                
                methods.add(MethodSignature(
                    name = methodName,
                    signature = trimmed.replace(Regex("\\{.*"), "").trim(),
                    lineNumber = index + 1,
                    isCurrentMethod = isCurrentMethod
                ))
            }
        }
        
        return methods.take(15) // Limit to reasonable number
    }
    
    private fun extractImports(text: String): List<String> {
        return text.lines()
            .filter { it.trim().startsWith("import ") }
            .map { it.trim() }
            .take(20) // Limit to reasonable number
    }
    
    private fun findCurrentMethodName(text: String, offset: Int): String? {
        val beforeCursor = text.substring(0, offset)
        val methodPattern = Regex("(public|private|protected|static|final)?\\s*[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(throws[^{]*)?\\s*\\{")
        
        val matches = methodPattern.findAll(beforeCursor).toList()
        return matches.lastOrNull()?.groupValues?.get(2)
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
            relevantKeywords = emptySet(),
            currentFileContext = null
        )
    }
    
    companion object {
        private const val MAX_PREFIX_LENGTH = 2000
        private const val MAX_SUFFIX_LENGTH = 500
        private const val MAX_FILE_CONTENT_LENGTH = 15000 // Limit full file content
    }
}
