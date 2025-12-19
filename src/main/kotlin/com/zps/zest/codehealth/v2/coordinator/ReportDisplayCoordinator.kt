package com.zps.zest.codehealth.v2.coordinator

import com.zps.zest.codehealth.v2.model.HealthReport
import com.zps.zest.codehealth.v2.model.ReportTriggerType
import com.zps.zest.codehealth.v2.model.StoreResult
import com.zps.zest.codehealth.v2.storage.ReportStorage

/**
 * Coordinates the flow of storing and displaying health reports.
 * Contains the core logic that was buggy in v1 - now testable.
 *
 * The bug in v1: When scheduled reports were stored, the editor would
 * check stale "immediate" or "git-triggered" reports first, showing
 * outdated data. This coordinator ensures the clicked report is always
 * stored as "current" so it's found first.
 */
class ReportDisplayCoordinator(
    private val storage: ReportStorage,
    private val editorOpener: EditorOpener
) {

    /**
     * Result of preparing a report for display.
     */
    data class PrepareResult(
        val primaryStoreResult: StoreResult,
        val currentStoreResult: StoreResult,
        val editorOpened: Boolean
    )

    /**
     * Prepare and display a health report when user clicks notification.
     * This is the main entry point that fixes the v1 bug.
     *
     * Flow:
     * 1. Store report based on trigger type (for historical tracking)
     * 2. ALSO store as current report (ensures editor finds it first)
     * 3. Open the editor
     */
    fun prepareAndDisplay(report: HealthReport): PrepareResult {
        if (report.isEmpty()) {
            return PrepareResult(
                primaryStoreResult = StoreResult(false, "", 0, 0, 0),
                currentStoreResult = StoreResult(false, "", 0, 0, 0),
                editorOpened = false
            )
        }

        // Step 1: Store based on trigger type for historical tracking
        val primaryResult = storage.storeReport(report)

        // Step 2: CRITICAL FIX - Also store as current report
        // This ensures getMostRecentReport() returns THIS report, not stale data
        val currentResult = storage.storeCurrentReport(report)

        // Step 3: Open editor
        val opened = editorOpener.openHealthOverviewEditor()

        return PrepareResult(
            primaryStoreResult = primaryResult,
            currentStoreResult = currentResult,
            editorOpened = opened
        )
    }

    /**
     * Load the report to display in the editor.
     * This is called by the editor when it initializes.
     */
    fun loadReportForDisplay(): HealthReport? {
        return storage.getMostRecentReport()
    }

    /**
     * Get summary statistics for a report.
     * Pure function, easily testable.
     */
    fun calculateSummary(report: HealthReport): ReportSummary {
        val totalIssues = report.getTotalIssueCount()
        val criticalIssues = report.getCriticalIssueCount()
        val averageScore = report.getAverageScore()

        val severity = when {
            criticalIssues > 0 -> SeverityLevel.CRITICAL
            totalIssues > 0 -> SeverityLevel.WARNING
            else -> SeverityLevel.HEALTHY
        }

        return ReportSummary(
            methodCount = report.results.size,
            totalIssues = totalIssues,
            criticalIssues = criticalIssues,
            averageScore = averageScore,
            severity = severity
        )
    }

    /**
     * Summary statistics for a health report.
     */
    data class ReportSummary(
        val methodCount: Int,
        val totalIssues: Int,
        val criticalIssues: Int,
        val averageScore: Int,
        val severity: SeverityLevel
    )

    /**
     * Overall severity level of a report.
     */
    enum class SeverityLevel {
        HEALTHY,
        WARNING,
        CRITICAL
    }
}

/**
 * Interface for opening the health overview editor.
 * Abstracted for testability - IntelliJ UI code is isolated.
 */
interface EditorOpener {
    fun openHealthOverviewEditor(): Boolean
}

/**
 * No-op implementation for testing.
 */
class NoOpEditorOpener : EditorOpener {
    var openCount = 0
        private set

    override fun openHealthOverviewEditor(): Boolean {
        openCount++
        return true
    }

    fun reset() {
        openCount = 0
    }
}
