package com.zps.zest.completion.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.ClassAnalyzer
import com.zps.zest.completion.async.AsyncClassAnalyzer
import com.zps.zest.completion.async.SimpleTaskQueue

/**
 * Lean context collector that captures full file content with cursor position
 * for reasoning-based completions. Supports Java and JavaScript with automatic
 * language detection and context truncation.
 * 
 * Features:
 * - Smart method body collapsing to stay within context limits
 * - Preserves methods containing the cursor
 * - Uses cosine similarity to preserve related methods based on name similarity
 * - Supports both word-based and character n-gram similarity matching
 * - Handles camelCase, PascalCase, snake_case method/field names
 */
class ZestLeanContextCollector(private val project: Project) {

    companion object {
        private const val MAX_CONTEXT_LENGTH = 1500  // Increased for priority-based inclusion
        private const val METHOD_BODY_PLACEHOLDER = " { /* method body hidden */ }"
        private const val FUNCTION_BODY_PLACEHOLDER = " { /* function body hidden */ }"
        private const val SIMILARITY_THRESHOLD = 0.6  // Threshold for method/field name similarity
    }

    private val asyncAnalyzer = AsyncClassAnalyzer(project)

    data class LeanContext(
        val fileName: String,
        val language: String,
        val fullContent: String,
        val markedContent: String, // Content with [CURSOR] marker
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

        // JavaScript contexts
        FUNCTION_BODY,
        FUNCTION_DECLARATION,
        OBJECT_LITERAL,
        ARROW_FUNCTION,
        MODULE_IMPORT,
        MODULE_EXPORT,

        UNKNOWN
    }

    fun collectFullFileContext(editor: Editor, offset: Int): LeanContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = detectLanguage(fileName, text)

        // Check for Cocos2d-x and delegate if needed
        if (language == "javascript" && isCocos2dxProject(text)) {
            return ZestCocos2dxContextCollector(project).collectCocos2dxContext(editor, offset)
        }

