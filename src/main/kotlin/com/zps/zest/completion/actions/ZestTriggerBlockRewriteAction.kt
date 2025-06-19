package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.ZestMethodRewriteService
import com.zps.zest.completion.context.ZestMethodContextCollector
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

/**
 * Action to manually trigger method rewrite at cursor position with smart instruction prefilling
 */
class ZestTriggerMethodRewriteAction : AnAction("Trigger Method Rewrite"), HasPriority {
    private val logger = Logger.getInstance(ZestTriggerMethodRewriteAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val methodRewriteService = project.serviceOrNull<ZestMethodRewriteService>()

        if (methodRewriteService == null) {
            logger.warn("ZestMethodRewriteService not available")
            return
        }

        // Check if another rewrite is in progress
        if (methodRewriteService.isRewriteInProgress()) {
            Messages.showWarningDialog(
                project,
                "A method rewrite operation is already in progress. Please wait for it to complete or cancel it first.",
                "Method Rewrite In Progress"
            )
            return
        }

        val offset = editor.caretModel.primaryCaret.offset

        // Smart instruction prefilling based on context
        val contextCollector = ZestMethodContextCollector(project)
        val methodContext = contextCollector.findMethodAtCursor(editor, offset)
        val prefilledInstruction = generateSmartInstruction(methodContext, editor, offset)

        // Show smart 3-option dialog and handle the full workflow
        showSmartRewriteDialogWithProgress(project, editor, methodContext, offset, methodRewriteService)
    }

    /**
     * Show dialog and handle the complete rewrite workflow with progress
     */
    private fun showSmartRewriteDialogWithProgress(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: ZestMethodContextCollector.MethodContext?,
        offset: Int,
        methodRewriteService: ZestMethodRewriteService
    ) {
        var dialogInstance: SmartRewriteDialog? = null
        
        val dialog = SmartRewriteDialog(project, methodContext, "") { instruction ->
            // This callback is triggered when user selects an instruction
            // The dialog will show processing state automatically
            logger.info("Triggered method rewrite at offset $offset with instruction: '$instruction'")
            methodRewriteService.rewriteCurrentMethod(editor, offset, instruction)

            // Close dialog after a short delay (the rewrite service will show its own progress)
            javax.swing.Timer(1000) {
                dialogInstance?.completeProcessing()
            }.apply {
                isRepeats = false
                start()
            }
        }
        
        dialogInstance = dialog

        // Show the dialog - it will handle the entire workflow internally
        dialog.showAndGetResult()
    }

