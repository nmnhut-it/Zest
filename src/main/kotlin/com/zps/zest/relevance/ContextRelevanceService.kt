package com.zps.zest.relevance

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiElement
import com.zps.zest.scoring.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for ranking and filtering code elements by relevance.
 * Central component for intelligent context selection in code completion.
 */
class ContextRelevanceService(
    private val project: Project,
    private val scorer: HybridScorer = HybridScorer.forCodeCompletion(),
    private val cache: RelevanceCache = RelevanceCache()
) {
    
    /**
     * Rank classes by relevance to the query context
     */
    fun rankClasses(
        classes: List<PsiClass>,
        context: QueryContext,
        maxResults: Int = 5
    ): List<RankedClass> {
        if (classes.isEmpty()) return emptyList()
        
        val rankedClasses = classes.mapNotNull { psiClass ->
            try {
                val cacheKey = "${context.query}_${psiClass.qualifiedName}"
                
                // Check cache first
                val cachedScore = cache.get(cacheKey)
                val score = if (cachedScore != null) {
                    cachedScore
                } else {
                    // Calculate score
                    val classContent = extractClassContent(psiClass)
                    val metadata = extractClassMetadata(psiClass, context)
                    val calculatedScore = scorer.calculateScore(context.query, classContent, metadata)
                    
                    // Cache the result
                    cache.put(cacheKey, calculatedScore)
                    calculatedScore
                }
                
                RankedClass(psiClass, score, extractClassSummary(psiClass))
            } catch (e: Exception) {
                null // Skip classes that cause errors
            }
        }
        
        return rankedClasses
            .sortedByDescending { it.score }
            .take(maxResults)
    }
    
    /**
     * Rank methods by relevance
     */
    fun rankMethods(
        methods: List<PsiMethod>,
        context: QueryContext,
        maxResults: Int = 10
    ): List<RankedMethod> {
        if (methods.isEmpty()) return emptyList()
        
        val rankedMethods = methods.mapNotNull { method ->
            try {
                val cacheKey = "${context.query}_${method.containingClass?.qualifiedName}_${method.name}"
                
                val cachedScore = cache.get(cacheKey)
                val score = if (cachedScore != null) {
                    cachedScore
                } else {
                    val methodContent = extractMethodContent(method)
                    val metadata = extractMethodMetadata(method, context)
                    val calculatedScore = scorer.calculateScore(context.query, methodContent, metadata)
                    
                    cache.put(cacheKey, calculatedScore)
                    calculatedScore
                }
                
                RankedMethod(method, score, extractMethodSignature(method))
            } catch (e: Exception) {
                null
            }
        }
        
        return rankedMethods
            .sortedByDescending { it.score }
            .take(maxResults)
    }
    
    /**
     * Rank fields by relevance
     */
    fun rankFields(
        fields: List<PsiField>,
        context: QueryContext,
        maxResults: Int = 10
    ): List<RankedField> {
        if (fields.isEmpty()) return emptyList()
        
        val rankedFields = fields.mapNotNull { field ->
            try {
                val score = calculateFieldRelevance(field, context)
                RankedField(field, score, field.type.presentableText)
            } catch (e: Exception) {
                null
            }
        }
        
        return rankedFields
            .sortedByDescending { it.score }
            .take(maxResults)
    }
    
    /**
     * Filter elements by minimum relevance threshold
     */
    fun <T : RankedElement> filterByRelevance(
        elements: List<T>,
        threshold: Double = 0.3
    ): List<T> {
        return elements.filter { it.score >= threshold }
    }
    
    /**
     * Get optimal context selection within token limit
     */
    fun selectOptimalContext(
        classes: List<PsiClass>,
        methods: List<PsiMethod>,
        fields: List<PsiField>,
        context: QueryContext,
        maxTokens: Int = 3000
    ): OptimalContext {
        // Rank all elements
        val rankedClasses = rankClasses(classes, context)
        val rankedMethods = rankMethods(methods, context)
        val rankedFields = rankFields(fields, context)
        
        // Build optimal context within token limit
        val selectedClasses = mutableListOf<RankedClass>()
        val selectedMethods = mutableListOf<RankedMethod>()
        val selectedFields = mutableListOf<RankedField>()
        
        var currentTokens = 0
        val tokenEstimator = TokenEstimator()
        
        // Priority: High-score classes first, then methods, then fields
        for (cls in rankedClasses) {
            val tokens = tokenEstimator.estimateClassTokens(cls.element)
            if (currentTokens + tokens <= maxTokens * 0.4) { // Reserve 40% for classes
                selectedClasses.add(cls)
                currentTokens += tokens
            }
        }
        
        for (method in rankedMethods) {
            val tokens = tokenEstimator.estimateMethodTokens(method.element)
            if (currentTokens + tokens <= maxTokens * 0.8) { // Up to 80% including methods
                selectedMethods.add(method)
                currentTokens += tokens
            }
        }
        
        for (field in rankedFields) {
            val tokens = tokenEstimator.estimateFieldTokens(field.element)
            if (currentTokens + tokens <= maxTokens) {
                selectedFields.add(field)
                currentTokens += tokens
            }
        }
        
        return OptimalContext(
            classes = selectedClasses,
            methods = selectedMethods,
            fields = selectedFields,
            totalTokens = currentTokens
        )
    }
    
    // Helper methods for content extraction
    
    private fun extractClassContent(psiClass: PsiClass): String {
        return buildString {
            append(psiClass.name ?: "")
            append(" ")
            psiClass.methods.forEach { method ->
                append(method.name)
                append(" ")
            }
            psiClass.fields.forEach { field ->
                append(field.name)
                append(" ")
            }
        }
    }
    
    private fun extractClassMetadata(psiClass: PsiClass, context: QueryContext): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            put("qualifiedName", psiClass.qualifiedName ?: "")
            put("isInterface", psiClass.isInterface)
            put("isEnum", psiClass.isEnum)
            put("methodCount", psiClass.methods.size)
            put("fieldCount", psiClass.fields.size)
            put("filePath", psiClass.containingFile?.virtualFile?.path ?: "")
            put("nodeType", if (psiClass.isInterface) "interface" else "class")
            
            // Distance from cursor
            val distance = calculateDistanceFromCursor(psiClass, context.cursorOffset)
            put("distanceFromCursor", distance)
        }
    }
    
    private fun extractMethodContent(method: PsiMethod): String {
        return buildString {
            append(method.name)
            append(" ")
            method.parameterList.parameters.forEach { param ->
                append(param.type.presentableText)
                append(" ")
                append(param.name)
                append(" ")
            }
            method.returnType?.let {
                append(it.presentableText)
                append(" ")
            }
        }
    }
    
    private fun extractMethodMetadata(method: PsiMethod, context: QueryContext): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            put("methodName", method.name)
            put("className", method.containingClass?.qualifiedName ?: "")
            put("paramCount", method.parameterList.parametersCount)
            put("isConstructor", method.isConstructor)
            put("isStatic", method.hasModifierProperty("static"))
            put("nodeType", "method")
            
            val distance = calculateDistanceFromCursor(method, context.cursorOffset)
            put("distanceFromCursor", distance)
        }
    }
    
    private fun extractMethodSignature(method: PsiMethod): String {
        return buildString {
            append(method.name)
            append("(")
            append(method.parameterList.parameters.joinToString(", ") { 
                "${it.type.presentableText} ${it.name}"
            })
            append(")")
            method.returnType?.let {
                append(": ")
                append(it.presentableText)
            }
        }
    }
    
    private fun extractClassSummary(psiClass: PsiClass): String {
        return buildString {
            if (psiClass.isInterface) append("interface ")
            else if (psiClass.isEnum) append("enum ")
            else append("class ")
            
            append(psiClass.name)
            
            val extendsList = psiClass.extendsList?.referenceElements
            if (!extendsList.isNullOrEmpty()) {
                append(" extends ")
                append(extendsList.joinToString(", ") { it.referenceName ?: "" })
            }
            
            val implementsList = psiClass.implementsList?.referenceElements
            if (!implementsList.isNullOrEmpty()) {
                append(" implements ")
                append(implementsList.joinToString(", ") { it.referenceName ?: "" })
            }
        }
    }
    
    private fun calculateFieldRelevance(field: PsiField, context: QueryContext): Double {
        val fieldName = field.name ?: return 0.0
        val fieldType = field.type.presentableText
        
        val content = "$fieldName $fieldType"
        val metadata = mapOf(
            "fieldName" to fieldName,
            "fieldType" to fieldType,
            "isStatic" to field.hasModifierProperty("static"),
            "isFinal" to field.hasModifierProperty("final")
        )
        
        return scorer.calculateScore(context.query, content, metadata)
    }
    
    private fun calculateDistanceFromCursor(element: PsiElement, cursorOffset: Int): Int {
        val elementOffset = element.textOffset
        return kotlin.math.abs(elementOffset - cursorOffset)
    }
}

