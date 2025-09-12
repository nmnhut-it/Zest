package com.zps.zest.codehealth

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope

/**
 * Optimizes review process by grouping methods and handling small files.
 * Logic and API preserved. Refactored for readability and shorter methods.
 */
class ReviewOptimizer(private val project: Project) {

    companion object {
        const val SMALL_FILE_THRESHOLD = 2000
        const val CHUNK_SIZE = 500
        const val CHUNK_OVERLAP = 50
        private const val TAG = "[ReviewOptimizer]"
    }

    data class ReviewUnit(
        val type: ReviewType,
        val className: String,
        val methods: List<String>,
        val filePath: String? = null,
        val lineCount: Int = 0,
        val language: String = "java",
        val startLine: Int = 0,
        val endLine: Int = 0
    ) {
        enum class ReviewType {
            WHOLE_FILE, PARTIAL_FILE, METHOD_GROUP, JS_TS_REGION
        }

        fun getIdentifier(): String = when (type) {
            ReviewType.WHOLE_FILE -> "file:$className"
            ReviewType.PARTIAL_FILE -> "partial:$className:$startLine-$endLine"
            ReviewType.METHOD_GROUP -> "class:$className:${methods.sorted().joinToString(",")}"
            ReviewType.JS_TS_REGION -> "region:$className"
        }

        fun getDescription(): String = when (type) {
            ReviewType.WHOLE_FILE -> "Entire file: $className ($lineCount lines)"
            ReviewType.PARTIAL_FILE -> "Partial file: $className (lines $startLine-$endLine)"
            ReviewType.METHOD_GROUP -> "Class $className: ${methods.size} methods (${methods.joinToString(", ")})"
            ReviewType.JS_TS_REGION -> "JS/TS Region: $className"
        }
    }

    fun optimizeReviewUnits(methodFQNs: List<String>): List<ReviewUnit> {
        val (javaMethods, jsTsRegions) = splitByLanguage(methodFQNs)
        val units = mutableListOf<ReviewUnit>()
        if (javaMethods.isNotEmpty()) units += optimizeJavaReviewUnits(javaMethods)
        if (jsTsRegions.isNotEmpty()) units += optimizeJsTsReviewUnits(jsTsRegions)
        return units
    }

    private fun splitByLanguage(methodFQNs: List<String>): Pair<List<String>, List<String>> {
        val java = mutableListOf<String>()
        val jsTs = mutableListOf<String>()
        methodFQNs.forEach { if (it.contains(".js:") || it.contains(".ts:")) jsTs += it else java += it }
        return java to jsTs
    }

    private fun optimizeJavaReviewUnits(methodFQNs: List<String>): List<ReviewUnit> {
        val byClass = methodFQNs.groupBy { it.substringBeforeLast(".") }
        val result = mutableListOf<ReviewUnit>()
        byClass.forEach { (className, methods) ->
            val (psiClass, lineCount) = resolvePsiClassInfo(className)
            if (psiClass != null) {
                result += buildJavaUnitsForClass(className, methods, psiClass, lineCount)
            } else {
                result += fallbackMethodGroup(className, methods)
            }
        }
        return result
    }

