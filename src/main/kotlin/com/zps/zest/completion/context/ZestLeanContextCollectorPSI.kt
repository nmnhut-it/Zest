package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.completion.async.AsyncClassAnalyzer

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
        const val MAX_CONTEXT_LENGTH = 1500
        const val METHOD_BODY_PLACEHOLDER = " { /* method body hidden */ }"
        const val FUNCTION_BODY_PLACEHOLDER = " { /* function body hidden */ }"
        const val SIMILARITY_THRESHOLD = 0.6
    }

    private val asyncAnalyzer = AsyncClassAnalyzer(project)

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
        val preservedFields: Set<String> = emptySet()
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
            // Convert from old LeanContext to new LeanContext
            val oldContext = ZestCocos2dxContextCollector(project).collectCocos2dxContext(editor, offset)
            return LeanContext(
                fileName = oldContext.fileName,
                language = oldContext.language,
                fullContent = oldContext.fullContent,
                markedContent = oldContext.markedContent,
                cursorOffset = oldContext.cursorOffset,
                cursorLine = oldContext.cursorLine,
                contextType = convertOldContextType(oldContext.contextType),
                isTruncated = oldContext.isTruncated,
                preservedMethods = oldContext.preservedMethods,
                preservedFields = oldContext.preservedFields
            )
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

        return LeanContext(
            fileName = fileName,
            language = language,
            fullContent = finalContent,
            markedContent = finalMarkedContent,
            cursorOffset = offset,
            cursorLine = cursorLine,
            contextType = contextType,
            isTruncated = isTruncated,
            preservedMethods = preservedMethods
        )
    }

    /**
     * Collect context with async dependency analysis for better method preservation
     */
    fun collectWithDependencyAnalysis(
        editor: Editor,
        offset: Int,
        onComplete: (LeanContext) -> Unit
    ) {
        // First, get immediate context
        val immediateContext = collectFullFileContext(editor, offset)
        
        // Invoke callback immediately on EDT
        ApplicationManager.getApplication().invokeLater {
            onComplete(immediateContext)
        }

        // Only do async analysis for Java files
        if (immediateContext.language != "java") {
            return
        }

        // Analyze dependencies asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (psiFile is PsiJavaFile) {
                    val element = psiFile.findElementAt(offset)
                    val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

                    if (containingMethod != null && containingClass != null) {
                        asyncAnalyzer.analyzeDependenciesForContext(
                            containingClass,
                            containingMethod
                        ) { methodsToPreserve, fieldsToPreserve ->
                            // Add current method and similar methods
                            val enhancedMethodsToPreserve = methodsToPreserve.toMutableSet()
                            enhancedMethodsToPreserve.add(containingMethod.name)
                            
                            // Find methods with similar names
                            containingClass.methods.forEach { method ->
                                if (method.name != containingMethod.name &&
                                    calculateMethodNameSimilarity(method.name, containingMethod.name) >= SIMILARITY_THRESHOLD
                                ) {
                                    enhancedMethodsToPreserve.add(method.name)
                                }
                            }

                            // Re-process with dependency information
                            val enhancedContext = ApplicationManager.getApplication().runReadAction<LeanContext> {
                                recreateContextWithDependencies(
                                    psiFile,
                                    immediateContext,
                                    enhancedMethodsToPreserve,
                                    fieldsToPreserve,
                                    offset
                                )
                            }

                            // Invoke callback on EDT
                            ApplicationManager.getApplication().invokeLater {
                                onComplete(enhancedContext)
                            }
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
            
            // Add content before method
            if (methodStart > lastOffset) {
                val beforeMethod = originalText.substring(lastOffset, methodStart)
                result.append(beforeMethod)
                markedResult.append(markedText.substring(lastOffset, methodStart))
            }
            
            if (method in methodsToExpand) {
                // Include full method
                result.append(originalText.substring(methodStart, methodEnd))
                markedResult.append(markedText.substring(methodStart, methodEnd))
            } else {
                // Collapse method body
                val collapsedMethod = collapseMethodBody(method, originalText)
                result.append(collapsedMethod)
                
                // Handle cursor marker in collapsed method
                val methodTextInMarked = markedText.substring(methodStart, methodEnd)
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
            result.append(originalText.substring(lastOffset))
            markedResult.append(markedText.substring(lastOffset))
        }
        
        return Triple(result.toString(), markedResult.toString(), preservedMethods)
    }

    /**
     * Collapse a method body using PSI information
     */
    private fun collapseMethodBody(method: PsiMethod, originalText: String): String {
        val body = method.body ?: return originalText.substring(method.textRange.startOffset, method.textRange.endOffset)
        
        // Get method signature (everything before the body)
        val signatureEnd = body.textOffset
        val signature = originalText.substring(method.textRange.startOffset, signatureEnd)
        
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

    /**
     * Convert old context type to new enum
     */
    @Suppress("DEPRECATION")
    private fun convertOldContextType(oldType: ZestLeanContextCollector.CursorContextType): CursorContextType {
        return when (oldType) {
            ZestLeanContextCollector.CursorContextType.METHOD_BODY -> CursorContextType.METHOD_BODY
            ZestLeanContextCollector.CursorContextType.CLASS_DECLARATION -> CursorContextType.CLASS_DECLARATION
            ZestLeanContextCollector.CursorContextType.IMPORT_SECTION -> CursorContextType.IMPORT_SECTION
            ZestLeanContextCollector.CursorContextType.VARIABLE_ASSIGNMENT -> CursorContextType.VARIABLE_ASSIGNMENT
            ZestLeanContextCollector.CursorContextType.AFTER_OPENING_BRACE -> CursorContextType.AFTER_OPENING_BRACE
            ZestLeanContextCollector.CursorContextType.FIELD_DECLARATION -> CursorContextType.FIELD_DECLARATION
            ZestLeanContextCollector.CursorContextType.ANNOTATION -> CursorContextType.ANNOTATION
            ZestLeanContextCollector.CursorContextType.FUNCTION_BODY -> CursorContextType.FUNCTION_BODY
            ZestLeanContextCollector.CursorContextType.FUNCTION_DECLARATION -> CursorContextType.FUNCTION_DECLARATION
            ZestLeanContextCollector.CursorContextType.OBJECT_LITERAL -> CursorContextType.OBJECT_LITERAL
            ZestLeanContextCollector.CursorContextType.ARROW_FUNCTION -> CursorContextType.ARROW_FUNCTION
            ZestLeanContextCollector.CursorContextType.MODULE_IMPORT -> CursorContextType.MODULE_IMPORT
            ZestLeanContextCollector.CursorContextType.MODULE_EXPORT -> CursorContextType.MODULE_EXPORT
            ZestLeanContextCollector.CursorContextType.UNKNOWN -> CursorContextType.UNKNOWN
        }
    }

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