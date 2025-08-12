package com.zps.zest.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.zps.zest.codehealth.CodeHealthAnalyzer.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Service that reviews methods in the background after they haven't been modified for 1 hour.
 * Results are cached for the final daily report.
 */
@Service(Service.Level.PROJECT)
class BackgroundHealthReviewer(private val project: Project) {
    
    companion object {
        private const val INACTIVITY_THRESHOLD_MS = 60 * 60 * 1000L // 60 minutes (1 hour)
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // Check every 5 minutes instead of 30 seconds
        
        fun getInstance(project: Project): BackgroundHealthReviewer =
            project.getService(BackgroundHealthReviewer::class.java)
    }
    
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val reviewExecutor = Executors.newCachedThreadPool()
    private val isReviewing = AtomicBoolean(false)
    
    // Add debouncing for method updates
    private val updateDebouncer = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val DEBOUNCE_DELAY_MS = 5000L // 5 second debounce
    
    // Cache for reviewed methods and units
    private val reviewedMethods = ConcurrentHashMap<String, MethodHealthResult>()
    private val reviewedUnits = ConcurrentHashMap<String, List<MethodHealthResult>>() // Unit ID -> Results
    private val pendingReviews = ConcurrentHashMap<String, Long>() // FQN -> last modified time
    private val gson = Gson()
    
    init {
        // Schedule periodic check for methods ready to review
        scheduler.scheduleWithFixedDelay(
            ::checkAndReviewInactiveMethods,
            CHECK_INTERVAL_MS / 1000, // Initial delay in seconds
            CHECK_INTERVAL_MS / 1000, // Period in seconds
            TimeUnit.SECONDS
        )
        
        println("[BackgroundHealthReviewer] Initialized with check interval: ${CHECK_INTERVAL_MS}ms")
    }
    
    /**
     * Add a method to the pending review queue
     */
    fun queueMethodForReview(fqn: String, lastModified: Long) {
        if (fqn.isBlank()) return
        
        pendingReviews[fqn] = lastModified
        println("[BackgroundHealthReviewer] Queued method for review: $fqn (last modified: $lastModified)")
    }
    
