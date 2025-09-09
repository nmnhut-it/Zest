package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.model.ContextDisplayData
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for viewing full analysis of a context file.
 * Provides syntax-highlighted view with tabs for different aspects of the analysis.
 */
class ContextAnalysisDialog(
    private val project: Project,
    private val data: ContextDisplayData
) : DialogWrapper(project) {
    
    private val disposables = mutableListOf<() -> Unit>()
    
    init {
        title = "Analysis: ${data.fileName}"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(800, 600)
        
        // Header with file info
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Tabbed pane with different views
        val tabbedPane = JBTabbedPane()
        
        // Full Analysis tab - always show, with better empty content handling
        val analysisPanel = if (!data.fullAnalysis.isNullOrBlank()) {
            createAnalysisPanel(data.fullAnalysis)
        } else {
            createEmptyAnalysisPanel()
        }
        tabbedPane.addTab("Full Analysis", analysisPanel)
        
        // Classes tab
        if (data.classes.isNotEmpty()) {
            val classesPanel = createListPanel("Classes Found", data.classes)
            tabbedPane.addTab("Classes (${data.classes.size})", classesPanel)
        }
        
        // Methods tab
        if (data.methods.isNotEmpty()) {
            val methodsPanel = createListPanel("Methods Found", data.methods)
            tabbedPane.addTab("Methods (${data.methods.size})", methodsPanel)
        }
        
        // Dependencies tab
        if (data.dependencies.isNotEmpty()) {
            val depsPanel = createListPanel("Dependencies", data.dependencies)
            tabbedPane.addTab("Dependencies (${data.dependencies.size})", depsPanel)
        }
        
        // Summary tab
        val summaryPanel = createSummaryPanel()
        tabbedPane.addTab("Summary", summaryPanel)
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        panel.background = UIUtil.getPanelBackground()
        
        // File path and status
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.isOpaque = false
        
        val pathLabel = JBLabel("File: ${data.filePath}")
        pathLabel.font = pathLabel.font.deriveFont(Font.BOLD)
        infoPanel.add(pathLabel)
        
        val statusLabel = JBLabel("Status: ${data.getStatusIcon()} ${data.getStatusText()}")
        statusLabel.foreground = when (data.status) {
            ContextDisplayData.AnalysisStatus.COMPLETED -> UIUtil.getContextHelpForeground()
            ContextDisplayData.AnalysisStatus.ERROR -> JBUI.CurrentTheme.NotificationError.foregroundColor()
            ContextDisplayData.AnalysisStatus.ANALYZING -> JBUI.CurrentTheme.NotificationInfo.foregroundColor()
            else -> UIUtil.getContextHelpForeground()
        }
        infoPanel.add(statusLabel)
        
        // Add content availability indicator
        val contentStatusLabel = if (!data.fullAnalysis.isNullOrBlank()) {
            JBLabel("Content: ✅ Available (${data.fullAnalysis.length} characters)")
        } else {
            JBLabel("Content: ⚠️ No analysis content available")
        }
        contentStatusLabel.foreground = UIUtil.getContextHelpForeground()
        contentStatusLabel.font = contentStatusLabel.font.deriveFont(11f)
        infoPanel.add(contentStatusLabel)
        
        if (!data.summary.isBlank()) {
            val summaryLabel = JBLabel("Summary: ${data.summary}")
            summaryLabel.foreground = UIUtil.getContextHelpForeground()
            infoPanel.add(summaryLabel)
        }
        
        panel.add(infoPanel, BorderLayout.WEST)
        
        // Copy button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.isOpaque = false
        
        val copyButton = JButton("Copy Analysis")
        copyButton.addActionListener {
            copyToClipboard()
        }
        buttonPanel.add(copyButton)
        
        panel.add(buttonPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createAnalysisPanel(analysis: String): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create editor for syntax highlighting
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(analysis)
        val editor = editorFactory.createViewer(document, project) as EditorEx
        
        // Set up editor settings
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = true
            isUseSoftWraps = true
        }
        
        // Set read-only
        editor.isViewer = true
        
        panel.add(editor.component, BorderLayout.CENTER)
        
        // Remember to release editor when dialog is disposed
        disposables.add {
            editorFactory.releaseEditor(editor)
        }
        
        return panel
    }
    
    private fun createEmptyAnalysisPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(20, 20, 20, 20)
        
        // Empty state content
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false
        
        // Empty state icon and message
        val emptyIcon = JBLabel("📋", SwingConstants.CENTER)
        emptyIcon.font = emptyIcon.font.deriveFont(48f)
        emptyIcon.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(emptyIcon)
        
        contentPanel.add(Box.createVerticalStrut(20))
        
        val messageLabel = JBLabel("No Analysis Content Available", SwingConstants.CENTER)
        messageLabel.font = messageLabel.font.deriveFont(Font.BOLD, 16f)
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(messageLabel)
        
        contentPanel.add(Box.createVerticalStrut(10))
        
        val explanationPanel = JPanel()
        explanationPanel.layout = BoxLayout(explanationPanel, BoxLayout.Y_AXIS)
        explanationPanel.isOpaque = false
        
        val reasonLabel = JBLabel("This may occur when:", SwingConstants.CENTER)
        reasonLabel.foreground = UIUtil.getContextHelpForeground()
        reasonLabel.alignmentX = Component.CENTER_ALIGNMENT
        explanationPanel.add(reasonLabel)
        
        val reasons = listOf(
            "• The file is empty or contains only whitespace",
            "• The analysis tool encountered an error",
            "• The file content could not be processed",
            "• The file is a binary or unsupported format"
        )
        
        reasons.forEach { reason ->
            val reasonItem = JBLabel(reason, SwingConstants.CENTER)
            reasonItem.foreground = UIUtil.getContextHelpForeground()
            reasonItem.font = reasonItem.font.deriveFont(12f)
            reasonItem.alignmentX = Component.CENTER_ALIGNMENT
            explanationPanel.add(reasonItem)
        }
        
        contentPanel.add(explanationPanel)
        
        // Center the content
        panel.add(contentPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createListPanel(title: String, items: List<String>): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // Title
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, BorderLayout.NORTH)
        
        // List
        val listModel = DefaultListModel<String>()
        items.sorted().forEach { listModel.addElement(it) }
        
        val list = JList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 20
        
        val scrollPane = JBScrollPane(list)
        scrollPane.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Count label
        val countLabel = JBLabel("Total: ${items.size}")
        countLabel.foreground = UIUtil.getContextHelpForeground()
        countLabel.border = EmptyBorder(5, 0, 0, 0)
        panel.add(countLabel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createSummaryPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        val summaryText = buildString {
            appendLine("File Analysis Summary")
            appendLine("=" .repeat(50))
            appendLine()
            appendLine("File: ${data.fileName}")
            appendLine("Path: ${data.filePath}")
            appendLine("Status: ${data.getStatusText()}")
            appendLine("Analysis Time: ${java.time.Instant.ofEpochMilli(data.timestamp)}")
            appendLine()
            appendLine("Content Summary:")
            appendLine("- Classes: ${data.classes.size}")
            if (data.classes.isNotEmpty()) {
                data.classes.take(5).forEach { 
                    appendLine("  • $it")
                }
                if (data.classes.size > 5) {
                    appendLine("  ... and ${data.classes.size - 5} more")
                }
            }
            appendLine()
            appendLine("- Methods: ${data.methods.size}")
            if (data.methods.isNotEmpty()) {
                data.methods.take(5).forEach { 
                    appendLine("  • $it")
                }
                if (data.methods.size > 5) {
                    appendLine("  ... and ${data.methods.size - 5} more")
                }
            }
            appendLine()
            appendLine("- Dependencies: ${data.dependencies.size}")
            if (data.dependencies.isNotEmpty()) {
                data.dependencies.take(5).forEach { 
                    appendLine("  • $it")
                }
                if (data.dependencies.size > 5) {
                    appendLine("  ... and ${data.dependencies.size - 5} more")
                }
            }
        }
        
        val textArea = JTextArea(summaryText)
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        textArea.background = UIUtil.getPanelBackground()
        
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun copyToClipboard() {
        val content = buildString {
            appendLine("Analysis for: ${data.filePath}")
            appendLine("=" .repeat(60))
            if (!data.fullAnalysis.isNullOrBlank()) {
                appendLine(data.fullAnalysis)
            } else {
                appendLine("No analysis available")
            }
        }
        
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(content), null)
        
        // Show notification
        JOptionPane.showMessageDialog(
            contentPane,
            "Analysis copied to clipboard",
            "Success",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    
    override fun dispose() {
        disposables.forEach { it() }
        super.dispose()
    }
}