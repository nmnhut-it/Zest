package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
 * Service that tracks project changes and modifications throughout the day.
 * Implements persistent storage and integrates with Zest's document listening system.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ProjectChangesTracker",
    storages = [Storage("zest-project-changes.xml")]
)
class ProjectChangesTracker(private val project: Project) : 
    PersistentStateComponent<ProjectChangesTracker.State>,
    ZestDocumentListener {

    companion object {
        const val MAX_METHODS_TO_TRACK = 500
        private const val CLEANUP_AFTER_HOURS = 24
        private const val DEBOUNCE_DELAY_MS = 300L
        
        fun getInstance(project: Project): ProjectChangesTracker =
            project.getService(ProjectChangesTracker::class.java)
            
        // Track tip display frequency
        private var tipShowCounter = 0
        private const val TIP_SHOW_FREQUENCY = 3 // Show tip every 3rd time
    }

    private var state = State()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val methodModifications = ConcurrentHashMap<String, ModifiedMethod>()
    
    // Store last analysis results for status bar widget
    private var lastAnalysisResults: List<CodeHealthAnalyzer.MethodHealthResult>? = null
    
    // Async processing
    private val documentProcessingQueue = SimpleTaskQueue(delayMs = DEBOUNCE_DELAY_MS)
    val isAnalysisRunning = AtomicBoolean(false)
    
    // JS/TS support
    private val jsTsTracker = JsTsHealthTracker(project)

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
//        println("[ProjectChangesTracker] Loading state with ${state.modifiedMethods.size} persisted methods")
        this.state = state
        // Load persisted data into memory
        methodModifications.clear()
        state.modifiedMethods.forEach { serializable ->
            val method = serializable.toModifiedMethod()
            if (method.fqn.isNotBlank()) {
//                println("[ProjectChangesTracker] Loaded method: ${method.fqn} (count: ${method.modificationCount})")
                methodModifications[method.fqn] = method
            } else {
//                println("[ProjectChangesTracker] WARNING: Skipping method with empty FQN")
            }
        }
//        println("[ProjectChangesTracker] State loaded. Total methods in memory: ${methodModifications.size}")
    }

    override fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {
        if (!state.enabled) return
        
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile == null) return
        
        val fileName = psiFile.name
        
        when {
            fileName.endsWith(".java") -> {
//                println("[ProjectChangesTracker] Document changed event received for Java file: $fileName")
                handleJavaDocument(document, editor, event)
            }
            jsTsTracker.shouldHandleFile(fileName) && CodeHealthConfigurable.ENABLE_JS_TS_SUPPORT -> {
//                println("[ProjectChangesTracker] Document changed event received for JS/TS file: $fileName")
                handleJsTsDocument(document, editor, fileName)
            }
            else -> {
                // Skip other file types
                return
            }
        }
    }
    
    private fun handleJavaDocument(document: Document, editor: Editor, event: DocumentEvent) {
        // Queue document processing to avoid blocking EDT
        documentProcessingQueue.submit {
            try {
                // Use computeInReadAction to avoid blocking
                ApplicationManager.getApplication().runReadAction {
                    val fqn = extractMethodFQN(document, editor.caretModel.offset)
                    if (fqn != null) {
//                        println("[CodeHealthTracker] Extracted FQN: $fqn")
                        // Track in background thread
                        ApplicationManager.getApplication().executeOnPooledThread {
                            trackMethodModification(fqn)
                        }
                    } else {
//                        println("[CodeHealthTracker] No FQN extracted from document change")
                    }
                }
            } catch (e: Exception) {
//                println("[ProjectChangesTracker] Error processing document change: ${e.message}")
            }
        }
    }
    
    private fun handleJsTsDocument(document: Document, editor: Editor, fileName: String) {
        // Queue document processing for JS/TS files
        documentProcessingQueue.submit {
            try {
                ApplicationManager.getApplication().runReadAction {
                    jsTsTracker.handleJsTsDocument(document, editor, fileName)
                }
            } catch (e: Exception) {
//                println("[ProjectChangesTracker] Error processing JS/TS document: ${e.message}")
            }
        }
    }

    private fun extractMethodFQN(document: Document, offset: Int): String? {
        if (project.isDisposed) {
//            println("[ProjectChangesTracker] Project is disposed")
            return null
        }
        
        return try {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            if (psiFile == null) {
//                println("[ProjectChangesTracker] PSI file is null")
                return null
            }
            
            // Ensure it's a Java file
            if (!psiFile.name.endsWith(".java")) {
//                println("[ProjectChangesTracker] Not a Java file: ${psiFile.name}")
                return null
            }
            
            if (!psiFile.isValid) {
//                println("[ProjectChangesTracker] PSI file is invalid")
                return null
            }
            
//            println("[ProjectChangesTracker] Looking for method at offset $offset in file ${psiFile.name}")
            
            val element = psiFile.findElementAt(offset)
            if (element == null) {
//                println("[ProjectChangesTracker] No element found at offset $offset")
                return null
            }
            
//            println("[ProjectChangesTracker] Found element: ${element.text} (${element.javaClass.simpleName})")
            
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            if (method == null) {
//                println("[ProjectChangesTracker] No method found at cursor position")
                return null
            }
            
//            println("[ProjectChangesTracker] Found method: ${method.name}")
            
            val containingClass = method.containingClass
            if (containingClass == null) {
//                println("[ProjectChangesTracker] Method has no containing class")
                return null
            }
            
            val className = containingClass.qualifiedName
            if (className == null) {
//                println("[ProjectChangesTracker] Class has no qualified name")
                return null
            }
            
            val fqn = "$className.${method.name}"
//            println("[ProjectChangesTracker] Extracted FQN: $fqn")
            fqn
        } catch (e: Exception) {
//            println("[ProjectChangesTracker] Error extracting method FQN: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun trackMethodModification(fqn: String) {
        if (fqn.isBlank()) {
//            println("[ProjectChangesTracker] WARNING: Attempting to track empty FQN")
            return
        }
        
//        println("[ProjectChangesTracker] Tracking modification for: $fqn")
        
        // Limit the number of tracked methods
        if (methodModifications.size >= MAX_METHODS_TO_TRACK && !methodModifications.containsKey(fqn)) {
            // Remove oldest entry
            methodModifications.entries
                .minByOrNull { it.value.lastModified }
                ?.let { 
//                    println("[CodeHealthTracker] Removing oldest method: ${it.key}")
                    methodModifications.remove(it.key) 
                }
        }

        val now = System.currentTimeMillis()
        methodModifications.compute(fqn) { _, existing ->
            if (existing != null) {
                existing.modificationCount++
                existing.lastModified = now
//                println("[ProjectChangesTracker] Updated method $fqn: count=${existing.modificationCount}")
                existing
            } else {
//                println("[ProjectChangesTracker] New method tracked: $fqn")
                ModifiedMethod(fqn)
            }
        }
        
        // Queue for background review after inactivity
        BackgroundHealthReviewer.getInstance(project).updateMethodModificationTime(fqn, now)
        
//        println("[ProjectChangesTracker] Total tracked methods: ${methodModifications.size}")
        
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
        // Combine Java methods and JS/TS regions
        val javaMethods = methodModifications.values.toList()
        val jsTsRegions = jsTsTracker.getModifiedRegionsAsMethods()
        
        val allMethods: List<ModifiedMethod> = (javaMethods + jsTsRegions).sortedByDescending { it.modificationCount }
        
//        println("[ProjectChangesTracker] getModifiedMethodDetails() returning ${allMethods.size} items:")
//        println("[ProjectChangesTracker]   - Java methods: ${javaMethods.size}")
//        println("[ProjectChangesTracker]   - JS/TS regions: ${jsTsRegions.size}")
        allMethods.forEach { method: ModifiedMethod ->
//            println("[ProjectChangesTracker]   - ${method.fqn} (count: ${method.modificationCount}, last: ${method.lastModified})")
        }
        return allMethods
    }

    fun clearOldEntries() {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(CLEANUP_AFTER_HOURS.toLong())
        methodModifications.entries.removeIf { it.value.lastModified < cutoffTime }
        // Also remove any entries with empty FQN
        methodModifications.entries.removeIf { it.key.isBlank() }
        // Clear old JS/TS regions
        jsTsTracker.clearOldRegions(cutoffTime)
    }
    
    fun clearAllTrackedMethods() {
//        println("[ProjectChangesTracker] Clearing all tracked methods and regions")
        methodModifications.clear()
        jsTsTracker.clearAllRegions()
        state.modifiedMethods.clear()
        persistStateAsync()
    }
    
    fun addTestMethod() {
//        println("[ProjectChangesTracker] Adding test method for debugging")
        val testFqn = "com.example.TestClass.testMethod"
        trackMethodModification(testFqn)
    }

    private fun cleanupOldEntries() {
        clearOldEntries()
        persistStateAsync()
    }

    /**
     * Get the last analysis results for the status bar widget
     */
    fun getLastResults(): List<CodeHealthAnalyzer.MethodHealthResult>? = lastAnalysisResults

    /**
     * Determine if we should show the tip about viewing reports
     */
    private fun shouldShowTip(): Boolean {
        tipShowCounter++
        // Show tip first 3 times, then every 3rd time
        return tipShowCounter <= 3 || tipShowCounter % TIP_SHOW_FREQUENCY == 0
    }
    
    fun checkAndNotify() {
        if (!state.enabled) return
        
        // Get the status bar widget if available
        val statusBarWidget = getStatusBarWidget()
        
        // Prevent concurrent analyses
        if (!isAnalysisRunning.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Zest Code Guardian")
                    .createNotification(
                        "⚡ Zest Guardian Already Working",
                        "🔍 Analysis in progress... Results coming soon!",
                        NotificationType.WARNING
                    )
                    .notify(project)
            }
            return
        }
        
//        println("[CodeHealth] Starting code health analysis...")
        
        // Notify status bar widget that analysis is starting
        statusBarWidget?.notifyAnalysisStarted("Preparing analysis...")
        
        // Show starting notification
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Guardian")
                .createNotification(
                    "🚀 Zest Code Guardian Activated",
                    "🔍 Scanning your code for improvement opportunities...",
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
        
        // Run analysis in background without progress UI
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reviewer = BackgroundHealthReviewer.getInstance(project)
                val preReviewedMethods = reviewer.getReviewedMethods()
//                println("[CodeHealth] Found ${preReviewedMethods.size} pre-reviewed methods")
                
                val methods = getModifiedMethodDetails()
                    .filter { it.fqn.isNotBlank() } // Filter out empty FQNs
                
                if (methods.isEmpty() && preReviewedMethods.isEmpty()) {
//                    println("[CodeHealth] No methods to analyze")
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Zest Code Guardian")
                            .createNotification(
                                "🤔 Zest Guardian: Nothing to Analyze",
                                "💡 Start coding! I'll watch for issues as you work.",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                    }
                    statusBarWidget?.notifyAnalysisComplete(emptyList())
                    isAnalysisRunning.set(false)
                    return@executeOnPooledThread
                }
                
                // Use ReviewOptimizer to group methods
                val optimizer = ReviewOptimizer(project)
                val allMethodFQNs = methods.map { it.fqn }
                val reviewUnits = optimizer.optimizeReviewUnits(allMethodFQNs)
                
//                println("[CodeHealth] Optimized into ${reviewUnits.size} review units:")
                reviewUnits.forEach { unit ->
//                    println("[CodeHealth] - ${unit.getDescription()}")
                }
                
                // Separate pre-reviewed units from new ones
                val preReviewedUnits = mutableListOf<ReviewOptimizer.ReviewUnit>()
                val needsReviewUnits = mutableListOf<ReviewOptimizer.ReviewUnit>()
                
                reviewUnits.forEach { unit ->
                    val unitId = unit.getIdentifier()
                    if (reviewer.hasReviewedUnit(unitId)) {
                        preReviewedUnits.add(unit)
                    } else {
                        needsReviewUnits.add(unit)
                    }
                }
                
//                println("[CodeHealth] ${needsReviewUnits.size} units need review, ${preReviewedUnits.size} already reviewed")
                
                // Update status bar with progress
                statusBarWidget?.notifyAnalysisProgress(0, needsReviewUnits.size)
                
                // Analyze only the units that haven't been reviewed yet
                val freshResults = if (needsReviewUnits.isNotEmpty()) {
                    val analyzer = CodeHealthAnalyzer.getInstance(project)
                    val startTime = System.currentTimeMillis()
                    
                    // Limit units if needed
                    val limitedUnits = needsReviewUnits.take(10) // Limit to 10 review units
                    if (limitedUnits.size < needsReviewUnits.size) {
                        ApplicationManager.getApplication().invokeLater {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Zest Code Guardian")
                                .createNotification(
                                    "⚡ Analysis Optimized",
                                    "🎯 Focusing on ${limitedUnits.size} of ${needsReviewUnits.size} code units for faster results.",
                                    NotificationType.WARNING
                                )
                                .notify(project)
                        }
                    }
                    
                    // Update progress callback
                    var currentUnit = 0
                    val progressCallback: (String) -> Unit = { status ->
                        currentUnit++
                        statusBarWidget?.notifyAnalysisProgress(currentUnit, limitedUnits.size)
                    }
                    
                    val results = analyzer.analyzeReviewUnitsAsync(limitedUnits, optimizer, progressCallback)
                    
                    val analysisTime = System.currentTimeMillis() - startTime
//                    println("[CodeHealth] Fresh analysis completed in ${analysisTime}ms. Found ${results.size} results")
                    results
                } else {
                    emptyList()
                }
                
                // Get pre-reviewed results
                val preReviewedResults = preReviewedUnits.flatMap { unit ->
                    reviewer.getReviewedUnitResults(unit.getIdentifier())
                }
                
                // Combine pre-reviewed and fresh results
                val allResults = preReviewedResults + freshResults
//                println("[CodeHealth] Total results: ${allResults.size} (${preReviewedResults.size} cached, ${freshResults.size} fresh)")
                
                // Store results for status bar widget
                lastAnalysisResults = allResults
                
                // Update status bar widget with results
                statusBarWidget?.notifyAnalysisComplete(allResults)
                
                if (allResults.isNotEmpty()) {
                    val totalIssues = allResults.sumOf { it.issues.size }
                    val verifiedIssues = allResults.sumOf { result -> 
                        result.issues.count { it.verified && !it.falsePositive } 
                    }
//                    println("[CodeHealth] Total issues: $totalIssues, Verified: $verifiedIssues")
                    
                    // Store the report for future viewing
                    CodeHealthReportStorage.getInstance(project).storeReport(allResults)
                    
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            CodeHealthNotification.showHealthReport(project, allResults)
                            
                            // Add tip notification (show occasionally)
                            if (shouldShowTip()) {
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Zest Code Guardian")
                                    .createNotification(
                                        "💡 Tip: View Report Anytime",
                                        "Left-click the Guardian widget in the status bar to view this report again today!",
                                        NotificationType.INFORMATION
                                    )
                                    .notify(project)
                            }
                        }
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Zest Code Guardian")
                            .createNotification(
                                "✨ Zest Guardian: All Clear!",
                                "🏆 Your code is clean - no issues detected!",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                    }
                }
                
                // Clear the background reviewer cache after report
                reviewer.clearReviewedMethods()
                
                // Update last check time
                state.lastCheckTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
//                println("[CodeHealth] ERROR during analysis: ${e.message}")
                e.printStackTrace()
                
                statusBarWidget?.notifyError("Analysis failed: ${e.message}")
                
                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Zest Code Guardian")
                        .createNotification(
                            "😔 Zest Guardian: Analysis Failed",
                            "💔 Something went wrong: ${e.message}. Let's try again!",
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            } finally {
                isAnalysisRunning.set(false)
//                println("[CodeHealth] Analysis finished")
            }
        }
    }
    
    private fun getStatusBarWidget(): com.zps.zest.codehealth.ui.CodeGuardianStatusBarWidget? {
        return try {
            val windowManager = com.intellij.openapi.wm.WindowManager.getInstance()
            val statusBar = windowManager.getStatusBar(project)
            statusBar?.getWidget(com.zps.zest.codehealth.ui.CodeGuardianStatusBarWidget.WIDGET_ID) as? com.zps.zest.codehealth.ui.CodeGuardianStatusBarWidget
        } catch (e: Exception) {
//            println("[CodeHealth] Could not get status bar widget: ${e.message}")
            null
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
        
        // Also dispose of background reviewer
        BackgroundHealthReviewer.getInstance(project).dispose()
        
        // Clear all data
        methodModifications.clear()
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
            if (!documentProcessingQueue.awaitTermination(5, TimeUnit.SECONDS)) {
                documentProcessingQueue.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            documentProcessingQueue.shutdownNow()
            Thread.currentThread().interrupt()
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
//                println("[SimpleTaskQueue] Error executing task: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
    
    fun shutdown() {
        scheduler.shutdown()
    }
    
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        return scheduler.awaitTermination(timeout, unit)
    }
    
    fun shutdownNow() {
        scheduler.shutdownNow()
    }
}
