package com.zps.zest.testgen.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.StateMachineTestGenerationService
import com.zps.zest.testgen.model.TestPlan
import com.zps.zest.testgen.statemachine.*
import com.zps.zest.testgen.ui.dialogs.MergedTestPreviewDialog
import com.zps.zest.testgen.ui.panels.*
import com.zps.zest.testgen.ui.model.*
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder

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
    }
    
    private val testGenService = project.getService(StateMachineTestGenerationService::class.java)
    private val component: JPanel
    private var currentSessionId: String? = null
    private var currentStateMachine: TestGenerationStateMachine? = null
    private var blinkTimer: javax.swing.Timer? = null
    private var chatMemoryUpdateTimer: javax.swing.Timer? = null
    
    // UI Components
    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var stateDisplayPanel: JPanel
    private lateinit var stateLabel: JBLabel
    private lateinit var stateDescription: JBLabel
    private lateinit var statusIndicator: JLabel
    private lateinit var actionPrompt: JLabel
    private lateinit var logArea: JBTextArea
    
    // Real-time display panels
    private lateinit var contextDisplayPanel: ContextDisplayPanel
    private lateinit var testPlanDisplayPanel: TestPlanDisplayPanel
    private lateinit var generatedTestsPanel: GeneratedTestsPanel
    private lateinit var streamingHelper: StreamingUIHelper
    
    // Simplified control system
    private lateinit var primaryActionButton: JButton
    private lateinit var cancelButton: JButton
    
    // Action banner components
    private lateinit var actionBanner: JPanel
    private lateinit var actionMessage: JBLabel
    private lateinit var embeddedActionButton: JButton
    
    // Event listener for state machine events and streaming updates
    private val eventListener = object : TestGenerationEventListener, StreamingEventListener {
        override fun onStateChanged(event: TestGenerationEvent.StateChanged) {
            SwingUtilities.invokeLater {
                updateStateDisplay(event.newState)
                updateControlButtons()
                
                // Update agent chat memories when state changes
                currentSessionId?.let { updateAgentChatMemories(it) }
                
                // Stop periodic updates if generation finished
                if (event.newState == TestGenerationState.COMPLETED || 
                    event.newState == TestGenerationState.FAILED || 
                    event.newState == TestGenerationState.CANCELLED) {
                    stopChatMemoryPeriodicUpdates()
                }
                
                val autoFlowStatus = if (currentStateMachine?.isAutoFlowEnabled == true) " [auto-flow]" else ""
                logEvent("State: ${event.oldState} → ${event.newState}$autoFlowStatus" + 
                        if (event.reason != null) " (${event.reason})" else "")
            }
        }
        
        override fun onActivityLogged(event: TestGenerationEvent.ActivityLogged) {
            SwingUtilities.invokeLater {
                // Show activity in the log
                logEvent("Activity: ${event.message}")
                
                // Update status indicator and clear action prompts
                statusIndicator.text = "🔄 ${event.message}"
                clearActionPrompt()
            }
        }
        
        override fun onErrorOccurred(event: TestGenerationEvent.ErrorOccurred) {
            SwingUtilities.invokeLater {
                updateControlButtons()
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
            SwingUtilities.invokeLater {
                updateControlButtons()
                logEvent("✓ Completed: ${event.summary}")
                
                // Auto-show preview dialog when fixing completes
                if (event.completedState == TestGenerationState.FIXING_TESTS) {
                    showFinalResult()
                }
            }
        }
        
        override fun onUserInputRequired(event: TestGenerationEvent.UserInputRequired) {
            SwingUtilities.invokeLater {
                updateButtonForState(null, false) // Update buttons
                logEvent("⚠️ USER ACTION REQUIRED: ${event.prompt}")
                
                when (event.inputType) {
                    "scenario_selection" -> {
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
                            "Your tests are ready to save",
                            "Save to File"
                        ) {
                            saveTestFile()
                        }
                        
                        primaryActionButton.text = "💾 Save Test File"
                        primaryActionButton.background = Color(76, 175, 80) // Green
                        primaryActionButton.isEnabled = true
                        
                        statusIndicator.text = "⏳ Tests ready to save"
                        logEvent("⚠️ ACTION REQUIRED: Click 'Save Test File' button to save the generated tests")
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
                // Switch to context tab to show new data
                tabbedPane.selectedIndex = 0
            }
        }
        
        override fun onTestPlanUpdated(data: TestPlanDisplayData) {
            SwingUtilities.invokeLater {
                testPlanDisplayPanel.updateTestPlan(data)
                // Switch to test plan tab
                tabbedPane.selectedIndex = 1
            }
        }
        
        override fun onTestGenerated(data: GeneratedTestDisplayData) {
            SwingUtilities.invokeLater {
                generatedTestsPanel.addGeneratedTest(data)
                // Switch to generated tests tab
                tabbedPane.selectedIndex = 2
            }
        }
        
        override fun onStatusChanged(status: String) {
            SwingUtilities.invokeLater {
                logEvent("Status: $status")
            }
        }
        
        override fun onProgressChanged(percent: Int, message: String) {
            SwingUtilities.invokeLater {
                // Legacy method - now just show status
                statusIndicator.text = "🔄 $message"
                logEvent("Status: $message")
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
    
    private fun setupUI() {
        // Top panel with action banner and state display
        val topPanel = JPanel(BorderLayout())
        topPanel.add(createActionBanner(), BorderLayout.NORTH)
        topPanel.add(createStateDisplayPanel(), BorderLayout.CENTER)
        component.add(topPanel, BorderLayout.NORTH)
        
        // Center with tabbed pane and log area
        val splitter = com.intellij.ui.JBSplitter(true, 0.7f)
        splitter.firstComponent = createTabbedPane()
        splitter.secondComponent = createLogPanel()
        component.add(splitter, BorderLayout.CENTER)
        
        // Bottom with simplified control buttons
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
    
    private fun createLogPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(0, 15, 0, 15)
        
        logArea = JBTextArea()
        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        logArea.background = UIUtil.getTextFieldBackground()
        logArea.text = "Test generation editor initialized\n"
        
        val scrollPane = JBScrollPane(logArea)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
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
        
        // Add a clear log button in the corner (small, unobtrusive)  
        val clearLogButton = JButton("Clear Log")
        clearLogButton.addActionListener { logArea.text = "" }
        clearLogButton.font = clearLogButton.font.deriveFont(10f)
        cancelPanel.add(clearLogButton)
        
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
            // Append to log area
            logArea.append(text)
            logArea.caretPosition = logArea.document.length
            
            // Process with streaming helper for real-time panel updates
            streamingHelper.processStreamingText(text)
        }
    }
    
    private fun startTestGeneration(request: com.zps.zest.testgen.model.TestGenerationRequest) {
        // Check if already running
        if (currentSessionId != null && currentStateMachine != null) {
            val currentState = currentStateMachine?.currentState
            if (currentState != null && currentState.isActive) {
                logEvent("Warning: Test generation already in progress (${currentState.displayName})")
                Messages.showWarningDialog(project, 
                    "Test generation is already in progress.\n\nCurrent state: ${currentState.displayName}", 
                    "Already Running")
                return
            }
        }
        
        logEvent("Starting test generation...")
        
        // Disable button immediately to prevent double-click
        primaryActionButton.isEnabled = false
        
        // Clear all panels and stop any existing timers
        contextDisplayPanel.clear()
        testPlanDisplayPanel.clear()
        generatedTestsPanel.clear()
        stopChatMemoryPeriodicUpdates() // Stop any existing periodic updates
        streamingHelper.reset()
        
        testGenService.startTestGeneration(request, eventListener, ::processStreamingText).thenAccept { stateMachine ->
            SwingUtilities.invokeLater {
                currentSessionId = stateMachine.sessionId
                currentStateMachine = stateMachine
                updateStateDisplay(stateMachine.currentState)
                updateControlButtons()
                updateAgentChatMemories(stateMachine.sessionId)
                startChatMemoryPeriodicUpdates() // Start periodic updates
                logEvent("Session started: ${stateMachine.sessionId}")
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                logEvent("ERROR: Failed to start: ${throwable.message}")
                Messages.showErrorDialog(project, 
                    "Failed to start test generation: ${throwable.message}", 
                    "Startup Error")
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
                    primaryActionButton.apply {
                        text = "▶️ Start Test Generation"
                        background = Color(76, 175, 80) // Green
                        isEnabled = true
                        isVisible = true
                    }
                    cancelButton.isVisible = false
                }
                
                state == TestGenerationState.AWAITING_USER_SELECTION -> {
                    val selectedScenarios = testPlanDisplayPanel.getSelectedTestScenarios()
                    val selectedCount = selectedScenarios.size
                    
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
                        text = "💾 Save Test File"
                        background = Color(76, 175, 80) // Green
                        isEnabled = true
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
            
            // Use new button state management
            updateButtonForState(currentState, hasError)
            
        } ?: run {
            // No active session
            updateButtonForState(null, false)
        }
    }
    
    
    private fun generateSelectedTests() {
        val selectedScenarios = testPlanDisplayPanel.getSelectedTestScenarios()
        if (selectedScenarios.isEmpty()) {
            com.zps.zest.ZestNotifications.showWarning(
                project,
                "No Scenarios Selected",
                "Please select at least one test scenario to generate tests"
            )
            return
        }
        
        currentSessionId?.let { sessionId ->
            if (testGenService.setUserSelection(sessionId, selectedScenarios)) {
                logEvent("User confirmed selection of ${selectedScenarios.size} scenarios - continuing generation")
                hideActionBanner()
                // Exit selection mode since selection was processed
                testPlanDisplayPanel.exitSelectionMode()
            } else {
                logEvent("Failed to set user selection")
                com.zps.zest.ZestNotifications.showWarning(
                    project,
                    "Selection Failed",
                    "Could not process scenario selection. Please try again."
                )
            }
        }
    }
    
    private fun saveTestFile() {
        currentStateMachine?.let { stateMachine ->
            val mergedTestClass = stateMachine.sessionData["mergedTestClass"] as? 
                com.zps.zest.testgen.model.MergedTestClass
            
            if (mergedTestClass != null) {
                try {
                    val filePath = writeMergedTestToFile(mergedTestClass)
                    
                    val message = buildString {
                        appendLine("Test file written successfully!")
                        appendLine()
                        appendLine("File: ${mergedTestClass.fileName}")
                        appendLine("Location: $filePath")
                        if (mergedTestClass.hasInferredPath()) {
                            appendLine("Path source: AI-inferred from project structure analysis")
                        } else {
                            appendLine("Path source: Convention-based fallback")
                        }
                        appendLine("Methods: ${mergedTestClass.methodCount} test methods")
                        appendLine("Framework: ${mergedTestClass.framework}")
                    }
                    
                    // Open the saved file in IntelliJ editor
                    openFileInEditor(filePath)
                    
                    logEvent("💾 Test file saved and opened: $filePath")
                    hideActionBanner()
                    
                    // Update button to show completion
                    primaryActionButton.apply {
                        text = "✅ File Opened"
                        background = Color(76, 175, 80) // Green
                        isEnabled = false
                    }
                    
                } catch (e: Exception) {
                    logEvent("ERROR: Failed to save test file: ${e.message}")
                    Messages.showErrorDialog(
                        project,
                        "Failed to write test file:\n${e.message}",
                        "Write Error"
                    )
                }
            } else {
                logEvent("ERROR: No merged test class available to save")
                Messages.showErrorDialog(
                    project,
                    "No merged test class available for saving",
                    "Save Error"
                )
            }
        }
    }
    
    /**
     * Write a merged test class to file using AI-inferred path or fallback to standard structure
     */
    private fun writeMergedTestToFile(mergedTest: com.zps.zest.testgen.model.MergedTestClass): String {
        val targetFile: java.io.File
        
        if (mergedTest.hasInferredPath()) {
            // Use AI-inferred path from merger agent
            val inferredPath = mergedTest.fullFilePath!!
            targetFile = java.io.File(inferredPath)
            
            // Ensure parent directories exist
            val parentDir = targetFile.parentFile
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            
        } else {
            // Fallback to convention-based approach (legacy behavior)
            val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
            
            // Try standard test source root first
            var testSourceRoot = "$basePath/src/test/java"
            var testDir = java.io.File(testSourceRoot)
            
            if (!testDir.exists()) {
                // Fallback to simple test directory
                testSourceRoot = "$basePath/test"
                testDir = java.io.File(testSourceRoot)
                if (!testDir.exists()) {
                    testDir.mkdirs()
                }
            }
            
            // Create package directories
            val packagePath = mergedTest.packageName.replace('.', java.io.File.separatorChar)
            val packageDir = java.io.File(testDir, packagePath)
            if (!packageDir.exists()) {
                packageDir.mkdirs()
            }
            
            targetFile = java.io.File(packageDir, mergedTest.fileName)
        }
        
        // Write the test file
        targetFile.writeText(mergedTest.fullContent)
        
        return targetFile.absolutePath
    }
    
    /**
     * Open the saved test file in IntelliJ's editor
     */
    private fun openFileInEditor(filePath: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile != null) {
                    // Refresh the file system to ensure the file is recognized
                    virtualFile.refresh(false, false)
                    
                    // Open the file in editor
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    
                    logEvent("✅ Test file opened in editor: ${virtualFile.name}")
                } else {
                    logEvent("ERROR: Could not find virtual file for: $filePath")
                }
            } catch (e: Exception) {
                logEvent("ERROR: Failed to open file in editor: ${e.message}")
            }
        }
    }
    
    private fun showFinalResult() {
        currentStateMachine?.let { stateMachine ->
            val mergedTestClass = stateMachine.sessionData["mergedTestClass"] as? 
                com.zps.zest.testgen.model.MergedTestClass
            
            if (mergedTestClass != null) {
                logEvent("Showing preview dialog for: ${mergedTestClass.className}")
                
                // Create or get session
                var session = stateMachine.sessionData["session"] as? 
                    com.zps.zest.testgen.model.TestGenerationSession
                
                if (session == null) {
                    // Create a minimal session for the dialog
                    val request = stateMachine.sessionData["request"] as? 
                        com.zps.zest.testgen.model.TestGenerationRequest
                    if (request != null) {
                        session = com.zps.zest.testgen.model.TestGenerationSession(
                            stateMachine.sessionId,
                            request,
                            com.zps.zest.testgen.model.TestGenerationSession.Status.COMPLETED
                        )
                    }
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
        val timestamp = java.time.LocalTime.now().toString().substring(0, 8)
        logArea.append("[$timestamp] $message\n")
        logArea.caretPosition = logArea.document.length
    }
    
    /**
     * Update chat memory displays for all agent tabs
     */
    private fun updateAgentChatMemories(sessionId: String) {
        try {
            val stateMachine = testGenService.getStateMachine(sessionId)
            
            // Get ContextAgent memory
            val contextAgent = stateMachine?.sessionData?.get("contextAgent") as? com.zps.zest.testgen.agents.ContextAgent
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
            
            // Get TestWriterAgent memory  
            val testWriterAgent = stateMachine?.sessionData?.get("testWriterAgent") as? com.zps.zest.testgen.agents.TestWriterAgent
            generatedTestsPanel.setChatMemory(testWriterAgent?.getChatMemory(), "TestWriter")
            
            // Get CoordinatorAgent memory for planning
            val coordinatorAgent = stateMachine?.sessionData?.get("coordinatorAgent") as? com.zps.zest.testgen.agents.CoordinatorAgent
            println("[DEBUG] CoordinatorAgent: $coordinatorAgent")
            println("[DEBUG] CoordinatorAgent ChatMemory: ${coordinatorAgent?.getChatMemory()}")
            testPlanDisplayPanel.setChatMemory(coordinatorAgent?.getChatMemory(), "Coordinator")
            
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
        statusIndicator.text = "🔄 Processing..."
        hideActionBanner()
    }
    
    private fun handlePrimaryAction() {
        val sessionId = currentSessionId
        val state = sessionId?.let { testGenService.getCurrentState(it) }
        
        when {
            state == null -> {
                // Start generation
                virtualFile.request?.let { startTestGeneration(it) }
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
                // Save test file
                saveTestFile()
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
        currentSessionId?.let { testGenService.cleanupSession(it) }
    }
    override fun setState(state: FileEditorState) {}
    override fun getFile(): VirtualFile = virtualFile
}

