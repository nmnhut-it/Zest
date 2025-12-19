package com.zps.zest.codehealth.v2.storage

import com.zps.zest.codehealth.v2.model.*
import junit.framework.TestCase
import java.time.LocalDate

/**
 * Unit tests for InMemoryReportStorage.
 * Tests the core storage logic without any IntelliJ dependencies.
 */
class InMemoryReportStorageTest : TestCase() {

    private lateinit var storage: InMemoryReportStorage

    override fun setUp() {
        super.setUp()
        storage = InMemoryReportStorage()
    }

    override fun tearDown() {
        storage.clearAll()
        super.tearDown()
    }

    // region Store Report Tests

    fun testStoreScheduledReport_StoresInDailyReports() {
        val report = createTestReport(ReportTriggerType.SCHEDULED)

        val result = storage.storeReport(report)

        assertTrue("Store should succeed", result.success)
        assertTrue("Key should be daily key", result.storageKey.startsWith(StorageKeys.DAILY_PREFIX))
        assertEquals(1, storage.getDailyReportCount())
    }

    fun testStoreGitTriggeredReport_StoresAsGitTriggered() {
        val report = createTestReport(ReportTriggerType.GIT_COMMIT)

        val result = storage.storeReport(report)

        assertTrue("Store should succeed", result.success)
        assertEquals(StorageKeys.GIT_TRIGGERED, result.storageKey)
        assertTrue(storage.hasGitTriggeredReport())
    }

    fun testStoreImmediateReport_StoresAsCurrent() {
        val report = createTestReport(ReportTriggerType.IMMEDIATE)

        val result = storage.storeReport(report)

        assertTrue("Store should succeed", result.success)
        assertEquals(StorageKeys.CURRENT_REPORT, result.storageKey)
        assertTrue(storage.hasCurrentReport())
    }

    fun testStoreCurrentReport_OverwritesPrevious() {
        val report1 = createTestReport(ReportTriggerType.SCHEDULED, "report1")
        val report2 = createTestReport(ReportTriggerType.SCHEDULED, "report2")

        storage.storeCurrentReport(report1)
        storage.storeCurrentReport(report2)

        val current = storage.getCurrentReport()
        assertNotNull(current)
        assertEquals("report2", current!!.label)
    }

    // endregion

    // region Get Report Tests

    fun testGetMostRecentReport_PrioritizesCurrentReport() {
        // Setup: Store reports of different types
        val scheduledReport = createTestReport(ReportTriggerType.SCHEDULED, "scheduled")
        val gitReport = createTestReport(ReportTriggerType.GIT_COMMIT, "git")
        val currentReport = createTestReport(ReportTriggerType.IMMEDIATE, "current")

        storage.storeReport(scheduledReport)
        storage.storeReport(gitReport)
        storage.storeCurrentReport(currentReport)

        // Current report should have highest priority
        val mostRecent = storage.getMostRecentReport()
        assertNotNull(mostRecent)
        assertEquals("current", mostRecent!!.label)
    }

    fun testGetMostRecentReport_FallsBackToGitTriggered() {
        val scheduledReport = createTestReport(ReportTriggerType.SCHEDULED, "scheduled")
        val gitReport = createTestReport(ReportTriggerType.GIT_COMMIT, "git")

        storage.storeReport(scheduledReport)
        storage.storeReport(gitReport)
        // No current report stored

        val mostRecent = storage.getMostRecentReport()
        assertNotNull(mostRecent)
        assertEquals("git", mostRecent!!.label)
    }

    fun testGetMostRecentReport_FallsBackToDaily() {
        val scheduledReport = createTestReport(ReportTriggerType.SCHEDULED, "scheduled")
        storage.storeReport(scheduledReport)
        // No current or git report

        val mostRecent = storage.getMostRecentReport()
        assertNotNull(mostRecent)
        assertEquals("scheduled", mostRecent!!.label)
    }

    fun testGetMostRecentReport_ReturnsNullWhenEmpty() {
        val mostRecent = storage.getMostRecentReport()
        assertNull(mostRecent)
    }

    fun testGetReportForDate_ReturnsCorrectReport() {
        val report = createTestReport(ReportTriggerType.SCHEDULED, "today")
        storage.storeReport(report)

        val retrieved = storage.getReportForDate(LocalDate.now())
        assertNotNull(retrieved)
        assertEquals("today", retrieved!!.label)
    }

    fun testGetReportForDate_ReturnsNullForMissingDate() {
        val retrieved = storage.getReportForDate(LocalDate.now().minusDays(30))
        assertNull(retrieved)
    }

    // endregion

    // region Clear Tests

    fun testClearCurrentReport_OnlyClearsCurrent() {
        val scheduledReport = createTestReport(ReportTriggerType.SCHEDULED, "scheduled")
        val currentReport = createTestReport(ReportTriggerType.IMMEDIATE, "current")

        storage.storeReport(scheduledReport)
        storage.storeCurrentReport(currentReport)

        storage.clearCurrentReport()

        assertNull(storage.getCurrentReport())
        assertEquals(1, storage.getDailyReportCount()) // Daily still exists
    }

    fun testClearAll_ClearsEverything() {
        storage.storeReport(createTestReport(ReportTriggerType.SCHEDULED))
        storage.storeReport(createTestReport(ReportTriggerType.GIT_COMMIT))
        storage.storeCurrentReport(createTestReport(ReportTriggerType.IMMEDIATE))

        storage.clearAll()

        assertEquals(0, storage.getDailyReportCount())
        assertFalse(storage.hasGitTriggeredReport())
        assertFalse(storage.hasCurrentReport())
    }

    // endregion

    // region Has Today Report Tests

    fun testHasTodayReport_ReturnsTrueWhenExists() {
        storage.storeReport(createTestReport(ReportTriggerType.SCHEDULED))
        assertTrue(storage.hasTodayReport())
    }

    fun testHasTodayReport_ReturnsFalseWhenEmpty() {
        assertFalse(storage.hasTodayReport())
    }

    // endregion

    // region Helper Methods

    private fun createTestReport(
        triggerType: ReportTriggerType,
        label: String = "test"
    ): HealthReport {
        return HealthReport(
            timestamp = System.currentTimeMillis(),
            triggerType = triggerType,
            results = listOf(createTestMethodResult()),
            label = label
        )
    }

    private fun createTestMethodResult(): MethodHealthResult {
        return MethodHealthResult(
            fqn = "com.example.TestClass.testMethod",
            healthScore = 85,
            modificationCount = 3,
            issues = listOf(
                HealthIssue(
                    issueCategory = "Performance",
                    severity = 3,
                    title = "Test Issue",
                    description = "Test description",
                    impact = "Test impact",
                    suggestedFix = "Test fix",
                    verified = true,
                    falsePositive = false
                )
            )
        )
    }

    // endregion
}
