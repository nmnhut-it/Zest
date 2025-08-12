package com.zps.zest.codehealth.testplan.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.testplan.TestPlanVirtualFile
import com.zps.zest.codehealth.testplan.analysis.TestabilityAnalyzer
import com.zps.zest.codehealth.testplan.models.*
import com.zps.zest.codehealth.testplan.storage.TestPlanStorage
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Editor for displaying and editing test plans in full-screen tabs
 */
class TestPlanEditor(
    private val project: Project,
    private val virtualFile: TestPlanVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    private val testPlanData = virtualFile.getTestPlanData()
    private val methodFqn = virtualFile.getMethodFqn()
    private val component: JComponent
    private var analysisResults: TestabilityResult? = null
    private val storage = TestPlanStorage.getInstance(project)
    
    init {
        component = createEditorComponent()
        startTestabilityAnalysis()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Test Plan"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {}
    
    override fun setState(state: FileEditorState) {
        // No-op for test plan editor
    }
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        mainPanel.add(createToolbar(), BorderLayout.NORTH)
        
        // Add split panel content
        val splitter = JBSplitter(false, 0.3f) // 30% left, 70% right
        splitter.firstComponent = createAnalysisPanel()
        splitter.secondComponent = createTestPlanPanel()
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Regenerate test plan action
        actionGroup.add(object : AnAction("Regenerate Test Plan", "Generate new test plan", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                regenerateTestPlan()
            }
        })
        
        // Export to markdown action
        actionGroup.add(object : AnAction("Export to Markdown", "Save test plan as .md file", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                exportToMarkdown()
            }
        })
        
        // Generate test code action
        actionGroup.add(object : AnAction("Generate Test Code", "Generate actual test implementation", AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                generateTestCode()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TestPlanEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun createAnalysisPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // Title
        val titleLabel = JBLabel("Testability Analysis")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(15))
        
        // Analysis results will be populated here
        if (analysisResults != null) {
            populateAnalysisResults(panel, analysisResults!!)
        } else {
            // Show loading state
            val loadingLabel = JBLabel("🔄 Analyzing method testability...")
            loadingLabel.foreground = UIUtil.getInactiveTextColor()
            panel.add(loadingLabel)
        }
        
        panel.add(Box.createVerticalGlue()) // Push everything to top
        
        return JBScrollPane(panel)
    }
    
    private fun populateAnalysisResults(panel: JPanel, results: TestabilityResult) {
        // Remove loading indicator
        panel.removeAll()
        
        // Re-add title
        val titleLabel = JBLabel("Testability Analysis")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(15))
        
        // Testability score
        addAnalysisRow(panel, "🎯 Testability Score:", "${results.score}/100", getScoreColor(results.score))
        
        // Complexity
        addAnalysisRow(panel, "📊 Complexity:", results.complexity.toString(), getComplexityColor(results.complexity))
        
        panel.add(Box.createVerticalStrut(10))
        
        // Dependencies
        if (results.dependencies.isNotEmpty()) {
            val depLabel = JBLabel("🔗 Dependencies:")
            depLabel.font = depLabel.font.deriveFont(Font.BOLD)
            panel.add(depLabel)
            results.dependencies.forEach { dep ->
                val depItem = JBLabel("  • $dep")
                depItem.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                panel.add(depItem)
            }
            panel.add(Box.createVerticalStrut(10))
        }
        
        // Side effects
        if (results.sideEffects.isNotEmpty()) {
            val sideEffectLabel = JBLabel("🎭 Side Effects:")
            sideEffectLabel.font = sideEffectLabel.font.deriveFont(Font.BOLD)
            panel.add(sideEffectLabel)
            results.sideEffects.forEach { effect ->
                val effectItem = JBLabel("  • ${effect.type}: ${effect.description}")
                effectItem.font = effectItem.font.deriveFont(11f)
                panel.add(effectItem)
            }
            panel.add(Box.createVerticalStrut(10))
        }
        
        // Mocking requirements
        if (results.mockingRequirements.isNotEmpty()) {
            val mockLabel = JBLabel("📋 Mocks Needed:")
            mockLabel.font = mockLabel.font.deriveFont(Font.BOLD)
            panel.add(mockLabel)
            results.mockingRequirements.forEach { mock ->
                val mockItem = JBLabel("  ✓ ${mock.className}")
                mockItem.foreground = Color(76, 175, 80)
                mockItem.font = mockItem.font.deriveFont(11f)
                panel.add(mockItem)
            }
            panel.add(Box.createVerticalStrut(10))
        }
        
        // Recommendations
        if (results.recommendations.isNotEmpty()) {
            val recLabel = JBLabel("💡 Recommendations:")
            recLabel.font = recLabel.font.deriveFont(Font.BOLD)
            panel.add(recLabel)
            results.recommendations.forEach { rec ->
                val recItem = JTextArea(rec)
                recItem.isEditable = false
                recItem.isOpaque = false
                recItem.background = UIUtil.getPanelBackground()
                recItem.font = recItem.font.deriveFont(11f)
                recItem.lineWrap = true
                recItem.wrapStyleWord = true
                recItem.border = EmptyBorder(2, 20, 2, 0)
                panel.add(recItem)
            }
        }
        
        // Regenerate button
        panel.add(Box.createVerticalStrut(15))
        val regenerateBtn = JButton("🔄 Regenerate Analysis")
        regenerateBtn.addActionListener { startTestabilityAnalysis() }
        panel.add(regenerateBtn)
        
        panel.add(Box.createVerticalGlue())
        
        // Refresh UI
        panel.revalidate()
        panel.repaint()
    }
    
    private fun createTestPlanPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // Title
        val titleLabel = JBLabel("Test Plan: ${formatMethodName(methodFqn)}")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(20))
        
        // Overview section
        addSection(panel, "Overview", 
            "This test plan provides comprehensive testing strategy for the method `$methodFqn`.")
        
        panel.add(Box.createVerticalStrut(15))
        
        // Test cases section
        addTestCasesSection(panel)
        
        panel.add(Box.createVerticalStrut(15))
        
        // Setup requirements section
        addSetupRequirementsSection(panel)
        
        panel.add(Box.createVerticalStrut(15))
        
        // Implementation template section
        addImplementationTemplateSection(panel)
        
        panel.add(Box.createVerticalGlue()) // Push content to top
        
        return JBScrollPane(panel)
    }
    
    private fun addAnalysisRow(panel: JPanel, label: String, value: String, valueColor: Color?) {
        val rowPanel = JPanel(BorderLayout())
        rowPanel.background = UIUtil.getPanelBackground()
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, 25)
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        rowPanel.add(labelComponent, BorderLayout.WEST)
        
        val valueComponent = JBLabel(value)
        if (valueColor != null) {
            valueComponent.foreground = valueColor
        }
        rowPanel.add(valueComponent, BorderLayout.EAST)
        
        panel.add(rowPanel)
        panel.add(Box.createVerticalStrut(8))
    }
    
    private fun addSection(panel: JPanel, title: String, content: String, backgroundColor: Color? = null) {
        val sectionPanel = JPanel()
        sectionPanel.layout = BoxLayout(sectionPanel, BoxLayout.Y_AXIS)
        sectionPanel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.background = backgroundColor ?: if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        sectionPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // Section title
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.add(titleLabel)
        sectionPanel.add(Box.createVerticalStrut(10))
        
        // Content
        val contentArea = JTextArea(content)
        contentArea.isEditable = false
        contentArea.isOpaque = false
        contentArea.background = sectionPanel.background
        contentArea.font = UIUtil.getLabelFont()
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.add(contentArea)
        
        panel.add(sectionPanel)
    }
    
    private fun addTestCasesSection(panel: JPanel) {
        val sectionPanel = JPanel()
        sectionPanel.layout = BoxLayout(sectionPanel, BoxLayout.Y_AXIS)
        sectionPanel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        sectionPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // Section title
        val titleLabel = JBLabel("Test Cases")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.add(titleLabel)
        sectionPanel.add(Box.createVerticalStrut(10))
        
        if (testPlanData.testCases.isNotEmpty()) {
            // Group test cases by category
            val groupedCases = testPlanData.testCases.groupBy { it.category }
            
            groupedCases.forEach { (category, cases) ->
                val categoryLabel = JBLabel(formatCategoryName(category))
                categoryLabel.font = categoryLabel.font.deriveFont(Font.BOLD, 13f)
                sectionPanel.add(categoryLabel)
                sectionPanel.add(Box.createVerticalStrut(8))
                
                cases.forEach { testCase ->
                    val casePanel = createTestCasePanel(testCase)
                    sectionPanel.add(casePanel)
                    sectionPanel.add(Box.createVerticalStrut(8))
                }
                
                sectionPanel.add(Box.createVerticalStrut(5))
            }
        } else {
            val placeholder = JBLabel("Test cases will be generated based on method analysis")
            placeholder.foreground = UIUtil.getInactiveTextColor()
            sectionPanel.add(placeholder)
        }
        
        panel.add(sectionPanel)
    }
    
    private fun createTestCasePanel(testCase: TestCase): JComponent {
        val casePanel = JPanel()
        casePanel.layout = BoxLayout(casePanel, BoxLayout.Y_AXIS)
        casePanel.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        casePanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getPriorityColor(testCase.priority), 2, 0, 0, 0),
            EmptyBorder(10, 15, 10, 15)
        )
        casePanel.alignmentX = Component.LEFT_ALIGNMENT
        
        // Test case name
        val nameLabel = JBLabel(testCase.name)
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 12f)
        casePanel.add(nameLabel)
        casePanel.add(Box.createVerticalStrut(5))
        
        // Description
        val descArea = JTextArea(testCase.description)
        descArea.isEditable = false
        descArea.isOpaque = false
        descArea.background = casePanel.background
        descArea.font = descArea.font.deriveFont(11f)
        descArea.lineWrap = true
        descArea.wrapStyleWord = true
        casePanel.add(descArea)
        
        return casePanel
    }
    
    private fun addSetupRequirementsSection(panel: JPanel) {
        val content = if (testPlanData.setupRequirements.isNotEmpty()) {
            testPlanData.setupRequirements.joinToString("\n") { "• ${it.type}: ${it.description}" }
        } else {
            "Setup requirements will be determined based on method dependencies and side effects."
        }
        
        addSection(panel, "Setup Requirements", content)
    }
    
    private fun addImplementationTemplateSection(panel: JPanel) {
        val template = buildString {
            appendLine("```java")
            appendLine("@Test")
            appendLine("public void test${extractMethodName(methodFqn)}() {")
            appendLine("    // Arrange")
            appendLine("    // TODO: Set up test data and mocks")
            appendLine("    ")
            appendLine("    // Act")
            appendLine("    // TODO: Call the method under test")
            appendLine("    ")
            appendLine("    // Assert")
            appendLine("    // TODO: Verify the results")
            appendLine("}")
            appendLine("```")
        }
        
        addSection(panel, "Implementation Template", template, Color(232, 245, 233))
    }
    
    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> Color(76, 175, 80)  // Green
            score >= 60 -> Color(255, 152, 0)   // Orange
            else -> Color(244, 67, 54)          // Red
        }
    }
    
    private fun getComplexityColor(complexity: Int): Color {
        return when {
            complexity <= 5 -> Color(76, 175, 80)   // Green (low complexity)
            complexity <= 10 -> Color(255, 152, 0)   // Orange (medium complexity)
            else -> Color(244, 67, 54)               // Red (high complexity)
        }
    }
    
    private fun getPriorityColor(priority: TestPriority): Color {
        return when (priority) {
            TestPriority.HIGH -> Color(244, 67, 54)     // Red
            TestPriority.MEDIUM -> Color(255, 152, 0)    // Orange
            TestPriority.LOW -> Color(76, 175, 80)       // Green
        }
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
            // Java method FQN
            fqn
        }
    }
    
    private fun extractMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            fileName.substringBeforeLast(".")
        } else {
            // Java method FQN
            fqn.substringAfterLast(".")
        }
    }
    
    private fun formatCategoryName(category: TestCaseCategory): String {
        return when (category) {
            TestCaseCategory.HAPPY_PATH -> "✅ Happy Path Tests"
            TestCaseCategory.EDGE_CASE -> "⚠️ Edge Case Tests"
            TestCaseCategory.ERROR_CONDITION -> "🚨 Error Condition Tests"
            TestCaseCategory.BOUNDARY -> "🔍 Boundary Tests"
            TestCaseCategory.NEGATIVE -> "❌ Negative Tests"
        }
    }
    
    private fun startTestabilityAnalysis() {
        // TODO: This will be implemented when TestabilityAnalyzer is complete
        // For now, create mock results
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(2000) // Simulate analysis time
            
            val mockResults = TestabilityResult(
                methodFqn = methodFqn,
                score = 75,
                complexity = 8,
                dependencies = listOf("UserService", "DatabaseConnection"),
                mockingRequirements = listOf(
                    MockingRequirement("UserService", "External service dependency"),
                    MockingRequirement("DatabaseConnection", "Database access")
                ),
                sideEffects = listOf(
                    SideEffect(SideEffectType.DATABASE, "Queries user table", "May be slow in tests", "Use in-memory database")
                ),
                recommendations = listOf(
                    "Consider extracting database queries to separate method",
                    "Add validation for null inputs"
                )
            )
            
            SwingUtilities.invokeLater {
                analysisResults = mockResults
                
                // Update test plan data with analysis results
                val updatedPlan = testPlanData.copy(
                    testabilityScore = mockResults.score,
                    complexity = mockResults.complexity,
                    dependencies = mockResults.dependencies,
                    mockingRequirements = mockResults.mockingRequirements,
                    sideEffects = mockResults.sideEffects,
                    testCases = generateTestCases(mockResults),
                    setupRequirements = generateSetupRequirements(mockResults)
                )
                
                // Save to storage
                storage.storeTestPlan(updatedPlan)
                virtualFile.updateTestPlanData(updatedPlan)
                
                val analysisPanel = (component as JPanel).getComponent(1) as JBSplitter
                val leftScrollPane = analysisPanel.firstComponent as JBScrollPane
                val leftPanel = leftScrollPane.viewport.view as JPanel
                populateAnalysisResults(leftPanel, mockResults)
            }
        }
    }
    
    private fun regenerateTestPlan() {
        Messages.showInfoMessage(project, "Regenerating test plan...", "Test Plan")
        startTestabilityAnalysis()
    }
    
    private fun exportToMarkdown() {
        Messages.showInfoMessage(
            project,
            "Export to markdown functionality will be implemented in Phase 3.",
            "Feature Coming Soon"
        )
    }
    
    private fun generateTestCode() {
        Messages.showInfoMessage(
            project,
            "Test code generation will be implemented in Phase 3.",
            "Feature Coming Soon"
        )
    }
    
    private fun generateTestCases(results: TestabilityResult): List<TestCase> {
        val testCases = mutableListOf<TestCase>()
        
        // Happy path test case
        testCases.add(TestCase(
            name = "Valid Input Test",
            description = "Test with valid input parameters",
            category = TestCaseCategory.HAPPY_PATH,
            setup = "Set up valid test data",
            input = "Valid parameters for ${extractMethodName(methodFqn)}",
            expectedOutput = "Expected successful result",
            priority = TestPriority.HIGH
        ))
        
        // Edge case based on complexity
        if (results.complexity > 5) {
            testCases.add(TestCase(
                name = "Edge Case Test",
                description = "Test boundary conditions and edge cases",
                category = TestCaseCategory.EDGE_CASE,
                setup = "Set up boundary condition data",
                input = "Edge case parameters",
                expectedOutput = "Expected edge case handling",
                priority = TestPriority.MEDIUM
            ))
        }
        
        // Error condition test
        testCases.add(TestCase(
            name = "Invalid Input Test",
            description = "Test error handling with invalid input",
            category = TestCaseCategory.ERROR_CONDITION,
            setup = "Set up invalid test data",
            input = "Invalid or null parameters",
            expectedOutput = "Expected exception or error handling",
            priority = TestPriority.HIGH
        ))
        
        // Additional tests based on side effects
        if (results.sideEffects.isNotEmpty()) {
            testCases.add(TestCase(
                name = "Side Effect Test",
                description = "Test side effects and external interactions",
                category = TestCaseCategory.NEGATIVE,
                setup = "Mock external dependencies",
                input = "Parameters that trigger side effects",
                expectedOutput = "Expected side effect behavior",
                priority = TestPriority.MEDIUM
            ))
        }
        
        return testCases
    }
    
    private fun generateSetupRequirements(results: TestabilityResult): List<SetupRequirement> {
        val requirements = mutableListOf<SetupRequirement>()
        
        if (results.dependencies.any { it.contains("Database") || it.contains("Repository") }) {
            requirements.add(SetupRequirement(
                type = SetupType.DATABASE,
                description = "Set up in-memory database for testing",
                code = "// Use H2 in-memory database or mock repository"
            ))
        }
        
        if (results.sideEffects.any { it.type == SideEffectType.FILE_IO }) {
            requirements.add(SetupRequirement(
                type = SetupType.FILES,
                description = "Set up temporary files for testing",
                code = "// Use temporary directory and clean up after tests"
            ))
        }
        
        if (results.mockingRequirements.isNotEmpty()) {
            requirements.add(SetupRequirement(
                type = SetupType.DEPENDENCIES,
                description = "Mock external dependencies",
                code = "// Initialize mocks for: ${results.mockingRequirements.joinToString { it.className }}"
            ))
        }
        
        return requirements
    }
}