package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Simplified lean context collector that uses PSI for Java code analysis
 * with non-blocking operations and circuit breaker for load management.
 */
class ZestLeanContextCollectorPSI(private val project: Project) {

    companion object {
        const val MAX_CONTEXT_LENGTH = 3000
        const val METHOD_BODY_PLACEHOLDER = " { /* method body hidden */ }"
        
        // Circuit breaker for expensive operations
        private const val MAX_CONCURRENT_REQUESTS = 3
        private val activeRequestCount = AtomicInteger(0)
        private val lastRequestTimes = ConcurrentLinkedQueue<Long>()
        private const val HIGH_LOAD_THRESHOLD = 5 // requests per second
    }

    data class LeanContext(
        val fileName: String,
        val language: String,
        val fullContent: String,
        val markedContent: String,
        val cursorOffset: Int,
        val cursorLine: Int,
        val contextType: CursorContextType,
        val isTruncated: Boolean = false,
        val preservedMethods: Set<String> = emptySet(),
        val preservedFields: Set<String> = emptySet(),
        val calledMethods: Set<String> = emptySet(),
        val usedClasses: Set<String> = emptySet(),
        val relatedClassContents: Map<String, String> = emptyMap(),
        val syntaxInstructions: String? = null,
        val astPatternMatches: List<String> = emptyList(), // Simplified
        val rankedClasses: List<String> = emptyList(),
        val relevanceScores: Map<String, Double> = emptyMap(),
        val uncommittedChanges: Any? = null, // Placeholder for compatibility
        val filePath: String = "",
        val diffContent: String = "",
        // Additional properties for pattern matches
        val similarity: Double = 0.0,
        val startLine: Int = 0,
        val endLine: Int = 0,
        val code: String = ""
    )

    enum class CursorContextType {
        METHOD_BODY,
        CLASS_DECLARATION,
        IMPORT_SECTION,
        VARIABLE_ASSIGNMENT,
        AFTER_OPENING_BRACE,
        FIELD_DECLARATION,
        ANNOTATION,
        FUNCTION_BODY,
        FUNCTION_DECLARATION,
        OBJECT_LITERAL,
        ARROW_FUNCTION,
        MODULE_IMPORT,
        MODULE_EXPORT,
        UNKNOWN
    }

    /**
     * Collects full file context using PSI for Java files.
     * This method must be called from a read action.
     */
    fun collectFullFileContext(editor: Editor, offset: Int): LeanContext {
        return ApplicationManager.getApplication().runReadAction<LeanContext> {
            collectFullFileContextInternal(editor, offset)
        }
    }

    private fun collectFullFileContextInternal(editor: Editor, offset: Int): LeanContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = detectLanguage(fileName)
        val cursorLine = document.getLineNumber(offset)
        
