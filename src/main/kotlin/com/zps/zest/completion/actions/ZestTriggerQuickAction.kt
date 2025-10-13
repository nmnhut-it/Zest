package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.MethodContextFormatter
import com.zps.zest.completion.prompts.ZestCustomPromptsLoader
import com.zps.zest.completion.metrics.ZestQuickActionMetricsService
import com.zps.zest.testgen.actions.GenerateTestAction
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.util.UUID
import javax.swing.*

/**
 * Context-aware quick action menu.
 * Shows options based on cursor position - some require method context, others are global.
 */
class ZestTriggerQuickAction : AnAction(), HasPriority {
    companion object {
        private val logger = Logger.getInstance(ZestTriggerQuickAction::class.java)
    }

    private object Instructions {
        const val WRITE_TEST = "__WRITE_TEST__"
        const val COMMIT_ALL = "__COMMIT_ALL__"
        const val FILL_CODE = "__FILL_CODE__"
        const val OPEN_GIT_UI = "__OPEN_GIT_UI__"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val offset = editor.caretModel.primaryCaret.offset
        val methodContext = findMethodAtOffset(editor, offset, project)

        showPromptSelectionDialog(project, editor, methodContext)
    }

    private fun showPromptSelectionDialog(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: MethodContext?
    ) {
        val dialog = SimplePromptDialog(project, methodContext) { instruction ->
            logger.info("User selected instruction: '$instruction'")
            routeInstruction(project, editor, methodContext, instruction)
        }
        dialog.show()
    }

    private fun routeInstruction(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: MethodContext?,
        instruction: String
    ) {
        when (instruction) {
            Instructions.WRITE_TEST -> {
                if (methodContext == null) {
                    Messages.showWarningDialog(project, "Place cursor inside a method to generate tests", "No Method Found")
                    return
                }
                triggerTestGenerationForMethod(project, editor, methodContext)
            }
            Instructions.COMMIT_ALL -> openGitUI(project)
            Instructions.FILL_CODE -> fillCodeAtCursor(project, editor)
            Instructions.OPEN_GIT_UI -> openGitUI(project)
            else -> {
                if (methodContext == null) {
                    Messages.showWarningDialog(project, "Place cursor inside a method for this action", "No Method Found")
                    return
                }
                openChatForRewrite(project, methodContext, instruction)
            }
        }
    }

    private fun openGitUI(project: Project) {
        try {
            val browserService = com.zps.zest.browser.WebBrowserService.getInstance(project)
            val browserPanel = browserService.browserPanel
            if (browserPanel != null) {
                browserPanel.openGitUI()
            } else {
                Messages.showWarningDialog(project, "Git UI not available. Please open the Zest browser first.", "Browser Not Ready")
            }
        } catch (e: Exception) {
            logger.error("Failed to open Git UI", e)
            Messages.showErrorDialog(project, "Failed to open Git UI: ${e.message}", "Error")
        }
    }

    private fun fillCodeAtCursor(project: Project, editor: com.intellij.openapi.editor.Editor) {
        try {
            val chatService = project.getService(com.zps.zest.chatui.ChatUIService::class.java)
            val offset = editor.caretModel.offset
            val document = editor.document
            val lineNumber = document.getLineNumber(offset)
            val lineText = document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(lineNumber),
                    document.getLineEndOffset(lineNumber)
                )
            ).trim()

