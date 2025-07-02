package com.zps.zest.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.events.ZestDocumentListener
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that tracks modified methods throughout the day.
 * Implements persistent storage and integrates with Zest's document listening system.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CodeHealthTracker",
    storages = [Storage("zest-code-health.xml")]
)
class CodeHealthTracker(private val project: Project) : 
    PersistentStateComponent<CodeHealthTracker.State>,
    ZestDocumentListener {

    companion object {
        const val MAX_METHODS_TO_TRACK = 500
        private const val CLEANUP_AFTER_HOURS = 24
        private const val DEBOUNCE_DELAY_MS = 300L
        
        fun getInstance(project: Project): CodeHealthTracker =
            project.getService(CodeHealthTracker::class.java)
    }

    private var state = State()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val methodModifications = ConcurrentHashMap<String, ModifiedMethod>()
    
    // Async processing
    private val documentProcessingQueue = SimpleTaskQueue(delayMs = DEBOUNCE_DELAY_MS)
    val isAnalysisRunning = AtomicBoolean(false)

    /**
     * Data class representing a modified method
     */
    data class ModifiedMethod(
        val fqn: String,
        var modificationCount: Int = 1,
        var lastModified: Long = System.currentTimeMillis(),
        val callers: MutableSet<String> = mutableSetOf()
    ) {
        fun toSerializable(): SerializableModifiedMethod =
            SerializableModifiedMethod(fqn, modificationCount, lastModified, callers.toList())
    }

    /**
     * Serializable version for persistence
     */
    data class SerializableModifiedMethod(
        val fqn: String = "",
        val modificationCount: Int = 0,
        val lastModified: Long = 0L,
        val callers: List<String> = emptyList()
    ) {
        fun toModifiedMethod(): ModifiedMethod =
            ModifiedMethod(fqn, modificationCount, lastModified, callers.toMutableSet())
    }

    /**
     * Persistent state
     */
    data class State(
        var modifiedMethods: MutableList<SerializableModifiedMethod> = mutableListOf(),
        var lastCheckTime: Long = System.currentTimeMillis(),
        var enabled: Boolean = true,
        var checkTime: String = "13:00" // 1 PM default
    )

    init {
        // Subscribe to document events
        project.messageBus.connect().subscribe(ZestDocumentListener.TOPIC, this)
        
        // Schedule daily check
        scheduleNextCheck()
        
        // Schedule periodic cleanup
        scheduler.scheduleWithFixedDelay(
            ::cleanupOldEntries,
            1, 1, TimeUnit.HOURS
        )
    }

    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        println("[CodeHealthTracker] Loading state with ${state.modifiedMethods.size} persisted methods")
        this.state = state
        // Load persisted data into memory
        methodModifications.clear()
        state.modifiedMethods.forEach { serializable ->
            val method = serializable.toModifiedMethod()
            if (method.fqn.isNotBlank()) {
                println("[CodeHealthTracker] Loaded method: ${method.fqn} (count: ${method.modificationCount})")
                methodModifications[method.fqn] = method
            } else {
                println("[CodeHealthTracker] WARNING: Skipping method with empty FQN")
            }
        }
        println("[CodeHealthTracker] State loaded. Total methods in memory: ${methodModifications.size}")
    }

    override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
        if (!state.enabled) return
        
        println("[CodeHealthTracker] Document changed event received")
        
        // Queue document processing to avoid blocking EDT
        documentProcessingQueue.submit {
            ReadAction.nonBlocking<String?> {
                extractMethodFQN(document, editor.caretModel.offset)
            }
            .inSmartMode(project)
            .executeSynchronously()
            ?.let { fqn ->
                println("[CodeHealthTracker] Extracted FQN: $fqn")
                trackMethodModification(fqn)
            } ?: println("[CodeHealthTracker] No FQN extracted from document change")
        }
    }

    private fun extractMethodFQN(document: Document, offset: Int): String? {
        if (project.isDisposed) {
            println("[CodeHealthTracker] Project is disposed")
            return null
        }
        
        return try {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            if (psiFile == null) {
                println("[CodeHealthTracker] PSI file is null")
                return null
            }
            
            if (!psiFile.isValid) {
                println("[CodeHealthTracker] PSI file is invalid")
                return null
            }
            
            println("[CodeHealthTracker] Looking for method at offset $offset in file ${psiFile.name}")
            
            val element = psiFile.findElementAt(offset)
            if (element == null) {
                println("[CodeHealthTracker] No element found at offset $offset")
                return null
            }
            
            println("[CodeHealthTracker] Found element: ${element.text} (${element.javaClass.simpleName})")
            
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            if (method == null) {
                println("[CodeHealthTracker] No method found at cursor position")
                return null
            }
            
            println("[CodeHealthTracker] Found method: ${method.name}")
            
            val containingClass = method.containingClass
            if (containingClass == null) {
                println("[CodeHealthTracker] Method has no containing class")
                return null
            }
            
            val className = containingClass.qualifiedName
            if (className == null) {
                println("[CodeHealthTracker] Class has no qualified name")
                return null
            }
            
            val fqn = "$className.${method.name}"
            println("[CodeHealthTracker] Extracted FQN: $fqn")
            fqn
        } catch (e: Exception) {
            println("[CodeHealthTracker] Error extracting method FQN: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun trackMethodModification(fqn: String) {
        if (fqn.isBlank()) {
            println("[CodeHealthTracker] WARNING: Attempting to track empty FQN")
            return
        }
        
        println("[CodeHealthTracker] Tracking modification for: $fqn")
        
        // Limit the number of tracked methods
        if (methodModifications.size >= MAX_METHODS_TO_TRACK && !methodModifications.containsKey(fqn)) {
            // Remove oldest entry
            methodModifications.entries
                .minByOrNull { it.value.lastModified }
                ?.let { 
                    println("[CodeHealthTracker] Removing oldest method: ${it.key}")
                    methodModifications.remove(it.key) 
                }
        }

        val now = System.currentTimeMillis()
        methodModifications.compute(fqn) { _, existing ->
            if (existing != null) {
                existing.modificationCount++
                existing.lastModified = now
                println("[CodeHealthTracker] Updated method $fqn: count=${existing.modificationCount}")
                existing
            } else {
                println("[CodeHealthTracker] New method tracked: $fqn")
                ModifiedMethod(fqn)
            }
        }
        
        // Queue for background review after inactivity
        BackgroundHealthReviewer.getInstance(project).updateMethodModificationTime(fqn, now)
        
        println("[CodeHealthTracker] Total tracked methods: ${methodModifications.size}")
        
        // Persist state periodically
        persistStateAsync()
    }
    
    private fun persistStateAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            state.modifiedMethods.clear()
            state.modifiedMethods.addAll(
                methodModifications.values
                    .sortedByDescending { it.modificationCount }
                    .take(MAX_METHODS_TO_TRACK)
                    .map { it.toSerializable() }
            )
        }
    }

    fun getModifiedMethods(): Set<String> = methodModifications.keys.toSet()

    fun getModifiedMethodDetails(): List<ModifiedMethod> {
        val methods = methodModifications.values.sortedByDescending { it.modificationCount }
        println("[CodeHealthTracker] getModifiedMethodDetails() returning ${methods.size} methods:")
        methods.forEach { method ->
            println("[CodeHealthTracker]   - ${method.fqn} (count: ${method.modificationCount}, last: ${method.lastModified})")
        }
        return methods
    }

    fun clearOldEntries() {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(CLEANUP_AFTER_HOURS.toLong())
        methodModifications.entries.removeIf { it.value.lastModified < cutoffTime }
        // Also remove any entries with empty FQN
        methodModifications.entries.removeIf { it.key.isBlank() }
    }
    
    fun clearAllTrackedMethods() {
        println("[CodeHealthTracker] Clearing all tracked methods")
        methodModifications.clear()
        state.modifiedMethods.clear()
        persistStateAsync()
    }
    
    fun addTestMethod() {
        println("[CodeHealthTracker] Adding test method for debugging")
        val testFqn = "com.example.TestClass.testMethod"
        trackMethodModification(testFqn)
    }

    private fun cleanupOldEntries() {
        clearOldEntries()
        persistStateAsync()
    }

    fun checkAndNotify() {
        if (!state.enabled) return
        
        // Prevent concurrent analyses
        if (!isAnalysisRunning.compareAndSet(false, true)) {
            return
        }
        
        println("[CodeHealth] Starting code health analysis...")
        
        // Run analysis in background without progress UI
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reviewer = BackgroundHealthReviewer.getInstance(project)
                val preReviewedMethods = reviewer.getReviewedMethods()
                println("[CodeHealth] Found ${preReviewedMethods.size} pre-reviewed methods")
                
                val methods = getModifiedMethodDetails()
                    .filter { it.fqn.isNotBlank() } // Filter out empty FQNs
                
                if (methods.isEmpty() && preReviewedMethods.isEmpty()) {
                    println("[CodeHealth] No methods to analyze")
                    isAnalysisRunning.set(false)
                    return@executeOnPooledThread
                }
                
                // Separate methods into pre-reviewed and need-review
                val needsReview = methods.filter { !preReviewedMethods.containsKey(it.fqn) }
                println("[CodeHealth] ${needsReview.size} methods need fresh review, ${preReviewedMethods.size} already reviewed")
                
                // Analyze only the methods that haven't been reviewed yet
                val freshResults = if (needsReview.isNotEmpty()) {
                    val analyzer = CodeHealthAnalyzer.getInstance(project)
                    val startTime = System.currentTimeMillis()
                    
                    val results = analyzer.analyzeAllMethodsAsync(needsReview.take(50), null)
                    
                    val analysisTime = System.currentTimeMillis() - startTime
                    println("[CodeHealth] Fresh analysis completed in ${analysisTime}ms. Found ${results.size} results")
                    results
                } else {
                    emptyList()
                }
                
                // Combine pre-reviewed and fresh results
                val allResults = preReviewedMethods.values.toList() + freshResults
                println("[CodeHealth] Total results: ${allResults.size} (${preReviewedMethods.size} cached, ${freshResults.size} fresh)")
                
                if (allResults.isNotEmpty()) {
                    val totalIssues = allResults.sumOf { it.issues.size }
                    val verifiedIssues = allResults.sumOf { result -> 
                        result.issues.count { it.verified && !it.falsePositive } 
                    }
                    println("[CodeHealth] Total issues: $totalIssues, Verified: $verifiedIssues")
                    
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            CodeHealthNotification.showHealthReport(project, allResults)
                        }
                    }
                }
                
                // Clear the background reviewer cache after report
                reviewer.clearReviewedMethods()
                
                // Update last check time
                state.lastCheckTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
                println("[CodeHealth] ERROR during analysis: ${e.message}")
                e.printStackTrace()
            } finally {
                isAnalysisRunning.set(false)
                println("[CodeHealth] Analysis finished")
            }
        }
    }

    private fun scheduleNextCheck() {
        val now = LocalDateTime.now()
        val checkTime = try {
            LocalTime.parse(state.checkTime)
        } catch (e: Exception) {
            LocalTime.of(13, 0)
        }
        
        var nextCheck = LocalDateTime.of(LocalDate.now(), checkTime)
        
        // If the time has already passed today, schedule for tomorrow
        if (nextCheck.isBefore(now)) {
            nextCheck = nextCheck.plusDays(1)
        }
        
        val delayMillis = java.time.Duration.between(now, nextCheck).toMillis()
        
        scheduler.schedule(
            {
                if (!project.isDisposed) {
                    checkAndNotify()
                    scheduleNextCheck() // Schedule next day's check
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS
        )
    }

    fun dispose() {
        documentProcessingQueue.shutdown()
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
}

/**
 * Simple task queue for async processing with proper scheduling
 */
class SimpleTaskQueue(private val delayMs: Long = 100L) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    
    fun submit(task: () -> Unit) {
        scheduler.schedule({
            try {
                task()
            } catch (e: Exception) {
                // Log error but continue processing
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
    
    fun shutdown() {
        scheduler.shutdown()
    }
}
