package com.zps.zest.codehealth.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.MessageType
import com.intellij.ui.awt.RelativePoint
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.zps.zest.codehealth.CodeHealthTracker
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.zps.zest.codehealth.CodeHealthReportStorage
import kotlinx.coroutines.*
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.Icon

/**
 * Status bar widget for Code Guardian - shows health monitoring status
 */
class CodeGuardianStatusBarWidget(project: Project) : EditorBasedWidget(project) {

    private val logger = Logger.getInstance(CodeGuardianStatusBarWidget::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val WIDGET_ID = "CodeGuardianStatus"

        // Icons for different states
        private val ICON_IDLE = AllIcons.RunConfigurations.TestPassed // Green check
        private val ICON_ANALYZING = AllIcons.Process.Step_1 // Spinning
        private val ICON_ISSUES = AllIcons.RunConfigurations.TestFailed // Red X
        private val ICON_WARNING = AllIcons.General.Warning // Yellow warning
        private val ICON_ERROR = AllIcons.General.Error // Red error
        private val ICON_SUCCESS = AllIcons.General.InspectionsOK // Green checkmark
    }

    // Current state
    private var currentState = GuardianState.IDLE
    private var issueCount = 0
    private var criticalCount = 0
    private var healthScore = 100
    private var lastCheckTime: LocalDateTime? = null
    private var displayText = "🟢 Guardian"
    private var detailedStatus = ""

    enum class GuardianState(val baseText: String, val icon: Icon) {
        IDLE("Guardian", ICON_SUCCESS),
        ANALYZING("Guardian: Analyzing...", ICON_ANALYZING),
        ISSUES_FOUND("Guardian", ICON_ISSUES),
        WARNING("Guardian", ICON_WARNING),
        ERROR("Guardian: Error", ICON_ERROR),
        SUCCESS("Guardian: Healthy", ICON_SUCCESS)
    }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(type: StatusBarWidget.PlatformType): StatusBarWidget.WidgetPresentation? {
        return TextPresentation()
    }

    private inner class TextPresentation : StatusBarWidget.TextPresentation {
        override fun getText(): String = displayText

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        override fun getTooltipText(): String? {
            val lastCheckStr = lastCheckTime?.let {
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                "Last check: ${it.format(formatter)}"
            } ?: "No check performed yet"

            val issuesStr = when {
                issueCount == 0 -> "No issues found"
                criticalCount > 0 -> "$issueCount issues ($criticalCount critical)"
                else -> "$issueCount issues found"
            }

            val healthStr = "Health score: $healthScore/100"

            val statusStr = if (detailedStatus.isNotEmpty()) {
                "\n$detailedStatus"
            } else ""
            
            // Check if reports are available
            val storage = CodeHealthReportStorage.getInstance(project)
            val reportHint = if (storage.hasTodayReport() || storage.getMostRecentReportDate() != null) {
                "\n\n📊 Click to view health reports"
            } else {
                "\n\nClick for options"
            }

            return """
                Code Guardian Status
                ────────────────────
                $lastCheckStr
                $issuesStr
                $healthStr$statusStr
                $reportHint
            """.trimIndent()
        }

        override fun getClickConsumer(): Consumer<MouseEvent>? {
            return Consumer { mouseEvent ->
                try {
                    when {
                        mouseEvent.isPopupTrigger || mouseEvent.button == MouseEvent.BUTTON3 -> {
                            showPopupMenu(mouseEvent)
                        }

                        mouseEvent.button == MouseEvent.BUTTON1 -> {
                            handleLeftClick(mouseEvent)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error handling widget click", e)
                }
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        logger.info("CodeGuardianStatusBarWidget installed")

        // Initial state
        updateState(GuardianState.IDLE)

        // Start listening to health check events
        setupHealthCheckListener()

        // Start periodic state sync
        startPeriodicStateSync()

        // Force initial widget update
        ApplicationManager.getApplication().invokeLater {
            statusBar.updateWidget(ID())
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
        logger.info("CodeGuardianStatusBarWidget disposed")
    }

    private fun handleLeftClick(mouseEvent: MouseEvent) {
        when (currentState) {
            GuardianState.IDLE -> {
                // Check if we have stored reports
                val storage = CodeHealthReportStorage.getInstance(project)
                if (storage.hasTodayReport() || storage.getMostRecentReportDate() != null) {
                    showStoredReports()
                } else {
                    showQuickMenu(mouseEvent)
                }
            }

            GuardianState.ANALYZING -> {
                showBalloon("Analysis in progress...", MessageType.INFO, mouseEvent)
            }

            GuardianState.ISSUES_FOUND, GuardianState.WARNING -> {
                // Show detailed report
                showStoredReports()
            }

            GuardianState.ERROR -> {
                showBalloon("Check failed. Click menu for details.", MessageType.ERROR, mouseEvent)
            }

            GuardianState.SUCCESS -> {
                // Show report even for success
                showStoredReports()
            }
        }
    }

    private fun showQuickMenu(mouseEvent: MouseEvent) {
        val group = DefaultActionGroup()

        group.add(object : AnAction(
            "View Full Report",
            "View detailed health report",
            AllIcons.Actions.Preview
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                showStoredReports()
            }

            override fun update(e: AnActionEvent) {
                val storage = CodeHealthReportStorage.getInstance(project)
                e.presentation.isEnabled = storage.hasTodayReport() || storage.getMostRecentReportDate() != null
            }
        })

        group.addSeparator()

        group.add(object : AnAction(
            "Run Check Now",
            "Run health check immediately",
            AllIcons.Actions.Execute
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                runHealthCheck()
            }
        })

        group.add(object : AnAction(
            "Settings",
            "Open Code Guardian settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                // TODO: Open settings
                showBalloon("Opening settings...", MessageType.INFO, mouseEvent)
            }
        })

        val dataContext = SimpleDataContext.getProjectContext(project)
        val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Code Guardian",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )

        popup.show(RelativePoint(mouseEvent))
    }

    private fun showPopupMenu(mouseEvent: MouseEvent) {
        val group = DefaultActionGroup()

        // Add status info
        group.add(object : AnAction(
            "Status: ${currentState.baseText}",
            null,
            currentState.icon
        ) {
            override fun actionPerformed(e: AnActionEvent) {}
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = false
            }
        })

        if (lastCheckTime != null) {
            val timeAgo = getTimeAgo(lastCheckTime!!)
            group.add(object : AnAction(
                "Last check: $timeAgo",
                null,
                AllIcons.Vcs.History
            ) {
                override fun actionPerformed(e: AnActionEvent) {}
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }
            })
        }

        group.addSeparator()

        // Actions
        group.add(object : AnAction(
            "View Full Report",
            "View detailed health report",
            AllIcons.Actions.Preview
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                showDetailedReport()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = lastCheckTime != null && issueCount > 0
            }
        })

