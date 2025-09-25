package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.testgen.agents.AITestMergerAgent
import com.zps.zest.testgen.model.MergedTestClass
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*

/**
 * Simplified panel that shows side-by-side comparison of existing and merged test classes.
 * Provides direct action to write the merged test to file.
 */
class TestMergingPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val statusLabel = JBLabel("No merged test class yet")
    private var existingCodeEditor: EditorEx? = null
    private var mergedCodeEditor: EditorEx? = null
    private var mergerAgent: AITestMergerAgent? = null

    // Current merged test class
    private var currentMergedClass: MergedTestClass? = null
    private var existingTestCode: String? = null
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        background = UIUtil.getPanelBackground()

        // Header with status
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // Main content - side by side comparison (back to original layout)
        val comparisonPanel = createComparisonPanel()
        add(comparisonPanel, BorderLayout.CENTER)

        // Bottom action panel
        val actionPanel = createActionPanel()
        add(actionPanel, BorderLayout.SOUTH)
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("📝 Test Merge Result")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.font = statusLabel.font.deriveFont(14f)
        panel.add(statusLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createComparisonPanel(): JComponent {
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.resizeWeight = 0.5
        splitPane.dividerSize = 10
        
        // Left side - existing test
        val existingPanel = createCodePanel("📂 Existing Test", true)
        splitPane.leftComponent = existingPanel
        
        // Right side - merged test
        val mergedPanel = createCodePanel("✨ Merged Test", false)
        splitPane.rightComponent = mergedPanel
        
        return splitPane
    }
    
    private fun createCodePanel(title: String, isExisting: Boolean): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)
        
        // Panel header
        val headerLabel = JBLabel(title)
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 12f)
        headerLabel.border = JBUI.Borders.emptyBottom(5)
        panel.add(headerLabel, BorderLayout.NORTH)
        
        // Create editor
        val editorFactory = EditorFactory.getInstance()
        val initialText = if (isExisting) {
            "// No existing test class found\n// New test will be created"
        } else {
            "// Merged test will appear here after generation"
        }
        
        val document = editorFactory.createDocument(initialText)
        val editor = editorFactory.createViewer(document, project) as EditorEx
        
        // Configure editor
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = true
            isUseSoftWraps = false
            additionalLinesCount = 2
        }
        
        // Set Java syntax highlighting
        val javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project, javaFileType
        )
        editor.highlighter = highlighter
        editor.isViewer = true
        
        // Store reference
        if (isExisting) {
            existingCodeEditor = editor
        } else {
            mergedCodeEditor = editor
        }
        
        // Add to panel with border
        val editorWrapper = JPanel(BorderLayout())
        editorWrapper.border = BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        editorWrapper.add(editor.component, BorderLayout.CENTER)
        panel.add(editorWrapper, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createActionPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 10))
        panel.border = JBUI.Borders.empty(10)
        panel.background = UIUtil.getPanelBackground()

        // Primary action - Write to File
        val writeButton = JButton("💾 Write Merged Test to File")
        writeButton.font = writeButton.font.deriveFont(Font.BOLD, 14f)
        writeButton.preferredSize = JBUI.size(250, 40)
        writeButton.addActionListener { writeMergedTestToFile() }
        writeButton.toolTipText = "Save the merged test class to your project"
        panel.add(writeButton)

        // View AI Chat Memory
        val chatMemoryButton = JButton("💬 View AI Chat")
        chatMemoryButton.preferredSize = JBUI.size(150, 40)
        chatMemoryButton.addActionListener { showChatMemoryDialog() }
        chatMemoryButton.toolTipText = "View AI conversation and validation process"
        panel.add(chatMemoryButton)

        // Copy to Clipboard
        val copyButton = JButton("📋 Copy to Clipboard")
        copyButton.preferredSize = JBUI.size(180, 40)
        copyButton.addActionListener { copyMergedClassToClipboard() }
        copyButton.toolTipText = "Copy the merged test code to clipboard"
        panel.add(copyButton)

        return panel
    }
    
    /**
     * Show chat memory dialog for the merger agent
     */
    private fun showChatMemoryDialog() {
        println("DEBUG: showChatMemoryDialog called - mergerAgent: $mergerAgent")

        if (mergerAgent == null) {
            println("DEBUG: mergerAgent is null")
            Messages.showWarningDialog(
                project,
                "No AI chat history available yet.\nChat history will be available after merging starts.",
                "No Chat History"
            )
            return
        }

        val chatMemory = mergerAgent?.chatMemory
        println("DEBUG: chatMemory: $chatMemory")

        if (chatMemory != null) {
            val messages = chatMemory.messages()
            println("DEBUG: Chat memory has ${messages.size} messages")

            val dialog = ChatMemoryDialog(project, chatMemory, "Test Merger AI")
            dialog.show()
        } else {
            println("DEBUG: Chat memory is null")
            Messages.showWarningDialog(
                project,
                "Chat memory is not available for this merging session.\n" +
                "This might happen if the AI agent hasn't started the conversation yet.\n" +
                "Try running the merge again or check the logs for errors.",
                "No Chat Memory"
            )
        }
    }

    /**
     * Update the display with existing and merged test classes
     * @param mergedClass The merged test result
     * @param existingCode Existing test code if any
     * @param agent The merger agent for chat memory (optional)
     */
    fun updateMergedClass(mergedClass: MergedTestClass, existingCode: String? = null, agent: AITestMergerAgent? = null) {
        println("DEBUG: updateMergedClass called - agent: $agent")

        SwingUtilities.invokeLater {
            currentMergedClass = mergedClass
            existingTestCode = existingCode

            // Store the merger agent for chat memory access
            agent?.let {
                println("DEBUG: Setting mergerAgent to: $it")
                println("DEBUG: Agent chat memory: ${it.chatMemory}")
                this.mergerAgent = it
            } ?: run {
                println("DEBUG: No agent provided to updateMergedClass")
            }

            // Update existing code editor
            existingCodeEditor?.let { editor ->
                ApplicationManager.getApplication().runWriteAction {
                    val text = existingCode ?: "// No existing test class found\n// New test will be created from scratch"
                    editor.document.setText(text)
                }
            }

            // Update merged code editor
            mergedCodeEditor?.let { editor ->
                ApplicationManager.getApplication().runWriteAction {
                    editor.document.setText(mergedClass.fullContent)
                }
            }

            // Update status with validation indicator
            val validationStatus = when {
                mergedClass.fullContent.contains("VALIDATION_PASSED") -> " ✅ Validated"
                mergedClass.fullContent.contains("VALIDATION_FAILED") -> " ⚠️ Has issues"
                else -> ""
            }

            val statusText = if (existingCode != null) {
                "Merged: ${mergedClass.className} (${mergedClass.methodCount} methods)$validationStatus"
            } else {
                "New: ${mergedClass.className} (${mergedClass.methodCount} methods)$validationStatus"
            }
            statusLabel.text = statusText
        }
    }
    
    /**
     * Clear display
     */
    fun clear() {
        SwingUtilities.invokeLater {
            currentMergedClass = null
            existingTestCode = null
            
            existingCodeEditor?.let { editor ->
                ApplicationManager.getApplication().runWriteAction {
                    editor.document.setText("// No existing test class found\n// New test will be created")
                }
            }
            
            mergedCodeEditor?.let { editor ->
                ApplicationManager.getApplication().runWriteAction {
                    editor.document.setText("// Merged test will appear here after generation")
                }
            }
            
            statusLabel.text = "No merged test class yet"
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
            File("$basePath/src/test/java").exists() -> "$basePath/src/test/java"
            File("$basePath/src/test/kotlin").exists() -> "$basePath/src/test/kotlin"
            File("$basePath/test/java").exists() -> "$basePath/test/java"
            File("$basePath/test").exists() -> "$basePath/test"
            else -> "$basePath/src/test/java" // Default to standard Maven/Gradle structure
        }
    }
    
    /**
     * Write merged test to file - extracted from MergedTestPreviewDialog
     */
    private fun writeMergedTestToFile() {
        val mergedTest = currentMergedClass
        if (mergedTest == null) {
            Messages.showWarningDialog(
                project,
                "No merged test class available to save",
                "No Test Available"
            )
            return
        }
        
        try {
            val targetFile: File
            
            if (mergedTest.hasInferredPath()) {
                // Use AI-inferred path from merger agent
                val inferredPath = mergedTest.fullFilePath!!
                targetFile = File(inferredPath)
                
                // Ensure parent directories exist
                val parentDir = targetFile.parentFile
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
            } else {
                // Use proper test source root detection
                val testSourceRoot = findBestTestSourceRoot()
                val testDir = File(testSourceRoot)
                
                // Create test directory if it doesn't exist
                if (!testDir.exists()) {
                    testDir.mkdirs()
                }
                
                // Create package directories
                val packagePath = mergedTest.packageName.replace('.', File.separatorChar)
                val packageDir = File(testDir, packagePath)
                if (!packageDir.exists()) {
                    packageDir.mkdirs()
                }
                
                targetFile = File(packageDir, mergedTest.fileName)
            }
            
            // Write the test file
            targetFile.writeText(mergedTest.fullContent)
            
            // Open the saved file in IntelliJ editor
            openFileInEditor(targetFile.absolutePath)
            
            // Show success notification
            statusLabel.text = "✅ Saved: ${targetFile.name}"
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to write test file:\n${e.message}",
                "Write Error"
            )
        }
    }
    
    /**
     * Open the saved test file in IntelliJ's editor
     */
    private fun openFileInEditor(filePath: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile != null) {
                    virtualFile.refresh(false, false)
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } catch (e: Exception) {
                // Silently fail - file was saved successfully even if we can't open it
            }
        }
    }
    
    private fun copyMergedClassToClipboard() {
        currentMergedClass?.let { mergedClass ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(mergedClass.fullContent), null)
            
            // Update status to show success
            statusLabel.text = "✅ Copied to clipboard"
            
            // Reset status after 3 seconds
            Timer(3000) {
                SwingUtilities.invokeLater {
                    currentMergedClass?.let {
                        statusLabel.text = "Merged: ${it.className} (${it.methodCount} methods)"
                    }
                }
            }.apply {
                isRepeats = false
                start()
            }
        } ?: run {
            Messages.showWarningDialog(
                project,
                "No merged test class available to copy",
                "No Test Available"
            )
        }
    }
    
    /**
     * Dispose of editor resources
     */
    fun dispose() {
        existingCodeEditor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            existingCodeEditor = null
        }
        mergedCodeEditor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            mergedCodeEditor = null
        }
        mergerAgent = null
    }
}