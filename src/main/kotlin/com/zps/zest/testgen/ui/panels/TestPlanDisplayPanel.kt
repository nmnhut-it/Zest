package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.dialogs.ScenarioDetailDialog
import com.zps.zest.testgen.ui.model.ScenarioDisplayData
import com.zps.zest.testgen.ui.model.TestPlanDisplayData
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.langchain4j.ui.DialogManager
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel that displays test plan with selectable scenarios.
 * Shows checkboxes for scenario selection and links to view details.
 */
class TestPlanDisplayPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private var currentPlan: TestPlanDisplayData? = null
    private val scenarioCheckboxes = mutableMapOf<String, JCheckBox>()
    private val scenariosPanel = JPanel()
    private val headerLabel = JBLabel("Test plans are not available yet")
    private val summaryLabel = JBLabel("")
    private var selectionListener: ((Set<String>) -> Unit)? = null
    private var planningAgentMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var planningAgentName: String = "Planning Agent"

    // Selection mode fields
    private var isSelectionMode = false
    private var originalTestPlan: com.zps.zest.testgen.model.TestPlan? = null
    private val scenarioIdToTestScenario = mutableMapOf<String, com.zps.zest.testgen.model.TestPlan.TestScenario>()

    // Testing notes editor
    private val testingNotesArea = JTextArea()
    // Removed confirmSelectionCallback - using direct selection
    
    // Removed confirmation UI - using main editor button instead
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        
        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)
        headerPanel.background = UIUtil.getPanelBackground()
        
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(headerLabel, BorderLayout.NORTH)
        
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.border = EmptyBorder(5, 0, 0, 0)
        headerPanel.add(summaryLabel, BorderLayout.CENTER)
        
        // Control buttons
        val controlPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        controlPanel.isOpaque = false
        
        val selectAllButton = JButton("Select All")
        selectAllButton.addActionListener { selectAll() }
        controlPanel.add(selectAllButton)
        
        val selectNoneButton = JButton("Select None")
        selectNoneButton.addActionListener { selectNone() }
        controlPanel.add(selectNoneButton)

        // Add scenario button (only in selection mode)
        val addScenarioButton = JButton("➕ Add Scenario")
        addScenarioButton.addActionListener { addNewScenario() }
        addScenarioButton.toolTipText = "Add a custom test scenario"
        controlPanel.add(addScenarioButton)

        headerPanel.add(controlPanel, BorderLayout.EAST)
        
        add(headerPanel, BorderLayout.NORTH)

        // Main content panel
        val mainContentPanel = JPanel(BorderLayout())
        mainContentPanel.background = UIUtil.getPanelBackground()

        // Testing notes section
        val testingNotesPanel = JPanel(BorderLayout())
        testingNotesPanel.border = EmptyBorder(10, 10, 5, 10)
        testingNotesPanel.background = UIUtil.getPanelBackground()

        val notesLabel = JBLabel("Testing Approach (click to edit):")
        notesLabel.font = notesLabel.font.deriveFont(Font.BOLD, 12f)
        testingNotesPanel.add(notesLabel, BorderLayout.NORTH)

        testingNotesArea.apply {
            rows = 3
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
                EmptyBorder(5, 5, 5, 5)
            )
            font = Font("Monospaced", Font.PLAIN, 12)
            toolTipText = "Edit to customize test framework, setup/teardown, and testing approach"
        }

        val notesScrollPane = JBScrollPane(testingNotesArea)
        notesScrollPane.preferredSize = Dimension(0, 80)
        testingNotesPanel.add(notesScrollPane, BorderLayout.CENTER)

        mainContentPanel.add(testingNotesPanel, BorderLayout.NORTH)

        // Scenarios panel
        scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
        scenariosPanel.background = UIUtil.getPanelBackground()
        scenariosPanel.border = EmptyBorder(0, 10, 10, 10)

        val scrollPane = JBScrollPane(scenariosPanel)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        mainContentPanel.add(scrollPane, BorderLayout.CENTER)

        add(mainContentPanel, BorderLayout.CENTER)
        
        // Bottom panel with chat button
        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        bottomPanel.border = EmptyBorder(5, 10, 5, 10)
        bottomPanel.background = UIUtil.getPanelBackground()
        
        val chatButton = JButton("💬 Planning Chat")
        chatButton.addActionListener { openPlanningChatDialog() }
        chatButton.toolTipText = "View planning agent chat memory"
        bottomPanel.add(chatButton)
        
        add(bottomPanel, BorderLayout.SOUTH)
        
        // No confirmation panel needed - using main editor button
    }
    
    // Confirmation panel removed - using main editor button
    
    /**
     * Set test plan for scenario selection mode
     */
    fun setTestPlanForSelection(testPlan: com.zps.zest.testgen.model.TestPlan) {
        SwingUtilities.invokeLater {
            isSelectionMode = true
            originalTestPlan = testPlan
            scenarioIdToTestScenario.clear()

            // Set the testing notes in the text area with visual emphasis
            testingNotesArea.text = testPlan.testingNotes
            testingNotesArea.isEditable = true
            // Use a subtle blue-tinted background for editable state (better contrast)
            testingNotesArea.background = if (com.zps.zest.util.ThemeUtils.isDarkTheme()) {
                java.awt.Color(45, 50, 60) // Slightly blue-tinted dark background
            } else {
                java.awt.Color(240, 245, 255) // Very light blue for light theme
            }
            testingNotesArea.caretPosition = 0 // Scroll to top

            // Convert to display data and populate mapping
            val displayData = TestPlanDisplayData.fromTestPlan(testPlan)
            testPlan.testScenarios.forEachIndexed { index, scenario ->
                val scenarioId = "scenario_${scenario.name.hashCode()}"
                scenarioIdToTestScenario[scenarioId] = scenario
            }

            // Update the display but don't show "generating" status
            currentPlan = displayData
            scenarioCheckboxes.clear()
            scenariosPanel.removeAll()

            // Add scenarios
            displayData.scenarios.forEach { scenario ->
                val scenarioTestScenario = scenarioIdToTestScenario["scenario_${scenario.name.hashCode()}"]
                addScenarioRow(scenario, scenarioTestScenario != null)
            }

            // Update header with USER ACTION REQUIRED prompt
            headerLabel.text = "⚠️ USER ACTION REQUIRED: Review Test Plan for ${testPlan.targetClass}"
            summaryLabel.text = "✏️ Edit testing approach below and select scenarios, then click 'Generate Selected Tests' button to proceed"

            revalidate()
            repaint()
        }
    }
    
    // Callback removed - using direct selection via main editor button
    
    /**
     * Update the test plan display (during agent generation)
     */
    fun updateTestPlan(plan: TestPlanDisplayData) {
        SwingUtilities.invokeLater {
            currentPlan = plan
            scenarioCheckboxes.clear()
            scenariosPanel.removeAll()

            // During generation, testing notes area is not editable and has normal background
            testingNotesArea.isEditable = false
            testingNotesArea.background = UIUtil.getTextFieldBackground()

            // Show planning in progress
            testingNotesArea.text = "Planning in progress..."

            // Update header - show that agent is still working
            headerLabel.text = "🔄 Generating Test Plan: ${plan.targetClass}"
            summaryLabel.text = buildString {
                append("Agent is analyzing code and identifying test scenarios... ")
                if (plan.totalScenarios > 0) {
                    append("${plan.totalScenarios} scenario(s) found")
                }
            }

            // Show progress indicator or placeholder while generating
            if (plan.scenarios.isEmpty()) {
                // Show a loading/progress message
                val loadingPanel = JPanel(BorderLayout())
                loadingPanel.background = UIUtil.getPanelBackground()
                loadingPanel.border = EmptyBorder(50, 20, 50, 20)

                val loadingLabel = JBLabel("🤖 CoordinatorAgent is analyzing your code...")
                loadingLabel.horizontalAlignment = SwingConstants.CENTER
                loadingLabel.font = loadingLabel.font.deriveFont(14f)
                loadingPanel.add(loadingLabel, BorderLayout.CENTER)

                val detailsLabel = JBLabel("Please wait while test scenarios are being identified")
                detailsLabel.horizontalAlignment = SwingConstants.CENTER
                detailsLabel.foreground = UIUtil.getContextHelpForeground()
                loadingPanel.add(detailsLabel, BorderLayout.SOUTH)

                scenariosPanel.add(loadingPanel)
            } else {
                // Add scenarios as they come in
                plan.scenarios.forEach { scenario ->
                    addScenarioRow(scenario, plan.isScenarioSelected(scenario.id))
                }
            }

            scenariosPanel.revalidate()
            scenariosPanel.repaint()
        }
    }
    
    /**
     * Add a scenario row with checkbox and details link
     */
    private fun addScenarioRow(scenario: ScenarioDisplayData, selected: Boolean) {
        val rowPanel = JPanel(BorderLayout())
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, 60)
        rowPanel.background = UIUtil.getPanelBackground()
        rowPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor()),
            EmptyBorder(12, 8, 12, 8)
        )
        
        // Left: Checkbox with scenario name
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        leftPanel.isOpaque = false
        
        val checkbox = JCheckBox(scenario.name, selected)
        checkbox.addActionListener { 
            notifySelectionChange()
        }
        scenarioCheckboxes[scenario.id] = checkbox
        leftPanel.add(checkbox)
        
        // Priority indicator
        val priorityLabel = JBLabel(scenario.getPriorityIcon())
        priorityLabel.toolTipText = scenario.priority.displayName
        leftPanel.add(priorityLabel)
        
        // Status indicator
        val statusLabel = JBLabel(scenario.getStatusIcon())
        statusLabel.toolTipText = scenario.generationStatus.name
        leftPanel.add(statusLabel)
        
        rowPanel.add(leftPanel, BorderLayout.WEST)
        
        // Right: Details and Edit links
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        rightPanel.isOpaque = false

        // Edit link (only in selection mode)
        if (isSelectionMode) {
            val editLabel = JBLabel("<html><a href='#'>✏️ Edit</a></html>")
            editLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            editLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            editLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    editScenario(scenario)
                }
            })
            rightPanel.add(editLabel)
        }

        val detailsLabel = JBLabel("<html><a href='#'>View Details</a></html>")
        detailsLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        detailsLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        detailsLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showScenarioDetails(scenario)
            }
        })
        rightPanel.add(detailsLabel)
        
        rowPanel.add(rightPanel, BorderLayout.EAST)
        
        scenariosPanel.add(rowPanel)
        scenariosPanel.add(Box.createVerticalStrut(5))
    }
    
    /**
     * Show scenario details dialog
     */
    private fun showScenarioDetails(scenario: ScenarioDisplayData) {
        val dialog = ScenarioDetailDialog(project, scenario)
        dialog.show()
    }

    /**
     * Edit a scenario
     */
    private fun editScenario(scenario: ScenarioDisplayData) {
        if (!isSelectionMode || originalTestPlan == null) return

        val dialog = ScenarioDetailDialog(project, scenario, true) { updatedScenario ->
            // Find the corresponding TestScenario and update it
            val scenarioId = "scenario_${scenario.name.hashCode()}"
            val testScenario = scenarioIdToTestScenario[scenarioId]

            if (testScenario != null) {
                // Find index of the scenario in the test plan
                val index = originalTestPlan?.testScenarios?.indexOf(testScenario) ?: -1

                if (index >= 0) {
                    // Create updated TestScenario with new values
                    val updatedTestScenario = com.zps.zest.testgen.model.TestPlan.TestScenario(
                        updatedScenario.name,
                        updatedScenario.description,
                        com.zps.zest.testgen.model.TestPlan.TestScenario.Type.valueOf(updatedScenario.category),
                        updatedScenario.inputs,
                        updatedScenario.expectedOutcome,
                        com.zps.zest.testgen.model.TestPlan.TestScenario.Priority.valueOf(updatedScenario.priority.name)
                    )

                    // Update the test plan
                    originalTestPlan?.updateScenario(index, updatedTestScenario)

                    // Update mapping with new ID if name changed
                    if (scenario.name != updatedScenario.name) {
                        scenarioIdToTestScenario.remove(scenarioId)
                        val newScenarioId = "scenario_${updatedScenario.name.hashCode()}"
                        scenarioIdToTestScenario[newScenarioId] = updatedTestScenario
                    } else {
                        scenarioIdToTestScenario[scenarioId] = updatedTestScenario
                    }

                    // Refresh display
                    originalTestPlan?.let { plan ->
                        setTestPlanForSelection(plan)
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Add a new custom scenario
     */
    private fun addNewScenario() {
        if (!isSelectionMode || originalTestPlan == null) return

        // Create a simple input dialog for quick scenario addition
        val panel = JPanel(GridLayout(4, 2, 5, 5))
        val nameField = JTextField()
        val descriptionField = JTextField()
        val typeCombo = JComboBox(arrayOf("UNIT", "INTEGRATION", "EDGE_CASE", "ERROR_HANDLING"))
        val priorityCombo = JComboBox(arrayOf("HIGH", "MEDIUM", "LOW"))

        panel.add(JLabel("Scenario Name:"))
        panel.add(nameField)
        panel.add(JLabel("Description:"))
        panel.add(descriptionField)
        panel.add(JLabel("Type:"))
        panel.add(typeCombo)
        panel.add(JLabel("Priority:"))
        panel.add(priorityCombo)

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            "Add New Test Scenario",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            val name = nameField.text.trim()
            val description = descriptionField.text.trim()

            if (name.isNotEmpty() && description.isNotEmpty()) {
                // Create new test scenario
                val newScenario = com.zps.zest.testgen.model.TestPlan.TestScenario(
                    name,
                    description,
                    com.zps.zest.testgen.model.TestPlan.TestScenario.Type.valueOf(typeCombo.selectedItem as String),
                    emptyList(), // User can add inputs later if needed
                    "Verify that $description", // Default expected outcome
                    com.zps.zest.testgen.model.TestPlan.TestScenario.Priority.valueOf(priorityCombo.selectedItem as String)
                )

                // Add to test plan
                originalTestPlan?.addScenario(newScenario)

                // Add to mapping
                val scenarioId = "scenario_${newScenario.name.hashCode()}"
                scenarioIdToTestScenario[scenarioId] = newScenario

                // Refresh display
                originalTestPlan?.let { plan ->
                    setTestPlanForSelection(plan)
                }
            }
        }
    }
    
    /**
     * Select all scenarios
     */
    private fun selectAll() {
        scenarioCheckboxes.values.forEach { it.isSelected = true }
        notifySelectionChange()
    }
    
    /**
     * Deselect all scenarios
     */
    private fun selectNone() {
        scenarioCheckboxes.values.forEach { it.isSelected = false }
        notifySelectionChange()
    }
    
    /**
     * Get selected scenario IDs
     */
    fun getSelectedScenarioIds(): Set<String> {
        return scenarioCheckboxes
            .filter { it.value.isSelected }
            .map { it.key }
            .toSet()
    }
    
    /**
     * Get selected scenarios as TestScenario objects (for selection mode)
     */
    fun getSelectedTestScenarios(): List<com.zps.zest.testgen.model.TestPlan.TestScenario> {
        if (!isSelectionMode) return emptyList()

        // Update the test plan with edited testing notes
        originalTestPlan?.testingNotes = testingNotesArea.text

        val selectedIds = getSelectedScenarioIds()
        return selectedIds.mapNotNull { id ->
            scenarioIdToTestScenario[id]
        }
    }

    /**
     * Get the edited testing notes
     */
    fun getEditedTestingNotes(): String {
        return testingNotesArea.text ?: ""
    }
    
    /**
     * Get total number of scenarios available for selection
     */
    fun getTotalScenarioCount(): Int {
        return scenarioCheckboxes.size
    }
    
    /**
     * Set selection change listener
     */
    fun setSelectionListener(listener: (Set<String>) -> Unit) {
        selectionListener = listener
    }
    
    /**
     * Notify about selection change
     */
    private fun notifySelectionChange() {
        selectionListener?.invoke(getSelectedScenarioIds())
    }
    
    // Selection status and confirmation methods removed - using main editor button
    
    /**
     * Exit selection mode and return to display mode
     */
    fun exitSelectionMode() {
        isSelectionMode = false
        originalTestPlan = null
        scenarioIdToTestScenario.clear()

        // Reset UI to non-editable state with proper background
        testingNotesArea.isEditable = false
        testingNotesArea.background = UIUtil.getTextFieldBackground()

        // Update header to show tests are being generated
        currentPlan?.let { plan ->
            val selectedCount = getSelectedScenarioIds().size
            headerLabel.text = "✅ Test Plan Confirmed: ${plan.targetClass}"
            summaryLabel.text = buildString {
                append("Generating tests for $selectedCount selected scenario(s)")
                if (testingNotesArea.text.isNotEmpty()) {
                    append(" | Approach: ${testingNotesArea.text.take(30)}...")
                }
            }
        }

        revalidate()
        repaint()
    }
    
    /**
     * Clear the display
     */
    fun clear() {
        SwingUtilities.invokeLater {
            // Exit selection mode if active
            if (isSelectionMode) {
                exitSelectionMode()
            }
            
            currentPlan = null
            scenarioCheckboxes.clear()
            scenariosPanel.removeAll()
            headerLabel.text = "No test plan available"
            summaryLabel.text = ""
            scenariosPanel.revalidate()
            scenariosPanel.repaint()
        }
    }
    
    /**
     * Update scenario status
     */
    fun updateScenarioStatus(scenarioId: String, status: ScenarioDisplayData.GenerationStatus) {
        SwingUtilities.invokeLater {
            // Find and update the scenario in the current plan
            currentPlan?.scenarios?.find { it.id == scenarioId }?.let { scenario ->
                // Update the status icon in the UI
                // This would require keeping references to the status labels
                // For now, we'll rebuild the display
                currentPlan?.let { updateTestPlan(it) }
            }
        }
    }
    
    /**
     * Get current test plan data for saving
     */
    fun getTestPlanData(): TestPlanDisplayData? {
        return currentPlan?.copy(selectedScenarios = getSelectedScenarioIds())
    }
    
    /**
     * Set chat memory for the planning agent
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory?, agentName: String = "Planning Agent") {
        this.planningAgentMemory = chatMemory
        this.planningAgentName = agentName
    }
    
    /**
     * Open planning chat memory dialog
     */
    private fun openPlanningChatDialog() {
        val dialog = ChatMemoryDialog(project, planningAgentMemory, planningAgentName)
        DialogManager.showDialog(dialog)
    }
}