    /**
     * Generate smart instruction based on current context and cursor position
     */
    private fun generateSmartInstruction(
        methodContext: ZestMethodContextCollector.MethodContext?,
        editor: com.intellij.openapi.editor.Editor,
        offset: Int
    ): String {
        if (methodContext == null) {
            return "Improve this code"
        }

        val document = editor.document
        val text = document.text
        val methodContent = methodContext.methodContent
        val cursorLine = document.getLineNumber(offset)
        val currentLineText = document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(cursorLine),
                document.getLineEndOffset(cursorLine)
            )
        ).trim()

        // Analyze context for smart instructions
        return when {
            // Empty or minimal method body
            isEmptyOrMinimalMethod(methodContent) ->
                "Implement this ${methodContext.methodName} method with proper functionality"

            // TODO/FIXME comments
            hasTodoComment(methodContent, currentLineText) -> {
                val todoText = extractTodoText(methodContent, currentLineText)
                if (todoText.isNotEmpty()) {
                    "Implement the TODO: $todoText"
                } else {
                    "Implement the TODO functionality"
                }
            }

            // Placeholder implementations
            hasPlaceholderCode(methodContent) ->
                "Replace placeholder implementation with proper logic"

            // Comments without implementation
            hasCommentWithoutImplementation(methodContent, currentLineText) ->
                "Implement the functionality described in the comments"

            // Method name-based suggestions
            isGetterMethod(methodContext.methodName) && isMinimalGetter(methodContent) ->
                "Add validation and error handling to this getter method"

            isSetterMethod(methodContext.methodName) && isMinimalSetter(methodContent) ->
                "Add validation, type checking, and error handling to this setter method"

            isTestMethod(methodContext.methodName) && isMinimalTest(methodContent) ->
                "Implement comprehensive test cases for this test method"

            // Complex method that needs refactoring
            isComplexMethod(methodContent) ->
                "Refactor this method for better readability and maintainability"

            // Missing error handling
            lacksErrorHandling(methodContent) ->
                "Add proper error handling and input validation"

            // Performance issues
            hasPerformanceIssues(methodContent) ->
                "Optimize this method for better performance"

            // Method name suggests specific functionality
            methodContext.methodName.contains("parse", ignoreCase = true) ->
                "Improve parsing logic with better error handling and validation"

            methodContext.methodName.contains("validate", ignoreCase = true) ->
                "Enhance validation logic with comprehensive checks"

            methodContext.methodName.contains("process", ignoreCase = true) ->
                "Improve processing logic with better error handling"

            methodContext.methodName.contains("init", ignoreCase = true) ->
                "Enhance initialization with proper setup and validation"

            // Default improvements
            else -> "Improve code quality, readability, and add proper error handling"
        }
    }


    /**
     * Custom dialog for smart method rewrite options with progress tracking
     */
    private class SmartRewriteDialog(
        private val project: Project,
        private val methodContext: ZestMethodContextCollector.MethodContext?,
        private val defaultInstruction: String,
        private val onInstructionSelected: ((String) -> Unit)? = null
    ) : DialogWrapper(project) {

        private var selectedInstruction: String? = null
        private val inputField = JBTextField()
        private var isCustomMode = false
        private var isProcessing = false
        private var currentSelection = 0 // Default to option 1 (index 0)

        // Context-aware options
        private val options = generateContextOptions()
        private val optionLabels = mutableListOf<JLabel>()

        // Progress tracking
        private var processingStartTime = 0L
        private var progressLabel: JLabel? = null
        private var progressTimer: javax.swing.Timer? = null

        init {
            title = "Method Rewrite"
            setOKButtonText("Apply")
            init()

            // Set up input field for capturing user input
            inputField.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {
                    val char = e.keyChar
                    when {
                        char.isDigit() && char in '1'..'3' && !isCustomMode && !isProcessing -> {
                            // Quick selection - start processing
                            val optionIndex = char.toString().toInt() - 1
                            currentSelection = optionIndex
                            updateHighlighting()
                            selectedInstruction = options[optionIndex].instruction
                            startProcessing()
                        }

                        char.code == 27 -> {
                            // Escape pressed (backup handling)
                            if (!isProcessing) {
                                close(CANCEL_EXIT_CODE)
                            }
                        }

                        !char.isDigit() && char.code >= 32 && char != '\n' && char != '\r' && !isCustomMode -> {
                            // Switch to custom mode (exclude Enter characters)
                            switchToCustomMode()
                            inputField.text = char.toString()
                        }
                    }
                }

                override fun keyPressed(e: KeyEvent) {
                    if (!isCustomMode) {
                        when (e.keyCode) {
                            KeyEvent.VK_UP -> {
                                currentSelection = (currentSelection - 1 + options.size) % options.size
                                updateHighlighting()
                                e.consume() // Prevent default behavior
                            }

                            KeyEvent.VK_DOWN -> {
                                currentSelection = (currentSelection + 1) % options.size
                                updateHighlighting()
                                e.consume() // Prevent default behavior
                            }

                            KeyEvent.VK_ENTER -> {
                                if (!isProcessing) {
                                    // Apply currently highlighted option and start processing
                                    selectedInstruction = options[currentSelection].instruction
                                    startProcessing()
                                }
                                e.consume() // Prevent default behavior
                            }

                            KeyEvent.VK_ESCAPE -> {
                                if (!isProcessing) {
                                    close(CANCEL_EXIT_CODE)
                                }
                                e.consume() // Prevent default behavior
                            }
                        }
                    } else {
                        // In custom mode, handle Enter and Escape
                        when (e.keyCode) {
                            KeyEvent.VK_ENTER -> {
                                if (!isProcessing) {
                                    selectedInstruction = inputField.text.trim()
                                    if (selectedInstruction?.isNotEmpty() == true) {
                                        startProcessing()
                                    }
                                }
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                if (!isProcessing) {
                                    close(CANCEL_EXIT_CODE)
                                }
                                e.consume()
                            }
                        }
                    }
                }

                override fun keyReleased(e: KeyEvent) {}
            })

            // Focus the input field
            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
            }
        }

        private fun generateContextOptions(): List<RewriteOption> {
            val methodName = methodContext?.methodName ?: "method"
            val methodContent = methodContext?.methodContent ?: ""

            return when {
                // Empty or minimal method body
                isEmptyOrMinimalMethod(methodContent) -> listOf(
                    RewriteOption(
                        "🚀",
                        "Implement method (recommended)",
                        "Implement this $methodName method with proper functionality"
                    ),
                    RewriteOption(
                        "📝",
                        "Add logging & monitoring",
                        "Add logging and debug statements to track execution"
                    ),
                    RewriteOption("🛡️", "Add error handling & validation", "Add input validation and error handling")
                )

                // TODO/FIXME comments  
                hasTodoComment(methodContent, "") -> {
                    val todoText = extractTodoText(methodContent, "")
                    val todoInstruction =
                        if (todoText.isNotEmpty()) "Implement the TODO: $todoText" else "Implement the TODO functionality"
                    listOf(
                        RewriteOption("✅", "Implement TODO (recommended)", todoInstruction),
                        RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                        RewriteOption(
                            "🛡️",
                            "Add error handling & validation",
                            "Add input validation and error handling"
                        )
                    )
                }

                // Complex method that needs refactoring
                isComplexMethod(methodContent) -> listOf(
                    RewriteOption(
                        "🔧",
                        "Refactor & simplify (recommended)",
                        "Refactor this method for better readability and maintainability"
                    ),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("📈", "Optimize performance", "Optimize this method for better performance")
                )

                // Performance issues
                hasPerformanceIssues(methodContent) -> listOf(
                    RewriteOption(
                        "📈",
                        "Optimize performance (recommended)",
                        "Optimize this method for better performance"
                    ),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("🔧", "Refactor structure", "Refactor this method for better structure")
                )

                // Default case
                else -> listOf(
                    RewriteOption(
                        "🚀",
                        "Improve method (recommended)",
                        "Improve code quality, readability, and add proper error handling"
                    ),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("🛡️", "Add error handling & validation", "Add input validation and error handling")
                )
            }
        }

        private fun switchToCustomMode() {
            if (isProcessing) return

            isCustomMode = true
            // Refresh the dialog to show custom input mode
            val content = contentPanel
            if (content != null) {
                content.removeAll()
                content.add(createCenterPanel())
                content.revalidate()
                content.repaint()
                pack()

                // Focus the input field in custom mode
                SwingUtilities.invokeLater {
                    inputField.requestFocusInWindow()
                }
            }
        }

        /**
         * Start processing the selected instruction
         */
        private fun startProcessing() {
            selectedInstruction?.let { instruction ->
                isProcessing = true
                processingStartTime = System.currentTimeMillis()

                // Update the dialog to show processing state
                refreshDialog()

                // Start a timer to update the progress
                progressTimer = javax.swing.Timer(500) { updateProgress() }
                progressTimer?.start()

                // Trigger the callback
                onInstructionSelected?.invoke(instruction)
            }
        }

        /**
         * Show processing state with the selected instruction (legacy method for compatibility)
         */
        fun showProcessingState(instruction: String) {
            selectedInstruction = instruction
            startProcessing()
        }

        /**
         * Update progress display
         */
        private fun updateProgress() {
            if (isProcessing && progressLabel != null) {
                val elapsed = (System.currentTimeMillis() - processingStartTime) / 1000
                val dots = ".".repeat((elapsed % 4).toInt())
                progressLabel?.text = "AI is processing your request$dots (${elapsed}s)"
            }
        }

        /**
         * Complete processing and close dialog
         */
        fun completeProcessing() {
            // Stop the progress timer
            progressTimer?.stop()
            progressTimer = null

            isProcessing = false
            close(OK_EXIT_CODE)
        }

        /**
         * Refresh the dialog content
         */
        private fun refreshDialog() {
            val content = contentPanel
            if (content != null) {
                content.removeAll()
                content.add(createCenterPanel())
                content.revalidate()
                content.repaint()
                pack()
            }
        }

        /**
         * Update visual highlighting for the currently selected option
         */
        private fun updateHighlighting() {
            // Get theme-appropriate colors
            val defaultTextColor = UIManager.getColor("Label.foreground") ?: Color.BLACK
            val highlightColor = Color(0, 150, 0) // Green color

            optionLabels.forEachIndexed { index, label ->
                if (index == currentSelection) {
                    // Highlight selected option with green text and bold font
                    label.foreground = highlightColor
                    label.font = label.font.deriveFont(Font.BOLD)
                } else {
                    // Reset to theme-appropriate color
                    label.foreground = defaultTextColor
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
            }
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(520, 220)

            if (isProcessing) {
                // Show processing state
                return createProcessingPanel()
            }

            // Header with current selection info
            val headerPanel = JPanel(BorderLayout())
            val methodInfo = if (methodContext != null) {
                "Method: ${methodContext.methodName}() | Detected: ${getDetectionDescription()}"
            } else {
                "Method rewrite options"
            }
            val headerLabel = JLabel(methodInfo)
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)

            // Add current selection indicator in header
            if (!isCustomMode && options.isNotEmpty()) {
                val selectionInfo = JLabel("Selected: ${options[currentSelection].title}")
                selectionInfo.font = selectionInfo.font.deriveFont(Font.ITALIC, 11f)
                selectionInfo.foreground = Color(0, 150, 0) // Green to match highlighted option
                headerPanel.add(selectionInfo, BorderLayout.SOUTH)
            }

            headerPanel.add(headerLabel, BorderLayout.CENTER)
            headerPanel.border = JBUI.Borders.empty(0, 0, 10, 0)

            panel.add(headerPanel, BorderLayout.NORTH)

            if (isCustomMode) {
                // Custom instruction mode
                val customPanel = createCustomPanel()
                panel.add(customPanel, BorderLayout.CENTER)
            } else {
                // Options mode  
                val optionsPanel = createOptionsPanel()
                panel.add(optionsPanel, BorderLayout.CENTER)
            }

            return panel
        }

        /**
         * Create processing panel showing the chosen instruction and progress
         */
        private fun createProcessingPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(520, 220)

            // Header
            val headerPanel = JPanel(BorderLayout())
            val methodInfo = if (methodContext != null) {
                "Method: ${methodContext.methodName}() | Rewriting in progress..."
            } else {
                "Method rewrite in progress..."
            }
            val headerLabel = JLabel(methodInfo)
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)
            headerPanel.add(headerLabel, BorderLayout.CENTER)
            headerPanel.border = JBUI.Borders.empty(0, 0, 15, 0)

            panel.add(headerPanel, BorderLayout.NORTH)

            // Center content
            val centerPanel = JPanel()
            centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)

            // Show selected instruction
            val instructionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            val instructionLabel = JLabel("Selected instruction:")
            instructionLabel.font = instructionLabel.font.deriveFont(Font.BOLD, 12f)
            instructionPanel.add(instructionLabel)
            centerPanel.add(instructionPanel)

            // Show the instruction text
            val instructionTextPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            val instructionText = JLabel("\"${selectedInstruction}\"")
            instructionText.font = instructionText.font.deriveFont(Font.ITALIC, 11f)
            instructionText.foreground = Color(0, 100, 200) // Blue color for instruction
            instructionTextPanel.add(instructionText)
            centerPanel.add(instructionTextPanel)

            // Add spacer
            centerPanel.add(Box.createVerticalStrut(20))

            // Progress indicator
            val progressPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            progressLabel = JLabel("AI is processing your request... (0s)")
            progressLabel?.font = progressLabel?.font?.deriveFont(Font.PLAIN, 12f)
            progressLabel?.foreground = Color(0, 150, 0) // Green color
            progressPanel.add(progressLabel)
            centerPanel.add(progressPanel)

            // Add spacer
            centerPanel.add(Box.createVerticalStrut(10))

            // Info text
            val infoPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            val infoLabel = JLabel("This dialog will close automatically when complete")
            infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 10f)
            infoLabel.foreground = Color.GRAY
            infoPanel.add(infoLabel)
            centerPanel.add(infoPanel)

            panel.add(centerPanel, BorderLayout.CENTER)

            return panel
        }

        private fun createOptionsPanel(): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

            // Clear previous labels
            optionLabels.clear()

            // Add each option with highlighting support
            options.forEachIndexed { index, option ->
                val optionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
                val label = JLabel("${index + 1}. ${option.icon} ${option.title}")
                label.font = label.font.deriveFont(Font.PLAIN, 13f)

                // Store label for highlighting
                optionLabels.add(label)

                optionPanel.add(label)
                panel.add(optionPanel)
            }

            // Apply initial highlighting (option 1 is default)
            updateHighlighting()

            // Add spacer
            panel.add(Box.createVerticalStrut(10))

            // Add instruction with highlighted default info
            val instructionText = "Press 1-3, Enter for highlighted option, or type custom:"
            val instructionLabel = JLabel(instructionText)
            instructionLabel.font = instructionLabel.font.deriveFont(Font.ITALIC, 11f)
            instructionLabel.foreground = Color.GRAY
            val instructionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            instructionPanel.add(instructionLabel)
            panel.add(instructionPanel)

            // Add invisible input field for key capture
            inputField.isOpaque = false
            inputField.border = null
            inputField.preferredSize = Dimension(1, 1)
            panel.add(inputField)

            return panel
        }

        private fun createCustomPanel(): JComponent {
            val panel = JPanel(BorderLayout())

            val label = JLabel("Custom instruction:")
            panel.add(label, BorderLayout.NORTH)

            inputField.isOpaque = true
            inputField.border = JBUI.Borders.compound(
                JBUI.Borders.empty(5, 0),
                inputField.border
            )
            inputField.preferredSize = Dimension(400, 30)
            panel.add(inputField, BorderLayout.CENTER)

            val tipLabel = JLabel("Press Enter to apply")
            tipLabel.font = tipLabel.font.deriveFont(Font.ITALIC, 11f)
            tipLabel.foreground = Color.GRAY
            panel.add(tipLabel, BorderLayout.SOUTH)

            return panel
        }

        private fun getDetectionDescription(): String {
            val content = methodContext?.methodContent ?: ""
            return when {
                isEmptyOrMinimalMethod(content) -> "Empty method body"
                hasTodoComment(content, "") -> "TODO comment found"
                isComplexMethod(content) -> "Complex method"
                hasPerformanceIssues(content) -> "Performance issues"
                else -> "Ready for improvement"
            }
        }

        fun showAndGetResult(): String? {
            showAndGet() // Just show the dialog, processing is handled internally
            return null // The callback will handle the result
        }

        // Hide the bottom button panel since we're using keyboard-only interaction
        override fun createSouthPanel(): JComponent? = null

        override fun dispose() {
            // Clean up timer when dialog is disposed
            progressTimer?.stop()
            progressTimer = null
            super.dispose()
        }

        // Helper methods (copied from parent class for access)
        private fun isEmptyOrMinimalMethod(content: String): Boolean {
            val body = content.substringAfter("{").substringBeforeLast("}")
            val cleanBody = body.trim()
                .replace(Regex("//.*"), "")
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
            return cleanBody.isEmpty() || cleanBody == "return null;" || cleanBody == "return;" || cleanBody.length < 10
        }

        private fun hasTodoComment(methodContent: String, currentLine: String): Boolean {
            return methodContent.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE))
        }

        private fun extractTodoText(methodContent: String, currentLine: String): String {
            val todoRegex = Regex("//\\s*(TODO|FIXME|XXX|HACK)\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)
            return todoRegex.find(methodContent)?.groupValues?.get(2)?.trim() ?: ""
        }

        private fun isComplexMethod(content: String): Boolean {
            val lines = content.lines().size
            val cyclomaticComplexity = content.count { it == '(' } +
                    content.split(Regex("\\b(if|for|while|switch|case|catch)\\b")).size - 1
            return lines > 30 || cyclomaticComplexity > 10
        }

        private fun hasPerformanceIssues(content: String): Boolean {
            return content.contains(Regex("for\\s*\\(.*\\)\\s*\\{[^}]*for\\s*\\(")) ||
                    content.contains("++") && content.contains("new ") ||
                    content.split("new ").size > 5
        }

        private data class RewriteOption(
            val icon: String,
            val title: String,
            val instruction: String
        )
    }

    // Helper methods for context analysis

    private fun isEmptyOrMinimalMethod(content: String): Boolean {
        val body = content.substringAfter("{").substringBeforeLast("}")
        val cleanBody = body.trim()
            .replace(Regex("//.*"), "") // Remove comments
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "") // Remove block comments
            .trim()

        return cleanBody.isEmpty() ||
                cleanBody == "return null;" ||
                cleanBody == "return;" ||
                cleanBody.length < 10
    }

    private fun hasTodoComment(methodContent: String, currentLine: String): Boolean {
        return methodContent.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE)) ||
                currentLine.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE))
    }

    private fun extractTodoText(methodContent: String, currentLine: String): String {
        val todoRegex = Regex("//\\s*(TODO|FIXME|XXX|HACK)\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)

        // First check current line
        todoRegex.find(currentLine)?.let { match ->
            return match.groupValues[2].trim()
        }

        // Then check method content
        todoRegex.find(methodContent)?.let { match ->
            return match.groupValues[2].trim()
        }

        return ""
    }

    private fun hasPlaceholderCode(content: String): Boolean {
        return content.contains("throw new UnsupportedOperationException") ||
                content.contains("throw UnsupportedOperationException") ||
                content.contains("NotImplementedError") ||
                content.contains("TODO()") ||
                content.contains("return null") ||
                content.contains("return 0") ||
                content.contains("return false") ||
                content.contains("return \"\"") ||
                content.contains("return []") ||
                content.contains("return {}")
    }

    private fun hasCommentWithoutImplementation(methodContent: String, currentLine: String): Boolean {
        val hasComment = methodContent.contains(Regex("//[^/]")) ||
                methodContent.contains(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) ||
                currentLine.contains(Regex("//[^/]"))

        if (!hasComment) return false

        // Check if there's minimal implementation after comments
        val body = methodContent.substringAfter("{").substringBeforeLast("}")
        val codeWithoutComments = body
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .trim()

        return codeWithoutComments.length < 20 // Very minimal implementation
    }

    private fun isGetterMethod(methodName: String): Boolean {
        return methodName.startsWith("get") ||
                methodName.startsWith("is") ||
                methodName.startsWith("has")
    }

    private fun isSetterMethod(methodName: String): Boolean {
        return methodName.startsWith("set")
    }

    private fun isTestMethod(methodName: String): Boolean {
        return methodName.startsWith("test") ||
                methodName.endsWith("Test") ||
                methodName.contains("should") ||
                methodName.contains("when")
    }

    private fun isMinimalGetter(content: String): Boolean {
        return content.contains("return ") && content.lines().size < 5
    }

    private fun isMinimalSetter(content: String): Boolean {
        return content.contains("this.") && content.contains("=") && content.lines().size < 5
    }

    private fun isMinimalTest(content: String): Boolean {
        return !content.contains("assert") &&
                !content.contains("expect") &&
                !content.contains("verify") &&
                content.lines().size < 10
    }

    private fun isComplexMethod(content: String): Boolean {
        val lines = content.lines().size
        val cyclomaticComplexity = content.count { it == '(' } +
                content.split(Regex("\\b(if|for|while|switch|case|catch)\\b")).size - 1

        return lines > 30 || cyclomaticComplexity > 10
    }

    private fun lacksErrorHandling(content: String): Boolean {
        return !content.contains("try") &&
                !content.contains("catch") &&
                !content.contains("throw") &&
                !content.contains("if") && // Basic validation
                content.contains("(") // Has parameters that should be validated
    }

    private fun hasPerformanceIssues(content: String): Boolean {
        return content.contains(Regex("for\\s*\\(.*\\)\\s*\\{[^}]*for\\s*\\(")) || // Nested loops
                content.contains("++") && content.contains("new ") || // Object creation in loops
                content.split("new ").size > 5 // Multiple object creations
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val methodRewriteService = project?.serviceOrNull<ZestMethodRewriteService>()

        val isAvailable = project != null &&
                editor != null &&
                methodRewriteService != null &&
                !editor.isDisposed

        e.presentation.isEnabledAndVisible = isAvailable

        // Update description based on service state
        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Method Rewrite (In Progress...)"
            e.presentation.description = "A method rewrite is currently in progress"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Trigger Method Rewrite"
            e.presentation.description = "Rewrite the current method with AI improvements"
            e.presentation.isEnabled = isAvailable
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    /**
     * High priority for manual triggers
     */
    override val priority: Int = 17
}