    private fun resolvePsiClassInfo(className: String): Pair<PsiClass?, Int> {
        return ReadAction.compute<Pair<PsiClass?, Int>, Throwable> {
            val psi = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project))
            val lines = psi?.containingFile?.text?.lines()?.size ?: 0
            psi to lines
        }
    }

    private fun buildJavaUnitsForClass(
        className: String,
        methods: List<String>,
        psiClass: PsiClass,
        lineCount: Int
    ): List<ReviewUnit> {
        val filePath = psiClass.containingFile?.virtualFile?.path
        val methodNames = methods.map { it.substringAfterLast(".") }
        return if (lineCount <= SMALL_FILE_THRESHOLD) {
            listOf(
                ReviewUnit(
                    type = ReviewUnit.ReviewType.WHOLE_FILE,
                    className = className,
                    methods = methodNames,
                    filePath = filePath,
                    lineCount = lineCount,
                    language = "java"
                )
            )
        } else {
            createFileChunks(className, methodNames, filePath, lineCount)
        }
    }

    private fun createFileChunks(
        className: String,
        methodNames: List<String>,
        filePath: String?,
        lineCount: Int
    ): List<ReviewUnit> {
        println("$TAG Large file detected: $className with $lineCount lines. Creating chunks...")
        val chunks = mutableListOf<ReviewUnit>()
        var current = 0
        var index = 1
        while (current < lineCount) {
            val start = if (current == 0) 0 else current - CHUNK_OVERLAP
            val end = minOf(current + CHUNK_SIZE, lineCount)
            chunks += ReviewUnit(
                type = ReviewUnit.ReviewType.PARTIAL_FILE,
                className = className,
                methods = methodNames,
                filePath = filePath,
                lineCount = end - start,
                language = "java",
                startLine = start,
                endLine = end
            )
            println("$TAG Created chunk $index: lines $start-$end")
            current += CHUNK_SIZE
            index++
        }
        return chunks
    }

    private fun fallbackMethodGroup(className: String, methods: List<String>): ReviewUnit {
        return ReviewUnit(
            type = ReviewUnit.ReviewType.METHOD_GROUP,
            className = className,
            methods = methods.map { it.substringAfterLast(".") },
            language = "java"
        )
    }

    private fun optimizeJsTsReviewUnits(regionIdentifiers: List<String>): List<ReviewUnit> {
        val byFile = regionIdentifiers.groupBy { it.substringBeforeLast(":").ifEmpty { it } }
        val result = mutableListOf<ReviewUnit>()
        byFile.forEach { (filePath, regions) ->
            println("$TAG Processing file: $filePath with ${regions.size} regions")
            val finalRegions = deduplicateRegionsByLine(regions)
            result += ReviewUnit(
                type = ReviewUnit.ReviewType.JS_TS_REGION,
                className = filePath,
                methods = finalRegions,
                filePath = filePath,
                language = detectJsTsLanguage(filePath)
            )
        }
        return result
    }

    private fun deduplicateRegionsByLine(regions: List<String>): List<String> {
        val pairs = regions.mapNotNull { id ->
            val idx = id.lastIndexOf(":")
            if (idx > 0) id.substring(idx + 1).toIntOrNull()?.let { it to id } else null
        }.sortedBy { it.first }
        val deduped = mutableListOf<String>()
        var lastLine = -100
        pairs.forEach { (line, id) ->
            if (line - lastLine > 40) {
                deduped += id
                lastLine = line
            }
        }
        return if (deduped.isEmpty()) regions else deduped
    }

    private fun detectJsTsLanguage(filePath: String): String = when {
        filePath.endsWith(".ts") -> "typescript"
        filePath.endsWith(".js") -> "javascript"
        else -> "javascript"
    }

    fun prepareReviewContext(unit: ReviewUnit): ReviewContext = when (unit.type) {
        ReviewUnit.ReviewType.WHOLE_FILE -> prepareWholeFileContext(unit)
        ReviewUnit.ReviewType.PARTIAL_FILE -> preparePartialFileContext(unit)
        ReviewUnit.ReviewType.METHOD_GROUP -> prepareMethodGroupContext(unit)
        ReviewUnit.ReviewType.JS_TS_REGION -> prepareJsTsRegionContext(unit)
    }

    private fun prepareWholeFileContext(unit: ReviewUnit): ReviewContext {
        return ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = findPsiClass(unit.className) ?: return@compute ReviewContext.empty(unit.className)
            val psiFile = psiClass.containingFile
            if (!isJavaFile(psiFile)) return@compute ReviewContext.empty(unit.className)
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
        return ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = findPsiClass(unit.className) ?: return@compute ReviewContext.empty(unit.className)
            val psiFile = psiClass.containingFile
            if (!isJavaFile(psiFile)) return@compute ReviewContext.empty(unit.className)
            val (content, start, end) = extractChunk(psiFile.text, unit.startLine, unit.endLine)
            println("$TAG Extracted partial content for ${unit.className}: lines $start-$end (${content.length} chars)")
            ReviewContext(
                className = unit.className,
                fileContent = content,
                reviewType = "partial_file",
                lineCount = end - start,
                methodNames = unit.methods,
                imports = extractImports(psiFile),
                classContext = extractClassContext(psiClass),
                startLine = unit.startLine,
                endLine = unit.endLine
            )
        }
    }

    private fun prepareMethodGroupContext(unit: ReviewUnit): ReviewContext {
        return ReadAction.compute<ReviewContext, Throwable> {
            val psiClass = findPsiClass(unit.className) ?: return@compute ReviewContext.empty(unit.className)
            val psiFile = psiClass.containingFile
            if (!isJavaFile(psiFile)) return@compute ReviewContext.empty(unit.className)
            val methods = extractMethodInfos(psiClass, unit.methods, psiFile)
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

    private fun prepareJsTsRegionContext(unit: ReviewUnit): ReviewContext {
        return ReadAction.compute<ReviewContext, Throwable> {
            val filePath = unit.filePath ?: unit.className
            println("$TAG Preparing JS/TS context for file: $filePath")
            println("$TAG Regions to process: ${unit.methods}")
            val document = loadDocument(filePath) ?: return@compute ReviewContext.empty(unit.className)
            val helper = JsTsContextHelper(project)
            val regions = buildJsTsRegionContexts(helper, document, unit.methods)
            val language = detectJsTsLanguage(filePath)
            ReviewContext(
                className = unit.className,
                reviewType = "js_ts_region",
                language = language,
                regionContexts = regions,
                lineCount = if (regions.isNotEmpty()) document.lineCount else 0
            )
        }
    }

    private fun findPsiClass(className: String): PsiClass? {
        return JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
    }

    private fun extractChunk(fullText: String, startLine: Int, endLine: Int): Triple<String, Int, Int> {
        val lines = fullText.lines()
        val start = maxOf(0, startLine)
        val end = minOf(lines.size, endLine)
        val content = if (start < end) lines.subList(start, end).joinToString("\n") else fullText
        return Triple(content, start, end)
    }

    private fun extractMethodInfos(
        psiClass: PsiClass,
        methodNames: List<String>,
        psiFile: PsiFile
    ): List<MethodInfo> {
        val infos = mutableListOf<MethodInfo>()
        methodNames.forEach { name ->
            psiClass.findMethodsByName(name, false).forEach { m ->
                infos += MethodInfo(
                    name = name,
                    signature = m.text.lines().firstOrNull() ?: name,
                    body = m.body?.text ?: "",
                    startLine = getLineNumber(psiFile, m),
                    annotations = m.annotations.map { it.text }
                )
            }
        }
        return infos
    }

    private fun loadDocument(filePath: String): com.intellij.openapi.editor.Document? {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (vf == null) {
            println("$TAG Virtual file not found for path: $filePath")
            return null
        }
        val psi = PsiManager.getInstance(project).findFile(vf)
        if (psi == null) {
            println("$TAG PSI file not found for virtual file: ${vf.path}")
            return null
        }
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)
        if (doc == null) println("$TAG Document not found for PSI file")
        return doc
    }

    private fun buildJsTsRegionContexts(
        contextHelper: JsTsContextHelper,
        document: com.intellij.openapi.editor.Document,
        regionIds: List<String>
    ): List<JsTsRegionContext> {
        val regions = mutableListOf<JsTsRegionContext>()
        regionIds.forEach { id -> parseJsTsRegionId(id)?.let { line ->
            if (line in 0 until document.lineCount) {
                val rc = contextHelper.extractRegionContext(document, line, 20)
                regions += JsTsRegionContext(
                    regionId = id,
                    startLine = rc.startLine,
                    endLine = rc.endLine,
                    content = rc.markedText,
                    framework = rc.framework.name
                )
                println("$TAG Added region context for line $line")
            } else {
                println("$TAG Line number $line out of bounds (document has ${document.lineCount} lines)")
            }
        } ?: println("$TAG Invalid region ID format: $id") }
        println("$TAG Total region contexts created: ${regions.size}")
        return regions
    }

    private fun parseJsTsRegionId(regionId: String): Int? {
        val idx = regionId.lastIndexOf(":")
        if (idx <= 0) return null
        return regionId.substring(idx + 1).toIntOrNull()
            ?: run { println("$TAG Invalid line number in region ID: $regionId"); null }
    }

    private fun extractImports(psiFile: PsiFile): List<String> {
        return (psiFile as? PsiJavaFile)?.importList?.allImportStatements?.map { it.text } ?: emptyList()
    }

    private fun isJavaFile(psiFile: PsiFile?): Boolean {
        return psiFile != null && (psiFile is PsiJavaFile || psiFile.name.endsWith(".java"))
    }

    private fun extractClassContext(psiClass: PsiClass): ClassContext {
        return ClassContext(
            fields = psiClass.fields.map { "${it.modifierList?.text ?: ""} ${it.type.presentableText} ${it.name}" },
            superClass = psiClass.superClass?.qualifiedName,
            interfaces = psiClass.interfaces.map { it.qualifiedName ?: "" },
            annotations = psiClass.annotations.map { it.text },
            isAbstract = psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT),
            constructors = psiClass.constructors.size
        )
    }

    private fun extractSurroundingCode(psiClass: PsiClass, methodNames: List<String>): String {
        val targets = findTargetMethods(psiClass, methodNames)
        val related = collectRelatedMethods(psiClass, targets)
        return formatRelatedMethods(related.filter { it.name !in methodNames }.take(5))
    }

    private fun findTargetMethods(psiClass: PsiClass, names: List<String>): List<PsiMethod> {
        return names.flatMap { psiClass.findMethodsByName(it, false).toList() }
    }

    private fun collectRelatedMethods(psiClass: PsiClass, targets: List<PsiMethod>): Set<PsiMethod> {
        val related = mutableSetOf<PsiMethod>()
        targets.forEach { m ->
            related += findMethodCallers(m, psiClass)
            related += findMethodCallees(m, psiClass)
        }
        return related
    }

    private fun formatRelatedMethods(methods: List<PsiMethod>): String {
        return methods.joinToString("\n\n") { m -> "// Related method: ${m.name}\n${m.text}" }
    }

    private fun findMethodCallers(method: PsiMethod, withinClass: PsiClass): List<PsiMethod> {
        return withinClass.methods.filter { it.body?.text?.contains(method.name) == true }
    }

    private fun findMethodCallees(method: PsiMethod, withinClass: PsiClass): List<PsiMethod> {
        val body = method.body?.text ?: return emptyList()
        return withinClass.methods.filter { body.contains(it.name) }
    }

    private fun getLineNumber(psiFile: PsiFile, element: PsiElement): Int {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        return doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
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
        val language: String = "java",
        val regionContexts: List<JsTsRegionContext> = emptyList(),
        val startLine: Int = 0,
        val endLine: Int = 0
    ) {
        companion object {
            fun empty(className: String) = ReviewContext(className = className, reviewType = "unknown")
        }

        fun toPromptContext(): String = when (reviewType) {
            "whole_file" -> buildWholeFilePrompt()
            "partial_file" -> buildPartialFilePrompt()
            "method_group" -> buildMethodGroupPrompt()
            "js_ts_region" -> buildJsTsRegionPrompt()
            else -> "No context available"
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
            val ctx = buildClassContextSection()
            val methodsBlock = buildMethodsSection()
            val related = buildRelatedSection()
            return """
                |Review Type: Method Group
                |Class: $className
                |$ctx
                |
                |Methods to Review:
                |$methodsBlock
                |
                |$related
            """.trimMargin()
        }

        private fun buildJsTsRegionPrompt(): String {
            val blocks = regionContexts.joinToString("\n\n") { buildRegionBlock(it) }
            return """
                |Review Type: JS/TS Code Regions
                |File: $className
                |Language: $language
                |Regions to review: ${regionContexts.size}
                |
                |IMPORTANT: These are PARTIAL views of the file (±20 lines around changes).
                |Don't flag missing imports or undefined variables that might exist elsewhere.
                |
                |$blocks
            """.trimMargin()
        }

        private fun buildClassContextSection(): String {
            return classContext?.let {
                """
                |Class Context:
                |- Super class: ${it.superClass ?: "None"}
                |- Interfaces: ${it.interfaces.joinToString(", ")}
                |- Fields: ${it.fields.size}
                |- Constructors: ${it.constructors}
                |- Abstract: ${it.isAbstract}
                """.trimMargin()
            } ?: "Class Context: Not available"
        }

        private fun buildMethodsSection(): String {
            return methods.joinToString("\n\n") { m ->
                """
                |Method: ${m.name} (line ${m.startLine})
                |${m.annotations.joinToString("\n")}
                |${m.signature}
                |${m.body}
                """.trimMargin()
            }
        }

        private fun buildRelatedSection(): String {
            return surroundingCode?.let {
                """
                |Related Methods:
                |$it
                """.trimMargin()
            } ?: ""
        }

        private fun buildRegionBlock(region: JsTsRegionContext): String {
            return """
                |Region ${region.regionId}:
                |Lines ${region.startLine + 1} to ${region.endLine + 1}
                |Framework: ${region.framework}
                |
                |```$language
                |${region.content}
                |```
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