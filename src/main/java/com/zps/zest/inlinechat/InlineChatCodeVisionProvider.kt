package com.zps.zest.inlinechat

import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.CodeVisionState.Companion.READY_EMPTY
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.ui.SpinningProgressIcon
import javax.swing.Icon

/**
 * Base class for inline chat code vision providers
 */
abstract class InlineChatCodeVisionProvider : CodeVisionProvider<Any>, DumbAware {
    companion object {
        // Debug flag - set to true to enable debug output
        const val DEBUG_CODE_VISION = false
        
        init {
            if (DEBUG_CODE_VISION) {
                System.out.println("=== InlineChatCodeVisionProvider companion object initialized ===")
            }
        }
    }
    
    private val logger = Logger.getInstance(InlineChatCodeVisionProvider::class.java)
    
    init {
        if (DEBUG_CODE_VISION) {
            System.out.println("=== ${this.javaClass.simpleName} initialized ===")
        }
    }
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    // provider id
    abstract override val id: String

    // action name
    abstract val action: String?

    // execute action id
    abstract val actionId: String?
    abstract val icon: Icon
    override val name: String = "Zest Inline Chat Code Vision Provider"

    override fun precomputeOnUiThread(editor: Editor): Any {
        return Any()
    }

    override fun computeCodeVision(editor: Editor, uiData: Any): CodeVisionState {
        if (DEBUG_CODE_VISION) {
            System.out.println("=== ${this.javaClass.simpleName}.computeCodeVision ===")
            System.out.println("Provider ID: $id")
            System.out.println("Editor: ${editor.virtualFile?.path}")
        }
        
        // Use ReadAction to avoid threading issues
        return ReadAction.compute<CodeVisionState, Throwable> {
            val project = editor.project ?: return@compute READY_EMPTY
            val inlineChatService = project.getService(InlineChatService::class.java) ?: return@compute READY_EMPTY
            
            // Don't show code vision buttons when using floating window
            if (inlineChatService.floatingCodeWindow != null) {
                return@compute READY_EMPTY
            }
            
            // Check if we have diff actions
            val hasAction = inlineChatService.inlineChatDiffActionState[id] == true
            
            if (DEBUG_CODE_VISION) {
                System.out.println("Has action for $id: $hasAction")
                System.out.println("All diff action states: ${inlineChatService.inlineChatDiffActionState}")
            }
            
            if (!hasAction) {
                return@compute READY_EMPTY
            }
            
            // Find a good place to show the button
            val document = editor.document
            if (document.lineCount == 0) {
                if (DEBUG_CODE_VISION) {
                    System.out.println("Document has no lines!")
                }
                return@compute READY_EMPTY
            }
            
            // Place at the selection start line or top of file
            val lineToPlace = if (inlineChatService.selectionStartLine > 0 && 
                                 inlineChatService.selectionStartLine < document.lineCount) {
                inlineChatService.selectionStartLine
            } else {
                0
            }
            
            if (DEBUG_CODE_VISION) {
                System.out.println("Placing Code Vision at line: $lineToPlace")
            }
            
            // Place at the top of the file
            val buttonTitle = if (actionId != null) {
                val shortcut = KeymapUtil.getFirstKeyboardShortcutText(getAction(actionId!!))
                if (shortcut.isNotEmpty()) {
                    "$action ($shortcut)"
                } else {
                    action ?: ""
                }
            } else {
                action ?: ""
            }
            
            val startOffset = document.getLineStartOffset(lineToPlace)
            val endOffset = document.getLineEndOffset(lineToPlace)
            
            val entry = TextCodeVisionEntry(buttonTitle, id, icon)
            val textRange = TextRange(startOffset, endOffset)
            
            if (DEBUG_CODE_VISION) {
                System.out.println("Creating code vision entry: $buttonTitle at range $textRange")
            }
            
            // Return a single entry
            return@compute CodeVisionState.Ready(listOf(textRange to entry))
        }
    }

    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        if (DEBUG_CODE_VISION) {
            System.out.println("=== ${this.javaClass.simpleName}.handleClick ===")
            System.out.println("Action ID: $actionId")
        }
        
        if (actionId == null) {
            return
        }
        val action = getAction(actionId!!)
        val dataContext = DataManager.getInstance().getDataContext(editor.component)
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
            com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
            null,
            dataContext
        )
        action.actionPerformed(event)
    }

    private fun getAction(actionId: String) = ActionManager.getInstance().getAction(actionId)
}

/**
 * Provides a loading indicator during LLM processing
 */
class InlineChatLoadingCodeVisionProvider : InlineChatCodeVisionProvider() {
    override val id: String = "Zest.InlineChat.Loading"
    override val action: String? = "Processing..."
    override val actionId: String? = null
    override val icon: Icon = SpinningProgressIcon()
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("Zest.InlineChat.Cancel"))
}

/**
 * Provides a cancel button for inline chat
 */
class InlineChatCancelCodeVisionProvider : InlineChatCodeVisionProvider() {
    override val id: String = "Zest.InlineChat.Cancel"
    override val action: String = "Cancel"
    override val actionId: String = "Zest.InlineChatCancelAction"
    override val icon: Icon = AllIcons.Actions.Cancel
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        emptyList()
}

/**
 * Provides an accept button for inline chat
 */
class InlineChatAcceptCodeVisionProvider : InlineChatCodeVisionProvider() {
    override val id: String = "Zest.InlineChat.Accept"
    override val action: String = "Accept Changes"
    override val actionId: String = "Zest.InlineChatAcceptAction"
    override val icon: Icon = AllIcons.Actions.Checked
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("Zest.InlineChat.Discard"))
}

/**
 * Provides a discard button for inline chat
 */
class InlineChatDiscardCodeVisionProvider : InlineChatCodeVisionProvider() {
    override val id: String = "Zest.InlineChat.Discard"
    override val action: String = "Discard Changes"
    override val actionId: String = "Zest.InlineChatDiscardAction"
    override val icon: Icon = AllIcons.Actions.Close
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        emptyList()
}