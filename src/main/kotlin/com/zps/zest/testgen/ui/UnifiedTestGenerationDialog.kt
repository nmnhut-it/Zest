package com.zps.zest.testgen.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.settings.ZestProjectSettings
import com.zps.zest.testgen.model.TestGenerationConfig
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Unified dialog for test generation: method selection, context, and configuration.
 * Replaces both MethodSelectionDialog and UserContextDialog with enhanced configuration options.
 */
class UnifiedTestGenerationDialog(
    private val project: Project,
    private val psiFile: PsiFile,
    private val preselectedElement: PsiElement? = null
) : DialogWrapper(project) {

    // Tab 1: Method Selection
    private val methodCheckBoxes = mutableMapOf<PsiMethod, JBCheckBox>()
    private var selectAllCheckBox: JBCheckBox? = null

    // Tab 2: Context & Files
    private val fileListModel = DefaultListModel<String>()
    private val fileList = JBList(fileListModel)
    private val codeTextArea = JBTextArea()

    // Tab 3: Configuration
    private val testsPerMethodSpinner = JSpinner(SpinnerNumberModel(5, 1, 100, 1))
    private val testTypeCheckboxes = mutableMapOf<TestGenerationConfig.TestTypeFilter, JBCheckBox>()
    private val priorityCheckboxes = mutableMapOf<TestGenerationConfig.PriorityFilter, JBCheckBox>()
    private val coverageCheckboxes = mutableMapOf<TestGenerationConfig.CoverageTarget, JBCheckBox>()
    private val saveAsDefaultCheckbox = JBCheckBox("Save as project default", true)

    init {
        title = "Generate Tests"
        init()
        loadConfiguration()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(800, 600)

        val tabbedPane = JTabbedPane()

        // Tab 1: Method Selection
        tabbedPane.addTab("Methods", createMethodSelectionPanel())

        // Tab 2: Context & Files
        tabbedPane.addTab("Context & Files", createContextPanel())

        // Tab 3: Configuration
        tabbedPane.addTab("Configuration", createConfigurationPanel())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        return mainPanel
    }

    // ===== TAB 1: METHOD SELECTION =====

    private fun createMethodSelectionPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = UIUtil.getPanelBackground()
        headerPanel.border = EmptyBorder(10, 15, 10, 15)

        val titleLabel = JBLabel("Select Methods for Test Generation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.NORTH)

        val descLabel = JBLabel("Choose which methods you want to generate tests for:")
        descLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(descLabel, BorderLayout.CENTER)

        panel.add(headerPanel, BorderLayout.NORTH)

        // Methods list
        val methodsPanel = JPanel()
        methodsPanel.layout = BoxLayout(methodsPanel, BoxLayout.Y_AXIS)
        methodsPanel.background = UIUtil.getPanelBackground()
        methodsPanel.border = EmptyBorder(10, 15, 10, 15)

        // "Select All" checkbox
        selectAllCheckBox = JBCheckBox("Select All")
        selectAllCheckBox!!.addActionListener {
            val selectAll = selectAllCheckBox!!.isSelected
            methodCheckBoxes.values.forEach { it.isSelected = selectAll }
        }
        methodsPanel.add(selectAllCheckBox!!)
        methodsPanel.add(Box.createVerticalStrut(10))
        methodsPanel.add(JSeparator())
        methodsPanel.add(Box.createVerticalStrut(10))

        // Find and display methods
        val methods = findMethods(psiFile)

        if (methods.isEmpty()) {
            val noMethodsLabel = JBLabel("No testable methods found in this file")
            noMethodsLabel.foreground = Color.RED
            methodsPanel.add(noMethodsLabel)

            val hintLabel = JBLabel("<html><small>Methods with @Test annotations or test methods are excluded</small></html>")
            hintLabel.foreground = UIUtil.getContextHelpForeground()
            methodsPanel.add(Box.createVerticalStrut(5))
            methodsPanel.add(hintLabel)
        } else {
            // Group methods by class
            val methodsByClass = methods.groupBy { it.containingClass?.name ?: "Unknown" }

            methodsByClass.forEach { (className, classMethods) ->
                val classLabel = JBLabel("Class: $className")
                classLabel.font = classLabel.font.deriveFont(Font.BOLD, 12f)
                classLabel.border = EmptyBorder(5, 0, 5, 0)
                methodsPanel.add(classLabel)

                classMethods.forEach { method ->
                    val methodPanel = createMethodPanel(method)
                    methodsPanel.add(methodPanel)
                    methodsPanel.add(Box.createVerticalStrut(5))
                }

                methodsPanel.add(Box.createVerticalStrut(10))
            }
        }

        val scrollPane = JBScrollPane(methodsPanel)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Footer with statistics
        val footerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        footerPanel.background = UIUtil.getPanelBackground()
        footerPanel.border = EmptyBorder(10, 15, 10, 15)

        val totalMethods = methods.size
        val publicMethods = methods.count { it.modifierList.hasModifierProperty(PsiModifier.PUBLIC) }
        val privateMethods = methods.count { it.modifierList.hasModifierProperty(PsiModifier.PRIVATE) }
        val protectedMethods = methods.count { it.modifierList.hasModifierProperty(PsiModifier.PROTECTED) }

        val statsLabel = JBLabel("Found $totalMethods methods (Public: $publicMethods, Protected: $protectedMethods, Private: $privateMethods)")
        statsLabel.foreground = UIUtil.getContextHelpForeground()
        footerPanel.add(statsLabel)

        panel.add(footerPanel, BorderLayout.SOUTH)

        updateSelectAllState()

        return panel
    }

    private fun createMethodPanel(method: PsiMethod): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(5, 20, 5, 10)

        val methodSignature = buildMethodSignature(method)
        val checkBox = JBCheckBox(methodSignature)

        // Preselect logic
        if (preselectedElement != null && isMethodContainsElement(method, preselectedElement)) {
            checkBox.isSelected = true
        } else {
            val isSimpleAccessor = (method.name.startsWith("get") ||
                    method.name.startsWith("set") ||
                    method.name.startsWith("is")) &&
                    method.body?.statements?.size ?: 0 <= 1
            checkBox.isSelected = method.modifierList.hasModifierProperty(PsiModifier.PUBLIC) && !isSimpleAccessor
        }

        checkBox.addActionListener { updateSelectAllState() }
        methodCheckBoxes[method] = checkBox
        panel.add(checkBox, BorderLayout.WEST)

        // Method info
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        infoPanel.background = UIUtil.getPanelBackground()

        val visibility = when {
            method.modifierList.hasModifierProperty(PsiModifier.PUBLIC) -> "public"
            method.modifierList.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
            method.modifierList.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
            else -> "package"
        }

        val visibilityLabel = JBLabel(visibility)
        visibilityLabel.foreground = when (visibility) {
            "public" -> Color(76, 175, 80)
            "protected" -> Color(255, 152, 0)
            "private" -> Color(244, 67, 54)
            else -> UIUtil.getLabelForeground()
        }
        visibilityLabel.font = visibilityLabel.font.deriveFont(10f)
        infoPanel.add(visibilityLabel)

        val complexity = estimateMethodComplexity(method)
        if (complexity > 0) {
            val complexityLabel = JBLabel("Complexity: $complexity")
            complexityLabel.foreground = when {
                complexity <= 5 -> Color(76, 175, 80)
                complexity <= 10 -> Color(255, 152, 0)
                else -> Color(244, 67, 54)
            }
            complexityLabel.font = complexityLabel.font.deriveFont(10f)
            infoPanel.add(complexityLabel)
        }

        panel.add(infoPanel, BorderLayout.CENTER)

        return panel
    }

    private fun buildMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        val returnType = method.returnType?.presentableText ?: "void"
        return "${method.name}($params): $returnType"
    }

    private fun findMethods(psiFile: PsiFile): List<PsiMethod> {
        val methods = mutableListOf<PsiMethod>()

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PsiClass -> {
                        element.methods.forEach { method ->
                            if (isTestableMethod(method)) {
                                methods.add(method)
                            }
                        }
                    }
                    is PsiMethod -> {
                        if (isTestableMethod(element) && !methods.contains(element)) {
                            methods.add(element)
                        }
                    }
                }
                super.visitElement(element)
            }
        })

        return methods.distinctBy { "${it.containingClass?.qualifiedName}.${it.name}${it.parameterList.text}" }
    }

    private fun isTestableMethod(method: PsiMethod): Boolean {
        return !method.isConstructor &&
                !method.name.startsWith("test") &&
                !method.name.contains("Test") &&
                !method.modifierList.annotations.any {
                    it.qualifiedName?.contains("Test") == true
                } &&
                !method.modifierList.annotations.any {
                    it.qualifiedName?.contains("Before") == true ||
                            it.qualifiedName?.contains("After") == true
                }
    }

    private fun isMethodContainsElement(method: PsiMethod, element: PsiElement): Boolean {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (current == method) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun estimateMethodComplexity(method: PsiMethod): Int {
        var complexity = 1

        method.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PsiIfStatement -> complexity++
                    is PsiForStatement -> complexity++
                    is PsiWhileStatement -> complexity++
                    is PsiSwitchStatement -> complexity++
                    is PsiTryStatement -> complexity++
                    is PsiConditionalExpression -> complexity++
                }
                super.visitElement(element)
            }
        })

        return complexity
    }

    private fun updateSelectAllState() {
        selectAllCheckBox?.let { selectAll ->
            val allSelected = methodCheckBoxes.values.all { it.isSelected }
            selectAll.isSelected = allSelected
            selectAll.isEnabled = methodCheckBoxes.isNotEmpty()
        }
    }

    // ===== TAB 2: CONTEXT & FILES =====

    private fun createContextPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = EmptyBorder(10, 10, 10, 10)

        val innerTabbedPane = JTabbedPane()

        // Related Files tab
        innerTabbedPane.addTab("Related Files", createFileSelectionPanel())

        // Code Snippets tab
        innerTabbedPane.addTab("Code Snippets", createCodeSnippetPanel())

        mainPanel.add(innerTabbedPane, BorderLayout.CENTER)

        // Instruction at top
        val instructionLabel = JLabel(
            "<html><b>Optional:</b> Add files or code snippets that will help the AI understand your code better.<br>" +
                    "This context will be analyzed first before generating tests.</html>"
        )
        instructionLabel.border = EmptyBorder(0, 0, 10, 0)
        mainPanel.add(instructionLabel, BorderLayout.NORTH)

        return mainPanel
    }

    private fun createFileSelectionPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)

        val label = JLabel("Select files that are related to the code you want to test:")
        panel.add(label, BorderLayout.NORTH)

        fileList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        val scrollPane = JBScrollPane(fileList)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        val addFilesButton = JButton("Add Files...")
        addFilesButton.addActionListener { addFiles() }
        buttonPanel.add(addFilesButton)

        val recentFilesButton = JButton("Add Recent Files")
        recentFilesButton.addActionListener { addRecentFiles() }
        buttonPanel.add(recentFilesButton)

        val removeButton = JButton("Remove Selected")
        removeButton.addActionListener { removeSelectedFiles() }
        buttonPanel.add(removeButton)

        val clearButton = JButton("Clear All")
        clearButton.addActionListener { fileListModel.clear() }
        buttonPanel.add(clearButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createCodeSnippetPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)

        val label = JLabel(
            "<html>Paste code snippets, configurations, or examples that might help understand the code:<br>" +
                    "(e.g., API responses, database schemas, configuration files)</html>"
        )
        panel.add(label, BorderLayout.NORTH)

        codeTextArea.rows = 20
        codeTextArea.columns = 60
        codeTextArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val scrollPane = JBScrollPane(codeTextArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        val clearButton = JButton("Clear")
        clearButton.addActionListener { codeTextArea.text = "" }
        buttonPanel.add(clearButton)

        val exampleButton = JButton("Show Example")
        exampleButton.addActionListener { showCodeExample() }
        buttonPanel.add(exampleButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun addFiles() {
        val descriptor = FileChooserDescriptor(
            true, false, false, false, false, true
        )
        descriptor.title = "Select Related Files"
        descriptor.description = "Choose files that are related to the code you want to test"

        val files = FileChooser.chooseFiles(descriptor, project, null)
        files.forEach { file ->
            val path = file.path
            if (!containsPath(path)) {
                fileListModel.addElement(path)
            }
        }
    }

    private fun addRecentFiles() {
        val editorManager = FileEditorManager.getInstance(project)
        val openFiles = editorManager.openFiles

        if (openFiles.isEmpty()) {
            Messages.showInfoMessage(project, "No recently opened files found", "No Recent Files")
            return
        }

        openFiles.forEach { file ->
            val path = file.path
            if (!containsPath(path) && isRelevantFile(file)) {
                fileListModel.addElement(path)
            }
        }
    }

    private fun isRelevantFile(file: VirtualFile): Boolean {
        val name = file.name
        return name.endsWith(".java") || name.endsWith(".kt") ||
                name.endsWith(".xml") || name.endsWith(".properties") ||
                name.endsWith(".json") || name.endsWith(".yaml") ||
                name.endsWith(".yml") || name.endsWith(".sql")
    }

    private fun containsPath(path: String): Boolean {
        for (i in 0 until fileListModel.size()) {
            if (fileListModel.get(i) == path) {
                return true
            }
        }
        return false
    }

    private fun removeSelectedFiles() {
        val selectedIndices = fileList.selectedIndices
        for (i in selectedIndices.size - 1 downTo 0) {
            fileListModel.remove(selectedIndices[i])
        }
    }

    private fun showCodeExample() {
        val example = """
            // Example: Database schema
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                username VARCHAR(50) NOT NULL,
                email VARCHAR(100) NOT NULL
            );

            // Example: API response format
            {
                "status": "success",
                "data": {
                    "userId": 123,
                    "roles": ["admin", "user"]
                }
            }

            // Example: Configuration
            spring.datasource.url=jdbc:postgresql://localhost:5432/testdb
            spring.jpa.hibernate.ddl-auto=update
        """.trimIndent()

        codeTextArea.text = example
    }

    // ===== TAB 3: CONFIGURATION =====

    private fun createConfigurationPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(15, 20, 15, 20)

        // Header
        val headerLabel = JBLabel("Test Generation Configuration")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(headerLabel)
        panel.add(Box.createVerticalStrut(10))

        val descLabel = JBLabel("Configure how tests will be generated for the selected methods")
        descLabel.foreground = UIUtil.getContextHelpForeground()
        panel.add(descLabel)
        panel.add(Box.createVerticalStrut(20))

        // Number of tests per method
        panel.add(createConfigSection(
            "Number of Tests per Method",
            "How many test scenarios should be generated for each selected method",
            createTestsPerMethodPanel()
        ))
        panel.add(Box.createVerticalStrut(15))

        // Test types
        panel.add(createConfigSection(
            "Test Types",
            "Select which types of tests to generate",
            createTestTypesPanel()
        ))
        panel.add(Box.createVerticalStrut(15))

        // Priority levels
        panel.add(createConfigSection(
            "Priority Levels",
            "Select which priority levels to include in test generation",
            createPriorityPanel()
        ))
        panel.add(Box.createVerticalStrut(15))

        // Coverage targets
        panel.add(createConfigSection(
            "Coverage Targets",
            "Select which aspects of the code to focus on when generating tests",
            createCoveragePanel()
        ))
        panel.add(Box.createVerticalStrut(20))

        // Save as default
        panel.add(saveAsDefaultCheckbox)
        panel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(panel)
        scrollPane.border = null
        return scrollPane
    }

    private fun createConfigSection(title: String, description: String, content: JComponent): JComponent {
        val section = JPanel()
        section.layout = BoxLayout(section, BoxLayout.Y_AXIS)
        section.alignmentX = Component.LEFT_ALIGNMENT

        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        section.add(titleLabel)

        val descLabel = JBLabel("<html><i>$description</i></html>")
        descLabel.foreground = UIUtil.getContextHelpForeground()
        section.add(descLabel)
        section.add(Box.createVerticalStrut(8))

        content.alignmentX = Component.LEFT_ALIGNMENT
        section.add(content)

        return section
    }

    private fun createTestsPerMethodPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        panel.alignmentX = Component.LEFT_ALIGNMENT

        testsPerMethodSpinner.preferredSize = Dimension(100, 25)
        panel.add(testsPerMethodSpinner)
        panel.add(Box.createHorizontalStrut(10))
        panel.add(JBLabel("test scenarios per method"))

        return panel
    }

    private fun createTestTypesPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        TestGenerationConfig.TestTypeFilter.values().forEach { testType ->
            val checkBox = JBCheckBox(testType.displayName)
            checkBox.toolTipText = testType.description
            testTypeCheckboxes[testType] = checkBox
            panel.add(checkBox)
            panel.add(Box.createVerticalStrut(5))
        }

        return panel
    }

    private fun createPriorityPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        TestGenerationConfig.PriorityFilter.values().forEach { priority ->
            val checkBox = JBCheckBox(priority.displayName)
            checkBox.toolTipText = priority.description
            priorityCheckboxes[priority] = checkBox
            panel.add(checkBox)
            panel.add(Box.createVerticalStrut(5))
        }

        return panel
    }

    private fun createCoveragePanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        TestGenerationConfig.CoverageTarget.values().forEach { coverage ->
            val checkBox = JBCheckBox(coverage.displayName)
            checkBox.toolTipText = coverage.description
            coverageCheckboxes[coverage] = checkBox
            panel.add(checkBox)
            panel.add(Box.createVerticalStrut(5))
        }

        return panel
    }

    // ===== CONFIGURATION PERSISTENCE =====

    private fun loadConfiguration() {
        val settings = ZestProjectSettings.getInstance(project)
        val config = settings.testGenerationConfig

        testsPerMethodSpinner.value = config.testsPerMethod

        config.testTypeFilters.forEach { testType ->
            testTypeCheckboxes[testType]?.isSelected = true
        }

        config.priorityFilters.forEach { priority ->
            priorityCheckboxes[priority]?.isSelected = true
        }

        config.coverageTargets.forEach { coverage ->
            coverageCheckboxes[coverage]?.isSelected = true
        }
    }

    private fun saveConfiguration() {
        if (saveAsDefaultCheckbox.isSelected) {
            val settings = ZestProjectSettings.getInstance(project)
            settings.setTestGenerationConfig(getConfiguration())
        }
    }

    // ===== PUBLIC GETTERS =====

    fun getSelectedMethods(): List<PsiMethod> {
        return methodCheckBoxes.entries
            .filter { it.value.isSelected }
            .map { it.key }
    }

    fun getSelectedFiles(): List<String> {
        val files = mutableListOf<String>()
        for (i in 0 until fileListModel.size()) {
            files.add(fileListModel.get(i))
        }
        return files
    }

    fun getProvidedCode(): String? {
        val code = codeTextArea.text
        return if (code.isNullOrBlank()) null else code
    }

    fun getConfiguration(): TestGenerationConfig {
        val testsPerMethod = testsPerMethodSpinner.value as Int

        val testTypes = testTypeCheckboxes.entries
            .filter { it.value.isSelected }
            .map { it.key }
            .toSet()

        val priorities = priorityCheckboxes.entries
            .filter { it.value.isSelected }
            .map { it.key }
            .toSet()

        val coverageTargets = coverageCheckboxes.entries
            .filter { it.value.isSelected }
            .map { it.key }
            .toSet()

        return TestGenerationConfig(testsPerMethod, testTypes, priorities, coverageTargets)
    }

    // ===== DIALOG ACTIONS =====

    override fun createActions(): Array<Action> {
        return arrayOf(
            okAction.apply { putValue(Action.NAME, "Generate Tests") },
            cancelAction
        )
    }

    override fun doOKAction() {
        if (getSelectedMethods().isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select at least one method to test",
                "No Methods Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Validate configuration
        val config = getConfiguration()
        if (config.testTypeFilters.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select at least one test type",
                "No Test Types Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        if (config.priorityFilters.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select at least one priority level",
                "No Priority Levels Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        saveConfiguration()
        super.doOKAction()
    }
}
