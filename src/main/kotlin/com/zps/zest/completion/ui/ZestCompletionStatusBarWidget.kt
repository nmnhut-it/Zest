package com.zps.zest.completion.ui

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
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionRenderer
import java.awt.event.MouseEvent
import javax.swing.Icon
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.*

/**
 * Enhanced status bar widget showing both completion and method rewrite state
 */
class ZestCompletionStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.IconPresentation {

    private val logger = Logger.getInstance(ZestCompletionStatusBarWidget::class.java)
    private val completionService by lazy { project.getService(ZestInlineCompletionService::class.java) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val WIDGET_ID = "ZestCompletionStatus"

        // Icons for different states
        private val ICON_REQUESTING = AllIcons.Process.Step_1 // Spinning icon for requesting
        private val ICON_WAITING = AllIcons.General.BalloonInformation // Info icon for waiting
        private val ICON_ACCEPTING = AllIcons.Process.Step_2 // Another process icon for accepting
        private val ICON_IDLE = AllIcons.General.InspectionsOK // Green check for idle
        private val ICON_ERROR = AllIcons.General.Error // Red error icon

        // Method rewrite specific icons
        private val ICON_METHOD_ANALYZING = AllIcons.Process.Step_3 // Brain icon for analyzing
        private val ICON_METHOD_AI_QUERY = AllIcons.Process.Step_4 // AI processing
        private val ICON_METHOD_DIFF_READY = AllIcons.General.Modified // Ready for review
        private val ICON_METHOD_APPLYING = AllIcons.Process.Step_5 // Applying changes
    }

    // Current states
    private var currentCompletionState = CompletionState.IDLE
    private var currentMethodRewriteState: MethodRewriteState? = null
    private var methodRewriteStatus = ""

    enum class CompletionState(val displayText: String, val icon: Icon, val tooltip: String) {
        REQUESTING("Requesting...", ICON_REQUESTING, "Zest completion is being requested from AI"),
        WAITING("Ready", ICON_WAITING, "Zest completion is ready - press Tab to accept"),
        ACCEPTING("Accepting...", ICON_ACCEPTING, "Zest completion is being accepted"),
        IDLE("Idle", ICON_IDLE, "Zest completion is idle - ready for new requests"),
        ERROR("Error", ICON_ERROR, "Zest completion has an error or orphaned state")
    }

    enum class MethodRewriteState(val displayText: String, val icon: Icon, val tooltip: String) {
        ANALYZING("Analyzing...", ICON_METHOD_ANALYZING, "Analyzing method structure and context"),
        AI_QUERYING("AI Processing...", ICON_METHOD_AI_QUERY, "Querying AI model for method improvements"),
        DIFF_READY("Review Ready", ICON_METHOD_DIFF_READY, "Method rewrite ready for review - press TAB to accept"),
        APPLYING("Applying...", ICON_METHOD_APPLYING, "Applying method changes to code"),
        COMPLETED("Completed", ICON_IDLE, "Method rewrite completed successfully")
    }

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        logger.info("ZestCompletionStatusBarWidget installed")

        // Initial state update
        updateCompletionState(CompletionState.IDLE)

        // Listen to completion service events
        setupCompletionServiceListener()

