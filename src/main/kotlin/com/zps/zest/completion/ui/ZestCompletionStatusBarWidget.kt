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
import kotlinx.coroutines.*
import java.awt.Component
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.awt.RelativePoint
import java.awt.Point

/**
 * Enhanced status bar widget showing both completion and method rewrite state
 */
class ZestCompletionStatusBarWidget(project: Project) : EditorBasedWidget(project) {

    private val logger = Logger.getInstance(ZestCompletionStatusBarWidget::class.java)
    private val completionService by lazy { project.getService(ZestInlineCompletionService::class.java) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val WIDGET_ID = "ZestCompletionStatus"

        // Icons for different states
        private val ICON_REQUESTING = AllIcons.Process.Step_1 // Spinning icon for requesting
        private val ICON_WAITING = AllIcons.General.BalloonInformation // Info icon for waiting
        private val ICON_ACCEPTING = AllIcons.Process.Step_2 // Another process icon for accepting
        private val ICON_IDLE = AllIcons.General.InspectionsOK // Green check for idle
        private val ICON_ERROR = AllIcons.General.Error // Red error icon
        private val ICON_WARNING = AllIcons.General.Warning // Yellow warning icon

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
    private var displayText = "Zest"

    enum class CompletionState(val displayText: String, val icon: Icon, val tooltip: String) {
        REQUESTING("⏳ Requesting...", ICON_REQUESTING, "Zest completion is being requested from AI"),
        LLM_RESPONDING("🤖 LLM Responding...", ICON_REQUESTING, "Receiving response from AI model"),
        PARSING("🔍 Parsing...", ICON_REQUESTING, "Parsing AI response"),
        RESPONSE_EMPTY("❌ Empty Response", ICON_ERROR, "AI returned empty response"),
        NO_COMPLETION("⚠️ No Completion", ICON_WARNING, "No valid completion after parsing"),
        CACHE_HIT("💾 From Cache", ICON_WAITING, "Using cached completion"),
        WAITING("💡 Ready", ICON_WAITING, "Zest completion is ready - press Tab to accept"),
        ACCEPTING("⚡ Accepting...", ICON_ACCEPTING, "Zest completion is being accepted"),
        IDLE("💤 Idle", ICON_IDLE, "Zest completion is idle - ready for new requests"),
        DISABLED("⏸️ Disabled", ICON_IDLE, "Zest completion is disabled in settings"),
        ERROR("❌ Error", ICON_ERROR, "Zest completion has an error or orphaned state - Click to reset"),
        RATE_LIMITED("⏱️ Rate Limited", ICON_WAITING, "Too many requests - waiting before next request")
    }

    enum class MethodRewriteState(val displayText: String, val icon: Icon, val tooltip: String) {
        ANALYZING("🧠 Analyzing...", ICON_METHOD_ANALYZING, "Analyzing method structure and context"),
        AI_QUERYING("🤖 Processing...", ICON_METHOD_AI_QUERY, "Querying AI model for method improvements"),
        DIFF_READY("✅ Ready", ICON_METHOD_DIFF_READY, "Method rewrite ready for review - press TAB to accept"),
        APPLYING("🔧 Applying...", ICON_METHOD_APPLYING, "Applying method changes to code"),
        COMPLETED("🎉 Done", ICON_IDLE, "Method rewrite completed successfully")
    }

    override fun ID(): String = WIDGET_ID

    // Override getPresentation to return our TextPresentation implementation
    override fun getPresentation(type: StatusBarWidget.PlatformType): StatusBarWidget.WidgetPresentation? {
        return TextPresentation()
    }

    // Inner class implementing TextPresentation
    private inner class TextPresentation : StatusBarWidget.TextPresentation {
        override fun getText(): String = displayText

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        override fun getTooltipText(): String? {
            val primaryState = if (currentMethodRewriteState != null) {
                "Method Rewrite: ${currentMethodRewriteState!!.tooltip}"
            } else {
                "Completion: ${currentCompletionState.tooltip}"
            }

            val statusInfo = if (methodRewriteStatus.isNotEmpty() && !methodRewriteStatus.contains(":")) {
                "\nLast Event: $methodRewriteStatus"
            } else if (methodRewriteStatus.contains(":")) {
                "\n$methodRewriteStatus"
            } else {
                ""
            }

            // Strategy display removed
            // val strategy = "\nStrategy: ${getStrategyName()}"
            
            // Add request state info
            val detailedState = try {
                completionService.getDetailedState()
            } catch (e: Exception) {
                emptyMap()
            }
            
            val requestInfo = buildString {
                val requestState = detailedState["currentRequestState"] as? String
                when (requestState) {
                    "WAITING" -> append("\n⏱️ Waiting to trigger completion...")
                    "REQUESTING" -> append("\n⏳ Requesting completion from AI...")
                    "DISPLAYING" -> append("\n💡 Completion ready to accept")
                }
                
                val requestsPerMinute = detailedState["requestsInLastMinute"] as? Int
                if (requestsPerMinute != null && requestsPerMinute > 0) {
                    append("\nRequests/min: $requestsPerMinute")
                }
                
                val timeSinceLastRequest = detailedState["timeSinceLastRequest"] as? Long
                if (timeSinceLastRequest != null && timeSinceLastRequest < 5000) {
                    append("\nLast request: ${timeSinceLastRequest}ms ago")
                }
            }

            return "$primaryState$statusInfo$requestInfo\n\nClick for options • Right-click for menu"
        }

        override fun getClickConsumer(): Consumer<MouseEvent>? {
            return Consumer { mouseEvent ->
                try {
                    logger.info("Widget clicked! Button: ${mouseEvent.button}, Modifiers: ${mouseEvent.modifiersEx}")

                    when {
                        // Right-click or platform-specific popup trigger
                        mouseEvent.isPopupTrigger -> {
                            logger.info("Popup trigger detected")
                            showPopupMenu(mouseEvent)
                        }
                        // Explicit right-click check (Button3)
                        mouseEvent.button == MouseEvent.BUTTON3 -> {
                            logger.info("Right click detected")
                            showPopupMenu(mouseEvent)
                        }
                        // Left click (Button1)
                        mouseEvent.button == MouseEvent.BUTTON1 -> {
                            logger.info("Left click detected")
                            handleLeftClick(mouseEvent)
                        }
                        // Middle click (Button2)
                        mouseEvent.button == MouseEvent.BUTTON2 -> {
                            logger.info("Middle click detected")
                            refreshCompletionState()
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
        logger.info("ZestCompletionStatusBarWidget installed")

        // Initial state update - check if enabled
        if (!completionService.isEnabled()) {
            updateCompletionState(CompletionState.DISABLED)
        } else {
            updateCompletionState(CompletionState.IDLE)
        }

        // Listen to completion service events
        setupCompletionServiceListener()

        // Start periodic state synchronization
        startPeriodicStateSync()

        // Force initial widget update
        ApplicationManager.getApplication().invokeLater {
            statusBar.updateWidget(ID())
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
        logger.info("ZestCompletionStatusBarWidget disposed")
    }

    /**
     * Handle left click based on current state
     */
    private fun handleLeftClick(mouseEvent: MouseEvent) {
        when {
            // Disabled state - offer to enable
            currentCompletionState == CompletionState.DISABLED -> {
                showBalloon("Inline completion is disabled. Enable it in settings.", MessageType.INFO, mouseEvent)
            }
            // Error state - click to reset (this is what you remembered!)
            currentCompletionState == CompletionState.ERROR -> {
                logger.info("Resetting error state on click")
                refreshCompletionState()
                showBalloon("State reset!", MessageType.INFO, mouseEvent)
            }
            // Method rewrite states
            currentMethodRewriteState != null -> {
                when (currentMethodRewriteState!!) {
                    MethodRewriteState.ANALYZING -> showBalloon(
                        "Method analysis in progress...",
                        MessageType.INFO,
                        mouseEvent
                    )

                    MethodRewriteState.AI_QUERYING -> showBalloon(
                        "AI processing method improvements...",
                        MessageType.INFO,
                        mouseEvent
                    )

                    MethodRewriteState.DIFF_READY -> showBalloon(
                        "Press TAB to accept, ESC to reject",
                        MessageType.WARNING,
                        mouseEvent
                    )

                    MethodRewriteState.APPLYING -> showBalloon(
                        "Applying method changes...",
                        MessageType.INFO,
                        mouseEvent
                    )

                    MethodRewriteState.COMPLETED -> showBalloon(
                        "Method rewrite completed!",
                        MessageType.INFO,
                        mouseEvent
                    )
                }
            }
            // Rate limited state - show info
            currentCompletionState == CompletionState.RATE_LIMITED -> {
                val detailedState = try {
                    completionService.getDetailedState()
                } catch (e: Exception) {
                    emptyMap()
                }
                val requestsPerMinute = detailedState["requestsInLastMinute"] as? Int ?: 0
                showBalloon(
                    "Rate limited: $requestsPerMinute requests in the last minute. Please wait...",
                    MessageType.WARNING,
                    mouseEvent
                )
            }
            // Completion states
            currentCompletionState == CompletionState.REQUESTING -> {
                showBalloon("Completion request in progress...", MessageType.INFO, mouseEvent)
            }

            currentCompletionState == CompletionState.WAITING -> {
                showBalloon("Press Tab to accept completion", MessageType.WARNING, mouseEvent)
            }

            currentCompletionState == CompletionState.ACCEPTING -> {
                showBalloon("Accepting completion...", MessageType.INFO, mouseEvent)
            }

            else -> {
                // Default action - show quick menu
                showQuickMenu(mouseEvent)
            }
        }
    }

    /**
     * Show a balloon notification at the widget location
     */
    private fun showBalloon(message: String, messageType: MessageType, mouseEvent: MouseEvent) {
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, messageType.defaultIcon, messageType.popupBackground) { _ -> }
            .setFadeoutTime(2500)
            .createBalloon()

        val point = RelativePoint(mouseEvent.component, Point(mouseEvent.x, mouseEvent.y - 30))
        balloon.show(point, Balloon.Position.above)
    }

    /**
     * Show quick menu for left click
     */
    private fun showQuickMenu(mouseEvent: MouseEvent) {
        val group = DefaultActionGroup()

        // Disabled - Switch Strategy removed
        // group.add(object : AnAction(
        //     "Switch Strategy (${getStrategyName()})",
        //     "Switch between completion strategies",
        //     AllIcons.Actions.ChangeView
        // ) {
        //     override fun actionPerformed(e: AnActionEvent) {
        //         switchCompletionStrategy()
        //     }
        // })

        group.add(object : AnAction("Refresh State", "Clear any orphaned state", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshCompletionState()
            }
        })

        if (currentMethodRewriteState != null) {
            group.addSeparator()
            group.add(object : AnAction("Cancel Method Rewrite", "Cancel current operation", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    cancelMethodRewrite()
                }
            })
        }

        val dataContext = SimpleDataContext.getProjectContext(project)
        val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Quick Actions",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )

        popup.show(RelativePoint(mouseEvent))
    }

    /**
     * Update the completion state
     */
    fun updateCompletionState(newState: CompletionState) {
        if (currentCompletionState != newState) {
            logger.debug("Completion state changed: ${currentCompletionState} -> ${newState}")
            currentCompletionState = newState
            updateDisplay()
        }
    }

    /**
     * Update status with specific debug message
     * This is called from the completion service to show detailed status
     */
    fun updateDebugStatus(message: String) {
        ApplicationManager.getApplication().invokeLater {
            // Parse the message to determine the appropriate state
            when {
                message.contains("LLM Query:", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.REQUESTING)
                }
                message.contains("LLM Response:", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.LLM_RESPONDING)
                }
                message.contains("Parsing", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.PARSING)
                }
                message.contains("No response", ignoreCase = true) || 
                message.contains("returned null", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.RESPONSE_EMPTY)
                }
                message.contains("Empty Completion", ignoreCase = true) || 
                message.contains("returned empty", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.RESPONSE_EMPTY)
                }
                message.contains("No Completion", ignoreCase = true) || 
                message.contains("No suggestions", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.NO_COMPLETION)
                }
                message.contains("Parse Failed", ignoreCase = true) || 
                message.contains("No valid completion", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.NO_COMPLETION)
                }
                message.contains("Cache Hit", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.CACHE_HIT)
                }
                message.contains("Completion Ready", ignoreCase = true) || 
                message.contains("Press Tab", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.WAITING)
                }
                message.contains("Rate Limited", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.RATE_LIMITED)
                }
                message.contains("Error", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.ERROR)
                }
                message.contains("Accepted", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.ACCEPTING)
                }
                message.contains("Dismissed", ignoreCase = true) || 
                message.contains("State Refreshed", ignoreCase = true) -> {
                    updateCompletionState(CompletionState.IDLE)
                }
            }
            
            // Store the full message for tooltip
            methodRewriteStatus = message
        }
    }
    
    /**
     * Show method rewrite state and optionally clear completion state focus
     */
    fun updateMethodRewriteState(newState: MethodRewriteState, status: String = "") {
        logger.info("Method rewrite state: ${newState.displayText} - $status")
        currentMethodRewriteState = newState
        methodRewriteStatus = status
        updateDisplay()

        // Auto-clear completed state after some time
        if (newState == MethodRewriteState.COMPLETED) {
            scope.launch {
                delay(3000) // Show for 3 seconds
                clearMethodRewriteState()
            }
        }
    }

    /**
     * Update method rewrite status text
     */
    fun updateMethodRewriteStatus(status: String) {
        methodRewriteStatus = status
        updateDisplay()

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
                logger.warn("Method rewrite failed: $status")
            }
        }
    }

    /**
     * Clear method rewrite state
     */
    fun clearMethodRewriteState() {
        currentMethodRewriteState = null
        methodRewriteStatus = ""
        updateDisplay()
    }

    /**
     * Update the display text based on current state
     */
    private fun updateDisplay() {
        // Priority: Method rewrite state takes precedence over completion state
        displayText = if (currentMethodRewriteState != null) {
            "Zest: ${currentMethodRewriteState!!.displayText}"
        } else {
            when (currentCompletionState) {
                CompletionState.REQUESTING -> "Zest: ⏳ Requesting..."
                CompletionState.LLM_RESPONDING -> "Zest: 🤖 LLM Responding..."
                CompletionState.PARSING -> "Zest: 🔍 Parsing..."
                CompletionState.RESPONSE_EMPTY -> "Zest: ❌ Empty Response"
                CompletionState.NO_COMPLETION -> "Zest: ⚠️ No Completion"
                CompletionState.CACHE_HIT -> "Zest: 💾 From Cache"
                CompletionState.WAITING -> "Zest: 💡 Ready"
                CompletionState.ACCEPTING -> "Zest: ⚡ Accepting..."
                CompletionState.IDLE -> "Zest" // Just "Zest" when idle/waiting for timer
                CompletionState.DISABLED -> "Zest: ⏸️ Disabled"
                CompletionState.ERROR -> "Zest: ❌ Error"
                CompletionState.RATE_LIMITED -> "Zest: ⏱️ Rate Limited"
            }
        }

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
        val connection = project.messageBus.connect(this)
        connection.subscribe(ZestInlineCompletionService.Listener.TOPIC, object : ZestInlineCompletionService.Listener {
            override fun loadingStateChanged(loading: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    // Check if service is enabled first
                    if (!completionService.isEnabled()) {
                        updateCompletionState(CompletionState.DISABLED)
                        return@invokeLater
                    }

                    if (loading) {
                        // Only show requesting if we're actually requesting (not just waiting)
                        val detailedState = try {
                            completionService.getDetailedState()
                        } catch (e: Exception) {
                            emptyMap()
                        }
                        
                        val requestState = detailedState["currentRequestState"] as? String
                        if (requestState == "REQUESTING") {
                            updateCompletionState(CompletionState.REQUESTING)
                        }
                        // Don't change state for WAITING - keep it as IDLE
                    } else {
                        // Get more detailed state info
                        val detailedState = try {
                            completionService.getDetailedState()
                        } catch (e: Exception) {
                            emptyMap()
                        }
                        
                        val requestState = detailedState["currentRequestState"] as? String
                        val hasCompletion = completionService.getCurrentCompletion() != null
                        
                        when {
                            hasCompletion -> updateCompletionState(CompletionState.WAITING)
                            requestState == "WAITING" -> updateCompletionState(CompletionState.IDLE)
                            requestState == "REQUESTING" -> updateCompletionState(CompletionState.REQUESTING)
                            else -> updateCompletionState(CompletionState.IDLE)
                        }
                    }
                }
            }

            override fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext) {
                ApplicationManager.getApplication().invokeLater {
                    if (completionService.isEnabled()) {
                        updateCompletionState(CompletionState.WAITING)
                    }
                }
            }

            // Removed old completionAccepted method - now tracked through state changes
        })
    }

    /**
     * Show popup menu with options (right-click menu)
     */
    private fun showPopupMenu(mouseEvent: MouseEvent) {
        val group = DefaultActionGroup()

        // Method rewrite actions
        if (currentMethodRewriteState != null) {
            group.add(object : AnAction(
                "Cancel Method Rewrite",
                "Cancel the current method rewrite operation",
                AllIcons.Actions.Cancel
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    cancelMethodRewrite()
                }
            })

            if (currentMethodRewriteState == MethodRewriteState.DIFF_READY) {
                group.add(object : AnAction("View Changes", "View the proposed changes", AllIcons.Actions.Diff) {
                    override fun actionPerformed(e: AnActionEvent) {
                        showBalloon("Use TAB key to accept changes", MessageType.INFO, mouseEvent)
                    }
                })
            }

            group.addSeparator()
        }

        // Standard completion actions
        group.add(object : AnAction(
            "Refresh & Clear State",
            "Clear any orphaned completion state and refresh",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshCompletionState()
            }
        })

        // Disabled - Switch Strategy removed
        // group.add(object :
        //     AnAction("Switch Strategy", "Switch between completion strategies", AllIcons.Actions.ToggleVisibility) {
        //     override fun actionPerformed(e: AnActionEvent) {
        //         switchCompletionStrategy()
        //     }
        // })

        group.addSeparator()

        group.add(object : AnAction("Show Debug Info", "Show current state information", AllIcons.Actions.Show) {
            override fun actionPerformed(e: AnActionEvent) {
                showDebugInfo()
            }
        })

        group.add(object : AnAction("Open Settings", "Open Zest plugin settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                // TODO: Implement settings navigation
                showBalloon("Settings not implemented yet", MessageType.INFO, mouseEvent)
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

        popup.show(RelativePoint(mouseEvent))
    }

    /**
     * Cancel method rewrite operation - DEPRECATED: Method rewrites now happen in ChatUIService
     */
    private fun cancelMethodRewrite() {
        // Method rewrites no longer use this system - they happen in ChatUIService
        // Just clear the state
        clearMethodRewriteState()
        logger.info("Method rewrite state cleared")
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
                logger.info("State refreshed successfully")

            } catch (e: Exception) {
                logger.warn("Failed to refresh completion state", e)
                updateCompletionState(CompletionState.ERROR)
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
                val newStrategy = ZestCompletionProvider.CompletionStrategy.LEAN;

                completionService.setCompletionStrategy(newStrategy)
                logger.info("Switched to ${newStrategy.name} strategy")
                refreshWidget()

            } catch (e: Exception) {
                logger.warn("Failed to switch strategy", e)
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
                appendLine("Widget State:")
                appendLine("  Completion State: ${currentCompletionState.displayText}")
                appendLine("  Method Rewrite State: ${currentMethodRewriteState?.displayText ?: "None"}")
                appendLine("  Method Rewrite Status: $methodRewriteStatus")
                appendLine()
                appendLine("Service State:")
                appendLine("  Request State: ${detailedState["currentRequestState"]}")
                appendLine("  Active Request ID: ${detailedState["activeRequestId"]}")
                appendLine("  Is Accepting: ${detailedState["isAcceptingCompletion"]}")
                appendLine("  Is Programmatic Edit: ${detailedState["isProgrammaticEdit"]}")
                appendLine()
                appendLine("Rate Limiting:")
                appendLine("  Requests/min: ${detailedState["requestsInLastMinute"]}")
                appendLine("  Time since last request: ${detailedState["timeSinceLastRequest"]}ms")
                appendLine("  Time since last accept: ${detailedState["timeSinceAccept"]}ms")
                appendLine()
                appendLine("Configuration:")
                // Strategy display removed
                // appendLine("  Strategy: ${detailedState["strategy"]}")
                appendLine("  Enabled: ${detailedState["isEnabled"]}")
                appendLine("  Auto-trigger: ${detailedState["autoTrigger"]}")
                appendLine()
                appendLine("Cache: $cacheStats")

                val currentCompletion = completionService.getCurrentCompletion()
                if (currentCompletion != null) {
                    appendLine("\n--- Current Completion ---")
                    appendLine("Text: '${currentCompletion.insertText.take(100)}...'")
                    appendLine("Range: ${currentCompletion.replaceRange}")
                    appendLine("Confidence: ${currentCompletion.confidence}")
                    appendLine("ID: ${currentCompletion.completionId}")
                }
            }

            logger.info(info)
            
            // Show in a message dialog too
            com.intellij.openapi.ui.Messages.showMessageDialog(
                project,
                info,
                "Zest Debug Information",
                com.intellij.openapi.ui.Messages.getInformationIcon()
            )

        } catch (e: Exception) {
            logger.warn("Failed to get debug info", e)
        }
    }

    /**
     * Check for orphaned states and update accordingly
     */
    fun checkForOrphanedState() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val isEnabled = completionService.isEnabled()

                // If completion is disabled, show disabled state and return
                if (!isEnabled) {
                    updateCompletionState(CompletionState.DISABLED)
                    return@invokeLater
                }

                val wasStuck = completionService.checkAndFixStuckState()
                if (wasStuck) {
                    updateCompletionState(CompletionState.ERROR)
                    logger.info("Fixed stuck acceptance state!")
                    return@invokeLater
                }

                val hasCompletion = completionService.getCurrentCompletion() != null
                val detailedState = completionService.getDetailedState()

                logger.debug("Status bar state check: $detailedState")

                // Check for rate limiting
                val requestsPerMinute = detailedState["requestsInLastMinute"] as? Int ?: 0
                if (requestsPerMinute >= 25) { // Show rate limited when approaching limit
                    updateCompletionState(CompletionState.RATE_LIMITED)
                    return@invokeLater
                }

                // Use the new currentRequestState from detailed state
                val requestState = detailedState["currentRequestState"] as? String

                when {
                    detailedState["isAcceptingCompletion"] == true -> updateCompletionState(CompletionState.ACCEPTING)
                    hasCompletion -> updateCompletionState(CompletionState.WAITING)
                    requestState == "REQUESTING" -> updateCompletionState(CompletionState.REQUESTING)
                    requestState == "WAITING" -> updateCompletionState(CompletionState.IDLE) // Timer waiting shows as idle
                    requestState == "DISPLAYING" -> updateCompletionState(if (hasCompletion) CompletionState.WAITING else CompletionState.IDLE)
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
            while (isActive) {
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