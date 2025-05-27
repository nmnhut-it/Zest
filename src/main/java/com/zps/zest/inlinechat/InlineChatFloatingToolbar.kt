package com.zps.zest.inlinechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel

/**
 * Shows a floating toolbar with Accept/Reject buttons for inline chat
 */
class InlineChatFloatingToolbar(
    private val project: Project,
    private val editor: Editor
) {
    private var popup: com.intellij.openapi.ui.popup.JBPopup? = null
    
    fun show() {
        ApplicationManager.getApplication().invokeLater {
            if (popup?.isVisible == true) {
                return@invokeLater
            }
            
            val panel = createToolbarPanel()
            
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setMovable(true)
                .setResizable(false)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setTitle("Inline Chat Actions")
                .createPopup()
            
            // Position the toolbar above the selection
            val selectionModel = editor.selectionModel
            val position = if (selectionModel.hasSelection()) {
                val startOffset = selectionModel.selectionStart
                val startPoint = editor.offsetToXY(startOffset)
                // Position it above the selection with enough vertical space to avoid obscuring text
                Point(startPoint.x + 50, startPoint.y - 120)
            } else {
                val caretOffset = editor.caretModel.offset
                val caretPoint = editor.offsetToXY(caretOffset)
                // Position it above the caret with enough vertical space to avoid obscuring text
                Point(caretPoint.x + 50, caretPoint.y - 120)
            }
            
            popup?.show(RelativePoint(editor.contentComponent, position))
        }
    }
    
    fun hide() {
        ApplicationManager.getApplication().invokeLater {
            popup?.cancel()
            popup = null
        }
    }
    
    private fun createToolbarPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(4)
        
        val actionGroup = DefaultActionGroup().apply {
            add(AcceptAction())
            add(RejectAction())
        }
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "InlineChatFloatingToolbar",
            actionGroup,
            true
        )
        toolbar.targetComponent = editor.contentComponent
        
        panel.add(toolbar.component, BorderLayout.CENTER)
        
        return panel
    }
    
    private inner class AcceptAction : DumbAwareAction(
        "Accept",
        "Accept the AI suggested changes",
        AllIcons.Actions.Checked
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            hide()
            ActionManager.getInstance()
                .getAction("Zest.InlineChatAcceptAction")
                ?.actionPerformed(e)
        }
    }
    
    private inner class RejectAction : DumbAwareAction(
        "Reject", 
        "Reject the AI suggested changes",
        AllIcons.Actions.Close
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            hide()
            ActionManager.getInstance()
                .getAction("Zest.InlineChatDiscardAction")
                ?.actionPerformed(e)
        }
    }
}
