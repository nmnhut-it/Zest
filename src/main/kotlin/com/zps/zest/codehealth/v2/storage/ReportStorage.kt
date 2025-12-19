package com.zps.zest.codehealth.v2.storage

import com.zps.zest.codehealth.v2.model.HealthReport
import com.zps.zest.codehealth.v2.model.ReportTriggerType
import com.zps.zest.codehealth.v2.model.StoreResult
import java.time.LocalDate

/**
 * Interface for storing and retrieving health reports.
 * Designed for testability - can be mocked or use in-memory implementation.
 */
interface ReportStorage {

    /**
     * Store a report with the given trigger type.
     * Returns result indicating success and storage details.
     */
    fun storeReport(report: HealthReport): StoreResult

    /**
     * Store a report as the "current" report for immediate display.
     * This is the primary report shown when user clicks notification.
     */
    fun storeCurrentReport(report: HealthReport): StoreResult

    /**
     * Get the current report (most recently clicked/displayed).
     * Returns null if no current report exists.
     */
    fun getCurrentReport(): HealthReport?

    /**
     * Get the most recent report of any type.
     * Checks current -> git-triggered -> daily in priority order.
     */
    fun getMostRecentReport(): HealthReport?

    /**
     * Get report for a specific date.
     */
    fun getReportForDate(date: LocalDate): HealthReport?

    /**
     * Get the git-triggered report.
     */
    fun getGitTriggeredReport(): HealthReport?

    /**
     * Check if a report exists for today.
     */
    fun hasTodayReport(): Boolean

    /**
     * Clear the current report (after it's been displayed).
     */
    fun clearCurrentReport()

    /**
     * Clear all stored reports.
     */
    fun clearAll()
}

/**
 * Storage key constants for consistent access.
 */
object StorageKeys {
    const val CURRENT_REPORT = "current_report"
    const val GIT_TRIGGERED = "git_triggered"
    const val DAILY_PREFIX = "daily_"
}
