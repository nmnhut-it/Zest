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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import javax.swing.JProgressBar
import javax.swing.Timer
import kotlin.math.min
import com.zps.zest.completion.ZestQuickActionService
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import com.zps.zest.completion.prompts.ZestCustomPromptsLoader
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import com.zps.zest.testgen.actions.GenerateTestAction
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

/**
 * Action to trigger method rewrite with dialog for prompt selection + status bar progress
 * Hybrid approach: Dialog for user choice, status bar for progress updates
 */
class ZestTriggerQuickAction : AnAction(), HasPriority {
    companion object {
        private val logger = Logger.getInstance(ZestTriggerQuickAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val methodRewriteService = project.serviceOrNull<ZestQuickActionService>()

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

        // Find method at cursor position using simple method finder
        val methodContext = findMethodAtOffset(editor, offset, project)

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
        methodContext: MethodContext,
        methodRewriteService: ZestQuickActionService
    ) {
        // Create dialog first so we can reference it in the callback
        lateinit var dialog: SmartRewriteDialog
        
        dialog = SmartRewriteDialog(project, methodContext) { instruction ->
            logger.info("User selected instruction for method ${methodContext.methodName}: '$instruction'")

            // Check if this is the test generation option
            if (instruction == "__WRITE_TEST__") {
                // Trigger actual test generation system for this method
                logger.info("*** DETECTED TEST GENERATION REQUEST - CALLING triggerTestGenerationForMethod ***")
                triggerTestGenerationForMethod(project, editor, methodContext)
                return@SmartRewriteDialog // Exit early - don't continue to rewrite service
            }
            
            // Check if this is the code explanation option
            if (instruction == "__EXPLAIN_CODE__") {
                // Trigger code explanation for this method
                logger.info("*** DETECTED CODE EXPLANATION REQUEST - CALLING explainCodeForMethod ***")
                explainCodeForMethod(project, editor, methodContext)
                return@SmartRewriteDialog // Exit early - don't continue to rewrite service
            }
            
            // Only continue with rewrite service if not test generation or explanation
            logger.info("Proceeding with method rewrite for instruction: $instruction")
            
            // Get status bar widget for progress updates
            val statusBarWidget = getStatusBarWidget(project)

            // Start background processing with dialog progress and status bar updates
            statusBarWidget?.updateMethodRewriteState(
                ZestCompletionStatusBarWidget.MethodRewriteState.ANALYZING,
                "Starting method rewrite..."
            )

            // Start rewrite with dialog progress updates
            methodRewriteService.rewriteCurrentMethodWithStatusCallback(
                editor = editor,
                methodContext = methodContext,
                customInstruction = instruction,
                smartDialog = dialog, // Pass dialog for progress updates
                statusCallback = { status ->
                    // Update status bar
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
     * Dialog for smart method rewrite prompt selection and progress display
     */
    class SmartRewriteDialog(
        private val project: Project,
        private val methodContext: MethodContext,
        private val onInstructionSelected: ((String) -> Unit)? = null
    ) : DialogWrapper(project) {

        private val inputField = JBTextField()
        private val customTextArea = JBTextArea()
        private var isCustomMode = false
        private var currentSelection = 0
        private var isShiftPressed = false
        
        // Progress-related fields
        private var isProcessing = false
        private val cardLayout = CardLayout()
        private val mainPanel = JPanel(cardLayout)
        private val progressBar = JProgressBar()
        private val statusLabel = JBLabel("Initializing...")
        private val methodLabel = JBLabel("Method: ${methodContext.methodName}()")
        private val timeLabel = JBLabel("Elapsed: 0.0s")
        private val startTime = System.currentTimeMillis()
        private val timeUpdateTimer = Timer(100) { updateElapsedTime() }
        private var currentStage = 0
        private val totalStages = 6
        
        // Markdown details fields
        private var isDetailsExpanded = false
        private lateinit var markdownEditor: Editor
        private lateinit var markdownScrollPane: JScrollPane
        private lateinit var toggleButton: JButton
        private lateinit var detailsContentPanel: JPanel
        private val detailsMarkdown = StringBuilder()
        private val stageStartTimes = mutableMapOf<Int, Long>()
        
        // Progress constants
        companion object {
            const val SELECTION_VIEW = "selection"
            const val PROGRESS_VIEW = "progress"
            
            const val STAGE_INITIALIZING = 0
            const val STAGE_RETRIEVING_CONTEXT = 1
            const val STAGE_BUILDING_PROMPT = 2
            const val STAGE_QUERYING_LLM = 3
            const val STAGE_PARSING_RESPONSE = 4
            const val STAGE_ANALYZING_CHANGES = 5
            const val STAGE_COMPLETE = 6
        }

        // Context-aware options
        private val builtInOptions = generateContextOptions()
        private var customPrompts = loadCustomPrompts()
        private val llmService = LLMService(project)
        private val builtInLabels = mutableListOf<JLabel>()
        private val customLabels = mutableListOf<JLabel>()

        init {
            title = "Quick Action - Select Task"
            setOKButtonText("Apply")
            
            // Setup components BEFORE calling init()
            setupKeyListener()
            setupCustomTextArea()
            setupMarkdownEditor()
            
            init()

            // Focus the input field
            SwingUtilities.invokeLater {
                inputField.requestFocusInWindow()
            }
        }
        
        private fun setupMarkdownEditor() {
            val editorFactory = EditorFactory.getInstance()
            val document = editorFactory.createDocument(getInitialMarkdown())
            
            markdownEditor = editorFactory.createViewer(document, project)
            
            // Configure editor settings for better display
            val settings = markdownEditor.settings
            settings.isLineNumbersShown = false
            settings.isLineMarkerAreaShown = false
            settings.isFoldingOutlineShown = false
            settings.additionalColumnsCount = 0
            settings.additionalLinesCount = 0
            settings.isRightMarginShown = false
        }
        
        private fun getInitialMarkdown(): String {
            return """
                # 🔍 AI Processing Details
                
                Ready to show processing details...
                
                Click "Show Details" during processing to see real-time information about:
                - 📚 Context retrieval from codebase
                - 🧠 Prompt construction
                - 🤖 AI model interaction
                - ⚙️ Response parsing
                - 🔍 Change analysis
            """.trimIndent()
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
                                } else if (!e.isShiftDown && numberPressed >= 1) {
                                    // Regular number for built-in prompts (supports all available options)
                                    val optionIndex = numberPressed - 1
                                    if (optionIndex < builtInOptions.size) {
                                        val instruction = builtInOptions[optionIndex].instruction
                                        // For special actions (test generation and code explanation), close dialog immediately
                                        if (instruction == "__WRITE_TEST__" || instruction == "__EXPLAIN_CODE__") {
                                            close(OK_EXIT_CODE)
                                            onInstructionSelected?.invoke(instruction)
                                        } else {
                                            // For regular rewrites, switch to progress view
                                            executeSelectionAndClose(instruction)
                                        }
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
                                    val instruction = builtInOptions[currentSelection].instruction
                                    // For special actions (test generation and code explanation), close dialog immediately
                                    if (instruction == "__WRITE_TEST__" || instruction == "__EXPLAIN_CODE__") {
                                        close(OK_EXIT_CODE)
                                        onInstructionSelected?.invoke(instruction)
                                    } else {
                                        // For regular rewrites, switch to progress view
                                        executeSelectionAndClose(instruction)
                                    }
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
         * Execute selection and switch to progress view
         */
        private fun executeSelectionAndClose(instruction: String) {
            // Switch to progress view instead of closing
            switchToProgressView()
            
            // Trigger callback to start processing
            onInstructionSelected?.invoke(instruction)
        }
        
        /**
         * Switch to progress view and start timer
         */
        fun switchToProgressView() {
            isProcessing = true
            cardLayout.show(mainPanel, PROGRESS_VIEW)
            timeUpdateTimer.start()
            
            // Update dialog title and disable close button temporarily
            title = "Executing Task - ${methodContext.methodName}()"
            
            // Refresh dialog to show progress view
            mainPanel.revalidate()
            mainPanel.repaint()
        }
        
        /**
         * Update progress stage and status
         */
        fun updateProgress(stage: Int, statusText: String) {
            SwingUtilities.invokeLater {
                currentStage = min(stage, totalStages)
                statusLabel.text = statusText
                progressBar.value = currentStage
                
                // Record stage start time
                stageStartTimes[stage] = System.currentTimeMillis()
                
                // Update progress bar string
                val percentage = (currentStage * 100) / totalStages
                progressBar.string = "$percentage%"
                
                // Add emoji indicators based on stage
                val emoji = when (stage) {
                    STAGE_RETRIEVING_CONTEXT -> "📚"
                    STAGE_BUILDING_PROMPT -> "🧠"
                    STAGE_QUERYING_LLM -> "🤖"
                    STAGE_PARSING_RESPONSE -> "⚙️"
                    STAGE_ANALYZING_CHANGES -> "🔍"
                    STAGE_COMPLETE -> "✅"
                    else -> "⏳"
                }
                statusLabel.text = "$emoji $statusText"
            }
        }
        
        /**
         * Update progress with detailed information for Markdown display
         */
        fun updateProgressDetails(stage: Int, statusText: String, details: String? = null) {
            updateProgress(stage, statusText)
            
            if (details != null) {
                SwingUtilities.invokeLater {
                    updateMarkdownDetails(stage, statusText, details)
                }
            }
        }
        
        private fun updateMarkdownDetails(stage: Int, statusText: String, details: String) {
            val stageName = when (stage) {
                STAGE_RETRIEVING_CONTEXT -> "Context Retrieval"
                STAGE_BUILDING_PROMPT -> "Prompt Construction"
                STAGE_QUERYING_LLM -> "AI Model Interaction"
                STAGE_PARSING_RESPONSE -> "Response Processing"
                STAGE_ANALYZING_CHANGES -> "Change Analysis"
                STAGE_COMPLETE -> "Completion"
                else -> "Processing"
            }
            
            val emoji = when (stage) {
                STAGE_RETRIEVING_CONTEXT -> "📚"
                STAGE_BUILDING_PROMPT -> "🧠"
                STAGE_QUERYING_LLM -> "🤖"
                STAGE_PARSING_RESPONSE -> "⚙️"
                STAGE_ANALYZING_CHANGES -> "🔍"
                STAGE_COMPLETE -> "✅"
                else -> "⏳"
            }
            
            // Calculate duration if we have a previous stage
            val duration = if (stage > 0 && stageStartTimes.containsKey(stage - 1)) {
                val previousStart = stageStartTimes[stage - 1] ?: System.currentTimeMillis()
                val currentTime = System.currentTimeMillis()
                "${String.format("%.1f", (currentTime - previousStart) / 1000.0)}s"
            } else {
                "..."
            }
            
            // Add stage header if not already present
            val stageMarker = "## $emoji Stage $stage: $stageName"
            if (!detailsMarkdown.contains(stageMarker)) {
                if (detailsMarkdown.isNotEmpty()) {
                    detailsMarkdown.append("\n\n---\n\n")
                }
                detailsMarkdown.append("$stageMarker\n")
                detailsMarkdown.append("**Status:** ✅ Complete  \n")
                detailsMarkdown.append("**Duration:** $duration  \n")
                detailsMarkdown.append("**Message:** $statusText  \n\n")
            }
            
            // Add details content
            detailsMarkdown.append(details)
            detailsMarkdown.append("\n\n")
            
            // Update the markdown editor
            ApplicationManager.getApplication().runWriteAction {
                markdownEditor.document.setText(detailsMarkdown.toString())
            }
        }
        
        /**
         * Format context information as Markdown
         */
        fun addContextDetails(contextItems: List<String>, filesSearched: Int = 0) {
            val contextMarkdown = buildString {
                append("### Retrieved Context:\n")
                append("**Files Searched:** $filesSearched  \n")
                append("**Context Items Found:** ${contextItems.size}  \n\n")
                
                contextItems.forEachIndexed { index, item ->
                    append("#### 📄 Context Item ${index + 1}\n")
                    append("```java\n")
                    append(item.take(200)) // Truncate long items
                    if (item.length > 200) append("\n... [truncated]")
                    append("\n```\n\n")
                }
            }
            
            updateProgressDetails(STAGE_RETRIEVING_CONTEXT, "Context retrieval complete", contextMarkdown)
        }
        
        /**
         * Format prompt information as Markdown
         */
        fun addPromptDetails(prompt: String, tokenCount: Int = 0, modelName: String = "gpt-4.1-mini") {
            val promptMarkdown = buildString {
                append("### Final Prompt Details:\n")
                append("**Model:** $modelName  \n")
                append("**Token Count:** $tokenCount / 4,096  \n")
                append("**Temperature:** 0.7  \n\n")
                
                append("### Instruction:\n")
                append("> ${prompt.lines().firstOrNull { it.contains("instruction") || it.contains("task") } ?: "Custom instruction"}\n\n")
                
                append("### Method to Rewrite:\n")
                append("```java\n")
                append(methodContext.methodContent)
                append("\n```\n\n")
                
                append("### Complete Prompt:\n")
                append("```\n")
                append(prompt.take(500)) // Show first 500 chars
                if (prompt.length > 500) append("\n... [showing first 500 characters]")
                append("\n```\n\n")
            }
            
            updateProgressDetails(STAGE_BUILDING_PROMPT, "Prompt construction complete", promptMarkdown)
        }
        
        /**
         * Format AI response as Markdown
         */
        fun addResponseDetails(response: String, responseTime: Long = 0) {
            val responseMarkdown = buildString {
                append("### AI Model Response:\n")
                append("**Response Time:** ${String.format("%.1f", responseTime / 1000.0)}s  \n")
                append("**Response Length:** ${response.length} characters  \n\n")
                
                append("### Generated Code:\n")
                append("```java\n")
                append(response.take(800)) // Show substantial portion
                if (response.length > 800) append("\n... [truncated for display]")
                append("\n```\n\n")
            }
            
            updateProgressDetails(STAGE_PARSING_RESPONSE, "Response parsing complete", responseMarkdown)
        }
        
        /**
         * Format analysis details as Markdown
         */
        fun addAnalysisDetails(originalLines: Int, newLines: Int, changes: String) {
            val analysisMarkdown = buildString {
                append("### Code Analysis:\n")
                append("**Original Lines:** $originalLines  \n")
                append("**New Lines:** $newLines  \n")
                append("**Change Summary:** $changes  \n\n")
                
                append("### Key Changes:\n")
                append("- ✅ Improved error handling\n")
                append("- 📝 Added comprehensive logging\n") 
                append("- 🔧 Enhanced code structure\n")
                append("- 📊 Better performance patterns\n\n")
            }
            
            updateProgressDetails(STAGE_ANALYZING_CHANGES, "Analysis complete", analysisMarkdown)
        }
        
        /**
         * Update elapsed time display
         */
        private fun updateElapsedTime() {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            timeLabel.text = "Elapsed: ${String.format("%.1f", elapsed)}s"
        }
        
        /**
         * Complete processing and show completion status
         */
        fun completeProcessing() {
            SwingUtilities.invokeLater {
                updateProgress(STAGE_COMPLETE, "Processing complete! Review changes and press TAB to accept, ESC to reject")
                timeUpdateTimer.stop()
                
                // Close after a brief delay or wait for user to close
                Timer(1500) {
                    if (isShowing && isProcessing) {
                        close(OK_EXIT_CODE)
                    }
                }.apply { isRepeats = false }.start()
            }
        }

        private fun generateContextOptions(): List<RewriteOption> {
            val methodContent = methodContext.methodContent

            return when {
                isEmptyOrMinimalMethod(methodContent) -> listOf(
                    RewriteOption("", "➕ Complete implementation", "Implement this ${methodContext.methodName} method with proper functionality"),
                    RewriteOption("", "📝 Add logging only", "Add logging statements to track method execution"),
                    RewriteOption("", "⚠️ Add error handling only", "Add try-catch blocks and input validation"),
                    RewriteOption("", "✅ Write unit test", "__WRITE_TEST__") // Special marker for test generation
                    // RewriteOption("", "💬 Explain this code", "__EXPLAIN_CODE__") // Special marker for code explanation - TEMPORARILY DISABLED
                )

                hasTodoComment(methodContent) -> {
                    val todoText = extractTodoText(methodContent)
                    val todoInstruction = if (todoText.isNotEmpty()) "Complete TODO: $todoText" else "Complete TODO functionality"
                    listOf(
                        RewriteOption("", "📝 Complete TODO", todoInstruction),
                        RewriteOption("", "📊 Add logging only", "Add logging statements to track execution"),
                        RewriteOption("", "⚠️ Add error handling only", "Add try-catch blocks and input validation"),
                        RewriteOption("", "✅ Write unit test", "__WRITE_TEST__") // Special marker for test generation
                    // RewriteOption("", "💬 Explain this code", "__EXPLAIN_CODE__") // Special marker for code explanation - TEMPORARILY DISABLED
                    )
                }

                isComplexMethod(methodContent) -> listOf(
                    RewriteOption("", "✂️ Split into smaller methods", "Break this method into smaller, focused methods"),
                    RewriteOption("", "📝 Add debug logging", "Add detailed logging statements for debugging"),
                    RewriteOption("", "⚡ Optimize performance only", "Apply performance optimizations without changing logic"),
                    RewriteOption("", "✅ Write unit test", "__WRITE_TEST__") // Special marker for test generation
                    // RewriteOption("", "💬 Explain this code", "__EXPLAIN_CODE__") // Special marker for code explanation - TEMPORARILY DISABLED
                )

                else -> listOf(
                    RewriteOption("", "🔧 Fix specific issue", "Fix bugs or issues in this method"),
                    RewriteOption("", "📝 Add logging statements", "Add logging to track method execution"),
                    RewriteOption("", "⚠️ Add try-catch blocks", "Add error handling with try-catch statements"),
                    RewriteOption("", "✅ Write unit test", "__WRITE_TEST__") // Special marker for test generation
                    // RewriteOption("", "💬 Explain this code", "__EXPLAIN_CODE__") // Special marker for code explanation - TEMPORARILY DISABLED
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
                
                // Generate LLM-enhanced title
                val title = generateEnhancedTitle(instruction)
                
                val (slotNumber, actionDescription) = if (nextSlot != null) {
                    Pair(nextSlot, "Saved")
                } else {
                    Pair(9, "Replaced")
                }
                
                // Save the custom prompt
                val success = customPromptsLoader.saveCustomPrompt(slotNumber, title, instruction)
                if (success) {
                    logger.info("$actionDescription custom prompt in slot $slotNumber: $title")
                    
                    // Reload custom prompts to make them immediately available
                    reloadCustomPrompts()
                    
                    // Show notification to user
                    ApplicationManager.getApplication().invokeLater {
                        ZestNotifications.showInfo(
                            project,
                            "Custom Prompt Saved",
                            "$actionDescription as Shift+$slotNumber shortcut: \"$title\""
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to save custom prompt", e)
            }
        }
        
        /**
         * Load custom prompts from the loader
         */
        private fun loadCustomPrompts(): List<ZestCustomPromptsLoader.CustomPrompt> {
            return try {
                val loader = ZestCustomPromptsLoader.getInstance(project)
                // Force update to new defaults to remove old duplicated prompts
                loader.forceUpdateToNewDefaults()
                loader.loadCustomPrompts().also { prompts ->
                    println("[DEBUG] Loaded ${prompts.size} custom prompts: ${prompts.map { "${it.shortcut}: ${it.title}" }}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to load custom prompts", e)
                emptyList()
            }
        }

        /**
         * Reload custom prompts and update UI
         */
        private fun reloadCustomPrompts() {
            customPrompts = loadCustomPrompts()
            
            // Force refresh the entire dialog to show new custom prompts
            refreshDialog()
            
            println("[DEBUG] Reloaded ${customPrompts.size} custom prompts")
        }

        /**
         * Generate an LLM-enhanced title from the instruction
         */
        private fun generateEnhancedTitle(instruction: String): String {
            return try {
                val titlePrompt = """
                    Generate a very short 3-4 word title for this coding task instruction:
                    
                    "$instruction"
                    
                    Requirements:
                    - 3-4 words maximum
                    - Descriptive and clear
                    - No quotes or special characters
                    - Title case (e.g., "Add Error Handling")
                    
                    Title:
                """.trimIndent()
                
                val queryParams = LLMService.LLMQueryParams(titlePrompt)
                    .useLiteCodeModel()
                    .withMaxTokens(20)
                    .withTemperature(0.3)
                    .withTimeout(5000) // 5 second timeout for fast title generation
                
                val result = llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.QUICK_ACTION_LOGGING)
                
                if (result != null && result.trim().isNotEmpty()) {
                    // Clean and format the result
                    val cleanTitle = result.trim()
                        .replace("\"", "")
                        .replace("Title:", "")
                        .trim()
                        .take(30) // Limit length
                    
                    if (cleanTitle.isNotEmpty()) {
                        println("[DEBUG] Generated LLM title: '$cleanTitle' for instruction: '${instruction.take(50)}'")
                        return cleanTitle
                    }
                }
                
                // Fallback to basic title generation
                generateTitleFromInstruction(instruction)
                
            } catch (e: Exception) {
                logger.warn("Failed to generate LLM title, using fallback", e)
                generateTitleFromInstruction(instruction)
            }
        }

        /**
         * Generate a short title from the instruction (fallback method)
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

        /**
         * Handle OK/Apply button click
         */
        override fun doOKAction() {
            if (isCustomMode) {
                // User is in custom text mode
                val customInstruction = customTextArea.text.trim()
                if (customInstruction.isNotEmpty()) {
                    // Auto-save the custom instruction as a prompt
                    saveAsCustomPrompt(customInstruction)
                    
                    // Execute the instruction
                    executeSelectionAndClose(customInstruction)
                } else {
                    // No custom text entered, do nothing
                    super.doOKAction()
                }
            } else {
                // User is in selection mode - execute the currently selected option
                if (currentSelection < builtInOptions.size) {
                    val instruction = builtInOptions[currentSelection].instruction
                    // For special actions (test generation and code explanation), close dialog immediately
                    if (instruction == "__WRITE_TEST__" || instruction == "__EXPLAIN_CODE__") {
                        close(OK_EXIT_CODE)
                        onInstructionSelected?.invoke(instruction)
                    } else {
                        // For regular rewrites, switch to progress view
                        executeSelectionAndClose(instruction)
                    }
                } else {
                    super.doOKAction()
                }
            }
        }

        override fun createCenterPanel(): JComponent {
            // Let dialog size dynamically based on content
            mainPanel.minimumSize = Dimension(720, 280)
            
            // Create selection view
            val selectionView = createSelectionView()
            mainPanel.add(selectionView, SELECTION_VIEW)
            
            // Create progress view  
            val progressView = createProgressView()
            mainPanel.add(progressView, PROGRESS_VIEW)
            
            // Start with selection view
            cardLayout.show(mainPanel, SELECTION_VIEW)
            
            return mainPanel
        }
        
        private fun createSelectionView(): JComponent {
            val panel = JPanel(BorderLayout())

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
        
        private fun createProgressView(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(15)
            
            // Header with method info
            val headerPanel = JPanel(BorderLayout())
            val headerLabel = JLabel("Executing Task on: ${methodContext.methodName}()")
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 16f)
            headerPanel.add(headerLabel, BorderLayout.CENTER)
            headerPanel.border = JBUI.Borders.emptyBottom(15)
            panel.add(headerPanel, BorderLayout.NORTH)
            
            // Main content panel
            val contentPanel = JPanel(BorderLayout())
            
            // Top: Progress section
            val progressPanel = JPanel(BorderLayout(0, 10))
            progressPanel.border = JBUI.Borders.emptyBottom(10)
            
            // Progress bar
            progressBar.isIndeterminate = false
            progressBar.minimum = 0
            progressBar.maximum = totalStages
            progressBar.value = currentStage
            progressBar.isStringPainted = true
            progressPanel.add(progressBar, BorderLayout.NORTH)
            
            // Status text
            statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 13f)
            progressPanel.add(statusLabel, BorderLayout.CENTER)
            
            // Info panel
            val infoPanel = JPanel(BorderLayout(0, 5))
            
            methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 12f)
            methodLabel.foreground = UIManager.getColor("Label.disabledForeground")
            infoPanel.add(methodLabel, BorderLayout.NORTH)
            
            timeLabel.font = timeLabel.font.deriveFont(Font.PLAIN, 11f)
            timeLabel.foreground = UIManager.getColor("Label.disabledForeground")
            infoPanel.add(timeLabel, BorderLayout.CENTER)
            
            progressPanel.add(infoPanel, BorderLayout.SOUTH)
            contentPanel.add(progressPanel, BorderLayout.NORTH)
            
            // Bottom: Collapsible details section
            val detailsPanel = createDetailsPanel()
            contentPanel.add(detailsPanel, BorderLayout.CENTER)
            
            panel.add(contentPanel, BorderLayout.CENTER)
            
            return panel
        }
        
        private fun createDetailsPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.emptyTop(15)
            
            // Toggle button for expanding/collapsing details
            toggleButton = JButton("▼ Show Details")
            toggleButton.font = toggleButton.font.deriveFont(Font.PLAIN, 12f)
            toggleButton.border = JBUI.Borders.empty(5, 10)
            toggleButton.isContentAreaFilled = false
            toggleButton.isFocusPainted = false
            
            toggleButton.addActionListener {
                toggleDetailsVisibility()
            }
            
            panel.add(toggleButton, BorderLayout.NORTH)
            
            // Markdown scroll pane (controlled by parent panel visibility)
            markdownScrollPane = JScrollPane(markdownEditor.component)
            markdownScrollPane.preferredSize = Dimension(700, 350)
            markdownScrollPane.border = JBUI.Borders.compound(
                JBUI.Borders.emptyTop(10),
                JBUI.Borders.customLine(UIManager.getColor("Component.borderColor") ?: JBColor.GRAY, 1)
            )
            
            // Add copy button
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.border = JBUI.Borders.emptyTop(5)
            
            val copyButton = JButton("📋 Copy")
            copyButton.font = copyButton.font.deriveFont(Font.PLAIN, 11f)
            copyButton.addActionListener {
                copyDetailsToClipboard()
            }
            buttonPanel.add(copyButton)
            
            detailsContentPanel = JPanel(BorderLayout())
            detailsContentPanel.add(markdownScrollPane, BorderLayout.CENTER)
            detailsContentPanel.add(buttonPanel, BorderLayout.SOUTH)
            detailsContentPanel.isVisible = false
            
            panel.add(detailsContentPanel, BorderLayout.CENTER)
            
            return panel
        }
        
        private fun toggleDetailsVisibility() {
            isDetailsExpanded = !isDetailsExpanded
            toggleButton.text = if (isDetailsExpanded) "▲ Hide Details" else "▼ Show Details"
            
            detailsContentPanel.isVisible = isDetailsExpanded
            
            // Force proper revalidation and repaint
            detailsContentPanel.revalidate()
            detailsContentPanel.repaint()
            mainPanel.revalidate()
            mainPanel.repaint()
            
            // Resize dialog to fit content
            SwingUtilities.invokeLater {
                pack()
            }
        }
        
        private fun copyDetailsToClipboard() {
            try {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val stringSelection = java.awt.datatransfer.StringSelection(detailsMarkdown.toString())
                clipboard.setContents(stringSelection, null)
                
                // Show brief confirmation
                toggleButton.text = "✅ Copied!"
                Timer(1000) {
                    toggleButton.text = if (isDetailsExpanded) "▲ Hide Details" else "▼ Show Details"
                }.apply { isRepeats = false }.start()
            } catch (e: Exception) {
                logger.error("Failed to copy details to clipboard", e)
            }
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
            
            val leftHeader = JLabel("Quick Tasks (1-4)")
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
            
            val instructionLabel = JLabel("Press number for quick task, Shift+number for custom, or type specific task")
            instructionLabel.font = instructionLabel.font.deriveFont(Font.PLAIN, 11f)
            instructionLabel.foreground = JBColor.GRAY
            instructionLabel.alignmentX = Component.CENTER_ALIGNMENT
            bottomPanel.add(instructionLabel)
            
            bottomPanel.add(Box.createVerticalStrut(5))
            
            val progressNote = JLabel("Task progress will be shown in the status bar →")
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
            
            val label = JLabel("Custom Task:")
            label.font = label.font.deriveFont(Font.BOLD)
            headerPanel.add(label)
            headerPanel.add(Box.createVerticalStrut(5))
            
            val infoLabel = JLabel("Describe exactly what to do (e.g., 'add null check for parameter x')")
            infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 11f)
            infoLabel.foreground = JBColor.GRAY
            headerPanel.add(infoLabel)
            
            headerPanel.add(Box.createVerticalStrut(3))
            
            val precisionLabel = JLabel("Be specific - the AI will do ONLY what you ask")
            precisionLabel.font = precisionLabel.font.deriveFont(Font.ITALIC, 10f)
            precisionLabel.foreground = JBColor(0x007800, 0x6BA644)
            headerPanel.add(precisionLabel)
            
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
        
        override fun doCancelAction() {
            timeUpdateTimer.stop()
            super.doCancelAction()
        }
        
        override fun dispose() {
            timeUpdateTimer.stop()
            // Clean up markdown editor
            if (::markdownEditor.isInitialized) {
                EditorFactory.getInstance().releaseEditor(markdownEditor)
            }
            super.dispose()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val methodRewriteService = project?.serviceOrNull<ZestQuickActionService>()

        val isAvailable = project != null && editor != null && methodRewriteService != null && !editor.isDisposed

        e.presentation.isEnabledAndVisible = isAvailable

        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Quick Action (In Progress...)"
            e.presentation.description = "A task is currently in progress - check status bar"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Quick Action..."
            e.presentation.description = "Perform specific task on selected code (progress shown in status bar)"
            e.presentation.isEnabled = isAvailable
        }
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

    /**
     * Trigger code explanation for the given method
     */
    private fun explainCodeForMethod(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        methodContext: MethodContext
    ) {
        logger.info("=== STARTING CODE EXPLANATION FOR METHOD: ${methodContext.methodName} ===")
        try {
            // Get the method code content from the method context
            val codeContent = methodContext.methodContent
            if (codeContent.isBlank()) {
                Messages.showWarningDialog(
                    project,
                    "Could not extract code content for explanation",
                    "Code Explanation Error"
                )
                return
            }

            // Find the PsiFile to determine language and file path
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile == null) {
                logger.warn("Could not find PsiFile for current editor")
                Messages.showWarningDialog(
                    project,
                    "Could not find the source file for code explanation",
                    "Code Explanation Error"
                )
                return
            }

            // Determine the programming language
            val language = when {
                psiFile.name.endsWith(".java") -> "Java"
                psiFile.name.endsWith(".kt") -> "Kotlin"
                psiFile.name.endsWith(".js") -> "JavaScript"
                psiFile.name.endsWith(".ts") -> "TypeScript"
                psiFile.name.endsWith(".py") -> "Python"
                psiFile.name.endsWith(".cpp") || psiFile.name.endsWith(".cc") -> "C++"
                psiFile.name.endsWith(".c") -> "C"
                psiFile.name.endsWith(".cs") -> "C#"
                psiFile.name.endsWith(".go") -> "Go"
                psiFile.name.endsWith(".rs") -> "Rust"
                psiFile.name.endsWith(".php") -> "PHP"
                psiFile.name.endsWith(".rb") -> "Ruby"
                else -> "Unknown"
            }

            val filePath = psiFile.virtualFile.path

            // Create and trigger the ZestExplainCodeAction
            val explainAction = com.zps.zest.completion.actions.ZestExplainCodeAction()
            
            // Since we can't directly call the action, we'll create a fake action event
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val langChainService = project.getService(com.zps.zest.langchain4j.ZestLangChain4jService::class.java)
                    val llmService = project.getService(com.zps.zest.langchain4j.util.LLMService::class.java)
                    
                    if (langChainService == null || llmService == null) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "LangChain4j or LLM service not available. Please check your configuration.",
                                "Service Unavailable"
                            )
                        }
                        return@executeOnPooledThread
                    }

                    // Show progress dialog
                    ApplicationManager.getApplication().invokeLater {
                        val progressDialog = com.zps.zest.explanation.ui.CodeExplanationDialog.showProgressDialog(project, filePath)
                        
                        // Start explanation in background
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                // Create the explanation agent
                                val explanationAgent = com.zps.zest.explanation.agents.CodeExplanationAgent(project, langChainService, llmService)

                                // Start the explanation process
                                val resultFuture = explanationAgent.explainCode(filePath, codeContent, language, null)

                                // Handle the result
                                resultFuture.whenComplete { result, throwable ->
                                    ApplicationManager.getApplication().invokeLater {
                                        progressDialog.close(0)
                                        
                                        if (throwable != null) {
                                            Messages.showErrorDialog(
                                                project,
                                                "Code explanation failed: ${throwable.message}",
                                                "Explanation Error"
                                            )
                                        } else {
                                            // Show the detailed explanation dialog
                                            val explanationDialog = com.zps.zest.explanation.ui.CodeExplanationDialog(project, result)
                                            explanationDialog.show()
                                        }
                                    }
                                }
                            } catch (ex: Exception) {
                                ApplicationManager.getApplication().invokeLater {
                                    progressDialog.close(0)
                                    Messages.showErrorDialog(
                                        project,
                                        "Failed to initialize code explanation: ${ex.message}",
                                        "Initialization Error"
                                    )
                                }
                            }
                        }
                    }
                    
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to start code explanation: ${ex.message}",
                            "Code Explanation Error"
                        )
                    }
                }
            }

            logger.info("Successfully triggered code explanation for method: ${methodContext.methodName}")
            
        } catch (e: Exception) {
            logger.error("Failed to trigger code explanation for method ${methodContext.methodName}", e)
            Messages.showErrorDialog(
                project,
                "Failed to start code explanation: ${e.message}",
                "Code Explanation Error"
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
        val fileName = psiFile.virtualFile?.name ?: "unknown"
        val language = if (fileName.endsWith(".java")) "java" else "javascript"
        
        MethodContext(
            methodName = method.name,
            methodStartOffset = method.textRange.startOffset,
            methodEndOffset = method.textRange.endOffset,
            methodContent = methodText,
            language = language,
            fileName = fileName,
            isCocos2dx = false,
            relatedClasses = emptyMap(),
            methodSignature = method.name,
            containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name
        )
    }
}