        // Start periodic state synchronization
        startPeriodicStateSync()
    }

    override fun dispose() {
        super.dispose()
        scope.cancel()
        logger.info("ZestCompletionStatusBarWidget disposed")
    }

    // StatusBarWidget.IconPresentation implementation
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getIcon(): Icon {
        // Priority: Method rewrite state takes precedence over completion state
        return currentMethodRewriteState?.icon ?: currentCompletionState.icon
    }

    override fun getTooltipText(): String {
        val primaryState = if (currentMethodRewriteState != null) {
            "Method Rewrite: ${currentMethodRewriteState!!.tooltip}"
        } else {
            "Completion: ${currentCompletionState.tooltip}"
        }

        val statusInfo = if (methodRewriteStatus.isNotEmpty()) {
            "\nStatus: $methodRewriteStatus"
        } else ""

        val strategy = "\nStrategy: ${getStrategyName()}"

        return "$primaryState$statusInfo$strategy\n\nClick for options"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { mouseEvent ->
            if (mouseEvent.isPopupTrigger || mouseEvent.button == MouseEvent.BUTTON3) {
                showPopupMenu(mouseEvent)
            } else {
                // Left click - show quick info or actions based on current state
                showQuickActions(mouseEvent)
            }
        }
    }

    /**
     * Update the completion state
     */
    fun updateCompletionState(newState: CompletionState) {
        if (currentCompletionState != newState) {
            logger.debug("Completion state changed: ${currentCompletionState} -> ${newState}")
            currentCompletionState = newState
            refreshWidget()
        }
    }

    /**
     * Update method rewrite state and optionally clear completion state focus
     */
    fun updateMethodRewriteState(newState: MethodRewriteState, status: String = "") {
        logger.info("Method rewrite state: ${newState.displayText} - $status")
        currentMethodRewriteState = newState
        methodRewriteStatus = status
        refreshWidget()

        // Show notification for important state changes
        when (newState) {
            MethodRewriteState.ANALYZING -> {
                ZestNotifications.showInfo(project, "Method Rewrite", "Analyzing method structure...")
            }
            MethodRewriteState.AI_QUERYING -> {
                ZestNotifications.showInfo(project, "Method Rewrite", "Querying AI model for improvements...")
            }
            MethodRewriteState.DIFF_READY -> {
                ZestNotifications.showInfo(project, "Method Rewrite Ready", "Review changes and press TAB to accept, ESC to reject")
            }
            MethodRewriteState.COMPLETED -> {
                ZestNotifications.showInfo(project, "Method Rewrite", "Method rewrite completed successfully!")
                // Auto-clear after showing completion
                scope.launch {
                    delay(3000) // Show for 3 seconds
                    clearMethodRewriteState()
                }
            }
            else -> { /* No notification needed */ }
        }
    }

    /**
     * Update method rewrite status text
     */
    fun updateMethodRewriteStatus(status: String) {
        methodRewriteStatus = status
        refreshWidget()

        // Parse status for state transitions
        when {
            status.contains("Analyzing", ignoreCase = true) -> {
                updateMethodRewriteState(MethodRewriteState.ANALYZING, status)
            }
            status.contains("AI", ignoreCase = true) || status.contains("Querying", ignoreCase = true) -> {
                updateMethodRewriteState(MethodRewriteState.AI_QUERYING, status)
            }
            status.contains("complete", ignoreCase = true) && status.contains("TAB", ignoreCase = true) -> {
                updateMethodRewriteState(MethodRewriteState.DIFF_READY, status)
            }
            status.contains("Applying", ignoreCase = true) -> {
                updateMethodRewriteState(MethodRewriteState.APPLYING, status)
            }
            status.contains("failed", ignoreCase = true) || status.contains("error", ignoreCase = true) -> {
                clearMethodRewriteState()
                ZestNotifications.showError(project, "Method Rewrite Failed", status)
            }
        }
    }

    /**
     * Clear method rewrite state
     */
    public fun clearMethodRewriteState() {
        currentMethodRewriteState = null
        methodRewriteStatus = ""
        refreshWidget()
    }

    /**
     * Get current completion strategy name
     */
    private fun getStrategyName(): String {
        return try {
            completionService.getCompletionStrategy().name
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Refresh the widget display
     */
    private fun refreshWidget() {
        ApplicationManager.getApplication().invokeLater {
            myStatusBar?.updateWidget(ID())
        }
    }

    /**
     * Setup listener for completion service events
     */
    private fun setupCompletionServiceListener() {
        project.messageBus.connect().subscribe(ZestInlineCompletionService.Listener.TOPIC, object : ZestInlineCompletionService.Listener {
            override fun loadingStateChanged(loading: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    if (loading) {
                        updateCompletionState(CompletionState.REQUESTING)
                    } else {
                        val hasCompletion = completionService.getCurrentCompletion() != null
                        updateCompletionState(if (hasCompletion) CompletionState.WAITING else CompletionState.IDLE)
                    }
                }
            }

            override fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext) {
                ApplicationManager.getApplication().invokeLater {
                    updateCompletionState(CompletionState.WAITING)
                }
            }

            override fun completionAccepted(type: ZestInlineCompletionService.AcceptType) {
                ApplicationManager.getApplication().invokeLater {
                    updateCompletionState(CompletionState.ACCEPTING)

                    scope.launch {
                        delay(500)
                        ApplicationManager.getApplication().invokeLater {
                            val hasCompletion = completionService.getCurrentCompletion() != null
                            updateCompletionState(if (hasCompletion) CompletionState.WAITING else CompletionState.IDLE)
                        }
                    }
                }
            }
        })
    }

    /**
     * Show popup menu with options
     */
    private fun showPopupMenu(mouseEvent: MouseEvent) {
        val group = DefaultActionGroup()

        // Method rewrite actions
        if (currentMethodRewriteState != null) {
            group.add(object : AnAction("Cancel Method Rewrite", "Cancel the current method rewrite operation", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    cancelMethodRewrite()
                }
            })

            if (currentMethodRewriteState == MethodRewriteState.DIFF_READY) {
                group.add(object : AnAction("Force Accept Changes", "Force accept the method rewrite changes", AllIcons.Actions.Checked) {
                    override fun actionPerformed(e: AnActionEvent) {
                        // This would need to be implemented in the service
                        showQuickInfo("Use TAB key to accept changes")
                    }
                })
            }

            group.addSeparator()
        }

        // Standard completion actions
        group.add(object : AnAction("Refresh & Clear State", "Clear any orphaned completion state and refresh", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshCompletionState()
            }
        })

        group.add(object : AnAction("Switch Strategy", "Switch between completion strategies", AllIcons.Actions.ToggleVisibility) {
            override fun actionPerformed(e: AnActionEvent) {
                switchCompletionStrategy()
            }
        })

        group.addSeparator()

        group.add(object : AnAction("Show Debug Info", "Show current state information", AllIcons.Actions.Show) {
            override fun actionPerformed(e: AnActionEvent) {
                showDebugInfo()
            }
        })

        val dataContext = SimpleDataContext.getProjectContext(project)
        val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Zest Status",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )

        if (myStatusBar != null) {
            popup.showUnderneathOf(myStatusBar as java.awt.Component)
        } else {
            popup.showInFocusCenter()
        }
    }

    /**
     * Show quick actions (left click)
     */
    private fun showQuickActions(mouseEvent: MouseEvent) {
        when {
            currentMethodRewriteState != null -> {
                when (currentMethodRewriteState!!) {
                    MethodRewriteState.ANALYZING -> showQuickInfo("Method analysis in progress...")
                    MethodRewriteState.AI_QUERYING -> showQuickInfo("AI processing method improvements...")
                    MethodRewriteState.DIFF_READY -> showQuickInfo("Method rewrite ready - Press TAB to accept, ESC to reject")
                    MethodRewriteState.APPLYING -> showQuickInfo("Applying method changes...")
                    MethodRewriteState.COMPLETED -> showQuickInfo("Method rewrite completed successfully!")
                }
            }
            currentCompletionState == CompletionState.ERROR -> {
                refreshCompletionState()
                checkForOrphanedState()
            }
            currentCompletionState == CompletionState.REQUESTING -> {
                showQuickInfo("Completion request in progress...")
            }
            currentCompletionState == CompletionState.WAITING -> {
                showQuickInfo("Completion ready - Press Tab to accept")
            }
            else -> {
                showQuickInfo("Ready for completion or method rewrite")
                checkForOrphanedState()
            }
        }
    }

    /**
     * Cancel method rewrite operation
     */
    private fun cancelMethodRewrite() {
        try {
            // Get method rewrite service and cancel
            val methodRewriteService = project.getService(com.zps.zest.completion.ZestMethodRewriteService::class.java)
            methodRewriteService?.cancelCurrentRewrite()

            clearMethodRewriteState()
            showQuickInfo("Method rewrite cancelled")

        } catch (e: Exception) {
            logger.warn("Failed to cancel method rewrite", e)
            showQuickInfo("Failed to cancel: ${e.message}")
        }
    }

    /**
     * Refresh and clear completion state
     */
    private fun refreshCompletionState() {
        logger.info("Refreshing completion state via status bar...")

        ApplicationManager.getApplication().invokeLater {
            try {
                completionService.forceRefreshState()
                updateCompletionState(CompletionState.IDLE)
                clearMethodRewriteState() // Also clear method rewrite state
                showQuickInfo("State refreshed!")

            } catch (e: Exception) {
                logger.warn("Failed to refresh completion state", e)
                updateCompletionState(CompletionState.ERROR)
                showQuickInfo("Failed to refresh: ${e.message}")
            }
        }
    }

    /**
     * Switch completion strategy
     */
    private fun switchCompletionStrategy() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val currentStrategy = completionService.getCompletionStrategy()
                val newStrategy = when (currentStrategy) {
                    ZestCompletionProvider.CompletionStrategy.SIMPLE ->
                        ZestCompletionProvider.CompletionStrategy.LEAN
                    ZestCompletionProvider.CompletionStrategy.LEAN ->
                        ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE
                    ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE ->
                        ZestCompletionProvider.CompletionStrategy.SIMPLE
                }

                completionService.setCompletionStrategy(newStrategy)
                showQuickInfo("Switched to ${newStrategy.name} strategy")

            } catch (e: Exception) {
                logger.warn("Failed to switch strategy", e)
                showQuickInfo("Failed to switch strategy: ${e.message}")
            }
        }
    }

    /**
     * Show debug information
     */
    private fun showDebugInfo() {
        try {
            val detailedState = completionService.getDetailedState()
            val cacheStats = completionService.getCacheStats()

            val info = buildString {
                appendLine("=== Zest Status Debug Info ===")
                appendLine("Completion State: ${currentCompletionState.displayText}")
                appendLine("Method Rewrite State: ${currentMethodRewriteState?.displayText ?: "None"}")
                appendLine("Method Rewrite Status: $methodRewriteStatus")
                appendLine()
                appendLine("Detailed State:")
                detailedState.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
                appendLine("Cache: $cacheStats")

                val currentCompletion = completionService.getCurrentCompletion()
                if (currentCompletion != null) {
                    appendLine("\n--- Current Completion ---")
                    appendLine("Text: '${currentCompletion.insertText.take(100)}...'")
                    appendLine("Range: ${currentCompletion.replaceRange}")
                    appendLine("Confidence: ${currentCompletion.confidence}")
                }
            }

            logger.info(info)
            showQuickInfo("Debug info logged to console")

        } catch (e: Exception) {
            logger.warn("Failed to get debug info", e)
            showQuickInfo("Failed to get debug info: ${e.message}")
        }
    }

    /**
     * Show quick info message
     */
    private fun showQuickInfo(message: String) {
        logger.info("Quick info: $message")
        // Could show a temporary notification here if needed
    }

    /**
     * Check for orphaned states and update accordingly
     */
    fun checkForOrphanedState() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val wasStuck = completionService.checkAndFixStuckState()
                if (wasStuck) {
                    updateCompletionState(CompletionState.ERROR)
                    showQuickInfo("Fixed stuck acceptance state!")
                    return@invokeLater
                }

                val hasCompletion = completionService.getCurrentCompletion() != null
                val isEnabled = completionService.isEnabled()
                val detailedState = completionService.getDetailedState()

                logger.debug("Status bar state check: $detailedState")

                when {
                    !isEnabled -> updateCompletionState(CompletionState.ERROR)
                    detailedState["isAcceptingCompletion"] == true -> updateCompletionState(CompletionState.ACCEPTING)
                    hasCompletion -> updateCompletionState(CompletionState.WAITING)
                    detailedState["activeRequestId"] != "null" -> updateCompletionState(CompletionState.REQUESTING)
                    else -> updateCompletionState(CompletionState.IDLE)
                }

            } catch (e: Exception) {
                logger.warn("Error checking for orphaned state", e)
                updateCompletionState(CompletionState.ERROR)
            }
        }
    }

    /**
     * Periodic state sync - check every few seconds for discrepancies
     */
    private fun startPeriodicStateSync() {
        scope.launch {
            while (true) {
                delay(3000) // Check every 3 seconds
                try {
                    checkForOrphanedState()
                } catch (e: Exception) {
                    logger.debug("Error in periodic state sync", e)
                }
            }
        }
    }
}