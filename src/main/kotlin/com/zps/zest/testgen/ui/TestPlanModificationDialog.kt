package com.zps.zest.testgen.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.model.TestPlan
import com.zps.zest.testgen.model.TestGenerationRequest
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for modifying test plans before generation
 */
class TestPlanModificationDialog(
    private val project: Project,
    private var testPlan: TestPlan
) : DialogWrapper(project) {
    
    private val scenarioPanels = mutableListOf<ScenarioPanel>()
    private lateinit var scenariosContainer: JPanel
    private lateinit var testTypeCombo: JComboBox<TestGenerationRequest.TestType>
    private lateinit var targetClassField: JBTextField
    private lateinit var targetMethodField: JBTextField
    
    init {
        title = "Modify Test Plan"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(800, 600)
        
        // Header panel with test plan metadata
        mainPanel.add(createHeaderPanel(), BorderLayout.NORTH)
        
        // Scrollable scenarios panel
        mainPanel.add(createScenariosPanel(), BorderLayout.CENTER)
        
        // Footer with actions
        mainPanel.add(createFooterPanel(), BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
            EmptyBorder(15, 15, 15, 15)
        )
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.anchor = GridBagConstraints.WEST
        
        // Target class
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JBLabel("Target Class:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        targetClassField = JBTextField(testPlan.targetClass)
        panel.add(targetClassField, gbc)
        
        // Target method
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JBLabel("Target Method:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        targetMethodField = JBTextField(testPlan.targetMethod)
        panel.add(targetMethodField, gbc)
        
        // Test type
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JBLabel("Test Type:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        testTypeCombo = JComboBox(TestGenerationRequest.TestType.values())
        testTypeCombo.selectedItem = testPlan.recommendedTestType
        panel.add(testTypeCombo, gbc)
        
        return panel
    }
    
    private fun createScenariosPanel(): JComponent {
        scenariosContainer = JPanel()
        scenariosContainer.layout = BoxLayout(scenariosContainer, BoxLayout.Y_AXIS)
        scenariosContainer.background = UIUtil.getPanelBackground()
        
        // Add existing scenarios
        testPlan.testScenarios.forEachIndexed { index, scenario ->
            val scenarioPanel = ScenarioPanel(index + 1, scenario)
            scenarioPanels.add(scenarioPanel)
            scenariosContainer.add(scenarioPanel)
            scenariosContainer.add(Box.createVerticalStrut(10))
        }
        
        val scrollPane = JBScrollPane(scenariosContainer)
        scrollPane.border = EmptyBorder(10, 10, 10, 10)
        
        return scrollPane
    }
    
    private fun createFooterPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0),
            EmptyBorder(10, 10, 10, 10)
        )
        
        val addScenarioButton = JButton("Add Scenario")
        addScenarioButton.addActionListener {
            addNewScenario()
        }
        panel.add(addScenarioButton)
        
        val removeEmptyButton = JButton("Remove Empty")
        removeEmptyButton.addActionListener {
            removeEmptyScenarios()
        }
        panel.add(removeEmptyButton)
        
        val validateButton = JButton("Validate")
        validateButton.addActionListener {
            validateTestPlan()
        }
        panel.add(validateButton)
        
        return panel
    }
    
    private fun addNewScenario() {
        val newScenario = TestPlan.TestScenario(
            "New Test Scenario",
            "Description of the test scenario",
            TestPlan.TestScenario.Type.UNIT,
            listOf("input1", "input2"),
            "Expected outcome",
            TestPlan.TestScenario.Priority.MEDIUM
        )
        
        val scenarioPanel = ScenarioPanel(scenarioPanels.size + 1, newScenario)
        scenarioPanels.add(scenarioPanel)
        scenariosContainer.add(scenarioPanel)
        scenariosContainer.add(Box.createVerticalStrut(10))
        scenariosContainer.revalidate()
        scenariosContainer.repaint()
    }
    
    private fun removeEmptyScenarios() {
        val toRemove = scenarioPanels.filter { it.isEmpty() }
        toRemove.forEach { panel ->
            scenarioPanels.remove(panel)
            scenariosContainer.remove(panel)
        }
        
        // Renumber remaining scenarios
        scenarioPanels.forEachIndexed { index, panel ->
            panel.updateNumber(index + 1)
        }
        
        scenariosContainer.revalidate()
        scenariosContainer.repaint()
        
        Messages.showInfoMessage(project, "Removed ${toRemove.size} empty scenarios", "Cleanup Complete")
    }
    
    private fun validateTestPlan() {
        val errors = mutableListOf<String>()
        
        if (targetClassField.text.isBlank()) {
            errors.add("Target class cannot be empty")
        }
        if (targetMethodField.text.isBlank()) {
            errors.add("Target method cannot be empty")
        }
        if (scenarioPanels.isEmpty()) {
            errors.add("At least one test scenario is required")
        }
        
        scenarioPanels.forEachIndexed { index, panel ->
            val scenarioErrors = panel.validateScenario()
            scenarioErrors.forEach { error ->
                errors.add("Scenario ${index + 1}: $error")
            }
        }
        
        if (errors.isEmpty()) {
            Messages.showInfoMessage(project, "Test plan is valid!", "Validation Successful")
        } else {
            Messages.showErrorDialog(
                project,
                "Validation errors:\n" + errors.joinToString("\n"),
                "Validation Failed"
            )
        }
    }
    
    override fun doOKAction() {
        // Validate before accepting
        val errors = mutableListOf<String>()
        
        if (targetClassField.text.isBlank()) {
            errors.add("Target class cannot be empty")
        }
        if (targetMethodField.text.isBlank()) {
            errors.add("Target method cannot be empty")
        }
        
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(
                project,
                errors.joinToString("\n"),
                "Invalid Test Plan"
            )
            return
        }
        
        // Build the modified test plan
        val modifiedScenarios = scenarioPanels.mapNotNull { it.getScenario() }
        
        testPlan = TestPlan(
            targetMethodField.text,
            targetClassField.text,
            modifiedScenarios,
            testPlan.dependencies,
            testTypeCombo.selectedItem as TestGenerationRequest.TestType,
            "Modified test plan"
        )
        
        super.doOKAction()
    }
    
    fun getModifiedTestPlan(): TestPlan = testPlan
    
    /**
     * Panel for editing individual test scenarios
     */
    private inner class ScenarioPanel(
        private var number: Int,
        private val scenario: TestPlan.TestScenario
    ) : JPanel() {
        
        private val nameField = JBTextField(scenario.name)
        private val descriptionArea = JBTextArea(scenario.description, 2, 40)
        private val typeCombo = JComboBox(TestPlan.TestScenario.Type.values())
        private val priorityCombo = JComboBox(TestPlan.TestScenario.Priority.values())
        private val inputsField = JBTextField(scenario.inputs.joinToString(", "))
        private val expectedField = JBTextField(scenario.expectedOutcome)
        private val removeButton = JButton("Remove")
        private val numberLabel = JBLabel("$number.")
        
        init {
            layout = GridBagLayout()
            background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(250, 250, 250)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(getTypeColor(), 3, 0, 0, 0),
                EmptyBorder(12, 15, 12, 15)
            )
            
            typeCombo.selectedItem = scenario.type
            priorityCombo.selectedItem = scenario.priority
            
            setupLayout()
            
            // Add remove action
            removeButton.addActionListener {
                removeThisScenario()
            }
            
            // Update border color when type changes
            typeCombo.addActionListener {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(getTypeColor(), 3, 0, 0, 0),
                    EmptyBorder(12, 15, 12, 15)
                )
            }
        }
        
        private fun setupLayout() {
            val gbc = GridBagConstraints()
            gbc.insets = JBUI.insets(3)
            gbc.anchor = GridBagConstraints.WEST
            
            // Row 1: Number and Name
            gbc.gridx = 0
            gbc.gridy = 0
            numberLabel.font = numberLabel.font.deriveFont(Font.BOLD, 14f)
            add(numberLabel, gbc)
            
            gbc.gridx = 1
            gbc.gridwidth = 3
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(nameField, gbc)
            
            gbc.gridx = 4
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(removeButton, gbc)
            
            // Row 2: Description
            gbc.gridx = 0
            gbc.gridy = 1
            add(JBLabel("Desc:"), gbc)
            
            gbc.gridx = 1
            gbc.gridwidth = 4
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true
            val scrollPane = JBScrollPane(descriptionArea)
            scrollPane.preferredSize = Dimension(0, 50)
            add(scrollPane, gbc)
            
            // Row 3: Type and Priority
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.weighty = 0.0
            add(JBLabel("Type:"), gbc)
            
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 0.3
            add(typeCombo, gbc)
            
            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JBLabel("Priority:"), gbc)
            
            gbc.gridx = 3
            gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 0.3
            add(priorityCombo, gbc)
            
            // Row 4: Inputs
            gbc.gridx = 0
            gbc.gridy = 3
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JBLabel("Inputs:"), gbc)
            
            gbc.gridx = 1
            gbc.gridwidth = 4
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(inputsField, gbc)
            
            // Row 5: Expected outcome
            gbc.gridx = 0
            gbc.gridy = 4
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JBLabel("Expected:"), gbc)
            
            gbc.gridx = 1
            gbc.gridwidth = 4
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(expectedField, gbc)
        }
        
        private fun getTypeColor(): Color {
            return when (typeCombo.selectedItem as? TestPlan.TestScenario.Type) {
                TestPlan.TestScenario.Type.UNIT -> Color(76, 175, 80)
                TestPlan.TestScenario.Type.INTEGRATION -> Color(33, 150, 243)
                TestPlan.TestScenario.Type.EDGE_CASE -> Color(255, 152, 0)
                TestPlan.TestScenario.Type.ERROR_HANDLING -> Color(244, 67, 54)
                else -> UIUtil.getBoundsColor()
            }
        }
        
        private fun removeThisScenario() {
            val parent = this@ScenarioPanel.parent
            scenarioPanels.remove(this)
            parent.remove(this)
            
            // Renumber remaining scenarios
            scenarioPanels.forEachIndexed { index, panel ->
                panel.updateNumber(index + 1)
            }
            
            parent.revalidate()
            parent.repaint()
        }
        
        fun updateNumber(newNumber: Int) {
            number = newNumber
            numberLabel.text = "$number."
        }
        
        fun isEmpty(): Boolean {
            return nameField.text.isBlank() && descriptionArea.text.isBlank()
        }
        
        fun validateScenario(): List<String> {
            val errors = mutableListOf<String>()
            
            if (nameField.text.isBlank()) {
                errors.add("Name cannot be empty")
            }
            if (descriptionArea.text.isBlank()) {
                errors.add("Description cannot be empty")
            }
            if (expectedField.text.isBlank()) {
                errors.add("Expected outcome cannot be empty")
            }
            
            return errors
        }
        
        fun getScenario(): TestPlan.TestScenario? {
            if (isEmpty()) return null
            
            val inputs = inputsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            
            return TestPlan.TestScenario(
                nameField.text,
                descriptionArea.text,
                typeCombo.selectedItem as TestPlan.TestScenario.Type,
                if (inputs.isEmpty()) listOf("default input") else inputs,
                expectedField.text.ifBlank { "Should execute successfully" },
                priorityCombo.selectedItem as TestPlan.TestScenario.Priority
            )
        }
    }
}