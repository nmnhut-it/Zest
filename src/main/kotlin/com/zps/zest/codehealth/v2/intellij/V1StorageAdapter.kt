package com.zps.zest.codehealth.v2.intellij

import com.intellij.openapi.project.Project
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import com.zps.zest.codehealth.v2.model.*
import com.zps.zest.codehealth.v2.storage.ReportStorage
import com.zps.zest.codehealth.v2.storage.StorageKeys
import java.time.LocalDate

/**
 * Adapter that wraps v1 CodeHealthReportStorage with v2 ReportStorage interface.
 * Enables gradual migration while maintaining backward compatibility.
 */
class V1StorageAdapter(private val project: Project) : ReportStorage {

    private val v1Storage: CodeHealthReportStorage
        get() = CodeHealthReportStorage.getInstance(project)

    override fun storeReport(report: HealthReport): StoreResult {
        val v1Results = report.results.map { it.toV1() }

        when (report.triggerType) {
            ReportTriggerType.GIT_COMMIT -> v1Storage.storeGitTriggeredReport(v1Results)
            ReportTriggerType.SCHEDULED, ReportTriggerType.MANUAL -> v1Storage.storeReport(v1Results)
            ReportTriggerType.IMMEDIATE -> v1Storage.saveImmediateReviewResults(report.label, v1Results)
        }

        val key = when (report.triggerType) {
            ReportTriggerType.GIT_COMMIT -> StorageKeys.GIT_TRIGGERED
            ReportTriggerType.SCHEDULED, ReportTriggerType.MANUAL -> StorageKeys.DAILY_PREFIX + LocalDate.now()
            ReportTriggerType.IMMEDIATE -> StorageKeys.CURRENT_REPORT
        }

        return StoreResult(
            success = true,
            storageKey = key,
            timestamp = System.currentTimeMillis(),
            resultCount = report.results.size,
            issueCount = report.getTotalIssueCount()
        )
    }

    override fun storeCurrentReport(report: HealthReport): StoreResult {
        val v1Results = report.results.map { it.toV1() }
        val label = report.label.ifEmpty { "notification_click" }
        v1Storage.saveImmediateReviewResults(label, v1Results)

        return StoreResult(
            success = true,
            storageKey = StorageKeys.CURRENT_REPORT,
            timestamp = System.currentTimeMillis(),
            resultCount = report.results.size,
            issueCount = report.getTotalIssueCount()
        )
    }

    override fun getCurrentReport(): HealthReport? {
        val v1Results = v1Storage.getImmediateReviewResults() ?: return null
        return HealthReport(
            triggerType = ReportTriggerType.IMMEDIATE,
            results = v1Results.map { it.toV2() },
            label = v1Storage.getImmediateReviewFileName() ?: ""
        )
    }

    override fun getMostRecentReport(): HealthReport? {
        // Priority: immediate -> git -> daily (matching v1 logic in editor)
        getCurrentReport()?.let { return it }
        getGitTriggeredReport()?.let { return it }
        return getMostRecentDailyReport()
    }

    override fun getReportForDate(date: LocalDate): HealthReport? {
        val v1Results = v1Storage.getReportForDate(date) ?: return null
        return HealthReport(
            triggerType = ReportTriggerType.SCHEDULED,
            results = v1Results.map { it.toV2() }
        )
    }

    override fun getGitTriggeredReport(): HealthReport? {
        val v1Results = v1Storage.getGitTriggeredReport() ?: return null
        return HealthReport(
            triggerType = ReportTriggerType.GIT_COMMIT,
            results = v1Results.map { it.toV2() }
        )
    }

    override fun hasTodayReport(): Boolean = v1Storage.hasTodayReport()

    override fun clearCurrentReport() {
        // V1 doesn't have explicit clear, store empty
        v1Storage.saveImmediateReviewResults("", emptyList())
    }

    override fun clearAll() {
        // V1 storage auto-cleans old reports, no full clear available
    }

    private fun getMostRecentDailyReport(): HealthReport? {
        val recentDate = v1Storage.getMostRecentReportDate() ?: return null
        return getReportForDate(recentDate)
    }
}

// Extension functions for converting between v1 and v2 models

fun MethodHealthResult.toV1(): CodeHealthAnalyzer.MethodHealthResult {
    return CodeHealthAnalyzer.MethodHealthResult(
        fqn = this.fqn,
        healthScore = this.healthScore,
        modificationCount = this.modificationCount,
        summary = this.summary,
        issues = this.issues.map { it.toV1() },
        impactedCallers = this.impactedCallers,
        codeContext = this.codeContext,
        actualModel = this.actualModel,
        annotatedCode = "",
        originalCode = ""
    )
}

fun HealthIssue.toV1(): CodeHealthAnalyzer.HealthIssue {
    return CodeHealthAnalyzer.HealthIssue(
        issueCategory = this.issueCategory,
        severity = this.severity,
        title = this.title,
        description = this.description,
        impact = this.impact,
        suggestedFix = this.suggestedFix,
        confidence = this.confidence,
        verified = this.verified,
        falsePositive = this.falsePositive,
        verificationReason = this.verificationReason,
        lineNumbers = this.lineNumbers,
        codeSnippet = this.codeSnippet
    )
}

fun CodeHealthAnalyzer.MethodHealthResult.toV2(): MethodHealthResult {
    return MethodHealthResult(
        fqn = this.fqn,
        healthScore = this.healthScore,
        modificationCount = this.modificationCount,
        summary = this.summary,
        issues = this.issues.map { it.toV2() },
        impactedCallers = this.impactedCallers,
        codeContext = this.codeContext,
        actualModel = this.actualModel
    )
}

fun CodeHealthAnalyzer.HealthIssue.toV2(): HealthIssue {
    return HealthIssue(
        issueCategory = this.issueCategory,
        severity = this.severity,
        title = this.title,
        description = this.description,
        impact = this.impact,
        suggestedFix = this.suggestedFix,
        confidence = this.confidence,
        verified = this.verified,
        falsePositive = this.falsePositive,
        verificationReason = this.verificationReason,
        lineNumbers = this.lineNumbers,
        codeSnippet = this.codeSnippet
    )
}
