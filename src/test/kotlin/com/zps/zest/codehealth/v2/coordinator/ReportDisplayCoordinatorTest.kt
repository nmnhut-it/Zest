package com.zps.zest.codehealth.v2.coordinator

import com.zps.zest.codehealth.v2.model.*
import com.zps.zest.codehealth.v2.storage.InMemoryReportStorage
import junit.framework.TestCase

/**
 * Unit tests for ReportDisplayCoordinator.
 * These tests verify the bug fix: scheduled reports must be displayed
 * correctly even when stale immediate/git reports exist.
 */
class ReportDisplayCoordinatorTest : TestCase() {

    private lateinit var storage: InMemoryReportStorage
    private lateinit var editorOpener: NoOpEditorOpener
    private lateinit var coordinator: ReportDisplayCoordinator

    override fun setUp() {
        super.setUp()
        storage = InMemoryReportStorage()
        editorOpener = NoOpEditorOpener()
        coordinator = ReportDisplayCoordinator(storage, editorOpener)
    }

    override fun tearDown() {
        storage.clearAll()
        editorOpener.reset()
        super.tearDown()
    }

    // region Bug Fix Tests - THE MAIN SCENARIO

    /**
     * This is the exact bug scenario:
     * 1. Old immediate report exists in storage (stale)
     * 2. Scheduled check runs and finds issues
     * 3. User clicks "View Details" notification
     * 4. Editor should show the NEW scheduled report, not stale data
     */
    fun testBugFix_ScheduledReportOverridesStaleImmediateReport() {
        // Step 1: Simulate stale immediate report from yesterday
        val staleReport = createTestReport(
            triggerType = ReportTriggerType.IMMEDIATE,
            label = "stale_from_yesterday",
            issueCount = 2
        )
        storage.storeCurrentReport(staleReport)

        // Verify stale report is current
        val beforeReport = storage.getMostRecentReport()
        assertEquals("stale_from_yesterday", beforeReport?.label)

        // Step 2: Scheduled check produces new report with 5 issues
        val scheduledReport = createTestReport(
            triggerType = ReportTriggerType.SCHEDULED,
            label = "fresh_scheduled_report",
            issueCount = 5
        )

        // Step 3: User clicks notification - coordinator handles this
        val result = coordinator.prepareAndDisplay(scheduledReport)

        // Step 4: Verify the FRESH report is now current
        assertTrue("Primary store should succeed", result.primaryStoreResult.success)
        assertTrue("Current store should succeed", result.currentStoreResult.success)
        assertTrue("Editor should open", result.editorOpened)

        // THE CRITICAL ASSERTION: getMostRecentReport returns fresh, not stale
        val displayedReport = storage.getMostRecentReport()
        assertNotNull("Should have a report", displayedReport)
        assertEquals("fresh_scheduled_report", displayedReport!!.label)
        assertEquals(5, displayedReport.getTotalIssueCount())
    }

    /**
     * Similar bug scenario with git-triggered stale report.
     */
    fun testBugFix_ScheduledReportOverridesStaleGitReport() {
        // Stale git report from earlier commit
        val staleGitReport = createTestReport(
            triggerType = ReportTriggerType.GIT_COMMIT,
            label = "stale_git_report",
            issueCount = 1
        )
        storage.storeReport(staleGitReport)

        // New scheduled report
        val scheduledReport = createTestReport(
            triggerType = ReportTriggerType.SCHEDULED,
            label = "new_scheduled_report",
            issueCount = 3
        )

        // Display new report
        coordinator.prepareAndDisplay(scheduledReport)

        // Fresh report should be shown
        val displayed = storage.getMostRecentReport()
        assertEquals("new_scheduled_report", displayed?.label)
    }

    /**
     * Verify git reports also work correctly when clicked.
     */
    fun testBugFix_GitReportOverridesStaleData() {
        // Stale scheduled report
        val staleScheduled = createTestReport(
            triggerType = ReportTriggerType.SCHEDULED,
            label = "stale_scheduled",
            issueCount = 2
        )
        storage.storeReport(staleScheduled)

        // New git commit report
        val gitReport = createTestReport(
            triggerType = ReportTriggerType.GIT_COMMIT,
            label = "fresh_git_commit",
            issueCount = 4
        )

        coordinator.prepareAndDisplay(gitReport)

        val displayed = storage.getMostRecentReport()
        assertEquals("fresh_git_commit", displayed?.label)
    }

    // endregion

    // region PrepareAndDisplay Tests

    fun testPrepareAndDisplay_EmptyReport_DoesNotOpenEditor() {
        val emptyReport = HealthReport(
            triggerType = ReportTriggerType.SCHEDULED,
            results = emptyList()
        )

        val result = coordinator.prepareAndDisplay(emptyReport)

        assertFalse("Should not store", result.primaryStoreResult.success)
        assertFalse("Should not open editor", result.editorOpened)
        assertEquals(0, editorOpener.openCount)
    }

