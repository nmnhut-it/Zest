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
        const val SMALL_FILE_THRESHOLD = 500 // lines
    }
    
    data class ReviewUnit(
        val type: ReviewType,
        val className: String,
        val methods: List<String>,
        val filePath: String? = null,
        val lineCount: Int = 0
    ) {
        enum class ReviewType {
            WHOLE_FILE,      // Review entire small file
            METHOD_GROUP     // Review group of methods from same class
        }
        
        fun getIdentifier(): String {
            return when (type) {
                ReviewType.WHOLE_FILE -> "file:$className"
                ReviewType.METHOD_GROUP -> "class:$className:${methods.sorted().joinToString(",")}"
            }
        }
        
        fun getDescription(): String {
            return when (type) {
                ReviewType.WHOLE_FILE -> "Entire file: $className ($lineCount lines)"
                ReviewType.METHOD_GROUP -> "Class $className: ${methods.size} methods (${methods.joinToString(", ")})"
            }
        }
    }
    
    /**
     * Groups methods by class and identifies small files for whole-file review
     */
    fun optimizeReviewUnits(methodFQNs: List<String>): List<ReviewUnit> {
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
                        lineCount = lineCount
                    ))
                } else {
                    // For large files, group methods
                    reviewUnits.add(ReviewUnit(
                        type = ReviewUnit.ReviewType.METHOD_GROUP,
                        className = className,
                        methods = methods.map { it.substringAfterLast(".") },
                        filePath = filePath
                    ))
                }
            } else {
                // Fallback if we can't find the file
                reviewUnits.add(ReviewUnit(
                    type = ReviewUnit.ReviewType.METHOD_GROUP,
                    className = className,
                    methods = methods.map { it.substringAfterLast(".") }
                ))
            }
        }
        
        return reviewUnits
    }
    
    /**
     * Prepares review context based on review unit type
     */
    fun prepareReviewContext(unit: ReviewUnit): ReviewContext {
        return when (unit.type) {
            ReviewUnit.ReviewType.WHOLE_FILE -> prepareWholeFileContext(unit)
            ReviewUnit.ReviewType.METHOD_GROUP -> prepareMethodGroupContext(unit)
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
        val surroundingCode: String? = null
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
                "method_group" -> buildMethodGroupPrompt()
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
}
