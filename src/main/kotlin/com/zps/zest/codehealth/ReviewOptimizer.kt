package com.zps.zest.codehealth

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Optimizes review process by grouping methods and handling small files
 */
class ReviewOptimizer(private val project: Project) {
    
    companion object {
        const val SMALL_FILE_THRESHOLD = 2000 // lines - increased to review more whole files
        const val CHUNK_SIZE = 500 // lines per chunk for large files
        const val CHUNK_OVERLAP = 50 // lines of overlap between chunks for context
    }
    
    data class ReviewUnit(
        val type: ReviewType,
        val className: String,
        val methods: List<String>,
        val filePath: String? = null,
        val lineCount: Int = 0,
        val language: String = "java", // Add language field
        val startLine: Int = 0, // For partial files
        val endLine: Int = 0 // For partial files
    ) {
        enum class ReviewType {
            WHOLE_FILE,      // Review entire small file
            PARTIAL_FILE,    // Review part of a large file
            METHOD_GROUP,    // Review group of methods from same class
            JS_TS_REGION    // Review JS/TS region
        }
        
        fun getIdentifier(): String {
            return when (type) {
                ReviewType.WHOLE_FILE -> "file:$className"
                ReviewType.PARTIAL_FILE -> "partial:$className:$startLine-$endLine" // Include line range for uniqueness
                ReviewType.METHOD_GROUP -> "class:$className:${methods.sorted().joinToString(",")}"
                ReviewType.JS_TS_REGION -> "region:$className" // For JS/TS, className is the region identifier
            }
        }
        
        fun getDescription(): String {
            return when (type) {
                ReviewType.WHOLE_FILE -> "Entire file: $className ($lineCount lines)"
                ReviewType.PARTIAL_FILE -> "Partial file: $className (lines $startLine-$endLine)"
                ReviewType.METHOD_GROUP -> "Class $className: ${methods.size} methods (${methods.joinToString(", ")})"
                ReviewType.JS_TS_REGION -> "JS/TS Region: $className"
            }
        }
    }
    
    /**
     * Groups methods by class and identifies small files for whole-file review
     */
    fun optimizeReviewUnits(methodFQNs: List<String>): List<ReviewUnit> {
        val reviewUnits = mutableListOf<ReviewUnit>()
        
        // Separate Java methods from JS/TS regions
        val javaMethods = mutableListOf<String>()
        val jsTsRegions = mutableListOf<String>()
        
        methodFQNs.forEach { fqn ->
            when {
                fqn.contains(".js:") || fqn.contains(".ts:") -> jsTsRegions.add(fqn)
                else -> javaMethods.add(fqn)
            }
        }
        
        // Process Java methods
        if (javaMethods.isNotEmpty()) {
            reviewUnits.addAll(optimizeJavaReviewUnits(javaMethods))
        }
        
        // Process JS/TS regions
        if (jsTsRegions.isNotEmpty()) {
            reviewUnits.addAll(optimizeJsTsReviewUnits(jsTsRegions))
        }
        
        return reviewUnits
    }
    
