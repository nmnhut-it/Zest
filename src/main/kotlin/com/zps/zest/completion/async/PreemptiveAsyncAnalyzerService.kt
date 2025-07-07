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
 * Service that preemptively analyzes methods when the cursor enters them
 * to provide instant context when completion is triggered.
 */
@Service(Service.Level.PROJECT)
class PreemptiveAsyncAnalyzerService(private val project: Project) : Disposable {
    
    data class MethodKey(
        val className: String?,
        val methodName: String,
        val methodSignature: String
    )
    
    data class AnalysisCache(
        val timestamp: Long,
        val result: AsyncClassAnalyzer.AnalysisResult,
        val methodKey: MethodKey
    )
    
    private val asyncAnalyzer = AsyncClassAnalyzer(project)
    private val analysisCache = ConcurrentHashMap<MethodKey, AnalysisCache>()
    private val activeAnalysis = ConcurrentHashMap<MethodKey, Boolean>()
    
    // Cache expiry time (5 minutes)
    private val CACHE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5)
    
    // Debounce delay for caret movement (300ms)
    private val ANALYSIS_DELAY_MS = 300L
    
    private var lastAnalysisTime = 0L
    private var pendingAnalysis: MethodKey? = null
    
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
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@runReadAction
                val containingClass = method.containingClass
                
                val methodKey = MethodKey(
                    className = containingClass?.qualifiedName,
                    methodName = method.name,
                    methodSignature = method.getSignature(PsiSubstitutor.EMPTY).toString()
                )
                
                // Check if already analyzed or currently analyzing
                if (analysisCache.containsKey(methodKey) && !isCacheExpired(methodKey)) {
                    println("PreemptiveAnalyzer: Method already analyzed: ${methodKey.methodName}")
                    return@runReadAction
                }
                
                if (activeAnalysis.putIfAbsent(methodKey, true) != null) {
                    println("PreemptiveAnalyzer: Already analyzing method: ${methodKey.methodName}")
                    return@runReadAction
                }
                
                // Start analysis
                println("PreemptiveAnalyzer: Starting analysis for method: ${methodKey.methodName}")
                lastAnalysisTime = currentTime
                
                asyncAnalyzer.analyzeMethodAsync(
                    method,
                    onProgress = { result ->
                        // Update cache with each progress update
                        analysisCache[methodKey] = AnalysisCache(
                            timestamp = System.currentTimeMillis(),
                            result = result,
                            methodKey = methodKey
                        )
                        println("PreemptiveAnalyzer: Progress update for ${methodKey.methodName} - " +
                                "${result.usedClasses.size} classes, ${result.calledMethods.size} methods")
                    },
                    onComplete = {
                        activeAnalysis.remove(methodKey)
                        println("PreemptiveAnalyzer: Completed analysis for method: ${methodKey.methodName}")
                    }
                )
            }
        }
    }
    
    /**
     * Get cached analysis for the method at the given offset
     */
    fun getCachedAnalysis(editor: Editor, offset: Int): AsyncClassAnalyzer.AnalysisResult? {
        return ApplicationManager.getApplication().runReadAction<AsyncClassAnalyzer.AnalysisResult?> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile !is PsiJavaFile) return@runReadAction null
            
            val element = psiFile.findElementAt(offset) ?: return@runReadAction null
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@runReadAction null
            val containingClass = method.containingClass
            
            val methodKey = MethodKey(
                className = containingClass?.qualifiedName,
                methodName = method.name,
                methodSignature = method.getSignature(PsiSubstitutor.EMPTY).toString()
            )
            
            val cached = analysisCache[methodKey]
            if (cached != null && !isCacheExpired(methodKey)) {
                println("PreemptiveAnalyzer: Found cached analysis for ${methodKey.methodName}")
                cached.result
            } else {
                println("PreemptiveAnalyzer: No cached analysis for ${methodKey.methodName}")
                null
            }
        }
    }
    
    /**
     * Force analysis of the current method (useful for manual trigger)
     */
    fun analyzeCurrentMethod(editor: Editor, offset: Int) {
        scheduleAnalysis(editor, offset)
    }
    
    private fun isCacheExpired(methodKey: MethodKey): Boolean {
        val cached = analysisCache[methodKey] ?: return true
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