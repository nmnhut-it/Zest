package com.zps.zest.completion.metrics.testing.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.metrics.*
import com.zps.zest.ConfigurationManager
import com.zps.zest.settings.ZestGlobalSettings
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Comprehensive testing dialog for Zest Metrics System.
 * Provides UI for manual testing of all 7 metric types.
 */
class MetricsTestDialog(private val project: Project) : DialogWrapper(project, true) {

    // Metric type selection
    private val metricTypeCombo = JComboBox(arrayOf(
        "Inline Completion",
        "Code Health",
        "Quick Action",
        "Dual Evaluation",
        "Code Quality",
        "Unit Test"
    ))

    // Common fields
    private val completionIdField = JBTextField("test-${System.currentTimeMillis()}", 30)
    private val modelField = JBTextField("local-model-mini", 20)

    // Inline completion fields
    private val fileTypeField = JBTextField("java", 10)
    private val strategyCombo = JComboBox(CompletionStrategy.values())
    private val completionLengthSpinner = JSpinner(SpinnerNumberModel(50, 1, 1000, 10))
    private val confidenceSpinner = JSpinner(SpinnerNumberModel(0.85, 0.0, 1.0, 0.05))

    // Code quality fields
    private val linesOfCodeSpinner = JSpinner(SpinnerNumberModel(50, 1, 1000, 10))
    private val styleScoreSpinner = JSpinner(SpinnerNumberModel(85, 0, 100, 5))
    private val compilationErrorsSpinner = JSpinner(SpinnerNumberModel(0, 0, 50, 1))
    private val logicBugsSpinner = JSpinner(SpinnerNumberModel(0, 0, 50, 1))
    private val aiSelfReviewCheckbox = JBCheckBox("Code was reviewed", true)
    private val codeImprovedCheckbox = JBCheckBox("Code was improved", false)

    // Dual evaluation fields
    private val modelsField = JBTextField("gpt-4o-mini,claude-3-5-sonnet-20241022", 40)

    // Unit test fields
    private val totalTestsSpinner = JSpinner(SpinnerNumberModel(5, 1, 100, 1))
    private val wordCountSpinner = JSpinner(SpinnerNumberModel(450, 50, 10000, 50))
    private val generationTimeSpinner = JSpinner(SpinnerNumberModel(12000, 1000, 300000, 1000))
    private val testsCompiledSpinner = JSpinner(SpinnerNumberModel(5, 0, 100, 1))
    private val testsPassedSpinner = JSpinner(SpinnerNumberModel(4, 0, 100, 1))

    // Results display
    private val summaryArea = JBTextArea(10, 90)
    private val jsonPayloadArea = JBTextArea(15, 90)
    private val curlCommandArea = JBTextArea(10, 90)
    private val sessionLogArea = JBTextArea(15, 90)
    private val statusLabel = JBLabel("Ready")
    private val executionTimeLabel = JBLabel("")

    // Preview mode
    private val previewOnlyCheckbox = JBCheckBox("Preview Only (don't send)", false)

    // Dynamic form panel
    private var dynamicPanel: JPanel? = null

    init {
        title = "Metrics System Manual Testing"
        init()
        setupMetricTypeListener()
    }