    /**
     * Optimize Java methods into review units
     */
    private fun optimizeJavaReviewUnits(methodFQNs: List<String>): List<ReviewUnit> {
        // Group methods by class
        val methodsByClass = methodFQNs.groupBy { fqn ->
            fqn.substringBeforeLast(".")
        }
        
        val reviewUnits = mutableListOf<ReviewUnit>()
        
        methodsByClass.forEach { (className, methods) ->
            // Try to find the class file - wrap in read action
            val psiClassInfo = com.intellij.openapi.application.ReadAction.compute<Pair<com.intellij.psi.PsiClass?, Int>, Throwable> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                
                val lineCount = psiClass?.containingFile?.text?.lines()?.size ?: 0
                Pair(psiClass, lineCount)
            }
            
            val psiClass = psiClassInfo.first
            val lineCount = psiClassInfo.second
            
            if (psiClass != null) {
                val filePath = psiClass.containingFile?.virtualFile?.path
                
                // If file is small, review the whole file
                if (lineCount <= SMALL_FILE_THRESHOLD) {
                    reviewUnits.add(ReviewUnit(
                        type = ReviewUnit.ReviewType.WHOLE_FILE,
                        className = className,
                        methods = methods.map { it.substringAfterLast(".") },
                        filePath = filePath,
                        lineCount = lineCount,
                        language = "java"
                    ))
                } else {
                    // For large files, create chunks
                    println("[ReviewOptimizer] Large file detected: $className with $lineCount lines. Creating chunks...")
                    var currentLine = 0
                    var chunkNumber = 1
                    
                    while (currentLine < lineCount) {
                        val startLine = if (currentLine == 0) 0 else currentLine - CHUNK_OVERLAP
                        val endLine = minOf(currentLine + CHUNK_SIZE, lineCount)
                        
                        reviewUnits.add(ReviewUnit(
                            type = ReviewUnit.ReviewType.PARTIAL_FILE,
                            className = className,
                            methods = methods.map { it.substringAfterLast(".") }, // Include all methods for context
                            filePath = filePath,
                            lineCount = endLine - startLine,
                            language = "java",
                            startLine = startLine,
                            endLine = endLine
                        ))
                        
                        println("[ReviewOptimizer] Created chunk $chunkNumber: lines $startLine-$endLine")
                        currentLine += CHUNK_SIZE
                        chunkNumber++
                    }
                }
            } else {
                // Fallback if we can't find the file
                reviewUnits.add(ReviewUnit(
                    type = ReviewUnit.ReviewType.METHOD_GROUP,
                    className = className,
                    methods = methods.map { it.substringAfterLast(".") },
                    language = "java"
                ))
            }
        }
        
