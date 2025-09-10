package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
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
                if (mergedTest.hasInferredPath()) {
                    appendLine("Path source: AI-inferred from project structure analysis")
                } else {
                    appendLine("Path source: Convention-based fallback")
                }
                appendLine("Methods: ${mergedTest.methodCount} test methods")
                appendLine("Framework: ${mergedTest.framework}")
            }
            
            // Open the saved file in IntelliJ editor instead of showing message
            openFileInEditor(filePath)
            
            // Close the dialog after successful write and opening file
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
     * Find the best test source root from project modules
     */
    private fun findBestTestSourceRoot(): String {
        // Try to find test roots from project modules
        val moduleManager = ModuleManager.getInstance(project)
        for (module in moduleManager.modules) {
            val rootManager = ModuleRootManager.getInstance(module)
            // Get test source roots (true = test roots only)
            val testRoots = rootManager.getSourceRoots(true)
            for (testRoot in testRoots) {
                if (testRoot.path.contains("test")) {
                    return testRoot.path
                }
            }
        }
        
        // Fallback to conventional paths
        val basePath = project.basePath ?: return "src/test/java"
        
        // Check common test directories
        return when {
            java.io.File("$basePath/src/test/java").exists() -> "$basePath/src/test/java"
            java.io.File("$basePath/src/test/kotlin").exists() -> "$basePath/src/test/kotlin"
            java.io.File("$basePath/test/java").exists() -> "$basePath/test/java"
            java.io.File("$basePath/test").exists() -> "$basePath/test"
            else -> "$basePath/src/test/java" // Default to standard Maven/Gradle structure
        }
    }
    
    /**
     * Write a merged test class to file using AI-inferred path or fallback to standard structure
     */
    private fun writeMergedTestToFile(mergedTest: MergedTestClass): String {
        val targetFile: java.io.File
        
        if (mergedTest.hasInferredPath()) {
            // Use AI-inferred path from merger agent
            val inferredPath = mergedTest.fullFilePath!!
            targetFile = java.io.File(inferredPath)
            
            // Ensure parent directories exist
            val parentDir = targetFile.parentFile
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            
        } else {
            // Use proper test source root detection
            val testSourceRoot = findBestTestSourceRoot()
            val testDir = java.io.File(testSourceRoot)
            
            // Create test directory if it doesn't exist
            if (!testDir.exists()) {
                testDir.mkdirs()
            }
            
            // Create package directories
            val packagePath = mergedTest.packageName.replace('.', java.io.File.separatorChar)
            val packageDir = java.io.File(testDir, packagePath)
            if (!packageDir.exists()) {
                packageDir.mkdirs()
            }
            
            targetFile = java.io.File(packageDir, mergedTest.fileName)
        }
        
        // Write the test file
        targetFile.writeText(mergedTest.fullContent)
        
        return targetFile.absolutePath
    }
    
    /**
     * Open the saved test file in IntelliJ's editor
     */
    private fun openFileInEditor(filePath: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile != null) {
                    // Refresh the file system to ensure the file is recognized
                    virtualFile.refresh(false, false)
                    
                    // Open the file in editor
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
                } else {
                    // If file not found, show error
                    Messages.showErrorDialog(
                        project,
                        "Could not find the saved file: $filePath",
                        "File Not Found"
                    )
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Failed to open file in editor: ${e.message}",
                    "Open Error"
                )
            }
        }
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