package com.zps.zest.codehealth.v2.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.v2.coordinator.ReportDisplayCoordinator
import com.zps.zest.codehealth.v2.model.HealthReport
import com.zps.zest.codehealth.v2.model.ReportTriggerType
import com.zps.zest.codehealth.v2.storage.ReportStorage

/**
 * Main service for displaying health reports in IntelliJ.
 * Provides a clean API and wires together v2 components.
 *
 * Usage:
 *   CodeHealthDisplayService.getInstance(project).showReport(results, isGitTriggered)
 */
@Service(Service.Level.PROJECT)
class CodeHealthDisplayService(private val project: Project) {

    private val storage: ReportStorage by lazy { V1StorageAdapter(project) }
    private val editorOpener by lazy { IntelliJEditorOpener(project) }

    /** Coordinator for advanced usage - exposed for testing */
    val coordinator: ReportDisplayCoordinator by lazy { ReportDisplayCoordinator(storage, editorOpener) }

    /**
     * Show a health report from v1 results.
     * Main entry point for notification click handlers.
     */
    fun showReport(
        results: List<CodeHealthAnalyzer.MethodHealthResult>,
        isGitTriggered: Boolean
    ): ReportDisplayCoordinator.PrepareResult {
        val triggerType = if (isGitTriggered) {
            ReportTriggerType.GIT_COMMIT
        } else {
            ReportTriggerType.SCHEDULED
        }

        val report = HealthReport(
            timestamp = System.currentTimeMillis(),
            triggerType = triggerType,
            results = results.map { it.toV2() },
            label = if (isGitTriggered) "git_commit" else "scheduled"
        )

        return coordinator.prepareAndDisplay(report)
    }

    /**
     * Show an immediate review report (user-triggered file review).
     * Stores results and opens editor via V2 coordinator to avoid stale data bug.
     */
    fun showImmediateReport(
        label: String,
        results: List<CodeHealthAnalyzer.MethodHealthResult>
    ): ReportDisplayCoordinator.PrepareResult {
        val report = HealthReport(
            timestamp = System.currentTimeMillis(),
            triggerType = ReportTriggerType.IMMEDIATE,
            results = results.map { it.toV2() },
            label = label
        )

        return coordinator.prepareAndDisplay(report)
    }

    /**
     * Load the current report for display in editor.
     */
    fun loadCurrentReport(): HealthReport? {
        return coordinator.loadReportForDisplay()
    }

    /**
     * Get summary for a report.
     */
    fun getSummary(report: HealthReport): ReportDisplayCoordinator.ReportSummary {
        return coordinator.calculateSummary(report)
    }

    companion object {
        fun getInstance(project: Project): CodeHealthDisplayService =
            project.getService(CodeHealthDisplayService::class.java)
    }
}
