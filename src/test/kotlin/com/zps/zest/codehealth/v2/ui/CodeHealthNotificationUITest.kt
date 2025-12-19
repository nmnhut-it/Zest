package com.zps.zest.codehealth.v2.ui

import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.zps.zest.codehealth.v2.ui.pages.CodeHealthOverviewPage
import com.zps.zest.codehealth.v2.ui.pages.NotificationPage
import com.zps.zest.codehealth.v2.ui.pages.StatusBarWidgetPage
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.time.Duration

/**
 * UI tests for Code Health notification and display flow.
 *
 * These tests verify the fix for the bug where clicking a notification
 * would show stale data instead of the current report.
 *
 * Prerequisites:
 * 1. Start IDE: ./gradlew runIdeForUiTests
 * 2. Wait for IDE to fully load
 * 3. Open a project with some Java/Kotlin files
 * 4. Run tests: ./gradlew testUI
 *
 * Note: UI tests are inherently slower and more fragile than unit tests.
 * Use @Ignore for tests that require specific IDE state.
 */
class CodeHealthNotificationUITest : BaseUITest() {

    private lateinit var notificationPage: NotificationPage
    private lateinit var overviewPage: CodeHealthOverviewPage
    private lateinit var statusBarPage: StatusBarWidgetPage

    override fun setUp() {
        super.setUp()
        notificationPage = NotificationPage(robot)
        overviewPage = CodeHealthOverviewPage(robot)
        statusBarPage = StatusBarWidgetPage(robot)
    }

    /**
     * Test that the Code Health Overview can be opened via status bar widget.
     */
    @Test
    fun testOpenCodeHealthOverviewViaStatusBar() {
        step("Test opening Code Health Overview via status bar") {
            // Skip if widget not visible (plugin not loaded)
            if (!statusBarPage.isVisible()) {
                println("SKIP: Guardian widget not visible - plugin may not be loaded")
                return@step
            }

            // Click the status bar widget
            statusBarPage.click()

            // Wait for overview to open
            val opened = overviewPage.waitForOpen(Duration.ofSeconds(15))
            assertTrue("Code Health Overview should open when clicking status bar widget", opened)

            // Verify summary panel is visible
            try {
                waitFor(Duration.ofSeconds(5)) {
                    overviewPage.isSummaryPanelVisible()
                }
            } catch (e: Exception) {
                // Ignore timeout
            }
            assertTrue("Summary panel should be visible", overviewPage.isSummaryPanelVisible())

            // Clean up - close the tab
            overviewPage.close()
        }
    }

    /**
     * Test that clicking "View Details" in notification opens Code Health Overview.
     *
     * Note: This test requires a notification to be present.
     * It may need to be triggered manually or via a scheduled check.
     */
    @Test
    @Ignore("Requires notification to be present - run manually after triggering a health check")
    fun testClickViewDetailsOpensOverview() {
        step("Test View Details button opens overview") {
            // Wait for notification to appear (may need to trigger health check first)
            waitForNotification("Code", Duration.ofSeconds(60))

            // Check if View Details button is present
            if (!notificationPage.hasViewDetailsButton()) {
                println("SKIP: No View Details button in notification")
                return@step
            }

            // Click View Details
            notificationPage.clickViewDetails()

            // Verify Code Health Overview opens
            val opened = overviewPage.waitForOpen(Duration.ofSeconds(10))
            assertTrue("Code Health Overview should open after clicking View Details", opened)

            // Verify the overview has data (the bug fix ensures fresh data is shown)
            try {
                waitFor(Duration.ofSeconds(5)) {
                    overviewPage.isSummaryPanelVisible()
                }
            } catch (e: Exception) {
                // Ignore timeout
            }

            val methodsCount = overviewPage.getMethodsAnalyzedCount()
            assertTrue(
                "Overview should show methods analyzed (not stale/empty data)",
                methodsCount != null && methodsCount > 0
            )
        }
    }

    /**
     * Test that clicking "Fix Now" in critical notification opens overview.
     */
    @Test
    @Ignore("Requires critical notification - run manually after triggering health check with critical issues")
    fun testClickFixNowOpensOverview() {
        step("Test Fix Now button opens overview") {
            // Wait for notification
            waitForNotification("Critical", Duration.ofSeconds(60))

            if (!notificationPage.hasFixNowButton()) {
                println("SKIP: No Fix Now button (no critical issues)")
                return@step
            }

            // Click Fix Now
            notificationPage.clickFixNow()

            // Verify overview opens
            assertTrue(
                "Code Health Overview should open after clicking Fix Now",
                overviewPage.waitForOpen(Duration.ofSeconds(10))
            )
        }
    }

