package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.ConfigurationManager
import com.zps.zest.completion.async.AsyncClassAnalyzer
import com.zps.zest.completion.async.PreemptiveAsyncAnalyzerService
import com.zps.zest.completion.rag.InlineCompletionRAG
import com.zps.zest.completion.ast.ASTPatternMatcher
import com.zps.zest.langchain4j.ASTChunker
import com.zps.zest.chunking.ChunkingOptions

/**
 * Lean context collector that uses PSI (Program Structure Interface) for accurate Java code analysis
 * instead of regex-based parsing. Supports Java and JavaScript with automatic language detection
 * and intelligent context truncation.
 *
 * Features:
 * - PSI-based parsing for accurate Java method detection
 * - Smart method body collapsing to stay within context limits
 * - Preserves methods containing the cursor
 * - Uses cosine similarity to preserve related methods based on name similarity
 * - Proper EDT (Event Dispatch Thread) handling for PSI operations
 * - Handles camelCase, PascalCase, snake_case method/field names
 */
class ZestLeanContextCollectorPSI(private val project: Project) {

    companion object {
        const val MAX_CONTEXT_LENGTH = 3000
        const val METHOD_BODY_PLACEHOLDER = " { /* method body hidden */ }"
        const val FUNCTION_BODY_PLACEHOLDER = " { /* function body hidden */ }"
        const val SIMILARITY_THRESHOLD = 0.6
    }

