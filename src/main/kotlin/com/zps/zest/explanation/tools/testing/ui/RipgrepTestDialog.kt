package com.zps.zest.explanation.tools.testing.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.explanation.tools.RipgrepCodeTool
import com.zps.zest.explanation.tools.testing.RipgrepManualTestCases
import com.zps.zest.explanation.tools.testing.RipgrepTestReporter
import java.awt.*
import java.awt.event.ActionEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Comprehensive testing dialog for RipgrepCodeTool.
 * Provides UI for manual regression testing with predefined test cases.
 */
class RipgrepTestDialog(private val project: Project) : DialogWrapper(project, true) {

    // Input components
    private val queryField = JBTextField(50)
    private val filePatternField = JBTextField(30)
    private val excludePatternField = JBTextField(30)
    private val contextLinesSpinner = JSpinner(SpinnerNumberModel(3, 0, 10, 1))
    private val beforeLinesSpinner = JSpinner(SpinnerNumberModel(0, 0, 10, 1))
    private val afterLinesSpinner = JSpinner(SpinnerNumberModel(0, 0, 10, 1))

    // Test mode selection
    private val basicSearchRadio = JRadioButton("Basic Search", true)
    private val findFilesRadio = JRadioButton("Find Files")
    private val searchWithContextRadio = JRadioButton("Search with Context")
    private val beforeContextRadio = JRadioButton("Before Context")
    private val afterContextRadio = JRadioButton("After Context")

    // Results display
    private val resultsArea = JBTextArea(20, 80)
    private val statusLabel = JBLabel("Ready")
    private val executionTimeLabel = JBLabel("")

    // Test cases
    private val testCasesCombo = JComboBox<RipgrepManualTestCases.TestCase>()

    // Tool instance
    private lateinit var ripgrepTool: RipgrepCodeTool
    private val relatedFiles = mutableSetOf<String>()
    private val usagePatterns = mutableListOf<String>()

    init {
        title = "RipgrepCodeTool Manual Testing"
        init()
        setupTool()
        loadTestCases()
    }

    private fun setupTool() {
        ripgrepTool = RipgrepCodeTool(project, relatedFiles, usagePatterns)
    }

    private fun loadTestCases() {
        testCasesCombo.removeAllItems()
        testCasesCombo.addItem(RipgrepManualTestCases.EMPTY_TEST_CASE)
        RipgrepManualTestCases.getAllTestCases().forEach { testCase ->
            testCasesCombo.addItem(testCase)
        }

        testCasesCombo.addActionListener {
            val selected = testCasesCombo.selectedItem as? RipgrepManualTestCases.TestCase
            if (selected != null && selected != RipgrepManualTestCases.EMPTY_TEST_CASE) {
                loadTestCase(selected)
            }
        }
    }

    private fun loadTestCase(testCase: RipgrepManualTestCases.TestCase) {
        queryField.text = testCase.query
        filePatternField.text = testCase.filePattern ?: ""
        excludePatternField.text = testCase.excludePattern ?: ""

        when (testCase.mode) {
            RipgrepManualTestCases.TestMode.BASIC_SEARCH -> basicSearchRadio.isSelected = true
            RipgrepManualTestCases.TestMode.FIND_FILES -> findFilesRadio.isSelected = true
            RipgrepManualTestCases.TestMode.SEARCH_WITH_CONTEXT -> {
                searchWithContextRadio.isSelected = true
                contextLinesSpinner.value = testCase.contextLines
            }
            RipgrepManualTestCases.TestMode.BEFORE_CONTEXT -> {
                beforeContextRadio.isSelected = true
                beforeLinesSpinner.value = testCase.beforeLines
            }
            RipgrepManualTestCases.TestMode.AFTER_CONTEXT -> {
                afterContextRadio.isSelected = true
                afterLinesSpinner.value = testCase.afterLines
            }
        }

        statusLabel.text = "Test case loaded: ${testCase.name}"
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(1000, 700)

        // Top panel with input fields
        panel.add(createInputPanel(), BorderLayout.NORTH)

        // Center panel with results
        panel.add(createResultsPanel(), BorderLayout.CENTER)

        // Bottom panel with status
        panel.add(createStatusPanel(), BorderLayout.SOUTH)

        return panel
    }

    private fun createInputPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Test Configuration")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Test case selection
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 1
        panel.add(JLabel("Test Case:"), gbc)

        gbc.gridx = 1
        gbc.gridwidth = 3
        gbc.weightx = 1.0
        panel.add(testCasesCombo, gbc)

        // Query input
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        val queryLabel = JLabel("Query:")
        queryLabel.toolTipText = "Search pattern (REGEX for content search, use | for OR)"
        panel.add(queryLabel, gbc)

        gbc.gridx = 1
        gbc.gridwidth = 3
        gbc.weightx = 1.0
        queryField.toolTipText = "For content search: regex pattern (| works for OR). For findFiles: leave empty"
        panel.add(queryField, gbc)

