package com.zps.zest.testgen.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.model.TestPlan
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for selecting which test scenarios to generate
 */
class TestScenarioSelectionDialog(
    project: Project,
    private val testPlan: TestPlan
) : DialogWrapper(project) {
    
    private val scenarioCheckBoxes = mutableMapOf<TestPlan.TestScenario, JBCheckBox>()
    private val selectedScenarios = mutableSetOf<TestPlan.TestScenario>()
    
    init {
        title = "Select Test Scenarios to Generate"
        setOKButtonText("Generate Selected Tests")
        setCancelButtonText("Cancel")
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Header panel
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Scenarios panel
        val scenariosPanel = createScenariosPanel()
        val scrollPane = JBScrollPane(scenariosPanel)
        scrollPane.preferredSize = Dimension(700, 400)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Button panel
        val buttonPanel = createButtonPanel()
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 10, 15)
        
        // Title
        val titleLabel = JBLabel("🧪 AI has planned ${testPlan.scenarioCount} test scenarios")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        
        panel.add(Box.createVerticalStrut(8))
        
        // Subtitle
        val subtitleLabel = JBLabel("Select which test scenarios you want to generate:")
        subtitleLabel.font = subtitleLabel.font.deriveFont(12f)
        subtitleLabel.foreground = UIUtil.getInactiveTextColor()
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(subtitleLabel)
        
        panel.add(Box.createVerticalStrut(5))
        
        // Target info
        val targetPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        targetPanel.background = UIUtil.getPanelBackground()
        targetPanel.alignmentX = Component.LEFT_ALIGNMENT
        
        targetPanel.add(JBLabel("Target: ").apply { 
            font = font.deriveFont(Font.BOLD, 11f)
        })
        targetPanel.add(JBLabel("${testPlan.targetClass}.${testPlan.targetMethod}").apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        })
        
        panel.add(targetPanel)
        
        // Separator
        panel.add(Box.createVerticalStrut(10))
        panel.add(JSeparator())
        
        return panel
    }
    
    private fun createScenariosPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(10, 15, 10, 15)
        
        // Group scenarios by type
        val groupedScenarios = testPlan.testScenarios.groupBy { it.type }
        
        for ((type, scenarios) in groupedScenarios) {
            // Type header
            val typeHeader = createTypeHeader(type, scenarios.size)
            panel.add(typeHeader)
            panel.add(Box.createVerticalStrut(8))
            
            // Scenarios for this type
            for (scenario in scenarios) {
                val scenarioCard = createScenarioCard(scenario)
                panel.add(scenarioCard)
                panel.add(Box.createVerticalStrut(8))
            }
            
            panel.add(Box.createVerticalStrut(10))
        }
        
        return panel
    }
    
    private fun createTypeHeader(type: TestPlan.TestScenario.Type, count: Int): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.maximumSize = Dimension(Integer.MAX_VALUE, 30)
        
        val label = JBLabel("${getTypeIcon(type)} ${type.displayName} ($count scenarios)")
        label.font = label.font.deriveFont(Font.BOLD, 13f)
        label.foreground = getTypeColor(type)
        
        panel.add(label, BorderLayout.WEST)
        
        return panel
    }
    
    private fun createScenarioCard(scenario: TestPlan.TestScenario): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(scenario.type), 2, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        card.maximumSize = Dimension(Integer.MAX_VALUE, 100)
        
        // Checkbox
        val checkBox = JBCheckBox()
        checkBox.isSelected = shouldSelectByDefault(scenario)
        checkBox.addActionListener {
            if (checkBox.isSelected) {
                selectedScenarios.add(scenario)
            } else {
                selectedScenarios.remove(scenario)
            }
            updateOKButton()
        }
        scenarioCheckBoxes[scenario] = checkBox
        
        // Initially add to selected if checked
        if (checkBox.isSelected) {
            selectedScenarios.add(scenario)
        }
        
        card.add(checkBox, BorderLayout.WEST)
        
        // Content panel
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.background = card.background
        contentPanel.border = EmptyBorder(0, 10, 0, 0)
        
        // Scenario name
        val nameLabel = JBLabel(scenario.name)
        nameLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        contentPanel.add(nameLabel)
        
        // Description
        if (scenario.description.isNotEmpty()) {
            val descLabel = JBLabel(scenario.description)
            descLabel.font = descLabel.font.deriveFont(11f)
            descLabel.foreground = UIUtil.getLabelForeground()
            contentPanel.add(descLabel)
        }
        
        // Priority and assertions
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        infoPanel.background = card.background
        
        val priorityLabel = JBLabel("${getPriorityIcon(scenario.priority)} ${scenario.priority.displayName}")
        priorityLabel.font = priorityLabel.font.deriveFont(10f)
        priorityLabel.foreground = UIUtil.getInactiveTextColor()
        infoPanel.add(priorityLabel)
        
        if (scenario.inputs.isNotEmpty()) {
            infoPanel.add(JBLabel(" • ").apply {
                font = font.deriveFont(10f)
                foreground = UIUtil.getInactiveTextColor()
            })
            infoPanel.add(JBLabel("${scenario.inputs.size} inputs").apply {
                font = font.deriveFont(10f)
                foreground = UIUtil.getInactiveTextColor()
            })
        }
        
        contentPanel.add(infoPanel)
        
        card.add(contentPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createButtonPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(10, 15, 10, 15)
        
        // Select All button
        val selectAllButton = JButton("Select All")
        selectAllButton.addActionListener {
            scenarioCheckBoxes.values.forEach { it.isSelected = true }
            selectedScenarios.clear()
            selectedScenarios.addAll(testPlan.testScenarios)
            updateOKButton()
        }
        panel.add(selectAllButton)
        
        // Select None button
        val selectNoneButton = JButton("Select None")
        selectNoneButton.addActionListener {
            scenarioCheckBoxes.values.forEach { it.isSelected = false }
            selectedScenarios.clear()
            updateOKButton()
        }
        panel.add(selectNoneButton)
        
        // Select by type buttons
        panel.add(Box.createHorizontalStrut(20))
        panel.add(JBLabel("Quick select:"))
        panel.add(Box.createHorizontalStrut(5))
        
        val selectUnitButton = JButton("Unit Tests Only")
        selectUnitButton.addActionListener {
            selectByType(TestPlan.TestScenario.Type.UNIT)
        }
        panel.add(selectUnitButton)
        
        val selectCriticalButton = JButton("Critical Only")
        selectCriticalButton.addActionListener {
            selectByPriority(TestPlan.TestScenario.Priority.HIGH)
        }
        panel.add(selectCriticalButton)
        
        return panel
    }
    
    private fun selectByType(type: TestPlan.TestScenario.Type) {
        selectedScenarios.clear()
        for ((scenario, checkBox) in scenarioCheckBoxes) {
            val shouldSelect = scenario.type == type
            checkBox.isSelected = shouldSelect
            if (shouldSelect) {
                selectedScenarios.add(scenario)
            }
        }
        updateOKButton()
    }
    
    private fun selectByPriority(minPriority: TestPlan.TestScenario.Priority) {
        selectedScenarios.clear()
        for ((scenario, checkBox) in scenarioCheckBoxes) {
            val shouldSelect = scenario.priority.ordinal >= minPriority.ordinal
            checkBox.isSelected = shouldSelect
            if (shouldSelect) {
                selectedScenarios.add(scenario)
            }
        }
        updateOKButton()
    }
    
    private fun shouldSelectByDefault(scenario: TestPlan.TestScenario): Boolean {
        // By default, select HIGH priority and UNIT tests
        return scenario.priority == TestPlan.TestScenario.Priority.HIGH ||
               scenario.type == TestPlan.TestScenario.Type.UNIT
    }
    
    private fun updateOKButton() {
        isOKActionEnabled = selectedScenarios.isNotEmpty()
        setOKButtonText(when (selectedScenarios.size) {
            0 -> "Select at least one test"
            1 -> "Generate 1 Test"
            else -> "Generate ${selectedScenarios.size} Tests"
        })
    }
    
    private fun getTypeIcon(type: TestPlan.TestScenario.Type): String {
        return when (type) {
            TestPlan.TestScenario.Type.UNIT -> "🔧"
            TestPlan.TestScenario.Type.INTEGRATION -> "🔗"
            TestPlan.TestScenario.Type.EDGE_CASE -> "⚠️"
            TestPlan.TestScenario.Type.ERROR_HANDLING -> "🛡️"
        }
    }
    
    private fun getPriorityIcon(priority: TestPlan.TestScenario.Priority): String {
        return when (priority) {
            TestPlan.TestScenario.Priority.HIGH -> "🔴"
            TestPlan.TestScenario.Priority.MEDIUM -> "🟡"
            TestPlan.TestScenario.Priority.LOW -> "🟢"
        }
    }
    
    private fun getTypeColor(type: TestPlan.TestScenario.Type): Color {
        return when (type) {
            TestPlan.TestScenario.Type.UNIT -> Color(76, 175, 80)
            TestPlan.TestScenario.Type.INTEGRATION -> Color(33, 150, 243)
            TestPlan.TestScenario.Type.EDGE_CASE -> Color(255, 152, 0)
            TestPlan.TestScenario.Type.ERROR_HANDLING -> Color(244, 67, 54)
        }
    }
    
    fun getSelectedScenarios(): List<TestPlan.TestScenario> {
        return selectedScenarios.toList()
    }
    
    override fun getPreferredFocusedComponent(): JComponent? {
        return scenarioCheckBoxes.values.firstOrNull()
    }
}