            val message = "Continue writing code at my cursor position. Current line: `$lineText`"
            chatService.clearConversation()
            chatService.openChatWithMessage(message, autoSend = true)
        } catch (e: Exception) {
            logger.error("Failed to trigger code fill", e)
            Messages.showErrorDialog(project, "Failed to start code completion: ${e.message}", "Error")
        }
    }

    /**
     * Open chat UI with method context and instruction
     */
    private fun openChatForRewrite(project: Project, methodContext: MethodContext, instruction: String) {
        try {
            val chatService = project.getService(com.zps.zest.chatui.ChatUIService::class.java)
            val metricsService = ZestQuickActionMetricsService.getInstance(project)

            // Generate unique rewrite ID for tracking
            val rewriteId = "rewrite-${UUID.randomUUID()}"

            // Track quick action request
            val customInstruction = if (!instruction.startsWith("__")) instruction else null
            metricsService.trackRewriteRequested(
                rewriteId = rewriteId,
                methodName = methodContext.methodName,
                language = methodContext.language,
                fileType = methodContext.fileName.substringAfterLast('.'),
                actualModel = "local-model",  // Default model for quick actions
                customInstruction = customInstruction
            )

            // Prepare chat for method rewrite
            chatService.clearConversation();
            chatService.prepareForMethodRewrite()

            // Format method context as markdown message
            val formattedMessage = MethodContextFormatter.format(methodContext, instruction)

            // Open chat with pre-filled message and auto-send
            chatService.openChatWithMessage(formattedMessage, autoSend = true)

            logger.info("Opened chat for method rewrite: ${methodContext.methodName}, rewriteId: $rewriteId")
        } catch (e: Exception) {
            logger.error("Failed to open chat for method rewrite", e)
            Messages.showErrorDialog(
                project,
                "Failed to open chat: ${e.message}",
                "Chat Error"
            )
        }
    }


    /**
     * Simplified dialog for quick action prompt selection - no progress tracking
     */
    class SimplePromptDialog(
        private val project: Project,
        private val methodContext: MethodContext?,
        private val onInstructionSelected: ((String) -> Unit)? = null
    ) : DialogWrapper(project) {

        private val inputField = JBTextField()
        private val customTextArea = JBTextArea()
        private var isCustomMode = false
        private var currentSelection = 0
        private var isShiftPressed = false
        private val hasMethod = methodContext != null

        private val builtInOptions = generateContextOptions()
        private var customPrompts = loadCustomPrompts()
        private val builtInLabels = mutableListOf<JLabel>()
        private val customLabels = mutableListOf<JLabel>()

        init {
            title = "Quick Action - Select Task"
            setupKeyListener()
            setupCustomTextArea()
            init()

            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
            }
        }

        private fun setupKeyListener() {
            inputField.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {
                    val char = e.keyChar
                    when {
                        char.code == 27 -> close(CANCEL_EXIT_CODE)
                        !char.isDigit() && char.code >= 32 && char != '\n' && char != '\r' && !isCustomMode -> {
                            switchToCustomMode(char.toString())
                        }
                    }
                }

                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SHIFT) {
                        isShiftPressed = true
                        updateHighlighting()
                    }

                    if (!isCustomMode) {
                        when (e.keyCode) {
                            KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
                            KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9 -> {
                                val numberPressed = (e.keyCode - KeyEvent.VK_0)

                                if (e.isShiftDown && numberPressed in 1..9) {
                                    val customPrompt = customPrompts.find { it.shortcut == numberPressed }
                                    if (customPrompt != null) {
                                        close(OK_EXIT_CODE)
                                        onInstructionSelected?.invoke(customPrompt.prompt)
                                        e.consume()
                                    }
                                } else if (!e.isShiftDown && numberPressed >= 1) {
                                    val optionIndex = numberPressed - 1
                                    if (optionIndex < builtInOptions.size) {
                                        val option = builtInOptions[optionIndex]
                                        if (!option.isDisabled(hasMethod)) {
                                            close(OK_EXIT_CODE)
                                            onInstructionSelected?.invoke(option.instruction)
                                            e.consume()
                                        }
                                    }
                                }
                            }

                            KeyEvent.VK_UP -> {
                                currentSelection = if (!e.isShiftDown) {
                                    (currentSelection - 1 + builtInOptions.size) % builtInOptions.size
                                } else {
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
                                    val option = builtInOptions[currentSelection]
                                    if (!option.isDisabled(hasMethod)) {
                                        close(OK_EXIT_CODE)
                                        onInstructionSelected?.invoke(option.instruction)
                                    }
                                } else if (e.isShiftDown && currentSelection < customPrompts.size) {
                                    close(OK_EXIT_CODE)
                                    onInstructionSelected?.invoke(customPrompts[currentSelection].prompt)
                                }
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    } else {
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

        private fun setupCustomTextArea() {
            customTextArea.lineWrap = true
            customTextArea.wrapStyleWord = true
            customTextArea.rows = 3
            customTextArea.columns = 50
            customTextArea.border = JBUI.Borders.empty(5)

            customTextArea.addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {}

                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> {
                            if (e.isControlDown) {
                                val customInstruction = customTextArea.text.trim()
                                if (customInstruction.isNotEmpty()) {
                                    close(OK_EXIT_CODE)
                                    onInstructionSelected?.invoke(customInstruction)
                                }
                                e.consume()
                            }
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

        private fun generateContextOptions(): List<RewriteOption> {
            val globalActions = listOf(
                RewriteOption("📝", "Fill code at cursor", Instructions.FILL_CODE, requiresMethod = false),
                RewriteOption("🚀", "Commit and push all", Instructions.COMMIT_ALL, requiresMethod = false)
            )

            if (methodContext == null) {
                return globalActions + listOf(
                    RewriteOption("🧪", "Write unit test", Instructions.WRITE_TEST, requiresMethod = true, disabledReason = "requires cursor in method"),
                    RewriteOption("🔧", "Refactor method", "Refactor this method", requiresMethod = true, disabledReason = "requires cursor in method"),
                    RewriteOption("📋", "Add logging", "Add logging statements", requiresMethod = true, disabledReason = "requires cursor in method"),
                    RewriteOption("🛡️", "Add error handling", "Add try-catch blocks", requiresMethod = true, disabledReason = "requires cursor in method")
                )
            }

            val methodContent = methodContext.methodContent
            val methodName = methodContext.methodName
            val methodActions = when {
                isEmptyOrMinimalMethod(methodContent) -> listOf(
                    RewriteOption("✨", "Complete implementation", "Implement this $methodName method with proper functionality"),
                    RewriteOption("📋", "Add logging only", "Add logging statements to track method execution"),
                    RewriteOption("🛡️", "Add error handling", "Add try-catch blocks and input validation"),
                    RewriteOption("🧪", "Write unit test", Instructions.WRITE_TEST)
                )

                hasTodoComment(methodContent) -> {
                    val todoText = extractTodoText(methodContent)
                    val todoInstruction = if (todoText.isNotEmpty()) "Complete TODO: $todoText" else "Complete TODO functionality"
                    listOf(
                        RewriteOption("✅", "Complete TODO", todoInstruction),
                        RewriteOption("📋", "Add logging", "Add logging statements"),
                        RewriteOption("🛡️", "Add error handling", "Add try-catch blocks"),
                        RewriteOption("🧪", "Write unit test", Instructions.WRITE_TEST)
                    )
                }

                isComplexMethod(methodContent) -> listOf(
                    RewriteOption("✂️", "Split into smaller methods", "Break this method into smaller, focused methods"),
                    RewriteOption("🐛", "Add debug logging", "Add detailed logging for debugging"),
                    RewriteOption("⚡", "Optimize performance", "Apply performance optimizations"),
                    RewriteOption("🧪", "Write unit test", Instructions.WRITE_TEST)
                )

                else -> listOf(
                    RewriteOption("🔧", "Fix specific issue", "Fix bugs or issues in this method"),
                    RewriteOption("📋", "Add logging", "Add logging statements"),
                    RewriteOption("🛡️", "Add error handling", "Add try-catch blocks"),
                    RewriteOption("🧪", "Write unit test", Instructions.WRITE_TEST)
                )
            }

            return globalActions + methodActions
        }

        private fun loadCustomPrompts(): List<ZestCustomPromptsLoader.CustomPrompt> {
            return try {
                val loader = ZestCustomPromptsLoader.getInstance(project)
                loader.loadCustomPrompts()
            } catch (e: Exception) {
                logger.warn("Failed to load custom prompts", e)
                emptyList()
            }
        }

        private fun updateHighlighting() {
            val defaultTextColor = UIManager.getColor("Label.foreground") ?: JBColor.BLACK

            builtInLabels.forEachIndexed { index, label ->
                if (!isShiftPressed && index == currentSelection) {
                    label.font = label.font.deriveFont(Font.BOLD)
                } else {
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                label.foreground = defaultTextColor
            }

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
            return if (isCustomMode) {
                createCustomPanel()
            } else {
                createOptionsPanel()
            }
        }

        private fun createOptionsPanel(): JComponent {
            val mainPanel = JPanel(BorderLayout())

            val columnsPanel = JPanel(GridLayout(1, 2, 20, 0))
            columnsPanel.border = JBUI.Borders.empty(5)

            val leftPanel = JPanel()
            leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
            leftPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1),
                JBUI.Borders.empty(10)
            )

            val leftHeader = JLabel("Quick Tasks (1-4)")
            leftHeader.font = leftHeader.font.deriveFont(Font.BOLD, 14f)
            leftHeader.alignmentX = Component.LEFT_ALIGNMENT
            leftPanel.add(leftHeader)
            leftPanel.add(Box.createVerticalStrut(10))

            builtInLabels.clear()
            builtInOptions.forEachIndexed { index, option ->
                val isDisabled = option.isDisabled(hasMethod)
                val labelPanel = JPanel()
                labelPanel.layout = BoxLayout(labelPanel, BoxLayout.Y_AXIS)
                labelPanel.alignmentX = Component.LEFT_ALIGNMENT

                val mainLabel = JLabel("${index + 1}. ${option.icon} ${option.title}")
                mainLabel.font = mainLabel.font.deriveFont(Font.PLAIN, 13f)
                mainLabel.alignmentX = Component.LEFT_ALIGNMENT

                if (isDisabled) {
                    mainLabel.foreground = JBColor.GRAY
                    val reasonLabel = JLabel("   (${option.getDisabledReason(hasMethod)})")
                    reasonLabel.font = reasonLabel.font.deriveFont(Font.ITALIC, 11f)
                    reasonLabel.foreground = JBColor.GRAY
                    reasonLabel.alignmentX = Component.LEFT_ALIGNMENT
                    labelPanel.add(mainLabel)
                    labelPanel.add(reasonLabel)
                } else {
                    labelPanel.add(mainLabel)
                }

                builtInLabels.add(mainLabel)
                leftPanel.add(labelPanel)
                leftPanel.add(Box.createVerticalStrut(5))
            }

            val rightPanel = JPanel()
            rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)
            rightPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1),
                JBUI.Borders.empty(10)
            )

            val rightHeader = JLabel("Custom Tasks (Shift+1-9)")
            rightHeader.font = rightHeader.font.deriveFont(Font.BOLD, 14f)
            rightHeader.alignmentX = Component.LEFT_ALIGNMENT
            rightPanel.add(rightHeader)
            rightPanel.add(Box.createVerticalStrut(10))

            customLabels.clear()
            if (customPrompts.isEmpty()) {
                val emptyLabel = JLabel("No custom tasks defined")
                emptyLabel.font = emptyLabel.font.deriveFont(Font.ITALIC)
                emptyLabel.foreground = JBColor.GRAY
                emptyLabel.alignmentX = Component.LEFT_ALIGNMENT
                rightPanel.add(emptyLabel)
            } else {
                customPrompts.forEach { prompt ->
                    val label = JLabel("Shift+${prompt.shortcut}. ${prompt.title}")
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

            val bottomPanel = JPanel()
            bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
            bottomPanel.border = JBUI.Borders.empty(10, 5, 0, 5)

            val instructionLabel = JLabel("Press number for quick task, Shift+number for custom, or type for custom text")
            instructionLabel.font = instructionLabel.font.deriveFont(Font.PLAIN, 11f)
            instructionLabel.foreground = JBColor.GRAY
            instructionLabel.alignmentX = Component.CENTER_ALIGNMENT
            bottomPanel.add(instructionLabel)

            mainPanel.add(bottomPanel, BorderLayout.SOUTH)

            updateHighlighting()

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

            val label = JLabel("Custom Task:")
            label.font = label.font.deriveFont(Font.BOLD)
            headerPanel.add(label)
            headerPanel.add(Box.createVerticalStrut(5))

            val infoLabel = JLabel("Describe exactly what to do (e.g., 'add null check for parameter x')")
            infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 11f)
            infoLabel.foreground = JBColor.GRAY
            headerPanel.add(infoLabel)

            panel.add(headerPanel, BorderLayout.NORTH)

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

            panel.add(tipPanel, BorderLayout.SOUTH)

            return panel
        }

        private fun switchToCustomMode(initialText: String = "") {
            isCustomMode = true
            customTextArea.text = initialText

            val content = contentPanel
            if (content != null) {
                content.removeAll()
                content.add(createCenterPanel())
                content.revalidate()
                content.repaint()
                pack()
            }

            SwingUtilities.invokeLater {
                customTextArea.requestFocusInWindow()
                customTextArea.caretPosition = customTextArea.text.length
            }
        }

        override fun doOKAction() {
            if (isCustomMode) {
                val customInstruction = customTextArea.text.trim()
                if (customInstruction.isNotEmpty()) {
                    close(OK_EXIT_CODE)
                    onInstructionSelected?.invoke(customInstruction)
                } else {
                    super.doOKAction()
                }
            } else {
                if (currentSelection < builtInOptions.size) {
                    val option = builtInOptions[currentSelection]
                    if (!option.isDisabled(hasMethod)) {
                        close(OK_EXIT_CODE)
                        onInstructionSelected?.invoke(option.instruction)
                    }
                } else {
                    super.doOKAction()
                }
            }
        }

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
            val instruction: String,
            val requiresMethod: Boolean = true,
            val disabledReason: String? = null
        ) {
            fun isDisabled(hasMethod: Boolean): Boolean = requiresMethod && !hasMethod
            fun getDisabledReason(hasMethod: Boolean): String? =
                if (isDisabled(hasMethod)) disabledReason else null
        }

        override fun createSouthPanel(): JComponent? = null
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        val isAvailable = project != null && editor != null && !editor.isDisposed

        e.presentation.isEnabledAndVisible = isAvailable
        e.presentation.isEnabled = isAvailable
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override val priority: Int = 17
    
    /**
     * Trigger test generation for a specific method using the actual test generation system
     */
    private fun triggerTestGenerationForMethod(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: MethodContext
    ) {
        logger.info("=== STARTING TEST GENERATION FOR METHOD: ${methodContext.methodName} ===")
        try {
            // Find the PsiMethod at the current cursor position
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile == null) {
                logger.warn("Could not find PsiFile for current editor")
                Messages.showWarningDialog(
                    project,
                    "Could not find the source file for test generation",
                    "Test Generation Error"
                )
                return
            }
            
            // Find the method containing the cursor position
            var targetMethod: PsiMethod? = null
            val offset = editor.caretModel.offset
            
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiMethod) {
                        val methodStart = element.textRange.startOffset
                        val methodEnd = element.textRange.endOffset
                        if (offset >= methodStart && offset <= methodEnd) {
                            if (element.name == methodContext.methodName) {
                                targetMethod = element
                                return // Found the method, stop searching
                            }
                        }
                    }
                    super.visitElement(element)
                }
            })
            
            if (targetMethod == null) {
                logger.warn("Could not find PsiMethod for ${methodContext.methodName}")
                Messages.showWarningDialog(
                    project,
                    "Could not find the method '${methodContext.methodName}' for test generation",
                    "Method Not Found"
                )
                return
            }
            
            // Use the GenerateTestAction to create test generation request for this single method
            val generateTestAction = GenerateTestAction()
            generateTestAction.createTestGenerationRequest(project, psiFile, null, targetMethod)
            
            logger.info("Successfully triggered test generation for method: ${methodContext.methodName}")
            
        } catch (e: Exception) {
            logger.error("Failed to trigger test generation for method ${methodContext.methodName}", e)
            Messages.showErrorDialog(
                project,
                "Failed to start test generation: ${e.message}",
                "Test Generation Error"
            )
        }
    }

}

