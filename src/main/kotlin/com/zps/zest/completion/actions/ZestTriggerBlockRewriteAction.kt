package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.ZestMethodRewriteService
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import com.zps.zest.completion.prompts.ZestCustomPromptsLoader
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

/**
 * Action to trigger method rewrite with dialog for prompt selection + status bar progress
 * Hybrid approach: Dialog for user choice, status bar for progress updates
 */
class ZestTriggerBlockRewriteAction : AnAction("Trigger Block Rewrite"), HasPriority {
    companion object {
        private val logger = Logger.getInstance(ZestTriggerBlockRewriteAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
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

        // Find method at cursor position
        val contextCollector = ZestMethodContextCollector(project)
        val methodContext = contextCollector.findMethodAtCursor(editor, offset)

        if (methodContext == null) {
            Messages.showWarningDialog(
                project,
                "Could not identify a method at the current cursor position. Place cursor inside a method to rewrite it.",
                "No Method Found"
            )
            return
        }

        // Show dialog for prompt selection, then use status bar for progress
        showPromptSelectionDialog(project, editor, methodContext, methodRewriteService)
    }

    /**
     * Show dialog for prompt selection, then background processing with status bar updates
     */
    private fun showPromptSelectionDialog(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        methodRewriteService: ZestMethodRewriteService
    ) {
        val dialog = SmartRewriteDialog(project, methodContext) { instruction ->
            logger.info("User selected instruction for method ${methodContext.methodName}: '$instruction'")

            // Check if this is the test generation option
            if (instruction == "__WRITE_TEST__") {
                // Send to test generation instead of rewrite
                logger.info("Redirecting to test generation for method ${methodContext.methodName}")
                com.zps.zest.browser.actions.SendMethodTestToChatBox.sendMethodTestPrompt(
                    project,
                    editor,
                    methodContext
                )
            } else {
                // Normal rewrite flow
                // Get status bar widget for progress updates
                val statusBarWidget = getStatusBarWidget(project)

                // Start background processing with status bar progress
                statusBarWidget?.updateMethodRewriteState(
                    ZestCompletionStatusBarWidget.MethodRewriteState.ANALYZING,
                    "Starting method rewrite..."
                )

                // Start rewrite with status bar updates
                methodRewriteService.rewriteCurrentMethodWithStatusCallback(
                    editor = editor,
                    methodContext = methodContext,
                    customInstruction = instruction,
                    dialog = null,  // No dialog updates needed
                    statusCallback = { status ->
                        // All progress updates go to status bar widget
                        statusBarWidget?.updateMethodRewriteStatus(status)
                    }
                )
            }
        }

        // Show dialog and let user choose
        dialog.show()
    }

    /**
     * Get the status bar widget for progress updates
     */
    private fun getStatusBarWidget(project: Project): ZestCompletionStatusBarWidget? {
        return try {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.getWidget(ZestCompletionStatusBarWidget.WIDGET_ID) as? ZestCompletionStatusBarWidget
        } catch (e: Exception) {
            logger.warn("Could not get status bar widget", e)
            null
        }
    }

    /**
     * Dialog for smart method rewrite prompt selection (closes immediately after selection)
     */
    private class SmartRewriteDialog(
        private val project: Project,
        private val methodContext: ZestMethodContextCollector.MethodContext,
        private val onInstructionSelected: ((String) -> Unit)? = null
    ) : DialogWrapper(project) {

        private val inputField = JBTextField()
        private val customTextArea = JBTextArea()
        private var isCustomMode = false
        private var currentSelection = 0
        private var isShiftPressed = false

        // Context-aware options
        private val builtInOptions = generateContextOptions()
        private val customPrompts = ZestCustomPromptsLoader.getInstance(project).loadCustomPrompts().also { prompts ->
            println("[DEBUG] Loaded ${prompts.size} custom prompts: ${prompts.map { "${it.shortcut}: ${it.title}" }}")
        }
        private val builtInLabels = mutableListOf<JLabel>()
        private val customLabels = mutableListOf<JLabel>()

        init {
            title = "Block Rewrite - Choose Instruction"
            setOKButtonText("Apply")
            init()

            setupKeyListener()
            setupCustomTextArea()

            // Focus the input field
            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
            }
        }
        
        private fun setupCustomTextArea() {
            customTextArea.lineWrap = true
            customTextArea.wrapStyleWord = true
            customTextArea.rows = 3
            customTextArea.columns = 50
            customTextArea.border = JBUI.Borders.empty(5)
            
            // Add key listener for custom text area
            customTextArea.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {}
                
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> {
                            if (e.isControlDown) {
                                // Ctrl+Enter to submit
                                val customInstruction = customTextArea.text.trim()
                                if (customInstruction.isNotEmpty()) {
                                    saveAsCustomPrompt(customInstruction)
                                    executeSelectionAndClose(customInstruction)
                                }
                                e.consume()
                            }
                            // Regular Enter adds new line (default behavior)
                        }
                        
                        KeyEvent.VK_ESCAPE -> {
                            close(CANCEL_EXIT_CODE)
                            e.consume()
                        }
                    }
                }
                