    /**
     * Update the last modified time for a method (called when method is edited)
     */
    fun updateMethodModificationTime(fqn: String, time: Long) {
        // Cancel any pending update for this method
        updateDebouncer[fqn]?.cancel(false)
        
        // Schedule the update after debounce delay
        val future = scheduler.schedule({
            pendingReviews[fqn] = time
            // Remove from reviewed cache since it's been modified again
            reviewedMethods.remove(fqn)
            updateDebouncer.remove(fqn)
            println("[BackgroundHealthReviewer] Updated modification time for $fqn after debounce")
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS)
        
        updateDebouncer[fqn] = future
    }
    
    /**
     * Check for methods that haven't been modified for 1 hour and review them
     */
    private fun checkAndReviewInactiveMethods() {
        if (project.isDisposed) return
        
        val now = System.currentTimeMillis()
        val methodsToReview = pendingReviews.entries
            .filter { (fqn, lastModified) ->
                val inactive = (now - lastModified) >= INACTIVITY_THRESHOLD_MS
                val notReviewed = !reviewedMethods.containsKey(fqn)
                inactive && notReviewed
            }
            .map { it.key }
        
        if (methodsToReview.isNotEmpty()) {
            println("[BackgroundHealthReviewer] Found ${methodsToReview.size} methods ready for background review")
            methodsToReview.forEach { fqn ->
                // Double-check before queuing
                if (!hasReviewedMethod(fqn)) {
                    reviewMethodInBackground(fqn)
                }
            }
        }
    }
    
    /**
     * Review a single method in the background
     */
    private fun reviewMethodInBackground(fqn: String) {
        // Skip if already reviewed
        if (hasReviewedMethod(fqn)) {
            println("[BackgroundHealthReviewer] Method $fqn already reviewed, skipping")
            return
        }
        
        reviewExecutor.submit {
            try {
                println("[BackgroundHealthReviewer] Starting background review of $fqn")
                
                // Check if this method is part of a small file or group that should be reviewed together
                val optimizer = ReviewOptimizer(project)
                val reviewUnits = optimizer.optimizeReviewUnits(listOf(fqn))
                
                if (reviewUnits.isNotEmpty()) {
                    val unit = reviewUnits.first()
                    val unitId = unit.getIdentifier()
                    
                    // Check if this unit has already been reviewed
                    if (hasReviewedUnit(unitId)) {
                        println("[BackgroundHealthReviewer] Unit $unitId already reviewed, skipping")
                        return@submit
                    }
                    
                    println("[BackgroundHealthReviewer] Reviewing as part of unit: ${unit.getDescription()}")
                    
                    val analyzer = CodeHealthAnalyzer.getInstance(project)
                    val results = analyzer.analyzeReviewUnitsAsync(listOf(unit), optimizer, null)
                    
                    if (results.isNotEmpty()) {
                        // Store results by unit
                        storeReviewedUnit(unitId, results)
                        
                        // Remove all methods in this unit from pending
                        unit.methods.forEach { methodName ->
                            pendingReviews.remove("${unit.className}.$methodName")
                        }
                        
                        println("[BackgroundHealthReviewer] Completed review of unit $unitId: " +
                                "${results.size} method results, " +
                                "${results.sumOf { it.issues.size }} total issues")
                        
                        // Persist to state
                        saveReviewedMethods()
                    }
                } else {
                    // Fallback to individual method review
                    val method = ProjectChangesTracker.ModifiedMethod(
                        fqn = fqn,
                        modificationCount = 1, 
                        lastModified = pendingReviews[fqn] ?: System.currentTimeMillis()
                    )
                    
                    val analyzer = CodeHealthAnalyzer.getInstance(project)
                    val results = analyzer.analyzeAllMethodsAsync(listOf(method), null)
                    
                    if (results.isNotEmpty()) {
                        val result = results.first()
                        reviewedMethods[fqn] = result
                        pendingReviews.remove(fqn)
                        
                        println("[BackgroundHealthReviewer] Completed individual review of $fqn: " +
                                "${result.issues.size} issues found, health score: ${result.healthScore}")
                        
                        saveReviewedMethods()
                    }
                }
            } catch (e: Exception) {
                println("[BackgroundHealthReviewer] Error reviewing $fqn: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Get all reviewed methods (used by the 13h report)
     */
    fun getReviewedMethods(): Map<String, MethodHealthResult> = reviewedMethods.toMap()
    
    /**
     * Check if a review unit has been reviewed
     */
    fun hasReviewedUnit(unitId: String): Boolean {
        return reviewedUnits.containsKey(unitId)
    }
    
    /**
     * Check if a specific method has been reviewed
     */
    fun hasReviewedMethod(fqn: String): Boolean {
        return reviewedMethods.containsKey(fqn)
    }
    
    /**
     * Get results for a reviewed unit
     */
    fun getReviewedUnitResults(unitId: String): List<MethodHealthResult> {
        return reviewedUnits[unitId] ?: emptyList()
    }
    
    /**
     * Store results for a review unit
     */
    fun storeReviewedUnit(unitId: String, results: List<MethodHealthResult>) {
        reviewedUnits[unitId] = results
        // Also store individual methods for compatibility
        results.forEach { result ->
            reviewedMethods[result.fqn] = result
        }
    }
    
    /**
     * Clear all reviewed methods and units (called after 13h report)
     */
    fun clearReviewedMethods() {
        reviewedMethods.clear()
        reviewedUnits.clear()
        pendingReviews.clear()
        saveReviewedMethods()
        println("[BackgroundHealthReviewer] Cleared all reviewed methods and units")
    }
    
    /**
     * Manually trigger review of all pending methods
     */
    fun reviewAllPendingMethods() {
        val methodsToReview = pendingReviews.keys.toList()
        println("[BackgroundHealthReviewer] Manually reviewing ${methodsToReview.size} pending methods")
        
        methodsToReview.forEach { fqn ->
            reviewMethodInBackground(fqn)
        }
    }
    
    /**
     * Trigger background review (called from status bar)
     */
    fun triggerBackgroundReview() {
        println("[BackgroundHealthReviewer] Background review triggered from status bar")
        
        // Check for methods ready to review immediately
        checkAndReviewInactiveMethods()
        
        // Also trigger review of all pending methods
        reviewAllPendingMethods()
    }
    
    /**
     * Trigger final review (called from status bar)
     */
    fun triggerFinalReview() {
        println("[BackgroundHealthReviewer] Final review triggered from status bar")
        
        // Review all pending methods first
        reviewAllPendingMethods()
        
        // Then trigger the code health check
        val tracker = ProjectChangesTracker.getInstance(project)
        tracker.checkAndNotify()
    }
    
    /**
     * Save reviewed methods to persistent storage
     */
    private fun saveReviewedMethods() {
        try {
            val tracker = ProjectChangesTracker.getInstance(project)
            val state = tracker.state
            
            // Serialize reviewed methods
            val serialized = reviewedMethods.mapValues { (_, result) ->
                gson.toJson(result)
            }
            
            // Store in state (you'll need to add this field to State)
            // For now, we'll just keep in memory
            
        } catch (e: Exception) {
            println("[BackgroundHealthReviewer] Error saving reviewed methods: ${e.message}")
        }
    }
    
    /**
     * Load reviewed methods from persistent storage
     */
    fun loadReviewedMethods(serializedData: Map<String, String>) {
        try {
            serializedData.forEach { (fqn, json) ->
                val result = gson.fromJson(json, MethodHealthResult::class.java)
                reviewedMethods[fqn] = result
            }
            println("[BackgroundHealthReviewer] Loaded ${reviewedMethods.size} reviewed methods from storage")
        } catch (e: Exception) {
            println("[BackgroundHealthReviewer] Error loading reviewed methods: ${e.message}")
        }
    }
    
    /**
     * Get statistics about the review queue
     */
    fun getQueueStats(): BackgroundReviewStats {
        val now = System.currentTimeMillis()
        val pendingCount = pendingReviews.size
        val reviewedCount = reviewedMethods.size
        val readyForReview = pendingReviews.count { (_, lastModified) ->
            (now - lastModified) >= INACTIVITY_THRESHOLD_MS
        }
        
        return BackgroundReviewStats(
            pendingCount = pendingCount,
            reviewedCount = reviewedCount,
            readyForReviewCount = readyForReview,
            totalIssuesFound = reviewedMethods.values.sumOf { it.issues.size }
        )
    }
    
    data class BackgroundReviewStats(
        val pendingCount: Int,
        val reviewedCount: Int,
        val readyForReviewCount: Int,
        val totalIssuesFound: Int
    )
    
    fun dispose() {
        // Cancel all pending debounced updates
        updateDebouncer.values.forEach { it.cancel(false) }
        updateDebouncer.clear()
        
        scheduler.shutdown()
        reviewExecutor.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
            reviewExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            reviewExecutor.shutdownNow()
        }
    }
}
