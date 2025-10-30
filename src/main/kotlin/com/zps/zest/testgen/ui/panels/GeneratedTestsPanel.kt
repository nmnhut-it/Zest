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
 * Simplified panel that displays generated test classes with real-time streaming capability.
 * Uses native EditorEx for both streaming and completed test display.
 * No heavy JCEF browser or complex split panes.
 */
class GeneratedTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    // Single container for all content
    private val mainContainer = JPanel()
    private val scrollPane = JBScrollPane(mainContainer)

    // Streaming editor (created when needed)
    private var streamingEditor: EditorEx? = null
    private var streamingPanel: JPanel? = null
    private val streamingContent = StringBuilder()
    private var currentStreamingClass: String? = null

    // Smooth streaming buffer and timer
    private val tokenBuffer = StringBuilder()
    private var smoothStreamingTimer: javax.swing.Timer? = null
    private var bufferPosition = 0

    // Completed tests storage
    private val testClassEditors = mutableListOf<EditorEx>()
    private val testClasses = mutableListOf<GeneratedTestDisplayData>()

    // UI components
    private val statusLabel = JBLabel("No tests generated yet")

    // Chat memory for debugging
    private var testWriterMemory: dev.langchain4j.memory.ChatMemory? = null
    private var testWriterAgentName: String = "TestWriter Agent"

    init {
        setupUI()
    }

    private fun setupUI() {
        background = UIUtil.getPanelBackground()

        // Header panel
        val headerPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(10, 10, 10, 10)
            background = UIUtil.getPanelBackground()
        }

        val titleLabel = JBLabel("🧪 Generated Test Classes").apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        headerPanel.add(titleLabel, BorderLayout.WEST)

        statusLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(statusLabel, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)

        // Main container setup - vertical layout for streaming + completed tests
        mainContainer.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = EmptyBorder(5, 10, 5, 10)
        }

        // Scroll pane for main content
        scrollPane.apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // Bottom actions panel
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(5, 10, 10, 10)
            background = UIUtil.getPanelBackground()
        }

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
        }

        val clearButton = JButton("Clear All").apply {
            addActionListener { clear() }
        }
        actionsPanel.add(clearButton)

        val chatButton = JButton("View Chat").apply {
            addActionListener { openTestWriterChatDialog() }
        }
        actionsPanel.add(chatButton)

        val snapshotButton = JButton("📸 Manage Snapshots").apply {
            preferredSize = JBUI.size(180, 40)
            addActionListener { showSnapshotManagerDialog() }
            toolTipText = "View and manage agent snapshots from this session"
        }
        actionsPanel.add(snapshotButton)

        bottomPanel.add(actionsPanel, BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    /**
     * Start streaming for a new test class
     */
    fun startStreaming(className: String) {
        SwingUtilities.invokeLater {
            // Clear previous streaming if any
            clearStreamingEditor()
            stopSmoothStreaming()

            streamingContent.clear()
            tokenBuffer.clear()
            bufferPosition = 0
            currentStreamingClass = className

            // Create streaming editor panel
            createStreamingEditor(className)

            statusLabel.text = "Generating test for $className..."

            // Start smooth streaming timer
            startSmoothStreaming()

            mainContainer.revalidate()
            mainContainer.repaint()
        }
    }

    /**
     * Start smooth streaming timer - emits buffered characters gradually
     */
    private fun startSmoothStreaming() {
        stopSmoothStreaming()

        // Timer fires every 15ms, emits multiple chars for smooth but fast rendering
        smoothStreamingTimer = javax.swing.Timer(15) {
            val charsToEmit = 3 // Emit 10 chars at a time (smooth but not too slow)
            val currentBuffer = synchronized(tokenBuffer) {
                val availableChars = tokenBuffer.length - bufferPosition
                if (availableChars > 0) {
                    val endPos = minOf(bufferPosition + charsToEmit, tokenBuffer.length)
                    val chunk = tokenBuffer.substring(bufferPosition, endPos)
                    bufferPosition = endPos
                    chunk
                } else {
                    null
                }
            }

            currentBuffer?.let { chunk ->
                streamingContent.append(chunk)
                updateEditorContent()
            }
        }.apply {
            start()
        }
    }

    /**
     * Stop smooth streaming timer
     */
    private fun stopSmoothStreaming() {
        smoothStreamingTimer?.stop()
        smoothStreamingTimer = null
    }

    /**
     * Update editor with current streaming content
     */
    private fun updateEditorContent() {
        streamingEditor?.let { editor ->
            val document = editor.document
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                document.setText(streamingContent.toString())
            }
            // Auto-scroll to bottom
            editor.scrollingModel.scrollVertically(document.textLength)
        }
    }

    /**
     * Append token to buffer (from LLM) - will be smoothly rendered by timer
     */
    fun appendStreamingContent(token: String) {
        synchronized(tokenBuffer) {
            tokenBuffer.append(token)
        }
    }

    /**
     * Finalize the streaming content
     */
    fun showActivity(message: String) {
        // Activity shown via streaming, no separate indicator needed
    }

    fun finalizeStreaming() {
        // Flush any remaining buffer content immediately
        synchronized(tokenBuffer) {
            if (bufferPosition < tokenBuffer.length) {
                val remaining = tokenBuffer.substring(bufferPosition)
                streamingContent.append(remaining)
                bufferPosition = tokenBuffer.length
            }
        }

        SwingUtilities.invokeLater {
            stopSmoothStreaming()
            updateEditorContent()
            updateStatus()
            currentStreamingClass = null
        }
    }

    /**
     * Add a generated test class to the display
     */
    fun addGeneratedTest(test: GeneratedTestDisplayData) {
        SwingUtilities.invokeLater {
            testClasses.add(test)

            // Clear streaming editor since we have the final test
            clearStreamingEditor()

            // Create panel for this test class
            val testPanel = createTestClassPanel(test)
            mainContainer.add(testPanel, 0) // Add at top
            mainContainer.add(Box.createVerticalStrut(10), 1)

            updateStatus()
            mainContainer.revalidate()
            mainContainer.repaint()
        }
    }

    /**
     * Create streaming editor panel
     */
    private fun createStreamingEditor(className: String) {
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                EmptyBorder(5, 5, 5, 5),
                BorderFactory.createLineBorder(UIUtil.getBoundsColor())
            )
            background = UIUtil.getPanelBackground()
        }

        // Header with streaming indicator
        val headerPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 10, 8, 10)
            background = UIUtil.getPanelBackground()
        }

        val classLabel = JBLabel("🔄 Generating: $className").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = JBUI.CurrentTheme.Link.linkColor()
        }
        headerPanel.add(classLabel, BorderLayout.WEST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Create editor for streaming content
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
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
        }

        // Set Java syntax highlighting
        val javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, javaFileType)
        editor.highlighter = highlighter

        // Store references
        streamingEditor = editor
        streamingPanel = panel
        testClassEditors.add(editor)

        // Add editor to panel with preferred size
        val editorComponent = editor.component.apply {
            preferredSize = Dimension(preferredSize.width, 300)
        }
        panel.add(editorComponent, BorderLayout.CENTER)

        // Add to main container at top
        mainContainer.add(panel, 0)
        mainContainer.add(Box.createVerticalStrut(10), 1)
    }

    /**
     * Clear the streaming editor
     */
    private fun clearStreamingEditor() {
        streamingPanel?.let { panel ->
            mainContainer.remove(panel)
            // Also remove the vertical strut after it
            if (mainContainer.componentCount > 0 && mainContainer.getComponent(0) is Box.Filler) {
                mainContainer.remove(0)
            }
        }

        streamingEditor?.let { editor ->
            testClassEditors.remove(editor)
            EditorFactory.getInstance().releaseEditor(editor)
        }

        streamingEditor = null
        streamingPanel = null
    }

    /**
     * Create a panel with embedded editor for displaying a test class
     */
    private fun createTestClassPanel(test: GeneratedTestDisplayData): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                EmptyBorder(5, 5, 5, 5),
                BorderFactory.createLineBorder(UIUtil.getBoundsColor())
            )
            background = UIUtil.getPanelBackground()
        }

        // Header with class name and actions
        val headerPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 10, 8, 10)
            background = UIUtil.getPanelBackground()
        }

        val classLabel = JBLabel("📄 ${test.className}").apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }
        headerPanel.add(classLabel, BorderLayout.WEST)

        // Action buttons
        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            isOpaque = false
        }

        val copyButton = JButton("Copy").apply {
            addActionListener { copyToClipboard(test) }
        }
        actionPanel.add(copyButton)

        val timestampLabel = JBLabel(formatTimestamp(test.timestamp)).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(11f)
        }
        actionPanel.add(timestampLabel)

        headerPanel.add(actionPanel, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Create editor for syntax highlighting
        val editorComponent = createEditorComponent(test.fullTestCode)
        panel.add(editorComponent, BorderLayout.CENTER)

        // Set preferred height based on content
        val lineCount = test.fullTestCode.lines().size
        val preferredHeight = minOf(400, 100 + lineCount * 15)
        panel.preferredSize = Dimension(panel.preferredSize.width, preferredHeight)
        panel.maximumSize = Dimension(Integer.MAX_VALUE, preferredHeight)

        return panel
    }

    /**
     * Create an editor component with syntax-highlighted code
     */
    private fun createEditorComponent(code: String): JComponent {
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
        }

        // Set Java syntax highlighting
        val javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, javaFileType)
        editor.highlighter = highlighter

        // Track editor for disposal
        testClassEditors.add(editor)

        return editor.component
    }

    /**
     * Copy test code to clipboard
     */
    private fun copyToClipboard(test: GeneratedTestDisplayData) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(test.fullTestCode), null)

        statusLabel.text = "Copied ${test.className} to clipboard"
        Timer(2000) { statusLabel.text = updateStatusText() }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Format timestamp for display
     */
    private fun formatTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60000 -> "just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            else -> "${diff / 3600000}h ago"
        }
    }

    /**
     * Clear all tests
     */
    fun clear() {
        SwingUtilities.invokeLater {
            // Clear streaming editor
            clearStreamingEditor()

            // Release all editors
            testClassEditors.forEach { editor ->
                EditorFactory.getInstance().releaseEditor(editor)
            }
            testClassEditors.clear()
            testClasses.clear()

            // Clear container
            mainContainer.removeAll()

            statusLabel.text = "No tests generated yet"
            mainContainer.revalidate()
            mainContainer.repaint()
        }
    }

    /**
     * Update status label text
     */
    private fun updateStatusText(): String {
        val count = testClasses.size
        return when (count) {
            0 -> "No tests generated yet"
            1 -> "1 test class generated"
            else -> "$count test classes generated"
        }
    }

    /**
     * Update status label
     */
    private fun updateStatus() {
        statusLabel.text = updateStatusText()
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
    fun setChatMemory(chatMemory: dev.langchain4j.memory.ChatMemory?, agentName: String = "TestWriter Agent") {
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
     * Open snapshot manager dialog
     */
    private fun showSnapshotManagerDialog() {
        val dialog = com.zps.zest.testgen.snapshot.ui.SnapshotManagerDialog(project)
        DialogManager.showDialog(dialog)
    }

    /**
     * Clean up resources when panel is disposed
     */
    fun dispose() {
        // Clear streaming editor
        clearStreamingEditor()

        // Release all editors
        testClassEditors.forEach { editor ->
            EditorFactory.getInstance().releaseEditor(editor)
        }
        testClassEditors.clear()

        // Clear data
        streamingContent.clear()
        currentStreamingClass = null
    }
}