    private val asyncAnalyzer = AsyncClassAnalyzer(project)
    private val ragService = InlineCompletionRAG(project)
    private val patternMatcher = ASTPatternMatcher()
    private val chunker = ASTChunker()

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
        // New fields for async analysis results
        val calledMethods: Set<String> = emptySet(),
        val usedClasses: Set<String> = emptySet(),
        val relatedClassContents: Map<String, String> = emptyMap(),
        val syntaxInstructions: String? = null,
        // VCS context for uncommitted changes
        val uncommittedChanges: ZestCompleteGitContext.CompleteGitInfo? = null,
        // RAG retrieved chunks
        val ragChunks: List<InlineCompletionRAG.RetrievedChunk> = emptyList(),
        // AST pattern matches
        val astPatternMatches: List<ASTPatternMatcher.PatternMatch> = emptyList()
    )

    enum class CursorContextType {
        // Java contexts
        METHOD_BODY,
        CLASS_DECLARATION,
        IMPORT_SECTION,
        VARIABLE_ASSIGNMENT,
        AFTER_OPENING_BRACE,
        FIELD_DECLARATION,
        ANNOTATION,
        
        // JavaScript contexts
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
        // Ensure we're in a read action
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

        // Check for Cocos2d-x JavaScript project
        if (language == "javascript" && isCocos2dxProject(text)) {
            // Use the new Cocos2d-x collector that returns the new LeanContext format
            return ZestCocos2dxContextCollector(project).collectCocos2dxContext(editor, offset)
        }

        // Insert cursor marker
        val markedContent = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)
        val cursorLine = document.getLineNumber(offset)

        // Get PSI file for Java analysis
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        
        val contextType = when {
            psiFile is PsiJavaFile -> detectJavaContextWithPSI(psiFile, offset)
            language == "javascript" -> detectJavaScriptContext(text, offset)
            else -> CursorContextType.UNKNOWN
        }

        // Apply truncation if needed
        val (finalContent, finalMarkedContent, isTruncated, preservedMethods) = when {
            psiFile is PsiJavaFile && markedContent.length > MAX_CONTEXT_LENGTH -> {
                truncateJavaWithPSI(psiFile, text, markedContent, offset)
            }
            markedContent.length > MAX_CONTEXT_LENGTH -> {
                // Fallback to regex-based truncation for non-Java files
                val (content, marked, truncated) = truncateWithRegex(text, markedContent)
                Tuple4(content, marked, truncated, emptySet())
            }
            else -> {
                Tuple4(text, markedContent, false, emptySet())
            }
        }

        // Try to get cached VCS context from background manager (truly non-blocking)
        val uncommittedChanges = try {
            val backgroundManager = project.getService(ZestBackgroundContextManager::class.java)
            // Check if we already have cached data without any blocking
            val cachedGitContext = backgroundManager.getCachedGitContextNonBlocking()
            if (cachedGitContext != null) {
                println("Using cached VCS context (non-blocking)")
                cachedGitContext
            } else {
                println("No cached VCS context available (non-blocking)")
                null
            }
        } catch (e: Exception) {
            println("Failed to get cached VCS context: ${e.message}")
            null
        }
        
        val config = ConfigurationManager.getInstance(project)
        
        // Extract pattern at cursor for AST matching (if enabled)
        val cursorPattern = if (config.isAstPatternMatchingEnabled) {
            try {
                patternMatcher.extractPatternAtCursor(text, offset, language)
            } catch (e: Exception) {
                println("Failed to extract AST pattern: ${e.message}")
                null
            }
        } else {
            null
        }
        
        // Get RAG chunks if enabled and we have a pattern or context
        val ragChunks = if (config.isInlineCompletionRagEnabled && 
                            (cursorPattern != null || contextType != CursorContextType.UNKNOWN)) {
            try {
                val query = extractQueryFromContext(text, offset, contextType)
                val maxChunks = config.maxRagContextSize / 300 // Estimate chunks based on size
                ragService.retrieveRelevantChunks(query, minOf(maxChunks, 3))
            } catch (e: Exception) {
                println("Failed to retrieve RAG chunks: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        return LeanContext(
            fileName = fileName,
            language = language,
            fullContent = finalContent,
            markedContent = finalMarkedContent,
            cursorOffset = offset,
            cursorLine = cursorLine,
            contextType = contextType,
            isTruncated = isTruncated,
            preservedMethods = preservedMethods,
            syntaxInstructions = null,
            uncommittedChanges = uncommittedChanges,
            ragChunks = ragChunks,
            astPatternMatches = findAstPatternMatchesIfEnabled(psiFile, text, offset, language, cursorPattern)
        )
    }

    /**
     * Collect context with full async analysis for better method preservation and related content
     */
    fun collectWithDependencyAnalysis(
        editor: Editor,
        offset: Int,
        onComplete: (LeanContext) -> Unit
    ) {
        // First, get immediate context
        val immediateContext = collectFullFileContext(editor, offset)
        
        // Check if we have preemptive analysis available
        val preemptiveService = project.getService(PreemptiveAsyncAnalyzerService::class.java)
        val cachedAnalysis = preemptiveService?.getCachedAnalysis(editor, offset)
        
        if (cachedAnalysis != null) {
            // We have cached analysis! Use it immediately
            val enhancedContext = immediateContext.copy(
                calledMethods = cachedAnalysis.calledMethods,
                usedClasses = cachedAnalysis.usedClasses,
                relatedClassContents = cachedAnalysis.relatedClassContents,
                preservedMethods = immediateContext.preservedMethods.plus(cachedAnalysis.calledMethods),
                ragChunks = immediateContext.ragChunks,
                astPatternMatches = immediateContext.astPatternMatches // Keep original for now
            )
            
            // Re-process with all dependency information
            val finalContext = ApplicationManager.getApplication().runReadAction<LeanContext> {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                val contextWithPatterns = enhancedContext.copy(
                    astPatternMatches = findAstPatternMatches(psiFile, enhancedContext, offset)
                )
                
                // Recreate context based on file type
                when {
                    psiFile is PsiJavaFile -> recreateContextWithFullAnalysis(psiFile, contextWithPatterns, offset)
                    else -> contextWithPatterns // For JS, Cocos2d-x, and other files
                }
            }
            
            // Invoke callback immediately with enhanced context
            ApplicationManager.getApplication().invokeLater {
                onComplete(finalContext)
            }
            
//            println("ZestLeanContextCollectorPSI: Used preemptive analysis with ${cachedAnalysis.relatedClassContents.size} related classes")
            return
        }
        
//        println("ZestLeanContextCollectorPSI: No preemptive analysis available, falling back to on-demand")
        
        // Only do async analysis for Java files
        if (immediateContext.language != "java") {
            ApplicationManager.getApplication().invokeLater {
                onComplete(immediateContext)
            }
            return
        }

        // Analyze the current method asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (psiFile is PsiJavaFile) {
                    val element = psiFile.findElementAt(offset)
                    val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

                    if (containingMethod != null && containingClass != null) {
                        var latestContext = immediateContext
                        
                        // First do quick dependency analysis
                        asyncAnalyzer.analyzeDependenciesForContext(
                            containingClass,
                            containingMethod
                        ) { methodsToPreserve, fieldsToPreserve ->
                            // Update context with dependency info
                            latestContext = latestContext.copy(
                                preservedMethods = methodsToPreserve.plus(containingMethod.name),
                                preservedFields = fieldsToPreserve,
                                uncommittedChanges = latestContext.uncommittedChanges // Preserve VCS context
                            )
                        }
                        
                        // Then do full async analysis
                        asyncAnalyzer.analyzeMethodAsync(
                            containingMethod,
                            onProgress = { analysisResult ->
                                // Update context with analysis results
                                latestContext = latestContext.copy(
                                    calledMethods = analysisResult.calledMethods,
                                    usedClasses = analysisResult.usedClasses,
                                    relatedClassContents = analysisResult.relatedClassContents,
                                    preservedMethods = latestContext.preservedMethods.plus(analysisResult.calledMethods),
                                    uncommittedChanges = latestContext.uncommittedChanges, // Preserve VCS context
                                    ragChunks = latestContext.ragChunks,
                                    astPatternMatches = latestContext.astPatternMatches
                                )
                                
                                // Send progressive updates
                                ApplicationManager.getApplication().invokeLater {
                                    onComplete(latestContext)
                                }
                            },
                            onComplete = {
                                // Re-process with all dependency information
                                val finalContext = ApplicationManager.getApplication().runReadAction<LeanContext> {
                                    recreateContextWithFullAnalysis(
                                        psiFile,
                                        latestContext,
                                        offset
                                    )
                                }
                                
                                // Final callback
                                ApplicationManager.getApplication().invokeLater {
                                    onComplete(finalContext)
                                }
                            }
                        )
                    } else {
                        // No method at cursor, return immediate context
                        ApplicationManager.getApplication().invokeLater {
                            onComplete(immediateContext)
                        }
                    }
                }
            }
        }
    }

    /**
     * Recreate context with dependency information using PSI
     */
    private fun recreateContextWithDependencies(
        psiFile: PsiJavaFile,
        originalContext: LeanContext,
        preservedMethods: Set<String>,
        preservedFields: Set<String>,
        cursorOffset: Int
    ): LeanContext {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return originalContext
        val text = document.text
        val markedContent = text.substring(0, cursorOffset) + "[CURSOR]" + text.substring(cursorOffset)
        
        val (finalContent, finalMarkedContent) = if (markedContent.length > MAX_CONTEXT_LENGTH) {
            collapseJavaMethodsWithPSI(psiFile, markedContent, cursorOffset, preservedMethods)
        } else {
            Pair(text, markedContent)
        }
        
        return originalContext.copy(
            fullContent = finalContent,
            markedContent = finalMarkedContent,
            preservedMethods = preservedMethods,
            preservedFields = preservedFields
        )
    }

    /**
     * Recreate context with full analysis information
     */
    private fun recreateContextWithFullAnalysis(
        psiFile: PsiJavaFile,
        context: LeanContext,
        cursorOffset: Int
    ): LeanContext {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return context
        val text = document.text
        val markedContent = text.substring(0, cursorOffset) + "[CURSOR]" + text.substring(cursorOffset)
        
        // Include all methods that are called or preserved
        val allPreservedMethods = context.preservedMethods.plus(context.calledMethods)
        
        val (finalContent, finalMarkedContent) = if (markedContent.length > MAX_CONTEXT_LENGTH) {
            collapseJavaMethodsWithPSI(psiFile, markedContent, cursorOffset, allPreservedMethods)
        } else {
            Pair(text, markedContent)
        }
        
        return context.copy(
            fullContent = finalContent,
            markedContent = finalMarkedContent,
            uncommittedChanges = context.uncommittedChanges, // Preserve VCS context
            ragChunks = context.ragChunks,
            astPatternMatches = context.astPatternMatches
        )
    }

    /**
     * Detect Java context type using PSI
     */
    private fun detectJavaContextWithPSI(psiFile: PsiJavaFile, offset: Int): CursorContextType {
        val element = psiFile.findElementAt(offset) ?: return CursorContextType.UNKNOWN
        
        return when {
            // Check if in import section
            PsiTreeUtil.getParentOfType(element, PsiImportList::class.java) != null ->
                CursorContextType.IMPORT_SECTION
                
            // Check if in annotation
            PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) != null ->
                CursorContextType.ANNOTATION
                
            // Check if in method body
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) != null -> {
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)!!
                if (method.body != null && method.body!!.textRange.contains(offset)) {
                    CursorContextType.METHOD_BODY
                } else {
                    CursorContextType.CLASS_DECLARATION
                }
            }
            
            // Check if in field declaration
            PsiTreeUtil.getParentOfType(element, PsiField::class.java) != null ->
                CursorContextType.FIELD_DECLARATION
                
            // Check if in variable assignment
            PsiTreeUtil.getParentOfType(element, PsiAssignmentExpression::class.java) != null ->
                CursorContextType.VARIABLE_ASSIGNMENT
                
            // Check if after opening brace
            element.prevSibling?.text == "{" || 
            (element is PsiWhiteSpace && element.prevSibling?.text == "{") ->
                CursorContextType.AFTER_OPENING_BRACE
                
            // Check if at class level
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java) != null ->
                CursorContextType.CLASS_DECLARATION
                
            else -> CursorContextType.UNKNOWN
        }
    }

    /**
     * Truncate Java code using PSI for accurate method detection
     */
    private fun truncateJavaWithPSI(
        psiFile: PsiJavaFile, 
        originalText: String,
        markedContent: String,
        cursorOffset: Int
    ): Tuple4<String, String, Boolean, Set<String>> {
        val methods = collectAllMethods(psiFile)
        val cursorMethod = findMethodContainingOffset(psiFile, cursorOffset)
        
        // Build priority list of methods
        val methodPriorities = mutableListOf<MethodPriority>()
        
        methods.forEach { method ->
            var priority = 0.0
            
            // Highest priority: contains cursor
            if (method == cursorMethod) {
                priority = 1000.0
            }
            
            // Medium priority: close to cursor
            else if (cursorMethod != null) {
                val distance = kotlin.math.abs(method.textOffset - cursorOffset)
                priority = 100.0 / (1.0 + distance / 1000.0)
                
                // Bonus for name similarity
                val similarity = calculateMethodNameSimilarity(method.name, cursorMethod.name)
                if (similarity >= SIMILARITY_THRESHOLD) {
                    priority += 50.0 * similarity
                }
            }
            
            methodPriorities.add(MethodPriority(method, priority))
        }
        
        methodPriorities.sortByDescending { it.priority }
        
        // Build result with collapsed methods
        val (collapsedContent, collapsedMarked, preservedMethods) = buildCollapsedContent(
            psiFile,
            originalText,
            markedContent,
            methodPriorities
        )
        
        val isTruncated = preservedMethods.size < methods.size
        
        return Tuple4(collapsedContent, collapsedMarked, isTruncated, preservedMethods)
    }

    /**
     * Collapse Java methods using PSI with dependency information
     */
    private fun collapseJavaMethodsWithPSI(
        psiFile: PsiJavaFile,
        markedContent: String,
        cursorOffset: Int,
        preservedMethods: Set<String>
    ): Pair<String, String> {
        val methods = collectAllMethods(psiFile)
        val cursorMethod = findMethodContainingOffset(psiFile, cursorOffset)
        
        // Build priority list
        val methodPriorities = mutableListOf<MethodPriority>()
        
        methods.forEach { method ->
            var priority = 0.0
            
            // Highest priority: contains cursor
            if (method == cursorMethod) {
                priority = 1000.0
            }
            // High priority: in preserved set
            else if (method.name in preservedMethods) {
                priority = 100.0
            }
            // Medium priority: similar to preserved methods
            else if (preservedMethods.any { preserved ->
                calculateMethodNameSimilarity(method.name, preserved) >= SIMILARITY_THRESHOLD
            }) {
                priority = 50.0
            }
            
            methodPriorities.add(MethodPriority(method, priority))
        }
        
        methodPriorities.sortByDescending { it.priority }
        
        val (_, collapsedMarked, _) = buildCollapsedContent(
            psiFile,
            markedContent.replace("[CURSOR]", ""),
            markedContent,
            methodPriorities
        )
        
        return Pair(collapsedMarked.replace("[CURSOR]", ""), collapsedMarked)
    }

    /**
     * Build collapsed content based on method priorities
     */
    private fun buildCollapsedContent(
        psiFile: PsiJavaFile,
        originalText: String,
        markedText: String,
        methodPriorities: List<MethodPriority>
    ): Triple<String, String, Set<String>> {
        val preservedMethods = mutableSetOf<String>()
        val methodsToExpand = mutableSetOf<PsiMethod>()
        
        var estimatedLength = 0
        
        // First pass: Always include high priority methods
        for (mp in methodPriorities) {
            if (mp.priority >= 100.0) {
                methodsToExpand.add(mp.method)
                preservedMethods.add(mp.method.name)
                estimatedLength += mp.method.textLength
            }
        }
        
        // Second pass: Add more methods if space allows
        for (mp in methodPriorities) {
            if (mp.method !in methodsToExpand) {
                val methodLength = mp.method.textLength
                if (estimatedLength + methodLength <= MAX_CONTEXT_LENGTH * 0.9) {
                    methodsToExpand.add(mp.method)
                    preservedMethods.add(mp.method.name)
                    estimatedLength += methodLength
                }
            }
        }
        
        // Build the collapsed content
        val result = StringBuilder()
        val markedResult = StringBuilder()
        
        var lastOffset = 0
        val sortedMethods = psiFile.classes.flatMap { it.methods.toList() }.sortedBy { it.textOffset }
        
        for (method in sortedMethods) {
            val methodStart = method.textRange.startOffset
            val methodEnd = method.textRange.endOffset
            
            // Add content before method with bounds checking
            if (methodStart > lastOffset) {
                val safeLastOffset = minOf(lastOffset, originalText.length)
                val safeMethodStart = minOf(methodStart, originalText.length)
                if (safeLastOffset < safeMethodStart) {
                    val beforeMethod = originalText.substring(safeLastOffset, safeMethodStart)
                    result.append(beforeMethod)
                    
                    val safeMarkedLastOffset = minOf(lastOffset, markedText.length)
                    val safeMarkedMethodStart = minOf(methodStart, markedText.length)
                    if (safeMarkedLastOffset < safeMarkedMethodStart) {
                        markedResult.append(markedText.substring(safeMarkedLastOffset, safeMarkedMethodStart))
                    }
                }
            }
            
            if (method in methodsToExpand) {
                // Include full method with bounds checking
                val safeMethodStart = minOf(methodStart, originalText.length)
                val safeMethodEnd = minOf(methodEnd, originalText.length)
                if (safeMethodStart < safeMethodEnd) {
                    result.append(originalText.substring(safeMethodStart, safeMethodEnd))
                    
                    val safeMarkedStart = minOf(methodStart, markedText.length)
                    val safeMarkedEnd = minOf(methodEnd, markedText.length)
                    if (safeMarkedStart < safeMarkedEnd) {
                        markedResult.append(markedText.substring(safeMarkedStart, safeMarkedEnd))
                    }
                }
            } else {
                // Collapse method body
                val collapsedMethod = collapseMethodBody(method, originalText)
                result.append(collapsedMethod)
                
                // Handle cursor marker in collapsed method with bounds checking
                val safeMarkedStart = minOf(methodStart, markedText.length)
                val safeMarkedEnd = minOf(methodEnd, markedText.length)
                val methodTextInMarked = if (safeMarkedStart < safeMarkedEnd) {
                    markedText.substring(safeMarkedStart, safeMarkedEnd)
                } else {
                    ""
                }
                if (methodTextInMarked.contains("[CURSOR]")) {
                    // Keep cursor marker in collapsed version
                    val beforeCursor = methodTextInMarked.substringBefore("[CURSOR]")
                    val signatureEnd = method.body?.textOffset ?: methodEnd
                    val relativeSignatureEnd = signatureEnd - methodStart
                    
                    if (beforeCursor.length < relativeSignatureEnd) {
                        // Cursor is before body - keep in signature
                        markedResult.append(collapsedMethod.replace(METHOD_BODY_PLACEHOLDER, "[CURSOR]$METHOD_BODY_PLACEHOLDER"))
                    } else {
                        // Cursor is in body - place after signature
                        markedResult.append(collapsedMethod.replace(METHOD_BODY_PLACEHOLDER, " { [CURSOR] /* method body hidden */ }"))
                    }
                } else {
                    markedResult.append(collapsedMethod)
                }
            }
            
            lastOffset = methodEnd
        }
        
        // Add remaining content
        if (lastOffset < originalText.length) {
            val safeLastOffset = minOf(lastOffset, originalText.length)
            result.append(originalText.substring(safeLastOffset))
            
            if (lastOffset < markedText.length) {
                val safeMarkedLastOffset = minOf(lastOffset, markedText.length)
                markedResult.append(markedText.substring(safeMarkedLastOffset))
            }
        }
        
        return Triple(result.toString(), markedResult.toString(), preservedMethods)
    }

    /**
     * Collapse a method body using PSI information
     */
    private fun collapseMethodBody(method: PsiMethod, originalText: String): String {
        val methodStart = method.textRange.startOffset
        val methodEnd = method.textRange.endOffset
        
        // Bounds checking
        val safeStart = minOf(methodStart, originalText.length)
        val safeEnd = minOf(methodEnd, originalText.length)
        
        if (safeStart >= safeEnd) {
            return "" // Invalid range
        }
        
        val body = method.body ?: return originalText.substring(safeStart, safeEnd)
        
        // Get method signature (everything before the body) with bounds checking
        val signatureEnd = minOf(body.textOffset, originalText.length)
        val safeSignatureStart = minOf(methodStart, originalText.length)
        
        if (safeSignatureStart >= signatureEnd) {
            return originalText.substring(safeStart, safeEnd) // Fallback to full method
        }
        
        val signature = originalText.substring(safeSignatureStart, signatureEnd)
        
        // Check if opening brace is on same line as signature
        val signatureLines = signature.lines()
        val lastSignatureLine = signatureLines.lastOrNull() ?: ""
        
        return if (lastSignatureLine.contains("{")) {
            // Brace on same line - replace inline
            signature.substringBeforeLast("{") + METHOD_BODY_PLACEHOLDER
        } else {
            // Brace on next line
            signature + METHOD_BODY_PLACEHOLDER
        }
    }

    /**
     * Find all methods in a Java file
     */
    private fun collectAllMethods(psiFile: PsiJavaFile): List<PsiMethod> {
        return psiFile.classes.flatMap { psiClass ->
            psiClass.methods.toList()
        }
    }

    /**
     * Find the method containing the given offset
     */
    private fun findMethodContainingOffset(psiFile: PsiJavaFile, offset: Int): PsiMethod? {
        val element = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    /**
     * Data class for method priority
     */
    private data class MethodPriority(
        val method: PsiMethod,
        val priority: Double
    )

    /**
     * Helper tuple class
     */
    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)



    // Keep existing utility methods from original implementation
    
    private fun detectLanguage(fileName: String): String {
        return when {
            fileName.endsWith(".java") -> "java"
            fileName.endsWith(".js") || fileName.endsWith(".jsx") -> "javascript"
            fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> "javascript"
            else -> "text"
        }
    }

    private fun isCocos2dxProject(content: String): Boolean {
        val cocos2dxPatterns = listOf(
            "cc\\.",
            "cc\\.\\w+",
            "ccui\\.",      // Cocos UI components
            "ccui\\.\\w+",  
            "ccs\\.",       // Cocos Studio
            "ccs\\.\\w+",
            "sp\\.",        // Spine animations
            "sp\\.\\w+",
            "cocos2d",
            "CCScene",
            "CCNode"
        )
        return cocos2dxPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(content)
        }
    }

    private fun detectJavaScriptContext(text: String, offset: Int): CursorContextType {
        // Keep existing JavaScript detection logic as it doesn't use PSI
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.lines()
        val currentLine = lines.lastOrNull() ?: ""

        return when {
            currentLine.trim().startsWith("import ") || currentLine.trim().startsWith("from ") ->
                CursorContextType.MODULE_IMPORT

            currentLine.trim().startsWith("export ") ->
                CursorContextType.MODULE_EXPORT

            isInsideArrowFunction(beforeCursor) ->
                CursorContextType.ARROW_FUNCTION

            isInsideJavaScriptFunction(beforeCursor) ->
                CursorContextType.FUNCTION_BODY

            isInsideObjectLiteral(beforeCursor) ->
                CursorContextType.OBJECT_LITERAL

            currentLine.contains("=") && !currentLine.contains("==") && !currentLine.contains("===") ->
                CursorContextType.VARIABLE_ASSIGNMENT

            else -> CursorContextType.UNKNOWN
        }
    }

    // Fallback regex-based truncation for non-Java files
    private fun truncateWithRegex(
        originalContent: String,
        markedContent: String
    ): Triple<String, String, Boolean> {
        // This is a simplified version - for JavaScript files, just do simple truncation
        if (markedContent.length > MAX_CONTEXT_LENGTH) {
            val truncated = markedContent.take(MAX_CONTEXT_LENGTH) + "\n/* ... content truncated ... */"
            val originalTruncated = originalContent.take(MAX_CONTEXT_LENGTH) + "\n/* ... content truncated ... */"
            return Triple(originalTruncated, truncated, true)
        }
        return Triple(originalContent, markedContent, false)
    }

    // Keep existing utility methods for similarity calculation
    
    private fun calculateMethodNameSimilarity(name1: String, name2: String): Double {
        val words1 = splitIntoWords(name1)
        val words2 = splitIntoWords(name2)

        if (words1.isEmpty() || words2.isEmpty()) {
            return calculateStringSimilarity(name1, name2)
        }

        val allWords = (words1 + words2).distinct()
        val vector1 = allWords.map { word -> words1.count { it == word }.toDouble() }
        val vector2 = allWords.map { word -> words2.count { it == word }.toDouble() }

        val dotProduct = vector1.zip(vector2).sumOf { (a, b) -> a * b }
        val norm1 = kotlin.math.sqrt(vector1.sumOf { it * it })
        val norm2 = kotlin.math.sqrt(vector2.sumOf { it * it })

        if (norm1 == 0.0 || norm2 == 0.0) return 0.0

        val wordSimilarity = dotProduct / (norm1 * norm2)
        val charSimilarity = calculateStringSimilarity(name1, name2)

        return 0.7 * wordSimilarity + 0.3 * charSimilarity
    }

    private fun calculateStringSimilarity(s1: String, s2: String, ngramSize: Int = 2): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val ngrams1 = extractNgrams(s1.lowercase(), ngramSize)
        val ngrams2 = extractNgrams(s2.lowercase(), ngramSize)

        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0

        val tf1 = ngrams1.groupingBy { it }.eachCount()
        val tf2 = ngrams2.groupingBy { it }.eachCount()

        val allNgrams = (tf1.keys + tf2.keys).distinct()
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (ngram in allNgrams) {
            val count1 = tf1[ngram]?.toDouble() ?: 0.0
            val count2 = tf2[ngram]?.toDouble() ?: 0.0

            dotProduct += count1 * count2
            norm1 += count1 * count1
            norm2 += count2 * count2
        }

        if (norm1 == 0.0 || norm2 == 0.0) return 0.0

        return dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
    }

    private fun extractNgrams(text: String, n: Int): List<String> {
        if (text.length < n) return listOf(text)

        val ngrams = mutableListOf<String>()
        for (i in 0..text.length - n) {
            ngrams.add(text.substring(i, i + n))
        }
        return ngrams
    }

    private fun splitIntoWords(text: String): List<String> {
        val camelCaseSplit = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")

        return camelCaseSplit.split(Regex("[\\s_-]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    }

    // JavaScript helper methods (keep from original as they don't use PSI)
    
    private fun isInsideArrowFunction(beforeCursor: String): Boolean {
        val arrowPattern = Regex(""".*=>\s*\{[^}]*$""")
        return beforeCursor.lines().any { arrowPattern.matches(it) }
    }

    private fun isInsideJavaScriptFunction(beforeCursor: String): Boolean {
        var braceCount = 0
        var inFunction = false

        for (line in beforeCursor.lines()) {
            val trimmed = line.trim()

            if (trimmed.matches(Regex(""".*function\s+\w*\s*\([^)]*\)\s*\{?.*"""))) {
                inFunction = true
            }

            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }

            if (braceCount == 0 && inFunction) {
                inFunction = false
            }
        }

        return inFunction && braceCount > 0
    }

    /**
     * Find AST pattern matches if enabled
     */
    private fun findAstPatternMatchesIfEnabled(
        psiFile: PsiFile?,
        text: String,
        offset: Int,
        language: String,
        cursorPattern: ASTPatternMatcher.ASTPattern?
    ): List<ASTPatternMatcher.PatternMatch> {
        val config = ConfigurationManager.getInstance(project)
        
        if (!config.isAstPatternMatchingEnabled || cursorPattern == null) {
            return emptyList()
        }
        
        return try {
            // Find similar patterns in the current file
            patternMatcher.findSimilarPatterns(cursorPattern, text, language)
                .take(3) // Limit to top 3 matches
        } catch (e: Exception) {
            println("Failed to find AST pattern matches: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Find AST pattern matches for enhanced context
     */
    private fun findAstPatternMatches(
        psiFile: PsiFile?,
        context: LeanContext,
        offset: Int
    ): List<ASTPatternMatcher.PatternMatch> {
        val config = ConfigurationManager.getInstance(project)
        
        if (!config.isAstPatternMatchingEnabled) {
            return context.astPatternMatches
        }
        
        // Try to extract pattern at cursor if not already done
        val pattern = try {
            patternMatcher.extractPatternAtCursor(
                context.fullContent,
                offset,
                context.language
            )
        } catch (e: Exception) {
            null
        }
        
        return if (pattern != null) {
            try {
                patternMatcher.findSimilarPatterns(
                    pattern,
                    context.fullContent,
                    context.language
                ).take(3)
            } catch (e: Exception) {
                context.astPatternMatches
            }
        } else {
            context.astPatternMatches
        }
    }
    
    /**
     * Extract query string from context for RAG retrieval
     */
    private fun extractQueryFromContext(text: String, offset: Int, contextType: CursorContextType): String {
        // Extract surrounding context (100 chars before and after)
        val contextStart = maxOf(0, offset - 100)
        val contextEnd = minOf(text.length, offset + 100)
        val contextWindow = text.substring(contextStart, contextEnd)
        
        // Extract meaningful tokens
        val tokens = contextWindow.split(Regex("\\W+"))
            .filter { it.isNotEmpty() && it.length > 2 }
            .distinct()
        
        // Add context type hint
        val contextHint = when (contextType) {
            CursorContextType.METHOD_BODY -> "method implementation"
            CursorContextType.CLASS_DECLARATION -> "class definition"
            CursorContextType.FUNCTION_BODY -> "function implementation"
            CursorContextType.VARIABLE_ASSIGNMENT -> "variable assignment"
            else -> "code completion"
        }
        
        return "$contextHint ${tokens.joinToString(" ")}"
    }
    
    private fun isInsideObjectLiteral(beforeCursor: String): Boolean {
        var braceCount = 0
        var inObject = false

        for (line in beforeCursor.lines()) {
            val trimmed = line.trim()

            if (trimmed.contains("={") || trimmed.matches(Regex(""".*\w+\s*:\s*\{.*"""))) {
                inObject = true
            }

            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }

            if (braceCount == 0) {
                inObject = false
            }
        }

        return inObject && braceCount > 0
    }
}