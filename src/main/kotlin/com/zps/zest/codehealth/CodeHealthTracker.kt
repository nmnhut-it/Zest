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
        this.state = state
        // Load persisted data into memory
        methodModifications.clear()
        state.modifiedMethods.forEach { serializable ->
            val method = serializable.toModifiedMethod()
            methodModifications[method.fqn] = method
        }
    }

    override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
        if (!state.enabled) return
        
        // Queue document processing to avoid blocking EDT
        documentProcessingQueue.submit {
            ReadAction.nonBlocking<String?> {
                extractMethodFQN(document, editor.caretModel.offset)
            }
            .inSmartMode(project)
            .executeSynchronously()
            ?.let { fqn ->
                trackMethodModification(fqn)
            }
        }
    }

    private fun extractMethodFQN(document: Document, offset: Int): String? {
        if (project.isDisposed) return null
        
        return try {
            PsiDocumentManager.getInstance(project).getPsiFile(document)?.let { psiFile ->
                if (!psiFile.isValid) return null
                
                psiFile.findElementAt(offset)?.let { element ->
                    PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.let { method ->
                        val containingClass = method.containingClass
                        if (containingClass != null) {
                            "${containingClass.qualifiedName}.${method.name}"
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun trackMethodModification(fqn: String) {
        // Limit the number of tracked methods
        if (methodModifications.size >= MAX_METHODS_TO_TRACK && !methodModifications.containsKey(fqn)) {
            // Remove oldest entry
            methodModifications.entries
                .minByOrNull { it.value.lastModified }
                ?.let { methodModifications.remove(it.key) }
        }

        methodModifications.compute(fqn) { _, existing ->
            existing?.apply {
                modificationCount++
                lastModified = System.currentTimeMillis()
            } ?: ModifiedMethod(fqn)
        }
        
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

    fun getModifiedMethodDetails(): List<ModifiedMethod> = 
        methodModifications.values.sortedByDescending { it.modificationCount }

    fun clearOldEntries() {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(CLEANUP_AFTER_HOURS.toLong())
        methodModifications.entries.removeIf { it.value.lastModified < cutoffTime }
    }

    private fun cleanupOldEntries() {
        clearOldEntries()
        persistStateAsync()
    }

    fun checkAndNotify() {
        if (!state.enabled || methodModifications.isEmpty()) return
        
        // Prevent concurrent analyses
        if (!isAnalysisRunning.compareAndSet(false, true)) {
            return
        }
        
        // Run analysis in background with progress
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Analyzing Code Health",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    
                    val methods = getModifiedMethodDetails()
                    if (methods.isEmpty()) return
                    
                    // Limit methods to analyze
                    val methodsToAnalyze = methods.take(50)
                    
                    indicator.text = "Analyzing ${methodsToAnalyze.size} modified methods..."
                    
                    // Trigger async analysis
                    val analyzer = CodeHealthAnalyzer.getInstance(project)
                    val results = analyzer.analyzeAllMethodsAsync(methodsToAnalyze, indicator)
                    
                    if (results.isNotEmpty() && !indicator.isCanceled) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                CodeHealthNotification.showHealthReport(project, results)
                            }
                        }
                    }
                    
                    // Update last check time
                    state.lastCheckTime = System.currentTimeMillis()
                    
                } finally {
                    isAnalysisRunning.set(false)
                }
            }
            
            override fun onCancel() {
                isAnalysisRunning.set(false)
            }
        })
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
 * Simple task queue for async processing
 */
class SimpleTaskQueue(private val delayMs: Long = 100L) {
    private val executor = Executors.newSingleThreadExecutor()
    
    fun submit(task: () -> Unit) {
        executor.submit {
            try {
                Thread.sleep(delayMs)
                task()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // Log error but continue processing
            }
        }
    }
    
    fun shutdown() {
        executor.shutdown()
    }
}
