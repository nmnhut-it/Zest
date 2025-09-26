package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.langchain4j.ui.DialogManager
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel that displays complete generated test classes with syntax highlighting.
 * Each test class is shown with its full code in an embedded editor.
 */
class GeneratedTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val testsContainer = JPanel()
    private val statusLabel = JBLabel("No tests generated yet")
    private val testClassEditors = mutableListOf<EditorEx>()
    private val testClasses = mutableListOf<GeneratedTestDisplayData>()
    private var testWriterMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var testWriterAgentName: String = "TestWriter Agent"

    init {
        setupUI()
    }

    private fun setupUI() {
        background = UIUtil.getPanelBackground()

        // Header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(10, 10, 10, 10)
        headerPanel.background = UIUtil.getPanelBackground()

        val titleLabel = JBLabel("🧪 Generated Test Classes")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.WEST)

        statusLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(statusLabel, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)

        // Container for test classes
        testsContainer.layout = BoxLayout(testsContainer, BoxLayout.Y_AXIS)
        testsContainer.background = UIUtil.getPanelBackground()

        val scrollPane = JBScrollPane(testsContainer)
        scrollPane.border = BorderFactory.createCompoundBorder(
            EmptyBorder(0, 10, 10, 10),
            BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        )
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)

        // Bottom panel with actions
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = EmptyBorder(5, 10, 10, 10)
        bottomPanel.background = UIUtil.getPanelBackground()

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        actionsPanel.isOpaque = false

        val clearButton = JButton("Clear All")
        clearButton.addActionListener { clear() }
        actionsPanel.add(clearButton)

        val chatButton = JButton("💬 Writer Chat")
        chatButton.addActionListener { openTestWriterChatDialog() }
        chatButton.toolTipText = "View TestWriter agent chat memory"
        actionsPanel.add(chatButton)

        bottomPanel.add(actionsPanel, BorderLayout.EAST)

        add(bottomPanel, BorderLayout.SOUTH)
    }

    /**
     * Add a generated test class to the display
     */
    fun addGeneratedTest(test: GeneratedTestDisplayData) {
        SwingUtilities.invokeLater {
            testClasses.add(test)

            // Create panel for this test class
            val testPanel = createTestClassPanel(test)
            testsContainer.add(testPanel)
            testsContainer.add(Box.createVerticalStrut(10))

            updateStatus()
            testsContainer.revalidate()
            testsContainer.repaint()
        }
    }

    /**
     * Create a panel with embedded editor for displaying a test class
     */
    private fun createTestClassPanel(test: GeneratedTestDisplayData): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createCompoundBorder(
            EmptyBorder(5, 5, 5, 5),
            BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        )
        panel.background = UIUtil.getPanelBackground()

        // Header with class name and actions
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(8, 10, 8, 10)
        headerPanel.background = UIUtil.getPanelBackground()

        val classLabel = JBLabel("📄 ${test.className}")
        classLabel.font = classLabel.font.deriveFont(Font.BOLD, 13f)
        headerPanel.add(classLabel, BorderLayout.WEST)

        // Action buttons for this test
        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        actionPanel.isOpaque = false

        val copyButton = JButton("Copy")
        copyButton.addActionListener {
            copyToClipboard(test)
        }
        actionPanel.add(copyButton)

        val timestampLabel = JBLabel(formatTimestamp(test.timestamp))
        timestampLabel.foreground = UIUtil.getContextHelpForeground()
        timestampLabel.font = timestampLabel.font.deriveFont(11f)
        actionPanel.add(timestampLabel)

        headerPanel.add(actionPanel, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Create editor for syntax highlighting
        val editorPanel = createEditorPanel(test.fullTestCode)
        panel.add(editorPanel, BorderLayout.CENTER)

        // Set preferred height based on content (max 400px)
        val lineCount = test.fullTestCode.lines().size
        val preferredHeight = minOf(400, 100 + lineCount * 15)
        panel.preferredSize = Dimension(panel.preferredSize.width, preferredHeight)
        panel.maximumSize = Dimension(Integer.MAX_VALUE, preferredHeight)

        return panel
    }

    /**
     * Create an editor panel with syntax-highlighted code
     */
    private fun createEditorPanel(code: String): JComponent {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(code)
        val editor = editorFactory.createViewer(document, project) as EditorEx

        // Configure editor
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = true
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }

        // Set Java syntax highlighting
        val javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project, javaFileType
        )
        editor.highlighter = highlighter
        editor.isViewer = true

        // Track editor for cleanup
        testClassEditors.add(editor)

        return editor.component
    }

    /**
     * Format timestamp for display
     */
    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diffMinutes = (now - timestamp) / (1000 * 60)
        return when {
            diffMinutes < 1 -> "just now"
            diffMinutes < 60 -> "$diffMinutes min ago"
            else -> "${diffMinutes / 60}h ago"
        }
    }

    /**
     * Copy test class to clipboard
     */
    private fun copyToClipboard(test: GeneratedTestDisplayData) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(test.fullTestCode), null)

        JOptionPane.showMessageDialog(
            this,
            "Test class '${test.className}' copied to clipboard",
            "Success",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    /**
     * Update test validation status - simplified version
     */
    fun updateTestStatus(testName: String, status: Any) {
        // Simplified - no validation status in new model
        SwingUtilities.invokeLater {
            updateStatus()
        }
    }

    /**
     * Show progress for test generation
     */
    fun showProgress(current: Int, total: Int) {
        SwingUtilities.invokeLater {
            if (total > 0) {
                statusLabel.text = "Generating test $current of $total..."
            } else {
                updateStatus()
            }
        }
    }

    /**
     * Clear all tests
     */
    fun clear() {
        SwingUtilities.invokeLater {
            // Release all editors
            testClassEditors.forEach { editor ->
                EditorFactory.getInstance().releaseEditor(editor)
            }
            testClassEditors.clear()
            testClasses.clear()
            testsContainer.removeAll()
            statusLabel.text = "No tests generated yet"
            testsContainer.revalidate()
            testsContainer.repaint()
        }
    }

    /**
     * Update status label
     */
    private fun updateStatus() {
        val count = testClasses.size
        statusLabel.text = when (count) {
            0 -> "No tests generated yet"
            1 -> "1 test class generated"
            else -> "$count test classes generated"
        }
    }

    /**
     * Get all generated tests for saving
     */
    fun getGeneratedTests(): List<GeneratedTestDisplayData> {
        return testClasses.toList()
    }

    /**
     * Set chat memory for the test writer agent
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory?, agentName: String = "TestWriter Agent") {
        this.testWriterMemory = chatMemory
        this.testWriterAgentName = agentName
    }

    /**
     * Open test writer chat memory dialog
     */
    private fun openTestWriterChatDialog() {
        val dialog = ChatMemoryDialog(project, testWriterMemory, testWriterAgentName)
        DialogManager.showDialog(dialog)
    }

    /**
     * Clean up resources when panel is disposed
     */
    fun dispose() {
        testClassEditors.forEach { editor ->
            EditorFactory.getInstance().releaseEditor(editor)
        }
        testClassEditors.clear()
    }
}