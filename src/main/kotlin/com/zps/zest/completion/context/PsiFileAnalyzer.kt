package com.zps.zest.completion.context

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Utility for analyzing PSI files and extracting cached information
 */
object PsiFileAnalyzer {
    
    /**
     * Analyze a PSI file and extract all cacheable information
     */
    fun analyzePsiFile(psiFile: PsiJavaFile): LeanContextCache.PsiAnalysisCache {
        val methods = mutableListOf<LeanContextCache.MethodInfo>()
        val classes = mutableListOf<LeanContextCache.ClassInfo>()
        val contextTypeRanges = mutableListOf<LeanContextCache.ContextTypeRange>()
        
        // Analyze all classes in the file
        psiFile.classes.forEach { psiClass ->
            analyzeClass(psiClass, classes, methods, contextTypeRanges)
        }
        
        // Add import section range
        val importList = psiFile.importList
        if (importList != null) {
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = importList.textRange.startOffset,
                    endOffset = importList.textRange.endOffset,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.IMPORT_SECTION
                )
            )
        }
        
        // Sort ranges by start offset for efficient lookup
        contextTypeRanges.sortBy { it.startOffset }
        
        return LeanContextCache.PsiAnalysisCache(
            methods = methods,
            classes = classes,
            contextTypeRanges = contextTypeRanges,
            fileLength = psiFile.textLength
        )
    }
    
    /**
     * Analyze a single class and add to collections
     */
    private fun analyzeClass(
        psiClass: PsiClass,
        classes: MutableList<LeanContextCache.ClassInfo>,
        methods: MutableList<LeanContextCache.MethodInfo>,
        contextTypeRanges: MutableList<LeanContextCache.ContextTypeRange>
    ) {
        // Add class info
        classes.add(
            LeanContextCache.ClassInfo(
                name = psiClass.name ?: "Unknown",
                qualifiedName = psiClass.qualifiedName,
                startOffset = psiClass.textRange.startOffset,
                endOffset = psiClass.textRange.endOffset
            )
        )
        
        // Add class declaration range
        val nameIdentifier = psiClass.nameIdentifier
        if (nameIdentifier != null) {
            // Class declaration context extends from class keyword to opening brace
            val classStart = psiClass.textRange.startOffset
            val bodyStart = psiClass.lBrace?.textRange?.startOffset ?: psiClass.textRange.endOffset
            
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = classStart,
                    endOffset = bodyStart,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.CLASS_DECLARATION
                )
            )
        }
        
        // Analyze methods
        psiClass.methods.forEach { method ->
            analyzeMethod(method, methods, contextTypeRanges)
        }
        
        // Analyze fields
        psiClass.fields.forEach { field ->
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = field.textRange.startOffset,
                    endOffset = field.textRange.endOffset,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.FIELD_DECLARATION
                )
            )
        }
        
        // Analyze nested classes
        psiClass.innerClasses.forEach { innerClass ->
            analyzeClass(innerClass, classes, methods, contextTypeRanges)
        }
    }
    
    /**
     * Analyze a single method and add to collections
     */
    private fun analyzeMethod(
        method: PsiMethod,
        methods: MutableList<LeanContextCache.MethodInfo>,
        contextTypeRanges: MutableList<LeanContextCache.ContextTypeRange>
    ) {
        val methodStart = method.textRange.startOffset
        val methodEnd = method.textRange.endOffset
        val body = method.body
        val bodyStart = body?.textRange?.startOffset ?: -1
        
        // Add method info
        methods.add(
            LeanContextCache.MethodInfo(
                name = method.name,
                startOffset = methodStart,
                endOffset = methodEnd,
                signature = try {
                    method.getSignature(PsiSubstitutor.EMPTY).toString()
                } catch (e: Exception) {
                    method.name
                },
                bodyStartOffset = bodyStart
            )
        )
        
        // Add method body context if it exists
        if (body != null) {
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = body.textRange.startOffset,
                    endOffset = body.textRange.endOffset,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.METHOD_BODY
                )
            )
            
            // Analyze method body for variable assignments and other contexts
            analyzeMethodBody(body, contextTypeRanges)
        }
        
        // Method signature range (everything before body)
        val signatureEnd = bodyStart.takeIf { it > 0 } ?: methodEnd
        if (signatureEnd > methodStart) {
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = methodStart,
                    endOffset = signatureEnd,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.CLASS_DECLARATION
                )
            )
        }
    }
    
    /**
     * Analyze method body for specific context types
     */
    private fun analyzeMethodBody(
        body: PsiCodeBlock,
        contextTypeRanges: MutableList<LeanContextCache.ContextTypeRange>
    ) {
        // Find variable assignments
        val assignments = PsiTreeUtil.findChildrenOfType(body, PsiAssignmentExpression::class.java)
        assignments.forEach { assignment ->
            contextTypeRanges.add(
                LeanContextCache.ContextTypeRange(
                    startOffset = assignment.textRange.startOffset,
                    endOffset = assignment.textRange.endOffset,
                    contextType = ZestLeanContextCollectorPSI.CursorContextType.VARIABLE_ASSIGNMENT
                )
            )
        }
        
        // Find positions after opening braces
        val codeBlocks = PsiTreeUtil.findChildrenOfType(body, PsiCodeBlock::class.java)
        codeBlocks.forEach { block ->
            val lBrace = block.lBrace
            if (lBrace != null) {
                val afterBraceOffset = lBrace.textRange.endOffset
                // Create a small range after the opening brace
                contextTypeRanges.add(
                    LeanContextCache.ContextTypeRange(
                        startOffset = afterBraceOffset,
                        endOffset = afterBraceOffset + 1,
                        contextType = ZestLeanContextCollectorPSI.CursorContextType.AFTER_OPENING_BRACE
                    )
                )
            }
        }
    }
}