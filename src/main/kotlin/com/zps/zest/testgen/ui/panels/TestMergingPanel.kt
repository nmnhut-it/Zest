package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.model.MergedTestClass
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel that displays merged test class with error detection and fixing capabilities.
 * Shows merge results, compilation errors, and provides access to merger agent chat memory.
 */
class TestMergingPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val statusLabel = JBLabel("No merged test class yet")
    private val mergedClassArea = JBTextArea()
    private val issuesListModel = DefaultListModel<TestIssue>()
    private val issuesList = JList(issuesListModel)
    private val issuesMap = mutableMapOf<String, TestIssue>()
    
    // Chat memory for AITestMergerAgent
    private var mergerAgentMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var mergerAgentName: String = "AI Merger Agent"
    
    // Current merged test class
    private var currentMergedClass: MergedTestClass? = null
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        
        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)
        headerPanel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("🔗 Test Merging & Review")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.WEST)
        
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(statusLabel, BorderLayout.EAST)
        
        add(headerPanel, BorderLayout.NORTH)
        
        // Main content - split between merged class and issues
        val mainSplitter = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        mainSplitter.setDividerLocation(0.6)
        mainSplitter.resizeWeight = 0.6
        
        // Top: Merged class display
        val classPanel = createMergedClassPanel()
        mainSplitter.topComponent = classPanel
        
        // Bottom: Issues and fixes
        val issuesPanel = createIssuesPanel()
        mainSplitter.bottomComponent = issuesPanel
        
        add(mainSplitter, BorderLayout.CENTER)
        
        // Bottom panel with actions
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = EmptyBorder(5, 10, 10, 10)
        bottomPanel.background = UIUtil.getPanelBackground()
        
        val actionsPanel = createActionsPanel()
        bottomPanel.add(actionsPanel, BorderLayout.EAST)
        
        add(bottomPanel, BorderLayout.SOUTH)
    }
    
    private fun createMergedClassPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(5, 10, 5, 10)
        
        // Header
        val headerLabel = JBLabel("📄 Merged Test Class")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 12f)
        panel.add(headerLabel, BorderLayout.NORTH)
        
        // Merged class code display
        mergedClassArea.isEditable = false
        mergedClassArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        mergedClassArea.background = UIUtil.getTextFieldBackground()
        mergedClassArea.text = "// Merged test class will appear here after merging"
        
        // Add syntax highlighting would be nice but complex - keeping simple for now
        val scrollPane = JBScrollPane(mergedClassArea)
        scrollPane.border = BorderFactory.createCompoundBorder(
            EmptyBorder(5, 0, 0, 0),
            BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        )
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createIssuesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(5, 10, 5, 10)
        
        // Header
        val headerLabel = JBLabel("🔍 Issues & Fixes")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 12f)
        panel.add(headerLabel, BorderLayout.NORTH)
        
        // Issues list
        issuesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        issuesList.cellRenderer = IssueListCellRenderer()
        
        // Add double-click to show fix suggestions
        issuesList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = issuesList.locationToIndex(e.point)
                    if (index >= 0) {
                        val issue = issuesListModel.getElementAt(index)
                        showFixSuggestions(issue)
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(issuesList)
        scrollPane.border = BorderFactory.createCompoundBorder(
            EmptyBorder(5, 0, 0, 0),
            BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        )
        scrollPane.preferredSize = JBUI.size(400, 150)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createActionsPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        panel.isOpaque = false
        
        val reviewButton = JButton("🔍 Review Code")
        reviewButton.addActionListener { triggerCodeReview() }
        reviewButton.toolTipText = "Analyze merged test class for errors and improvements"
        panel.add(reviewButton)
        
        val autoFixButton = JButton("🔧 Auto-Fix Issues")
        autoFixButton.addActionListener { triggerAutoFix() }
        autoFixButton.toolTipText = "Automatically fix detected issues"
        panel.add(autoFixButton)
        
        val chatButton = JButton("💬 Merger Chat")
        chatButton.addActionListener { openMergerChatDialog() }
        chatButton.toolTipText = "View AI Merger agent chat memory"
        panel.add(chatButton)
        
        val exportButton = JButton("📋 Copy Class")
        exportButton.addActionListener { copyMergedClassToClipboard() }
        exportButton.toolTipText = "Copy merged test class to clipboard"
        panel.add(exportButton)
        
        return panel
    }
    
    /**
     * Update the merged test class display
     */
    fun updateMergedClass(mergedClass: MergedTestClass) {
        SwingUtilities.invokeLater {
            currentMergedClass = mergedClass
            mergedClassArea.text = mergedClass.fullContent
            
            // Update status
            statusLabel.text = "${mergedClass.className} (${mergedClass.methodCount} methods)"
            
            // Auto-trigger basic review
            detectBasicIssues(mergedClass)
        }
    }
    
    /**
     * Add or update an issue in the issues list
     */
    fun addIssue(issue: TestIssue) {
        SwingUtilities.invokeLater {
            issuesMap[issue.id] = issue
            
            // Update list model
            issuesListModel.clear()
            issuesMap.values.sortedBy { it.severity.ordinal }.forEach { 
                issuesListModel.addElement(it) 
            }
            
            updateIssuesStatus()
        }
    }
    
    /**
     * Clear all issues
     */
    fun clearIssues() {
        SwingUtilities.invokeLater {
            issuesMap.clear()
            issuesListModel.clear()
            updateIssuesStatus()
        }
    }
    
    /**
     * Clear display
     */
    fun clear() {
        SwingUtilities.invokeLater {
            currentMergedClass = null
            mergedClassArea.text = "// Merged test class will appear here after merging"
            statusLabel.text = "No merged test class yet"
            clearIssues()
        }
    }
    
    /**
     * Set chat memory for the merger agent
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory?, agentName: String = "AI Merger Agent") {
        this.mergerAgentMemory = chatMemory
        this.mergerAgentName = agentName
    }
    
    /**
     * Detect basic issues in merged test class
     */
    private fun detectBasicIssues(mergedClass: MergedTestClass) {
        clearIssues()
        
        val content = mergedClass.fullContent
        val lines = content.split("\n")
        
        // Check for missing imports
        if (!content.contains("import org.junit") && content.contains("@Test")) {
            addIssue(TestIssue(
                "missing_junit_import",
                "Missing JUnit imports",
                "Test class uses @Test annotation but missing JUnit imports",
                TestIssue.Severity.ERROR,
                null,
                "Add: import org.junit.jupiter.api.Test"
            ))
        }
        
        // Check for test methods without assertions
        val testMethods = extractTestMethods(content)
        testMethods.forEachIndexed { index, method ->
            if (!method.body.contains("assert") && !method.body.contains("verify") && !method.body.contains("expect")) {
                addIssue(TestIssue(
                    "no_assertions_${index}",
                    "Missing assertions in ${method.name}",
                    "Test method ${method.name} has no assertions - tests should verify expected behavior",
                    TestIssue.Severity.WARNING,
                    method.lineNumber,
                    "Add assertions like assertEquals(), assertTrue(), or assertThat()"
                ))
            }
        }
        
        // Check for empty test methods
        testMethods.forEach { method ->
            val bodyTrimmed = method.body.replace(Regex("//.*"), "").replace(Regex("/\\*.*?\\*/"), "").trim()
            if (bodyTrimmed.isEmpty()) {
                addIssue(TestIssue(
                    "empty_method_${method.name}",
                    "Empty test method: ${method.name}",
                    "Test method ${method.name} has no implementation",
                    TestIssue.Severity.ERROR,
                    method.lineNumber,
                    "Implement test logic with Given-When-Then pattern"
                ))
            }
        }
    }
    
    private fun extractTestMethods(content: String): List<TestMethod> {
        val methods = mutableListOf<TestMethod>()
        val lines = content.split("\n")
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("@Test")) {
                // Find method signature in next few lines
                for (j in (i + 1)..minOf(i + 5, lines.size - 1)) {
                    val methodLine = lines[j].trim()
                    if (methodLine.contains("void ") && methodLine.contains("(")) {
                        val methodName = extractMethodName(methodLine)
                        val methodBody = extractMethodBody(lines, j)
                        methods.add(TestMethod(methodName, methodBody, j + 1))
                        break
                    }
                }
            }
        }
        
        return methods
    }
    
    private fun extractMethodName(methodLine: String): String {
        val voidIndex = methodLine.indexOf("void ")
        val parenIndex = methodLine.indexOf("(")
        return if (voidIndex >= 0 && parenIndex > voidIndex) {
            methodLine.substring(voidIndex + 5, parenIndex).trim()
        } else {
            "unknownMethod"
        }
    }
    
    private fun extractMethodBody(lines: List<String>, startIndex: Int): String {
        val body = StringBuilder()
        var braceCount = 0
        var foundOpenBrace = false
        
        for (i in startIndex until lines.size) {
            val line = lines[i]
            
            for (char in line) {
                if (char == '{') {
                    braceCount++
                    foundOpenBrace = true
                }
                if (char == '}') braceCount--
            }
            
            if (foundOpenBrace && braceCount > 0) {
                body.append(line).append("\n")
            } else if (foundOpenBrace && braceCount == 0) {
                break
            }
        }
        
        return body.toString().trim()
    }
    
    private fun updateIssuesStatus() {
        val errorCount = issuesMap.values.count { it.severity == TestIssue.Severity.ERROR }
        val warningCount = issuesMap.values.count { it.severity == TestIssue.Severity.WARNING }
        
        statusLabel.text = buildString {
            currentMergedClass?.let { 
                append("${it.className} (${it.methodCount} methods)")
                if (errorCount > 0 || warningCount > 0) {
                    append(" | ")
                    if (errorCount > 0) append("❌ $errorCount errors ")
                    if (warningCount > 0) append("⚠️ $warningCount warnings")
                }
            }
        }
    }
    
    private fun triggerCodeReview() {
        currentMergedClass?.let { mergedClass ->
            // Clear existing issues before review
            clearIssues()
            
            // Placeholder for AITestMergerAgent integration
            // In actual implementation, this would call: aiMergerAgent.reviewTestClass(mergedClass.fullContent)
            val worker = object : SwingWorker<String, Void>() {
                override fun doInBackground(): String {
                    Thread.sleep(1000) // Simulate review time
                    return simulateReviewResults(mergedClass.fullContent)
                }
                
                override fun done() {
                    try {
                        val reviewResults = get()
                        parseAndDisplayReviewResults(reviewResults)
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(
                            this@TestMergingPanel,
                            "Code review failed: ${e.message}",
                            "Review Error", 
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
            worker.execute()
        } ?: run {
            JOptionPane.showMessageDialog(
                this,
                "No merged test class available for review",
                "Code Review",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
    
    private fun simulateReviewResults(testClassCode: String): String {
        return """
        COMPILATION_ERRORS:
        - Line 15: Missing import for Assertions class
        
        LOGICAL_ISSUES:
        - Line 23: Test method has no assertions - add assertEquals or assertTrue
        - Line 35: Empty test method needs implementation
        
        QUALITY_IMPROVEMENTS:
        - Line 28: Method name should follow testMethod_WhenCondition_ThenResult pattern
        - Line 42: Add meaningful assertion failure message
        
        SUGGESTIONS:
        - Add import: static org.junit.jupiter.api.Assertions.*
        - Implement missing assertions in testUserCreation()
        - Add proper Given-When-Then structure to empty test methods
        """.trimIndent()
    }
    
    private fun parseAndDisplayReviewResults(reviewResults: String) {
        val lines = reviewResults.split("\n")
        var currentSection = ""
        
        for (line in lines) {
            when {
                line.startsWith("COMPILATION_ERRORS:") -> currentSection = "COMPILATION"
                line.startsWith("LOGICAL_ISSUES:") -> currentSection = "LOGICAL"
                line.startsWith("QUALITY_IMPROVEMENTS:") -> currentSection = "QUALITY"
                line.startsWith("- Line ") -> {
                    parseIssueLine(line, currentSection)
                }
            }
        }
    }
    
    private fun parseIssueLine(line: String, section: String) {
        try {
            // Parse "- Line X: Description"
            val parts = line.substring(2).split(": ", limit = 2) // Remove "- "
            if (parts.size == 2) {
                val lineNum = parts[0].replace("Line ", "").toIntOrNull()
                val description = parts[1]
                
                val severity = when (section) {
                    "COMPILATION" -> TestIssue.Severity.ERROR
                    "LOGICAL" -> TestIssue.Severity.WARNING
                    "QUALITY" -> TestIssue.Severity.INFO
                    else -> TestIssue.Severity.INFO
                }
                
                addIssue(TestIssue(
                    "${section}_${lineNum}_${System.currentTimeMillis()}",
                    if (lineNum != null) "Line $lineNum: $description" else description,
                    description,
                    severity,
                    lineNum,
                    "Auto-fix available" // Placeholder
                ))
            }
        } catch (e: Exception) {
            // Skip malformed lines
        }
    }
    
    private fun triggerAutoFix() {
        val selectedIssue = issuesList.selectedValue
        if (selectedIssue != null) {
            // TODO: Implement auto-fix functionality
            JOptionPane.showMessageDialog(
                this,
                "Auto-fix for: ${selectedIssue.title}\n\nSuggestion: ${selectedIssue.suggestion}",
                "Auto-Fix",
                JOptionPane.INFORMATION_MESSAGE
            )
        } else if (issuesMap.isNotEmpty()) {
            // Auto-fix all issues
            JOptionPane.showMessageDialog(
                this,
                "Auto-fixing ${issuesMap.size} issues...\n\nThis will be implemented with enhanced AITestMergerAgent",
                "Auto-Fix All",
                JOptionPane.INFORMATION_MESSAGE
            )
        } else {
            JOptionPane.showMessageDialog(
                this,
                "No issues detected to fix",
                "Auto-Fix",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
    
    private fun showFixSuggestions(issue: TestIssue) {
        val message = buildString {
            appendLine("Issue: ${issue.title}")
            appendLine()
            appendLine("Description: ${issue.description}")
            if (issue.lineNumber != null) {
                appendLine("Line: ${issue.lineNumber}")
            }
            appendLine()
            appendLine("Suggested Fix:")
            appendLine(issue.suggestion)
        }
        
        JOptionPane.showMessageDialog(
            this,
            message,
            "Fix Suggestion",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    private fun openMergerChatDialog() {
        val dialog = ChatMemoryDialog(project, mergerAgentMemory, mergerAgentName)
        dialog.show()
    }
    
    private fun copyMergedClassToClipboard() {
        currentMergedClass?.let { mergedClass ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(mergedClass.fullContent), null)
            
            JOptionPane.showMessageDialog(
                this,
                "Merged test class copied to clipboard",
                "Export Successful",
                JOptionPane.INFORMATION_MESSAGE
            )
        } ?: run {
            JOptionPane.showMessageDialog(
                this,
                "No merged test class available to copy",
                "Export",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
    
    /**
     * Get all detected issues for external processing
     */
    fun getDetectedIssues(): List<TestIssue> {
        return issuesMap.values.toList()
    }
    
    /**
     * Data class for test issues
     */
    data class TestIssue(
        val id: String,
        val title: String,
        val description: String,
        val severity: Severity,
        val lineNumber: Int? = null,
        val suggestion: String? = null
    ) {
        enum class Severity {
            ERROR, WARNING, INFO
        }
        
        fun getIcon(): String = when (severity) {
            Severity.ERROR -> "❌"
            Severity.WARNING -> "⚠️"
            Severity.INFO -> "ℹ️"
        }
        
        fun getSummary(): String = "${getIcon()} $title${lineNumber?.let { " (line $it)" } ?: ""}"
    }
    
    /**
     * Data class for test methods
     */
    private data class TestMethod(
        val name: String,
        val body: String,
        val lineNumber: Int
    )
    
    /**
     * Custom cell renderer for issues list
     */
    private class IssueListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (value is TestIssue && component is JLabel) {
                val panel = JPanel(BorderLayout())
                panel.background = if (isSelected) list.selectionBackground else list.background
                panel.border = EmptyBorder(8, 10, 8, 10)
                
                // Issue summary with icon
                val titleLabel = JBLabel(value.getSummary())
                titleLabel.foreground = when {
                    isSelected -> list.selectionForeground
                    value.severity == TestIssue.Severity.ERROR -> Color(255, 0, 0)
                    value.severity == TestIssue.Severity.WARNING -> Color(255, 140, 0)
                    else -> list.foreground
                }
                titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
                panel.add(titleLabel, BorderLayout.NORTH)
                
                // Description
                val descLabel = JBLabel("<html>${value.description}</html>")
                descLabel.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
                panel.add(descLabel, BorderLayout.CENTER)
                
                // Suggestion hint
                if (value.suggestion != null) {
                    val suggestionLabel = JBLabel("💡 Double-click for fix suggestion")
                    suggestionLabel.foreground = UIUtil.getContextHelpForeground()
                    suggestionLabel.font = suggestionLabel.font.deriveFont(10f)
                    panel.add(suggestionLabel, BorderLayout.SOUTH)
                }
                
                return panel
            }
            
            return component
        }
    }
}