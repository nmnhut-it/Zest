package com.zps.zest.inlinechat

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.ui.components.IconLabelButton
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Intention action to show inline chat input
 */
class InlineChatIntentionAction : BaseIntentionAction(), DumbAware {
    private var inlay: Inlay<InlineChatInlayRenderer>? = null
    private var inlayRender: InlineChatInlayRenderer? = null
    private var project: Project? = null
    private var editor: Editor? = null
    
    override fun getFamilyName(): String {
        return "Zest"
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val inlineChatService = project.serviceOrNull<InlineChatService>() ?: return
        
        // Early return if input is already visible or floating window is active
        if (inlineChatService.inlineChatInputVisible || inlineChatService.floatingCodeWindow != null) {
            return
        }
        
        this.project = project
        this.editor = editor
        if (editor != null) {
            val locationInfo = getCurrentLocation(editor = editor)
            inlineChatService.location = locationInfo.location
            
            // Set the selection start line
            val selectionModel = editor.selectionModel
            if (selectionModel.hasSelection()) {
                inlineChatService.selectionStartLine = editor.document.getLineNumber(selectionModel.selectionStart)
            } else {
                // Use current cursor line
                inlineChatService.selectionStartLine = editor.document.getLineNumber(editor.caretModel.offset)
            }
            
            inlineChatService.inlineChatInputVisible = true
            addInputToEditor(project, editor, locationInfo.startOffset)
        }

        // listen for theme change
        project.messageBus.connect().subscribe(LafManagerListener.TOPIC, LafManagerListener {
            inlayRender?.repaint() // FIXME
        })
    }

    override fun getText(): String {
        // Check if the selection contains TODOs
        val editor = this.editor
        if (editor != null && editor.selectionModel.hasSelection()) {
            val selectedText = editor.selectionModel.selectedText ?: ""
            if (ZestContextProvider.containsTodos(selectedText)) {
                return "Open Zest inline edit (TODOs detected)"
            }
        }
        return "Open Zest inline edit"
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        return IntentionPreviewInfo.EMPTY
    }

    private fun addInputToEditor(project: Project, editor: Editor, offset: Int) {
        val inlayModel = editor.inlayModel
        inlayRender = InlineChatInlayRenderer(project, editor, this::onClose, this::onInputSubmit)
        inlay = inlayModel.addBlockElement(offset, true, true, 0, inlayRender!!)
    }

    private fun onClose() {
        inlay?.dispose()
        val inlineChatService = project?.serviceOrNull<InlineChatService>() ?: return
        inlineChatService.inlineChatInputVisible = false
    }

    private fun onInputSubmit(value: String) {
        // Capture selection information first, then submit the command
        // No need to keep the actual selection, but we need its info for processing
        chatEdit(command = value)
        // Add to command history
        project?.serviceOrNull<CommandHistory>()?.addCommand(value)
    }

    private fun chatEdit(command: String) {
        val scope = CoroutineScope(Dispatchers.IO)
        val inlineChatService = project?.serviceOrNull<InlineChatService>() ?: return
        
        // Capture and store selection info before proceeding
        editor?.let { 
            val selectionModel = it.selectionModel
            if (selectionModel.hasSelection()) {
                // Store selection offsets in the service
                inlineChatService.selectionStartOffset = selectionModel.selectionStart
                inlineChatService.selectionEndOffset = selectionModel.selectionEnd
                
                // Store the selected text directly
                val selectedText = selectionModel.selectedText
                if (!selectedText.isNullOrEmpty()) {
                    inlineChatService.originalCode = selectedText
                }
                
                // Now it's safe to clear the selection since we've captured what we need
                selectionModel.removeSelection()
            }
        }
        
        scope.launch {
            // We need to get the location in a read action since we're in a background thread
            val location = com.intellij.openapi.application.ReadAction.compute<org.eclipse.lsp4j.Location, Throwable> {
                inlineChatService.location
            } ?: return@launch
            
            val param = ChatEditParams(
                location = location,
                command = command
            )
            
            processInlineChatCommand(project!!, param)
        }
    }
}

/**
 * Renderer for the inline chat input
 */
