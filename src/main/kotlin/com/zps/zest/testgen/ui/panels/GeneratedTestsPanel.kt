package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.dialogs.TestCodeViewerDialog
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData
import com.zps.zest.langchain4j.ui.ChatMemoryPanel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel that displays generated tests with links to view code.
 * Shows validation status and provides access to full test code.
 */
class GeneratedTestsPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val testsListModel = DefaultListModel<GeneratedTestDisplayData>()
    private val testsList = JList(testsListModel)
    private val statusLabel = JBLabel("No tests generated yet")
    private val progressBar = JProgressBar()
    private val testsMap = mutableMapOf<String, GeneratedTestDisplayData>()
    private var chatMemoryPanel: ChatMemoryPanel? = null
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        
        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)
        headerPanel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("🧪 Generated Tests")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.WEST)
        
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(statusLabel, BorderLayout.EAST)
        
        add(headerPanel, BorderLayout.NORTH)
        
        // Tests list
        testsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        testsList.cellRenderer = TestListCellRenderer()
        testsList.visibleRowCount = 10
        
        // Add double-click listener to view test code
        testsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = testsList.locationToIndex(e.point)
                    if (index >= 0) {
                        val test = testsListModel.getElementAt(index)
                        showTestCode(test)
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(testsList)
        scrollPane.border = BorderFactory.createCompoundBorder(
            EmptyBorder(0, 10, 10, 10),
            BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        )
        
        // Create split panel: tests list (top) + chat memory (bottom)
        val splitter = com.intellij.ui.JBSplitter(true, 0.7f)
        splitter.firstComponent = scrollPane
        
        // Placeholder for chat memory
        val chatPlaceholder = JPanel(BorderLayout())
        chatPlaceholder.background = UIUtil.getPanelBackground()
        chatPlaceholder.add(JBLabel("TestWriter chat memory will appear here", SwingConstants.CENTER))
        splitter.secondComponent = chatPlaceholder
        
        add(splitter, BorderLayout.CENTER)
        
        // Bottom panel with progress and actions
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = EmptyBorder(5, 10, 10, 10)
        bottomPanel.background = UIUtil.getPanelBackground()
        
        // Progress bar
        progressBar.isStringPainted = true
        progressBar.isVisible = false
        bottomPanel.add(progressBar, BorderLayout.CENTER)
        
        // Actions panel
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        actionsPanel.isOpaque = false
        
        val viewCodeButton = JButton("View Selected Test")
        viewCodeButton.addActionListener {
            testsList.selectedValue?.let { showTestCode(it) }
        }
        actionsPanel.add(viewCodeButton)
        
        val exportButton = JButton("Export All Tests")
        exportButton.addActionListener { exportAllTests() }
        actionsPanel.add(exportButton)
        
        bottomPanel.add(actionsPanel, BorderLayout.EAST)
        
        add(bottomPanel, BorderLayout.SOUTH)
    }
    
    /**
     * Add a generated test to the display
     */
    fun addGeneratedTest(test: GeneratedTestDisplayData) {
        SwingUtilities.invokeLater {
            testsMap[test.testName] = test
            testsListModel.addElement(test)
            updateStatus()
        }
    }
    
    /**
     * Update test validation status
     */
    fun updateTestStatus(testName: String, status: GeneratedTestDisplayData.ValidationStatus) {
        SwingUtilities.invokeLater {
            testsMap[testName]?.let { test ->
                val updatedTest = test.copy(validationStatus = status)
                testsMap[testName] = updatedTest
                
                // Update in list model
                for (i in 0 until testsListModel.size()) {
                    if (testsListModel.getElementAt(i).testName == testName) {
                        testsListModel.setElementAt(updatedTest, i)
                        break
                    }
                }
            }
            updateStatus()
        }
    }
    
    /**
     * Show progress for test generation
     */
    fun showProgress(current: Int, total: Int) {
        SwingUtilities.invokeLater {
            if (total > 0) {
                progressBar.isVisible = true
                progressBar.minimum = 0
                progressBar.maximum = total
                progressBar.value = current
                progressBar.string = "Generating test $current of $total..."
            } else {
                progressBar.isVisible = false
            }
        }
    }
    
    /**
     * Clear all tests
     */
    fun clear() {
        SwingUtilities.invokeLater {
            testsMap.clear()
            testsListModel.clear()
            statusLabel.text = "No tests generated yet"
            progressBar.isVisible = false
        }
    }
    
    /**
     * Update status label
     */
    private fun updateStatus() {
        val total = testsMap.size
        if (total == 0) {
            statusLabel.text = "No tests generated yet"
            return
        }
        
        val passed = testsMap.values.count { 
            it.validationStatus == GeneratedTestDisplayData.ValidationStatus.PASSED 
        }
        val failed = testsMap.values.count { 
            it.validationStatus == GeneratedTestDisplayData.ValidationStatus.FAILED 
        }
        val warnings = testsMap.values.count { 
            it.validationStatus == GeneratedTestDisplayData.ValidationStatus.WARNINGS 
        }
        
        statusLabel.text = buildString {
            append("Total: $total")
            if (passed > 0) append(" | ✅ Passed: $passed")
            if (warnings > 0) append(" | ⚠️ Warnings: $warnings")
            if (failed > 0) append(" | ❌ Failed: $failed")
        }
    }
    
    /**
     * Show test code in viewer dialog
     */
    private fun showTestCode(test: GeneratedTestDisplayData) {
        val dialog = TestCodeViewerDialog(project, test)
        dialog.show()
    }
    
    /**
     * Export all tests to clipboard or file
     */
    private fun exportAllTests() {
        if (testsMap.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "No tests to export",
                "Export Tests",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }
        
        val allCode = testsMap.values.joinToString("\n\n") { test ->
            """
            |// Test: ${test.testName}
            |// Scenario: ${test.scenarioName}
            |// Status: ${test.validationStatus}
            |
            |${test.testCode}
            """.trimMargin()
        }
        
        // Copy to clipboard
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(allCode), null)
        
        JOptionPane.showMessageDialog(
            this,
            "All tests copied to clipboard",
            "Export Successful",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    /**
     * Get all generated tests for saving
     */
    fun getGeneratedTests(): List<GeneratedTestDisplayData> {
        return testsMap.values.toList()
    }
    
    /**
     * Custom cell renderer for test list
     */
    private class TestListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            )
            
            if (value is GeneratedTestDisplayData && component is JLabel) {
                val panel = JPanel(BorderLayout())
                panel.background = if (isSelected) list.selectionBackground else list.background
                panel.border = EmptyBorder(5, 10, 5, 10)
                
                // Test name with status icon
                val nameLabel = JBLabel("${value.getStatusIcon()} ${value.testName}")
                nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
                panel.add(nameLabel, BorderLayout.WEST)
                
                // Right side info
                val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
                rightPanel.isOpaque = false
                
                // Line count
                val linesLabel = JBLabel("${value.lineCount} lines")
                linesLabel.foreground = UIUtil.getContextHelpForeground()
                rightPanel.add(linesLabel)
                
                // Validation summary
                if (value.validationStatus != GeneratedTestDisplayData.ValidationStatus.NOT_VALIDATED) {
                    val validationLabel = JBLabel(value.getValidationSummary())
                    validationLabel.foreground = when (value.validationStatus) {
                        GeneratedTestDisplayData.ValidationStatus.PASSED -> Color(0, 128, 0)
                        GeneratedTestDisplayData.ValidationStatus.WARNINGS -> Color(255, 140, 0)
                        GeneratedTestDisplayData.ValidationStatus.FAILED -> Color(255, 0, 0)
                        else -> UIUtil.getContextHelpForeground()
                    }
                    rightPanel.add(validationLabel)
                }
                
                // View link
                val viewLabel = JBLabel("<html><a href='#'>View Code</a></html>")
                viewLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                rightPanel.add(viewLabel)
                
                panel.add(rightPanel, BorderLayout.EAST)
                
                return panel
            }
            
            return component
        }
    }
    
    /**
     * Set chat memory for the test writer agent and update the display
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory?, agentName: String = "TestWriter Agent") {
        SwingUtilities.invokeLater {
            // Dispose existing chat panel
            chatMemoryPanel?.dispose()
            
            // Create new chat memory panel
            chatMemoryPanel = if (chatMemory != null) {
                ChatMemoryPanel(project, chatMemory, "$agentName Memory")
            } else {
                null
            }
            
            // Update the split panel's second component
            val splitter = findComponentOfType(this, com.intellij.ui.JBSplitter::class.java)
            splitter?.let { split ->
                if (chatMemoryPanel != null) {
                    split.secondComponent = chatMemoryPanel
                } else {
                    val placeholder = JPanel(BorderLayout())
                    placeholder.background = UIUtil.getPanelBackground()
                    placeholder.add(JBLabel("No active $agentName chat memory", SwingConstants.CENTER))
                    split.secondComponent = placeholder
                }
                split.revalidate()
                split.repaint()
            }
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun <T> findComponentOfType(container: Container, type: Class<T>): T? {
        if (type.isInstance(container)) {
            return container as T
        }
        
        for (i in 0 until container.componentCount) {
            val component = container.getComponent(i)
            if (component is Container) {
                val found = findComponentOfType(component, type)
                if (found != null) {
                    return found
                }
            }
        }
        
        return null
    }
}