/**
 * Simple method finder to replace deleted ZestMethodContextCollector
 */
private fun findMethodAtOffset(editor: Editor, offset: Int, project: Project): MethodContext? {
    return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<MethodContext?> {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
        val element = psiFile.findElementAt(offset) ?: return@runReadAction null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@runReadAction null
        
        val methodText = method.text ?: ""
        val virtualFile = psiFile.virtualFile
        val fileName = virtualFile?.name ?: "unknown"

        // Get relative path from project root
        val filePath = if (virtualFile != null && project.basePath != null) {
            val projectPath = project.basePath!!.replace('\\', '/')
            val fullPath = virtualFile.path.replace('\\', '/')
            if (fullPath.startsWith(projectPath)) {
                fullPath.substring(projectPath.length + 1)
            } else {
                fileName
            }
        } else {
            fileName
        }

        val language = if (fileName.endsWith(".java")) "java" else if (fileName.endsWith(".kt")) "kotlin" else "javascript"

        MethodContext(
            methodName = method.name,
            methodStartOffset = method.textRange.startOffset,
            methodEndOffset = method.textRange.endOffset,
            methodContent = methodText,
            language = language,
            fileName = filePath,
            isCocos2dx = false,
            relatedClasses = emptyMap(),
            methodSignature = method.name,
            containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name
        )
    }
}