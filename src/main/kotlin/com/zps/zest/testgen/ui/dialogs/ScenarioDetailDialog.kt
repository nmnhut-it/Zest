package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.model.ScenarioDisplayData
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for viewing detailed information about a test scenario.
 */
class ScenarioDetailDialog(
    project: Project,
    private val scenario: ScenarioDisplayData
) : DialogWrapper(project) {
    
    init {
        title = "Scenario Details: ${scenario.name}"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(700, 600) // Increased size for better readability
        
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = EmptyBorder(10, 10, 10, 10)
        
        // Scenario name and priority
        addSection(content, "Scenario", scenario.name)
        addSection(content, "Priority", "${scenario.getPriorityIcon()} ${scenario.priority.displayName}")
        addSection(content, "Category", scenario.category)
        addSection(content, "Status", "${scenario.getStatusIcon()} ${scenario.generationStatus}")
        
        content.add(Box.createVerticalStrut(10))
        content.add(JSeparator())
        content.add(Box.createVerticalStrut(10))
        
        // Description - use special handling for long text
        addTextSection(content, "Description", scenario.description)

        // Test inputs
        if (scenario.inputs.isNotEmpty()) {
            content.add(Box.createVerticalStrut(5))
            addListSection(content, "Test Inputs", scenario.inputs)
        }

        // Expected outcome
        if (scenario.expectedOutcome.isNotEmpty()) {
            content.add(Box.createVerticalStrut(10))
            addTextSection(content, "Expected Outcome", scenario.expectedOutcome)
        }

        // Expected complexity
        addSection(content, "Expected Complexity", scenario.expectedComplexity)
        
        // Setup steps
        if (scenario.setupSteps.isNotEmpty()) {
            content.add(Box.createVerticalStrut(10))
            addListSection(content, "Setup Steps", scenario.setupSteps)
        }
        
        // Execution steps
        if (scenario.executionSteps.isNotEmpty()) {
            content.add(Box.createVerticalStrut(10))
            addListSection(content, "Execution Steps", scenario.executionSteps)
        }
        
        // Assertions
        if (scenario.assertions.isNotEmpty()) {
            content.add(Box.createVerticalStrut(10))
            addListSection(content, "Expected Assertions", scenario.assertions)
        }
        
        val scrollPane = JBScrollPane(content)
        scrollPane.border = BorderFactory.createEmptyBorder()
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun addSection(parent: JPanel, label: String, value: String) {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = EmptyBorder(5, 0, 5, 0)

        val labelComponent = JBLabel("$label:")
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        labelComponent.preferredSize = Dimension(120, labelComponent.preferredSize.height)
        panel.add(labelComponent, BorderLayout.WEST)

        val valueComponent = JBLabel(value)
        valueComponent.foreground = UIUtil.getLabelForeground()
        panel.add(valueComponent, BorderLayout.CENTER)

        parent.add(panel)
    }

    private fun addTextSection(parent: JPanel, label: String, value: String) {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = EmptyBorder(5, 0, 5, 0)
        panel.maximumSize = Dimension(Integer.MAX_VALUE, 100) // Limit height

        val labelComponent = JBLabel("$label:")
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        labelComponent.preferredSize = Dimension(120, labelComponent.preferredSize.height)
        labelComponent.verticalAlignment = SwingConstants.TOP
        panel.add(labelComponent, BorderLayout.WEST)

        // Use JBTextArea for text wrapping
        val textArea = JBTextArea(value)
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = UIUtil.getPanelBackground()
        textArea.foreground = UIUtil.getLabelForeground()
        textArea.font = UIUtil.getLabelFont()
        textArea.border = EmptyBorder(0, 0, 0, 0)

        panel.add(textArea, BorderLayout.CENTER)

        parent.add(panel)
    }
    
    private fun addListSection(parent: JPanel, title: String, items: List<String>) {
        // Main container panel with proper label alignment
        val containerPanel = JPanel(BorderLayout())
        containerPanel.isOpaque = false
        containerPanel.border = EmptyBorder(5, 0, 5, 0)

        // Title label aligned like other sections
        val titleLabel = JBLabel("$title:")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.preferredSize = Dimension(120, titleLabel.preferredSize.height)
        titleLabel.verticalAlignment = SwingConstants.TOP
        containerPanel.add(titleLabel, BorderLayout.WEST)

        // Items panel for the list
        val itemsPanel = JPanel()
        itemsPanel.layout = BoxLayout(itemsPanel, BoxLayout.Y_AXIS)
        itemsPanel.isOpaque = false

        items.forEachIndexed { index, item ->
            val itemPanel = JPanel(BorderLayout())
            itemPanel.isOpaque = false
            itemPanel.border = EmptyBorder(2, 0, 2, 0)

            // Number label
            val numberLabel = JBLabel("${index + 1}. ")
            numberLabel.verticalAlignment = SwingConstants.TOP
            numberLabel.preferredSize = Dimension(25, numberLabel.preferredSize.height)
            itemPanel.add(numberLabel, BorderLayout.WEST)

            // Item text with wrapping
            val itemText = JBTextArea(item)
            itemText.isEditable = false
            itemText.lineWrap = true
            itemText.wrapStyleWord = true
            itemText.background = UIUtil.getPanelBackground()
            itemText.foreground = UIUtil.getLabelForeground()
            itemText.font = UIUtil.getLabelFont()
            itemText.border = EmptyBorder(0, 0, 0, 0)
            itemPanel.add(itemText, BorderLayout.CENTER)

            itemsPanel.add(itemPanel)
        }

        containerPanel.add(itemsPanel, BorderLayout.CENTER)
        parent.add(containerPanel)
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}