package com.zps.zest.completion.async

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service that preemptively analyzes classes when the cursor enters them
 * to provide instant context when completion is triggered.
 */
@Service(Service.Level.PROJECT)
class PreemptiveAsyncAnalyzerService(private val project: Project) : Disposable {
    
    data class ClassKey(
        val className: String
    )
    
    data class AnalysisCache(
        var timestamp: Long,
        var result: AsyncClassAnalyzer.AnalysisResult,
        val classKey: ClassKey,
        val analyzedMethods: MutableSet<String> = mutableSetOf()
    )
    
    private val asyncAnalyzer = AsyncClassAnalyzer(project)
    private val analysisCache = ConcurrentHashMap<ClassKey, AnalysisCache>()
    private val activeAnalysis = ConcurrentHashMap<ClassKey, Boolean>()
    
    // Cache expiry time (5 minutes)
    private val CACHE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5)
    
    // Debounce delay for caret movement (300ms)
    private val ANALYSIS_DELAY_MS = 300L
    
    private var lastAnalysisTime = 0L
    private var pendingAnalysis: ClassKey? = null
    
    init {
        setupListeners()
        // Clean up old cache entries periodically
        ApplicationManager.getApplication().executeOnPooledThread {
            while (!project.isDisposed) {
                cleanupCache()
                Thread.sleep(60000) // Check every minute
            }
        }
    }
    
    private fun setupListeners() {
        // Listen to file editor changes
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val editor = event.newEditor?.let { FileEditorManager.getInstance(project).selectedTextEditor }
                editor?.let { setupCaretListener(it) }
            }
        })
        
        // Setup listener for current editor
        FileEditorManager.getInstance(project).selectedTextEditor?.let { 
            setupCaretListener(it)
        }
    }
    
    private fun setupCaretListener(editor: Editor) {
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                scheduleAnalysis(editor, event.caret?.offset ?: return)
            }
        }, this)
    }
    
    private fun scheduleAnalysis(editor: Editor, offset: Int) {
        val currentTime = System.currentTimeMillis()
        
        // Debounce rapid caret movements
        if (currentTime - lastAnalysisTime < ANALYSIS_DELAY_MS) {
            return
        }
        
        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (psiFile !is PsiJavaFile) return@runReadAction
                
                val element = psiFile.findElementAt(offset) ?: return@runReadAction
                val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return@runReadAction
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                
                val classKey = ClassKey(
                    className = containingClass.qualifiedName ?: containingClass.name ?: "Unknown"
                )
                
                // Check if class is already analyzed or currently analyzing
                val cachedAnalysis = analysisCache[classKey]
                if (cachedAnalysis != null && !isCacheExpired(classKey)) {
                    // If we have a method, check if it's already been analyzed
                    if (method != null) {
                        val methodSignature = method.getSignature(PsiSubstitutor.EMPTY).toString()
                        if (cachedAnalysis.analyzedMethods.contains(methodSignature)) {
                            println("PreemptiveAnalyzer: Class ${classKey.className} and method ${method.name} already analyzed")
                            return@runReadAction
                        }
                        // Add new method to analysis
                        println("PreemptiveAnalyzer: Adding method ${method.name} to existing class analysis")
                        analyzeAdditionalMethod(classKey, method, cachedAnalysis)
                    } else {
                        println("PreemptiveAnalyzer: Class ${classKey.className} already analyzed")
                    }
                    return@runReadAction
                }
                
                if (activeAnalysis.putIfAbsent(classKey, true) != null) {
                    println("PreemptiveAnalyzer: Already analyzing class: ${classKey.className}")
                    return@runReadAction
                }
                
                // Start analysis for the whole class
                println("PreemptiveAnalyzer: Starting analysis for class: ${classKey.className}")
                lastAnalysisTime = currentTime
                
                // Create initial cache entry
                val cache = AnalysisCache(
                    timestamp = System.currentTimeMillis(),
                    result = AsyncClassAnalyzer.AnalysisResult(),
                    classKey = classKey
                )
                analysisCache[classKey] = cache
                
                // Analyze all methods in the class
                val methods = containingClass.methods.toList()
                println("PreemptiveAnalyzer: Found ${methods.size} methods in class ${classKey.className}")
                
                // Keep track of all results to merge
                val allCalledMethods = mutableSetOf<String>()
                val allUsedClasses = mutableSetOf<String>()
                val allRelatedClassContents = mutableMapOf<String, String>()
                
                // Analyze each method in the class
                methods.forEach { classMethod ->
                    asyncAnalyzer.analyzeMethodAsync(
                        classMethod,
                        onProgress = { result ->
                            // Merge results into class-level collections
                            synchronized(cache) {
                                allCalledMethods.addAll(result.calledMethods)
                                allUsedClasses.addAll(result.usedClasses)
                                allRelatedClassContents.putAll(result.relatedClassContents)
                                cache.analyzedMethods.add(classMethod.getSignature(PsiSubstitutor.EMPTY).toString())
                                
                                // Update cache with merged results
                                cache.result = AsyncClassAnalyzer.AnalysisResult(
                                    calledMethods = allCalledMethods.toSet(),
                                    usedClasses = allUsedClasses.toSet(),
                                    relatedClassContents = allRelatedClassContents.toMap()
                                )
                                cache.timestamp = System.currentTimeMillis()
                            }
                            println("PreemptiveAnalyzer: Progress update for ${classKey.className}.${classMethod.name} - " +
                                    "${result.usedClasses.size} classes, ${result.calledMethods.size} methods")
                        },
                        onComplete = {
                            println("PreemptiveAnalyzer: Completed analysis for method ${classMethod.name} in class ${classKey.className}")
                        }
                    )
                }
                
                activeAnalysis.remove(classKey)
                println("PreemptiveAnalyzer: Completed scheduling analysis for class: ${classKey.className}")
            }
        }
    }
    
    /**
     * Analyze additional method in an already cached class
     */
    private fun analyzeAdditionalMethod(classKey: ClassKey, method: PsiMethod, cachedAnalysis: AnalysisCache) {
        val methodSignature = method.getSignature(PsiSubstitutor.EMPTY).toString()
        
        asyncAnalyzer.analyzeMethodAsync(
            method,
            onProgress = { result ->
                // Merge results into existing class-level cache
                synchronized(cachedAnalysis) {
                    // Create new merged result
                    val mergedResult = AsyncClassAnalyzer.AnalysisResult(
                        calledMethods = cachedAnalysis.result.calledMethods + result.calledMethods,
                        usedClasses = cachedAnalysis.result.usedClasses + result.usedClasses,
                        relatedClassContents = cachedAnalysis.result.relatedClassContents + result.relatedClassContents
                    )
                    
                    cachedAnalysis.result = mergedResult
                    cachedAnalysis.analyzedMethods.add(methodSignature)
                    cachedAnalysis.timestamp = System.currentTimeMillis()
                }
                println("PreemptiveAnalyzer: Added analysis for ${method.name} to class ${classKey.className}")
            },
            onComplete = {
                println("PreemptiveAnalyzer: Completed additional method analysis for ${method.name}")
            }
        )
    }
    
    /**
     * Get cached analysis for the class at the given offset
     */
    fun getCachedAnalysis(editor: Editor, offset: Int): AsyncClassAnalyzer.AnalysisResult? {
        return ApplicationManager.getApplication().runReadAction<AsyncClassAnalyzer.AnalysisResult?> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile !is PsiJavaFile) return@runReadAction null
            
            val element = psiFile.findElementAt(offset) ?: return@runReadAction null
            val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return@runReadAction null
            
            val classKey = ClassKey(
                className = containingClass.qualifiedName ?: containingClass.name ?: "Unknown"
            )
            
            val cached = analysisCache[classKey]
            if (cached != null && !isCacheExpired(classKey)) {
                println("PreemptiveAnalyzer: Found cached analysis for class ${classKey.className}")
                cached.result
            } else {
                println("PreemptiveAnalyzer: No cached analysis for class ${classKey.className}")
                null
            }
        }
    }
    
    /**
     * Force analysis of the current class (useful for manual trigger)
     */
    fun analyzeCurrentClass(editor: Editor, offset: Int) {
        scheduleAnalysis(editor, offset)
    }
    
    private fun isCacheExpired(classKey: ClassKey): Boolean {
        val cached = analysisCache[classKey] ?: return true
        return System.currentTimeMillis() - cached.timestamp > CACHE_EXPIRY_MS
    }
    
    private fun cleanupCache() {
        val now = System.currentTimeMillis()
        val keysToRemove = analysisCache.entries
            .filter { now - it.value.timestamp > CACHE_EXPIRY_MS }
            .map { it.key }
        
        keysToRemove.forEach { analysisCache.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            println("PreemptiveAnalyzer: Cleaned up ${keysToRemove.size} expired cache entries")
        }
    }
    
    fun clearCache() {
        analysisCache.clear()
        activeAnalysis.clear()
    }
    
    override fun dispose() {
        asyncAnalyzer.shutdown()
        clearCache()
    }
}