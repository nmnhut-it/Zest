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

/**
 * Status bar widget showing Zest completion state with icons and refresh functionality
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
    }
    
    // Current state
    private var currentState = CompletionState.IDLE
    
    enum class CompletionState(val displayText: String, val icon: Icon, val tooltip: String) {
        REQUESTING("Requesting...", ICON_REQUESTING, "Zest completion is being requested from AI"),
        WAITING("Ready", ICON_WAITING, "Zest completion is ready - press Tab to accept"),
        ACCEPTING("Accepting...", ICON_ACCEPTING, "Zest completion is being accepted"),
        IDLE("Idle", ICON_IDLE, "Zest completion is idle - ready for new requests"),
        ERROR("Error", ICON_ERROR, "Zest completion has an error or orphaned state")
    }
    
    override fun ID(): String = WIDGET_ID
    
    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        logger.info("ZestCompletionStatusBarWidget installed")
        
        // Initial state update
        updateState(CompletionState.IDLE)
        
        // Listen to completion service events
        setupCompletionServiceListener()
        
        // Start periodic state synchronization
        startPeriodicStateSync()
    }
    
    override fun dispose() {
        super.dispose()
        scope.cancel() // Cancel the coroutine scope
        logger.info("ZestCompletionStatusBarWidget disposed")
    }
    
    // StatusBarWidget.IconPresentation implementation
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    
    override fun getIcon(): Icon = currentState.icon
    
    override fun getTooltipText(): String = "${currentState.tooltip}\n\nClick for options • Strategy: ${getStrategyName()}"
    
    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { mouseEvent ->
            if (mouseEvent.isPopupTrigger || mouseEvent.button == MouseEvent.BUTTON3) {
                showPopupMenu(mouseEvent)
            } else {
                // Left click - show quick info or refresh
                showQuickActions(mouseEvent)
            }
        }
    }
    
    /**
     * Update the widget state
     */
    fun updateState(newState: CompletionState) {
        if (currentState != newState) {
            logger.debug("Completion state changed: ${currentState} -> ${newState}")
            currentState = newState
            
            // Update status bar on EDT
            ApplicationManager.getApplication().invokeLater {
                myStatusBar?.updateWidget(ID())
            }
        }
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
     * Setup listener for completion service events
     */
    private fun setupCompletionServiceListener() {
        project.messageBus.connect().subscribe(ZestInlineCompletionService.Listener.TOPIC, object : ZestInlineCompletionService.Listener {
            override fun loadingStateChanged(loading: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    if (loading) {
                        updateState(CompletionState.REQUESTING)
                    } else {
                        // Check if we have a completion ready
                        val hasCompletion = completionService.getCurrentCompletion() != null
                        updateState(if (hasCompletion) CompletionState.WAITING else CompletionState.IDLE)
                    }
                }
            }
            
            override fun completionDisplayed(context: ZestInlineCompletionRenderer.RenderingContext) {
                ApplicationManager.getApplication().invokeLater {
                    updateState(CompletionState.WAITING)
                }
            }
            
            override fun completionAccepted(type: ZestInlineCompletionService.AcceptType) {
                ApplicationManager.getApplication().invokeLater {
                    updateState(CompletionState.ACCEPTING)
                    
                    // Auto-transition back to idle after acceptance using coroutines
                    scope.launch {
                        delay(500) // Wait 500ms
                        ApplicationManager.getApplication().invokeLater {
                            val hasCompletion = completionService.getCurrentCompletion() != null
                            updateState(if (hasCompletion) CompletionState.WAITING else CompletionState.IDLE)
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
        
        // Add refresh action
        group.add(object : AnAction("Refresh & Clear State", "Clear any orphaned completion state and refresh", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshCompletionState()
            }
        })
        
        // Add strategy switch action
        group.add(object : AnAction("Switch Strategy", "Switch between SIMPLE and LEAN completion strategies", AllIcons.Actions.ToggleVisibility) {
            override fun actionPerformed(e: AnActionEvent) {
                switchCompletionStrategy()
            }
        })
        
        group.addSeparator()
        
        // Add immediate state check action
        group.add(object : AnAction("Check State Now", "Immediately check and fix completion state", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                checkForOrphanedState()
                showQuickInfo("State check completed")
            }
        })
        
        // Add manual reset action for stuck states
        group.add(object : AnAction("Force Reset Flags", "Force reset all completion flags (emergency recovery)", AllIcons.Actions.ForceRefresh) {
            override fun actionPerformed(e: AnActionEvent) {
                completionService.forceRefreshState()
                updateState(CompletionState.IDLE)
                showQuickInfo("All flags force reset!")
            }
        })
        
        // Add debug info action
        group.add(object : AnAction("Show Debug Info", "Show current completion state information", AllIcons.Actions.Show) {
            override fun actionPerformed(e: AnActionEvent) {
                showDebugInfo()
            }
        })
        
        val dataContext = SimpleDataContext.getProjectContext(project)
        val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Zest Completion",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )
        
        // Show popup relative to the status bar
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
        when (currentState) {
            CompletionState.ERROR -> {
                refreshCompletionState()
                checkForOrphanedState()
            }
            CompletionState.REQUESTING -> {
                // Show info about current request
                showQuickInfo("Completion request in progress...")
            }
            CompletionState.WAITING -> {
                showQuickInfo("Completion ready - Press Tab to accept")
            }
            CompletionState.ACCEPTING -> {
                // Check if this state is stuck
                val detailedState = completionService.getDetailedState()
                val timeSinceAccept = detailedState["timeSinceAccept"] as? Long ?: 0L
                if (timeSinceAccept > 3000L) {
                    showQuickInfo("Acceptance stuck - checking state...")
                    checkForOrphanedState()
                } else {
                    showQuickInfo("Accepting completion...")
                }
            }
            CompletionState.IDLE -> {
                showQuickInfo("Ready for new completion request")
                // Force check state to ensure it's really idle
                checkForOrphanedState()
            }
        }
    }
    
    /**
     * Refresh and clear completion state
     */
    private fun refreshCompletionState() {
        logger.info("Refreshing completion state via status bar...")
        
        ApplicationManager.getApplication().invokeLater {
            try {
                // Use the completion service's force refresh method
                completionService.forceRefreshState()
                
                // Update to idle state
                updateState(CompletionState.IDLE)
                
                showQuickInfo("Completion state refreshed!")
                
            } catch (e: Exception) {
                logger.warn("Failed to refresh completion state", e)
                updateState(CompletionState.ERROR)
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
                appendLine("=== Zest Completion Debug Info ===")
                appendLine("Widget State: ${currentState.displayText}")
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
     * Show quick info tooltip
     */
    private fun showQuickInfo(message: String) {
        // You could implement a temporary tooltip or notification here
        logger.info("Quick info: $message")
    }
    
    /**
     * Check for orphaned states and update accordingly
     */
    fun checkForOrphanedState() {
        ApplicationManager.getApplication().invokeLater {
            try {
                // First check if completion service has stuck state
                val wasStuck = completionService.checkAndFixStuckState()
                if (wasStuck) {
                    updateState(CompletionState.ERROR)
                    showQuickInfo("Fixed stuck acceptance state!")
                    return@invokeLater
                }
                
                val hasCompletion = completionService.getCurrentCompletion() != null
                val isEnabled = completionService.isEnabled()
                val detailedState = completionService.getDetailedState()
                
                logger.debug("Status bar state check: $detailedState")
                
                when {
                    !isEnabled -> updateState(CompletionState.ERROR)
                    detailedState["isAcceptingCompletion"] == true -> updateState(CompletionState.ACCEPTING)
                    hasCompletion -> updateState(CompletionState.WAITING)
                    detailedState["activeRequestId"] != "null" -> updateState(CompletionState.REQUESTING)
                    else -> updateState(CompletionState.IDLE)
                }
                
            } catch (e: Exception) {
                logger.warn("Error checking for orphaned state", e)
                updateState(CompletionState.ERROR)
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