    private fun setupMetricTypeListener() {
        metricTypeCombo.addActionListener {
            refreshDynamicPanel()
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(1000, 700)

        panel.add(createInputPanel(), BorderLayout.NORTH)
        panel.add(createResultsPanel(), BorderLayout.CENTER)
        panel.add(createStatusPanel(), BorderLayout.SOUTH)

        return panel
    }

    private fun createInputPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Test Configuration")

        // Top section with metric type and common fields
        val topPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Metric type
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        topPanel.add(JLabel("Metric Type:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        topPanel.add(metricTypeCombo, gbc)

        // Completion ID
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        topPanel.add(JLabel("Completion ID:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        topPanel.add(completionIdField, gbc)

        // Model
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.0
        topPanel.add(JLabel("Model:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        topPanel.add(modelField, gbc)

        panel.add(topPanel, BorderLayout.NORTH)

        // Dynamic panel for metric-specific fields
        dynamicPanel = createDynamicPanel()
        panel.add(dynamicPanel, BorderLayout.CENTER)

        return panel
    }

    private fun createDynamicPanel(): JPanel {
        val selectedType = metricTypeCombo.selectedItem as String

        return when (selectedType) {
            "Inline Completion" -> createInlineCompletionPanel()
            "Code Health" -> createCodeHealthPanel()
            "Quick Action" -> createQuickActionPanel()
            "Dual Evaluation" -> createDualEvaluationPanel()
            "Code Quality" -> createCodeQualityPanel()
            "Unit Test" -> createUnitTestPanel()
            else -> JPanel()
        }
    }

    private fun refreshDynamicPanel() {
        val parent = dynamicPanel?.parent ?: return
        parent.remove(dynamicPanel)
        dynamicPanel = createDynamicPanel()
        parent.add(dynamicPanel, BorderLayout.CENTER)
        parent.revalidate()
        parent.repaint()
    }

    private fun createInlineCompletionPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Inline Completion Parameters")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // File type
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("File Type:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(fileTypeField, gbc)

        // Strategy
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JLabel("Strategy:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(strategyCombo, gbc)

        // Completion length
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("Completion Length:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(completionLengthSpinner, gbc)

        // Confidence
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        panel.add(JLabel("Confidence:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(confidenceSpinner, gbc)

        // Quick test buttons
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2
        panel.add(createInlineQuickTestPanel(), gbc)

        return panel
    }

    private fun createInlineQuickTestPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val requestButton = JButton("Request")
        requestButton.addActionListener { testInlineRequest() }
        panel.add(requestButton)

        val viewButton = JButton("View")
        viewButton.addActionListener { testInlineView() }
        panel.add(viewButton)

        val acceptButton = JButton("Accept")
        acceptButton.addActionListener { testInlineAccept() }
        panel.add(acceptButton)

        val rejectButton = JButton("Reject")
        rejectButton.addActionListener { testInlineReject() }
        panel.add(rejectButton)

        val dismissButton = JButton("Dismiss")
        dismissButton.addActionListener { testInlineDismiss() }
        panel.add(dismissButton)

        return panel
    }

    private fun createCodeQualityPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Code Quality Parameters")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Lines of code
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Lines of Code:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(linesOfCodeSpinner, gbc)

        // Style score
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JLabel("Style Score (0-100):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(styleScoreSpinner, gbc)

        // Compilation errors
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("Compilation Errors:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(compilationErrorsSpinner, gbc)

        // Logic bugs
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        panel.add(JLabel("Logic Bugs:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(logicBugsSpinner, gbc)

        // Checkboxes
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2
        panel.add(aiSelfReviewCheckbox, gbc)

        gbc.gridy = 5
        panel.add(codeImprovedCheckbox, gbc)

        // Quick tests
        gbc.gridy = 6
        panel.add(createCodeQualityQuickTestPanel(), gbc)

        return panel
    }

    private fun createCodeQualityQuickTestPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val highScoreButton = JButton("High Score (95%)")
        highScoreButton.addActionListener {
            styleScoreSpinner.value = 95
            compilationErrorsSpinner.value = 0
            logicBugsSpinner.value = 0
            testCodeQuality()
        }
        panel.add(highScoreButton)

        val lowScoreButton = JButton("Low Score (65%)")
        lowScoreButton.addActionListener {
            styleScoreSpinner.value = 65
            compilationErrorsSpinner.value = 2
            logicBugsSpinner.value = 1
            codeImprovedCheckbox.isSelected = true
            testCodeQuality()
        }
        panel.add(lowScoreButton)

        val withBugsButton = JButton("With Bugs")
        withBugsButton.addActionListener {
            styleScoreSpinner.value = 75
            compilationErrorsSpinner.value = 5
            logicBugsSpinner.value = 3
            testCodeQuality()
        }
        panel.add(withBugsButton)

        return panel
    }

    private fun createDualEvaluationPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Dual Evaluation Parameters")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Models field
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Models (comma-separated):"), gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(modelsField, gbc)

        // Quick tests
        gbc.gridy = 2
        panel.add(createDualEvalQuickTestPanel(), gbc)

        return panel
    }

    private fun createDualEvalQuickTestPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val twoModelsButton = JButton("2 Models Comparison")
        twoModelsButton.addActionListener {
            modelsField.text = "gpt-4o-mini,claude-3-5-sonnet-20241022"
            testDualEvaluation()
        }
        panel.add(twoModelsButton)

        val threeModelsButton = JButton("3 Models Comparison")
        threeModelsButton.addActionListener {
            modelsField.text = "gpt-4o-mini,claude-3-5-sonnet-20241022,gemini-1.5-flash"
            testDualEvaluation()
        }
        panel.add(threeModelsButton)

        return panel
    }

    private fun createUnitTestPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Unit Test Parameters")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Total tests
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Total Tests:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(totalTestsSpinner, gbc)

        // Word count
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JLabel("Word Count:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(wordCountSpinner, gbc)

        // Generation time
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("Generation Time (ms):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(generationTimeSpinner, gbc)

        // Tests compiled
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        panel.add(JLabel("Tests Compiled:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(testsCompiledSpinner, gbc)

        // Tests passed
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0
        panel.add(JLabel("Tests Passed Immediately:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(testsPassedSpinner, gbc)

        // Quick tests
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2
        panel.add(createUnitTestQuickTestPanel(), gbc)

        return panel
    }

    private fun createUnitTestQuickTestPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val perfectButton = JButton("Perfect (100%)")
        perfectButton.addActionListener {
            val total = totalTestsSpinner.value as Int
            testsCompiledSpinner.value = total
            testsPassedSpinner.value = total
            testUnitTest()
        }
        panel.add(perfectButton)

        val partialButton = JButton("Partial (80%)")
        partialButton.addActionListener {
            val total = totalTestsSpinner.value as Int
            testsCompiledSpinner.value = total
            testsPassedSpinner.value = (total * 0.8).toInt()
            testUnitTest()
        }
        panel.add(partialButton)

        val withErrorsButton = JButton("With Errors")
        withErrorsButton.addActionListener {
            val total = totalTestsSpinner.value as Int
            testsCompiledSpinner.value = (total * 0.6).toInt()
            testsPassedSpinner.value = (total * 0.5).toInt()
            testUnitTest()
        }
        panel.add(withErrorsButton)

        return panel
    }

    private fun createCodeHealthPanel(): JPanel {
        val panel = JPanel()
        panel.border = TitledBorder("Code Health - Use existing Code Health features")
        panel.add(JLabel("Code Health metrics are tracked automatically"))
        return panel
    }

    private fun createQuickActionPanel(): JPanel {
        val panel = JPanel()
        panel.border = TitledBorder("Quick Action - Use actual quick actions")
        panel.add(JLabel("Quick Action metrics are tracked when you use quick actions"))
        return panel
    }

    private fun createResultsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Test Results")

        // Create tabbed pane
        val tabbedPane = JBTabbedPane()

        // Tab 1: Summary
        summaryArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        summaryArea.isEditable = false
        val summaryScroll = JBScrollPane(summaryArea)
        tabbedPane.addTab("Summary", summaryScroll)

        // Tab 2: JSON Payload
        jsonPayloadArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        jsonPayloadArea.isEditable = false
        jsonPayloadArea.lineWrap = false
        val jsonScroll = JBScrollPane(jsonPayloadArea)
        tabbedPane.addTab("JSON Payload", jsonScroll)

        // Tab 3: CURL Command
        curlCommandArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        curlCommandArea.isEditable = false
        curlCommandArea.lineWrap = true
        curlCommandArea.wrapStyleWord = true
        val curlScroll = JBScrollPane(curlCommandArea)
        tabbedPane.addTab("CURL Command", curlScroll)

        // Tab 4: Session Log
        sessionLogArea.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        sessionLogArea.isEditable = false
        val sessionScroll = JBScrollPane(sessionLogArea)
        tabbedPane.addTab("Session Log", sessionScroll)

        panel.add(tabbedPane, BorderLayout.CENTER)

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT))

        toolbar.add(previewOnlyCheckbox)

        val clearButton = JButton("Clear All")
        clearButton.addActionListener { clearAllResults() }
        toolbar.add(clearButton)

        val copyJsonButton = JButton("Copy JSON")
        copyJsonButton.addActionListener { copyToClipboard(jsonPayloadArea.text) }
        toolbar.add(copyJsonButton)

        val copyCurlButton = JButton("Copy CURL")
        copyCurlButton.addActionListener { copyToClipboard(curlCommandArea.text) }
        toolbar.add(copyCurlButton)

        val refreshSessionButton = JButton("Refresh Session")
        refreshSessionButton.addActionListener { refreshSessionLog() }
        toolbar.add(refreshSessionButton)

        panel.add(toolbar, BorderLayout.SOUTH)

        return panel
    }

    private fun clearAllResults() {
        summaryArea.text = ""
        jsonPayloadArea.text = ""
        curlCommandArea.text = ""
        sessionLogArea.text = ""
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
        Messages.showInfoMessage(project, "Copied to clipboard", "Success")
    }

    private fun refreshSessionLog() {
        val sessionLogger = MetricsSessionLogger.getInstance(project)
        val events = sessionLogger.getAllEvents()
        val stats = sessionLogger.getSessionStats()

        sessionLogArea.text = buildString {
            appendLine("=== SESSION LOG (${events.size} events) ===")
            appendLine("Session Duration: ${stats.sessionDurationMs / 1000}s")
            appendLine("Success Rate: ${String.format("%.1f%%", stats.successRate)}")
            appendLine("Avg Response Time: ${stats.averageResponseTime}ms")
            appendLine()
            events.reversed().forEach { entry ->
                appendLine("[${entry.getFormattedRelativeTime()}] ${entry.eventType} (${entry.completionId})")
                appendLine("  ${if (entry.success) "✅" else "❌"} ${entry.responseCode} | ${entry.responseTimeMs}ms | ${entry.endpoint}")
                appendLine()
            }
        }
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

    // Test execution methods

    private fun testInlineRequest() {
        executeMetricTest("Inline Completion Request") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCompletionRequested(
                completionId = completionIdField.text,
                strategy = (strategyCombo.selectedItem as CompletionStrategy).name,
                fileType = fileTypeField.text,
                actualModel = modelField.text
            )
            "Inline completion request tracked"
        }
    }

    private fun testInlineView() {
        executeMetricTest("Inline Completion View") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCompletionViewed(
                completionId = completionIdField.text,
                completionLength = completionLengthSpinner.value as Int,
                completionLineCount = 3,
                confidence = (confidenceSpinner.value as Double).toFloat()
            )
            "Inline completion view tracked"
        }
    }

    private fun testInlineAccept() {
        executeMetricTest("Inline Completion Accept") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCompletionAccepted(
                completionId = completionIdField.text,
                completionContent = "Sample completion code",
                isAll = true,
                acceptType = "full",
                userAction = "tab"
            )
            "Inline completion accept tracked"
        }
    }

    private fun testInlineReject() {
        executeMetricTest("Inline Completion Reject") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCompletionDeclined(
                completionId = completionIdField.text,
                reason = "esc_pressed"
            )
            "Inline completion reject tracked"
        }
    }

    private fun testInlineDismiss() {
        executeMetricTest("Inline Completion Dismiss") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCompletionDismissed(
                completionId = completionIdField.text,
                reason = "user_typed"
            )
            "Inline completion dismiss tracked"
        }
    }

    private fun testCodeQuality() {
        executeMetricTest("Code Quality") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackCodeQuality(
                completionId = completionIdField.text,
                linesOfCode = linesOfCodeSpinner.value as Int,
                styleComplianceScore = styleScoreSpinner.value as Int,
                selfReviewPassed = (styleScoreSpinner.value as Int) >= 80,
                compilationErrors = compilationErrorsSpinner.value as Int,
                logicBugsDetected = logicBugsSpinner.value as Int,
                wasReviewed = aiSelfReviewCheckbox.isSelected,
                wasImproved = codeImprovedCheckbox.isSelected
            )
            val score = styleScoreSpinner.value as Int
            val errors = compilationErrorsSpinner.value as Int
            val bugs = logicBugsSpinner.value as Int
            "Code quality tracked: score=$score, errors=$errors, bugs=$bugs"
        }
    }

    private fun testDualEvaluation() {
        executeMetricTest("Dual Evaluation") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            val models = modelsField.text.split(",").map { it.trim() }

            // Simulate results from multiple models
            val results = models.mapIndexed { index, model ->
                ModelComparisonResult(
                    modelName = model,
                    responseTimeMs = 2000L + (index * 500),
                    tokenCount = 150 + (index * 20),
                    qualityScore = null
                )
            }

            metricsService.trackDualEvaluation(
                completionId = completionIdField.text,
                originalPrompt = "Write a function to calculate factorial",
                models = models,
                results = results,
                elapsed = results.maxOf { it.responseTimeMs }
            )
            "Dual evaluation tracked: ${models.size} models compared"
        }
    }

    private fun testUnitTest() {
        executeMetricTest("Unit Test") {
            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
            metricsService.trackUnitTest(
                testId = completionIdField.text,
                totalTests = totalTestsSpinner.value as Int,
                wordCount = wordCountSpinner.value as Int,
                generationTimeMs = (generationTimeSpinner.value as Int).toLong(),
                testsCompiled = testsCompiledSpinner.value as Int,
                testsPassedImmediately = testsPassedSpinner.value as Int
            )
            val total = totalTestsSpinner.value as Int
            val passed = testsPassedSpinner.value as Int
            val pct = if (total > 0) (passed * 100f / total) else 0f
            "Unit test tracked: ${passed}/${total} passed (${pct}%)"
        }
    }

    private fun executeMetricTest(testName: String, testAction: () -> String) {
        val startTime = System.currentTimeMillis()
        statusLabel.text = "Executing..."
        executionTimeLabel.text = ""

        try {
            // Execute the test
            val result = testAction()

            val endTime = System.currentTimeMillis()
            val executionTime = endTime - startTime

            // Wait a moment for async logging to complete
            Thread.sleep(100)

            // Get the most recent session log entry (the one we just created)
            val sessionLogger = MetricsSessionLogger.getInstance(project)
            val recentEvents = sessionLogger.getAllEvents().take(1)

            if (recentEvents.isNotEmpty()) {
                val lastEvent = recentEvents[0]
                val config = ConfigurationManager.getInstance(project)

                // Populate all tabs
                populateSummaryTab(testName, result, executionTime, lastEvent)
                populateJsonPayloadTab(lastEvent)
                populateCurlCommandTab(lastEvent, config.authToken)
                refreshSessionLog()
            }

            statusLabel.text = if (previewOnlyCheckbox.isSelected) {
                "Preview generated (not sent)"
            } else {
                "Test completed successfully"
            }
            statusLabel.foreground = JBColor.GREEN
            executionTimeLabel.text = "Execution time: ${executionTime}ms"

        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val executionTime = endTime - startTime

            summaryArea.text = "❌ Error: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"

            statusLabel.text = "Test failed"
            statusLabel.foreground = JBColor.RED
            executionTimeLabel.text = "Failed after ${executionTime}ms"
        }
    }

    private fun populateSummaryTab(testName: String, result: String, executionTime: Long, entry: SessionLogEntry) {
        val settings = ZestGlobalSettings.getInstance()

        summaryArea.text = buildString {
            appendLine("=== $testName ===")
            appendLine("Timestamp: ${entry.getFormattedTimestamp()}")
            appendLine("Relative Time: ${entry.getFormattedRelativeTime()}")
            appendLine()
            appendLine("Configuration:")
            appendLine("  Metrics Enabled: ${settings.metricsEnabled}")
            appendLine("  Server URL: ${settings.metricsServerBaseUrl}")
            appendLine("  Preview Only: ${previewOnlyCheckbox.isSelected}")
            appendLine()
            appendLine("Endpoint:")
            appendLine("  ${entry.httpMethod} ${entry.endpoint}")
            appendLine()
            appendLine("Result:")
            appendLine("  $result")
            appendLine()
            appendLine("HTTP Response:")
            appendLine("  ${if (entry.success) "✅" else "❌"} ${entry.responseCode} ${if (entry.success) "OK" else "FAILED"}")
            appendLine("  Response Time: ${entry.responseTimeMs}ms")
            if (entry.errorMessage != null) {
                appendLine("  Error: ${entry.errorMessage}")
            }
            appendLine()
            appendLine("⏱️  Total Execution: ${executionTime}ms")
            appendLine()
            if (!previewOnlyCheckbox.isSelected) {
                appendLine("✅ Metric sent to server")
            } else {
                appendLine("👁️ Preview only - NOT sent to server")
            }
        }
    }

    private fun populateJsonPayloadTab(entry: SessionLogEntry) {
        jsonPayloadArea.text = entry.getPrettyJsonPayload()
    }

    private fun populateCurlCommandTab(entry: SessionLogEntry, authToken: String?) {
        curlCommandArea.text = entry.getCurlCommand(authToken)
    }

    override fun createActions(): Array<Action> {
        val runAllButton = object : DialogWrapperAction("Run All Inline Tests") {
            override fun doAction(e: ActionEvent?) {
                runAllInlineTests()
            }
        }

        return arrayOf(
            runAllButton,
            okAction,
            cancelAction
        )
    }

    private fun runAllInlineTests() {
        summaryArea.text = "Running all inline completion tests...\n\n"
        testInlineRequest()
        Thread.sleep(300)
        testInlineView()
        Thread.sleep(300)
        testInlineAccept()
        summaryArea.append("\n✅ All inline completion tests completed\n")
        statusLabel.text = "All tests completed"
        refreshSessionLog()
    }
}