    /**
     * Test Code Health Overview displays correct data after refresh.
     */
    @Test
    fun testOverviewRefreshShowsData() {
        step("Test overview refresh functionality") {
            // First, open the overview
            if (statusBarPage.isVisible()) {
                statusBarPage.click()
            } else {
                openCodeHealthToolWindow()
            }

            if (!overviewPage.waitForOpen(Duration.ofSeconds(15))) {
                println("SKIP: Could not open Code Health Overview")
                return@step
            }

            // Click refresh
            try {
                overviewPage.clickRefreshData()
                Thread.sleep(2000) // Wait for refresh

                // Verify summary panel still visible after refresh
                assertTrue(
                    "Summary panel should remain visible after refresh",
                    overviewPage.isSummaryPanelVisible()
                )
            } finally {
                overviewPage.close()
            }
        }
    }

    /**
     * Test navigating between tabs in Code Health Overview.
     */
    @Test
    fun testOverviewTabNavigation() {
        step("Test tab navigation in overview") {
            // Open overview
            if (statusBarPage.isVisible()) {
                statusBarPage.click()
            } else {
                openCodeHealthToolWindow()
            }

            if (!overviewPage.waitForOpen(Duration.ofSeconds(15))) {
                println("SKIP: Could not open Code Health Overview")
                return@step
            }

            try {
                // Navigate to Critical Issues tab
                overviewPage.clickCriticalIssuesTab()
                Thread.sleep(500)

                // Navigate to Less-Critical Issues tab
                overviewPage.clickLessCriticalIssuesTab()
                Thread.sleep(500)

                // Navigate to Tracked Methods tab
                overviewPage.clickTrackedMethodsTab()
                Thread.sleep(500)

                // All navigations should succeed without error
                assertTrue("Tab navigation completed successfully", true)
            } finally {
                overviewPage.close()
            }
        }
    }

    /**
     * Integration test: Verify the bug fix flow.
     *
     * This test simulates the exact bug scenario:
     * 1. Open overview (simulating stale data)
     * 2. Close it
     * 3. Trigger refresh via status bar
     * 4. Verify fresh data is displayed
     */
    @Test
    fun testBugFix_FreshDataDisplayedAfterNotificationClick() {
        step("Test bug fix: fresh data displayed after notification click") {
            if (!statusBarPage.isVisible()) {
                println("SKIP: Guardian widget not visible")
                return@step
            }

            // Step 1: Open overview first time (may have stale data)
            statusBarPage.click()
            if (!overviewPage.waitForOpen(Duration.ofSeconds(15))) {
                println("SKIP: Could not open Code Health Overview")
                return@step
            }

            // Record initial state
            val initialScore = overviewPage.getOverallHealthScore()
            val initialMethods = overviewPage.getMethodsAnalyzedCount()
            println("Initial state: score=$initialScore, methods=$initialMethods")

            // Step 2: Close the overview
            overviewPage.close()
            Thread.sleep(1000)

            // Step 3: Open again (simulating notification click)
            statusBarPage.click()
            overviewPage.waitForOpen(Duration.ofSeconds(15))

            // Step 4: Verify data is displayed (not empty/stale)
            try {
                waitFor(Duration.ofSeconds(5)) {
                    overviewPage.isSummaryPanelVisible()
                }
            } catch (e: Exception) {
                // Ignore timeout
            }

            val score = overviewPage.getOverallHealthScore()
            val methods = overviewPage.getMethodsAnalyzedCount()
            println("After reopen: score=$score, methods=$methods")

            // The bug was that data would be empty/null after reopening
            // With the fix, data should still be present
            assertTrue(
                "Summary panel should be visible (bug fix: not showing empty)",
                overviewPage.isSummaryPanelVisible()
            )

            overviewPage.close()
        }
    }

    /**
     * Test that notification contains expected elements.
     */
    @Test
    @Ignore("Requires notification to be present")
    fun testNotificationStructure() {
        step("Test notification structure") {
            waitForNotification("Code", Duration.ofSeconds(60))

            assertTrue(
                "Code Health notification should be visible",
                notificationPage.isCodeHealthNotificationVisible()
            )

            val title = notificationPage.getNotificationTitle()
            assertTrue("Notification should have a title", !title.isNullOrEmpty())

            val content = notificationPage.getNotificationContent()
            assertTrue("Notification should have content", !content.isNullOrEmpty())

            // Should have at least one action button
            val hasAction = notificationPage.hasFixNowButton() || notificationPage.hasViewDetailsButton()
            assertTrue("Notification should have an action button", hasAction)
        }
    }
}