class InlineChatInlayRenderer(
    private val project: Project,
    private val editor: Editor,
    private val onClose: () -> Unit,
    private val onSubmit: (value: String) -> Unit
) : EditorCustomElementRenderer {
    private val inlineChatComponent = InlineChatComponent(project, this::handleClose, onSubmit)
    private var targetRegion: Rectangle? = null
    private var disposed = false

    private val keyEventHandler = object : KeyEventDispatcher {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.id == KeyEvent.KEY_PRESSED && (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE)) {
                if (e.component is JBTextArea) {
                    // Return true to consume the event (prevent default handling)
                    return true
                }
            }
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                handleClose()
                return true
            }
            return false
        }
    }

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventHandler)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return inlineChatComponent.preferredSize.width
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlineChatComponent.preferredSize.height
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        if (disposed) {
            return
        }
        val visibleArea = editor.scrollingModel.visibleArea
        if (this.targetRegion == null) {
            this.targetRegion = targetRegion
            this.targetRegion?.y = targetRegion.y + visibleArea.y
        }
        val firstTargetRegion = this.targetRegion ?: targetRegion
        inlineChatComponent.setSize(firstTargetRegion.width, firstTargetRegion.height)
        inlineChatComponent.setLocation(firstTargetRegion.x, firstTargetRegion.y)

        if (inlineChatComponent.parent == null) {
            editor.contentComponent.add(inlineChatComponent)
            inlineChatComponent.requestFocus()
        }

        inlineChatComponent.repaint()
    }

    fun repaint() {
        inlineChatComponent.repaint()
    }

    private fun handleClose() {
        this.dispose()
        onClose()
    }

    private fun dispose() {
        if (disposed) {
            return
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventHandler)
        inlineChatComponent.parent?.remove(inlineChatComponent)
        editor.contentComponent.remove(inlineChatComponent)
        editor.contentComponent.repaint()
        this.disposed = true
    }
}

/**
 * Main component for the inline chat input
 */
class InlineChatComponent(
    private val project: Project,
    private val onClose: () -> Unit,
    private val onSubmit: (value: String) -> Unit
) : JPanel() {
    private val closeButton = createCloseButton()
    private val inlineInput = InlineInputComponent(project, this::handleSubmit, this::handleClose)

    override fun isOpaque(): Boolean {
        return false
    }

    private fun getTheme(): EditorColorsScheme {
        return EditorColorsManager.getInstance().globalScheme
    }

    init {
        layout = BorderLayout()
        putClientProperty(UIUtil.HIDE_EDITOR_FROM_DATA_CONTEXT_PROPERTY, true)

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(inlineInput, BorderLayout.CENTER)
        contentPanel.add(closeButton, BorderLayout.EAST)
        contentPanel.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        add(contentPanel, BorderLayout.CENTER)

        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(getTheme().defaultBackground, 3, true),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )

        minimumSize = Dimension(200, 40)
        preferredSize = Dimension(800, 60)
    }

    private fun handleClose(): Unit {
        onClose()
    }

    private fun createCloseButton(): JLabel {
        val closeButton = IconLabelButton(AllIcons.Actions.Close) { handleClose() }
        closeButton.toolTipText = "Close Zest inline edit"
        closeButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        closeButton.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        return closeButton
    }

    private fun handleSubmit(value: String) {
        onClose()
        onSubmit(value)
    }

    override fun requestFocus() {
        inlineInput.requestFocus()
    }
}

/**
 * Component for inline text input
 */
