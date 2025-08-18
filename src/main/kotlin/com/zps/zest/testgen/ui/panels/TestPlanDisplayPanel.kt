package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.dialogs.ScenarioDetailDialog
import com.zps.zest.testgen.ui.model.ScenarioDisplayData
import com.zps.zest.testgen.ui.model.TestPlanDisplayData
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
    private val headerLabel = JBLabel("No test plan available")
    private val summaryLabel = JBLabel("")
    private var selectionListener: ((Set<String>) -> Unit)? = null
    
    // Selection mode fields
    private var isSelectionMode = false
    private var originalTestPlan: com.zps.zest.testgen.model.TestPlan? = null
    private val scenarioIdToTestScenario = mutableMapOf<String, com.zps.zest.testgen.model.TestPlan.TestScenario>()
    private var confirmSelectionCallback: ((List<com.zps.zest.testgen.model.TestPlan.TestScenario>) -> Unit)? = null
    
    // Confirmation UI components
    private lateinit var confirmationPanel: JPanel
    private lateinit var selectionStatusLabel: JBLabel
    private lateinit var confirmButton: JButton
    
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
        
        headerPanel.add(controlPanel, BorderLayout.EAST)
        
        add(headerPanel, BorderLayout.NORTH)
        
        // Scenarios panel
        scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
        scenariosPanel.background = UIUtil.getPanelBackground()
        scenariosPanel.border = EmptyBorder(0, 10, 10, 10)
        
        val scrollPane = JBScrollPane(scenariosPanel)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)
        
        // Confirmation panel (initially hidden)
        setupConfirmationPanel()
    }
    
    /**
     * Setup confirmation panel for selection mode
     */
    private fun setupConfirmationPanel() {
        confirmationPanel = JPanel(BorderLayout())
        confirmationPanel.background = UIUtil.getPanelBackground()
        confirmationPanel.border = EmptyBorder(10, 15, 10, 15)
        confirmationPanel.isVisible = false
        
        // Left side: Selection status
        selectionStatusLabel = JBLabel("No scenarios selected")
        selectionStatusLabel.foreground = UIUtil.getContextHelpForeground()
        confirmationPanel.add(selectionStatusLabel, BorderLayout.WEST)
        
        // Right side: Confirm button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.isOpaque = false
        
        confirmButton = JButton("Confirm Selection")
        confirmButton.isEnabled = false
        confirmButton.addActionListener { confirmSelection() }
        buttonPanel.add(confirmButton)
        
        val cancelButton = JButton("Cancel")
        cancelButton.addActionListener { exitSelectionMode() }
        buttonPanel.add(cancelButton)
        
        confirmationPanel.add(buttonPanel, BorderLayout.EAST)
        add(confirmationPanel, BorderLayout.SOUTH)
    }
    
    /**
     * Set test plan for scenario selection mode
     */
    fun setTestPlanForSelection(testPlan: com.zps.zest.testgen.model.TestPlan) {
        SwingUtilities.invokeLater {
            isSelectionMode = true
            originalTestPlan = testPlan
            scenarioIdToTestScenario.clear()
            
            // Convert to display data and populate mapping
            val displayData = TestPlanDisplayData.fromTestPlan(testPlan)
            testPlan.testScenarios.forEachIndexed { index, scenario ->
                val scenarioId = "scenario_${scenario.name.hashCode()}"
                scenarioIdToTestScenario[scenarioId] = scenario
            }
            
            // Update the display
            updateTestPlan(displayData)
            
            // Show confirmation panel and update header
            confirmationPanel.isVisible = true
            headerLabel.text = "📋 Select Test Scenarios: ${testPlan.targetClass}"
            summaryLabel.text = "Please select which scenarios to generate from ${testPlan.scenarioCount} available"
            
            // Update selection status
            updateSelectionStatus()
            
            revalidate()
            repaint()
        }
    }
    
    /**
     * Set callback for when selection is confirmed
     */
    fun setConfirmSelectionCallback(callback: (List<com.zps.zest.testgen.model.TestPlan.TestScenario>) -> Unit) {
        confirmSelectionCallback = callback
    }
    
    /**
     * Update the test plan display
     */
    fun updateTestPlan(plan: TestPlanDisplayData) {
        SwingUtilities.invokeLater {
            currentPlan = plan
            scenarioCheckboxes.clear()
            scenariosPanel.removeAll()
            
            // Update header
            headerLabel.text = "📋 Test Plan: ${plan.targetClass}"
            summaryLabel.text = buildString {
                append("Methods: ${plan.targetMethods.joinToString(", ")}")
                append(" | Type: ${plan.recommendedTestType}")
                append(" | ${plan.totalScenarios} scenario(s)")
            }
            
            // Add scenarios
            plan.scenarios.forEach { scenario ->
                addScenarioRow(scenario, plan.isScenarioSelected(scenario.id))
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
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, 40)
        rowPanel.background = UIUtil.getPanelBackground()
        rowPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor()),
            EmptyBorder(10, 5, 10, 5)
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
        
        // Right: Details link
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        rightPanel.isOpaque = false
        
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
        
        // Update selection status if in selection mode
        if (isSelectionMode) {
            updateSelectionStatus()
        }
    }
    
    /**
     * Update selection status display
     */
    private fun updateSelectionStatus() {
        val selectedCount = getSelectedScenarioIds().size
        val totalCount = scenarioCheckboxes.size
        
        selectionStatusLabel.text = when (selectedCount) {
            0 -> "No scenarios selected"
            totalCount -> "All $totalCount scenarios selected"
            else -> "$selectedCount of $totalCount scenarios selected"
        }
        
        confirmButton.isEnabled = selectedCount > 0
    }
    
    /**
     * Confirm the current selection
     */
    private fun confirmSelection() {
        if (!isSelectionMode || originalTestPlan == null) return
        
        val selectedIds = getSelectedScenarioIds()
        val selectedScenarios = selectedIds.mapNotNull { id ->
            scenarioIdToTestScenario[id]
        }
        
        if (selectedScenarios.isNotEmpty()) {
            confirmSelectionCallback?.invoke(selectedScenarios)
            exitSelectionMode()
        }
    }
    
    /**
     * Exit selection mode and return to display mode
     */
    private fun exitSelectionMode() {
        isSelectionMode = false
        originalTestPlan = null
        scenarioIdToTestScenario.clear()
        confirmSelectionCallback = null
        
        // Hide confirmation panel
        confirmationPanel.isVisible = false
        
        // Update header back to normal
        currentPlan?.let { plan ->
            headerLabel.text = "📋 Test Plan: ${plan.targetClass}"
            summaryLabel.text = buildString {
                append("Methods: ${plan.targetMethods.joinToString(", ")}")
                append(" | Type: ${plan.recommendedTestType}")
                append(" | ${plan.totalScenarios} scenario(s)")
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
}