    fun testPrepareAndDisplay_ValidReport_OpensEditor() {
        val report = createTestReport(ReportTriggerType.SCHEDULED)

        val result = coordinator.prepareAndDisplay(report)

        assertTrue(result.editorOpened)
        assertEquals(1, editorOpener.openCount)
    }

    fun testPrepareAndDisplay_StoresBothPrimaryAndCurrent() {
        val report = createTestReport(
            triggerType = ReportTriggerType.SCHEDULED,
            label = "dual_store_test"
        )

        val result = coordinator.prepareAndDisplay(report)

        // Should store to both locations
        assertTrue(result.primaryStoreResult.success)
        assertTrue(result.currentStoreResult.success)

        // Daily storage should have it
        assertTrue(storage.hasTodayReport())

        // Current should also have it
        val current = storage.getCurrentReport()
        assertNotNull(current)
        assertEquals("dual_store_test", current!!.label)
    }

    // endregion

    // region LoadReportForDisplay Tests

    fun testLoadReportForDisplay_ReturnsCurrentWhenAvailable() {
        val currentReport = createTestReport(ReportTriggerType.IMMEDIATE, "current")
        storage.storeCurrentReport(currentReport)

        val loaded = coordinator.loadReportForDisplay()

        assertNotNull(loaded)
        assertEquals("current", loaded!!.label)
    }

    fun testLoadReportForDisplay_ReturnsNullWhenEmpty() {
        val loaded = coordinator.loadReportForDisplay()
        assertNull(loaded)
    }

    // endregion

    // region CalculateSummary Tests

    fun testCalculateSummary_CriticalIssues_ReturnsCriticalSeverity() {
        val report = createReportWithIssues(
            listOf(
                createIssue(severity = 5, verified = true), // Critical
                createIssue(severity = 3, verified = true)  // Medium
            )
        )

        val summary = coordinator.calculateSummary(report)

        assertEquals(ReportDisplayCoordinator.SeverityLevel.CRITICAL, summary.severity)
        assertEquals(1, summary.criticalIssues)
        assertEquals(2, summary.totalIssues)
    }

    fun testCalculateSummary_OnlyMediumIssues_ReturnsWarningSeverity() {
        val report = createReportWithIssues(
            listOf(
                createIssue(severity = 3, verified = true),
                createIssue(severity = 2, verified = true)
            )
        )

        val summary = coordinator.calculateSummary(report)

        assertEquals(ReportDisplayCoordinator.SeverityLevel.WARNING, summary.severity)
        assertEquals(0, summary.criticalIssues)
        assertEquals(2, summary.totalIssues)
    }

    fun testCalculateSummary_NoIssues_ReturnsHealthy() {
        val report = HealthReport(
            triggerType = ReportTriggerType.SCHEDULED,
            results = listOf(
                MethodHealthResult(
                    fqn = "clean.Method",
                    healthScore = 100,
                    issues = emptyList()
                )
            )
        )

        val summary = coordinator.calculateSummary(report)

        assertEquals(ReportDisplayCoordinator.SeverityLevel.HEALTHY, summary.severity)
        assertEquals(0, summary.totalIssues)
        assertEquals(100, summary.averageScore)
    }

    fun testCalculateSummary_UnverifiedIssuesNotCounted() {
        val report = createReportWithIssues(
            listOf(
                createIssue(severity = 5, verified = false), // Unverified - not counted
                createIssue(severity = 5, verified = true, falsePositive = true) // False positive - not counted
            )
        )

        val summary = coordinator.calculateSummary(report)

        assertEquals(ReportDisplayCoordinator.SeverityLevel.HEALTHY, summary.severity)
        assertEquals(0, summary.totalIssues)
    }

    // endregion

    // region Helper Methods

    private fun createTestReport(
        triggerType: ReportTriggerType,
        label: String = "test",
        issueCount: Int = 1
    ): HealthReport {
        val issues = (1..issueCount).map { idx ->
            createIssue(severity = 3, verified = true, title = "Issue $idx")
        }

        return HealthReport(
            timestamp = System.currentTimeMillis(),
            triggerType = triggerType,
            results = listOf(
                MethodHealthResult(
                    fqn = "com.example.TestMethod",
                    healthScore = 75,
                    issues = issues
                )
            ),
            label = label
        )
    }

    private fun createReportWithIssues(issues: List<HealthIssue>): HealthReport {
        return HealthReport(
            triggerType = ReportTriggerType.SCHEDULED,
            results = listOf(
                MethodHealthResult(
                    fqn = "com.example.Method",
                    healthScore = 70,
                    issues = issues
                )
            )
        )
    }

    private fun createIssue(
        severity: Int,
        verified: Boolean,
        falsePositive: Boolean = false,
        title: String = "Test Issue"
    ): HealthIssue {
        return HealthIssue(
            issueCategory = "Test",
            severity = severity,
            title = title,
            description = "Test description",
            impact = "Test impact",
            suggestedFix = "Test fix",
            verified = verified,
            falsePositive = falsePositive
        )
    }

    // endregion
}
