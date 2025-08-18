package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for viewing generated test code with syntax highlighting.
 * Shows test code, validation results, and allows copying/exporting.
 */
class TestCodeViewerDialog(
    private val project: Project,
    private val testData: GeneratedTestDisplayData
) : DialogWrapper(project) {
    
    private var editor: EditorEx? = null
    
    init {
        title = "Test Code: ${testData.testName}"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(900, 650)
        
        // Header with test info
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Tabbed pane for code and validation
        val tabbedPane = JBTabbedPane()
        
        // Code tab
        val codePanel = createCodePanel()
        tabbedPane.addTab("Test Code", codePanel)
        
        // Validation tab (if validation was performed)
        if (testData.validationMessages.isNotEmpty()) {
            val validationPanel = createValidationPanel()
            tabbedPane.addTab("Validation Results (${testData.validationMessages.size})", validationPanel)
        }
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        // Left side - test info
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.isOpaque = false
        
        val nameLabel = JBLabel("Test: ${testData.testName}")
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 14f)
        infoPanel.add(nameLabel)
        
        val scenarioLabel = JBLabel("Scenario: ${testData.scenarioName}")
        scenarioLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(scenarioLabel)
        
        val statusLabel = JBLabel("Status: ${testData.getStatusIcon()} ${testData.validationStatus}")
        infoPanel.add(statusLabel)
        
        val linesLabel = JBLabel("Lines of code: ${testData.lineCount}")
        linesLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(linesLabel)
        
        panel.add(infoPanel, BorderLayout.WEST)
        
        // Right side - actions
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        actionsPanel.isOpaque = false
        
        val copyButton = JButton("Copy to Clipboard")
        copyButton.addActionListener { copyToClipboard() }
        actionsPanel.add(copyButton)
        
        panel.add(actionsPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createCodePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create editor for syntax highlighting
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(testData.testCode)
        editor = editorFactory.createViewer(document, project) as EditorEx
        
        // Configure editor
        editor?.let { ed ->
            ed.settings.apply {
                isLineNumbersShown = true
                isWhitespacesShown = false
                isLineMarkerAreaShown = true
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
            ed.highlighter = highlighter
            
            // Set read-only
            ed.isViewer = true
            
            panel.add(ed.component, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createValidationPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // Summary at top
        val summaryPanel = JPanel(BorderLayout())
        summaryPanel.border = EmptyBorder(0, 0, 10, 0)
        
        val summaryLabel = JBLabel(testData.getValidationSummary())
        summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD, 14f)
        summaryLabel.foreground = when (testData.validationStatus) {
            GeneratedTestDisplayData.ValidationStatus.PASSED -> Color(0, 128, 0)
            GeneratedTestDisplayData.ValidationStatus.WARNINGS -> Color(255, 140, 0)
            GeneratedTestDisplayData.ValidationStatus.FAILED -> Color(255, 0, 0)
            else -> UIUtil.getLabelForeground()
        }
        summaryPanel.add(summaryLabel, BorderLayout.WEST)
        
        panel.add(summaryPanel, BorderLayout.NORTH)
        
        // Messages list
        val messagesPanel = JPanel()
        messagesPanel.layout = BoxLayout(messagesPanel, BoxLayout.Y_AXIS)
        
        // Group messages by type
        val errors = testData.validationMessages.filter { 
            it.type == GeneratedTestDisplayData.ValidationMessage.MessageType.ERROR 
        }
        val warnings = testData.validationMessages.filter { 
            it.type == GeneratedTestDisplayData.ValidationMessage.MessageType.WARNING 
        }
        val infos = testData.validationMessages.filter { 
            it.type == GeneratedTestDisplayData.ValidationMessage.MessageType.INFO 
        }
        
        // Add errors
        if (errors.isNotEmpty()) {
            addMessageSection(messagesPanel, "Errors", errors, Color(255, 0, 0))
        }
        
        // Add warnings
        if (warnings.isNotEmpty()) {
            addMessageSection(messagesPanel, "Warnings", warnings, Color(255, 140, 0))
        }
        
        // Add info messages
        if (infos.isNotEmpty()) {
            addMessageSection(messagesPanel, "Information", infos, UIUtil.getContextHelpForeground())
        }
        
        val scrollPane = JBScrollPane(messagesPanel)
        scrollPane.border = BorderFactory.createEmptyBorder()
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun addMessageSection(
        parent: JPanel,
        title: String,
        messages: List<GeneratedTestDisplayData.ValidationMessage>,
        color: Color
    ) {
        if (parent.componentCount > 0) {
            parent.add(Box.createVerticalStrut(15))
        }
        
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.foreground = color
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        parent.add(titleLabel)
        
        parent.add(Box.createVerticalStrut(5))
        
        messages.forEach { message ->
            val messagePanel = JPanel(BorderLayout())
            messagePanel.alignmentX = Component.LEFT_ALIGNMENT
            messagePanel.border = EmptyBorder(2, 20, 2, 0)
            messagePanel.maximumSize = Dimension(Integer.MAX_VALUE, 30)
            
            val iconAndMessage = buildString {
                append(message.getIcon())
                append(" ")
                if (message.line != null) {
                    append("[Line ${message.line}] ")
                }
                append(message.message)
            }
            
            val messageLabel = JBLabel(iconAndMessage)
            messageLabel.foreground = UIUtil.getLabelForeground()
            messagePanel.add(messageLabel, BorderLayout.WEST)
            
            parent.add(messagePanel)
        }
    }
    
    private fun copyToClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val content = buildString {
            appendLine("// Test: ${testData.testName}")
            appendLine("// Scenario: ${testData.scenarioName}")
            appendLine("// Generated: ${java.time.Instant.ofEpochMilli(testData.timestamp)}")
            appendLine()
            append(testData.testCode)
        }
        clipboard.setContents(StringSelection(content), null)
        
        JOptionPane.showMessageDialog(
            contentPane,
            "Test code copied to clipboard",
            "Success",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    
    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        super.dispose()
    }
}