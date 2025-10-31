package com.zps.zest.testgen.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.StateMachineTestGenerationService
import com.zps.zest.testgen.model.TestPlan
import com.zps.zest.testgen.statemachine.*
import com.zps.zest.testgen.ui.dialogs.MergedTestPreviewDialog
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.langchain4j.ui.DialogManager
import com.zps.zest.testgen.ui.panels.*
import com.zps.zest.testgen.ui.model.*
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Clean state machine-based test generation editor.
 * Provides clear state visualization and manual control buttons.
 */
class StateMachineTestGenerationEditor(
    private val project: Project,
    private val virtualFile: TestGenerationVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    companion object {
        private val LOG = Logger.getInstance(StateMachineTestGenerationEditor::class.java)

        // Debug flags for console logging
        private const val DEBUG_SESSION_VALIDATION = true
        private const val DEBUG_UI_UPDATES = true
        private const val DEBUG_BUTTON_STATE = true
        private const val DEBUG_EVENT_TIMING = true
        private const val WARN_POTENTIAL_ERRORS = true  // Warning logs for error-prone areas
    }
    
    private val testGenService = project.getService(StateMachineTestGenerationService::class.java)
    private val component: JPanel

    // Thread-safe access to current session state
    @Volatile
    private var currentSessionId: String? = null
    @Volatile
    private var currentStateMachine: TestGenerationStateMachine? = null
    private val stateMachineLock = Any()

    private var blinkTimer: javax.swing.Timer? = null
    private var chatMemoryUpdateTimer: javax.swing.Timer? = null
    private var mergerChatDialog: ChatMemoryDialog? = null
    private var currentMergerAgent: com.zps.zest.testgen.agents.AITestMergerAgent? = null
    
    // UI Components
    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var stateDisplayPanel: JPanel
    private lateinit var stateLabel: JBLabel
    private lateinit var stateDescription: JBLabel
    private lateinit var statusIndicator: JLabel
    private lateinit var actionPrompt: JLabel
    
    // Real-time display panels
    private lateinit var contextDisplayPanel: ContextDisplayPanel
    private lateinit var testPlanDisplayPanel: TestPlanDisplayPanel
    private lateinit var generatedTestsPanel: GeneratedTestsPanel
    private lateinit var testMergingPanel: TestMergingPanel
    private lateinit var streamingHelper: StreamingUIHelper
    
    // Simplified control system
    private lateinit var primaryActionButton: JButton
    private lateinit var cancelButton: JButton
    private var totalTokensUsed = 0
    
    // Action banner components
    private lateinit var actionBanner: JPanel
    private lateinit var actionMessage: JBLabel
    private lateinit var embeddedActionButton: JButton
    
    // Event listener for state machine events and streaming updates
    private val eventListener = object : TestGenerationEventListener, StreamingEventListener {
        override fun onStateChanged(event: TestGenerationEvent.StateChanged) {
            val eventTime = System.currentTimeMillis()

            // CRITICAL BUG FIX: Allow events from real session even if we're in "STARTING" state
            // The sentinel "STARTING" value blocks all real events until CompletableFuture completes!
            val isStartingSentinel = currentSessionId == "STARTING"
            val shouldAccept = currentSessionId == null || event.sessionId == currentSessionId || isStartingSentinel

            if (!shouldAccept) {
                if (DEBUG_SESSION_VALIDATION) {
                    println("[DEBUG_SESSION] StateChanged IGNORED: received=${event.sessionId}, current=$currentSessionId, state=${event.newState}")
                }
                LOG.debug("Ignoring state change event from different session: ${event.sessionId} != $currentSessionId")
                return
            }

            // Update currentSessionId if we're in STARTING state
            if (isStartingSentinel && currentSessionId == "STARTING") {
                synchronized(stateMachineLock) {
                    if (WARN_POTENTIAL_ERRORS) {
                        println("[WARN_SESSION_UPDATE] Updating sessionId from STARTING to ${event.sessionId}")
                    }
                    currentSessionId = event.sessionId
                }
            }

            if (DEBUG_SESSION_VALIDATION) {
                println("[DEBUG_SESSION] StateChanged ACCEPTED: sessionId=${event.sessionId}, ${event.oldState} → ${event.newState}, reason=${event.reason}")
            }

            SwingUtilities.invokeLater {
                if (DEBUG_EVENT_TIMING) {
                    val delay = System.currentTimeMillis() - eventTime
                    if (delay > 100) {
                        println("[DEBUG_UI_DELAY] StateChanged delay: ${delay}ms for ${event.oldState} → ${event.newState}")
                    }
                }

                updateStateDisplay(event.newState)
                updateControlButtons()

                // Update agent chat memories when state changes
                currentSessionId?.let { updateAgentChatMemories(it) }

                // Stop periodic updates if generation finished
                if (event.newState == TestGenerationState.COMPLETED ||
                    event.newState == TestGenerationState.FAILED ||
                    event.newState == TestGenerationState.CANCELLED) {
                    if (chatMemoryUpdateTimer != null) {
                        if (WARN_POTENTIAL_ERRORS && chatMemoryUpdateTimer?.isRunning == false) {
                            println("[WARN_TIMER_STATE] Timer already stopped for terminal state ${event.newState}, sessionId=${event.sessionId}")
                        }
                    }
                    stopChatMemoryPeriodicUpdates()
                }

                val autoFlowStatus = if (currentStateMachine?.isAutoFlowEnabled == true) " [auto-flow]" else ""
                logEvent("State: ${event.oldState} → ${event.newState}$autoFlowStatus" +
                        if (event.reason != null) " (${event.reason})" else "")
            }
        }
        
        override fun onActivityLogged(event: TestGenerationEvent.ActivityLogged) {
            // CRITICAL BUG FIX: Allow events from real session even if we're in "STARTING" state
            val isStartingSentinel = currentSessionId == "STARTING"
            val shouldAccept = currentSessionId == null || event.sessionId == currentSessionId || isStartingSentinel

            if (!shouldAccept) {
                if (DEBUG_SESSION_VALIDATION) {
                    println("[DEBUG_SESSION] ActivityLogged IGNORED: received=${event.sessionId}, current=$currentSessionId")
                }
                return
            }

            // Update currentSessionId if we're in STARTING state
            if (isStartingSentinel && currentSessionId == "STARTING") {
                synchronized(stateMachineLock) {
                    currentSessionId = event.sessionId
                }
            }

            SwingUtilities.invokeLater {
                logEvent("Activity: ${event.message}")
                clearActionPrompt()
            }
        }
        
        override fun onErrorOccurred(event: TestGenerationEvent.ErrorOccurred) {
            // CRITICAL BUG FIX: Allow events from real session even if we're in "STARTING" state
            val isStartingSentinel = currentSessionId == "STARTING"
            val shouldAccept = currentSessionId == null || event.sessionId == currentSessionId || isStartingSentinel

            if (!shouldAccept) {
                if (DEBUG_SESSION_VALIDATION) {
                    println("[DEBUG_SESSION] ErrorOccurred IGNORED: received=${event.sessionId}, current=$currentSessionId")
                }
                return
            }

            // Update currentSessionId if we're in STARTING state
            if (isStartingSentinel && currentSessionId == "STARTING") {
                synchronized(stateMachineLock) {
                    currentSessionId = event.sessionId
                }
            }

            if (DEBUG_SESSION_VALIDATION) {
                println("[DEBUG_SESSION] ErrorOccurred ACCEPTED: sessionId=${event.sessionId}, error=${event.errorMessage}, recoverable=${event.isRecoverable}")
            }

            SwingUtilities.invokeLater {
                updateControlButtons()
                updateTokenDisplay()
                logEvent("ERROR: ${event.errorMessage}" +
                        if (event.isRecoverable) " (recoverable)" else " (fatal)")

                if (event.isRecoverable) {
                    // Show recoverable error prompt
                    val result = Messages.showYesNoDialog(
                        project,
                        "Test generation encountered an error:\n\n${event.errorMessage}\n\nWould you like to retry this step?",
                        "Error - Retry?",
                        "Retry", "Skip",
                        Messages.getQuestionIcon()
                    )
                    
                    currentSessionId?.let { sessionId ->
                        if (result == Messages.YES) {
                            testGenService.retryCurrentState(sessionId)
                            logEvent("User chose to retry after error")
                        } else {
                            if (testGenService.canSkip(sessionId)) {
                                testGenService.skipCurrentState(sessionId, "User skipped after error")
                                logEvent("User chose to skip after error")
                            }
                        }
                    }
                } else {
                    Messages.showErrorDialog(project, 
                        "Fatal error: ${event.errorMessage}", 
                        "Test Generation Failed")
                }
            }
        }
        
        override fun onStepCompleted(event: TestGenerationEvent.StepCompleted) {
            // CRITICAL BUG FIX: Allow events from real session even if we're in "STARTING" state
            val isStartingSentinel = currentSessionId == "STARTING"
            val shouldAccept = currentSessionId == null || event.sessionId == currentSessionId || isStartingSentinel

            if (!shouldAccept) {
                if (DEBUG_SESSION_VALIDATION) {
                    println("[DEBUG_SESSION] StepCompleted IGNORED: received=${event.sessionId}, current=$currentSessionId")
                }
                return
            }

            // Update currentSessionId if we're in STARTING state
            if (isStartingSentinel && currentSessionId == "STARTING") {
                synchronized(stateMachineLock) {
                    currentSessionId = event.sessionId
                }
            }

            SwingUtilities.invokeLater {
                updateControlButtons()
                updateTokenDisplay()
                logEvent("✓ Completed: ${event.summary}")

                // Auto-show preview dialog when fixing completes
                if (event.completedState == TestGenerationState.FIXING_TESTS) {
                    showFinalResult()
                }
            }
        }
        
        override fun onUserInputRequired(event: TestGenerationEvent.UserInputRequired) {
            // CRITICAL BUG FIX: Allow events from real session even if we're in "STARTING" state
            val isStartingSentinel = currentSessionId == "STARTING"
            val shouldAccept = currentSessionId == null || event.sessionId == currentSessionId || isStartingSentinel

            if (!shouldAccept) {
                if (DEBUG_SESSION_VALIDATION) {
                    println("[DEBUG_SESSION] UserInputRequired IGNORED: received=${event.sessionId}, current=$currentSessionId")
                }
                return
            }

            // Update currentSessionId if we're in STARTING state
            if (isStartingSentinel && currentSessionId == "STARTING") {
                synchronized(stateMachineLock) {
                    currentSessionId = event.sessionId
                }
            }

            if (DEBUG_UI_UPDATES) {
                println("[DEBUG_UI] UserInputRequired: type=${event.inputType}, prompt=${event.prompt}, sessionId=${event.sessionId}")
            }

            SwingUtilities.invokeLater {
                updateButtonForState(null, false) // Update buttons
                logEvent("⚠️ USER ACTION REQUIRED: ${event.prompt}")
                
                when (event.inputType) {
                    "scenario_selection", "test_plan_review" -> {
                        val testPlan = event.data as TestPlan
                        testPlanDisplayPanel.setTestPlanForSelection(testPlan)
                        
                        // Show prominent action banner with dynamic text
                        showActionBanner(
                            "Select test scenarios from ${testPlan.scenarioCount} available scenarios",
                            "View Test Plan"
                        ) {
                            tabbedPane.selectedIndex = 1
                        }
                        
                        // Auto-switch to Test Plan tab
                        tabbedPane.selectedIndex = 1
                        
                        statusIndicator.text = "⏳ Waiting for scenario selection"
                        logEvent("⚠️ ACTION REQUIRED: Select test scenarios in the Test Plan tab")
                        
                        // Add balloon notification with count
                        com.zps.zest.ZestNotifications.showWarning(
                            project,
                            "Action Required",
                            "Please select test scenarios from ${testPlan.scenarioCount} available scenarios"
                        )
                    }
                    "write_file" -> {
                        showActionBanner(
                            "Your tests are ready! Use the Test Merging tab to review and save.",
                            "View Results"
                        ) {
                            tabbedPane.selectedIndex = 3 // Switch to Test Merging tab
                        }
                        
                        primaryActionButton.text = "✅ Generation Complete"
                        primaryActionButton.background = Color(76, 175, 80) // Green
                        primaryActionButton.isEnabled = false
                        
                        statusIndicator.text = "✅ Tests ready for review"
                        logEvent("✅ Generation complete - review in Test Merging tab")
                    }
                    "retry_generation" -> {
                        showActionBanner(
                            "Test generation failed - retry?",
                            "Retry Now"
                        ) {
                            retryCurrentState()
                        }
                        
                        primaryActionButton.text = "🔄 Retry Generation"
                        primaryActionButton.background = Color(244, 67, 54) // Red
                        primaryActionButton.isEnabled = true
                        
                        statusIndicator.text = "❌ Generation failed"
                        logEvent("⚠️ ACTION REQUIRED: Click 'Retry Generation' button to attempt test generation again")
                    }
                    else -> {
                        showActionBanner(
                            event.prompt,
                            "Take Action"
                        ) {
                            // Generic action - could be improved based on specific needs
                        }
                        
                        statusIndicator.text = "⏳ Waiting for user input"
                        logEvent("⚠️ ACTION REQUIRED: ${event.prompt}")
                    }
                }
                
                // Play system beep for attention
                Toolkit.getDefaultToolkit().beep()
            }
        }
        
        override fun onEvent(event: TestGenerationEvent) {
            // Generic logging for all events
            LOG.debug("Event: ${event::class.simpleName} for session ${event.sessionId}")
        }
        
        // StreamingEventListener implementation for real-time updates
        override fun onFileAnalyzed(data: ContextDisplayData) {
            SwingUtilities.invokeLater {
                contextDisplayPanel.addFile(data)
                tabbedPane.selectedIndex = 0
                updateTokenDisplay()
            }
        }

        override fun onTestPlanUpdated(data: TestPlanDisplayData) {
            SwingUtilities.invokeLater {
                testPlanDisplayPanel.updateTestPlan(data)
                tabbedPane.selectedIndex = 1
                updateTokenDisplay()
            }
        }

        override fun onTestGenerated(data: GeneratedTestDisplayData) {
            SwingUtilities.invokeLater {
                generatedTestsPanel.addGeneratedTest(data)
                tabbedPane.selectedIndex = 2
                updateTokenDisplay()
            }
        }

        // New streaming events for real-time test generation display
        override fun onTestGenerationStreamingStarted(className: String) {
            SwingUtilities.invokeLater {
                generatedTestsPanel.startStreaming(className)
                tabbedPane.selectedIndex = 2
                updateTokenDisplay()
            }
        }

        override fun onTestGenerationToken(token: String) {
            SwingUtilities.invokeLater {
                generatedTestsPanel.appendStreamingContent(token)
            }
        }

        override fun onTestGenerationStreamingComplete() {
            SwingUtilities.invokeLater {
                generatedTestsPanel.finalizeStreaming()
                updateTokenDisplay()
            }
        }

        override fun onLLMCallCompleted(inputTokens: Int, outputTokens: Int, durationMs: Long) {
            SwingUtilities.invokeLater {
                totalTokensUsed += inputTokens + outputTokens
                statusIndicator.text = "${formatTokenCount(totalTokensUsed)} tokens"
            }
        }

        override fun onToolExecutionDetected(toolNames: List<String>) {
            SwingUtilities.invokeLater {
                val lastTool = toolNames.lastOrNull() ?: return@invokeLater
                statusIndicator.text = "🔧 $lastTool | ${formatTokenCount(totalTokensUsed)} tokens"
            }
        }

        override fun onManualToolActivity(toolName: String, detail: String) {
            SwingUtilities.invokeLater {
                val message = if (detail.isNotEmpty()) "$toolName: $detail" else toolName
                statusIndicator.text = "⚙️ $message | ${formatTokenCount(totalTokensUsed)} tokens"
            }
        }
        
        override fun onMergerAgentCreated(mergerAgent: com.zps.zest.testgen.agents.AITestMergerAgent) {
            SwingUtilities.invokeLater {
                // Set the merger agent immediately when it's created
                testMergingPanel.setMergerAgent(mergerAgent)

                // Store reference for later use
                currentMergerAgent = mergerAgent
            }
        }

        override fun onMergingStarted() {
            SwingUtilities.invokeLater {
                // Close any existing dialog
                mergerChatDialog?.close(DialogWrapper.OK_EXIT_CODE)

                // Get the current merger agent
                val agent = currentMergerAgent ?: currentStateMachine?.let { stateMachine ->
                    val mergingHandler = stateMachine.getCurrentHandler(
                        com.zps.zest.testgen.statemachine.handlers.TestMergingHandler::class.java
                    )
                    mergingHandler?.aiTestMergerAgent
                }

                if (agent != null && agent.chatMemory != null) {
                    // Show the ChatMemoryDialog with the merger's chat memory
                    val dialog = ChatMemoryDialog(project, agent.chatMemory, "Test Merger AI")
                    mergerChatDialog = dialog
                    DialogManager.showDialog(dialog)

                    logEvent("💬 Merger chat memory dialog opened")

                    // Also switch to the Test Merging tab to show progress
                    tabbedPane.selectedIndex = 3
                } else {
                    logEvent("⚠️ Merger agent or chat memory not available yet")
                }
            }
        }

        override fun onMergingCompleted(success: Boolean) {
            SwingUtilities.invokeLater {
                // Close the chat memory dialog after a short delay
                if (mergerChatDialog != null) {
                    Timer(2000) {
                        mergerChatDialog?.close(DialogWrapper.OK_EXIT_CODE)
                        mergerChatDialog = null
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }

                if (success) {
                    logEvent("✅ Test merging completed successfully")
                } else {
                    logEvent("❌ Test merging failed")
                }
            }
        }

        override fun onMergedTestClassUpdated(mergedClass: com.zps.zest.testgen.model.MergedTestClass) {
            SwingUtilities.invokeLater {
                // Thread-safe access to currentStateMachine
                val stateMachine = synchronized(stateMachineLock) { currentStateMachine }

                // Get existing test code from AITestMergerAgent via direct handler access
                val mergingHandler = stateMachine?.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.TestMergingHandler::class.java)
                val aiMergerAgent = mergingHandler?.getAITestMergerAgent()
                val existingTestCode = aiMergerAgent?.lastExistingTestCode

                // Update panel with merged code, existing code, AND the agent for chat memory
                testMergingPanel.updateMergedClass(mergedClass, existingTestCode, aiMergerAgent)

                // Switch to test merging tab
                tabbedPane.selectedIndex = 3
            }
        }
        
        override fun onStatusChanged(status: String) {
            SwingUtilities.invokeLater {
                logEvent("Status: $status")

                // Start streaming when test planning begins
                if (status.contains("Generating test plan", ignoreCase = true) ||
                    status.contains("Planning", ignoreCase = true)) {
                    testPlanDisplayPanel.startStreaming()
                    testPlanDisplayPanel.appendStreamingText("⏳ Initializing CoordinatorAgent...\n\nPreparing to analyze code and generate test plan...\n\n")
                    tabbedPane.selectedIndex = 1
                }
            }
        }
        
        override fun onProgressChanged(percent: Int, message: String) {
            SwingUtilities.invokeLater {
                logEvent("Status: $message")
            }
        }

        // New live update event handlers for test merging
        override fun onTestCodeSet(className: String, testCode: String, isExisting: Boolean) {
            SwingUtilities.invokeLater {
                // Immediately update the test merging panel with the test code
                testMergingPanel.updateTestCodeImmediately(className, testCode, isExisting)

                // Switch to test merging tab if not already there
                if (tabbedPane.selectedIndex != 3) {
                    tabbedPane.selectedIndex = 3
                }

                // Log the event
                val codeType = if (isExisting) "existing" else "new"
                logEvent("📝 Setting $codeType test code for $className")
            }
        }

        override fun onTestCodeUpdated(className: String, updatedCode: String) {
            SwingUtilities.invokeLater {
                // Update the merged test editor with the latest code
                testMergingPanel.updateTestCodeImmediately(className, updatedCode, false)

                // Don't log every update to avoid spam, just update display
            }
        }

        override fun onFixApplied(oldText: String, newText: String, lineNumber: Int?) {
            SwingUtilities.invokeLater {
                // Show the fix being applied with line highlighting
                testMergingPanel.showFixApplied(oldText, newText, lineNumber)

                // Log fix application briefly
                val fixLocation = lineNumber?.let { " at line $it" } ?: ""
                logEvent("🔧 Fix applied$fixLocation")
            }
        }

        override fun onValidationStatusChanged(status: String, issues: List<String>?) {
            SwingUtilities.invokeLater {
                // Update validation status display
                testMergingPanel.updateValidationStatus(status, issues)

                // Log validation status
                when (status) {
                    "VALIDATION_PASSED" -> logEvent("✅ Validation passed")
                    "VALIDATION_FAILED" -> {
                        val issueCount = issues?.size ?: 0
                        logEvent("⚠️ Validation found $issueCount issue(s)")
                    }
                    else -> logEvent("📋 Validation: $status")
                }
            }
        }

        override fun onPhaseStarted(phase: com.zps.zest.testgen.statemachine.TestGenerationState) {
            SwingUtilities.invokeLater {
                val tabIndex = when (phase) {
                    com.zps.zest.testgen.statemachine.TestGenerationState.GATHERING_CONTEXT -> {
                        contextDisplayPanel.showActivity("Gathering context...")
                        0
                    }
                    com.zps.zest.testgen.statemachine.TestGenerationState.PLANNING_TESTS -> {
                        testPlanDisplayPanel.showActivity("Generating test plan...")
                        testPlanDisplayPanel.startStreaming()
                        testPlanDisplayPanel.appendStreamingText("⏳ CoordinatorAgent starting...\n\nInitializing test plan generation...\n\n")
                        1
                    }
                    com.zps.zest.testgen.statemachine.TestGenerationState.GENERATING_TESTS -> {
                        generatedTestsPanel.showActivity("Generating tests...")
                        2
                    }
                    com.zps.zest.testgen.statemachine.TestGenerationState.MERGING_TESTS -> {
                        testMergingPanel.showActivity("Merging and validating tests...")
                        3
                    }
                    else -> return@invokeLater
                }

                // WARNING: Check if tab switch is valid
                if (WARN_POTENTIAL_ERRORS && tabIndex >= tabbedPane.tabCount) {
                    println("[WARN_TAB_SWITCH] Attempted to switch to invalid tab index $tabIndex, tabCount=${tabbedPane.tabCount}, phase=$phase")
                    return@invokeLater
                }

                tabbedPane.selectedIndex = tabIndex
            }
        }
    }
    
    init {
        component = JPanel(BorderLayout())
        component.background = UIUtil.getPanelBackground()
        setupUI()
        initializeStreamingHelper()

        // Auto-start if we have a request (but not if already running)
        virtualFile.request?.let { request ->
            SwingUtilities.invokeLater {
                // Only auto-start if no active session
                if (currentSessionId == null || currentStateMachine?.currentState?.isActive != true) {
                    startTestGeneration(request)
                }
            }
        }
    }

    /**
     * Get the event listener for external use (e.g., snapshot resume)
     */
    fun getEventListener(): TestGenerationEventListener = eventListener

    /**
     * Get the streaming callback for external use (e.g., snapshot resume)
     */
    fun getStreamingCallback(): ((String) -> Unit) = ::processStreamingText

    /**
     * Called when a session is resumed from a snapshot
     */
    fun onSessionResumed(stateMachine: TestGenerationStateMachine) {
        synchronized(stateMachineLock) {
            currentSessionId = stateMachine.sessionId
            currentStateMachine = stateMachine
        }

        if (DEBUG_UI_UPDATES) {
            println("[DEBUG_UI] Session resumed: sessionId=${stateMachine.sessionId}, state=${stateMachine.currentState}")
        }

        SwingUtilities.invokeLater {
            updateStateDisplay(stateMachine.currentState)
            updateControlButtons()
            updateAgentChatMemories(stateMachine.sessionId)
            startChatMemoryPeriodicUpdates()
            logEvent("Session resumed from snapshot: ${stateMachine.sessionId}")
        }
    }
    
    private fun setupUI() {
        // Top panel with action banner and state display
        val topPanel = JPanel(BorderLayout())
        topPanel.add(createActionBanner(), BorderLayout.NORTH)
        topPanel.add(createStateDisplayPanel(), BorderLayout.CENTER)
        component.add(topPanel, BorderLayout.NORTH)
        
        // Center with tabbed pane (log removed for cleaner UI - activity logged internally)
        component.add(createTabbedPane(), BorderLayout.CENTER)

        // Bottom with control buttons
        component.add(createControlPanel(), BorderLayout.SOUTH)
    }
    
    private fun createActionBanner(): JComponent {
        actionBanner = JPanel(BorderLayout())
        actionBanner.background = Color(255, 243, 224) // Light orange
        actionBanner.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 4, 0, 0, Color(255, 152, 0)), // Orange left border
            EmptyBorder(15, 20, 15, 20)
        )
        actionBanner.isVisible = false
        
        // Icon + Message panel
        val messagePanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        messagePanel.isOpaque = false
        
        val warningIcon = JBLabel("⚠️")
        warningIcon.font = warningIcon.font.deriveFont(20f)
        
        actionMessage = JBLabel()
        actionMessage.font = actionMessage.font.deriveFont(Font.BOLD, 14f)
        actionMessage.foreground = Color.BLACK
        
        messagePanel.add(warningIcon)
        messagePanel.add(actionMessage)
        
        // Embedded action button
        embeddedActionButton = JButton()
        embeddedActionButton.font = embeddedActionButton.font.deriveFont(Font.BOLD, 12f)
        embeddedActionButton.background = Color(255, 152, 0)
        embeddedActionButton.foreground = Color.WHITE
        embeddedActionButton.preferredSize = JBUI.size(150, 32)
        
        actionBanner.add(messagePanel, BorderLayout.CENTER)
        actionBanner.add(embeddedActionButton, BorderLayout.EAST)
        
        return actionBanner
    }

    private fun createStateDisplayPanel(): JComponent {
        stateDisplayPanel = JPanel(BorderLayout())
        stateDisplayPanel.border = EmptyBorder(15, 15, 10, 15)
        stateDisplayPanel.background = UIUtil.getPanelBackground()
        
        // Left side - state info
        val stateInfoPanel = JPanel()
        stateInfoPanel.layout = BoxLayout(stateInfoPanel, BoxLayout.Y_AXIS)
        stateInfoPanel.isOpaque = false
        
        stateLabel = JBLabel("Idle")
        stateLabel.font = stateLabel.font.deriveFont(Font.BOLD, 16f)
        stateInfoPanel.add(stateLabel)
        
        stateDescription = JBLabel("No test generation in progress")
        stateDescription.foreground = UIUtil.getContextHelpForeground()
        stateInfoPanel.add(stateDescription)
        
        stateDisplayPanel.add(stateInfoPanel, BorderLayout.WEST)
        
        // Right side - status and action prompts
        val statusPanel = JPanel()
        statusPanel.layout = BoxLayout(statusPanel, BoxLayout.Y_AXIS)
        statusPanel.isOpaque = false
        
        statusIndicator = JBLabel("")
        statusIndicator.font = statusIndicator.font.deriveFont(Font.BOLD)
        statusIndicator.foreground = UIUtil.getContextHelpForeground()
        statusPanel.add(statusIndicator)
        
        actionPrompt = JBLabel("")
        actionPrompt.font = actionPrompt.font.deriveFont(Font.BOLD)
        actionPrompt.foreground = UIUtil.getErrorForeground()
        statusPanel.add(actionPrompt)
        
        stateDisplayPanel.add(statusPanel, BorderLayout.EAST)
        
        return stateDisplayPanel
    }

    private fun createControlPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        // Left: Status indicator
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        statusIndicator.preferredSize = JBUI.size(200, 20)
        statusPanel.add(statusIndicator)
        
        // Center: Primary action button (changes based on state)
        val actionPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        primaryActionButton = JButton("▶️ Start Test Generation")
        primaryActionButton.preferredSize = JBUI.size(220, 40)
        primaryActionButton.font = primaryActionButton.font.deriveFont(Font.BOLD, 14f)
        primaryActionButton.background = Color(76, 175, 80) // Green
        primaryActionButton.addActionListener { handlePrimaryAction() }
        actionPanel.add(primaryActionButton)
        
        // Right: Cancel button (only visible during execution)
        val cancelPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        cancelButton = JButton("Cancel")
        cancelButton.addActionListener { cancelGeneration() }
        cancelButton.isVisible = false
        cancelPanel.add(cancelButton)
        
        panel.add(statusPanel, BorderLayout.WEST)
        panel.add(actionPanel, BorderLayout.CENTER)
        panel.add(cancelPanel, BorderLayout.EAST)

        return panel
    }

    private fun createTabbedPane(): JComponent {
        tabbedPane = JBTabbedPane()
        
        // Context tab - real-time context gathering display
        contextDisplayPanel = ContextDisplayPanel(project)
        tabbedPane.addTab("Context", contextDisplayPanel)
        
        // Test Plan tab - real-time test plan with scenario selection
        testPlanDisplayPanel = TestPlanDisplayPanel(project)
        testPlanDisplayPanel.setSelectionListener { selectedIds ->
            LOG.info("Selected scenarios: ${selectedIds.size}")
            // Update button state and action banner when selection changes
            SwingUtilities.invokeLater { 
                updateControlButtons()
                
                // Update action banner message if visible
                if (actionBanner.isVisible) {
                    val totalScenarios = testPlanDisplayPanel.getTotalScenarioCount()
                    actionMessage.text = if (selectedIds.isNotEmpty()) {
                        "Great! ${selectedIds.size} of $totalScenarios scenarios selected - click the button below"
                    } else {
                        "Select test scenarios from $totalScenarios available scenarios"
                    }
                }
            }
        }
        // No longer using callback - direct selection via main button
        tabbedPane.addTab("Test Plan", testPlanDisplayPanel)
        
        // Generated Tests tab - real-time generated test display
        generatedTestsPanel = GeneratedTestsPanel(project)
        tabbedPane.addTab("Generated Tests", generatedTestsPanel)
        
        // Test Merging tab - merged class display with error review
        testMergingPanel = TestMergingPanel(project)
        tabbedPane.addTab("Merge and Fix", testMergingPanel)
        
        return tabbedPane
    }
    
    private fun initializeStreamingHelper() {
        // Initialize the streaming helper with the event listener
        streamingHelper = StreamingUIHelper(eventListener)
    }
    
    /**
     * Process streaming text for real-time updates.
     * This bridges the gap between state machine events and streaming UI updates.
     */
    private fun processStreamingText(text: String) {
        SwingUtilities.invokeLater {
            // Process streaming text with helper for real-time panel updates
            // (Activity log removed from UI - events logged via LOG.debug if needed)
            streamingHelper.processStreamingText(text)
        }
    }
    
    /**
     * Filter streaming text to reduce log verbosity - only log important events
     */
    private fun shouldLogStreamingText(text: String): Boolean {
        val lowerText = text.lowercase()
        
        // Always log important events
        if (lowerText.contains("error") || 
            lowerText.contains("failed") || 
            lowerText.contains("completed") ||
            lowerText.contains("summary") ||
            lowerText.contains("starting") ||
            lowerText.contains("✅") ||
            lowerText.contains("❌") ||
            lowerText.contains("⚠️") ||
            lowerText.startsWith("attempt ")) {
            return true
        }
        
        // Skip verbose tool output and incremental progress
        if (lowerText.contains("🔧") || // Tool calls
            lowerText.contains("extracted") || 
            lowerText.contains("detected") ||
            lowerText.contains("parsed") ||
            lowerText.contains("parsing") ||
            lowerText.contains("analyzing") ||
            text.startsWith("-") || // Separator lines
            text.trim().isEmpty()) {
            return false
        }
        
        return true // Log everything else
    }
    
    private fun startTestGeneration(request: com.zps.zest.testgen.model.TestGenerationRequest) {
        // Thread-safe check if already running
        synchronized(stateMachineLock) {
            if (DEBUG_UI_UPDATES) {
                println("[DEBUG_UI] startTestGeneration called, currentSessionId=$currentSessionId")
            }

            if (currentSessionId != null && currentStateMachine != null) {
                val currentState = currentStateMachine?.currentState
                if (currentState != null && currentState.isActive) {
                    if (DEBUG_UI_UPDATES) {
                        println("[DEBUG_UI] BLOCKED: already running, state=${currentState.displayName}, sessionId=$currentSessionId")
                    }
                    logEvent("Warning: Test generation already in progress (${currentState.displayName})")
                    Messages.showWarningDialog(project,
                        "Test generation is already in progress.\n\nCurrent state: ${currentState.displayName}",
                        "Already Running")
                    return
                }
            }
            // Set a sentinel value immediately to prevent race condition
            currentSessionId = "STARTING"
            if (DEBUG_UI_UPDATES) {
                println("[DEBUG_UI] Set sentinel STARTING to prevent race conditions")
            }
        }

        logEvent("Starting test generation...")

        // Disable button immediately to prevent double-click
        primaryActionButton.isEnabled = false

        // Clear all panels and stop any existing timers
        contextDisplayPanel.clear()
        testPlanDisplayPanel.clear()
        generatedTestsPanel.clear()
        testMergingPanel.clear()
        stopChatMemoryPeriodicUpdates() // Stop any existing periodic updates
        streamingHelper.reset()

        testGenService.startTestGeneration(request, eventListener, ::processStreamingText).thenAccept { stateMachine ->
            SwingUtilities.invokeLater {
                synchronized(stateMachineLock) {
                    // WARNING: Check if session changed while starting (rapid clicks)
                    if (currentSessionId != "STARTING" && currentSessionId != null) {
                        if (WARN_POTENTIAL_ERRORS) {
                            println("[WARN_SESSION_RACE] Session changed during startup! current=$currentSessionId, new=${stateMachine.sessionId}")
                        }
                        LOG.warn("Session ID changed during startup - possible rapid restart")
                    }
                    currentSessionId = stateMachine.sessionId
                    currentStateMachine = stateMachine
                }
                updateStateDisplay(stateMachine.currentState)
                updateControlButtons()
                updateAgentChatMemories(stateMachine.sessionId)
                startChatMemoryPeriodicUpdates() // Start periodic updates
                logEvent("Session started: ${stateMachine.sessionId}")
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                synchronized(stateMachineLock) {
                    if (WARN_POTENTIAL_ERRORS) {
                        println("[WARN_STARTUP_FAILED] Startup failed, cleaning up sessionId=$currentSessionId, error=${throwable.message}")
                    }
                    currentSessionId = null
                    currentStateMachine = null
                }
                logEvent("ERROR: Failed to start: ${throwable.message}")
                Messages.showErrorDialog(project,
                    "Failed to start test generation: ${throwable.message}",
                    "Startup Error")
                primaryActionButton.isEnabled = true
                updateControlButtons()
            }
            null
        }
    }
    
    private fun continueExecution() {
        currentSessionId?.let { sessionId ->
            if (testGenService.continueExecution(sessionId)) {
                logEvent("Continuing execution...")
                updateControlButtons()
            } else {
                logEvent("Cannot continue execution")
            }
        }
    }
    
    private fun retryCurrentState() {
        currentSessionId?.let { sessionId ->
            if (testGenService.retryCurrentState(sessionId)) {
                logEvent("Retrying current state...")
                updateControlButtons()
            } else {
                logEvent("Cannot retry current state")
            }
        }
    }
    
    private fun skipCurrentState() {
        currentSessionId?.let { sessionId ->
            val currentState = testGenService.getCurrentState(sessionId)
            val result = Messages.showYesNoDialog(
                project,
                "Skip current state: ${currentState?.displayName}?\n\nThis may result in incomplete test generation.",
                "Skip State",
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                if (testGenService.skipCurrentState(sessionId, "User requested skip")) {
                    logEvent("Skipped state: ${currentState?.displayName}")
                    updateControlButtons()
                } else {
                    logEvent("Cannot skip current state")
                }
            }
        }
    }
    
    private fun cancelGeneration() {
        currentSessionId?.let { sessionId ->
            testGenService.cancelGeneration(sessionId, "User cancelled")
            logEvent("Generation cancelled")
            updateControlButtons()
        }
    }
    
    private fun updateStateDisplay(state: TestGenerationState) {
        stateLabel.text = state.displayName
        stateDescription.text = state.description
        
        // Color coding for states
        stateLabel.foreground = when {
            state == TestGenerationState.FAILED -> JBColor.RED
            state == TestGenerationState.COMPLETED -> JBColor(Color(0, 128, 0), Color(0, 200, 0))
            state == TestGenerationState.CANCELLED -> JBColor.ORANGE
            state.isActive -> JBColor.BLUE
            else -> UIUtil.getLabelForeground()
        }
    }
    
    private fun updateButtonForState(state: TestGenerationState?, hasError: Boolean = false) {
        SwingUtilities.invokeLater {
            // WARNING: Detect button state desync
            if (WARN_POTENTIAL_ERRORS && state != null) {
                val currentState = currentStateMachine?.currentState
                if (currentState != null && currentState != state) {
                    println("[WARN_BUTTON_DESYNC] Button update for wrong state! updating for $state but machine is in $currentState, sessionId=$currentSessionId")
                }
            }

            when {
                hasError -> {
                    primaryActionButton.apply {
                        text = "🔄 Retry"
                        background = Color(244, 67, 54) // Red
                        isEnabled = true
                        isVisible = true
                    }
                    cancelButton.isVisible = false
                }
                
                state == null || state == TestGenerationState.IDLE -> {
                    // Differentiate between new session and resumed session
                    val isResumedSession = virtualFile.request == null && currentStateMachine != null
                    val isNewSession = virtualFile.request != null

                    if (DEBUG_BUTTON_STATE) {
                        println("[DEBUG_BUTTON_STATE] IDLE state: isResumedSession=$isResumedSession, isNewSession=$isNewSession, hasRequest=${virtualFile.request != null}, hasStateMachine=${currentStateMachine != null}")
                    }

                    when {
                        isResumedSession -> {
                            // Resumed session - show continue option
                            primaryActionButton.apply {
                                text = "▶️ Continue from Checkpoint"
                                background = Color(33, 150, 243) // Blue to differentiate from new session
                                isEnabled = currentStateMachine?.currentState?.isTerminal != true
                                isVisible = true
                            }
                        }
                        isNewSession -> {
                            // Normal new session - show start
                            primaryActionButton.apply {
                                text = "▶️ Start Test Generation"
                                background = Color(76, 175, 80) // Green
                                isEnabled = true
                                isVisible = true
                            }
                        }
                        else -> {
                            // No request and no state machine - invalid state, hide button
                            primaryActionButton.apply {
                                text = "⚠️ No Session Data"
                                background = Color(156, 156, 156) // Gray
                                isEnabled = false
                                isVisible = false
                            }
                        }
                    }
                    cancelButton.isVisible = false
                }
                
                state == TestGenerationState.AWAITING_USER_SELECTION -> {
                    val selectedScenarios = testPlanDisplayPanel.getSelectedTestScenarios()
                    val selectedCount = selectedScenarios.size

                    // WARNING: Verify selection count matches what panel shows
                    if (WARN_POTENTIAL_ERRORS) {
                        val totalCount = testPlanDisplayPanel.getTotalScenarioCount()
                        if (totalCount == 0) {
                            println("[WARN_SELECTION_MISMATCH] No scenarios available in panel! selectedCount=$selectedCount, totalCount=$totalCount")
                        }
                    }

                    primaryActionButton.apply {
                        text = if (selectedCount > 0) {
                            "✅ Generate $selectedCount Selected Test${if (selectedCount == 1) "" else "s"}"
                        } else {
                            "⚠️ Select Test Scenarios First"
                        }

                        // Color coding based on selection state
                        background = if (selectedCount > 0) {
                            Color(76, 175, 80) // Green when scenarios are selected
                        } else {
                            Color(156, 156, 156) // Gray when no selection
                        }

                        foreground = if (selectedCount > 0) {
                            Color.WHITE // White text on green
                        } else {
                            Color.WHITE // White text on gray
                        }

                        isEnabled = selectedCount > 0
                        isVisible = true
                    }
                    cancelButton.isVisible = true
                }
                
                state == TestGenerationState.FIXING_TESTS -> {
                    primaryActionButton.apply {
                        text = "🔧 Fix Compilation Errors"
                        background = Color(255, 152, 0) // Orange  
                        isEnabled = true
                        isVisible = true
                    }
                    cancelButton.isVisible = true
                }
                
                state == TestGenerationState.COMPLETED -> {
                    primaryActionButton.apply {
                        text = "✅ Generation Complete"
                        background = Color(76, 175, 80) // Green
                        isEnabled = false  // No action needed - use dialog for saving
                        isVisible = true
                    }
                    cancelButton.isVisible = false
                }

                state?.isActive == true -> {
                    // During auto-flow states, show disabled button with current state
                    primaryActionButton.apply {
                        text = "⏳ ${state.displayName}..."
                        background = Color(156, 156, 156) // Gray
                        isEnabled = false
                        isVisible = true
                    }
                    cancelButton.isVisible = true
                }
            }
        }
    }
    
    private fun hasSelectedScenarios(): Boolean {
        return testPlanDisplayPanel.getSelectedTestScenarios().isNotEmpty()
    }
    
    private fun getSelectedScenarioCount(): Int {
        return testPlanDisplayPanel.getSelectedTestScenarios().size
    }
    
    private fun updateControlButtons() {
        currentSessionId?.let { sessionId ->
            val currentState = testGenService.getCurrentState(sessionId)
            val hasError = testGenService.canRetry(sessionId)

            if (DEBUG_BUTTON_STATE) {
                println("[DEBUG_BUTTON] updateControlButtons: sessionId=$sessionId, state=$currentState, hasError=$hasError")
            }

            // Use new button state management
            updateButtonForState(currentState, hasError)

        } ?: run {
            if (DEBUG_BUTTON_STATE) {
                println("[DEBUG_BUTTON] updateControlButtons: no active session")
            }
            // No active session
            updateButtonForState(null, false)
        }
    }
    
    
    private fun generateSelectedTests() {
        val selectedScenarios = testPlanDisplayPanel.getSelectedTestScenarios()

        if (DEBUG_UI_UPDATES) {
            println("[DEBUG_UI] generateSelectedTests: count=${selectedScenarios.size}, sessionId=$currentSessionId")
        }

        if (selectedScenarios.isEmpty()) {
            com.zps.zest.ZestNotifications.showWarning(
                project,
                "No Scenarios Selected",
                "Please select at least one test scenario to generate tests"
            )
            return
        }

        // Get the edited testing notes
        val editedTestingNotes = testPlanDisplayPanel.getEditedTestingNotes()

        currentSessionId?.let { sessionId ->
            if (testGenService.setUserSelection(sessionId, selectedScenarios, editedTestingNotes)) {
                if (DEBUG_UI_UPDATES) {
                    println("[DEBUG_UI] User selection confirmed, count=${selectedScenarios.size}, sessionId=$sessionId")
                }
                logEvent("User confirmed selection of ${selectedScenarios.size} scenarios - continuing generation")
                hideActionBanner()
                // Exit selection mode since selection was processed
                testPlanDisplayPanel.exitSelectionMode()
            } else {
                if (DEBUG_UI_UPDATES) {
                    println("[DEBUG_UI] FAILED to set user selection, sessionId=$sessionId")
                }
                logEvent("Failed to set user selection")
                com.zps.zest.ZestNotifications.showWarning(
                    project,
                    "Selection Failed",
                    "Could not process scenario selection. Please try again."
                )
            }
        }
    }

    private fun showFinalResult() {
        currentStateMachine?.let { stateMachine ->
            // Get merged test class from TestMergingHandler
            val mergingHandler = stateMachine.getHandler(
                com.zps.zest.testgen.statemachine.TestGenerationState.MERGING_TESTS,
                com.zps.zest.testgen.statemachine.handlers.TestMergingHandler::class.java
            )
            val mergedTestClass = mergingHandler?.mergedTestClass

            if (mergedTestClass != null) {
                logEvent("Showing preview dialog for: ${mergedTestClass.className}")

                // Create a minimal session for the dialog
                val request = stateMachine.request
                var session: com.zps.zest.testgen.model.TestGenerationSession? = null
                if (request != null) {
                    session = com.zps.zest.testgen.model.TestGenerationSession(
                        stateMachine.sessionId,
                        request,
                        com.zps.zest.testgen.model.TestGenerationSession.Status.COMPLETED
                    )
                }

                if (session != null) {
                    session.setMergedTestClass(mergedTestClass)
                    val dialog = MergedTestPreviewDialog(project, session)
                    dialog.show()
                } else {
                    logEvent("ERROR: Cannot create session for preview dialog")
                    Messages.showErrorDialog(project, 
                        "Cannot show preview: session data missing", 
                        "Preview Error")
                }
            } else {
                logEvent("ERROR: No merged test class found in session data")
                Messages.showErrorDialog(project, 
                    "No merged test class available for preview", 
                    "Preview Error")
            }
        }
    }
    
    
    private fun logEvent(message: String) {
        // Log to IntelliJ's log for debugging (UI log area removed for cleaner interface)
        val timestamp = java.time.LocalTime.now().toString().substring(0, 8)
        LOG.debug("[$timestamp] $message")
    }
    
    /**
     * Update chat memory displays for all agent tabs
     */
    private fun updateAgentChatMemories(sessionId: String) {
        try {
            val stateMachine = testGenService.getStateMachine(sessionId)

            if (stateMachine == null) {
                if (WARN_POTENTIAL_ERRORS) {
                    println("[WARN_AGENT_ACCESS] Cannot update chat memories - state machine is null for sessionId=$sessionId")
                }
                return
            }

            // Get ContextAgent memory via direct handler access
            val contextHandler = stateMachine.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler::class.java)
            val contextAgent = contextHandler?.getContextAgent()
            val contextMemory = contextAgent?.getChatMemory()
            val contextMessageCount = contextMemory?.messages()?.size ?: 0
            
            // Only log if there are changes or new agents
            val wasContextAgentNull = contextDisplayPanel.getLastKnownMessageCount() == -1
            val contextChanged = contextDisplayPanel.getLastKnownMessageCount() != contextMessageCount
            
            if (wasContextAgentNull && contextAgent != null) {
                println("[DEBUG] ContextAgent now available with $contextMessageCount messages")
            } else if (contextChanged) {
                println("[DEBUG] ContextAgent messages: ${contextDisplayPanel.getLastKnownMessageCount()} → $contextMessageCount")
            }
            
            contextDisplayPanel.setChatMemory(contextMemory)
            
            // Get TestWriterAgent memory via direct handler access
            val generationHandler = stateMachine?.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler::class.java)
            val testWriterAgent = generationHandler?.getTestWriterAgent()
            generatedTestsPanel.setChatMemory(testWriterAgent?.getChatMemory(), "TestWriter")
            
            // Get CoordinatorAgent memory via direct handler access
            val planningHandler = stateMachine?.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler::class.java)
            val coordinatorAgent = planningHandler?.getCoordinatorAgent()
            testPlanDisplayPanel.setChatMemory(coordinatorAgent?.getChatMemory(), "Coordinator")

            // Wire up streaming consumer to show AI reasoning text in real-time
            coordinatorAgent?.setStreamingConsumer { text ->
                testPlanDisplayPanel.appendStreamingText(text)
            }

            // AITestMergerAgent is now accessed directly in onMergedTestClassUpdated
            
            // Only log when there are significant changes
            if (wasContextAgentNull && contextAgent != null) {
                logEvent("✅ ContextAgent chat memory now available")
            }
            
        } catch (e: Exception) {
            logEvent("⚠️ Could not update agent chat memories: ${e.message}")
        }
    }
    
    private fun startBlinkingActionPrompt() {
        stopBlinking() // Stop any existing timer
        
        var visible = true
        blinkTimer = javax.swing.Timer(500) {
            visible = !visible
            actionPrompt.isVisible = visible
        }
        blinkTimer?.start()
        
        // Stop blinking after 10 seconds
        javax.swing.Timer(10000) {
            stopBlinking()
        }.apply { isRepeats = false }.start()
    }
    
    private fun stopBlinking() {
        blinkTimer?.stop()
        blinkTimer = null
        actionPrompt.isVisible = true
    }
    
    /**
     * Start periodic updates of chat memories to catch newly created agents
     */
    private fun startChatMemoryPeriodicUpdates() {
        stopChatMemoryPeriodicUpdates() // Stop any existing timer
        
        chatMemoryUpdateTimer = javax.swing.Timer(2000) { // Update every 2 seconds
            currentSessionId?.let { sessionId ->
                updateAgentChatMemories(sessionId)
            }
        }
        chatMemoryUpdateTimer?.start()
        println("[DEBUG] Started periodic chat memory updates")
    }
    
    /**
     * Stop periodic chat memory updates
     */
    private fun stopChatMemoryPeriodicUpdates() {
        chatMemoryUpdateTimer?.stop()
        chatMemoryUpdateTimer = null
        println("[DEBUG] Stopped periodic chat memory updates")
    }
    
    private fun clearActionPrompt() {
        stopBlinking()
        actionPrompt.text = ""
        hideActionBanner()
    }
    
    private fun handlePrimaryAction() {
        val sessionId = currentSessionId

        if (DEBUG_UI_UPDATES) {
            println("[DEBUG_UI] handlePrimaryAction: sessionId=$sessionId")
        }

        // Don't allow action if currently starting
        if (sessionId == "STARTING") {
            if (DEBUG_UI_UPDATES) {
                println("[DEBUG_UI] handlePrimaryAction BLOCKED: currently starting")
            }
            logEvent("Test generation is starting, please wait...")
            return
        }
        val state = sessionId?.let { testGenService.getCurrentState(it) }

        if (DEBUG_UI_UPDATES) {
            println("[DEBUG_UI] handlePrimaryAction: state=$state, action=${
                when {
                    state == null -> "START"
                    state == TestGenerationState.AWAITING_USER_SELECTION -> "GENERATE_SELECTED"
                    state == TestGenerationState.FIXING_TESTS -> "CONTINUE"
                    state == TestGenerationState.COMPLETED -> "SHOW_RESULTS"
                    testGenService.canRetry(sessionId) -> "RETRY"
                    else -> "UNKNOWN"
                }
            }")
        }

        when {
            state == null -> {
                // Differentiate between new session and resumed session
                val isResumedSession = virtualFile.request == null && currentStateMachine != null

                if (isResumedSession) {
                    // Resumed session - continue from checkpoint
                    if (DEBUG_UI_UPDATES) {
                        println("[DEBUG_UI] handlePrimaryAction: Continuing resumed session, sessionId=${currentStateMachine?.sessionId}")
                    }
                    continueExecution()
                } else {
                    // New session - start generation
                    virtualFile.request?.let {
                        if (DEBUG_UI_UPDATES) {
                            println("[DEBUG_UI] handlePrimaryAction: Starting new test generation")
                        }
                        startTestGeneration(it)
                    } ?: run {
                        if (DEBUG_UI_UPDATES) {
                            println("[DEBUG_UI] handlePrimaryAction: No request object available!")
                        }
                        logEvent("⚠️ Cannot start test generation: no request data available")
                    }
                }
            }
            state == TestGenerationState.AWAITING_USER_SELECTION -> {
                // Generate selected tests
                generateSelectedTests()
            }
            state == TestGenerationState.FIXING_TESTS -> {
                // Manually trigger test fixing
                continueExecution()
            }
            state == TestGenerationState.COMPLETED -> {
                // Show final results - no save action needed (handled by dialog)
                tabbedPane.selectedIndex = 3 // Switch to Test Merging tab
            }
            testGenService.canRetry(sessionId) -> {
                // Retry current state
                retryCurrentState()
            }
        }
    }
    
    private fun showActionBanner(message: String, buttonText: String, buttonAction: () -> Unit) {
        SwingUtilities.invokeLater {
            actionMessage.text = message
            embeddedActionButton.text = buttonText
            
            // Remove previous action listeners
            embeddedActionButton.actionListeners.forEach { 
                embeddedActionButton.removeActionListener(it) 
            }
            embeddedActionButton.addActionListener { buttonAction() }
            
            actionBanner.isVisible = true
            actionBanner.revalidate()
            actionBanner.repaint()
            
            // Flash the banner briefly
            startBannerFlash()
        }
    }
    
    private fun hideActionBanner() {
        SwingUtilities.invokeLater {
            actionBanner.isVisible = false
            actionBanner.revalidate()
            actionBanner.repaint()
        }
    }
    
    private fun startBannerFlash() {
        var flashCount = 0
        val flashTimer = javax.swing.Timer(300) {
            flashCount++
            if (flashCount <= 6) { // Flash 3 times (6 state changes)
                val isHighlight = flashCount % 2 == 1
                actionBanner.background = if (isHighlight) Color(255, 193, 7) else Color(255, 243, 224)
                actionBanner.repaint()
            } else {
                (it.source as javax.swing.Timer).stop()
                actionBanner.background = Color(255, 243, 224)
                actionBanner.repaint()
            }
        }
        flashTimer.start()
    }

    private fun formatTokenCount(count: Int): String {
        return String.format("%,d", count)
    }

    private fun updateTokenDisplay() {
        if (totalTokensUsed > 0 && !statusIndicator.text.contains("tokens")) {
            statusIndicator.text = "${formatTokenCount(totalTokensUsed)} tokens"
        }
    }

    // FileEditor implementation
    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent? = component
    override fun getName(): String = "State Machine Test Generation"
    override fun isValid(): Boolean = true
    override fun isModified(): Boolean = false
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {
        currentSessionId?.let { sessionId ->
            // Cancel any ongoing generation first to kill HTTP connections
            val currentState = testGenService.getCurrentState(sessionId)
            if (currentState != null && !currentState.isTerminal) {
                LOG.info("Cancelling ongoing test generation during editor disposal: $sessionId")
                testGenService.cancelGeneration(sessionId, "Editor closed")
            }

            // Then cleanup the session
            testGenService.cleanupSession(sessionId)
            LOG.info("Session cleaned up on editor close: $sessionId")
        }

        // Unregister virtual file from file system
        TestGenerationFileSystem.INSTANCE.unregisterFile(virtualFile)
        LOG.info("Virtual file unregistered: ${virtualFile.sessionId}")

        // Close any open chat memory dialog
        if (mergerChatDialog != null) {
            if (WARN_POTENTIAL_ERRORS) {
                println("[WARN_DIALOG_ORPHANED] Chat memory dialog still open during dispose, closing now")
            }
            mergerChatDialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
        mergerChatDialog = null
        currentMergerAgent = null

        // Stop any running timers
        if (blinkTimer != null || chatMemoryUpdateTimer != null) {
            if (WARN_POTENTIAL_ERRORS) {
                println("[WARN_TIMER_LEAK] Timers still running during dispose: blink=${blinkTimer != null}, chatMemory=${chatMemoryUpdateTimer != null}")
            }
            blinkTimer?.stop()
            chatMemoryUpdateTimer?.stop()
        }

        // Dispose of editor resources in panels
        testMergingPanel.dispose()
        generatedTestsPanel.dispose()
    }
    override fun setState(state: FileEditorState) {}
    override fun getFile(): VirtualFile = virtualFile
}

