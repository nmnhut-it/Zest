package com.zps.zest.codehealth.v2.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import org.junit.After
import org.junit.Assume
import org.junit.Before
import java.time.Duration

/**
 * Base class for UI tests using RemoteRobot.
 * Provides common setup, teardown, and utility methods.
 *
 * Prerequisites:
 * 1. Run: ./gradlew runIdeForUiTests
 * 2. Wait for IDE to fully start
 * 3. Run: ./gradlew testUI
 *
 * Tests will be SKIPPED (not failed) if IDE is not running.
 */
abstract class BaseUITest {

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = "8082"

        val robotHost: String = System.getProperty("robot.host", DEFAULT_HOST)
        val robotPort: String = System.getProperty("robot.port", DEFAULT_PORT)

        /**
         * Check if RemoteRobot server is available.
         */
        fun isIdeRunning(): Boolean {
            return try {
                val testRobot = RemoteRobot("http://$robotHost:$robotPort")
                testRobot.find<ComponentFixture>(byXpath("//div"), Duration.ofSeconds(2))
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    protected lateinit var robot: RemoteRobot
    protected var ideAvailable = false

    @Before
    open fun setUp() {
        // Skip all UI tests if IDE is not running
        ideAvailable = isIdeRunning()
        Assume.assumeTrue(
            "Skipping UI test: IDE not running. Start with: ./gradlew runIdeForUiTests",
            ideAvailable
        )

        robot = RemoteRobot("http://$robotHost:$robotPort")
        waitForIdeToBeReady()
    }

    @After
    open fun tearDown() {
        // Close any open dialogs
        closeAllDialogs()
    }

    /**
     * Wait for IDE to be fully loaded and responsive.
     */
    protected fun waitForIdeToBeReady() = step("Wait for IDE to be ready") {
        waitFor(Duration.ofMinutes(2), Duration.ofSeconds(5)) {
            try {
                robot.find<CommonContainerFixture>(byXpath("//div[@class='IdeFrameImpl']"))
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Close all open dialogs.
     */
    protected fun closeAllDialogs() = step("Close all dialogs") {
        try {
            val dialogs = robot.findAll<CommonContainerFixture>(byXpath("//div[@class='MyDialog']"))
            dialogs.forEach { dialog ->
                try {
                    dialog.find<JButtonFixture>(byXpath("//div[@text='Cancel']")).click()
                } catch (e: Exception) {
                    // Ignore if no cancel button
                }
            }
        } catch (e: Exception) {
            // No dialogs to close
        }
    }

    /**
     * Wait for a notification to appear.
     */
    protected fun waitForNotification(
        titleContains: String,
        timeout: Duration = Duration.ofSeconds(30)
    ): ComponentFixture? = step("Wait for notification: $titleContains") {
        var notification: ComponentFixture? = null
        waitFor(timeout, Duration.ofSeconds(1)) {
            try {
                notification = robot.find<ComponentFixture>(
                    byXpath("//div[contains(@class, 'NotificationBalloon')]//div[contains(@text, '$titleContains')]")
                )
                true
            } catch (e: Exception) {
                false
            }
        }
        notification
    }

    /**
     * Click a button in the current context by text.
     */
    protected fun clickButton(text: String) = step("Click button: $text") {
        robot.find<JButtonFixture>(byXpath("//div[@text='$text']")).click()
    }

    /**
     * Open the Code Health tool window.
     */
    protected fun openCodeHealthToolWindow() = step("Open Code Health tool window") {
        // Try via menu
        try {
            robot.find<ComponentFixture>(byXpath("//div[@text='View']")).click()
            Thread.sleep(500)
            robot.find<ComponentFixture>(byXpath("//div[@text='Tool Windows']")).click()
            Thread.sleep(500)
            robot.find<ComponentFixture>(byXpath("//div[contains(@text, 'Code Health')]")).click()
        } catch (e: Exception) {
            // Try via status bar widget
            robot.find<ComponentFixture>(
                byXpath("//div[contains(@class, 'StatusBarWidget') and contains(@text, 'Guardian')]")
            ).click()
        }
    }

    /**
     * Find the Code Health Overview editor tab.
     */
    protected fun findCodeHealthOverviewTab(): ComponentFixture? = step("Find Code Health Overview tab") {
        try {
            robot.find<ComponentFixture>(
                byXpath("//div[contains(@class, 'EditorTab') and contains(@text, 'Code Health')]")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Wait for editor tab to open.
     */
    protected fun waitForEditorTab(
        tabNameContains: String,
        timeout: Duration = Duration.ofSeconds(10)
    ): Boolean = step("Wait for editor tab: $tabNameContains") {
        try {
            waitFor(timeout, Duration.ofSeconds(1)) {
                try {
                    robot.find<ComponentFixture>(
                        byXpath("//div[contains(@class, 'EditorTab') and contains(@text, '$tabNameContains')]")
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
