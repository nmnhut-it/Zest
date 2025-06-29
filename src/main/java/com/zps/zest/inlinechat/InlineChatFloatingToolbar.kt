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
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Point
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shows a floating toolbar with Accept/Reject buttons for inline chat
 */
class InlineChatFloatingToolbar(
    private val project: Project,
    private val editor: Editor
) {
    private var popup: com.intellij.openapi.ui.popup.JBPopup? = null
    private var warningMessage: String? = null
    
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
    
    /**
     * Set a warning message to be displayed in the toolbar
     */
    fun setWarningMessage(message: String?) {
        this.warningMessage = message
        // If popup is already visible, re-create it with the warning
        if (popup?.isVisible == true) {
            hide()
            show()
        }
    }
    
    private fun createToolbarPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(4)
        
        // Create the action toolbar with Accept/Reject buttons
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
        
        // Main panel with buttons
        val buttonPanel = JPanel(BorderLayout())
        buttonPanel.add(toolbar.component, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.NORTH)
        
        // Add warning message if present
        if (!warningMessage.isNullOrEmpty()) {
            val warningPanel = createWarningPanel(warningMessage!!)
            panel.add(warningPanel, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun createWarningPanel(message: String): JPanel {
        val panel = JPanel(BorderLayout())
        // Use standard warning colors
        val warningBorderColor = JBColor(Color(255, 190, 0), Color(210, 130, 0))  // Amber/orange color
        val warningBackgroundColor = JBColor(Color(255, 250, 220), Color(80, 70, 40))  // Light yellow/amber background
        
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningBorderColor, 1, true),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )
        panel.background = warningBackgroundColor
        
        val warningIcon = JBLabel(AllIcons.General.BalloonWarning)
        
        val warningLabel = JBLabel(message, SwingConstants.LEFT)
        warningLabel.font = warningLabel.font.deriveFont(Font.PLAIN, 12f)
        
        val innerPanel = JPanel(BorderLayout(8, 0))
        innerPanel.background = panel.background
        innerPanel.add(warningIcon, BorderLayout.WEST)
        innerPanel.add(warningLabel, BorderLayout.CENTER)
        
        panel.add(innerPanel, BorderLayout.CENTER)
        
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