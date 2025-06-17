package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Collects context specifically for method rewrites
 * Focuses on identifying and extracting complete methods with surrounding context
 */
class ZestMethodContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestMethodContextCollector::class.java)
    
    data class MethodContext(
        val fileName: String,
        val language: String,
        val methodName: String,
        val methodContent: String,
        val methodStartOffset: Int,
        val methodEndOffset: Int,
        val methodSignature: String,
        val containingClass: String?,
        val surroundingMethods: List<SurroundingMethod>,
        val classContext: String,
        val fullFileContent: String,
        val cursorOffset: Int
    )
    
    data class SurroundingMethod(
        val name: String,
        val signature: String,
        val content: String,
        val position: MethodPosition
    )
    
    enum class MethodPosition {
        BEFORE, AFTER
    }
    
    /**
     * Find the method containing the cursor position
     */
    fun findMethodAtCursor(editor: Editor, offset: Int): MethodContext? {
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = virtualFile?.fileType?.name ?: "text"
        val fullFileContent = document.text
        
        // Try PSI-based method detection first
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile != null) {
            val psiMethod = findMethodWithPsi(psiFile, offset)
            if (psiMethod != null) {
                return buildMethodContextFromPsi(psiMethod, fileName, language, fullFileContent, offset)
            }
        }
        
        // Fallback to textual method detection
        return findMethodTextually(fullFileContent, offset, fileName, language)
    }
    
    /**
     * Use PSI to find the method containing the cursor
     */
    private fun findMethodWithPsi(psiFile: PsiFile, offset: Int): PsiMethod? {
        try {
            val elementAtCursor = psiFile.findElementAt(offset)
            if (elementAtCursor == null) return null
            
            // Find the containing method
            return PsiTreeUtil.getParentOfType(elementAtCursor, PsiMethod::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to find method with PSI", e)
            return null
        }
    }
    
    /**
     * Build method context from PSI method
     */
    private fun buildMethodContextFromPsi(
        psiMethod: PsiMethod,
        fileName: String,
        language: String,
        fullFileContent: String,
        cursorOffset: Int
    ): MethodContext {
        val methodName = psiMethod.name
        val methodContent = psiMethod.text
        val methodStartOffset = psiMethod.textRange.startOffset
        val methodEndOffset = psiMethod.textRange.endOffset
        
        // Log for debugging
        logger.debug("PSI Method boundaries: start=$methodStartOffset, end=$methodEndOffset")
        logger.debug("PSI Method content length: ${methodContent.length}")
        logger.debug("PSI Method ends with: '${methodContent.takeLast(10).replace("\n", "\\n")}'")
        
        // Get method signature
        val methodSignature = extractMethodSignature(methodContent)
        
        // Get containing class
        val containingClass = PsiTreeUtil.getParentOfType(psiMethod, PsiClass::class.java)
        val className = containingClass?.name
        
        // Get surrounding methods
        val surroundingMethods = getSurroundingMethods(containingClass, psiMethod)
        
        // Get class context
        val classContext = containingClass?.text ?: ""
        
        return MethodContext(
            fileName = fileName,
            language = language,
            methodName = methodName,
            methodContent = methodContent,
            methodStartOffset = methodStartOffset,
            methodEndOffset = methodEndOffset,
            methodSignature = methodSignature,
            containingClass = className,
            surroundingMethods = surroundingMethods,
            classContext = classContext,
            fullFileContent = fullFileContent,
            cursorOffset = cursorOffset
        )
    }
    
    /**
     * Get methods before and after the current method
     */
    private fun getSurroundingMethods(containingClass: PsiClass?, currentMethod: PsiMethod): List<SurroundingMethod> {
        if (containingClass == null) return emptyList()
        
        val allMethods = containingClass.methods.toList()
        val currentMethodIndex = allMethods.indexOf(currentMethod)
        if (currentMethodIndex == -1) return emptyList()
        
        val surrounding = mutableListOf<SurroundingMethod>()
        
        // Add previous method
        if (currentMethodIndex > 0) {
            val prevMethod = allMethods[currentMethodIndex - 1]
            surrounding.add(
                SurroundingMethod(
                    name = prevMethod.name,
                    signature = extractMethodSignature(prevMethod.text),
                    content = prevMethod.text,
                    position = MethodPosition.BEFORE
                )
            )
        }
        
        // Add next method
        if (currentMethodIndex < allMethods.size - 1) {
            val nextMethod = allMethods[currentMethodIndex + 1]
            surrounding.add(
                SurroundingMethod(
                    name = nextMethod.name,
                    signature = extractMethodSignature(nextMethod.text),
                    content = nextMethod.text,
                    position = MethodPosition.AFTER
                )
            )
        }
        
        return surrounding
    }
    
    /**
     * Fallback textual method detection when PSI is not available
     */
    private fun findMethodTextually(
        text: String,
        offset: Int,
        fileName: String,
        language: String
    ): MethodContext? {
        val lines = text.lines()
        val cursorLine = text.substring(0, offset).count { it == '\n' }
        
        // Find method boundaries using textual patterns
        val methodBoundaries = findMethodBoundariesTextually(lines, cursorLine, language)
        if (methodBoundaries == null) return null
        
        val (startLine, endLine) = methodBoundaries
        
        // Calculate offsets more accurately
        var methodStartOffset = 0
        for (i in 0 until startLine) {
            methodStartOffset += lines[i].length + 1 // +1 for newline
        }
        
        // Build method content preserving exact text including trailing characters
        val methodLines = lines.subList(startLine, endLine + 1)
        val methodContent = if (endLine < lines.size - 1) {
            // Not the last line in file, include newline
            methodLines.joinToString("\n") + "\n"
        } else {
            // Last line in file, check if original has trailing newline
            val lastLineEnd = methodStartOffset + methodLines.dropLast(1).sumOf { it.length + 1 } + methodLines.last().length
            if (lastLineEnd < text.length && text[lastLineEnd] == '\n') {
                methodLines.joinToString("\n") + "\n"
            } else {
                methodLines.joinToString("\n")
            }
        }
        
        val methodEndOffset = methodStartOffset + methodContent.length
        
        // Log for debugging
        logger.debug("Method boundaries: start=$methodStartOffset, end=$methodEndOffset, content length=${methodContent.length}")
        logger.debug("Method ends with: '${methodContent.takeLast(10).replace("\n", "\\n")}'")
        logger.debug("Original text at end offset: '${if (methodEndOffset < text.length) text.substring(methodEndOffset, minOf(methodEndOffset + 5, text.length)).replace("\n", "\\n") else "EOF"}'")

        
        // Extract method name and signature
        val methodSignature = extractMethodSignature(methodContent)
        val methodName = extractMethodName(methodSignature, language)
        
        // Get surrounding context
        val surroundingMethods = findSurroundingMethodsTextually(lines, startLine, endLine, language)
        
        return MethodContext(
            fileName = fileName,
            language = language,
            methodName = methodName,
            methodContent = methodContent,
            methodStartOffset = methodStartOffset,
            methodEndOffset = methodEndOffset,
            methodSignature = methodSignature,
            containingClass = findContainingClass(lines, startLine),
            surroundingMethods = surroundingMethods,
            classContext = extractClassContext(lines, startLine, endLine),
            fullFileContent = text,
            cursorOffset = offset
        )
    }
    
    /**
     * Find method boundaries using textual patterns
     */
    private fun findMethodBoundariesTextually(
        lines: List<String>,
        cursorLine: Int,
        language: String
    ): Pair<Int, Int>? {
        
        // Look backwards for method start
        var methodStart = -1
        for (i in cursorLine downTo 0) {
            val line = lines[i].trim()
            if (isMethodDeclaration(line, language)) {
                methodStart = i
                break
            }
        }
        
        if (methodStart == -1) return null
        
        // Look forwards for method end (matching braces)
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
        
        return if (foundOpenBrace) Pair(methodStart, methodEnd) else null
    }
    
    /**
     * Check if a line looks like a method declaration
     */
    private fun isMethodDeclaration(line: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "kotlin", "scala" -> {
                line.matches(Regex("""^\s*(public|private|protected|static|final|override|fun).*\(.*\).*\{?\s*$""")) ||
                line.matches(Regex("""^\s*fun\s+\w+\s*\(.*\).*\{?\s*$"""))
            }
            "javascript", "typescript" -> {
                line.matches(Regex("""^\s*(function\s+\w+|const\s+\w+\s*=.*function|\w+\s*:\s*function).*\(.*\).*\{?\s*$"""))
            }
            "python" -> {
                line.matches(Regex("""^\s*def\s+\w+\s*\(.*\).*:?\s*$"""))
            }
            "go" -> {
                line.matches(Regex("""^\s*func\s+(\(\w+\s+\*?\w+\)\s+)?\w+\s*\(.*\).*\{?\s*$"""))
            }
            "rust" -> {
                line.matches(Regex("""^\s*(pub\s+)?fn\s+\w+.*\(.*\).*\{?\s*$"""))
            }
            else -> {
                // Generic pattern
                line.contains("(") && line.contains(")") && 
                (line.contains("function") || line.contains("def") || line.contains("fun"))
            }
        }
    }
    
    /**
     * Extract method signature from method content
     */
    private fun extractMethodSignature(methodContent: String): String {
        val lines = methodContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("(") && trimmed.contains(")")) {
                return trimmed
            }
        }
        return lines.firstOrNull()?.trim() ?: ""
    }
    
    /**
     * Extract method name from signature
     */
    private fun extractMethodName(signature: String, language: String): String {
        return when (language.lowercase()) {
            "java", "kotlin", "scala" -> {
                val match = Regex("""(?:fun\s+)?(\w+)\s*\(""").find(signature)
                match?.groupValues?.get(1) ?: "unknown"
            }
            "javascript", "typescript" -> {
                val match = Regex("""(?:function\s+)?(\w+)\s*[:\(]""").find(signature)
                match?.groupValues?.get(1) ?: "unknown"
            }
            "python" -> {
                val match = Regex("""def\s+(\w+)\s*\(""").find(signature)
                match?.groupValues?.get(1) ?: "unknown"
            }
            "go" -> {
                val match = Regex("""func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)\s*\(""").find(signature)
                match?.groupValues?.get(1) ?: "unknown"
            }
            "rust" -> {
                val match = Regex("""fn\s+(\w+)""").find(signature)
                match?.groupValues?.get(1) ?: "unknown"
            }
            else -> "unknown"
        }
    }
    
    /**
     * Find surrounding methods textually
     */
    private fun findSurroundingMethodsTextually(
        lines: List<String>,
        currentStart: Int,
        currentEnd: Int,
        language: String
    ): List<SurroundingMethod> {
        val surrounding = mutableListOf<SurroundingMethod>()
        
        // Find previous method
        for (i in (currentStart - 1) downTo 0) {
            if (isMethodDeclaration(lines[i].trim(), language)) {
                val prevMethodBoundaries = findMethodBoundariesTextually(lines, i, language)
                if (prevMethodBoundaries != null && prevMethodBoundaries.second < currentStart) {
                    val (start, end) = prevMethodBoundaries
                    val content = lines.subList(start, end + 1).joinToString("\n")
                    val signature = extractMethodSignature(content)
                    val name = extractMethodName(signature, language)
                    
                    surrounding.add(
                        SurroundingMethod(name, signature, content, MethodPosition.BEFORE)
                    )
                    break
                }
            }
        }
        
        // Find next method
        for (i in (currentEnd + 1) until lines.size) {
            if (isMethodDeclaration(lines[i].trim(), language)) {
                val nextMethodBoundaries = findMethodBoundariesTextually(lines, i, language)
                if (nextMethodBoundaries != null && nextMethodBoundaries.first > currentEnd) {
                    val (start, end) = nextMethodBoundaries
                    val content = lines.subList(start, end + 1).joinToString("\n")
                    val signature = extractMethodSignature(content)
                    val name = extractMethodName(signature, language)
                    
                    surrounding.add(
                        SurroundingMethod(name, signature, content, MethodPosition.AFTER)
                    )
                    break
                }
            }
        }
        
        return surrounding
    }
    
    /**
     * Find containing class name
     */
    private fun findContainingClass(lines: List<String>, methodStartLine: Int): String? {
        for (i in methodStartLine downTo 0) {
            val line = lines[i].trim()
            val classMatch = Regex("""(?:public|private|protected)?\s*class\s+(\w+)""").find(line)
            if (classMatch != null) {
                return classMatch.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * Extract class context around the method
     */
    private fun extractClassContext(lines: List<String>, methodStart: Int, methodEnd: Int): String {
        val contextStart = maxOf(0, methodStart - 10)
        val contextEnd = minOf(lines.size - 1, methodEnd + 10)
        return lines.subList(contextStart, contextEnd + 1).joinToString("\n")
    }
}