        // File pattern
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        val filePatternLabel = JLabel("File Pattern:")
        filePatternLabel.toolTipText = "Comma-separated glob patterns (e.g., *.java,*.kt or pom.xml,build.gradle)"
        panel.add(filePatternLabel, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        filePatternField.toolTipText = "Use comma to separate multiple patterns: *.java,*.kt"
        panel.add(filePatternField, gbc)

        // Exclude pattern
        gbc.gridx = 2
        gbc.weightx = 0.0
        val excludeLabel = JLabel("Exclude:")
        excludeLabel.toolTipText = "Comma-separated patterns to exclude (e.g., test,generated)"
        panel.add(excludeLabel, gbc)

        gbc.gridx = 3
        gbc.weightx = 1.0
        excludePatternField.toolTipText = "Use comma to separate multiple exclude patterns"
        panel.add(excludePatternField, gbc)

        // Mode selection
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 4
        panel.add(createModePanel(), gbc)

        // Context lines configuration
        gbc.gridy = 4
        panel.add(createContextPanel(), gbc)

        // Quick test buttons
        gbc.gridy = 5
        panel.add(createQuickTestPanel(), gbc)

        // Execute button
        gbc.gridy = 6
        gbc.gridwidth = 4
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.CENTER
        val executeButton = JButton("Execute Test")
        executeButton.addActionListener { executeTest() }
        executeButton.preferredSize = JBUI.size(200, 35)
        executeButton.background = JBColor(Color(0, 120, 215), Color(0, 120, 215))
        executeButton.foreground = JBColor.WHITE
        panel.add(executeButton, gbc)

        return panel
    }

    private fun createModePanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = TitledBorder("Test Mode")

        val group = ButtonGroup()
        group.add(basicSearchRadio)
        group.add(findFilesRadio)
        group.add(searchWithContextRadio)
        group.add(beforeContextRadio)
        group.add(afterContextRadio)

        panel.add(basicSearchRadio)
        panel.add(findFilesRadio)
        panel.add(searchWithContextRadio)
        panel.add(beforeContextRadio)
        panel.add(afterContextRadio)

