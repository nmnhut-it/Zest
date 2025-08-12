package com.zps.zest.testgen.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.TestGenerationService
import com.zps.zest.testgen.model.*
import com.zps.zest.testgen.util.ExistingTestAnalyzer
import com.zps.zest.testgen.util.TestPlanImporter
import java.awt.*
import java.awt.event.ActionEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Enhanced Test Generation Editor with existing test integration and bite-sized generation
 */
class EnhancedTestGenerationEditor(
    private val project: Project,
    private val virtualFile: TestGenerationVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    private val testGenService = project.getService(TestGenerationService::class.java)
    private val existingTestAnalyzer = ExistingTestAnalyzer(project)
    private val testPlanImporter = TestPlanImporter()
    
    private val component: JComponent
    private var currentSession: TestGenerationSession? = null
    private var existingTestClass: ExistingTestAnalyzer.ExistingTestClass? = null
    private var testGapAnalysis: ExistingTestAnalyzer.TestGapAnalysis? = null
    
    // UI Components
    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var planApprovalTab: JComponent
    private lateinit var existingTestsTab: JComponent
    private lateinit var selectivePlannTab: JComponent
    private val progressBar = JProgressBar()
    private val statusLabel = JBLabel("Ready to generate tests")
    
    // Selection tracking
    private val selectedScenarios = mutableSetOf<String>()
    private var approvedTestPlan: TestPlan? = null
    
    init {
        component = createEditorComponent()
        setupProgressListener()
        analyzeExistingTests()
    }
    
    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent? = component
    override fun getName(): String = "Enhanced Test Generation"
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
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Enhanced toolbar
        mainPanel.add(createEnhancedToolbar(), BorderLayout.NORTH)
        
        // Status bar
        mainPanel.add(createStatusBar(), BorderLayout.SOUTH)
        
        // Main content with enhanced tabs
        val splitter = JBSplitter(true, 0.12f) // 12% for overview
        splitter.firstComponent = createEnhancedOverviewPanel()
        splitter.secondComponent = createEnhancedTabbedInterface()
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createEnhancedToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Import test plan action
        actionGroup.add(object : AnAction("Import Test Plan", "Import test plan from CSV/JSON", AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) {
                importTestPlan()
            }
        })
        
        actionGroup.addSeparator()
        
        // Generate plan only
        actionGroup.add(object : AnAction("Plan Tests", "Create test plan without generating code", AllIcons.Actions.Preview) {
            override fun actionPerformed(e: AnActionEvent) {
                startTestPlanning()
            }
        })
        
        // Generate selected tests
        actionGroup.add(object : AnAction("Generate Selected", "Generate only selected test scenarios", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                generateSelectedTests()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedScenarios.isNotEmpty() && approvedTestPlan != null
            }
        })
        
        // Add to existing test class
        actionGroup.add(object : AnAction("Add to Existing", "Add tests to existing test class", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                addToExistingTestClass()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = existingTestClass != null && selectedScenarios.isNotEmpty()
            }
        })
        
        actionGroup.addSeparator()
        
        // Analyze test gaps
        actionGroup.add(object : AnAction("Analyze Coverage", "Analyze test coverage gaps", AllIcons.Actions.Find) {
            override fun actionPerformed(e: AnActionEvent) {
                analyzeTestCoverage()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "EnhancedTestGenerationEditor", 
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
    
    private fun createEnhancedOverviewPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 10, 15)
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3)
        gbc.anchor = GridBagConstraints.WEST
        
        // Title with existing test info
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 3
        val titleText = if (existingTestClass != null) {
            "🧪 Test Generation (${existingTestClass!!.testMethods.size} existing tests found)"
        } else {
            "🧪 Test Generation (No existing tests)"
        }
        val titleLabel = JBLabel(titleText)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, gbc)
        
        // Test gaps info
        testGapAnalysis?.let { gaps ->
            gbc.gridy = 1
            gbc.gridwidth = 1
            
            // Missing methods
            gbc.gridx = 0
            panel.add(JBLabel("Missing: ${gaps.missingMethods.size}"), gbc)
            
            // Partial methods  
            gbc.gridx = 1
            panel.add(JBLabel("Partial: ${gaps.partiallyTestedMethods.size}"), gbc)
            
            // Well tested
            gbc.gridx = 2
            panel.add(JBLabel("Complete: ${gaps.wellTestedMethods.size}"), gbc)
        }
        
        // Selection info
        if (selectedScenarios.isNotEmpty()) {
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.gridwidth = 3
            val selectionLabel = JBLabel("Selected: ${selectedScenarios.size} scenarios")
            selectionLabel.foreground = Color(33, 150, 243)
            panel.add(selectionLabel, gbc)
        }
        
        return panel
    }
    
    private fun createEnhancedTabbedInterface(): JComponent {
        tabbedPane = JBTabbedPane()
        
        planApprovalTab = createPlanApprovalTab()
        existingTestsTab = createExistingTestsTab()  
        selectivePlannTab = createSelectiveGenerationTab()
        
        tabbedPane.addTab("📋 Plan & Approve", planApprovalTab)
        tabbedPane.addTab("🔍 Existing Tests", existingTestsTab)
        tabbedPane.addTab("⚡ Selective Generation", selectivePlannTab)
        tabbedPane.addTab("🧪 Generated Tests", createGeneratedTestsTab())
        tabbedPane.addTab("✅ Validation", createValidationTab())
        
        return tabbedPane
    }
    
    private fun createPlanApprovalTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        approvedTestPlan?.let { testPlan ->
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            
            // Plan summary with approval status
            val summaryCard = createApprovalCard("Test Plan Summary", """
                Target: ${testPlan.targetClass}.${testPlan.targetMethod}
                Type: ${testPlan.recommendedTestType.description}  
                Scenarios: ${testPlan.scenarioCount}
                Status: ${if (approvedTestPlan != null) "✅ Approved" else "⏳ Pending Approval"}
            """.trimIndent())
            content.add(summaryCard)
            content.add(Box.createVerticalStrut(15))
            
            // Scenarios with checkboxes
            val scenariosPanel = JPanel()
            scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
            
            for ((index, scenario) in testPlan.testScenarios.withIndex()) {
                val scenarioCard = createSelectableScenarioCard(index + 1, scenario)
                scenariosPanel.add(scenarioCard)
                scenariosPanel.add(Box.createVerticalStrut(10))
            }
            
            content.add(JBLabel("Select Scenarios to Generate:").apply { 
                font = font.deriveFont(Font.BOLD, 14f) 
            })
            content.add(Box.createVerticalStrut(10))
            content.add(scenariosPanel)
            
            // Approval buttons
            val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            
            val approveAllButton = JButton("Approve All")
            approveAllButton.addActionListener { approveAllScenarios() }
            buttonPanel.add(approveAllButton)
            
            val modifyButton = JButton("Modify Plan")
            modifyButton.addActionListener { modifyTestPlan() }
            buttonPanel.add(modifyButton)
            
            content.add(Box.createVerticalStrut(15))
            content.add(buttonPanel)
            
            panel.add(JBScrollPane(content), BorderLayout.CENTER)
        } ?: run {
            val placeholderLabel = JBLabel("Create or import a test plan to begin approval process")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(placeholderLabel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createExistingTestsTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        existingTestClass?.let { testClass ->
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            
            // Existing test class info
            val classInfoCard = createInfoCard("Existing Test Class", """
                Class: ${testClass.className}
                Package: ${testClass.packageName}
                Framework: ${testClass.framework}
                Methods: ${testClass.testMethodCount}
                Can Add Methods: ${if (testClass.canAddMethods()) "✅ Yes" else "❌ No"}
            """.trimIndent())
            content.add(classInfoCard)
            content.add(Box.createVerticalStrut(15))
            
            // Test gaps analysis
            testGapAnalysis?.let { gaps ->
                if (gaps.hasGaps()) {
                    val gapsCard = createInfoCard("Coverage Analysis", """
                        Missing Tests: ${gaps.missingMethods.joinToString(", ")}
                        Partial Coverage: ${gaps.partiallyTestedMethods.joinToString(", ")}
                        Well Tested: ${gaps.wellTestedMethods.size} methods
                        Total Gaps: ${gaps.totalGaps}
                    """.trimIndent())
                    content.add(gapsCard)
                    content.add(Box.createVerticalStrut(15))
                }
            }
            
            // Existing test methods
            content.add(JBLabel("Existing Test Methods:").apply { 
                font = font.deriveFont(Font.BOLD, 14f) 
            })
            content.add(Box.createVerticalStrut(10))
            
            for (testMethod in testClass.testMethods) {
                val methodCard = createExistingTestMethodCard(testMethod)
                content.add(methodCard)
                content.add(Box.createVerticalStrut(8))
            }
            
            panel.add(JBScrollPane(content), BorderLayout.CENTER)
        } ?: run {
            val placeholderLabel = JBLabel("No existing test class found for the selected code")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(placeholderLabel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createSelectiveGenerationTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        
        // Generation options
        val optionsCard = createInfoCard("Generation Options", """
            Bite-Sized Approach: Generate only what you need
            • Select specific scenarios to implement
            • Add to existing test classes when possible  
            • Focus on missing coverage areas first
            • Generate incrementally to avoid overwhelming changes
        """.trimIndent())
        content.add(optionsCard)
        content.add(Box.createVerticalStrut(15))
        
        // Quick actions
        val actionsPanel = JPanel(GridLayout(2, 2, 10, 10))
        
        val missingOnlyButton = JButton("Generate Missing Tests Only")
        missingOnlyButton.addActionListener { selectMissingTestsOnly() }
        actionsPanel.add(missingOnlyButton)
        
        val edgeCasesButton = JButton("Generate Edge Cases")
        edgeCasesButton.addActionListener { selectEdgeCasesOnly() }
        actionsPanel.add(edgeCasesButton)
        
        val highPriorityButton = JButton("Generate High Priority")
        highPriorityButton.addActionListener { selectHighPriorityOnly() }
        actionsPanel.add(highPriorityButton)
        
        val customButton = JButton("Custom Selection")
        customButton.addActionListener { showCustomSelectionDialog() }
        actionsPanel.add(customButton)
        
        content.add(JBLabel("Quick Selection:").apply { 
            font = font.deriveFont(Font.BOLD, 14f) 
        })
        content.add(Box.createVerticalStrut(10))
        content.add(actionsPanel)
        content.add(Box.createVerticalStrut(20))
        
        // Progress tracking for selective generation
        if (selectedScenarios.isNotEmpty()) {
            val progressCard = createInfoCard("Selected for Generation", 
                "Ready to generate ${selectedScenarios.size} selected test scenarios")
            content.add(progressCard)
        }
        
        panel.add(content, BorderLayout.CENTER)
        return panel
    }
    
    private fun createGeneratedTestsTab(): JComponent {
        // Use existing implementation but enhanced for selective generation
        return createStandardGeneratedTestsTab()
    }
    
    private fun createValidationTab(): JComponent {
        // Use existing implementation
        return createStandardValidationTab()
    }
    
    // Enhanced UI components
    
    private fun createApprovalCard(title: String, content: String): JComponent {
        val card = createInfoCard(title, content)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Color(33, 150, 243), 3, 0, 0, 0),
            EmptyBorder(15, 15, 15, 15)
        )
        return card
    }
    
    private fun createSelectableScenarioCard(number: Int, scenario: TestPlan.TestScenario): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(scenario.type), 3, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        
        // Checkbox for selection
        val checkbox = JCheckBox()
        checkbox.isSelected = selectedScenarios.contains(scenario.name)
        checkbox.addActionListener { 
            if (checkbox.isSelected) {
                selectedScenarios.add(scenario.name)
            } else {
                selectedScenarios.remove(scenario.name)
            }
            refreshUI()
        }
        
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
        
        card.add(checkbox, BorderLayout.WEST)
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createExistingTestMethodCard(testMethod: ExistingTestAnalyzer.ExistingTestMethod): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(45, 45, 45) else Color(248, 248, 248)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Color(76, 175, 80), 2, 0, 0, 0),
            EmptyBorder(8, 12, 8, 12)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val nameLabel = JBLabel(testMethod.methodName)
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, 11)
        leftPanel.add(nameLabel)
        
        val infoLabel = JBLabel("Tests: ${testMethod.testedMethodName} (${testMethod.type.displayName})")
        infoLabel.font = infoLabel.font.deriveFont(9f)
        infoLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(infoLabel)
        
        if (testMethod.description.isNotEmpty()) {
            val descLabel = JBLabel(testMethod.description)
            descLabel.font = descLabel.font.deriveFont(10f)
            leftPanel.add(descLabel)
        }
        
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    // Action implementations
    
    private fun importTestPlan() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withFileFilter { it.extension?.toLowerCase() in listOf("csv", "json") }
            .withTitle("Import Test Plan")
            .withDescription("Select CSV or JSON file containing test scenarios")
        
        val file = FileChooser.chooseFile(descriptor, project, null)
        if (file != null) {
            val targetClass = extractTargetClass()
            val targetMethod = extractTargetMethod()
            
            val result = testPlanImporter.importTestPlan(file.path, targetClass, targetMethod)
            
            if (result.isSuccess) {
                approvedTestPlan = result.testPlan
                selectedScenarios.clear()
                if (result.hasWarnings()) {
                    Messages.showWarningDialog(
                        project,
                        result.message + "\n\nWarnings:\n" + result.warnings.joinToString("\n"),
                        "Import Successful with Warnings"
                    )
                } else {
                    Messages.showInfoMessage(project, result.message, "Import Successful")
                }
                refreshUI()
            } else {
                Messages.showErrorDialog(project, result.message, "Import Failed")
            }
        }
    }
    
    private fun startTestPlanning() {
        // Create planning-only request
        val request = TestGenerationRequest(
            virtualFile.targetFile,
            virtualFile.selectionStart,
            virtualFile.selectionEnd,
            "Planning only",
            TestGenerationRequest.TestType.AUTO_DETECT,
            mapOf("planOnly" to "true")
        )
        
        testGenService.startTestGeneration(request).thenAccept { session ->
            SwingUtilities.invokeLater {
                currentSession = session
                testGenService.addProgressListener(session.sessionId, ::onProgress)
                // Wait for planning phase to complete, then set as approved plan
                monitorPlanningCompletion(session)
            }
        }
    }
    
    private fun generateSelectedTests() {
        if (selectedScenarios.isEmpty() || approvedTestPlan == null) {
            Messages.showWarningDialog(project, "Please select scenarios to generate", "No Selection")
            return
        }
        
        // Filter approved plan to only selected scenarios
        val filteredScenarios = approvedTestPlan!!.testScenarios.filter { 
            selectedScenarios.contains(it.name) 
        }
        
        val filteredPlan = TestPlan(
            approvedTestPlan!!.targetMethod,
            approvedTestPlan!!.targetClass,
            filteredScenarios,
            approvedTestPlan!!.dependencies,
            approvedTestPlan!!.recommendedTestType,
            "Selective generation: ${filteredScenarios.size} scenarios"
        )
        
        // Start generation with filtered plan
        startGenerationWithPlan(filteredPlan)
    }
    
    private fun addToExistingTestClass() {
        if (existingTestClass == null || selectedScenarios.isEmpty()) {
            Messages.showWarningDialog(project, "No existing test class or no scenarios selected", "Cannot Add")
            return
        }
        
        val result = Messages.showYesNoDialog(
            project,
            "Add ${selectedScenarios.size} new test methods to ${existingTestClass!!.className}?",
            "Confirm Addition",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            // Start generation with merge mode
            generateSelectedTests() // This will be enhanced to handle merging
        }
    }
    
    private fun analyzeTestCoverage() {
        statusLabel.text = "Analyzing test coverage..."
        
        SwingUtilities.invokeLater {
            analyzeExistingTests()
            statusLabel.text = "Coverage analysis complete"
            refreshUI()
        }
    }
    
    // Selection helpers
    
    private fun selectMissingTestsOnly() {
        testGapAnalysis?.let { gaps ->
            selectedScenarios.clear()
            approvedTestPlan?.testScenarios?.forEach { scenario ->
                if (gaps.missingMethods.any { method -> 
                    scenario.name.contains(method, ignoreCase = true) 
                }) {
                    selectedScenarios.add(scenario.name)
                }
            }
            refreshUI()
        }
    }
    
    private fun selectEdgeCasesOnly() {
        selectedScenarios.clear()
        approvedTestPlan?.testScenarios?.forEach { scenario ->
            if (scenario.type == TestPlan.TestScenario.Type.EDGE_CASE) {
                selectedScenarios.add(scenario.name)
            }
        }
        refreshUI()
    }
    
    private fun selectHighPriorityOnly() {
        selectedScenarios.clear()
        approvedTestPlan?.testScenarios?.forEach { scenario ->
            if (scenario.priority == TestPlan.TestScenario.Priority.HIGH) {
                selectedScenarios.add(scenario.name)
            }
        }
        refreshUI()
    }
    
    private fun approveAllScenarios() {
        approvedTestPlan?.testScenarios?.forEach { scenario ->
            selectedScenarios.add(scenario.name)
        }
        refreshUI()
    }
    
    private fun modifyTestPlan() {
        approvedTestPlan?.let { currentPlan ->
            val dialog = TestPlanModificationDialog(project, currentPlan)
            if (dialog.showAndGet()) {
                approvedTestPlan = dialog.getModifiedTestPlan()
                selectedScenarios.clear() // Clear selections after modification
                refreshUI()
                Messages.showInfoMessage(
                    project, 
                    "Test plan modified successfully. Please review and select scenarios to generate.",
                    "Plan Modified"
                )
            }
        } ?: run {
            Messages.showWarningDialog(
                project,
                "Please create or import a test plan first",
                "No Test Plan"
            )
        }
    }
    
    private fun showCustomSelectionDialog() {
        // TODO: Implement custom selection dialog
        Messages.showInfoMessage(project, "Custom selection dialog coming in next update", "Coming Soon")
    }
    
    // Helper methods
    
    private fun analyzeExistingTests() {
        val targetClass = extractTargetClass()
        existingTestClass = existingTestAnalyzer.findExistingTestClass(targetClass)
        
        existingTestClass?.let { testClass ->
            testGapAnalysis = existingTestAnalyzer.analyzeTestGaps(testClass, targetClass)
        }
    }
    
    private fun extractTargetClass(): String {
        // Extract from virtual file or PSI analysis
        return virtualFile.targetFile.name.removeSuffix(".java").removeSuffix(".kt")
    }
    
    private fun extractTargetMethod(): String {
        // This could be enhanced to detect the actual method from selection
        return "targetMethod"
    }
    
    private fun monitorPlanningCompletion(session: TestGenerationSession) {
        // Monitor session until planning is complete
        Thread {
            while (session.status.isActive && session.testPlan == null) {
                Thread.sleep(1000)
                val updatedSession = testGenService.getSession(session.sessionId)
                if (updatedSession?.testPlan != null) {
                    SwingUtilities.invokeLater {
                        approvedTestPlan = updatedSession.testPlan
                        refreshUI()
                    }
                    break
                }
            }
        }.start()
    }
    
    private fun monitorGenerationCompletion(session: TestGenerationSession, testPlan: TestPlan) {
        // Monitor the generation session completion
        Thread {
            while (session.status.isActive) {
                Thread.sleep(1000)
                val updatedSession = testGenService.getSession(session.sessionId)
                if (updatedSession != null && !updatedSession.status.isActive) {
                    SwingUtilities.invokeLater {
                        when (updatedSession.status) {
                            TestGenerationSession.Status.COMPLETED -> {
                                statusLabel.text = "Successfully generated ${selectedScenarios.size} test scenarios"
                                progressBar.isVisible = false
                                
                                Messages.showInfoMessage(
                                    project,
                                    "Generated ${updatedSession.generatedTests?.size ?: 0} tests for ${selectedScenarios.size} scenarios",
                                    "Generation Complete"
                                )
                                
                                // Clear selection after successful generation
                                selectedScenarios.clear()
                                refreshUI()
                            }
                            TestGenerationSession.Status.COMPLETED_WITH_ISSUES -> {
                                statusLabel.text = "Generation completed with issues"
                                progressBar.isVisible = false
                                
                                Messages.showWarningDialog(
                                    project,
                                    "Test generation completed but some issues were encountered. Check the validation tab for details.",
                                    "Generation Completed with Issues"
                                )
                            }
                            TestGenerationSession.Status.FAILED -> {
                                statusLabel.text = "Test generation failed"
                                progressBar.isVisible = false
                                
                                val errorMessages = updatedSession.errors?.joinToString("\n") ?: "Unknown error"
                                Messages.showErrorDialog(
                                    project,
                                    "Test generation failed:\n$errorMessages",
                                    "Generation Failed"
                                )
                            }
                            TestGenerationSession.Status.CANCELLED -> {
                                statusLabel.text = "Test generation cancelled"
                                progressBar.isVisible = false
                            }
                            else -> {
                                // Other states
                            }
                        }
                    }
                    break
                }
            }
        }.start()
    }
    
    private fun startGenerationWithPlan(testPlan: TestPlan) {
        // Create a modified request with the filtered plan
        val originalRequest = TestGenerationRequest(
            virtualFile.targetFile,
            virtualFile.selectionStart,
            virtualFile.selectionEnd,
            "Selective generation from approved plan",
            TestGenerationRequest.TestType.AUTO_DETECT,
            mapOf(
                "preApprovedPlan" to "true",
                "selectedScenarios" to selectedScenarios.joinToString(",")
            )
        )
        
        // Start generation with the custom request
        statusLabel.text = "Starting selective test generation..."
        progressBar.isVisible = true
        progressBar.value = 0
        
        testGenService.startTestGeneration(originalRequest).thenAccept { session ->
            SwingUtilities.invokeLater {
                currentSession = session
                
                // Override the session's test plan with our filtered plan
                session.setTestPlan(testPlan)
                
                testGenService.addProgressListener(session.sessionId, ::onProgress)
                
                // Update UI to show generation in progress
                tabbedPane.selectedIndex = 3 // Switch to Generated Tests tab
                
                statusLabel.text = "Generating ${testPlan.scenarioCount} selected test scenarios..."
                
                // Monitor session completion
                monitorGenerationCompletion(session, testPlan)
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                val errorMsg = "Failed to start test generation: ${throwable.message}"
                statusLabel.text = errorMsg
                progressBar.isVisible = false
                Messages.showErrorDialog(project, errorMsg, "Generation Failed")
            }
            null
        }
    }
    
    private fun onProgress(progress: TestGenerationProgress) {
        SwingUtilities.invokeLater {
            statusLabel.text = progress.message
            progressBar.value = progress.progressPercent
            progressBar.isVisible = progress.progressPercent < 100
            
            if (progress.isComplete) {
                refreshUI()
            }
        }
    }
    
    private fun refreshUI() {
        SwingUtilities.invokeLater {
            component.revalidate()
            component.repaint()
        }
    }
    
    private fun setupProgressListener() {
        // Setup progress listening
    }
    
    // Standard UI components (simplified versions of existing ones)
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
    
    private fun getTypeColor(type: TestPlan.TestScenario.Type): Color {
        return when (type) {
            TestPlan.TestScenario.Type.UNIT -> Color(76, 175, 80)
            TestPlan.TestScenario.Type.INTEGRATION -> Color(33, 150, 243)
            TestPlan.TestScenario.Type.EDGE_CASE -> Color(255, 152, 0)
            TestPlan.TestScenario.Type.ERROR_HANDLING -> Color(244, 67, 54)
        }
    }
    
    private fun createStandardGeneratedTestsTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel("Generated tests will appear here", SwingConstants.CENTER), BorderLayout.CENTER)
        return panel
    }
    
    private fun createStandardValidationTab(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel("Validation results will appear here", SwingConstants.CENTER), BorderLayout.CENTER)
        return panel
    }
}