        return reviewUnits
    }
    
    /**
     * Optimize JS/TS regions into review units
     */
    private fun optimizeJsTsReviewUnits(regionIdentifiers: List<String>): List<ReviewUnit> {
        // Group regions by file - need to handle file paths with colons correctly
        val regionsByFile = regionIdentifiers.groupBy { identifier ->
            // Find the last colon which separates file path from line number
            val lastColonIndex = identifier.lastIndexOf(":")
            if (lastColonIndex > 0) {
                identifier.substring(0, lastColonIndex)
            } else {
                identifier
            }
        }
        
        val reviewUnits = mutableListOf<ReviewUnit>()
        
        regionsByFile.forEach { (filePath, regions) ->
            println("[ReviewOptimizer] Processing file: $filePath with ${regions.size} regions")
            
            // Deduplicate nearby regions
            val lineNumbers = regions.mapNotNull { region ->
                val lastColonIndex = region.lastIndexOf(":")
                if (lastColonIndex > 0) {
                    region.substring(lastColonIndex + 1).toIntOrNull()?.let { line ->
                        line to region
                    }
                } else {
                    null
                }
            }.sortedBy { it.first }
            
            val deduplicatedRegions = mutableListOf<String>()
            var lastLine = -100
            
            lineNumbers.forEach { (line, region) ->
                // Keep regions that are more than 40 lines apart
                if (line - lastLine > 40) {
                    deduplicatedRegions.add(region)
                    lastLine = line
                }
            }
            
            // If no regions after deduplication, use the original list
            val finalRegions = if (deduplicatedRegions.isEmpty()) regions else deduplicatedRegions
            
            val language = when {
                filePath.endsWith(".ts") -> "typescript"
                filePath.endsWith(".js") -> "javascript"
                else -> "javascript"
            }
            
            reviewUnits.add(ReviewUnit(
                type = ReviewUnit.ReviewType.JS_TS_REGION,
                className = filePath, // Use file path as identifier
                methods = finalRegions, // Store deduplicated region identifiers
                filePath = filePath,
                lineCount = 0, // Will be determined when reading file
                language = language
            ))
        }
        
        return reviewUnits
    }
    
    /**
     * Prepares review context based on review unit type
     */
    fun prepareReviewContext(unit: ReviewUnit): ReviewContext {
        return when (unit.type) {
            ReviewUnit.ReviewType.WHOLE_FILE -> prepareWholeFileContext(unit)
            ReviewUnit.ReviewType.PARTIAL_FILE -> preparePartialFileContext(unit)
            ReviewUnit.ReviewType.METHOD_GROUP -> prepareMethodGroupContext(unit)
            ReviewUnit.ReviewType.JS_TS_REGION -> prepareJsTsRegionContext(unit)
        }
    }
    
    private fun prepareWholeFileContext(unit: ReviewUnit): ReviewContext {
        return com.intellij.openapi.application.ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(unit.className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                ?: return@compute ReviewContext.empty(unit.className)
                
            val psiFile = psiClass.containingFile
            
            // Ensure it's a Java file
            if (!isJavaFile(psiFile)) {
                return@compute ReviewContext.empty(unit.className)
            }
            
            ReviewContext(
                className = unit.className,
                fileContent = psiFile.text,
                reviewType = "whole_file",
                lineCount = unit.lineCount,
                methodNames = unit.methods,
                imports = extractImports(psiFile),
                classContext = extractClassContext(psiClass)
            )
        }
    }
    
    private fun preparePartialFileContext(unit: ReviewUnit): ReviewContext {
        return com.intellij.openapi.application.ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(unit.className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                ?: return@compute ReviewContext.empty(unit.className)
                
            val psiFile = psiClass.containingFile
            
            // Ensure it's a Java file
            if (!isJavaFile(psiFile)) {
                return@compute ReviewContext.empty(unit.className)
            }
            
            // Extract the partial content
            val fullText = psiFile.text
            val lines = fullText.lines()
            
            // Get the chunk with bounds checking
            val startIdx = maxOf(0, unit.startLine)
            val endIdx = minOf(lines.size, unit.endLine)
            
            val partialContent = if (startIdx < endIdx) {
                lines.subList(startIdx, endIdx).joinToString("\n")
            } else {
                fullText // Fallback to full text if bounds are invalid
            }
            
            println("[ReviewOptimizer] Extracted partial content for ${unit.className}: lines $startIdx-$endIdx (${partialContent.length} chars)")
            
            ReviewContext(
                className = unit.className,
                fileContent = partialContent,
                reviewType = "partial_file",
                lineCount = endIdx - startIdx,
                methodNames = unit.methods,
                imports = extractImports(psiFile),
                classContext = extractClassContext(psiClass),
                startLine = unit.startLine,
                endLine = unit.endLine
            )
        }
    }
    
    private fun prepareMethodGroupContext(unit: ReviewUnit): ReviewContext {
        return com.intellij.openapi.application.ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(unit.className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                ?: return@compute ReviewContext.empty(unit.className)
                
            val psiFile = psiClass.containingFile
            
            // Ensure it's a Java file
            if (!isJavaFile(psiFile)) {
                return@compute ReviewContext.empty(unit.className)
            }
            
            val methods = mutableListOf<MethodInfo>()
            
            // Extract method details
            unit.methods.forEach { methodName ->
                val psiMethods = psiClass.findMethodsByName(methodName, false)
                psiMethods.forEach { psiMethod ->
                    methods.add(MethodInfo(
                        name = methodName,
                        signature = psiMethod.text.lines().firstOrNull() ?: methodName,
                        body = psiMethod.body?.text ?: "",
                        startLine = getLineNumber(psiFile, psiMethod),
                        annotations = psiMethod.annotations.map { it.text }
                    ))
                }
            }
            
            ReviewContext(
                className = unit.className,
                reviewType = "method_group",
                methods = methods,
                imports = extractImports(psiFile),
                classContext = extractClassContext(psiClass),
                surroundingCode = extractSurroundingCode(psiClass, unit.methods)
            )
        }
    }
    
    /**
     * Prepare context for JS/TS regions
     */
    private fun prepareJsTsRegionContext(unit: ReviewUnit): ReviewContext {
        return com.intellij.openapi.application.ReadAction.compute<ReviewContext, Throwable> {
            val filePath = unit.filePath ?: unit.className // Use className as fallback since it contains the file path
            
            println("[ReviewOptimizer] Preparing JS/TS context for file: $filePath")
            println("[ReviewOptimizer] Regions to process: ${unit.methods}")
            
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(filePath)
            
            if (virtualFile == null) {
                println("[ReviewOptimizer] Virtual file not found for path: $filePath")
                return@compute ReviewContext.empty(unit.className)
            }
                
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) {
                println("[ReviewOptimizer] PSI file not found for virtual file: ${virtualFile.path}")
                return@compute ReviewContext.empty(unit.className)
            }
                
            val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getDocument(psiFile)
            if (document == null) {
                println("[ReviewOptimizer] Document not found for PSI file")
                return@compute ReviewContext.empty(unit.className)
            }
                
            val contextHelper = JsTsContextHelper(project)
            val regionContexts = mutableListOf<JsTsRegionContext>()
            
            // Process each region identifier
            unit.methods.forEach { regionId ->
                println("[ReviewOptimizer] Processing region: $regionId")
                val lastColonIndex = regionId.lastIndexOf(":")
                if (lastColonIndex > 0) {
                    val lineNumber = regionId.substring(lastColonIndex + 1).toIntOrNull()
                    if (lineNumber != null && lineNumber >= 0) {
                        // Line numbers in the region ID are 0-based
                        if (lineNumber < document.lineCount) {
                            val regionContext = contextHelper.extractRegionContext(
                                document,
                                lineNumber,
                                20 // Context lines
                            )
                    
                            regionContexts.add(JsTsRegionContext(
                                regionId = regionId,
                                startLine = regionContext.startLine,
                                endLine = regionContext.endLine,
                                content = regionContext.markedText,
                                framework = regionContext.framework.name
                            ))
                            println("[ReviewOptimizer] Added region context for line $lineNumber")
                        } else {
                            println("[ReviewOptimizer] Line number $lineNumber out of bounds (document has ${document.lineCount} lines)")
                        }
                    } else {
                        println("[ReviewOptimizer] Invalid line number in region ID: $regionId")
                    }
                } else {
                    println("[ReviewOptimizer] Invalid region ID format: $regionId")
                }
            }
            
            println("[ReviewOptimizer] Total region contexts created: ${regionContexts.size}")
            
            // Fix the language detection based on file extension
            val language = when {
                filePath.endsWith(".ts") -> "typescript"
                filePath.endsWith(".js") -> "javascript"  
                else -> "javascript"
            }
            
            ReviewContext(
                className = unit.className,
                reviewType = "js_ts_region",
                language = language, // Use the dynamically detected language, not unit.language
                regionContexts = regionContexts,
                lineCount = if (regionContexts.isNotEmpty()) document.lineCount else 0
            )
        }
    }
    
    private fun extractImports(psiFile: PsiFile): List<String> {
        if (psiFile !is PsiJavaFile) return emptyList()
        return psiFile.importList?.allImportStatements?.map { it.text } ?: emptyList()
    }
    
    private fun isJavaFile(psiFile: PsiFile?): Boolean {
        return psiFile != null && (psiFile is PsiJavaFile || psiFile.name.endsWith(".java"))
    }
    
    private fun extractClassContext(psiClass: com.intellij.psi.PsiClass): ClassContext {
        return ClassContext(
            fields = psiClass.fields.map { "${it.modifierList?.text ?: ""} ${it.type.presentableText} ${it.name}" },
            superClass = psiClass.superClass?.qualifiedName,
            interfaces = psiClass.interfaces.map { it.qualifiedName ?: "" },
            annotations = psiClass.annotations.map { it.text },
            isAbstract = psiClass.isInterface || psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
            constructors = psiClass.constructors.size
        )
    }
    
    private fun extractSurroundingCode(psiClass: com.intellij.psi.PsiClass, methodNames: List<String>): String {
        // Extract other methods that might be relevant (called by or calling the target methods)
        val targetMethods = methodNames.flatMap { name ->
            psiClass.findMethodsByName(name, false).toList()
        }
        
        val relatedMethods = mutableSetOf<PsiMethod>()
        
        targetMethods.forEach { method ->
            // Find methods that call this method
            val callers = findMethodCallers(method, psiClass)
            relatedMethods.addAll(callers)
            
            // Find methods called by this method
            val callees = findMethodCallees(method, psiClass)
            relatedMethods.addAll(callees)
        }
        
        return relatedMethods
            .filter { it.name !in methodNames }
            .take(5) // Limit to 5 related methods
            .joinToString("\n\n") { method ->
                "// Related method: ${method.name}\n${method.text}"
            }
    }
    
    private fun findMethodCallers(method: PsiMethod, withinClass: com.intellij.psi.PsiClass): List<PsiMethod> {
        return withinClass.methods.filter { potentialCaller ->
            potentialCaller.body?.text?.contains(method.name) == true
        }
    }
    
    private fun findMethodCallees(method: PsiMethod, withinClass: com.intellij.psi.PsiClass): List<PsiMethod> {
        val methodBody = method.body?.text ?: return emptyList()
        return withinClass.methods.filter { potentialCallee ->
            methodBody.contains(potentialCallee.name)
        }
    }
    
    private fun getLineNumber(psiFile: PsiFile, element: com.intellij.psi.PsiElement): Int {
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)
        return if (document != null) {
            document.getLineNumber(element.textOffset) + 1
        } else {
            0
        }
    }
    
    data class ReviewContext(
        val className: String,
        val fileContent: String? = null,
        val reviewType: String,
        val lineCount: Int = 0,
        val methodNames: List<String> = emptyList(),
        val methods: List<MethodInfo> = emptyList(),
        val imports: List<String> = emptyList(),
        val classContext: ClassContext? = null,
        val surroundingCode: String? = null,
        val language: String = "java", // Add language field
        val regionContexts: List<JsTsRegionContext> = emptyList(), // For JS/TS regions
        val startLine: Int = 0, // For partial files
        val endLine: Int = 0 // For partial files
    ) {
        companion object {
            fun empty(className: String) = ReviewContext(
                className = className,
                reviewType = "unknown"
            )
        }
        
        fun toPromptContext(): String {
            return when (reviewType) {
                "whole_file" -> buildWholeFilePrompt()
                "partial_file" -> buildPartialFilePrompt()
                "method_group" -> buildMethodGroupPrompt()
                "js_ts_region" -> buildJsTsRegionPrompt()
                else -> "No context available"
            }
        }
        
        private fun buildWholeFilePrompt(): String {
            return """
                |Review Type: Entire File
                |File: $className
                |Size: $lineCount lines
                |Modified methods: ${methodNames.joinToString(", ")}
                |
                |File Content:
                |```java
                |$fileContent
                |```
            """.trimMargin()
        }
        
        private fun buildPartialFilePrompt(): String {
            return """
                |Review Type: Partial File (Chunk)
                |File: $className
                |Lines: $startLine-$endLine (of total file)
                |Chunk size: $lineCount lines
                |Modified methods in file: ${methodNames.joinToString(", ")}
                |
                |Note: This is a partial section of a larger file. Focus on methods visible in this chunk.
                |
                |File Content (lines $startLine-$endLine):
                |```java
                |$fileContent
                |```
            """.trimMargin()
        }
        
        private fun buildMethodGroupPrompt(): String {
            return """
                |Review Type: Method Group
                |Class: $className
                |Methods to review: ${methods.map { it.name }.joinToString(", ")}
                |
                |Class Context:
                |${classContext?.let {
                    """
                    |- Super class: ${it.superClass ?: "None"}
                    |- Interfaces: ${it.interfaces.joinToString(", ")}
                    |- Fields: ${it.fields.size}
                    |- Constructors: ${it.constructors}
                    |- Abstract: ${it.isAbstract}
                    """.trimIndent()
                } ?: "Not available"}
                |
                |Methods to Review:
                |${methods.joinToString("\n\n") { method ->
                    """
                    |Method: ${method.name} (line ${method.startLine})
                    |${method.annotations.joinToString("\n")}
                    |${method.signature}
                    |${method.body}
                    """.trimMargin()
                }}
                |
                |${surroundingCode?.let { 
                    """
                    |Related Methods:
                    |$it
                    """.trimMargin()
                } ?: ""}
            """.trimMargin()
        }
        
        private fun buildJsTsRegionPrompt(): String {
            return """
                |Review Type: JS/TS Code Regions
                |File: $className
                |Language: $language
                |Regions to review: ${regionContexts.size}
                |
                |IMPORTANT: These are PARTIAL views of the file (±20 lines around changes).
                |Don't flag missing imports or undefined variables that might exist elsewhere.
                |
                |${regionContexts.joinToString("\n\n") { region ->
                    """
                    |Region ${region.regionId}:
                    |Lines ${region.startLine + 1} to ${region.endLine + 1}
                    |Framework: ${region.framework}
                    |
                    |```$language
                    |${region.content}
                    |```
                    """.trimMargin()
                }}
            """.trimMargin()
        }
    }
    
    data class MethodInfo(
        val name: String,
        val signature: String,
        val body: String,
        val startLine: Int,
        val annotations: List<String> = emptyList()
    )
    
    data class ClassContext(
        val fields: List<String>,
        val superClass: String?,
        val interfaces: List<String>,
        val annotations: List<String>,
        val isAbstract: Boolean,
        val constructors: Int
    )
    
    data class JsTsRegionContext(
        val regionId: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
        val framework: String
    )
}