/**
 * Query context for relevance calculations
 */
data class QueryContext(
    val query: String,
    val cursorOffset: Int,
    val fileName: String,
    val language: String,
    val contextType: String? = null,
    val intent: QueryIntent = QueryIntent.MIXED
)

/**
 * Base class for ranked elements
 */
abstract class RankedElement(
    open val score: Double,
    open val summary: String
)

/**
 * Ranked class with relevance score
 */
data class RankedClass(
    val element: PsiClass,
    override val score: Double,
    override val summary: String
) : RankedElement(score, summary)

/**
 * Ranked method with relevance score
 */
data class RankedMethod(
    val element: PsiMethod,
    override val score: Double,
    override val summary: String
) : RankedElement(score, summary)

/**
 * Ranked field with relevance score
 */
data class RankedField(
    val element: PsiField,
    override val score: Double,
    override val summary: String
) : RankedElement(score, summary)

/**
 * Optimal context selection result
 */
data class OptimalContext(
    val classes: List<RankedClass>,
    val methods: List<RankedMethod>,
    val fields: List<RankedField>,
    val totalTokens: Int
)

/**
 * Token estimator for context size management
 */
class TokenEstimator {
    fun estimateClassTokens(psiClass: PsiClass): Int {
        // Rough estimation: class name + method signatures + field declarations
        var tokens = 10 // Class declaration overhead
        tokens += psiClass.methods.size * 15 // Average method signature
        tokens += psiClass.fields.size * 5   // Average field declaration
        return tokens
    }
    
    fun estimateMethodTokens(method: PsiMethod): Int {
        // Method signature + some body context
        return 20 + method.parameterList.parametersCount * 3
    }
    
    fun estimateFieldTokens(field: PsiField): Int {
        return 5 // Field declaration
    }
}