class InlineInputComponent(
    private var project: Project,
    private var onSubmit: (value: String) -> Unit,
    private var onCancel: () -> Unit
) : JPanel() {
    private val logger = Logger.getInstance(InlineInputComponent::class.java)
    private val history: CommandHistory? = project.serviceOrNull<CommandHistory>()
    private val textArea: JTextArea = createTextArea()
    private val submitButton: JLabel = createSubmitButton()
    private val historyButton: JLabel = createHistoryButton()
    private var commandListComponent: CommandListComponent? = null

    init {
        putClientProperty(UIUtil.HIDE_EDITOR_FROM_DATA_CONTEXT_PROPERTY, true)
        layout = BorderLayout()
        add(historyButton, BorderLayout.WEST)
        add(textArea, BorderLayout.CENTER)
        add(submitButton, BorderLayout.EAST)
        border = BorderFactory.createLineBorder(UIUtil.getHeaderInactiveColor(), 2, true)

        addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                e.consume()
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE) {
                    e.consume()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                e.consume()
            }
        })
    }

    private fun createTextArea(): JBTextArea {
        val textArea = JBTextArea().apply {
            font = Font(font.family, font.style, 14)
        }
        textArea.lineWrap = false
        textArea.rows = 1
        textArea.columns = 30
        textArea.emptyText.text = "Enter your request for Zest AI..."
        textArea.border = BorderFactory.createEmptyBorder(6, 4, 4, 4)
        // To prevent keystrokes(backspace, delete) being handled by the host editor
        textArea.putClientProperty(UIUtil.HIDE_EDITOR_FROM_DATA_CONTEXT_PROPERTY, true)
        textArea.addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                //
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE) {
                    e.consume()
                }

                if (e.keyCode == KeyEvent.VK_ENTER) {
                    handleConfirm()
                }

                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    // Handle escape
                    textArea.text = ""
                    onCancel()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                //
            }
        })
        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                border = BorderFactory.createLineBorder(UIUtil.getFocusedBorderColor(), 2, true)
            }

            override fun focusLost(e: FocusEvent) {
                border = BorderFactory.createLineBorder(UIUtil.getHeaderInactiveColor(), 2, true)
            }
        })
        return textArea
    }

    override fun requestFocus() {
        textArea.requestFocus()
    }

    private fun handleConfirm() {
        onSubmit(textArea.text.trim())
        textArea.text = ""
    }

    private fun createSubmitButton(): JLabel {
        val submitButton = IconLabelButton(AllIcons.Chooser.Right) { handleConfirm() }
        submitButton.toolTipText = "Submit the command"
        submitButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        submitButton.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        return submitButton
    }

    private fun createHistoryButton(): JLabel {
        val historyButton = IconLabelButton(AllIcons.Actions.SearchWithHistory) { handleOpenHistory() }
        historyButton.toolTipText = "Select suggested / history Command"
        historyButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        historyButton.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        return historyButton
    }

    private fun handleOpenHistory() {
        val commandItems = getCommandList()
        commandListComponent = CommandListComponent("Select Command", commandItems, { textArea.text = it.value }, {
            history?.deleteCommand(it.value)
            refreshCommandList()
        }, {
            history?.clearHistory()
            refreshCommandList()
        })
        val popup =
            JBPopupFactory.getInstance().createComponentPopupBuilder(commandListComponent?.component!!, JPanel())
                .createPopup()
        popup.showUnderneathOf(this)
    }

    private fun refreshCommandList() {
        val commandItems = getCommandList()
        commandListComponent?.setData(commandItems)
    }

    private fun getHistoryCommand(): List<InlineEditCommand> {
        return history?.getHistory()?.map {
            InlineEditCommand(it, null)
        } ?: emptyList()
    }

    private fun getCommandList(): List<CommandListItem> {
        val suggestedItems = try {
            getSuggestedCommands(project).let { deferred -> 
                // This is a simplified approach - in a real implementation,
                // you would handle this asynchronously
                deferred.invokeOnCompletion {
                    if (deferred.isCompleted && !deferred.isCancelled) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            refreshCommandList()
                        }
                    }
                }
                
                @OptIn(ExperimentalCoroutinesApi::class)
                if (deferred.isCompleted) {
                    deferred.getCompleted()
                } else {
                    emptyList()
                }
            }.map {
                CommandListItem(
                    label = it.label,
                    value = it.command,
                    icon = when {
                        it.label.contains("TODO", ignoreCase = true) -> AllIcons.General.TodoDefault
                        it.label.contains("test", ignoreCase = true) -> AllIcons.RunConfigurations.TestState.Run
                        it.label.contains("document", ignoreCase = true) -> AllIcons.Actions.Edit
                        it.label.contains("refactor", ignoreCase = true) -> AllIcons.Actions.RefactoringBulb
                        it.label.contains("bug", ignoreCase = true) -> AllIcons.General.BalloonError
                        else -> AllIcons.Actions.IntentionBulbGrey
                    },
                    description = it.command,
                    canDelete = false
                )
            }
        } catch (e: Exception) {
            logger.warn("Error getting suggested commands", e)
            emptyList()
        }
        
        val historyItems = getHistoryCommand().filter { historyCommand ->
            suggestedItems.find {
                it.value == historyCommand.command.replace(
                    "\n",
                    ""
                )
            } == null
        }.map {
            CommandListItem(
                label = it.command.replace("\n", ""),
                value = it.command.replace("\n", ""),
                icon = AllIcons.Vcs.History,
                description = null,
                canDelete = true
            )
        }
        return suggestedItems + historyItems
    }
}

data class InlineEditCommand(val command: String, val context: List<ChatEditFileContext>?)
data class ChatEditFileContext(val referrer: String, val uri: String, val range: org.eclipse.lsp4j.Range)