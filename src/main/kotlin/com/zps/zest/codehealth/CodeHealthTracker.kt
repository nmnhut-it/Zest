package com.zps.zest.codehealth

import com.intellij.openapi.components.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
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
        
        fun getInstance(project: Project): CodeHealthTracker =
            project.getService(CodeHealthTracker::class.java)
    }

    private var state = State()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val methodModifications = ConcurrentHashMap<String, ModifiedMethod>()

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
        
        // Extract method FQN at cursor position
        extractMethodFQN(document, editor.caretModel.offset)?.let { fqn ->
            trackMethodModification(fqn)
        }
    }

    private fun extractMethodFQN(document: Document, offset: Int): String? {
        return PsiDocumentManager.getInstance(project).getPsiFile(document)?.let { psiFile ->
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
    }

    fun checkAndNotify() {
        if (!state.enabled || methodModifications.isEmpty()) return
        
        // Trigger analysis
        val analyzer = CodeHealthAnalyzer.getInstance(project)
        val results = analyzer.analyzeAllMethods(getModifiedMethodDetails())
        
        if (results.isNotEmpty()) {
            // Show notification
            CodeHealthNotification.showHealthReport(project, results)
        }
        
        // Update last check time
        state.lastCheckTime = System.currentTimeMillis()
    }

    private fun scheduleNextCheck() {
        val now = LocalDateTime.now()
        val checkTime = LocalTime.parse(state.checkTime)
        var nextCheck = LocalDateTime.of(LocalDate.now(), checkTime)
        
        // If the time has already passed today, schedule for tomorrow
        if (nextCheck.isBefore(now)) {
            nextCheck = nextCheck.plusDays(1)
        }
        
        val delayMillis = java.time.Duration.between(now, nextCheck).toMillis()
        
        scheduler.schedule(
            {
                checkAndNotify()
                scheduleNextCheck() // Schedule next day's check
            },
            delayMillis,
            TimeUnit.MILLISECONDS
        )
    }

    fun dispose() {
        scheduler.shutdown()
    }
}