        group.add(object : AnAction(
            "Run Check Now",
            "Run health check immediately",
            AllIcons.Actions.Execute
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                runHealthCheck()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = currentState != GuardianState.ANALYZING
            }
        })

        group.addSeparator()

        group.add(object : AnAction(
            "Start Background Patrol",
            "Start automatic background checking",
            AllIcons.Actions.StartDebugger
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                startBackgroundPatrol()
            }
        })

        group.add(object : AnAction(
            "Daily Report",
            "Generate daily health report",
            AllIcons.Actions.Download
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                generateDailyReport()
            }
        })

        group.addSeparator()

        group.add(object : AnAction(
            "Settings",
            "Open Code Guardian settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                // TODO: Open settings
                showBalloon("Opening settings...", MessageType.INFO, mouseEvent)
            }
        })

        val dataContext = SimpleDataContext.getProjectContext(project)
        val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Code Guardian Menu",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )

        popup.show(RelativePoint(mouseEvent))
    }

    private fun updateState(
        state: GuardianState,
        issues: Int = 0,
        critical: Int = 0,
        score: Int = 100,
        status: String = ""
    ) {
        currentState = state
        issueCount = issues
        criticalCount = critical
        healthScore = score
        detailedStatus = status

        // Update display text based on state
        displayText = when (state) {
            GuardianState.IDLE -> "🟢 Guardian"
            GuardianState.ANALYZING -> "⚡ Guardian: Analyzing..."
            GuardianState.ISSUES_FOUND -> {
                when {
                    critical > 0 -> "🔴 Guardian: $issues issues"
                    issues > 5 -> "🟡 Guardian: $issues issues"
                    else -> "🟡 Guardian: $issues issues"
                }
            }

            GuardianState.WARNING -> "🟡 Guardian: Warning"
            GuardianState.ERROR -> "⚠️ Guardian: Check failed"
            GuardianState.SUCCESS -> "✅ Guardian: Healthy"
        }

        refreshWidget()
    }

    private fun refreshWidget() {
        ApplicationManager.getApplication().invokeLater {
            myStatusBar?.updateWidget(ID())
        }
    }

    private fun setupHealthCheckListener() {
        // Listen to health check events via message bus
        val connection = project.messageBus.connect(this)

        // TODO: Subscribe to health check events when message bus topic is implemented
        // For now, we'll poll the service periodically
    }

    private fun startPeriodicStateSync() {
        scope.launch {
            while (isActive) {
                delay(5000) // Check every 5 seconds
                try {
                    syncWithHealthTracker()
                } catch (e: Exception) {
                    logger.debug("Error in periodic state sync", e)
                }
            }
        }
    }

    private fun syncWithHealthTracker() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val tracker = CodeHealthTracker.getInstance(project)
                val lastResults = tracker.getLastResults()

                if (lastResults != null) {
                    val realIssues = lastResults.flatMap { it.issues }
                        .filter { it.verified && !it.falsePositive }

                    val totalIssues = realIssues.size
                    val criticalIssues = realIssues.count { it.severity >= 4 }
                    val avgScore = if (lastResults.isNotEmpty()) {
                        lastResults.map { it.healthScore }.average().toInt()
                    } else 100

                    lastCheckTime = LocalDateTime.now() // TODO: Get actual check time from tracker

                    val state = when {
                        totalIssues == 0 -> GuardianState.SUCCESS
                        criticalIssues > 0 -> GuardianState.ISSUES_FOUND
                        totalIssues > 0 -> GuardianState.WARNING
                        else -> GuardianState.IDLE
                    }

                    updateState(state, totalIssues, criticalIssues, avgScore)
                }
            } catch (e: Exception) {
                logger.debug("Error syncing with health tracker", e)
            }
        }
    }

    private fun runHealthCheck() {
        logger.info("Running health check from status bar")

        updateState(GuardianState.ANALYZING, status = "Starting analysis...")

        scope.launch {
            try {
                val tracker = CodeHealthTracker.getInstance(project)

                ApplicationManager.getApplication().invokeLater {
                    tracker.checkAndNotify()
                }

                // The state will be updated via the listener/sync
            } catch (e: Exception) {
                logger.error("Error running health check", e)
                ApplicationManager.getApplication().invokeLater {
                    updateState(GuardianState.ERROR, status = "Check failed: ${e.message}")
                }
            }
        }
    }

    private fun showDetailedReport() {
        val tracker = CodeHealthTracker.getInstance(project)
        val lastResults = tracker.getLastResults()

        if (lastResults != null && lastResults.isNotEmpty()) {
            com.zps.zest.codehealth.CodeHealthNotification.showHealthReport(project, lastResults)
        } else {
            showStoredReports()
        }
    }
    
    private fun showStoredReports() {
        ApplicationManager.getApplication().invokeLater {
            val dialog = com.zps.zest.codehealth.ui.SwingHealthReportDialog(project)
            dialog.show()
        }
    }

    private fun startBackgroundPatrol() {
        logger.info("Starting background patrol from status bar")

        val reviewer = BackgroundHealthReviewer.getInstance(project)
        reviewer.triggerBackgroundReview()

        showBalloon("Background patrol started", MessageType.INFO)
    }

    private fun generateDailyReport() {
        logger.info("Generating daily report from status bar")

        val reviewer = BackgroundHealthReviewer.getInstance(project)
        reviewer.triggerFinalReview()

        showBalloon("Generating daily report...", MessageType.INFO)
    }

    private fun getTimeAgo(time: LocalDateTime): String {
        val now = LocalDateTime.now()
        val minutes = java.time.Duration.between(time, now).toMinutes()

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 120 -> "1 hour ago"
            minutes < 1440 -> "${minutes / 60} hours ago"
            else -> "${minutes / 1440} days ago"
        }
    }

    private fun showBalloon(message: String, messageType: MessageType, event: MouseEvent? = null) {
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, messageType.defaultIcon, messageType.popupBackground) { _ -> }
            .setFadeoutTime(2500)
            .createBalloon()

        if (event != null) {
            val point = RelativePoint(event.component, Point(event.x, event.y - 30))
            balloon.show(point, Balloon.Position.above)
        } else {
            // Show at status bar location
            myStatusBar?.let { statusBar ->
                val component = statusBar.component
                val point = RelativePoint(component as Component, Point(component.width - 200, 0))
                balloon.show(point, Balloon.Position.above)
            }
        }
    }

    /**
     * Public methods for updating state from health check services
     */
    fun notifyAnalysisStarted(status: String = "") {
        ApplicationManager.getApplication().invokeLater {
            updateState(GuardianState.ANALYZING, status = status)
        }
    }

    fun notifyAnalysisProgress(current: Int, total: Int) {
        ApplicationManager.getApplication().invokeLater {
            val status = if (total > 0) "Analyzing $current/$total methods" else "Analyzing..."
            updateState(GuardianState.ANALYZING, status = status)
        }
    }

    fun notifyAnalysisComplete(
        results: List<CodeHealthAnalyzer.MethodHealthResult>
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (results.isEmpty()) {
                updateState(GuardianState.SUCCESS, status = "No methods to analyze")
                return@invokeLater
            }

            val realIssues = results.flatMap { it.issues }
                .filter { it.verified && !it.falsePositive }

            val totalIssues = realIssues.size
            val criticalIssues = realIssues.count { it.severity >= 4 }
            val avgScore = results.map { it.healthScore }.average().toInt()

            lastCheckTime = LocalDateTime.now()

            val state = when {
                totalIssues == 0 -> GuardianState.SUCCESS
                criticalIssues > 0 -> GuardianState.ISSUES_FOUND
                totalIssues > 0 -> GuardianState.WARNING
                else -> GuardianState.IDLE
            }

            val status = when {
                totalIssues == 0 -> "All methods healthy"
                criticalIssues > 0 -> "$criticalIssues critical issues found"
                else -> "$totalIssues issues found"
            }

            updateState(state, totalIssues, criticalIssues, avgScore, status)
        }
    }

    fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            updateState(GuardianState.ERROR, status = message)
        }
    }
}