        // Insert cursor marker
        val markedContent = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)

        // Calculate cursor line
        val cursorLine = document.getLineNumber(offset)

        // Detect context type based on language
        val contextType = when (language) {
            "java" -> detectJavaContext(text, offset)
            "javascript" -> detectJavaScriptContext(text, offset)
            else -> CursorContextType.UNKNOWN
        }

        // Apply truncation if needed
        val (finalContent, finalMarkedContent, isTruncated) = if (markedContent.length > MAX_CONTEXT_LENGTH) {
            truncateWithMethodCollapsing(text, markedContent, language)
        } else {
            Triple(text, markedContent, false)
        }

        return LeanContext(
            fileName = fileName,
            language = language,
            fullContent = finalContent,
            markedContent = finalMarkedContent,
            cursorOffset = offset,
            cursorLine = cursorLine,
            contextType = contextType,
            isTruncated = isTruncated
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
        // First, get immediate context - this needs to be on EDT for editor access
        val immediateContext: LeanContext = if (ApplicationManager.getApplication().isDispatchThread) {
            collectFullFileContext(editor, offset)
        } else {
            ApplicationManager.getApplication().runReadAction<LeanContext> {
                collectFullFileContext(editor, offset)
            }
        }

        // Invoke callback on EDT
        if (ApplicationManager.getApplication().isDispatchThread) {
            onComplete(immediateContext)
        } else {
            ApplicationManager.getApplication().invokeLater {
                onComplete(immediateContext)
            }
        }

        // Only do async analysis for Java files
        if (immediateContext.language != "java") {
            return
        }

        // Analyze dependencies asynchronously - must get PSI elements in read action
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
                            // Add current method name to the preserved set
                            val currentMethodName = containingMethod.name
                            val enhancedMethodsToPreserve = methodsToPreserve.toMutableSet()
                            enhancedMethodsToPreserve.add(currentMethodName)
                            
                            // Also find and add all methods with similar names
                            val allMethods = containingClass.methods
                            for (method in allMethods) {
                                val methodName = method.name
                                if (methodName != currentMethodName && 
                                    calculateMethodNameSimilarity(methodName, currentMethodName) >= SIMILARITY_THRESHOLD) {
                                    enhancedMethodsToPreserve.add(methodName)
                                }
                            }
                            
                            // Re-process with dependency information
                            val enhancedMarkedContent = collapseMethodBodiesWithDependencies(
                                immediateContext.markedContent,
                                if (immediateContext.language == "java") METHOD_BODY_PLACEHOLDER else FUNCTION_BODY_PLACEHOLDER,
                                immediateContext.language,
                                enhancedMethodsToPreserve,
                                fieldsToPreserve
                            )

                            val enhancedContext = immediateContext.copy(
                                markedContent = enhancedMarkedContent,
                                fullContent = enhancedMarkedContent.replace("[CURSOR]", ""),
                                preservedMethods = enhancedMethodsToPreserve,
                                preservedFields = fieldsToPreserve
                            )

                            // Always invoke callback on EDT
                            ApplicationManager.getApplication().invokeLater {
                                onComplete(enhancedContext)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun detectLanguage(fileName: String, content: String): String {
        return when {
            fileName.endsWith(".java") -> "java"
            fileName.endsWith(".js") || fileName.endsWith(".jsx") -> "javascript"
            fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> "javascript" // Treat TS as JS for context
            else -> "text"
        }
    }

    private fun isCocos2dxProject(content: String): Boolean {
        // Detect Cocos2d-x usage patterns
        val cocos2dxPatterns = listOf(
            "cc\\.",           // cc.Node, cc.Scene, etc.
            "cc\\.\\w+",       // Any cc.Something
            "cocos2d",         // Direct cocos2d references
            "CCScene",         // Legacy patterns
            "CCNode"
        )

        return cocos2dxPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(content)
        }
    }

    private fun detectJavaContext(text: String, offset: Int): CursorContextType {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.lines()
        val currentLine = lines.lastOrNull() ?: ""
        val previousLines = lines.takeLast(10)

        return when {
            // Check for import section
            currentLine.trim().startsWith("import") ||
                    previousLines.any { it.trim().startsWith("import") } &&
                    !hasSignificantCodeAfterImports(beforeCursor) ->
                CursorContextType.IMPORT_SECTION

            // Check for method body (inside braces after method signature)
            isInsideJavaMethod(beforeCursor) ->
                CursorContextType.METHOD_BODY

            // Check for class level (not inside any method)
            isAtJavaClassLevel(beforeCursor) ->
                CursorContextType.CLASS_DECLARATION

            // Check for variable assignment
            currentLine.contains("=") && !currentLine.contains("==") &&
                    !currentLine.trim().startsWith("//") ->
                CursorContextType.VARIABLE_ASSIGNMENT

            // Check for after opening brace
            currentLine.trim().endsWith("{") ||
                    previousLines.lastOrNull()?.trim()?.endsWith("{") == true ->
                CursorContextType.AFTER_OPENING_BRACE

            else -> CursorContextType.UNKNOWN
        }
    }

    private fun detectJavaScriptContext(text: String, offset: Int): CursorContextType {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.lines()
        val currentLine = lines.lastOrNull() ?: ""
        val previousLines = lines.takeLast(10)

        return when {
            // Module imports/exports
            currentLine.trim().startsWith("import ") || currentLine.trim().startsWith("from ") ->
                CursorContextType.MODULE_IMPORT

            currentLine.trim().startsWith("export ") ->
                CursorContextType.MODULE_EXPORT

            // Arrow functions
            isInsideArrowFunction(beforeCursor, offset) ->
                CursorContextType.ARROW_FUNCTION

            // Regular function body
            isInsideJavaScriptFunction(beforeCursor) ->
                CursorContextType.FUNCTION_BODY

            // Function declaration level
            isAtJavaScriptFunctionLevel(beforeCursor) ->
                CursorContextType.FUNCTION_DECLARATION

            // Object literal
            isInsideObjectLiteral(beforeCursor) ->
                CursorContextType.OBJECT_LITERAL

            // Variable assignment
            currentLine.contains("=") && !currentLine.contains("==") && !currentLine.contains("===") &&
                    !currentLine.trim().startsWith("//") ->
                CursorContextType.VARIABLE_ASSIGNMENT

            // After opening brace
            currentLine.trim().endsWith("{") ||
                    previousLines.lastOrNull()?.trim()?.endsWith("{") == true ->
                CursorContextType.AFTER_OPENING_BRACE

            else -> CursorContextType.UNKNOWN
        }
    }

    private fun truncateWithMethodCollapsing(
        originalContent: String,
        markedContent: String,
        language: String
    ): Triple<String, String, Boolean> {

        val placeholder = when (language) {
            "java" -> METHOD_BODY_PLACEHOLDER
            "javascript" -> FUNCTION_BODY_PLACEHOLDER
            else -> " { /* body hidden */ }"
        }

        // Use priority-based collapsing
        val (collapsedMarkedContent, allContentIncluded) = collapseMethodBodiesInPriorityOrder(
            markedContent,
            placeholder,
            language,
            MAX_CONTEXT_LENGTH,
            emptySet(), // No preserved methods in immediate context
            emptySet()  // No preserved fields in immediate context
        )

        // Generate original content by removing cursor marker from the result
        val collapsedOriginalContent = collapsedMarkedContent.replace("[CURSOR]", "")

        return Triple(collapsedOriginalContent, collapsedMarkedContent, !allContentIncluded)
    }

    /**
     * Collapse method bodies in priority order, preserving the most relevant methods first
     * Returns the collapsed content and whether all content fit within the limit
     */
    private fun collapseMethodBodiesInPriorityOrder(
        content: String,
        placeholder: String,
        language: String,
        maxLength: Int,
        preservedMethods: Set<String> = emptySet(),
        preservedFields: Set<String> = emptySet()
    ): Pair<String, Boolean> {
        val lines = content.lines()
        val cursorLine = findCursorLine(lines)
        
        // First pass: identify all methods and calculate their priorities
        val methodInfoList = mutableListOf<MethodInfo>()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val methodMatch = when (language) {
                "java" -> findJavaMethodStart(line, i, lines)
                "javascript" -> findJavaScriptFunctionStart(line, i, lines)
                else -> null
            }
            
            if (methodMatch != null) {
                val signatureStartIndex = if (methodMatch.signatureStartIndex != -1) {
                    methodMatch.signatureStartIndex
                } else {
                    i - (methodMatch.signatureLines.size - 1)
                }
                
                val methodEnd = findMatchingCloseBrace(lines, methodMatch.braceLineIndex)
                val containsCursor = cursorLine in signatureStartIndex..methodEnd
                val methodName = extractMethodName(lines[i])
                
                // Calculate priority (higher is more important)
                var priority = 0.0
                
                // Highest priority: contains cursor
                if (containsCursor) {
                    priority = 1000.0
                }
                
                // High priority: in preserved methods set
                if (methodName != null && methodName in preservedMethods) {
                    priority = maxOf(priority, 100.0)
                }
                
                // Medium priority: similar to preserved methods
                if (methodName != null && preservedMethods.isNotEmpty()) {
                    val maxSimilarity = preservedMethods.maxOf { preserved ->
                        calculateMethodNameSimilarity(methodName, preserved)
                    }
                    if (maxSimilarity >= SIMILARITY_THRESHOLD) {
                        priority = maxOf(priority, 50.0 + maxSimilarity * 50.0)
                    }
                }
                
                // Low priority: distance from cursor (closer is better)
                if (priority == 0.0 && cursorLine >= 0) {
                    val distance = kotlin.math.abs(i - cursorLine)
                    priority = 10.0 / (1.0 + distance / 100.0)
                }
                
                methodInfoList.add(
                    MethodInfo(
                        startLine = signatureStartIndex,
                        endLine = methodEnd,
                        signatureLines = methodMatch.signatureLines,
                        braceLineIndex = methodMatch.braceLineIndex,
                        priority = priority,
                        methodName = methodName ?: "unknown"
                    )
                )
                
                i = methodEnd + 1
            } else {
                i++
            }
        }
        
        // Sort methods by priority (descending)
        methodInfoList.sortByDescending { it.priority }
        
        // Second pass: build result with methods in priority order
        val result = mutableListOf<String>()
        val processedRanges = mutableListOf<IntRange>()
        val expandedMethods = mutableSetOf<MethodInfo>()
        
        // Always expand highest priority methods first
        for (methodInfo in methodInfoList) {
            if (methodInfo.priority >= 100.0) { // Contains cursor or is in preserved set
                expandedMethods.add(methodInfo)
                processedRanges.add(methodInfo.startLine..methodInfo.endLine)
            }
        }
        
        // Build initial content with expanded high-priority methods
        var currentLength = buildContentWithExpandedMethods(
            lines, expandedMethods, processedRanges, placeholder
        ).length
        
        // Add more methods in priority order until we approach the limit
        for (methodInfo in methodInfoList) {
            if (methodInfo in expandedMethods) continue
            
            // Estimate the additional length if we expand this method
            val methodLength = (methodInfo.startLine..methodInfo.endLine)
                .sumOf { lines.getOrNull(it)?.length?.plus(1) ?: 0 }
            
            // Leave some buffer space (10% of max length)
            if (currentLength + methodLength <= maxLength * 0.9) {
                expandedMethods.add(methodInfo)
                processedRanges.add(methodInfo.startLine..methodInfo.endLine)
                currentLength += methodLength
            }
        }
        
        // Build final result
        val finalContent = buildContentWithExpandedMethods(
            lines, expandedMethods, processedRanges, placeholder
        )
        
        // Check if everything fit
        val allContentIncluded = expandedMethods.size == methodInfoList.size
        
        return Pair(finalContent, allContentIncluded)
    }
    
    /**
     * Build content with specified methods expanded and others collapsed
     */
    private fun buildContentWithExpandedMethods(
        lines: List<String>,
        expandedMethods: Set<MethodInfo>,
        processedRanges: List<IntRange>,
        placeholder: String
    ): String {
        val result = mutableListOf<String>()
        var i = 0
        
        // Sort processed ranges by start line
        val sortedRanges = processedRanges.sortedBy { it.first }
        
        while (i < lines.size) {
            // Check if this line is part of a method
            val containingMethod = expandedMethods.find { method ->
                i in method.startLine..method.endLine
            }
            
            if (containingMethod != null) {
                // This is part of an expanded method
                if (i == containingMethod.startLine) {
                    // Add signature lines
                    result.addAll(containingMethod.signatureLines)
                    
                    // Add the method body
                    val bodyStart = if (containingMethod.braceLineIndex > containingMethod.startLine + containingMethod.signatureLines.size - 1) {
                        containingMethod.braceLineIndex
                    } else {
                        containingMethod.startLine + containingMethod.signatureLines.size
                    }
                    
                    for (j in bodyStart..containingMethod.endLine) {
                        if (j < lines.size) {
                            result.add(lines[j])
                        }
                    }
                }
                
                // Skip to end of method
                i = containingMethod.endLine + 1
            } else {
                // Check if this line is part of any method
                val methodRange = sortedRanges.find { i in it }
                
                if (methodRange != null) {
                    // This is a collapsed method
                    val methodInfo = expandedMethods.find { it.startLine == methodRange.first }
                        ?: methodInfoList.find { it.startLine == methodRange.first }
                    
                    if (methodInfo != null && i == methodInfo.startLine) {
                        // Add collapsed method
                        result.addAll(methodInfo.signatureLines)
                        
                        if (methodInfo.braceLineIndex != -1) {
                            val collapsedBody = collapseBodyFromBrace(lines, methodInfo.braceLineIndex, placeholder)
                            if (methodInfo.braceLineIndex > methodInfo.startLine + methodInfo.signatureLines.size - 1) {
                                result.add(collapsedBody)
                            } else {
                                // Replace last line with collapsed version
                                result[result.size - 1] = collapsedBody
                            }
                        }
                    }
                    
                    // Skip to end of method
                    i = methodRange.last + 1
                } else {
                    // Regular line
                    result.add(lines[i])
                    i++
                }
            }
        }
        
        return removeDuplicateLines(result).joinToString("\n")
    }
    
    /**
     * Data class to hold method information
     */
    private data class MethodInfo(
        val startLine: Int,
        val endLine: Int,
        val signatureLines: List<String>,
        val braceLineIndex: Int,
        val priority: Double,
        val methodName: String
    )
    
    /**
     * List to track all methods found during parsing
     */
    private var methodInfoList = mutableListOf<MethodInfo>()

    /**
     * Remove consecutive duplicate lines from the result
     */
    private fun removeDuplicateLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        
        val result = mutableListOf<String>()
        val recentLines = mutableSetOf<String>()
        val lookback = 5 // Check last 5 non-empty lines for duplicates
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip if this exact line (ignoring whitespace) was recently added
            if (trimmed.isNotEmpty() && trimmed in recentLines) {
                continue
            }
            
            result.add(line)
            
            // Update recent lines set
            if (trimmed.isNotEmpty()) {
                recentLines.add(trimmed)
                // Keep only the last N lines in the set
                if (recentLines.size > lookback) {
                    recentLines.remove(recentLines.first())
                }
            }
        }
        
        return result
    }



    /**
     * Calculate cosine similarity between two strings based on character n-grams
     */
    private fun calculateStringSimilarity(s1: String, s2: String, ngramSize: Int = 2): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val ngrams1 = extractNgrams(s1.toLowerCase(), ngramSize)
        val ngrams2 = extractNgrams(s2.toLowerCase(), ngramSize)
        
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0
        
        // Calculate term frequencies
        val tf1 = ngrams1.groupingBy { it }.eachCount()
        val tf2 = ngrams2.groupingBy { it }.eachCount()
        
        // Calculate cosine similarity
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
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2))
    }
    
    /**
     * Extract character n-grams from a string
     */
    private fun extractNgrams(text: String, n: Int): List<String> {
        if (text.length < n) return listOf(text)
        
        val ngrams = mutableListOf<String>()
        for (i in 0..text.length - n) {
            ngrams.add(text.substring(i, i + n))
        }
        return ngrams
    }
    
    /**
     * Split camelCase or snake_case string into words
     */
    private fun splitIntoWords(text: String): List<String> {
        // Handle camelCase and PascalCase
        val camelCaseSplit = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")
        
        // Handle snake_case and kebab-case
        return camelCaseSplit.split(Regex("[\\s_-]+"))
            .filter { it.isNotEmpty() }
            .map { it.toLowerCase() }
    }
    
    /**
     * Calculate word-based cosine similarity between method names
     */
    private fun calculateMethodNameSimilarity(name1: String, name2: String): Double {
        val words1 = splitIntoWords(name1)
        val words2 = splitIntoWords(name2)
        
        if (words1.isEmpty() || words2.isEmpty()) {
            // Fall back to character-based similarity
            return calculateStringSimilarity(name1, name2)
        }
        
        // Create word frequency vectors
        val allWords = (words1 + words2).distinct()
        val vector1 = allWords.map { word -> words1.count { it == word }.toDouble() }
        val vector2 = allWords.map { word -> words2.count { it == word }.toDouble() }
        
        // Calculate cosine similarity
        val dotProduct = vector1.zip(vector2).sumOf { (a, b) -> a * b }
        val norm1 = Math.sqrt(vector1.sumOf { it * it })
        val norm2 = Math.sqrt(vector2.sumOf { it * it })
        
        if (norm1 == 0.0 || norm2 == 0.0) return 0.0
        
        val wordSimilarity = dotProduct / (norm1 * norm2)
        
        // Combine with character-based similarity for better results
        val charSimilarity = calculateStringSimilarity(name1, name2)
        
        // Weight word similarity more heavily
        return 0.7 * wordSimilarity + 0.3 * charSimilarity
    }
    
    /**
     * Check if a method name should be preserved based on similarity to preserved methods
     */
    private fun shouldPreserveMethodBySimilarity(
        methodName: String, 
        preserveMethods: Set<String>,
        similarityThreshold: Double = SIMILARITY_THRESHOLD
    ): Boolean {
        // First check exact match
        if (methodName in preserveMethods) return true
        
        // Then check similarity
        for (preservedMethod in preserveMethods) {
            val similarity = calculateMethodNameSimilarity(methodName, preservedMethod)
            if (similarity >= similarityThreshold) {
                return true
            }
        }
        
        return false
    }

    /**
     * Check if a field name should be preserved based on similarity to preserved fields
     */
    private fun shouldPreserveFieldBySimilarity(
        fieldName: String,
        preserveFields: Set<String>,
        similarityThreshold: Double = SIMILARITY_THRESHOLD
    ): Boolean {
        // First check exact match
        if (fieldName in preserveFields) return true
        
        // Then check similarity
        for (preservedField in preserveFields) {
            val similarity = calculateMethodNameSimilarity(fieldName, preservedField)
            if (similarity >= similarityThreshold) {
                return true
            }
        }
        
        return false
    }

    /**
     * Enhanced collapsing that preserves methods and fields based on dependencies
     */
    private fun collapseMethodBodiesWithDependencies(
        content: String,
        placeholder: String,
        language: String,
        preserveMethods: Set<String>,
        preserveFields: Set<String>
    ): String {
        // Use priority-based collapsing with preserved methods
        val (collapsedContent, _) = collapseMethodBodiesInPriorityOrder(
            content,
            placeholder,
            language,
            Int.MAX_VALUE, // No limit when we have dependency information
            preserveMethods,
            preserveFields
        )
        
        return collapsedContent
    }

    /**
     * Extract method name from a line
     */
    private fun extractMethodName(line: String): String? {
        // Remove annotations and comments
        val cleanLine = line.trim().replace(Regex("""^@\w+.*"""), "").trim()

        // Java method patterns - more specific
        // Pattern 1: Traditional method with return type
        var match =
            Regex("""(?:public|private|protected|static|final|abstract|synchronized|native)*\s*(?:<[^>]+>\s+)?(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(""").find(
                cleanLine
            )
        if (match != null && match.groupValues.size > 2) {
            return match.groupValues[2] // Method name is the second group
        }

        // Pattern 2: Constructor (starts with capital letter)
        match = Regex("""(?:public|private|protected)*\s*([A-Z]\w*)\s*\(""").find(cleanLine)
        if (match != null) {
            return match.groupValues[1]
        }

        // Pattern 3: Method without explicit modifiers
        match = Regex("""^\s*(\w+)\s+(\w+)\s*\(""").find(cleanLine)
        if (match != null && match.groupValues.size > 2) {
            return match.groupValues[2]
        }

        // JavaScript patterns
        match = Regex("""function\s+(\w+)""").find(cleanLine)
        if (match != null) return match.groupValues[1]

        match = Regex("""(\w+)\s*:\s*function""").find(cleanLine)
        if (match != null) return match.groupValues[1]

        match = Regex("""(?:const|let|var)\s+(\w+)\s*=""").find(cleanLine)
        if (match != null) return match.groupValues[1]

        // ES6 method syntax
        match = Regex("""^\s*(\w+)\s*\([^)]*\)\s*\{""").find(cleanLine)
        if (match != null) return match.groupValues[1]

        return null
    }

    /**
     * Extract field name from a field declaration line
     */
    private fun extractFieldName(line: String): String? {
        val match = Regex("""(?:private|protected|public|static|final)*\s+\w+\s+(\w+)\s*[;=]""").find(line)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Check if a line is a field declaration
     */
    private fun isFieldDeclaration(line: String): Boolean {
        return line.matches(Regex(""".*(?:private|protected|public|static|final).*\w+\s+\w+\s*[;=].*""")) &&
                !line.contains("(")
    }

    /**
     * Finds the line number containing the [CURSOR] marker
     */
    private fun findCursorLine(lines: List<String>): Int {
        return lines.indexOfFirst { it.contains("[CURSOR]") }
    }



    private data class MethodMatch(
        val signatureLines: List<String>,
        val braceLineIndex: Int,
        val signatureStartIndex: Int = -1
    )

    private fun findJavaMethodStart(line: String, lineIndex: Int, lines: List<String>): MethodMatch? {
        val trimmed = line.trim()

        // Skip if this line is an annotation
        if (trimmed.startsWith("@")) {
            return null
        }

        // Look for Java method patterns
        val methodPatterns = listOf(
            // Standard method with modifiers
            Regex("""^\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native)\s+)*(?:<[^>]+>\s+)?(?:\w+(?:<[^>]+>)?\s+)+\w+\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\s*\{?\s*$"""),
            // Constructor
            Regex("""^\s*(?:(?:public|private|protected)\s+)?[A-Z]\w*\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\s*\{?\s*$"""),
            // Method with annotations (look at previous lines)
            Regex("""^\s*(?:\w+(?:<[^>]+>)?\s+)+\w+\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\s*\{?\s*$""")
        )

        for (pattern in methodPatterns) {
            if (pattern.matches(trimmed)) {
                // Check if this line or the next contains opening brace
                val braceIndex = when {
                    line.contains("{") -> lineIndex
                    lineIndex + 1 < lines.size && lines[lineIndex + 1].trim().startsWith("{") -> lineIndex + 1
                    else -> -1
                }

                // Include annotation lines if present
                val signatureStart = findAnnotationStart(lines, lineIndex)
                val signatureLines = lines.subList(signatureStart, lineIndex + 1)

                return MethodMatch(signatureLines, braceIndex, signatureStart)
            }
        }

        return null
    }

    private fun findJavaScriptFunctionStart(line: String, lineIndex: Int, lines: List<String>): MethodMatch? {
        val trimmed = line.trim()

        // JavaScript function patterns
        val functionPatterns = listOf(
            // function declaration: function name() {
            Regex("""^\s*function\s+\w*\s*\([^)]*\)\s*\{?\s*$"""),
            // arrow function: const name = () => {
            Regex("""^\s*(?:const|let|var)\s+\w+\s*=\s*\([^)]*\)\s*=>\s*\{?\s*$"""),
            // arrow function: name = () => {
            Regex("""^\s*\w+\s*=\s*\([^)]*\)\s*=>\s*\{?\s*$"""),
            // method in object: methodName: function() {
            Regex("""^\s*\w+\s*:\s*function\s*\([^)]*\)\s*\{?\s*$"""),
            // ES6 method: methodName() {
            Regex("""^\s*\w+\s*\([^)]*\)\s*\{?\s*$"""),
            // async function
            Regex("""^\s*async\s+function\s+\w*\s*\([^)]*\)\s*\{?\s*$""")
        )

        for (pattern in functionPatterns) {
            if (pattern.matches(trimmed)) {
                val braceIndex = when {
                    line.contains("{") -> lineIndex
                    lineIndex + 1 < lines.size && lines[lineIndex + 1].trim().startsWith("{") -> lineIndex + 1
                    else -> -1
                }

                return MethodMatch(listOf(line), braceIndex, lineIndex)
            }
        }

        return null
    }

    private fun findAnnotationStart(lines: List<String>, methodLineIndex: Int): Int {
        var start = methodLineIndex

        // Look backwards for annotations
        for (i in (methodLineIndex - 1) downTo 0) {
            val line = lines[i].trim()
            if (line.startsWith("@") || line.isEmpty() || line.startsWith("//") || line.startsWith("/*")) {
                if (line.startsWith("@")) {
                    start = i
                }
                continue
            } else {
                break
            }
        }

        return start
    }

    private fun collapseBodyFromBrace(lines: List<String>, braceLineIndex: Int, placeholder: String): String {
        val braceLine = lines[braceLineIndex]
        val beforeBrace = braceLine.substringBefore("{")
        val afterBrace = braceLine.substringAfter("{")

        // If there's content after the opening brace on the same line, preserve it
        return if (afterBrace.trim().isNotEmpty() && !afterBrace.trim().startsWith("}")) {
            beforeBrace + "{" + placeholder + " }"
        } else {
            beforeBrace + placeholder
        }
    }

    private fun findMatchingCloseBrace(lines: List<String>, openBraceLineIndex: Int): Int {
        var braceCount = 0
        var foundOpenBrace = false

        for (i in openBraceLineIndex until lines.size) {
            val line = lines[i]

            for (char in line) {
                when (char) {
                    '{' -> {
                        braceCount++
                        foundOpenBrace = true
                    }

                    '}' -> {
                        if (foundOpenBrace) {
                            braceCount--
                            if (braceCount == 0) {
                                return i
                            }
                        }
                    }
                }
            }
        }

        // If no matching brace found, return the last line
        return lines.size - 1
    }

    // JavaScript-specific context detection methods
    private fun isInsideArrowFunction(beforeCursor: String, offset: Int): Boolean {
        val arrowPattern = Regex(""".*=>\s*\{[^}]*$""")
        return beforeCursor.lines().any { arrowPattern.matches(it) }
    }

    private fun isInsideJavaScriptFunction(beforeCursor: String): Boolean {
        var braceCount = 0
        var inFunction = false

        val lines = beforeCursor.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Look for function declaration
            if (trimmed.matches(Regex(""".*function\s+\w*\s*\([^)]*\)\s*\{?.*"""))) {
                inFunction = true
            }

            // Count braces
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }

            if (braceCount == 0 && inFunction) {
                inFunction = false
            }
        }

        return inFunction && braceCount > 0
    }

    private fun isAtJavaScriptFunctionLevel(beforeCursor: String): Boolean {
        val lines = beforeCursor.lines()
        return lines.any { line ->
            line.trim().matches(Regex(""".*function\s+\w+\s*\([^)]*\)\s*\{?.*"""))
        }
    }

    private fun isInsideObjectLiteral(beforeCursor: String): Boolean {
        var braceCount = 0
        var inObject = false

        val lines = beforeCursor.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Look for object literal start
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

    // Existing Java-specific methods (keeping original logic)
    private fun isInsideJavaMethod(beforeCursor: String): Boolean {
        var braceCount = 0
        var inMethod = false

        val lines = beforeCursor.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Look for method signature
            if (trimmed.matches(Regex(".*\\b(public|private|protected|static|final)\\s+.*\\s+\\w+\\s*\\(.*\\).*\\{?"))) {
                inMethod = true
            }

            // Count braces
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }

            // If we exit all braces, we're not in a method anymore
            if (braceCount == 0 && inMethod) {
                inMethod = false
            }
        }

        return inMethod && braceCount > 0
    }

    private fun isAtJavaClassLevel(beforeCursor: String): Boolean {
        var braceCount = 0
        var inClass = false

        val lines = beforeCursor.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Look for class declaration
            if (trimmed.matches(Regex(".*\\b(class|interface|enum)\\s+\\w+.*\\{?"))) {
                inClass = true
            }

            // Count braces
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
        }

        // At class level if we're inside class but not deeply nested
        return inClass && braceCount == 1
    }

    private fun hasSignificantCodeAfterImports(beforeCursor: String): Boolean {
        val lines = beforeCursor.lines()

        // Find last import line
        var lastImportIndex = -1
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("import")) {
                lastImportIndex = i
            }
        }

        if (lastImportIndex == -1) return true

        // Check if there's significant code after imports
        val afterImports = lines.drop(lastImportIndex + 1)
        return afterImports.any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") &&
                    !trimmed.startsWith("package")
        }
    }
}