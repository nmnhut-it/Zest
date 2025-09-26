package com.zps.zest.testgen.ui.panels

import com.zps.zest.langchain4j.ui.DialogManager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.PsiShortNamesCache
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
import javax.swing.Timer

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
        val chatMemoryButton = JButton("💬 Merger Chat")
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
            DialogManager.showDialog(dialog)
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
     * Set the merger agent immediately when it's created (before merging starts)
     */
    fun setMergerAgent(agent: AITestMergerAgent) {
        SwingUtilities.invokeLater {
            this.mergerAgent = agent
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
     * Immediately update test code display when it's set (live update)
     * @param className The test class name
     * @param testCode The test code to display
     * @param isExisting Whether this is existing test code or new test code
     */
    fun updateTestCodeImmediately(className: String, testCode: String, isExisting: Boolean) {
        SwingUtilities.invokeLater {
            val editor = if (isExisting) existingCodeEditor else mergedCodeEditor
            editor?.let {
                ApplicationManager.getApplication().runWriteAction {
                    it.document.setText(testCode)
                }
            }

            // Update status to show immediate feedback
            if (!isExisting) {
                statusLabel.text = "Working on: $className"
            }
        }
    }

    /**
     * Show a fix being applied with optional line highlighting
     * @param oldText The text being replaced
     * @param newText The replacement text
     * @param lineNumber Optional line number where the fix is applied
     */
    fun showFixApplied(oldText: String, newText: String, lineNumber: Int?) {
        SwingUtilities.invokeLater {
            // Update status to show fix progress
            val fixInfo = lineNumber?.let { "line $it" } ?: "text replacement"
            statusLabel.text = "🔧 Applying fix at $fixInfo..."

            // If we have a line number, try to highlight it temporarily
            lineNumber?.let { line ->
                mergedCodeEditor?.let { editor ->
                    try {
                        val document = editor.document
                        if (line > 0 && line <= document.lineCount) {
                            val startOffset = document.getLineStartOffset(line - 1)
                            val endOffset = document.getLineEndOffset(line - 1)

                            // Scroll to the line being fixed
                            editor.scrollingModel.scrollTo(editor.offsetToLogicalPosition(startOffset), ScrollType.CENTER)

                            // Flash the line briefly with a highlight
                            val highlighter = editor.markupModel.addLineHighlighter(
                                EditorColors.SEARCH_RESULT_ATTRIBUTES,
                                line - 1,
                                HighlighterLayer.SELECTION
                            )

                            // Remove highlight after a short delay
                            Timer(500) {
                                SwingUtilities.invokeLater {
                                    editor.markupModel.removeHighlighter(highlighter)
                                }
                            }.apply {
                                isRepeats = false
                                start()
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore highlighting errors
                    }
                }
            }
        }
    }

    /**
     * Update validation status display
     * @param status The validation status (VALIDATION_PASSED, VALIDATION_FAILED, etc.)
     * @param issues Optional list of validation issues
     */
    fun updateValidationStatus(status: String, issues: List<String>?) {
        SwingUtilities.invokeLater {
            when (status) {
                "VALIDATION_PASSED" -> {
                    statusLabel.text = "✅ Validation passed"
                    statusLabel.foreground = Color(0, 128, 0)
                }
                "VALIDATION_FAILED" -> {
                    val issueCount = issues?.size ?: 0
                    statusLabel.text = "⚠️ Validation found $issueCount issue${if (issueCount != 1) "s" else ""}"
                    statusLabel.foreground = Color(255, 140, 0)
                }
                else -> {
                    statusLabel.text = status
                    statusLabel.foreground = UIUtil.getContextHelpForeground()
                }
            }
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
     * Write merged test to file using IntelliJ's VFS and Document API
     */
    private fun writeMergedTestToFile() {
        val mergedTest = currentMergedClass ?: run {
            Messages.showWarningDialog(project, "No merged test class available to save", "No Test Available")
            return
        }

        object : com.intellij.openapi.progress.Task.Backgroundable(project, "Saving Test File", false) {
            private var savedFile: VirtualFile? = null

            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.text = "Writing test file..."

                ApplicationManager.getApplication().runWriteAction {
                    savedFile = createOrUpdateTestFile(mergedTest)
                }
            }

            override fun onSuccess() {
                statusLabel.text = "✅ Saved: ${mergedTest.fileName}"
                savedFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, "Failed to write test file:\n${error.message}", "Write Error")
            }
        }.queue()
    }

    /**
     * Create or update the test file with the merged content
     */
    private fun createOrUpdateTestFile(mergedTest: MergedTestClass): VirtualFile? {
        val targetDir = findOrCreateTargetDirectory(mergedTest) ?: return null

        // Create or find the file
        var file = targetDir.findChild(mergedTest.fileName)
        if (file == null) {
            file = targetDir.createChildData(this, mergedTest.fileName)
        }

        // Write content using Document API
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            document.setText(mergedTest.fullContent)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document)
        } else {
            file.setBinaryContent(mergedTest.fullContent.toByteArray(Charsets.UTF_8))
        }

        return file
    }

    /**
     * Find or create the target directory for the test file
     */
    private fun findOrCreateTargetDirectory(mergedTest: MergedTestClass): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()

        // Determine the directory path (without filename)
        val dirPath = if (mergedTest.hasInferredPath()) {
            // Extract directory from full file path
            val fullPath = mergedTest.fullFilePath!!
            fullPath.substringBeforeLast(File.separator)
        } else {
            // Build path from test root and package
            val testRoot = findBestTestSourceRoot()
            if (mergedTest.packageName.isNotEmpty()) {
                val packagePath = mergedTest.packageName.replace('.', File.separatorChar)
                "$testRoot${File.separator}$packagePath"
            } else {
                testRoot
            }
        }

        // Find existing directory or create it
        var targetDir = fileSystem.findFileByPath(dirPath)
        if (targetDir == null) {
            // Need to create the directory structure
            val parts = dirPath.replace('\\', '/').split('/')
            var currentPath = ""
            var currentDir: VirtualFile? = null

            for (part in parts) {
                if (part.isEmpty()) continue

                currentPath += if (currentPath.isEmpty()) part else "/$part"
                var dir = fileSystem.findFileByPath(currentPath)

                if (dir == null && currentDir != null) {
                    // Create this directory under the parent
                    dir = currentDir.createChildDirectory(this, part)
                } else if (dir == null) {
                    // Try with drive letter on Windows
                    dir = fileSystem.findFileByPath("$currentPath/")
                }

                currentDir = dir
            }

            targetDir = currentDir
        }

        return targetDir
    }
    
    /**
     * Open the saved test file in IntelliJ's editor
     */
    private fun openFileInEditor(virtualFile: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            try {
                if (virtualFile != null) {
                    virtualFile.refresh(true, false, {
                        FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    });
                }
            } catch (e: Exception) {
                // Silently fail - file was saved successfully even if we can't open it
                e.printStackTrace()
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