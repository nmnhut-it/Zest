package com.zps.zest.testgen.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.TestGenerationService
import com.zps.zest.testgen.model.*
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Test Generation Editor with tab-based interface for managing AI-powered test generation
 */
class TestGenerationEditor(
    private val project: Project,
    private val virtualFile: TestGenerationVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    companion object {
        private val LOG = Logger.getInstance(TestGenerationEditor::class.java)
    }
    
    private val testGenService = project.getService(TestGenerationService::class.java)
    private val component: JPanel
    private var currentSession: TestGenerationSession? = null
    private val progressBar = JProgressBar()
    private val statusLabel = JBLabel("Ready to generate tests")
    
    // UI Components that need updating when data changes
    private var tabbedPane: JBTabbedPane? = null
    private var planTab: JComponent? = null
    private var testsTab: JComponent? = null
    private var validationTab: JComponent? = null
    private var progressTab: JComponent? = null
    
    // Panels that need content updates
    private var overviewPanel: JPanel? = null
    private var sessionInfoPanel: JPanel? = null
    private var planContentPanel: JPanel? = null
    private var testsContentPanel: JPanel? = null
    private var validationContentPanel: JPanel? = null
    private var progressContentPanel: JPanel? = null private var codePreviewPanel: JPanel? = null
    
    init {
        component = JPanel(BorderLayout())
        component.background = UIUtil.getPanelBackground()
        setupUI()
        setupProgressListener()
        updateDataDependentComponents()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Test Generation"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {
        currentSession?.let { session ->
            testGenService.removeProgressListener(session.sessionId, ::onProgress)
        }
    }
    
    override fun setState(state: FileEditorState) {}
    
    override fun getFile(): VirtualFile = virtualFile
    
    private fun setupUI() {
        // Add toolbar at top
        component.add(createToolbar(), BorderLayout.NORTH)
        
        // Add status bar at bottom
        component.add(createStatusBar(), BorderLayout.SOUTH)
        
        // Create overview panel with code preview
        val topPanel = JPanel(BorderLayout())
        topPanel.background = UIUtil.getPanelBackground()
        
        // Create and store overview panel
        overviewPanel = JPanel(GridBagLayout())
        overviewPanel!!.background = UIUtil.getPanelBackground()
        overviewPanel!!.border = EmptyBorder(15, 15, 10, 15)
        topPanel.add(overviewPanel, BorderLayout.NORTH)
        
        // Create code preview panel
        codePreviewPanel = JPanel(BorderLayout())
        codePreviewPanel!!.background = UIUtil.getPanelBackground()
        codePreviewPanel!!.border = EmptyBorder(10, 15, 10, 15)
        topPanel.add(codePreviewPanel, BorderLayout.CENTER)
        
        // Create tabbed pane
        tabbedPane = JBTabbedPane()
        
        // Add main content with tabs
        val splitter = JBSplitter(true, 0.3f) // 30% top for overview+preview, 70% for tabs
        splitter.firstComponent = JBScrollPane(topPanel)
        splitter.secondComponent = tabbedPane
        
        component.add(splitter, BorderLayout.CENTER)
        
        // Setup tabs (empty initially)
        setupTabs()
    }
    
    private fun setupTabs() {
        // Create content panels for tabs
        planContentPanel = JPanel(BorderLayout())
        planContentPanel!!.background = UIUtil.getPanelBackground()
        planContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        testsContentPanel = JPanel(BorderLayout())
        testsContentPanel!!.background = UIUtil.getPanelBackground()
        testsContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        validationContentPanel = JPanel(BorderLayout())
        validationContentPanel!!.background = UIUtil.getPanelBackground()
        validationContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        progressContentPanel = JPanel(BorderLayout())
        progressContentPanel!!.background = UIUtil.getPanelBackground()
        progressContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        // Create scrollable tabs
        planTab = JBScrollPane(planContentPanel)
        testsTab = JBScrollPane(testsContentPanel)
        validationTab = JBScrollPane(validationContentPanel)
        progressTab = progressContentPanel
        
        tabbedPane!!.addTab("📋 Test Plan", planTab)
        tabbedPane!!.addTab("🧪 Generated Tests", testsTab)
        tabbedPane!!.addTab("✅ Validation", validationTab)
        tabbedPane!!.addTab("📊 Progress", progressTab)
    }
    
    private fun updateDataDependentComponents() {
        SwingUtilities.invokeLater {
            updateOverviewPanel()
            updateCodePreviewPanel()
            updateTestPlanTab()
            updateGeneratedTestsTab()
            updateValidationTab()
            updateProgressTab()
            component.revalidate()
            component.repaint()
        }
    }
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Generate tests action
        actionGroup.add(object : AnAction("Generate Tests", "Start AI-powered test generation", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                startTestGeneration()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = currentSession?.status?.isActive != true
            }
        })
        
        // Cancel generation action
        actionGroup.add(object : AnAction("Cancel", "Cancel test generation", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                cancelTestGeneration()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = currentSession?.status?.isActive == true
            }
        })
        
        actionGroup.addSeparator()
        
        // Write tests to files action
        actionGroup.add(object : AnAction("Write Tests", "Write generated tests to files", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                writeTestsToFiles()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = currentSession?.status?.isCompleted == true && 
                                         currentSession?.generatedTests?.isNotEmpty() == true
            }
        })
        
        // Refresh action
        actionGroup.add(object : AnAction("Refresh", "Refresh session data", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshSessionData()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TestGenerationEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun createStatusBar(): JComponent {
        val statusPanel = JPanel(BorderLayout())
        statusPanel.background = UIUtil.getPanelBackground()
        statusPanel.border = EmptyBorder(5, 10, 5, 10)
        
        progressBar.isVisible = false
        progressBar.preferredSize = Dimension(200, 20)
        
        statusLabel.foreground = UIUtil.getLabelForeground()
        
        statusPanel.add(statusLabel, BorderLayout.WEST)
        statusPanel.add(progressBar, BorderLayout.EAST)
        
        return statusPanel
    }
    
    private fun updateOverviewPanel() {
        overviewPanel?.removeAll()
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.anchor = GridBagConstraints.WEST
        
        // Title
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        val titleLabel = JBLabel("🧪 AI Test Generation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        overviewPanel!!.add(titleLabel, gbc)
        
        // Show selected code info
        gbc.gridy = 1
        gbc.gridwidth = 2
        val fileInfoLabel = JBLabel("📄 File: ${virtualFile.targetFile.name}")
        fileInfoLabel.font = fileInfoLabel.font.deriveFont(Font.BOLD, 14f)
        overviewPanel!!.add(fileInfoLabel, gbc)
        
        // Show selection info
        gbc.gridy = 2
        val selectionInfo = if (virtualFile.selectionStart != virtualFile.selectionEnd) {
            val chars = virtualFile.selectionEnd - virtualFile.selectionStart
            "🎯 Selection: ${chars} characters (lines ${getLineNumber(virtualFile.selectionStart)}-${getLineNumber(virtualFile.selectionEnd)})"
        } else {
            "📄 Entire file selected"
        }
        val selectionLabel = JBLabel(selectionInfo)
        selectionLabel.foreground = UIUtil.getLabelForeground()
        overviewPanel!!.add(selectionLabel, gbc)
        
        // Session info
        if (currentSession != null) {
            gbc.gridy = 3
            gbc.gridwidth = 1
            overviewPanel!!.add(JBLabel("Session:"), gbc)
            
            gbc.gridx = 1
            val sessionLabel = JBLabel(currentSession!!.sessionId.substring(0, 8) + "...")
            sessionLabel.foreground = UIUtil.getInactiveTextColor()
            overviewPanel!!.add(sessionLabel, gbc)
            
            gbc.gridx = 0
            gbc.gridy = 4
            overviewPanel!!.add(JBLabel("Status:"), gbc)
            
            gbc.gridx = 1
            val statusLabel = JBLabel(currentSession!!.status.description)
            statusLabel.foreground = when (currentSession!!.status) {
                TestGenerationSession.Status.COMPLETED -> Color(76, 175, 80)
                TestGenerationSession.Status.FAILED, TestGenerationSession.Status.CANCELLED -> Color(244, 67, 54)
                else -> Color(255, 152, 0)
            }
            overviewPanel!!.add(statusLabel, gbc)
        } else {
            gbc.gridy = 3
            gbc.gridwidth = 2
            val readyLabel = JBLabel("✅ Ready to generate tests! Click 'Generate Tests' in the toolbar to start.")
            readyLabel.font = readyLabel.font.deriveFont(Font.BOLD, 12f)
            readyLabel.foreground = Color(76, 175, 80)
            overviewPanel!!.add(readyLabel, gbc)
            
            // Add hint about what will be tested
            gbc.gridy = 4
            val hintLabel = JBLabel("💡 Tip: You'll be able to select which methods to test")
            hintLabel.font = hintLabel.font.deriveFont(11f)
            hintLabel.foreground = UIUtil.getInactiveTextColor()
            overviewPanel!!.add(hintLabel, gbc)
        }
    }
    
    private fun getLineNumber(offset: Int): Int {
        return try {
            val document = virtualFile.targetFile.viewProvider.document
            if (document != null) {
                document.getLineNumber(offset) + 1
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun updateCodePreviewPanel() {
        codePreviewPanel?.removeAll()
        
        // Add label
        val previewLabel = JBLabel("🔍 Selected Code Preview:")
        previewLabel.font = previewLabel.font.deriveFont(Font.BOLD, 12f)
        codePreviewPanel!!.add(previewLabel, BorderLayout.NORTH)
        
        // Get selected code text
        val selectedCode = try {
            val text = virtualFile.targetFile.text
            if (virtualFile.selectionStart != virtualFile.selectionEnd && 
                virtualFile.selectionStart >= 0 && 
                virtualFile.selectionEnd <= text.length) {
                text.substring(virtualFile.selectionStart, virtualFile.selectionEnd)
            } else {
                // Show first 500 chars if entire file
                if (text.length > 500) {
                    text.substring(0, 500) + "\n...\n[Full file selected]"
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            "Unable to preview selected code"
        }
        
        // Create text area for code preview
        val codeArea = JBTextArea(selectedCode)
        codeArea.isEditable = false
        codeArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        codeArea.background = if (UIUtil.isUnderDarcula()) Color(43, 43, 43) else Color(250, 250, 250)
        codeArea.foreground = UIUtil.getLabelForeground()
        codeArea.border = EmptyBorder(10, 10, 10, 10)
        
        val scrollPane = JBScrollPane(codeArea)
        scrollPane.preferredSize = Dimension(600, 150)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        
        codePreviewPanel!!.add(scrollPane, BorderLayout.CENTER)
    }
    
    private fun updateTestPlanTab() {
        planContentPanel?.removeAll()
        
        currentSession?.testPlan?.let { testPlan ->
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            content.background = UIUtil.getPanelBackground()
            
            // Plan summary
            val summaryCard = createInfoCard("Test Plan Summary", """
                Target Class: ${testPlan.targetClass}
                Target Method: ${testPlan.targetMethod}
                Recommended Type: ${testPlan.recommendedTestType.description}
                Scenarios: ${testPlan.scenarioCount}
            """.trimIndent())
            content.add(summaryCard)
            content.add(Box.createVerticalStrut(15))
            
            // Reasoning
            if (testPlan.reasoning.isNotEmpty()) {
                val reasoningCard = createInfoCard("Planning Reasoning", testPlan.reasoning)
                content.add(reasoningCard)
                content.add(Box.createVerticalStrut(15))
            }
            
            // Scenarios
            val scenariosPanel = JPanel()
            scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
            scenariosPanel.background = UIUtil.getPanelBackground()
            
            for ((index, scenario) in testPlan.testScenarios.withIndex()) {
                val scenarioCard = createScenarioCard(index + 1, scenario)
                scenariosPanel.add(scenarioCard)
                scenariosPanel.add(Box.createVerticalStrut(10))
            }
            
            content.add(JBLabel("Test Scenarios:").apply { 
                font = font.deriveFont(Font.BOLD, 14f) 
            })
            content.add(Box.createVerticalStrut(10))
            content.add(scenariosPanel)
            
            planContentPanel!!.add(content, BorderLayout.NORTH)
        } ?: run {
            val placeholderLabel = JBLabel("Test plan will appear here after generation starts")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            planContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateGeneratedTestsTab() {
        testsContentPanel?.removeAll()
        
        currentSession?.generatedTests?.let { tests ->
            if (tests.isNotEmpty()) {
                val content = JPanel()
                content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
                content.background = UIUtil.getPanelBackground()
                
                for ((index, test) in tests.withIndex()) {
                    val testCard = createTestCard(index + 1, test)
                    content.add(testCard)
                    content.add(Box.createVerticalStrut(15))
                }
                
                testsContentPanel!!.add(content, BorderLayout.NORTH)
            } else {
                val placeholderLabel = JBLabel("Generated tests will appear here")
                placeholderLabel.horizontalAlignment = SwingConstants.CENTER
                placeholderLabel.foreground = UIUtil.getInactiveTextColor()
                testsContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
            }
        } ?: run {
            val placeholderLabel = JBLabel("Generated tests will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            testsContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateValidationTab() {
        validationContentPanel?.removeAll()
        
        currentSession?.validationResult?.let { validation ->
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            content.background = UIUtil.getPanelBackground()
            
            // Validation summary
            val summaryText = """
                Validation Status: ${if (validation.isSuccessful) "✅ Passed" else "❌ Issues Found"}
                Errors: ${validation.errorCount}
                Warnings: ${validation.warningCount}
                Fixes Applied: ${validation.appliedFixes.size}
            """.trimIndent()
            
            val summaryCard = createInfoCard("Validation Summary", summaryText)
            content.add(summaryCard)
            content.add(Box.createVerticalStrut(15))
            
            // Issues
            if (validation.issues.isNotEmpty()) {
                content.add(JBLabel("Validation Issues:").apply { 
                    font = font.deriveFont(Font.BOLD, 14f) 
                })
                content.add(Box.createVerticalStrut(10))
                
                for (issue in validation.issues) {
                    val issueCard = createIssueCard(issue)
                    content.add(issueCard)
                    content.add(Box.createVerticalStrut(8))
                }
            }
            
            // Applied fixes
            if (validation.appliedFixes.isNotEmpty()) {
                content.add(Box.createVerticalStrut(15))
                content.add(JBLabel("Applied Fixes:").apply { 
                    font = font.deriveFont(Font.BOLD, 14f) 
                })
                content.add(Box.createVerticalStrut(10))
                
                val fixesText = validation.appliedFixes.joinToString("\n") { "• $it" }
                val fixesCard = createInfoCard("Fixes", fixesText)
                content.add(fixesCard)
            }
            
            validationContentPanel!!.add(content, BorderLayout.NORTH)
        } ?: run {
            val placeholderLabel = JBLabel("Validation results will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            validationContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateProgressTab() {
        progressContentPanel?.removeAll()
        
        // This would contain detailed progress information
        val placeholderLabel = JBLabel("Detailed progress information will be shown here")
        placeholderLabel.horizontalAlignment = SwingConstants.CENTER
        placeholderLabel.foreground = UIUtil.getInactiveTextColor()
        progressContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
    }
    
    
    private fun createInfoCard(title: String, content: String): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        card.add(titleLabel, BorderLayout.NORTH)
        
        val contentArea = JBTextArea(content)
        contentArea.isEditable = false
        contentArea.background = card.background
        contentArea.border = EmptyBorder(10, 0, 0, 0)
        card.add(contentArea, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createScenarioCard(number: Int, scenario: TestPlan.TestScenario): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(scenario.type), 3, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val nameLabel = JBLabel("$number. ${scenario.name}")
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        leftPanel.add(nameLabel)
        
        val infoLabel = JBLabel("${scenario.type.displayName} • ${scenario.priority.displayName}")
        infoLabel.font = infoLabel.font.deriveFont(10f)
        infoLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(infoLabel)
        
        val descLabel = JBLabel(scenario.description)
        descLabel.font = descLabel.font.deriveFont(11f)
        leftPanel.add(descLabel)
        
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createTestCard(number: Int, test: GeneratedTest): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(test.scenario.type), 3, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val nameLabel = JBLabel("$number. ${test.testName}")
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        leftPanel.add(nameLabel)
        
        val classLabel = JBLabel("${test.testClassName} (${test.framework})")
        classLabel.font = classLabel.font.deriveFont(10f)
        classLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(classLabel)
        
        val contentPreview = test.testContent.take(100) + if (test.testContent.length > 100) "..." else ""
        val previewLabel = JBLabel(contentPreview)
        previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        leftPanel.add(previewLabel)
        
        card.add(leftPanel, BorderLayout.WEST)
        
        // Action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = card.background
        
        val viewButton = JButton("View Code")
        viewButton.addActionListener {
            showTestCode(test)
        }
        buttonPanel.add(viewButton)
        
        card.add(buttonPanel, BorderLayout.EAST)
        
        return card
    }
    
    private fun createIssueCard(issue: ValidationResult.ValidationIssue): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        
        val severityColor = when (issue.severity) {
            ValidationResult.ValidationIssue.Severity.ERROR -> Color(244, 67, 54)
            ValidationResult.ValidationIssue.Severity.WARNING -> Color(255, 152, 0)
            else -> Color(76, 175, 80)
        }
        
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(severityColor, 3, 0, 0, 0),
            EmptyBorder(10, 15, 10, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val issueLabel = JBLabel(issue.description)
        issueLabel.font = issueLabel.font.deriveFont(12f)
        leftPanel.add(issueLabel)
        
        val detailsLabel = JBLabel("${issue.severity.displayName} in ${issue.testName}")
        detailsLabel.font = detailsLabel.font.deriveFont(10f)
        detailsLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(detailsLabel)
        
        if (issue.fixSuggestion.isNotEmpty()) {
            val suggestionLabel = JBLabel("💡 ${issue.fixSuggestion}")
            suggestionLabel.font = suggestionLabel.font.deriveFont(11f)
            suggestionLabel.foreground = Color(76, 175, 80)
            leftPanel.add(suggestionLabel)
        }
        
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun getTypeColor(type: TestPlan.TestScenario.Type): Color {
        return when (type) {
            TestPlan.TestScenario.Type.UNIT -> Color(76, 175, 80)
            TestPlan.TestScenario.Type.INTEGRATION -> Color(33, 150, 243)
            TestPlan.TestScenario.Type.EDGE_CASE -> Color(255, 152, 0)
            TestPlan.TestScenario.Type.ERROR_HANDLING -> Color(244, 67, 54)
        }
    }
    
    private fun setupProgressListener() {
        // This will be called when progress updates are received
    }
    
    private fun onProgress(progress: TestGenerationProgress) {
        SwingUtilities.invokeLater {
            statusLabel.text = progress.message
            progressBar.value = progress.progressPercent
            progressBar.isVisible = progress.progressPercent < 100
            
            if (progress.isComplete) {
                refreshSessionData()
            }
        }
    }
    
    private fun startTestGeneration() {
        // Extract methods from selected code
        val methods = extractMethodsFromSelection()
        
        if (methods.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "No methods found in the selected code. Please select code containing methods to test.",
                "No Methods Found"
            )
            return
        }
        
        // Show method selection dialog
        val selectedMethods = showMethodSelectionDialog(methods)
        if (selectedMethods.isEmpty()) {
            return // User cancelled
        }
        
        // Generate description from selected methods
        val description = "Generate tests for methods: ${selectedMethods.joinToString(", ")}"
        
        val request = TestGenerationRequest(
            virtualFile.targetFile,
            virtualFile.selectionStart,
            virtualFile.selectionEnd,
            description,
            TestGenerationRequest.TestType.AUTO_DETECT,
            selectedMethods // Pass selected methods
        )
        
        testGenService.startTestGeneration(request).thenAccept { session ->
            SwingUtilities.invokeLater {
                currentSession = session
                testGenService.addProgressListener(session.sessionId, ::onProgress)
                updateDataDependentComponents()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                Messages.showErrorDialog(
                    project,
                    "Failed to start test generation: ${throwable.message}",
                    "Test Generation Error"
                )
            }
            null
        }
    }
    
    private fun extractMethodsFromSelection(): List<String> {
        val methods = mutableListOf<String>()
        
        try {
            val text = virtualFile.targetFile.text
            val selectedText = if (virtualFile.selectionStart != virtualFile.selectionEnd && 
                                 virtualFile.selectionStart >= 0 && 
                                 virtualFile.selectionEnd <= text.length) {
                text.substring(virtualFile.selectionStart, virtualFile.selectionEnd)
            } else {
                text
            }
            
            // Simple method extraction - look for Java/Kotlin method patterns
            // Java: public/private/protected [static] [final] returnType methodName(
            val javaMethodPattern = """(public|private|protected|static|final|\s)+(\w+\s+)?(\w+)\s*\([^)]*\)""".toRegex()
            javaMethodPattern.findAll(selectedText).forEach { match ->
                val methodSignature = match.value.trim()
                val methodName = methodSignature.substringBefore("(").substringAfterLast(" ")
                if (methodName.isNotEmpty() && !methodName.matches("(if|for|while|switch|catch)".toRegex())) {
                    methods.add(methodName)
                }
            }
            
            // Kotlin: fun methodName(
            val kotlinMethodPattern = """fun\s+(\w+)\s*\([^)]*\)""".toRegex()
            kotlinMethodPattern.findAll(selectedText).forEach { match ->
                val methodName = match.value.substringAfter("fun").substringBefore("(").trim()
                if (methodName.isNotEmpty()) {
                    methods.add(methodName)
                }
            }
            
        } catch (e: Exception) {
            LOG.warn("Failed to extract methods from selection", e)
        }
        
        return methods.distinct()
    }
    
    private fun showMethodSelectionDialog(methods: List<String>): List<String> {
        val checkBoxes = methods.map { method -> 
            JCheckBox(method, true) // Default to all selected
        }
        
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        
        val instructionLabel = JBLabel("Select methods to generate tests for:")
        instructionLabel.font = instructionLabel.font.deriveFont(Font.BOLD, 12f)
        panel.add(instructionLabel)
        panel.add(Box.createVerticalStrut(10))
        
        checkBoxes.forEach { checkBox ->
            panel.add(checkBox)
            panel.add(Box.createVerticalStrut(5))
        }
        
        // Add select all/none buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val selectAllBtn = JButton("Select All")
        selectAllBtn.addActionListener {
            checkBoxes.forEach { it.isSelected = true }
        }
        val selectNoneBtn = JButton("Select None")
        selectNoneBtn.addActionListener {
            checkBoxes.forEach { it.isSelected = false }
        }
        buttonPanel.add(selectAllBtn)
        buttonPanel.add(selectNoneBtn)
        panel.add(buttonPanel)
        
        val result = Messages.showOkCancelDialog(
            project,
            panel,
            "Select Methods to Test",
            "Generate Tests",
            "Cancel",
            Messages.getQuestionIcon()
        )
        
        return if (result == Messages.OK) {
            checkBoxes.filter { it.isSelected }.map { it.text }
        } else {
            emptyList()
        }
    }
    
    private fun cancelTestGeneration() {
        currentSession?.let { session ->
            testGenService.cancelSession(session.sessionId)
            statusLabel.text = "Generation cancelled"
            progressBar.isVisible = false
        }
    }
    
    private fun writeTestsToFiles() {
        currentSession?.let { session ->
            testGenService.writeTestsToFiles(session.sessionId).thenAccept { writtenFiles ->
                SwingUtilities.invokeLater {
                    val message = "Successfully wrote ${writtenFiles.size} test files:\n" + 
                                 writtenFiles.joinToString("\n") { "• $it" }
                    Messages.showInfoMessage(project, message, "Tests Written")
                }
            }.exceptionally { throwable ->
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to write test files: ${throwable.message}",
                        "Write Error"
                    )
                }
                null
            }
        }
    }
    
    private fun refreshSessionData() {
        currentSession?.let { session ->
            val updatedSession = testGenService.getSession(session.sessionId)
            if (updatedSession != null) {
                currentSession = updatedSession
                updateDataDependentComponents()
            }
        }
    }
    
    private fun refreshUI() {
        // Use the new update method instead of recreating everything
        updateDataDependentComponents()
    }
    
    private fun showTestCode(test: GeneratedTest) {
        val dialog = JDialog()
        dialog.title = "Generated Test: ${test.testName}"
        dialog.setSize(800, 600)
        dialog.setLocationRelativeTo(component)
        
        val codeArea = JBTextArea(test.fullContent)
        codeArea.isEditable = false
        codeArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        
        val scrollPane = JBScrollPane(codeArea)
        dialog.add(scrollPane)
        
        dialog.isVisible = true
    }
}