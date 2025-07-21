package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.zps.zest.ClassAnalyzer
import com.zps.zest.completion.async.AsyncClassAnalyzer

/**
 * Collects context specifically for method rewrites
 * Focuses on identifying and extracting complete methods with surrounding context
 */
class ZestMethodContextCollector(private val project: Project) {
    private val logger = Logger.getInstance(ZestMethodContextCollector::class.java)
    private val cocosContextCollector = ZestCocos2dxContextCollector(project)
    private val asyncAnalyzer = AsyncClassAnalyzer(project)

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
        val cursorOffset: Int,
        // Cocos2d-x specific context
        val isCocos2dx: Boolean = false,
        val cocosContextType: ZestCocos2dxContextCollector.Cocos2dxContextType? = null,
        val cocosSyntaxPreferences: ZestCocos2dxContextCollector.CocosSyntaxPreferences? = null,
        val cocosCompletionHints: List<String> = emptyList(),
        val cocosFrameworkVersion: String? = null,
        // Related classes from async analysis
        val relatedClasses: Map<String, String> = emptyMap()
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
     * Data class to hold Cocos2d-x context results
     */
    private data class CocosContextResult(
        val contextType: ZestCocos2dxContextCollector.Cocos2dxContextType?,
        val syntaxPreferences: ZestCocos2dxContextCollector.CocosSyntaxPreferences?,
        val completionHints: List<String>,
        val frameworkVersion: String?
    )

    /**
     * Collect Cocos2d-x specific context using the specialized collector
     */
    private fun collectCocos2dxContext(
        fullFileContent: String,
        cursorOffset: Int,
        methodContent: String
    ): CocosContextResult {
        return try {
            // Create a mock editor to use the Cocos2d-x context collector
            // We'll analyze the method content and surrounding context
            val beforeCursor = fullFileContent.substring(0, cursorOffset)
            val afterCursor = fullFileContent.substring(cursorOffset)

            // Use the Cocos2d-x collector's analysis methods
            val contextType = detectCocos2dxContextTypeFromMethod(methodContent, beforeCursor)
            val frameworkVersion = detectCocos2dxVersionFromContent(fullFileContent)
            val syntaxPreferences = createCocosSyntaxPreferences(frameworkVersion)

            // Generate completion hints based on context
            val completionHints = generateCocosCompletionHints(contextType, syntaxPreferences, methodContent)

            CocosContextResult(contextType, syntaxPreferences, completionHints, frameworkVersion)
        } catch (e: Exception) {
            logger.warn("Failed to collect Cocos2d-x context", e)
            CocosContextResult(null, null, emptyList(), null)
        }
    }


    /**
     * Find the method containing the cursor position
     */
    fun findMethodAtCursor(editor: Editor, offset: Int): MethodContext? {
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = detectLanguage(virtualFile)
        val fullFileContent = document.text

        // Check if there's a selection first
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            // User has selected text - use that as the method content
            return createMethodContextFromSelection(
                editor = editor,
                fileName = fileName,
                language = language,
                fullFileContent = fullFileContent
            )
        }

        // No selection - detect method at cursor
        // Detect if this is a Cocos2d-x project
        val isCocos2dx = detectCocos2dxProject(fullFileContent, fileName, language)

