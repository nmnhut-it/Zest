package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.model.MergedTestClass
import com.zps.zest.testgen.model.TestGenerationSession
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for previewing the final merged test class with syntax highlighting.
 * Shows the complete generated test file and provides actions to save or copy.
 */
class MergedTestPreviewDialog(
    private val project: Project,
    private val session: TestGenerationSession
) : DialogWrapper(project) {
    
    private var editor: EditorEx? = null
    private val mergedTest: MergedTestClass? = session.mergedTestClass
    
    init {
        title = "Generated Test Preview: ${mergedTest?.className ?: "Unknown"}"
        init()
    }
    
    override fun createCenterPanel(): JComponent? {
        if (mergedTest == null) {
            return JBLabel("No merged test available").apply {
                preferredSize = JBUI.size(600, 400)
                horizontalAlignment = SwingConstants.CENTER
            }
        }
        
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(1000, 700)
        
        // Header with test info
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Code editor panel
        val codePanel = createCodePanel()
        mainPanel.add(codePanel, BorderLayout.CENTER)
        
        // Actions panel
        val actionsPanel = createActionsPanel()
        mainPanel.add(actionsPanel, BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(15, 15, 15, 15)
        panel.background = UIUtil.getPanelBackground()
        
        // Left side - test info
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.isOpaque = false
        
        val classLabel = JBLabel("Test Class: ${mergedTest!!.className}")
        classLabel.font = classLabel.font.deriveFont(Font.BOLD, 16f)
        infoPanel.add(classLabel)
        
        val packageLabel = JBLabel("Package: ${mergedTest.packageName}")
        packageLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(packageLabel)
        
        val methodsLabel = JBLabel("Test Methods: ${mergedTest.methodCount}")
        methodsLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(methodsLabel)
        
        val frameworkLabel = JBLabel("Framework: ${mergedTest.framework}")
        frameworkLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(frameworkLabel)
        
        val pathLabel = JBLabel("File: ${mergedTest.fileName}")
        pathLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(pathLabel)
        
        panel.add(infoPanel, BorderLayout.WEST)
        
        // Right side - quick actions
        val quickActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        quickActionsPanel.isOpaque = false
        
        val copyButton = JButton("Copy to Clipboard")
        copyButton.addActionListener { copyToClipboard() }
        quickActionsPanel.add(copyButton)
        
        panel.add(quickActionsPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createCodePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(0, 15, 0, 15)
        
        // Create editor for syntax highlighting
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(mergedTest!!.fullContent)
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
                additionalLinesCount = 3
                additionalColumnsCount = 3
            }
            
            // Set Java/Kotlin syntax highlighting based on file extension
            val fileType = if (mergedTest.fileName.endsWith(".kt")) {
                FileTypeManager.getInstance().getFileTypeByExtension("kt")
            } else {
                FileTypeManager.getInstance().getFileTypeByExtension("java")
            }
            
            val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                project, fileType
            )
            ed.highlighter = highlighter
            
            // Set read-only
            ed.isViewer = true
            
            panel.add(ed.component, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createActionsPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 15))
        panel.background = UIUtil.getPanelBackground()
        
        // Write to File button (primary action)
        val writeButton = JButton("Write to File")
        writeButton.font = writeButton.font.deriveFont(Font.BOLD)
        writeButton.preferredSize = JBUI.size(120, 35)
        writeButton.addActionListener { writeToFile() }
        panel.add(writeButton)
        
        // Copy to Clipboard button
        val copyButton = JButton("Copy to Clipboard")
        copyButton.preferredSize = JBUI.size(140, 35)
        copyButton.addActionListener { copyToClipboard() }
        panel.add(copyButton)
        
        return panel
    }
    
    private fun writeToFile() {
        try {
            val filePath = writeMergedTestToFile(mergedTest!!)
            
            val message = buildString {
                appendLine("Test file written successfully!")
                appendLine()
                appendLine("File: ${mergedTest.fileName}")
                appendLine("Location: $filePath")
                appendLine("Methods: ${mergedTest.methodCount} test methods")
                appendLine("Framework: ${mergedTest.framework}")
            }
            
            Messages.showInfoMessage(
                project,
                message,
                "Test Generation Complete"
            )
            
            // Close the dialog after successful write
            doCancelAction()
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to write test file:\n${e.message}",
                "Write Error"
            )
        }
    }
    
    /**
     * Write a merged test class to file using standard Maven/Gradle structure
     */
    private fun writeMergedTestToFile(mergedTest: MergedTestClass): String {
        // Use standard Maven/Gradle test directory structure
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        
        // Try standard test source root first
        var testSourceRoot = "$basePath/src/test/java"
        var testDir = java.io.File(testSourceRoot)
        
        if (!testDir.exists()) {
            // Fallback to simple test directory
            testSourceRoot = "$basePath/test"
            testDir = java.io.File(testSourceRoot)
            if (!testDir.exists()) {
                testDir.mkdirs()
            }
        }
        
        // Create package directories
        val packagePath = mergedTest.packageName.replace('.', java.io.File.separatorChar)
        val packageDir = java.io.File(testDir, packagePath)
        if (!packageDir.exists()) {
            packageDir.mkdirs()
        }
        
        // Write the test file
        val testFile = java.io.File(packageDir, mergedTest.fileName)
        testFile.writeText(mergedTest.fullContent)
        
        return testFile.absolutePath
    }
    
    private fun copyToClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val content = buildString {
            appendLine("// Generated Test Class: ${mergedTest!!.className}")
            appendLine("// Package: ${mergedTest.packageName}")
            appendLine("// Test Methods: ${mergedTest.methodCount}")
            appendLine("// Framework: ${mergedTest.framework}")
            appendLine("// Generated: ${java.time.Instant.now()}")
            appendLine()
            append(mergedTest.fullContent)
        }
        clipboard.setContents(StringSelection(content), null)
        
        // Show brief confirmation
        val parentComponent = contentPane
        val message = JLabel("Test code copied to clipboard!")
        message.horizontalAlignment = SwingConstants.CENTER
        
        JOptionPane.showMessageDialog(
            parentComponent,
            message,
            "Copied",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    override fun createActions(): Array<Action> {
        // Only show Close button - main actions are in the custom panel
        return arrayOf(cancelAction.apply { 
            putValue(Action.NAME, "Close")
        })
    }
    
    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        super.dispose()
    }
}