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
    
    // UI Components
    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var stateDisplayPanel: JPanel
    private lateinit var stateLabel: JBLabel
    private lateinit var stateDescription: JBLabel
    private lateinit var progressBar: JProgressBar
    private lateinit var logArea: JBTextArea
    
    // Real-time display panels
    private lateinit var contextDisplayPanel: ContextDisplayPanel
    private lateinit var testPlanDisplayPanel: TestPlanDisplayPanel
    private lateinit var generatedTestsPanel: GeneratedTestsPanel
    private lateinit var streamingHelper: StreamingUIHelper
    
    // Control buttons
    private lateinit var continueButton: JButton
    private lateinit var retryButton: JButton
    private lateinit var skipButton: JButton
    private lateinit var cancelButton: JButton
    private lateinit var showPreviewButton: JButton
    
    // Event listener for state machine events and streaming updates
    private val eventListener = object : TestGenerationEventListener, StreamingEventListener {
        override fun onStateChanged(event: TestGenerationEvent.StateChanged) {
            SwingUtilities.invokeLater {
                updateStateDisplay(event.newState)
                updateControlButtons()
                
                val autoFlowStatus = if (currentStateMachine?.isAutoFlowEnabled == true) " [auto-flow]" else ""
                logEvent("State: ${event.oldState} → ${event.newState}$autoFlowStatus" + 
                        if (event.reason != null) " (${event.reason})" else "")
            }
        }
        
        override fun onProgressUpdated(event: TestGenerationEvent.ProgressUpdated) {
            SwingUtilities.invokeLater {
                progressBar.value = event.progressPercent
                progressBar.string = event.message
                progressBar.isIndeterminate = event.progressPercent == 0
                logEvent("Progress: ${event.progressPercent}% - ${event.message}")
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
                
                // Auto-show preview dialog when generation completes
                if (event.completedState == TestGenerationState.COMPLETED) {
                    showFinalResult()
                }
            }
        }
        
        override fun onUserInputRequired(event: TestGenerationEvent.UserInputRequired) {
            SwingUtilities.invokeLater {
                updateControlButtons()
                logEvent("⚠ User input required: ${event.prompt}")
                
                if (event.inputType == "scenario_selection") {
                    showScenarioSelectionDialog(event.data as TestPlan)
                }
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
                progressBar.value = percent
                progressBar.string = message
                progressBar.isIndeterminate = percent == 0
                logEvent("Progress: $percent% - $message")
            }
        }
    }
    
    init {
        component = JPanel(BorderLayout())
        component.background = UIUtil.getPanelBackground()
        setupUI()
        initializeStreamingHelper()
        
        // Auto-start if we have a request
        virtualFile.request?.let { request ->
            SwingUtilities.invokeLater {
                startTestGeneration(request)
            }
        }
    }
    
    private fun setupUI() {
        // Header with state display
        component.add(createStateDisplayPanel(), BorderLayout.NORTH)
        
        // Center with tabbed pane and log area
        val splitter = com.intellij.ui.JBSplitter(true, 0.7f)
        splitter.firstComponent = createTabbedPane()
        splitter.secondComponent = createLogPanel()
        component.add(splitter, BorderLayout.CENTER)
        
        // Bottom with control buttons
        component.add(createControlPanel(), BorderLayout.SOUTH)
    }
    
    private fun createStateDisplayPanel(): JComponent {
        stateDisplayPanel = JPanel(BorderLayout())
        stateDisplayPanel.border = EmptyBorder(15, 15, 10, 15)
        stateDisplayPanel.background = UIUtil.getPanelBackground()
        
        // Left side - state info
        val stateInfoPanel = JPanel()
        stateInfoPanel.layout = BoxLayout(stateInfoPanel, BoxLayout.Y_AXIS)
        stateInfoPanel.isOpaque = false
        
        stateLabel = JBLabel("Ready")
        stateLabel.font = stateLabel.font.deriveFont(Font.BOLD, 16f)
        stateInfoPanel.add(stateLabel)
        
        stateDescription = JBLabel("Click Start to begin test generation")
        stateDescription.foreground = UIUtil.getContextHelpForeground()
        stateInfoPanel.add(stateDescription)
        
        stateDisplayPanel.add(stateInfoPanel, BorderLayout.WEST)
        
        // Right side - progress
        val progressPanel = JPanel(BorderLayout())
        progressPanel.isOpaque = false
        
        progressBar = JProgressBar()
        progressBar.isStringPainted = true
        progressBar.string = "Ready"
        progressBar.preferredSize = JBUI.size(250, 20)
        progressPanel.add(progressBar, BorderLayout.CENTER)
        
        stateDisplayPanel.add(progressPanel, BorderLayout.EAST)
        
        return stateDisplayPanel
    }
    
    private fun createLogPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(0, 15, 0, 15)
        
        logArea = JBTextArea()
        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        logArea.background = UIUtil.getTextFieldBackground()
        logArea.text = "Ready to start test generation...\n"
        
        val scrollPane = JBScrollPane(logArea)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createControlPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 15))
        panel.background = UIUtil.getPanelBackground()
        
        // Start button (only shown when idle)
        val startButton = JButton("Start Generation")
        startButton.addActionListener { 
            virtualFile.request?.let { startTestGeneration(it) }
        }
        panel.add(startButton)
        
        // Manual control buttons (normally disabled)
        continueButton = JButton("Continue")
        continueButton.addActionListener { continueExecution() }
        continueButton.isEnabled = false
        panel.add(continueButton)
        
        retryButton = JButton("Retry")
        retryButton.addActionListener { retryCurrentState() }
        retryButton.isEnabled = false
        panel.add(retryButton)
        
        skipButton = JButton("Skip")
        skipButton.addActionListener { skipCurrentState() }
        skipButton.isEnabled = false
        panel.add(skipButton)
        
        // Always available buttons
        cancelButton = JButton("Cancel")
        cancelButton.addActionListener { cancelGeneration() }
        cancelButton.isEnabled = false
        panel.add(cancelButton)
        
        // Show preview button (enabled when we have completed results)
        showPreviewButton = JButton("Show Preview")
        showPreviewButton.addActionListener { showFinalResult() }
        showPreviewButton.isEnabled = false
        panel.add(showPreviewButton)
        
        val clearLogButton = JButton("Clear Log")
        clearLogButton.addActionListener { logArea.text = "" }
        panel.add(clearLogButton)
        
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
        }
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
        logEvent("Starting test generation...")
        
        // Clear all panels
        contextDisplayPanel.clear()
        testPlanDisplayPanel.clear()
        generatedTestsPanel.clear()
        streamingHelper.reset()
        
        testGenService.startTestGeneration(request, eventListener, ::processStreamingText).thenAccept { stateMachine ->
            SwingUtilities.invokeLater {
                currentSessionId = stateMachine.sessionId
                currentStateMachine = stateMachine
                updateStateDisplay(stateMachine.currentState)
                updateControlButtons()
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
    
    private fun updateControlButtons() {
        currentSessionId?.let { sessionId ->
            val currentState = testGenService.getCurrentState(sessionId)
            val isAutoFlowing = currentStateMachine?.isAutoFlowEnabled ?: false
            val hasError = testGenService.canRetry(sessionId)
            
            // Only show manual control buttons when there's an error or auto-flow is disabled
            val showManualControls = hasError || !isAutoFlowing
            
            continueButton.isEnabled = showManualControls && testGenService.canContinueManually(sessionId)
            retryButton.isEnabled = showManualControls && testGenService.canRetry(sessionId)
            skipButton.isEnabled = showManualControls && testGenService.canSkip(sessionId)
            
            // Always show cancel button for active states
            cancelButton.isEnabled = currentState != null && !currentState.isTerminal
            
            // Enable show preview button when we have completed results
            showPreviewButton.isEnabled = currentState == TestGenerationState.COMPLETED && 
                currentStateMachine?.sessionData?.get("mergedTestClass") != null
            
            // Update button text based on current state
            when (currentState) {
                TestGenerationState.GATHERING_CONTEXT -> {
                    continueButton.text = "Continue Context"
                    skipButton.text = "Skip Context"
                }
                TestGenerationState.PLANNING_TESTS -> {
                    continueButton.text = "Continue Planning"
                    skipButton.text = "Skip Planning"
                }
                TestGenerationState.GENERATING_TESTS -> {
                    continueButton.text = "Continue Generation"
                    skipButton.text = "Skip Generation"
                }
                TestGenerationState.MERGING_TESTS -> {
                    continueButton.text = "Continue Merging"
                }
                else -> {
                    continueButton.text = "Continue"
                    skipButton.text = "Skip"
                }
            }
        } ?: run {
            // No active session
            continueButton.isEnabled = false
            retryButton.isEnabled = false
            skipButton.isEnabled = false
            cancelButton.isEnabled = false
            showPreviewButton.isEnabled = false
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
    
    private fun showScenarioSelectionDialog(testPlan: com.zps.zest.testgen.model.TestPlan) {
        val dialog = ScenarioSelectionDialog(project, testPlan)
        if (dialog.showAndGet()) {
            val selectedScenarios = dialog.getSelectedScenarios()
            currentSessionId?.let { sessionId ->
                if (testGenService.setUserSelection(sessionId, selectedScenarios)) {
                    logEvent("User selected ${selectedScenarios.size} scenarios - auto-flow will continue")
                } else {
                    logEvent("Failed to set user selection")
                }
            }
        }
    }
    
    private fun logEvent(message: String) {
        val timestamp = java.time.LocalTime.now().toString().substring(0, 8)
        logArea.append("[$timestamp] $message\n")
        logArea.caretPosition = logArea.document.length
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

/**
 * Simple scenario selection dialog
 */
private class ScenarioSelectionDialog(
    project: Project,
    private val testPlan: TestPlan
) : com.intellij.openapi.ui.DialogWrapper(project) {
    
    private val checkBoxes = mutableListOf<JCheckBox>()
    
    init {
        title = "Select Test Scenarios"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        val header = JBLabel("<html><h3>Select scenarios to generate:</h3></html>")
        panel.add(header)
        panel.add(Box.createVerticalStrut(10))
        
        testPlan.testScenarios.forEach { scenario ->
            val checkBox = JCheckBox(scenario.name, true)
            checkBox.toolTipText = scenario.description
            checkBoxes.add(checkBox)
            panel.add(checkBox)
        }
        
        return JBScrollPane(panel)
    }
    
    fun getSelectedScenarios(): List<TestPlan.TestScenario> {
        return checkBoxes.mapIndexedNotNull { index, checkBox ->
            if (checkBox.isSelected) testPlan.testScenarios[index] else null
        }
    }
}