        return panel
    }

    private fun createContextPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = TitledBorder("Context Lines")

        panel.add(JLabel("Context:"))
        panel.add(contextLinesSpinner)
        panel.add(Box.createHorizontalStrut(20))

        panel.add(JLabel("Before:"))
        panel.add(beforeLinesSpinner)
        panel.add(Box.createHorizontalStrut(20))

        panel.add(JLabel("After:"))
        panel.add(afterLinesSpinner)

        return panel
    }

    private fun createQuickTestPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val todoButton = JButton("Find TODOs")
        todoButton.addActionListener {
            queryField.text = "TODO|FIXME"
            basicSearchRadio.isSelected = true
            executeTest()
        }
        panel.add(todoButton)

        val importsButton = JButton("Find Imports")
        importsButton.addActionListener {
            queryField.text = "^import\\s+"
            filePatternField.text = "*.{java,kt}"
            basicSearchRadio.isSelected = true
            executeTest()
        }
        panel.add(importsButton)

        val classesButton = JButton("Find Classes")
        classesButton.addActionListener {
            queryField.text = "^public\\s+class\\s+"
            filePatternField.text = "*.java"
            basicSearchRadio.isSelected = true
            executeTest()
        }
        panel.add(classesButton)

        val testsButton = JButton("Find Test Files")
        testsButton.addActionListener {
            queryField.text = ""
            filePatternField.text = "*Test*.{java,kt}"
            findFilesRadio.isSelected = true
            executeTest()
        }
        panel.add(testsButton)

        return panel
    }

    private fun createResultsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Test Results")

        resultsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        resultsArea.isEditable = false

        val scrollPane = JBScrollPane(resultsArea)
        scrollPane.preferredSize = JBUI.size(900, 400)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Toolbar for results
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT))

        val clearButton = JButton("Clear")
        clearButton.addActionListener { resultsArea.text = "" }
        toolbar.add(clearButton)

        val exportButton = JButton("Export Report")
        exportButton.addActionListener { exportTestReport() }
        toolbar.add(exportButton)

        panel.add(toolbar, BorderLayout.SOUTH)

        return panel
    }

    private fun createStatusPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        statusPanel.add(JLabel("Status:"))
        statusPanel.add(statusLabel)
        statusPanel.add(Box.createHorizontalStrut(20))
        statusPanel.add(executionTimeLabel)

        panel.add(statusPanel, BorderLayout.WEST)

        return panel
    }

    private fun executeTest() {
        val startTime = System.currentTimeMillis()
        statusLabel.text = "Executing..."
        executionTimeLabel.text = ""
        resultsArea.text = ""

        SwingUtilities.invokeLater {
            try {
                val result = when {
                    basicSearchRadio.isSelected -> {
                        ripgrepTool.searchCode(
                            queryField.text,
                            filePatternField.text.ifEmpty { null },
                            excludePatternField.text.ifEmpty { null },
                            0,  // no before lines
                            0,  // no after lines
                            false  // multiline
                        )
                    }
                    findFilesRadio.isSelected -> {
                        // For file finding, use filePattern field first, then query field if empty
                        val pattern = filePatternField.text.ifEmpty { queryField.text }
                        ripgrepTool.findFiles(pattern)
                    }
                    searchWithContextRadio.isSelected -> {
                        val contextLines = contextLinesSpinner.value as Int
                        ripgrepTool.searchCode(
                            queryField.text,
                            filePatternField.text.ifEmpty { null },
                            excludePatternField.text.ifEmpty { null },
                            contextLines,  // same before and after
                            contextLines,  // same before and after
                            false  // multiline
                        )
                    }
                    beforeContextRadio.isSelected -> {
                        ripgrepTool.searchCode(
                            queryField.text,
                            filePatternField.text.ifEmpty { null },
                            excludePatternField.text.ifEmpty { null },
                            beforeLinesSpinner.value as Int,  // only before
                            0,  // no after
                            false  // multiline
                        )
                    }
                    afterContextRadio.isSelected -> {
                        ripgrepTool.searchCode(
                            queryField.text,
                            filePatternField.text.ifEmpty { null },
                            excludePatternField.text.ifEmpty { null },
                            0,  // no before
                            afterLinesSpinner.value as Int,  // only after
                            false  // multiline
                        )
                    }
                    else -> "No test mode selected"
                }

                resultsArea.text = result

                val endTime = System.currentTimeMillis()
                val executionTime = endTime - startTime

                statusLabel.text = "Test completed successfully"
                executionTimeLabel.text = "Execution time: ${executionTime}ms"

                // Log related files and usage patterns
                if (relatedFiles.isNotEmpty()) {
                    resultsArea.append("\n\n=== Related Files ===\n")
                    relatedFiles.forEach { resultsArea.append("- $it\n") }
                }

                if (usagePatterns.isNotEmpty()) {
                    resultsArea.append("\n=== Usage Patterns ===\n")
                    usagePatterns.forEach { resultsArea.append("- $it\n") }
                }

            } catch (e: Exception) {
                statusLabel.text = "Test failed"
                resultsArea.text = "Error: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
                executionTimeLabel.text = ""
            }
        }
    }

    private fun exportTestReport() {
        val reporter = RipgrepTestReporter(project)

        val testResult = RipgrepTestReporter.TestResult(
            testName = (testCasesCombo.selectedItem as? RipgrepManualTestCases.TestCase)?.name ?: "Manual Test",
            query = queryField.text,
            filePattern = filePatternField.text.ifEmpty { null },
            excludePattern = excludePatternField.text.ifEmpty { null },
            mode = when {
                basicSearchRadio.isSelected -> "Basic Search"
                findFilesRadio.isSelected -> "Find Files"
                searchWithContextRadio.isSelected -> "Search with Context"
                beforeContextRadio.isSelected -> "Before Context"
                afterContextRadio.isSelected -> "After Context"
                else -> "Unknown"
            },
            contextLines = if (searchWithContextRadio.isSelected) contextLinesSpinner.value as Int else 0,
            beforeLines = if (beforeContextRadio.isSelected) beforeLinesSpinner.value as Int else 0,
            afterLines = if (afterContextRadio.isSelected) afterLinesSpinner.value as Int else 0,
            output = resultsArea.text,
            executionTime = executionTimeLabel.text.replace("Execution time: ", "").replace("ms", "").toLongOrNull() ?: 0,
            success = statusLabel.text.contains("success", ignoreCase = true)
        )

        val reportFile = reporter.generateReport(listOf(testResult))

        if (reportFile != null) {
            Messages.showInfoMessage(
                project,
                "Test report saved to:\n${reportFile.absolutePath}",
                "Report Exported"
            )
        } else {
            Messages.showErrorDialog(
                project,
                "Failed to export test report",
                "Export Error"
            )
        }
    }

    override fun createActions(): Array<Action> {
        val runAllTestsAction = object : DialogWrapperAction("Run All Tests") {
            override fun doAction(e: ActionEvent?) {
                runAllTests()
            }
        }

        return arrayOf(
            runAllTestsAction,
            okAction,
            cancelAction
        )
    }

    private fun runAllTests() {
        val results = mutableListOf<RipgrepTestReporter.TestResult>()
        resultsArea.text = "Running all test cases...\n\n"

        RipgrepManualTestCases.getAllTestCases().forEach { testCase ->
            resultsArea.append("Running: ${testCase.name}...\n")
            loadTestCase(testCase)
            executeTest()

            // Collect result
            results.add(RipgrepTestReporter.TestResult(
                testName = testCase.name,
                query = testCase.query,
                filePattern = testCase.filePattern,
                excludePattern = testCase.excludePattern,
                mode = testCase.mode.toString(),
                contextLines = testCase.contextLines,
                beforeLines = testCase.beforeLines,
                afterLines = testCase.afterLines,
                output = resultsArea.text,
                executionTime = 0, // Would need to track this properly
                success = true
            ))

            resultsArea.append("\n" + "=".repeat(80) + "\n\n")
        }

        resultsArea.append("All tests completed!\n")
        statusLabel.text = "All tests completed"
    }
}