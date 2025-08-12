package com.zps.zest.completion.async

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.zps.zest.ClassAnalyzer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Async wrapper for ClassAnalyzer that breaks down PSI operations into chunks
 */
class AsyncClassAnalyzer(private val project: Project) {

    data class AnalysisResult(
        val calledMethods: Set<String> = emptySet(),
        val usedClasses: Set<String> = emptySet(),
        val usedFields: Set<String> = emptySet(),
        val relatedClassContents: Map<String, String> = emptyMap()
    )

    private val queue = SimpleTaskQueue(delayMs = 15)

    /**
     * Analyze a method asynchronously, breaking down the work into chunks
     */
    fun analyzeMethodAsync(
        psiMethod: PsiMethod,
        onProgress: (AnalysisResult) -> Unit,
        onComplete: () -> Unit = {}
    ) {
        val calledMethods = mutableSetOf<String>()
        val usedClasses = mutableSetOf<String>()
        val usedFields = mutableSetOf<String>()
        val relatedClassContents = mutableMapOf<String, String>()

        // Step 1: Analyze method signature (must read PSI on EDT or with read action)
        queue.submit {
            ApplicationManager.getApplication().runReadAction {
                analyzeMethodSignature(psiMethod, calledMethods, usedClasses)
            }
            // Invoke callback on EDT
            ApplicationManager.getApplication().invokeLater {
                onProgress(
                    AnalysisResult(
                        calledMethods.toSet(),
                        usedClasses.toSet(),
                        usedFields.toSet(),
                        relatedClassContents.toMap()
                    )
                )
            }
        }

        // Step 2: Analyze method body in chunks
        queue.submit {
            val statements = ApplicationManager.getApplication().runReadAction(Computable<List<PsiStatement>> {
                collectStatements(psiMethod)
            })

            // Process statements in chunks
            statements.chunked(5).forEach { chunk ->
                queue.submit {
                    ApplicationManager.getApplication().runReadAction {
                        chunk.forEach { statement ->
                            analyzeStatement(statement, calledMethods, usedClasses, usedFields)
                        }
                    }
                    // Invoke callback on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onProgress(
                            AnalysisResult(
                                calledMethods.toSet(),
                                usedClasses.toSet(),
                                usedFields.toSet(),
                                relatedClassContents.toMap()
                            )
                        )
                    }
                }
            }
        }

        // Step 3: Collect related classes
        queue.submit {
            val classesToLoad = ApplicationManager.getApplication().runReadAction(Computable<List<String>> {
                usedClasses.toList()
            })

            // Load classes one by one
            classesToLoad.forEach { className ->
                queue.submit {
                    ApplicationManager.getApplication().runReadAction {
                        loadClassContent(className, relatedClassContents)
                    }
                    // Invoke callback on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onProgress(
                            AnalysisResult(
                                calledMethods.toSet(),
                                usedClasses.toSet(),
                                usedFields.toSet(),
                                relatedClassContents.toMap()
                            )
                        )
                    }
                }
            }
        }

        // Final callback on EDT
        queue.submit {
            ApplicationManager.getApplication().invokeLater {
                onComplete()
            }
        }
    }

    /**
     * Analyze dependencies for context collection
     */
    fun analyzeDependenciesForContext(
        containingClass: PsiClass,
        methodAtCursor: PsiMethod?,
        onResult: (Set<String>, Set<String>) -> Unit
    ) {
        val methodsToPreserve = mutableSetOf<String>()
        val fieldsToPreserve = mutableSetOf<String>()

        if (methodAtCursor == null) {
            onResult(emptySet(), emptySet())
            return
        }

        // Quick synchronous scan for immediate results
        ApplicationManager.getApplication().runReadAction {
            val visitor = object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    val method = expression.resolveMethod()
                    if (method != null && method.containingClass == containingClass) {
                        methodsToPreserve.add(method.name)
                    }
                }

                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    val resolved = expression.resolve()
                    if (resolved is PsiField && resolved.containingClass == containingClass) {
                        fieldsToPreserve.add(resolved.name)
                    }
                }
            }

            methodAtCursor.accept(visitor)
        }

        // Return immediate results
        onResult(methodsToPreserve.toSet(), fieldsToPreserve.toSet())

        // Continue with deeper analysis in background if needed
        // (can be extended later)
    }

    private fun analyzeMethodSignature(
        method: PsiMethod,
        calledMethods: MutableSet<String>,
        usedClasses: MutableSet<String>
    ) {
        // Analyze parameter types
        method.parameterList.parameters.forEach { param ->
            val type = param.type
            if (type is PsiClassType) {
                type.resolve()?.let { psiClass ->
                    if (!isJavaLangClass(psiClass)) {
                        val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                        usedClasses.add(className)
//                        println("AsyncClassAnalyzer: Found class in parameter type: $className")
                    }
                }
            }
        }

        // Analyze return type
        method.returnType?.let { returnType ->
            if (returnType is PsiClassType) {
                returnType.resolve()?.let { psiClass ->
                    if (!isJavaLangClass(psiClass)) {
                        val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                        usedClasses.add(className)
//                        println("AsyncClassAnalyzer: Found class in return type: $className")
                    }
                }
            }
        }
    }

    private fun collectStatements(method: PsiMethod): List<PsiStatement> {
        val statements = mutableListOf<PsiStatement>()
        method.body?.accept(object : JavaRecursiveElementVisitor() {
            override fun visitStatement(statement: PsiStatement) {
                statements.add(statement)
                super.visitStatement(statement)
            }
        })
        return statements
    }

    private fun analyzeStatement(
        statement: PsiStatement,
        calledMethods: MutableSet<String>,
        usedClasses: MutableSet<String>,
        usedFields: MutableSet<String>
    ) {
        statement.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                expression.methodExpression.referenceName?.let {
                    calledMethods.add(it)
                }
                
                // Also check the method's return type
                expression.resolveMethod()?.let { method ->
                    method.returnType?.let { returnType ->
                        if (returnType is PsiClassType) {
                            returnType.resolve()?.let { psiClass ->
                                if (!isJavaLangClass(psiClass)) {
                                    val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                                    usedClasses.add(className)
//                                    println("AsyncClassAnalyzer: Found class from method return type: $className")
                                }
                            }
                        }
                    }
                }
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                val resolved = expression.resolve()
                when (resolved) {
                    is PsiField -> {
                        usedFields.add(resolved.name)
                        // Also check field type
                        resolved.type.let { fieldType ->
                            if (fieldType is PsiClassType) {
                                fieldType.resolve()?.let { psiClass ->
                                    if (!isJavaLangClass(psiClass)) {
                                        val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                                        usedClasses.add(className)
//                                        println("AsyncClassAnalyzer: Found class from field type: $className")
                                    }
                                }
                            }
                        }
                    }

                    is PsiClass -> {
                        if (!isJavaLangClass(resolved)) {
                            val className = resolved.qualifiedName ?: resolved.name ?: ""
                            usedClasses.add(className)
//                            println("AsyncClassAnalyzer: Found used class in reference: $className")
                        }
                    }
                }
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                expression.classReference?.resolve()?.let { psiClass ->
                    if (psiClass is PsiClass && !isJavaLangClass(psiClass)) {
                        val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                        usedClasses.add(className)
//                        println("AsyncClassAnalyzer: Found used class in new expression: $className")
                    }
                }
            }
            
            override fun visitLocalVariable(variable: PsiLocalVariable) {
                super.visitLocalVariable(variable)
                // Check variable type
                variable.type.let { varType ->
                    if (varType is PsiClassType) {
                        varType.resolve()?.let { psiClass ->
                            if (!isJavaLangClass(psiClass)) {
                                val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                                usedClasses.add(className)
//                                println("AsyncClassAnalyzer: Found class from local variable type: $className")
                            }
                        }
                    }
                }
            }
            
            override fun visitTypeElement(typeElement: PsiTypeElement) {
                super.visitTypeElement(typeElement)
                // Check any type references
                val type = typeElement.type
                if (type is PsiClassType) {
                    type.resolve()?.let { psiClass ->
                        if (!isJavaLangClass(psiClass)) {
                            val className = psiClass.qualifiedName ?: psiClass.name ?: ""
                            usedClasses.add(className)
//                            println("AsyncClassAnalyzer: Found class from type element: $className")
                        }
                    }
                }
            }
        })
    }

    private fun loadClassContent(className: String, relatedClassContents: MutableMap<String, String>) {
        if (className.isEmpty()) return

        // Try different scopes to find the class
        val scopes = mutableListOf(
            GlobalSearchScope.projectScope(project),
            GlobalSearchScope.allScope(project)
        )
        
        // Add module scope if available
        val moduleManager = ModuleManager.getInstance(project)
        val firstModule = moduleManager.modules.firstOrNull()
        if (firstModule != null) {
            scopes.add(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(firstModule))
        }
        
        var psiClass: PsiClass? = null
        for (scope in scopes) {
            psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
            if (psiClass != null) {
//                println("AsyncClassAnalyzer: Found class $className in scope: $scope")
                break
            }
        }

        if (psiClass != null && !isJavaLangClass(psiClass)) {
            val classStructure = buildString {
                ClassAnalyzer.appendClassStructure(this, psiClass)
            }
            relatedClassContents[className] = classStructure
//            println("AsyncClassAnalyzer: Loaded class content for $className, size: ${classStructure.length}")
        } else {
            println("AsyncClassAnalyzer: Could not find class $className in any scope or it's a Java lang class")
        }
    }

    private fun isJavaLangClass(psiClass: PsiClass): Boolean {
        val qualifiedName = psiClass.qualifiedName ?: return false
        return qualifiedName.startsWith("java.") ||
                qualifiedName.startsWith("javax.") ||
                qualifiedName.startsWith("kotlin.")
    }

    fun shutdown() {
        queue.shutdown()
    }
}
