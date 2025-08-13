package com.zps.zest.testgen.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
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
    
    private val testGenService = project.getService(TestGenerationService::class.java)
    private val component: JComponent
    private var currentSession: TestGenerationSession? = null
    private val progressBar = JProgressBar()
    private val statusLabel = JBLabel("Ready to generate tests")
    
    // UI Components
    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var planTab: JComponent
    private lateinit var testsTab: JComponent
    private lateinit var validationTab: JComponent
    private lateinit var progressTab: JComponent
    
    init {
        component = createEditorComponent()
        setupProgressListener()
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
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        mainPanel.add(createToolbar(), BorderLayout.NORTH)
        
        // Add status bar at bottom
        mainPanel.add(createStatusBar(), BorderLayout.SOUTH)
        
        // Add main content with tabs
        val splitter = JBSplitter(true, 0.15f) // 15% top for status, 85% for tabs
        splitter.firstComponent = createOverviewPanel()
        splitter.secondComponent = createTabbedInterface()
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
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
    
    private fun createOverviewPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 10, 15)
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.anchor = GridBagConstraints.WEST
        
        // Title
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        val titleLabel = JBLabel("🧪 AI Test Generation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        panel.add(titleLabel, gbc)
        
        // Session info
        if (currentSession != null) {
            gbc.gridy = 1
            gbc.gridwidth = 1
            panel.add(JBLabel("Session:"), gbc)
            
            gbc.gridx = 1
            val sessionLabel = JBLabel(currentSession!!.sessionId.substring(0, 8) + "...")
            sessionLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(sessionLabel, gbc)
            
            gbc.gridx = 0
            gbc.gridy = 2
            panel.add(JBLabel("Status:"), gbc)
            
            gbc.gridx = 1
            val statusLabel = JBLabel(currentSession!!.status.description)
            statusLabel.foreground = when (currentSession!!.status) {
                TestGenerationSession.Status.COMPLETED -> Color(76, 175, 80)
                TestGenerationSession.Status.FAILED, TestGenerationSession.Status.CANCELLED -> Color(244, 67, 54)
                else -> Color(255, 152, 0)
            }
            panel.add(statusLabel, gbc)
        } else {
            gbc.gridy = 1
            gbc.gridwidth = 2
            val infoLabel = JBLabel("Select code and click 'Generate Tests' to start")
            infoLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(infoLabel, gbc)
        }
        
        return panel
    }
    
    private fun createTabbedInterface(): JComponent {
        tabbedPane = JBTabbedPane()
        
        planTab = createTestPlanTab()
        testsTab = createGeneratedTestsTab()
        validationTab = createValidationTab()
        progressTab = createProgressTab()
        
        tabbedPane.addTab("📋 Test Plan", planTab)
        tabbedPane.addTab("🧪 Generated Tests", testsTab)
        tabbedPane.addTab("✅ Validation", validationTab)
        tabbedPane.addTab("📊 Progress", progressTab)
        
        return tabbedPane
    }
    
    private fun createTestPlanTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
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
            
            panel.add(JBScrollPane(content), BorderLayout.CENTER)
        } ?: run {
            val placeholderLabel = JBLabel("Test plan will appear here after generation starts")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(placeholderLabel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createGeneratedTestsTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
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
                
                panel.add(JBScrollPane(content), BorderLayout.CENTER)
            } else {
                val placeholderLabel = JBLabel("Generated tests will appear here")
                placeholderLabel.horizontalAlignment = SwingConstants.CENTER
                placeholderLabel.foreground = UIUtil.getInactiveTextColor()
                panel.add(placeholderLabel, BorderLayout.CENTER)
            }
        } ?: run {
            val placeholderLabel = JBLabel("Generated tests will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(placeholderLabel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createValidationTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
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
            
            panel.add(JBScrollPane(content), BorderLayout.CENTER)
        } ?: run {
            val placeholderLabel = JBLabel("Validation results will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(placeholderLabel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createProgressTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // This would contain detailed progress information
        val placeholderLabel = JBLabel("Detailed progress information will be shown here")
        placeholderLabel.horizontalAlignment = SwingConstants.CENTER
        placeholderLabel.foreground = UIUtil.getInactiveTextColor()
        panel.add(placeholderLabel, BorderLayout.CENTER)
        
        return panel
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
        val description = Messages.showInputDialog(
            project,
            "Describe what you want to test (optional):",
            "Test Generation",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: return
        
        val request = TestGenerationRequest(
            virtualFile.targetFile,
            virtualFile.selectionStart,
            virtualFile.selectionEnd,
            description,
            TestGenerationRequest.TestType.AUTO_DETECT,
            null
        )
        
        testGenService.startTestGeneration(request).thenAccept { session ->
            SwingUtilities.invokeLater {
                currentSession = session
                testGenService.addProgressListener(session.sessionId, ::onProgress)
                refreshUI()
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
                refreshUI()
            }
        }
    }
    
    private fun refreshUI() {
        SwingUtilities.invokeLater {
            // Remove and re-create tabs with updated data
            tabbedPane.removeAll()
            
            planTab = createTestPlanTab()
            testsTab = createGeneratedTestsTab()
            validationTab = createValidationTab()
            progressTab = createProgressTab()
            
            tabbedPane.addTab("📋 Test Plan", planTab)
            tabbedPane.addTab("🧪 Generated Tests", testsTab)
            tabbedPane.addTab("✅ Validation", validationTab)
            tabbedPane.addTab("📊 Progress", progressTab)
            
            component.revalidate()
            component.repaint()
        }
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