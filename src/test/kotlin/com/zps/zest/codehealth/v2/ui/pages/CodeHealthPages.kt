package com.zps.zest.codehealth.v2.ui.pages

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Page object for Code Health notifications.
 * Encapsulates interactions with notification balloons.
 */
class NotificationPage(private val robot: RemoteRobot) {

    companion object {
        private const val NOTIFICATION_XPATH = "//div[contains(@class, 'NotificationBalloon')]"
        private const val FIX_NOW_XPATH = "//div[@text='🚀 Fix Now']"
        private const val VIEW_DETAILS_XPATH = "//div[@text='👀 View Details']"
    }

    /**
     * Check if a Code Health notification is visible.
     */
    fun isCodeHealthNotificationVisible(): Boolean = step("Check if Code Health notification is visible") {
        try {
            robot.find<ComponentFixture>(
                byXpath("$NOTIFICATION_XPATH//div[contains(@text, 'Code') or contains(@text, 'Guardian') or contains(@text, 'Health')]")
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the notification title text.
     */
    fun getNotificationTitle(): String? = step("Get notification title") {
        try {
            val label = robot.find<ComponentFixture>(
                byXpath("$NOTIFICATION_XPATH//div[@class='JLabel'][1]")
            )
            label.callJs<String>("component.getText()")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the notification content text.
     */
    fun getNotificationContent(): String? = step("Get notification content") {
        try {
            val label = robot.find<ComponentFixture>(
                byXpath("$NOTIFICATION_XPATH//div[@class='JLabel'][2]")
            )
            label.callJs<String>("component.getText()")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Click "Fix Now" button in notification.
     */
    fun clickFixNow() = step("Click Fix Now button") {
        robot.find<JButtonFixture>(byXpath(FIX_NOW_XPATH)).click()
    }

    /**
     * Click "View Details" button in notification.
     */
    fun clickViewDetails() = step("Click View Details button") {
        robot.find<JButtonFixture>(byXpath(VIEW_DETAILS_XPATH)).click()
    }

    /**
     * Check if Fix Now button is present.
     */
    fun hasFixNowButton(): Boolean {
        return try {
            robot.find<JButtonFixture>(byXpath(FIX_NOW_XPATH))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if View Details button is present.
     */
    fun hasViewDetailsButton(): Boolean {
        return try {
            robot.find<JButtonFixture>(byXpath(VIEW_DETAILS_XPATH))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wait for notification to disappear.
     */
    fun waitForNotificationToDisappear(timeout: Duration = Duration.ofSeconds(10)): Boolean =
        step("Wait for notification to disappear") {
            try {
                waitFor(timeout, Duration.ofSeconds(1)) {
                    !isCodeHealthNotificationVisible()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}

/**
 * Page object for Code Health Overview editor.
 * Encapsulates interactions with the Code Health Overview tab/editor.
 */
class CodeHealthOverviewPage(private val robot: RemoteRobot) {

    companion object {
        private const val OVERVIEW_TAB_XPATH = "//div[contains(@class, 'EditorTab') and contains(@text, 'Code Health')]"
        private const val SUMMARY_PANEL_XPATH = "//div[contains(@text, 'Code Health Dashboard')]"
        private const val CRITICAL_TAB_XPATH = "//div[@text='🚨 Critical Issues']"
        private const val ISSUES_TAB_XPATH = "//div[@text='⚠️ Less-Critical Issues']"
        private const val TRACKED_TAB_XPATH = "//div[@text='📋 Tracked Methods']"
    }

    /**
     * Check if Code Health Overview editor is open.
     */
    fun isOpen(): Boolean = step("Check if Code Health Overview is open") {
        try {
            robot.find<ComponentFixture>(byXpath(OVERVIEW_TAB_XPATH))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wait for Code Health Overview to open.
     */
    fun waitForOpen(timeout: Duration = Duration.ofSeconds(10)): Boolean =
        step("Wait for Code Health Overview to open") {
            try {
                waitFor(timeout, Duration.ofSeconds(1)) {
                    isOpen()
                }
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Check if summary panel is visible (dashboard with metrics).
     */
    fun isSummaryPanelVisible(): Boolean = step("Check if summary panel is visible") {
        try {
            robot.find<ComponentFixture>(byXpath(SUMMARY_PANEL_XPATH))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get overall health score from dashboard.
     */
    fun getOverallHealthScore(): Int? = step("Get overall health score") {
        try {
            val scoreLabel = robot.find<ComponentFixture>(
                byXpath("//div[contains(@text, 'Overall Health')]/..//div[@class='JLabel'][contains(@text, '/100')]")
            )
            val text = scoreLabel.callJs<String>("component.getText()")
            text.replace("/100", "").trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get methods analyzed count.
     */
    fun getMethodsAnalyzedCount(): Int? = step("Get methods analyzed count") {
        try {
            val label = robot.find<ComponentFixture>(
                byXpath("//div[contains(@text, 'Methods Analyzed')]/..//div[@class='JLabel'][last()]")
            )
            val text = label.callJs<String>("component.getText()")
            text.trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get issues found count.
     */
    fun getIssuesFoundCount(): Int? = step("Get issues found count") {
        try {
            val label = robot.find<ComponentFixture>(
                byXpath("//div[contains(@text, 'Issues Found')]/..//div[@class='JLabel'][last()]")
            )
            val text = label.callJs<String>("component.getText()")
            text.trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Click on Critical Issues tab.
     */
    fun clickCriticalIssuesTab() = step("Click Critical Issues tab") {
        robot.find<ComponentFixture>(byXpath(CRITICAL_TAB_XPATH)).click()
    }

    /**
     * Click on Less-Critical Issues tab.
     */
    fun clickLessCriticalIssuesTab() = step("Click Less-Critical Issues tab") {
        robot.find<ComponentFixture>(byXpath(ISSUES_TAB_XPATH)).click()
    }

    /**
     * Click on Tracked Methods tab.
     */
    fun clickTrackedMethodsTab() = step("Click Tracked Methods tab") {
        robot.find<ComponentFixture>(byXpath(TRACKED_TAB_XPATH)).click()
    }

    /**
     * Click Refresh Data button in toolbar.
     */
    fun clickRefreshData() = step("Click Refresh Data") {
        robot.find<ComponentFixture>(byXpath("//div[@tooltiptext='Refresh Data']")).click()
    }

    /**
     * Click Run Analysis button in toolbar.
     */
    fun clickRunAnalysis() = step("Click Run Analysis") {
        robot.find<ComponentFixture>(byXpath("//div[@tooltiptext='Run Analysis']")).click()
    }

    /**
     * Close the Code Health Overview tab.
     */
    fun close() = step("Close Code Health Overview") {
        try {
            val tab = robot.find<CommonContainerFixture>(byXpath(OVERVIEW_TAB_XPATH))
            // Click the close button on the tab
            tab.find<ComponentFixture>(byXpath("//div[@class='InplaceButton']")).click()
        } catch (e: Exception) {
            // Fallback: try clicking tab and using Ctrl+F4
            try {
                robot.find<ComponentFixture>(byXpath(OVERVIEW_TAB_XPATH)).click()
                // Use JavaScript to simulate key press
                robot.runJs("robot.keyboard('ctrl+F4')")
            } catch (e2: Exception) {
                // Ignore if can't close
            }
        }
    }
}

/**
 * Page object for the Code Guardian status bar widget.
 */
class StatusBarWidgetPage(private val robot: RemoteRobot) {

    companion object {
        private const val WIDGET_XPATH = "//div[contains(@class, 'StatusBarWidget') and contains(@text, 'Guardian')]"
    }

    /**
     * Check if the Guardian widget is visible.
     */
    fun isVisible(): Boolean {
        return try {
            robot.find<ComponentFixture>(byXpath(WIDGET_XPATH))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the widget text (e.g., "Guardian: 3 issues").
     */
    fun getText(): String? {
        return try {
            robot.find<ComponentFixture>(byXpath(WIDGET_XPATH)).callJs<String>("component.getText()")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Click the widget to open the menu.
     */
    fun click() = step("Click Guardian status bar widget") {
        robot.find<ComponentFixture>(byXpath(WIDGET_XPATH)).click()
    }

    /**
     * Right-click the widget for context menu.
     */
    fun rightClick() = step("Right-click Guardian widget") {
        robot.find<ComponentFixture>(byXpath(WIDGET_XPATH)).rightClick()
    }
}
