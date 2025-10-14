package com.zps.zest.pochi

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Dialog that shows Pochi CLI output streaming in real-time.
 * Provides visibility into what Pochi is doing and allows user to cancel.
 */
class PochiStreamingDialog(
    private val project: Project,
    private val methodName: String
) : DialogWrapper(project) {

    private val outputArea = JTextArea()
    private val statusLabel = JLabel("Starting Pochi...")
    private var isCancelled = false

    init {
        title = "Pochi AI Agent - $methodName"
        setupUI()
        init()
    }

    private fun setupUI() {
        outputArea.isEditable = false
        outputArea.font = Font("Monospaced", Font.PLAIN, 12)
        outputArea.lineWrap = true
        outputArea.wrapStyleWord = true
        outputArea.border = JBUI.Borders.empty(5)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 500)

        // Status header
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = JBUI.Borders.empty(5, 10)
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
        headerPanel.add(statusLabel, BorderLayout.WEST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Output area with scroll
        val scrollPane = JBScrollPane(outputArea)
        scrollPane.border = JBUI.Borders.customLine(UIManager.getColor("Component.borderColor"), 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction)
    }

    override fun doCancelAction() {
        isCancelled = true
        super.doCancelAction()
    }

    /**
     * Append text chunk to output area (called from background thread)
     */
    fun appendOutput(text: String) {
        SwingUtilities.invokeLater {
            outputArea.append(text)
            outputArea.caretPosition = outputArea.document.length
        }
    }

    /**
     * Update status message
     */
    fun updateStatus(status: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = status
        }
    }

    /**
     * Mark as completed successfully
     */
    fun markComplete() {
        SwingUtilities.invokeLater {
            statusLabel.text = "✓ Pochi completed successfully"
        }
    }

    /**
     * Mark as failed with error
     */
    fun markFailed(error: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "✗ Pochi failed: $error"
            appendOutput("\n\n=== ERROR ===\n$error\n")
        }
    }

    /**
     * Check if user cancelled
     */
    fun isCancelled(): Boolean = isCancelled

    /**
     * Get all output text
     */
    fun getOutputText(): String = outputArea.text
}