        // Try PSI-based method detection first
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile != null) {
            val psiMethod = findMethodWithPsi(psiFile, offset)
            if (psiMethod != null) {
                return buildMethodContextFromPsi(psiMethod, fileName, language, fullFileContent, offset, isCocos2dx)
            }
        }

        // Fallback to textual method detection
        return findMethodTextually(fullFileContent, offset, fileName, language, isCocos2dx)
    }

    /**
     * Create method context from selected text
     */
    private fun createMethodContextFromSelection(
        editor: Editor,
        fileName: String,
        language: String,
        fullFileContent: String
    ): MethodContext {
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: ""
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd

        // Detect if this is Cocos2d-x content
        val isCocos2dx = detectCocos2dxProject(fullFileContent, fileName, language)

        // Extract method signature from selected text
        val methodSignature = extractMethodSignature(selectedText)
        val methodName = if (methodSignature.isNotBlank()) {
            extractMethodName(methodSignature, language)
        } else {
            "selectedMethod"
        }

        // Collect Cocos2d-x specific context if applicable
        val (cocosContextType, cocosSyntaxPreferences, cocosCompletionHints, cocosFrameworkVersion) =
            if (isCocos2dx) {
                collectCocos2dxContext(fullFileContent, startOffset, selectedText)
            } else {
                CocosContextResult(null, null, emptyList(), null)
            }

        // Find surrounding context
        val lines = fullFileContent.lines()
        val startLine = fullFileContent.substring(0, startOffset).count { it == '\n' }
        val endLine = fullFileContent.substring(0, endOffset).count { it == '\n' }

        return MethodContext(
            fileName = fileName,
            language = language,
            methodName = methodName,
            methodContent = selectedText,
            methodStartOffset = startOffset,
            methodEndOffset = endOffset,
            methodSignature = methodSignature,
            containingClass = findContainingClass(lines, startLine),
            surroundingMethods = emptyList(), // No surrounding methods for selection
            classContext = extractClassContext(lines, startLine, endLine),
            fullFileContent = fullFileContent,
            cursorOffset = startOffset,
            isCocos2dx = isCocos2dx,
            cocosContextType = cocosContextType,
            cocosSyntaxPreferences = cocosSyntaxPreferences,
            cocosCompletionHints = cocosCompletionHints,
            cocosFrameworkVersion = cocosFrameworkVersion
        )
    }

    /**
     * Detect language from file with better TypeScript support
     */
    private fun detectLanguage(virtualFile: com.intellij.openapi.vfs.VirtualFile?): String {
        return when {
            virtualFile == null -> "text"
            virtualFile.name.endsWith(".ts") -> "typescript"
            virtualFile.name.endsWith(".tsx") -> "typescript"
            virtualFile.name.endsWith(".js") -> "javascript"
            virtualFile.name.endsWith(".jsx") -> "javascript"
            virtualFile.name.endsWith(".java") -> "java"
            virtualFile.name.endsWith(".kt") -> "kotlin"
            virtualFile.name.endsWith(".py") -> "python"
            virtualFile.name.endsWith(".go") -> "go"
            virtualFile.name.endsWith(".rs") -> "rust"
            virtualFile.name.endsWith(".scala") -> "scala"
            virtualFile.name.endsWith(".rb") -> "ruby"
            virtualFile.name.endsWith(".php") -> "php"
            virtualFile.name.endsWith(".cs") -> "csharp"
            virtualFile.name.endsWith(".cpp") || virtualFile.name.endsWith(".cc") -> "cpp"
            virtualFile.name.endsWith(".c") -> "c"
            virtualFile.name.endsWith(".swift") -> "swift"
            else -> virtualFile.fileType?.name?.lowercase() ?: "text"
        }
    }

    /**
     * Find method with async analysis for related classes
     */
    fun findMethodWithAsyncAnalysis(
        editor: Editor,
        offset: Int,
        onComplete: (MethodContext) -> Unit
    ) {
        // Get immediate context first - handle EDT properly
        val immediateContext: MethodContext? = if (ApplicationManager.getApplication().isDispatchThread) {
            findMethodAtCursor(editor, offset)
        } else {
            ApplicationManager.getApplication().runReadAction<MethodContext> {
                findMethodAtCursor(editor, offset)
            }
        }

        if (immediateContext == null) {
            return
        }

        // Invoke callback on EDT
        if (ApplicationManager.getApplication().isDispatchThread) {
            onComplete(immediateContext)
        } else {
            ApplicationManager.getApplication().invokeLater {
                onComplete(immediateContext)
            }
        }

        // Only do async analysis for Java
        if (immediateContext.language.lowercase() != "java") {
            return
        }

        // Analyze related classes asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (psiFile is PsiJavaFile) {
                    val element = psiFile.findElementAt(offset)
                    val psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

                    if (psiMethod != null) {
                        asyncAnalyzer.analyzeMethodAsync(
                            psiMethod,
                            onProgress = { result ->
                                // Create enhanced context with related classes
                                val enhancedContext = immediateContext.copy(
                                    relatedClasses = result.relatedClassContents,
                                    classContext = if (result.relatedClassContents.isNotEmpty()) {
                                        buildEnhancedClassContext(
                                            immediateContext.classContext,
                                            result.relatedClassContents
                                        )
                                    } else {
                                        immediateContext.classContext
                                    }
                                )

                                // Always invoke callback on EDT
                                ApplicationManager.getApplication().invokeLater {
                                    onComplete(enhancedContext)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Build enhanced class context with related classes
     */
    private fun buildEnhancedClassContext(
        originalContext: String,
        relatedClasses: Map<String, String>
    ): String {
        if (relatedClasses.isEmpty()) {
            return originalContext
        }

        return buildString {
            append(originalContext)
            append("\n\n// ===== Related Classes Used =====\n")

            relatedClasses.forEach { (className, classStructure) ->
                append("\n// $className:\n")
                append(classStructure)
                append("\n")
            }
        }
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
        cursorOffset: Int,
        isCocos2dx: Boolean
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

        // Collect Cocos2d-x specific context if applicable
        val (cocosContextType, cocosSyntaxPreferences, cocosCompletionHints, cocosFrameworkVersion) =
            if (isCocos2dx) {
                collectCocos2dxContext(fullFileContent, cursorOffset, methodContent)
            } else {
                CocosContextResult(null, null, emptyList(), null)
            }

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
            cursorOffset = cursorOffset,
            isCocos2dx = isCocos2dx,
            cocosContextType = cocosContextType,
            cocosSyntaxPreferences = cocosSyntaxPreferences,
            cocosCompletionHints = cocosCompletionHints,
            cocosFrameworkVersion = cocosFrameworkVersion
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
        language: String,
        isCocos2dx: Boolean
    ): MethodContext? {
        val lines = text.lines()
        val cursorLine = text.substring(0, offset).count { it == '\n' }

        // Find method boundaries using enhanced textual patterns
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
            val lastLineEnd =
                methodStartOffset + methodLines.dropLast(1).sumOf { it.length + 1 } + methodLines.last().length
            if (lastLineEnd < text.length && text[lastLineEnd] == '\n') {
                methodLines.joinToString("\n") + "\n"
            } else {
                methodLines.joinToString("\n")
            }
        }

        val methodEndOffset = methodStartOffset + methodContent.length

        // Validate the extracted method
        val validation = validateExtractedMethod(methodContent, language)
        if (!validation.isValid) {
            logger.warn("Extracted method has issues: ${validation.issues.joinToString(", ")}")
            // Continue anyway but log the issues
        }

        // Log for debugging
        logger.debug("Method boundaries: start=$methodStartOffset, end=$methodEndOffset, content length=${methodContent.length}")
        logger.debug("Method ends with: '${methodContent.takeLast(10).replace("\n", "\\n")}'")
        logger.debug(
            "Original text at end offset: '${
                if (methodEndOffset < text.length) text.substring(
                    methodEndOffset,
                    minOf(methodEndOffset + 5, text.length)
                ).replace("\n", "\\n") else "EOF"
            }'"
        )


        // Extract method name and signature
        val methodSignature = extractMethodSignature(methodContent)
        val methodName = extractMethodName(methodSignature, language)

        // Get surrounding context
        val surroundingMethods = findSurroundingMethodsTextually(lines, startLine, endLine, language)

        // Collect Cocos2d-x specific context if applicable
        val (cocosContextType, cocosSyntaxPreferences, cocosCompletionHints, cocosFrameworkVersion) =
            if (isCocos2dx) {
                collectCocos2dxContext(text, offset, methodContent)
            } else {
                CocosContextResult(null, null, emptyList(), null)
            }

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
            cursorOffset = offset,
            isCocos2dx = isCocos2dx,
            cocosContextType = cocosContextType,
            cocosSyntaxPreferences = cocosSyntaxPreferences,
            cocosCompletionHints = cocosCompletionHints,
            cocosFrameworkVersion = cocosFrameworkVersion
        )
    }


    /**
     * Find method boundaries using textual patterns with enhanced string and comment awareness
     */
    private fun findMethodBoundariesTextually(
        lines: List<String>,
        cursorLine: Int,
        language: String
    ): Pair<Int, Int>? {
        // Enhanced method to find method start considering context
        var methodStart = -1
        val contextLines = mutableListOf<String>()

        // Look backwards for method start with context
        for (i in cursorLine downTo maxOf(0, cursorLine - 50)) {
            val line = lines[i].trim()
            contextLines.add(0, line)

            if (isEnhancedMethodDeclaration(line, contextLines, language)) {
                methodStart = i
                break
            }
        }

        if (methodStart == -1) return null

        // Find method end with string-aware brace counting
        return findMethodEndWithStringAwareness(lines, methodStart, language)
    }

    /**
     * Enhanced method declaration detection considering context
     */
    private fun isEnhancedMethodDeclaration(
        line: String,
        context: List<String>,
        language: String
    ): Boolean {
        // Check for Cocos2d-x specific patterns
        if (language.lowercase() in listOf("javascript", "typescript")) {
            // Check if we're in an extend block or object literal
            val inExtendBlock = context.any { it.contains(".extend({") || it.contains("= {") }
            val inObjectLiteral = context.any { it.endsWith("{") && !it.contains("function") }

            if (inExtendBlock || inObjectLiteral) {
                // More lenient pattern for object literal methods
                return line.matches(Regex("""^\s*\w+\s*:\s*function.*""")) ||
                        line.matches(Regex("""^\s*\w+\s*\(.*\)\s*\{.*""")) ||
                        line.matches(Regex("""^\s*\w+\s*:\s*\(.*\)\s*=>.*"""))
            }
        }

        return isMethodDeclaration(line, language)
    }

    /**
     * Find method end with string and comment awareness
     */
    private fun findMethodEndWithStringAwareness(
        lines: List<String>,
        methodStart: Int,
        language: String
    ): Pair<Int, Int> {
        var braceCount = 0
        var inString = false
        var inMultiLineComment = false
        var stringChar: Char? = null
        var escaped = false
        var foundOpenBrace = false
        var methodEnd = methodStart

        for (i in methodStart until lines.size) {
            val line = lines[i]
            var j = 0

            while (j < line.length) {
                val char = line[j]
                val nextChar = if (j < line.length - 1) line[j + 1] else null

                // Handle escape sequences
                if (escaped) {
                    escaped = false
                    j++
                    continue
                }

                if (char == '\\' && !inMultiLineComment) {
                    escaped = true
                    j++
                    continue
                }

                // Handle multi-line comments
                if (!inString) {
                    if (char == '/' && nextChar == '*' && !inMultiLineComment) {
                        inMultiLineComment = true
                        j += 2
                        continue
                    }
                    if (char == '*' && nextChar == '/' && inMultiLineComment) {
                        inMultiLineComment = false
                        j += 2
                        continue
                    }
                }

                // Skip characters in multi-line comments
                if (inMultiLineComment) {
                    j++
                    continue
                }

                // Handle single-line comments
                if (!inString && char == '/' && nextChar == '/') {
                    break // Skip rest of line
                }

                // Handle strings
                if (!inString && (char == '"' || char == '\'' || char == '`')) {
                    inString = true
                    stringChar = char
                } else if (inString && char == stringChar) {
                    inString = false
                    stringChar = null
                }

                // Count braces only outside strings and comments
                if (!inString && !inMultiLineComment) {
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

                j++
            }
        }

        // If we didn't find the closing brace, return the last line we processed
        return if (foundOpenBrace) Pair(methodStart, methodEnd) else Pair(methodStart, methodStart)
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
                // Enhanced patterns for JavaScript/TypeScript including Cocos2d-x
                // Traditional function declarations
                line.matches(Regex("""^\s*function\s+\w+\s*\(.*\).*\{?\s*$""")) ||
                        // Variable function assignments: const/let/var name = function
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*function\s*\(.*\).*\{?\s*$""")) ||
                        // Variable function assignments with async
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*(async\s+)?function\s*\(.*\).*\{?\s*$""")) ||
                        // Object property assignments: x.y = function or x.y.z = function
                        line.matches(Regex("""^\s*[\w\.]+\.\w+\s*=\s*function\s*\(.*\).*\{?\s*$""")) ||
                        // Object property assignments with async: x.y = async function
                        line.matches(Regex("""^\s*[\w\.]+\.\w+\s*=\s*(async\s+)?function\s*\(.*\).*\{?\s*$""")) ||
                        // Object property arrow functions: x.y = () =>
                        line.matches(Regex("""^\s*[\w\.]+\.\w+\s*=\s*\(.*\)\s*=>\s*\{?\s*$""")) ||
                        // Object property arrow functions with async: x.y = async () =>
                        line.matches(Regex("""^\s*[\w\.]+\.\w+\s*=\s*async\s*\(.*\)\s*=>\s*\{?\s*$""")) ||
                        // Object literal methods (common in Cocos2d-x)
                        line.matches(Regex("""^\s*\w+\s*:\s*function\s*\(.*\).*\{?\s*$""")) ||
                        // Arrow functions: const/let/var name = () =>
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*\(.*\)\s*=>\s*\{?\s*$""")) ||
                        // Arrow functions: const/let/var name = async () =>
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*async\s*\(.*\)\s*=>\s*\{?\s*$""")) ||
                        // Arrow functions without parentheses: const/let/var name = arg =>
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*\w+\s*=>\s*\{?\s*$""")) ||
                        // Async functions
                        line.matches(Regex("""^\s*async\s+function\s+\w+\s*\(.*\).*\{?\s*$""")) ||
                        // Class methods in Cocos2d-x extend pattern (with optional trailing comma)
                        line.matches(Regex("""^\s*\w+\s*:\s*function\s*\(.*\)\s*\{?\s*,?\s*$""")) ||
                        // Object method with arrow function: methodName: () =>
                        line.matches(Regex("""^\s*\w+\s*:\s*\(.*\)\s*=>\s*\{?\s*$""")) ||
                        // Object method with arrow function (no parens): methodName: arg =>
                        line.matches(Regex("""^\s*\w+\s*:\s*\w+\s*=>\s*\{?\s*$""")) ||
                        // ES6 class methods
                        line.matches(Regex("""^\s*(static\s+|async\s+)?\w+\s*\(.*\)\s*\{?\s*$""")) ||
                        // Generator functions
                        line.matches(Regex("""^\s*function\s*\*\s*\w+\s*\(.*\).*\{?\s*$""")) ||
                        // Variable generator functions
                        line.matches(Regex("""^\s*(const|let|var)\s+\w+\s*=\s*function\s*\*\s*\(.*\).*\{?\s*$""")) ||
                        // TypeScript method with return type: methodName(): ReturnType
                        (language == "typescript" && line.matches(Regex("""^\s*\w+\s*\(.*\)\s*:\s*\w+.*\{?\s*$"""))) ||
                        // TypeScript property method with return type: methodName: (params): ReturnType =>
                        (language == "typescript" && line.matches(Regex("""^\s*\w+\s*:\s*\(.*\)\s*:\s*\w+\s*=>\s*\{?\s*$""")))
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

        // For JavaScript/TypeScript, check for various patterns
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Skip empty lines and pure comment lines
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue
            }

            // Object literal method pattern: methodName: function(params)
            if (trimmed.matches(Regex("""^\w+\s*:\s*function\s*\(.*\).*"""))) {
                return trimmed.substringBefore("{").trim()
            }

            // Arrow function pattern: methodName: (params) =>
            if (trimmed.matches(Regex("""^\w+\s*:\s*\(.*\)\s*=>.*"""))) {
                return trimmed.substringBefore("{").substringBefore("=>").trim() + " =>"
            }

            // Object property assignment: x.y = function or x.y.z = function
            if (trimmed.matches(Regex("""^[\w\.]+\.\w+\s*=\s*(?:async\s+)?function.*"""))) {
                // Check if parameters are on the next line
                if (!trimmed.contains("(") && index < lines.size - 1) {
                    val nextLine = lines[index + 1].trim()
                    if (nextLine.startsWith("(")) {
                        return "$trimmed $nextLine".substringBefore("{").trim()
                    }
                }
                return trimmed.substringBefore("{").trim()
            }

            // Object property arrow function: x.y = () =>
            if (trimmed.matches(Regex("""^[\w\.]+\.\w+\s*=\s*(?:async\s*)?\(.*\)\s*=>.*"""))) {
                return trimmed.substringBefore("{").trim()
            }

            // Variable function: const/let/var name = function
            if (trimmed.matches(Regex("""^(const|let|var)\s+\w+\s*=\s*(?:async\s+)?function.*"""))) {
                // Check if parameters are on the next line
                if (!trimmed.contains("(") && index < lines.size - 1) {
                    val nextLine = lines[index + 1].trim()
                    if (nextLine.startsWith("(")) {
                        return "$trimmed $nextLine".substringBefore("{").trim()
                    }
                }
                return trimmed.substringBefore("{").trim()
            }

            // Variable arrow function: const/let/var name = () =>
            if (trimmed.matches(Regex("""^(const|let|var)\s+\w+\s*=\s*(?:async\s*)?\(.*\)\s*=>.*"""))) {
                return trimmed.substringBefore("{").trim()
            }

            // TypeScript method with return type: methodName(): ReturnType
            if (trimmed.matches(Regex("""^\w+\s*\(.*\)\s*:\s*[\w\[\]<>]+.*"""))) {
                return trimmed.substringBefore("{").trim()
            }

            // Regular function or other patterns with parentheses
            if (trimmed.contains("(") && trimmed.contains(")")) {
                // Extract just the signature part, not the body
                val signaturePart = if (trimmed.contains("{")) {
                    trimmed.substringBefore("{").trim()
                } else {
                    trimmed
                }
                return signaturePart
            }

            // Multi-line signatures: check if this line starts a function but params are on next lines
            if (trimmed.matches(Regex("""^(function|async\s+function|const|let|var|static|async|[\w\.]+\.\w+\s*=).*""")) && !trimmed.contains(
                    "("
                )
            ) {
                // Look ahead for the full signature
                val signatureBuilder = StringBuilder(trimmed)
                for (j in (index + 1) until minOf(index + 5, lines.size)) {
                    val nextLine = lines[j].trim()
                    signatureBuilder.append(" ").append(nextLine)
                    if (nextLine.contains(")")) {
                        return signatureBuilder.toString().substringBefore("{").trim()
                    }
                }
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
                // Try different patterns in order of specificity
                // Object literal method: methodName: function(
                val objectMethodMatch = Regex("""^\s*(\w+)\s*:\s*function\s*\(""").find(signature)
                if (objectMethodMatch != null) return objectMethodMatch.groupValues[1]

                // Arrow function: methodName: () =>
                val arrowMethodMatch = Regex("""^\s*(\w+)\s*:\s*\(.*\)\s*=>""").find(signature)
                if (arrowMethodMatch != null) return arrowMethodMatch.groupValues[1]

                // Arrow function without parens: methodName: arg =>
                val arrowMethodNoParensMatch = Regex("""^\s*(\w+)\s*:\s*\w+\s*=>""").find(signature)
                if (arrowMethodNoParensMatch != null) return arrowMethodNoParensMatch.groupValues[1]

                // Object property method: x.y = function or x.y.z = function
                val objectPropertyMatch = Regex("""^\s*[\w\.]+\.(\w+)\s*=\s*(?:async\s+)?function""").find(signature)
                if (objectPropertyMatch != null) return objectPropertyMatch.groupValues[1]

                // Object property arrow: x.y = () =>
                val objectPropertyArrowMatch =
                    Regex("""^\s*[\w\.]+\.(\w+)\s*=\s*(?:async\s*)?\(.*\)\s*=>""").find(signature)
                if (objectPropertyArrowMatch != null) return objectPropertyArrowMatch.groupValues[1]

                // Regular function: function methodName(
                val functionMatch = Regex("""function\s+(\w+)\s*\(""").find(signature)
                if (functionMatch != null) return functionMatch.groupValues[1]

                // Variable function: const/let/var methodName = function(
                val varFunctionMatch =
                    Regex("""(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?function""").find(signature)
                if (varFunctionMatch != null) return varFunctionMatch.groupValues[1]

                // Variable arrow function: const/let/var methodName = () =>
                val varArrowMatch =
                    Regex("""(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?\(.*\)\s*=>""").find(signature)
                if (varArrowMatch != null) return varArrowMatch.groupValues[1]

                // Variable arrow function without parens: const/let/var methodName = arg =>
                val varArrowNoParensMatch =
                    Regex("""(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?\w+\s*=>""").find(signature)
                if (varArrowNoParensMatch != null) return varArrowNoParensMatch.groupValues[1]

                // ES6 class method: methodName(
                val classMethodMatch = Regex("""^\s*(?:static\s+|async\s+)?(\w+)\s*\(""").find(signature)
                if (classMethodMatch != null) return classMethodMatch.groupValues[1]

                // Generator function: function* methodName(
                val generatorMatch = Regex("""function\s*\*\s*(\w+)\s*\(""").find(signature)
                if (generatorMatch != null) return generatorMatch.groupValues[1]

                "unknown"
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
     * Detect if this is a Cocos2d-x project based on file content and context
     */
    private fun detectCocos2dxProject(content: String, fileName: String, language: String): Boolean {
        // Only check JavaScript/TypeScript files
        if (!language.lowercase().contains("javascript") && !language.lowercase().contains("typescript")) {
            return false
        }

        // Check for Cocos2d-x specific patterns
        return content.contains(Regex("\\bcc\\.(Node|Scene|Layer|Sprite|Label|Menu)\\b")) ||
                content.contains("cocos2d") ||
                content.contains("cc.Class") ||
                content.contains("cc.extend") ||
                content.contains("cc.game") ||
                content.contains("cc.") ||
                fileName.contains("cocos", ignoreCase = true) ||
                // Check for specific Cocos2d-x lifecycle methods
                content.contains(Regex("\\b(ctor|onEnter|onExit|onEnterTransitionDidFinish)\\s*:")) ||
                // Check for Cocos2d-x specific patterns in extends
                content.contains(Regex("\\.(extend|create)\\s*\\("))
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

    /**
     * Validate extracted method content
     */
    private fun validateExtractedMethod(
        methodContent: String,
        language: String
    ): ValidationResult {
        val issues = mutableListOf<String>()

        // Check brace balance (accounting for strings)
        val (openBraces, closeBraces) = countBracesOutsideStrings(methodContent)

        if (openBraces != closeBraces) {
            issues.add("Unbalanced braces: $openBraces open, $closeBraces close")
        }

        // Check for method signature
        if (!hasValidMethodSignature(methodContent, language)) {
            issues.add("Missing or invalid method signature")
        }

        // Check for Cocos2d-x specific issues
        if (isCocos2dxMethod(methodContent)) {
            validateCocos2dxMethod(methodContent, issues)
        }

        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }

    /**
     * Count braces outside of strings
     */
    private fun countBracesOutsideStrings(content: String): Pair<Int, Int> {
        var openCount = 0
        var closeCount = 0
        var inString = false
        var stringChar: Char? = null
        var escaped = false

        for (i in content.indices) {
            val char = content[i]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\') {
                escaped = true
                continue
            }

            if (!inString && (char == '"' || char == '\'' || char == '`')) {
                inString = true
                stringChar = char
            } else if (inString && char == stringChar) {
                inString = false
                stringChar = null
            }

            if (!inString) {
                when (char) {
                    '{' -> openCount++
                    '}' -> closeCount++
                }
            }
        }

        return Pair(openCount, closeCount)
    }

    /**
     * Check if method has valid signature
     */
    private fun hasValidMethodSignature(content: String, language: String): Boolean {
        val lines = content.lines()
        return lines.any { line -> isMethodDeclaration(line.trim(), language) }
    }

    /**
     * Check if this is a Cocos2d-x method
     */
    private fun isCocos2dxMethod(content: String): Boolean {
        return content.contains("cc.") ||
                content.contains("cocos2d") ||
                content.contains(".extend(") ||
                content.contains("_super()")
    }

    /**
     * Validate Cocos2d-x specific method patterns
     */
    private fun validateCocos2dxMethod(content: String, issues: MutableList<String>) {
        // Check for common Cocos2d-x issues
        if (content.contains(".create(") && content.contains("cc.")) {
            issues.add("Consider using direct constructors (cc.Node()) instead of .create() methods")
        }

        // Check for proper lifecycle method calls
        if (content.contains("onEnter") && !content.contains("_super")) {
            issues.add("Lifecycle methods should call this._super() when overriding")
        }
    }

    /**
     * Data class for validation results
     */
    private data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )

    /**
     * Detect Cocos2d-x context type from method content and surrounding context
     */
    private fun detectCocos2dxContextTypeFromMethod(
        methodContent: String,
        beforeCursor: String
    ): ZestCocos2dxContextCollector.Cocos2dxContextType? {
        val lines = methodContent.lines()
        val previousLines = beforeCursor.lines().takeLast(5)

        return when {
            // Scene contexts
            isInSceneDefinition(previousLines) -> ZestCocos2dxContextCollector.Cocos2dxContextType.SCENE_DEFINITION
            isInLifecycleMethod(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.SCENE_LIFECYCLE_METHOD
            methodContent.contains("init:") || methodContent.contains("init ") ->
                ZestCocos2dxContextCollector.Cocos2dxContextType.SCENE_INIT_METHOD

            // Node creation and management
            isNodeCreation(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.NODE_CREATION
            isNodePropertySetting(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.NODE_PROPERTY_SETTING
            isChildManagement(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.NODE_CHILD_MANAGEMENT

            // Event handling
            isEventListenerSetup(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.EVENT_LISTENER_SETUP
            isTouchEventHandler(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.TOUCH_EVENT_HANDLER

            // Actions and animations
            isActionCreation(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.ACTION_CREATION
            isActionSequence(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.ACTION_SEQUENCE

            // Resource management
            isResourceLoading(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.RESOURCE_LOADING

            // Game logic
            isInUpdateLoop(methodContent) -> ZestCocos2dxContextCollector.Cocos2dxContextType.GAME_UPDATE_LOOP

            // General contexts
            isInFunctionBody(beforeCursor) -> ZestCocos2dxContextCollector.Cocos2dxContextType.FUNCTION_BODY

            else -> ZestCocos2dxContextCollector.Cocos2dxContextType.UNKNOWN
        }
    }

    /**
     * Detect Cocos2d-x framework version from content
     */
    private fun detectCocos2dxVersionFromContent(content: String): String? {
        return when {
            content.contains("cc.Class") -> "3.x"
            content.contains("cc.Node.extend") -> "2.x"
            content.contains("cocos2d-js") -> "3.x"
            content.contains("cc.") -> "2.x" // Default assumption for old syntax
            else -> null
        }
    }

    /**
     * Create Cocos2d-x syntax preferences based on framework version
     */
    private fun createCocosSyntaxPreferences(frameworkVersion: String?): ZestCocos2dxContextCollector.CocosSyntaxPreferences {
        return ZestCocos2dxContextCollector.CocosSyntaxPreferences(
            preferDirectConstructor = true,
            useOldVersionSyntax = true,
            preferredPatterns = when (frameworkVersion) {
                "2.x" -> listOf(
                    "Use cc.Node() instead of cc.Node.create() - direct constructor preferred",
                    "Use cc.Sprite() instead of cc.Sprite.create() - direct constructor preferred",
                    "Use cc.Layer() instead of cc.Layer.create() - direct constructor preferred",
                    "Use .extend() pattern for class inheritance: var MyLayer = cc.Layer.extend({...})",
                    "Use old version cocos2d-x-js syntax patterns"
                )

                "3.x" -> listOf(
                    "Use cc.Node() instead of cc.Node.create() - direct constructor preferred",
                    "Use cc.Class for class definitions when available",
                    "Use modern event system with cc.EventListener"
                )

                else -> listOf(
                    "Use cc.Node() instead of cc.Node.create()",
                    "Follow cocos2d-x-js conventions"
                )
            }
        )
    }

    /**
     * Generate completion hints based on Cocos2d-x context
     */
    private fun generateCocosCompletionHints(
        contextType: ZestCocos2dxContextCollector.Cocos2dxContextType?,
        syntaxPreferences: ZestCocos2dxContextCollector.CocosSyntaxPreferences?,
        methodContent: String
    ): List<String> {
        val hints = mutableListOf<String>()

        // Add general syntax preferences
        syntaxPreferences?.preferredPatterns?.let { hints.addAll(it) }

        // Add context-specific hints
        when (contextType) {
            ZestCocos2dxContextCollector.Cocos2dxContextType.NODE_CREATION -> {
                hints.add("SYNTAX: Use cc.Sprite() instead of cc.Sprite.create()")
                hints.add("SYNTAX: Use cc.Node() instead of cc.Node.create()")
            }

            ZestCocos2dxContextCollector.Cocos2dxContextType.SCENE_DEFINITION -> {
                hints.add("SYNTAX: Use var MyScene = cc.Scene.extend({...}) pattern")
                hints.add("SYNTAX: Include ctor, onEnter, onExit lifecycle methods")
            }

            ZestCocos2dxContextCollector.Cocos2dxContextType.ACTION_CREATION -> {
                hints.add("SYNTAX: Use cc.MoveTo() instead of cc.MoveTo.create()")
                hints.add("SYNTAX: Use cc.ScaleTo() instead of cc.ScaleTo.create()")
            }

            else -> {
                hints.add("SYNTAX: Follow cocos2d-x-js old version patterns")
            }
        }

        // Add strict language reminder for Cocos2d-x
        hints.add("IMPORTANT: This is Cocos2d-x JavaScript code. Use ONLY existing Cocos2d-x API functions - do not invent or hallucinate any non-existent cocos functions")
        hints.add("STRICT: Verify all cc.* functions exist in Cocos2d-x documentation. Common valid functions include: cc.Node(), cc.Sprite(), cc.Layer(), cc.Scene(), cc.Director, cc.MoveTo(), cc.ScaleTo(), etc.")
        hints.add("LANGUAGE: JavaScript/Cocos2d-x - NOT TypeScript, NOT modern JS frameworks. Use only Cocos2d-x JavaScript APIs")

        return hints.distinct()
    }

    // Cocos2d-x pattern detection methods
    private fun isInSceneDefinition(previousLines: List<String>): Boolean {
        return previousLines.any { line ->
            line.contains("cc.Scene.extend") ||
                    line.contains("extend(cc.Scene") ||
                    line.contains("new cc.Scene")
        }
    }

    private fun isInLifecycleMethod(content: String): Boolean {
        val lifecycleMethods = listOf("ctor", "onEnter", "onExit", "onEnterTransitionDidFinish", "update", "init")
        return lifecycleMethods.any { method ->
            content.contains("$method:") || content.contains("$method ")
        }
    }

    private fun isNodeCreation(content: String): Boolean {
        val nodeTypes = listOf("cc.Node", "cc.Scene", "cc.Layer", "cc.Sprite", "cc.Label", "cc.Menu")
        return nodeTypes.any { nodeType ->
            content.contains("new $nodeType") ||
                    content.contains("$nodeType(") ||
                    content.contains("$nodeType.create")
        }
    }

    private fun isNodePropertySetting(content: String): Boolean {
        val nodeProperties = listOf("setPosition", "setScale", "setRotation", "setVisible", "setOpacity")
        return nodeProperties.any { content.contains(it) } ||
                content.contains(Regex("\\.(x|y|scale|rotation|visible|opacity)\\s*="))
    }

    private fun isChildManagement(content: String): Boolean {
        return content.contains("addChild") || content.contains("removeChild") ||
                content.contains("removeFromParent") || content.contains("getChildByTag")
    }

    private fun isEventListenerSetup(content: String): Boolean {
        return content.contains("cc.EventListener") || content.contains("addEventListener")
    }

    private fun isTouchEventHandler(content: String): Boolean {
        return content.contains("onTouchBegan") || content.contains("onTouchMoved") ||
                content.contains("onTouchEnded") || content.contains("TouchOneByOne")
    }

    private fun isActionCreation(content: String): Boolean {
        return content.contains("cc.MoveTo") || content.contains("cc.MoveBy") ||
                content.contains("cc.ScaleTo") || content.contains("cc.RotateTo") ||
                content.contains("cc.FadeIn") || content.contains("cc.FadeOut")
    }

    private fun isActionSequence(content: String): Boolean {
        return content.contains("cc.Sequence") || content.contains("cc.Spawn") ||
                content.contains("runAction")
    }

    private fun isResourceLoading(content: String): Boolean {
        return content.contains("cc.loader") || content.contains("cc.textureCache") ||
                content.contains("preload") || content.contains("load(")
    }

    private fun isInUpdateLoop(content: String): Boolean {
        return content.contains("update:") || content.contains("scheduleUpdate") ||
                content.contains("schedule(")
    }

    private fun isInFunctionBody(beforeCursor: String): Boolean {
        var braceCount = 0
        var inFunction = false

        beforeCursor.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains("function") || trimmed.matches(Regex(".*:\\s*function.*"))) {
                inFunction = true
            }
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
            if (braceCount == 0) inFunction = false
        }

        return inFunction && braceCount > 0
    }
}