        // Create marked content
        val markedContent = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)
        
        // Get PSI file for Java analysis
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        
        // Detect context type
        val contextType = when {
            psiFile is PsiJavaFile -> detectJavaContextWithPSI(psiFile, offset)
            language == "javascript" -> detectJavaScriptContext(text, offset)
            else -> CursorContextType.UNKNOWN
        }

        // Apply truncation if needed
        val (finalContent, finalMarkedContent, isTruncated, preservedMethods) = when {
            text.length > MAX_CONTEXT_LENGTH -> {
                val truncatedText = text.take(MAX_CONTEXT_LENGTH)
                val truncatedMarked = markedContent.take(MAX_CONTEXT_LENGTH)
                Triple(truncatedText, truncatedMarked, true) to emptySet<String>()
            }
            else -> {
                Triple(text, markedContent, false) to emptySet<String>()
            }
        }.let { (triple, methods) ->
            val (content, marked, truncated) = triple
            listOf(content, marked, truncated, methods)
        }.let { list ->
            listOf(list[0] as String, list[1] as String, list[2] as Boolean, list[3] as Set<String>)
        }

        return LeanContext(
            fileName = fileName,
            language = language,
            fullContent = finalContent as String,
            markedContent = finalMarkedContent as String,
            cursorOffset = offset,
            cursorLine = cursorLine,
            contextType = contextType,
            isTruncated = isTruncated as Boolean,
            preservedMethods = preservedMethods as Set<String>
        )
    }

    /**
     * Collect context with dependency analysis - non-blocking version
     */
    fun collectWithDependencyAnalysis(
        editor: Editor,
        offset: Int,
        onComplete: (LeanContext) -> Unit
    ) {
        // Check if system is under high load
        if (isSystemUnderHighLoad()) {
            val context = collectFullFileContext(editor, offset)
            ApplicationManager.getApplication().invokeLater {
                onComplete(context)
            }
            return
        }
        
        // Begin request tracking
        beginRequest()
        
        try {
            // Get immediate context
            val context = collectFullFileContext(editor, offset)
            
            // For now, just return the basic context - can be enhanced later
            ApplicationManager.getApplication().invokeLater {
                try {
                    onComplete(context)
                } finally {
                    endRequest()
                }
            }
        } catch (e: Exception) {
            endRequest()
            val fallbackContext = collectFullFileContext(editor, offset)
            ApplicationManager.getApplication().invokeLater {
                onComplete(fallbackContext)
            }
        }
    }

    // Circuit breaker methods
    
    private fun isSystemUnderHighLoad(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Clean old request times
        while (lastRequestTimes.peek()?.let { currentTime - it > 1000 } == true) {
            lastRequestTimes.poll()
        }
        
        // Check concurrent requests
        if (activeRequestCount.get() >= MAX_CONCURRENT_REQUESTS) {
            return true
        }
        
        // Check request rate
        if (lastRequestTimes.size >= HIGH_LOAD_THRESHOLD) {
            return true
        }
        
        return false
    }
    
    private fun beginRequest(): Boolean {
        val currentTime = System.currentTimeMillis()
        lastRequestTimes.offer(currentTime)
        activeRequestCount.incrementAndGet()
        return true
    }
    
    private fun endRequest() {
        activeRequestCount.decrementAndGet()
    }

    // Utility methods
    
    private fun detectLanguage(fileName: String): String {
        return when {
            fileName.endsWith(".java") -> "java"
            fileName.endsWith(".js") || fileName.endsWith(".jsx") -> "javascript"
            fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> "javascript"
            else -> "text"
        }
    }

    private fun detectJavaContextWithPSI(psiFile: PsiJavaFile, offset: Int): CursorContextType {
        val element = psiFile.findElementAt(offset) ?: return CursorContextType.UNKNOWN
        
        return when {
            PsiTreeUtil.getParentOfType(element, PsiImportList::class.java) != null ->
                CursorContextType.IMPORT_SECTION
                
            PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) != null ->
                CursorContextType.ANNOTATION
                
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) != null -> {
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)!!
                if (method.body?.textRange?.contains(offset) == true) {
                    CursorContextType.METHOD_BODY
                } else {
                    CursorContextType.CLASS_DECLARATION
                }
            }
            
            PsiTreeUtil.getParentOfType(element, PsiField::class.java) != null ->
                CursorContextType.FIELD_DECLARATION
                
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java) != null ->
                CursorContextType.CLASS_DECLARATION
                
            else -> CursorContextType.UNKNOWN
        }
    }

    private fun detectJavaScriptContext(text: String, offset: Int): CursorContextType {
        val beforeCursor = text.substring(0, offset)
        val currentLine = beforeCursor.lines().lastOrNull() ?: ""

        return when {
            currentLine.trim().startsWith("import ") -> CursorContextType.MODULE_IMPORT
            currentLine.trim().startsWith("export ") -> CursorContextType.MODULE_EXPORT
            currentLine.contains("function") -> CursorContextType.FUNCTION_BODY
            currentLine.contains("=>") -> CursorContextType.ARROW_FUNCTION
            else -> CursorContextType.UNKNOWN
        }
    }
}