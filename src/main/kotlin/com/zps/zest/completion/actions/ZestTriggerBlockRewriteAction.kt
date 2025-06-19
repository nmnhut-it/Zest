package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.ZestMethodRewriteService
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

/**
 * Action to trigger method rewrite with dialog for prompt selection + status bar progress
 * Hybrid approach: Dialog for user choice, status bar for progress updates
 */
class ZestTriggerBlockRewriteAction : AnAction("Trigger Block Rewrite"), HasPriority {
    private val logger = Logger.getInstance(ZestTriggerBlockRewriteAction::class.java)

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
        private var isCustomMode = false
        private var currentSelection = 0

        // Context-aware options
        private val options = generateContextOptions()
        private val optionLabels = mutableListOf<JLabel>()

        init {
            title = "Block Rewrite - Choose Instruction"
            setOKButtonText("Apply")
            init()

            setupKeyListener()

            // Focus the input field
            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
            }
        }

        private fun setupKeyListener() {
            inputField.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {
                    val char = e.keyChar
                    when {
                        char.isDigit() && char in '1'..'3' && !isCustomMode -> {
                            // Quick selection - execute and close dialog immediately
                            val optionIndex = char.toString().toInt() - 1
                            executeSelectionAndClose(options[optionIndex].instruction)
                        }

                        char.code == 27 -> {
                            // Escape pressed
                            close(CANCEL_EXIT_CODE)
                        }

                        !char.isDigit() && char.code >= 32 && char != '\n' && char != '\r' && !isCustomMode -> {
                            // Switch to custom mode
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
                                e.consume()
                            }

                            KeyEvent.VK_DOWN -> {
                                currentSelection = (currentSelection + 1) % options.size
                                updateHighlighting()
                                e.consume()
                            }

                            KeyEvent.VK_ENTER -> {
                                executeSelectionAndClose(options[currentSelection].instruction)
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    } else {
                        // In custom mode
                        when (e.keyCode) {
                            KeyEvent.VK_ENTER -> {
                                val customInstruction = inputField.text.trim()
                                if (customInstruction.isNotEmpty()) {
                                    executeSelectionAndClose(customInstruction)
                                }
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    }
                }

                override fun keyReleased(e: KeyEvent) {}
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
                    RewriteOption("🚀", "Implement method (recommended)", "Implement this ${methodContext.methodName} method with proper functionality"),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements to track execution"),
                    RewriteOption("🛡️", "Add error handling & validation", "Add input validation and error handling")
                )

                hasTodoComment(methodContent) -> {
                    val todoText = extractTodoText(methodContent)
                    val todoInstruction = if (todoText.isNotEmpty()) "Implement the TODO: $todoText" else "Implement the TODO functionality"
                    listOf(
                        RewriteOption("✅", "Implement TODO (recommended)", todoInstruction),
                        RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                        RewriteOption("🛡️", "Add error handling & validation", "Add input validation and error handling")
                    )
                }

                isComplexMethod(methodContent) -> listOf(
                    RewriteOption("🔧", "Refactor & simplify (recommended)", "Refactor this method for better readability and maintainability"),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("📈", "Optimize performance", "Optimize this method for better performance")
                )

                else -> listOf(
                    RewriteOption("🚀", "Improve method (recommended)", "Improve code quality, readability, and add proper error handling"),
                    RewriteOption("📝", "Add logging & monitoring", "Add logging and debug statements"),
                    RewriteOption("🛡️", "Add error handling & validation", "Add input validation and error handling")
                )
            }
        }

        private fun switchToCustomMode() {
            isCustomMode = true
            refreshDialog()

            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
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
            val defaultTextColor = UIManager.getColor("Label.foreground") ?: Color.BLACK
            val highlightColor = Color(0, 150, 0)

            optionLabels.forEachIndexed { index, label ->
                if (index == currentSelection) {
                    label.foreground = highlightColor
                    label.font = label.font.deriveFont(Font.BOLD)
                } else {
                    label.foreground = defaultTextColor
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
            }
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(520, 220)

            // Header
            val headerPanel = JPanel(BorderLayout())
            val methodInfo = "Block: ${methodContext.methodName}() | Detected: ${getDetectionDescription()}"
            val headerLabel = JLabel(methodInfo)
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)

            if (!isCustomMode && options.isNotEmpty()) {
                val selectionInfo = JLabel("Selected: ${options[currentSelection].title}")
                selectionInfo.font = selectionInfo.font.deriveFont(Font.ITALIC, 11f)
                selectionInfo.foreground = Color(0, 150, 0)
                headerPanel.add(selectionInfo, BorderLayout.SOUTH)
            }

            headerPanel.add(headerLabel, BorderLayout.CENTER)
            headerPanel.border = JBUI.Borders.empty(0, 0, 10, 0)
            panel.add(headerPanel, BorderLayout.NORTH)

            if (isCustomMode) {
                panel.add(createCustomPanel(), BorderLayout.CENTER)
            } else {
                panel.add(createOptionsPanel(), BorderLayout.CENTER)
            }

            return panel
        }

        private fun createOptionsPanel(): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

            optionLabels.clear()

            options.forEachIndexed { index, option ->
                val optionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
                val label = JLabel("${index + 1}. ${option.icon} ${option.title}")
                label.font = label.font.deriveFont(Font.PLAIN, 13f)
                optionLabels.add(label)
                optionPanel.add(label)
                panel.add(optionPanel)
            }

            updateHighlighting()

            panel.add(Box.createVerticalStrut(10))

            val instructionLabel = JLabel("Press 1-3, Enter for highlighted option, or type custom:")
            instructionLabel.font = instructionLabel.font.deriveFont(Font.ITALIC, 11f)
            instructionLabel.foreground = Color.GRAY
            val instructionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            instructionPanel.add(instructionLabel)
            panel.add(instructionPanel)

            // Add note about status bar progress
            val progressNote = JLabel("Progress will be shown in the status bar →")
            progressNote.font = progressNote.font.deriveFont(Font.ITALIC, 10f)
            progressNote.foreground = Color(0, 100, 200)
            val progressPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            progressPanel.add(progressNote)
            panel.add(progressPanel)

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
            inputField.border = JBUI.Borders.compound(JBUI.Borders.empty(5, 0), inputField.border)
            inputField.preferredSize = Dimension(400, 30)
            panel.add(inputField, BorderLayout.CENTER)

            val tipPanel = JPanel()
            tipPanel.layout = BoxLayout(tipPanel, BoxLayout.Y_AXIS)

            val tipLabel = JLabel("Press Enter to apply")
            tipLabel.font = tipLabel.font.deriveFont(Font.ITALIC, 11f)
            tipLabel.foreground = Color.GRAY
            tipPanel.add(tipLabel)

            val progressNote = JLabel("Progress will be shown in the status bar")
            progressNote.font = progressNote.font.deriveFont(Font.ITALIC, 10f)
            progressNote.foreground = Color(0, 100, 200)
            tipPanel.add(progressNote)

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