package com.zps.zest.codehealth.v2.storage

import com.zps.zest.codehealth.v2.model.HealthReport
import com.zps.zest.codehealth.v2.model.ReportTriggerType
import com.zps.zest.codehealth.v2.model.StoreResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ReportStorage.
 * Useful for testing and lightweight scenarios.
 * Thread-safe using ConcurrentHashMap.
 */
class InMemoryReportStorage : ReportStorage {

    private val dailyReports = ConcurrentHashMap<String, HealthReport>()
    private var currentReport: HealthReport? = null
    private var gitTriggeredReport: HealthReport? = null

    @Synchronized
    override fun storeReport(report: HealthReport): StoreResult {
        val timestamp = System.currentTimeMillis()
        val key = when (report.triggerType) {
            ReportTriggerType.GIT_COMMIT -> {
                gitTriggeredReport = report
                StorageKeys.GIT_TRIGGERED
            }
            ReportTriggerType.SCHEDULED, ReportTriggerType.MANUAL -> {
                val dateKey = getDailyKey(report.timestamp)
                dailyReports[dateKey] = report
                dateKey
            }
            ReportTriggerType.IMMEDIATE -> {
                currentReport = report
                StorageKeys.CURRENT_REPORT
            }
        }

        return StoreResult(
            success = true,
            storageKey = key,
            timestamp = timestamp,
            resultCount = report.results.size,
            issueCount = report.getTotalIssueCount()
        )
    }

    @Synchronized
    override fun storeCurrentReport(report: HealthReport): StoreResult {
        currentReport = report
        return StoreResult(
            success = true,
            storageKey = StorageKeys.CURRENT_REPORT,
            timestamp = System.currentTimeMillis(),
            resultCount = report.results.size,
            issueCount = report.getTotalIssueCount()
        )
    }

    @Synchronized
    override fun getCurrentReport(): HealthReport? = currentReport

    @Synchronized
    override fun getMostRecentReport(): HealthReport? {
        // Priority: current -> git-triggered -> most recent daily
        currentReport?.let { return it }
        gitTriggeredReport?.let { return it }
        return getMostRecentDailyReport()
    }

    @Synchronized
    override fun getReportForDate(date: LocalDate): HealthReport? {
        val key = StorageKeys.DAILY_PREFIX + date.toString()
        return dailyReports[key]
    }

    @Synchronized
    override fun getGitTriggeredReport(): HealthReport? = gitTriggeredReport

    @Synchronized
    override fun hasTodayReport(): Boolean {
        val todayKey = getDailyKey(System.currentTimeMillis())
        return dailyReports.containsKey(todayKey)
    }

    @Synchronized
    override fun clearCurrentReport() {
        currentReport = null
    }

    @Synchronized
    override fun clearAll() {
        dailyReports.clear()
        currentReport = null
        gitTriggeredReport = null
    }

    private fun getMostRecentDailyReport(): HealthReport? {
        return dailyReports.values.maxByOrNull { it.timestamp }
    }

    private fun getDailyKey(timestamp: Long): String {
        val date = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return StorageKeys.DAILY_PREFIX + date.toString()
    }

    // Test helper methods
    fun getDailyReportCount(): Int = dailyReports.size
    fun hasGitTriggeredReport(): Boolean = gitTriggeredReport != null
    fun hasCurrentReport(): Boolean = currentReport != null
}
