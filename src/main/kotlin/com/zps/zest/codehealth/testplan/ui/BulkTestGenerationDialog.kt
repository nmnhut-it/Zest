package com.zps.zest.codehealth.testplan.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.testplan.generation.TestGenerationService
import com.zps.zest.codehealth.testplan.models.*
import com.zps.zest.codehealth.testplan.storage.TestPlanStorage
import com.zps.zest.codehealth.testplan.storage.TestPlanStorage.TestPlanStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.*
import javax.swing.*

/**
 * Dialog for bulk test generation from stored test plans
 */
class BulkTestGenerationDialog(private val project: Project) : DialogWrapper(project) {
    
    private lateinit var frameworkCombo: JComboBox<TestFramework>
    private lateinit var mockingCombo: JComboBox<MockingFramework>
    private lateinit var targetDirField: TextFieldWithBrowseButton
    private lateinit var progressBar: JProgressBar
    private lateinit var statusLabel: JLabel
    private lateinit var testPlansList: JBList<TestPlanData>
    
    private val storage = TestPlanStorage.getInstance(project)
    private val testGenerationService = TestGenerationService.getInstance(project)
    private val coroutineScope = CoroutineScope(Dispatchers.Swing)
    
    init {
        title = "Generate All Tests from Plans"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val pendingPlans = storage.getAllPendingTestPlans()
        
        return panel {
            row("Test Framework:") { 
                frameworkCombo = JComboBox(TestFramework.entries.toTypedArray()).apply {
                    selectedItem = TestFramework.JUNIT5
                }
                cell(frameworkCombo)
            }
            row("Mocking Framework:") { 
                mockingCombo = JComboBox(MockingFramework.entries.toTypedArray()).apply {
                    selectedItem = MockingFramework.MOCKITO
                }
                cell(mockingCombo)
            }
            row("Target Directory:") {
                targetDirField = TextFieldWithBrowseButton().apply {
                    text = "src/test/java"
                    addBrowseFolderListener("Select Target Directory", "Select directory for generated tests", 
                        project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
                }
                cell(targetDirField)
            }
            
            row {
                val scrollPane = JBScrollPane().apply {
                    preferredSize = Dimension(700, 250)
                    testPlansList = JBList<TestPlanData>().apply {
                        setListData(pendingPlans.toTypedArray())
                    }
                    testPlansList.cellRenderer = TestPlanListCellRenderer()
                    setViewportView(testPlansList)
                }
                cell(scrollPane)
            }.rowComment("${pendingPlans.size} test plans ready for generation")
            
            row {
                progressBar = JProgressBar(0, 100).apply {
                    isStringPainted = true
                    string = "Ready to generate tests"
                }
                cell(progressBar)
            }
            
            row {
                statusLabel = JLabel("Click OK to start generating test files").apply {
                    foreground = UIUtil.getInactiveTextColor()
                }
                cell(statusLabel)
            }
            
            // Statistics row
            val stats = storage.getStatistics()
            row {
                val statsPanel = createStatisticsPanel(stats)
                cell(statsPanel)
            }
        }
    }
    
    private fun createStatisticsPanel(stats: TestPlanStatistics): JComponent {
        val panel = JPanel(GridLayout(2, 3, 10, 5))
        panel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            JBUI.Borders.empty(10)
        )
        
        // Top row
        panel.add(createStatCard("Total Plans", stats.totalPlans.toString()))
        panel.add(createStatCard("Pending", stats.pendingPlans.toString(), Color(255, 152, 0)))
        panel.add(createStatCard("Generated", stats.generatedPlans.toString(), Color(76, 175, 80)))
        
        // Bottom row
        panel.add(createStatCard("Avg Score", "${stats.averageTestabilityScore}/100"))
        panel.add(createStatCard("High Score (80+)", stats.highScorePlans.toString(), Color(76, 175, 80)))
        panel.add(createStatCard("Low Score (<60)", stats.lowScorePlans.toString(), Color(244, 67, 54)))
        
        return panel
    }
    
    private fun createStatCard(label: String, value: String, valueColor: Color? = null): JComponent {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.background = UIUtil.getPanelBackground()
        
        val labelComponent = JLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(10f)
        labelComponent.alignmentX = Component.CENTER_ALIGNMENT
        card.add(labelComponent)
        
        val valueComponent = JLabel(value)
        valueComponent.font = valueComponent.font.deriveFont(Font.BOLD, 14f)
        if (valueColor != null) {
            valueComponent.foreground = valueColor
        }
        valueComponent.alignmentX = Component.CENTER_ALIGNMENT
        card.add(valueComponent)
        
        return card
    }
    
    override fun doOKAction() {
        val pendingPlans = storage.getAllPendingTestPlans()
        if (pendingPlans.isEmpty()) {
            Messages.showInfoMessage(project, "No pending test plans found.", "Nothing to Generate")
            return
        }
        
        // Disable OK button and start generation
        okAction.isEnabled = false
        cancelAction.isEnabled = false
        
        startTestGeneration()
    }
    
    private fun startTestGeneration() {
        coroutineScope.launch {
            try {
                testGenerationService.generateAllTests(
                    framework = frameworkCombo.selectedItem as TestFramework,
                    mockingFramework = mockingCombo.selectedItem as MockingFramework,
                    targetDirectory = targetDirField.text
                ) { progress ->
                    SwingUtilities.invokeLater {
                        updateProgress(progress)
                    }
                }
                
                SwingUtilities.invokeLater {
                    val stats = storage.getStatistics()
                    Messages.showInfoMessage(
                        project,
                        "Successfully generated ${stats.generatedPlans} test files!\n\n" +
                        "Files created in: ${targetDirField.text}",
                        "Test Generation Complete"
                    )
                    close(OK_EXIT_CODE)
                }
                
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project, 
                        "Error generating tests: ${e.message}",
                        "Test Generation Failed"
                    )
                    // Re-enable buttons
                    okAction.isEnabled = true
                    cancelAction.isEnabled = true
                }
            }
        }
    }
    
    private fun updateProgress(progress: TestGenerationProgress) {
        val percentage = if (progress.total > 0) (progress.current * 100) / progress.total else 0
        progressBar.value = percentage
        progressBar.string = "${progress.current}/${progress.total} - $percentage%"
        statusLabel.text = progress.status
        
        // Update list to show generated items
        testPlansList.repaint()
    }
    
    /**
     * Custom cell renderer for test plan list
     */
    private class TestPlanListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (value is TestPlanData) {
                val methodName = formatMethodName(value.methodFqn)
                val scoreColor = when {
                    value.testabilityScore >= 80 -> "🟢"
                    value.testabilityScore >= 60 -> "🟡"
                    else -> "🔴"
                }
                
                val statusIcon = if (value.isGenerated) "✅" else "⏳"
                text = "$statusIcon $scoreColor $methodName (${value.testabilityScore}/100)"
                
                if (value.isGenerated) {
                    foreground = if (isSelected) Color.WHITE else UIUtil.getInactiveTextColor()
                }
            }
            
            return this
        }
        
        private fun formatMethodName(fqn: String): String {
            return if (fqn.contains(":")) {
                // JS/TS file with line numbers
                val colonIndex = fqn.lastIndexOf(":")
                val filePath = fqn.substring(0, colonIndex)
                val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
                val lineInfo = fqn.substring(colonIndex)
                "$fileName$lineInfo"
            } else {
                // Java method FQN - show class.method
                val parts = fqn.split(".")
                if (parts.size >= 2) {
                    "${parts[parts.size - 2]}.${parts.last()}"
                } else {
                    fqn
                }
            }
        }
    }
}