                override fun keyReleased(e: KeyEvent) {}
            })
        }

        private fun setupKeyListener() {
            inputField.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {
                    val char = e.keyChar
                    when {
                        char.code == 27 -> {
                            // Escape pressed
                            close(CANCEL_EXIT_CODE)
                        }

                        !char.isDigit() && char.code >= 32 && char != '\n' && char != '\r' && !isCustomMode -> {
                            // Switch to custom mode
                            switchToCustomMode(char.toString())
                        }
                    }
                }

                override fun keyPressed(e: KeyEvent) {
                    // Track shift key state
                    if (e.keyCode == KeyEvent.VK_SHIFT) {
                        isShiftPressed = true
                        updateHighlighting()
                    }
                    
                    if (!isCustomMode) {
                        when (e.keyCode) {
                            // Handle number keys for quick selection
                            KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
                            KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9 -> {
                                val numberPressed = (e.keyCode - KeyEvent.VK_0)
                                
                                if (e.isShiftDown && numberPressed in 1..9) {
                                    // Shift+number for custom prompts
                                    println("[DEBUG] Shift+$numberPressed pressed, looking for custom prompt")
                                    val customPrompt = customPrompts.find { it.shortcut == numberPressed }
                                    if (customPrompt != null) {
                                        println("[DEBUG] Found custom prompt: ${customPrompt.title}")
                                        executeSelectionAndClose(customPrompt.prompt)
                                        e.consume()
                                    } else {
                                        println("[DEBUG] No custom prompt found for shortcut $numberPressed")
                                    }
                                } else if (!e.isShiftDown && numberPressed in 1..4) {
                                    // Regular number for built-in prompts (now supports 1-4)
                                    val optionIndex = numberPressed - 1
                                    if (optionIndex < builtInOptions.size) {
                                        executeSelectionAndClose(builtInOptions[optionIndex].instruction)
                                        e.consume()
                                    }
                                }
                            }
                            
                            KeyEvent.VK_UP -> {
                                currentSelection = if (!e.isShiftDown) {
                                    (currentSelection - 1 + builtInOptions.size) % builtInOptions.size
                                } else {
                                    // Navigate custom prompts
                                    (currentSelection - 1 + customPrompts.size) % customPrompts.size
                                }
                                updateHighlighting()
                                e.consume()
                            }

                            KeyEvent.VK_DOWN -> {
                                currentSelection = if (!e.isShiftDown) {
                                    (currentSelection + 1) % builtInOptions.size
                                } else {
                                    (currentSelection + 1) % customPrompts.size
                                }
                                updateHighlighting()
                                e.consume()
                            }

                            KeyEvent.VK_ENTER -> {
                                if (!e.isShiftDown && currentSelection < builtInOptions.size) {
                                    executeSelectionAndClose(builtInOptions[currentSelection].instruction)
                                } else if (e.isShiftDown && currentSelection < customPrompts.size) {
                                    executeSelectionAndClose(customPrompts[currentSelection].prompt)
                                }
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    } else {
                        // In custom mode - only handle escape, text area has its own handler
                        when (e.keyCode) {
                            KeyEvent.VK_ESCAPE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    }
                }

                override fun keyReleased(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SHIFT) {
                        isShiftPressed = false
                        updateHighlighting()
                    }
                }
            })
        }

        /**
         * Execute selection and close dialog immediately
         */
        private fun executeSelectionAndClose(instruction: String) {
            // Trigger callback immediately
            onInstructionSelected?.invoke(instruction)

            // Close dialog immediately - progress will be shown via status bar
            close(OK_EXIT_CODE)
        }

        private fun generateContextOptions(): List<RewriteOption> {
            val methodContent = methodContext.methodContent

            return when {
                isEmptyOrMinimalMethod(methodContent) -> listOf(
                    RewriteOption("", "Implement method", "Implement this ${methodContext.methodName} method with proper functionality"),
                    RewriteOption("", "Add logging & monitoring", "Add logging and debug statements to track execution"),
                    RewriteOption("", "Add error handling", "Add input validation and error handling"),
                    RewriteOption("", "Write test for method", "__WRITE_TEST__") // Special marker for test generation
                )

                hasTodoComment(methodContent) -> {
                    val todoText = extractTodoText(methodContent)
                    val todoInstruction = if (todoText.isNotEmpty()) "Implement the TODO: $todoText" else "Implement the TODO functionality"
                    listOf(
                        RewriteOption("", "Implement TODO", todoInstruction),
                        RewriteOption("", "Add logging & monitoring", "Add logging and debug statements"),
                        RewriteOption("", "Add error handling", "Add input validation and error handling"),
                        RewriteOption("", "Write test for method", "__WRITE_TEST__") // Special marker for test generation
                    )
                }

                isComplexMethod(methodContent) -> listOf(
                    RewriteOption("", "Refactor & simplify", "Refactor this method for better readability and maintainability"),
                    RewriteOption("", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("", "Optimize performance", "Optimize this method for better performance"),
                    RewriteOption("", "Write test for method", "__WRITE_TEST__") // Special marker for test generation
                )

                else -> listOf(
                    RewriteOption("", "Improve method", "Improve code quality, readability, and add proper error handling"),
                    RewriteOption("", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("", "Add error handling", "Add input validation and error handling"),
                    RewriteOption("", "Write test for method", "__WRITE_TEST__") // Special marker for test generation
                )
            }
        }

        private fun switchToCustomMode(initialText: String = "") {
            isCustomMode = true
            customTextArea.text = initialText
            refreshDialog()

            SwingUtilities.invokeLater {
                customTextArea.requestFocusInWindow()
                // Position cursor at the end
                customTextArea.caretPosition = customTextArea.text.length
            }
        }
        
        /**
         * Save custom prompt to available slots
         */
        private fun saveAsCustomPrompt(instruction: String) {
            try {
                val customPromptsLoader = ZestCustomPromptsLoader.getInstance(project)
                val existingPrompts = customPromptsLoader.loadCustomPrompts()
                
                // Find the next available slot (1-9)
                val usedSlots = existingPrompts.map { it.shortcut }.toSet()
                val nextSlot = (1..9).firstOrNull { it !in usedSlots }
                
                if (nextSlot != null) {
                    // Generate a title from the instruction (first few words)
                    val title = generateTitleFromInstruction(instruction)
                    
                    // Save the custom prompt
                    val success = customPromptsLoader.saveCustomPrompt(nextSlot, title, instruction)
                    if (success) {
                        logger.info("Saved custom prompt to slot $nextSlot: $title")
                    }
                } else {
                    // All slots occupied, replace the last one (slot 9)
                    val title = generateTitleFromInstruction(instruction)
                    val success = customPromptsLoader.saveCustomPrompt(9, title, instruction)
                    if (success) {
                        logger.info("Replaced custom prompt in slot 9: $title")
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to save custom prompt", e)
            }
        }
        
        /**
         * Generate a short title from the instruction
         */
        private fun generateTitleFromInstruction(instruction: String): String {
            val words = instruction.split("\\s+".toRegex()).take(4)
            var title = words.joinToString(" ")
            if (title.length > 30) {
                title = title.substring(0, 30) + "..."
            }
            return if (title.isNotEmpty()) {
                title.first().uppercase() + title.drop(1)
            } else {
                title
            }
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
            val defaultTextColor = UIManager.getColor("Label.foreground") ?: JBColor.BLACK

            // Update built-in options
            builtInLabels.forEachIndexed { index, label ->
                if (!isShiftPressed && index == currentSelection) {
                    label.font = label.font.deriveFont(Font.BOLD)
                } else {
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                label.foreground = defaultTextColor
            }
            
            // Update custom options
            customLabels.forEachIndexed { index, label ->
                if (isShiftPressed && index == currentSelection) {
                    label.font = label.font.deriveFont(Font.BOLD)
                } else {
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                label.foreground = defaultTextColor
            }
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(720, 280)

            // Header
            val headerPanel = JPanel(BorderLayout())
            val methodInfo = "Block: ${methodContext.methodName}() | Detected: ${getDetectionDescription()}"
            val headerLabel = JLabel(methodInfo)
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)

            if (!isCustomMode) {
                val selectionText = if (isShiftPressed && customPrompts.isNotEmpty()) {
                    "Custom: ${customPrompts.getOrNull(currentSelection)?.title ?: ""}"
                } else if (builtInOptions.isNotEmpty()) {
                    "Built-in: ${builtInOptions.getOrNull(currentSelection)?.title ?: ""}"
                } else {
                    ""
                }
                
                if (selectionText.isNotEmpty()) {
                    val selectionInfo = JLabel(selectionText)
                    selectionInfo.font = selectionInfo.font.deriveFont(Font.BOLD)
                    headerPanel.add(selectionInfo, BorderLayout.SOUTH)
                }
            }

            headerPanel.add(headerLabel, BorderLayout.CENTER)
            headerPanel.border = JBUI.Borders.emptyBottom(10)
            panel.add(headerPanel, BorderLayout.NORTH)

            if (isCustomMode) {
                panel.add(createCustomPanel(), BorderLayout.CENTER)
            } else {
                panel.add(createOptionsPanel(), BorderLayout.CENTER)
            }

            return panel
        }

        private fun createOptionsPanel(): JComponent {
            val mainPanel = JPanel(BorderLayout())
            
            // Create two-column layout
            val columnsPanel = JPanel(GridLayout(1, 2, 20, 0))
            columnsPanel.border = JBUI.Borders.empty(5)
            
            // Left column - Built-in prompts
            val leftPanel = JPanel()
            leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
            leftPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1),
                JBUI.Borders.empty(10)
            )
            
            val leftHeader = JLabel("Built-in Prompts (1-4)")
            leftHeader.font = leftHeader.font.deriveFont(Font.BOLD, 14f)
            leftHeader.alignmentX = Component.LEFT_ALIGNMENT
            leftPanel.add(leftHeader)
            leftPanel.add(Box.createVerticalStrut(10))
            
            builtInLabels.clear()
            builtInOptions.forEachIndexed { index, option ->
                val label = JLabel("${index + 1}. ${option.title}")
                label.font = label.font.deriveFont(Font.PLAIN, 13f)
                label.alignmentX = Component.LEFT_ALIGNMENT
                builtInLabels.add(label)
                leftPanel.add(label)
                leftPanel.add(Box.createVerticalStrut(5))
            }
            
            // Right column - Custom prompts
            val rightPanel = JPanel()
            rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)
            rightPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1),
                JBUI.Borders.empty(10)
            )
            
            val rightHeader = JLabel("Custom Prompts (Shift+1-9)")
            rightHeader.font = rightHeader.font.deriveFont(Font.BOLD, 14f)
            rightHeader.alignmentX = Component.LEFT_ALIGNMENT
            rightPanel.add(rightHeader)
            rightPanel.add(Box.createVerticalStrut(10))
            
            customLabels.clear()
            if (customPrompts.isEmpty()) {
                val emptyLabel = JLabel("No custom prompts defined")
                emptyLabel.font = emptyLabel.font.deriveFont(Font.ITALIC)
                emptyLabel.foreground = JBColor.GRAY
                emptyLabel.alignmentX = Component.LEFT_ALIGNMENT
                rightPanel.add(emptyLabel)
            } else {
                customPrompts.forEach { prompt ->
                    val label = JLabel("⇧${prompt.shortcut}. ${prompt.title}")
                    label.font = label.font.deriveFont(Font.PLAIN, 13f)
                    label.alignmentX = Component.LEFT_ALIGNMENT
                    customLabels.add(label)
                    rightPanel.add(label)
                    rightPanel.add(Box.createVerticalStrut(5))
                }
            }
            
            columnsPanel.add(leftPanel)
            columnsPanel.add(rightPanel)
            mainPanel.add(columnsPanel, BorderLayout.CENTER)
            
            // Bottom instructions
            val bottomPanel = JPanel()
            bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
            bottomPanel.border = JBUI.Borders.empty(10, 5, 0, 5)
            
            val instructionLabel = JLabel("Press number for built-in, Shift+number for custom, or type your own prompt")
            instructionLabel.font = instructionLabel.font.deriveFont(Font.PLAIN, 11f)
            instructionLabel.foreground = JBColor.GRAY
            instructionLabel.alignmentX = Component.CENTER_ALIGNMENT
            bottomPanel.add(instructionLabel)
            
            bottomPanel.add(Box.createVerticalStrut(5))
            
            val progressNote = JLabel("Progress will be shown in the status bar →")
            progressNote.font = progressNote.font.deriveFont(Font.ITALIC, 10f)
            progressNote.foreground = JBColor(0x0064C8, 0x4A90E2)
            progressNote.alignmentX = Component.CENTER_ALIGNMENT
            bottomPanel.add(progressNote)
            
            mainPanel.add(bottomPanel, BorderLayout.SOUTH)
            
            updateHighlighting()
            
            // Hidden input field for capturing keystrokes
            inputField.isOpaque = false
            inputField.border = null
            inputField.preferredSize = Dimension(1, 1)
            bottomPanel.add(inputField)
            
            return mainPanel
        }

        private fun createCustomPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(600, 200)

            val headerPanel = JPanel()
            headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
            
            val label = JLabel("Custom instruction:")
            label.font = label.font.deriveFont(Font.BOLD)
            headerPanel.add(label)
            headerPanel.add(Box.createVerticalStrut(5))
            
            val infoLabel = JLabel("Type your custom instruction (will be saved for future use)")
            infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 11f)
            infoLabel.foreground = JBColor.GRAY
            headerPanel.add(infoLabel)
            
            panel.add(headerPanel, BorderLayout.NORTH)

            // Use text area with scroll pane for better text wrapping
            val scrollPane = JBScrollPane(customTextArea)
            scrollPane.preferredSize = Dimension(580, 100)
            scrollPane.border = JBUI.Borders.compound(
                JBUI.Borders.empty(5, 0),
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1)
            )
            panel.add(scrollPane, BorderLayout.CENTER)

            val tipPanel = JPanel()
            tipPanel.layout = BoxLayout(tipPanel, BoxLayout.Y_AXIS)
            tipPanel.border = JBUI.Borders.emptyTop(10)

            val tipLabel = JLabel("Ctrl+Enter to apply, Escape to cancel")
            tipLabel.font = tipLabel.font.deriveFont(Font.ITALIC, 11f)
            tipLabel.foreground = JBColor.GRAY
            tipLabel.alignmentX = Component.CENTER_ALIGNMENT
            tipPanel.add(tipLabel)
            
            tipPanel.add(Box.createVerticalStrut(3))

            val progressNote = JLabel("Progress will be shown in the status bar")
            progressNote.font = progressNote.font.deriveFont(Font.ITALIC, 10f)
            progressNote.foreground = JBColor(0x0064C8, 0x4A90E2)
            progressNote.alignmentX = Component.CENTER_ALIGNMENT
            tipPanel.add(progressNote)
            
            tipPanel.add(Box.createVerticalStrut(3))
            
            val saveNote = JLabel("Custom prompts are automatically saved for future use")
            saveNote.font = saveNote.font.deriveFont(Font.ITALIC, 9f)
            saveNote.foreground = JBColor(0x007800, 0x6BA644)
            saveNote.alignmentX = Component.CENTER_ALIGNMENT
            tipPanel.add(saveNote)

            panel.add(tipPanel, BorderLayout.SOUTH)

            return panel
        }

        private fun getDetectionDescription(): String {
            val content = methodContext.methodContent
            return when {
                isEmptyOrMinimalMethod(content) -> "Empty method body"
                hasTodoComment(content) -> "TODO comment found"
                isComplexMethod(content) -> "Complex method"
                else -> "Ready for improvement"
            }
        }

        // Helper methods for method analysis
        private fun isEmptyOrMinimalMethod(content: String): Boolean {
            val body = content.substringAfter("{").substringBeforeLast("}")
            val cleanBody = body.trim()
                .replace(Regex("//.*"), "")
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
            return cleanBody.isEmpty() || cleanBody == "return null;" || cleanBody == "return;" || cleanBody.length < 10
        }

        private fun hasTodoComment(content: String): Boolean {
            return content.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE))
        }

        private fun extractTodoText(content: String): String {
            val todoRegex = Regex("//\\s*(TODO|FIXME|XXX|HACK)\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)
            return todoRegex.find(content)?.groupValues?.get(2)?.trim() ?: ""
        }

        private fun isComplexMethod(content: String): Boolean {
            val lines = content.lines().size
            val cyclomaticComplexity = content.count { it == '(' } +
                    content.split(Regex("\\b(if|for|while|switch|case|catch)\\b")).size - 1
            return lines > 30 || cyclomaticComplexity > 10
        }

        private data class RewriteOption(
            val icon: String,
            val title: String,
            val instruction: String
        )

        // Hide the bottom button panel since we're using keyboard-only interaction
        override fun createSouthPanel(): JComponent? = null
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val methodRewriteService = project?.serviceOrNull<ZestMethodRewriteService>()

        val isAvailable = project != null && editor != null && methodRewriteService != null && !editor.isDisposed

        e.presentation.isEnabledAndVisible = isAvailable

        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Block Rewrite (In Progress...)"
            e.presentation.description = "A block rewrite is currently in progress - check status bar"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Trigger Block Rewrite"
            e.presentation.description = "Choose rewrite instruction (progress shown in status bar)"
            e.presentation.isEnabled = isAvailable
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override val priority: Int = 17
}