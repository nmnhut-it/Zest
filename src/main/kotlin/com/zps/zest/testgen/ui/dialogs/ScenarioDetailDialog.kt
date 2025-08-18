package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
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
        mainPanel.preferredSize = JBUI.size(600, 500)
        
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
        
        // Description
        addSection(content, "Description", scenario.description)
        
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
    
    private fun addListSection(parent: JPanel, title: String, items: List<String>) {
        val titleLabel = JBLabel(title + ":")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        parent.add(titleLabel)
        
        items.forEachIndexed { index, item ->
            val itemLabel = JBLabel("  ${index + 1}. $item")
            itemLabel.alignmentX = Component.LEFT_ALIGNMENT
            itemLabel.border = EmptyBorder(2, 20, 2, 0)
            parent.add(itemLabel)
        }
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}