package com.zps.zest.pochi

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Dialog that shows AI CLI output streaming in real-time with proper terminal rendering.
 * Uses TerminalExecutionConsole for ANSI colors, progress bars, and spinners.
 * Flushes console every 100ms for true real-time streaming.
 */
class PochiStreamingDialog(
    private val project: Project,
    private val processHandler: ProcessHandler,
    private val toolName: String = "AI Assistant"
) : DialogWrapper(project) {

    private val console: TerminalExecutionConsole
    private val statusLabel = JLabel("Starting $toolName...")
    private val progressBar = JProgressBar()
    private var flushTimer: Timer? = null

    init {
        title = "$toolName - Code Analysis"

        // Create TerminalExecutionConsole (handles ANSI codes, colors, etc.)
        console = TerminalExecutionConsole(project, processHandler)

        // Start flush timer for real-time streaming (every 100ms)
        startFlushTimer()

        init()
    }

    private fun startFlushTimer() {
        flushTimer = Timer(100) {
            SwingUtilities.invokeLater {
                try {
                    console.flushImmediately()
                } catch (e: Exception) {
                    // Ignore - might fail if not on EDT or console disposed
                }
            }
        }
        flushTimer?.start()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1200, 850)
        panel.minimumSize = Dimension(900, 600)

        // Status header with progress bar
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = JBUI.Borders.empty(12, 15, 8, 15)

        // Status label
        statusLabel.font = UIManager.getFont("Label.font").deriveFont(Font.BOLD, 15f)
        headerPanel.add(statusLabel, BorderLayout.WEST)

        // Progress indicator
        progressBar.isIndeterminate = true
        progressBar.preferredSize = Dimension(150, 20)
        val progressPanel = JPanel(BorderLayout())
        progressPanel.add(progressBar, BorderLayout.EAST)
        headerPanel.add(progressPanel, BorderLayout.EAST)

        panel.add(headerPanel, BorderLayout.NORTH)

        // Console component (fills entire center area - no extra borders)
        val consoleComponent = console.component
        panel.add(consoleComponent, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }

    override fun doCancelAction() {
        // Stop flush timer
        flushTimer?.stop()
        flushTimer = null

        // Stop progress bar
        progressBar.isIndeterminate = false

        // Destroy process if user cancels
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            super.doCancelAction()
        } else {
            processHandler.destroyProcess()
            super.doCancelAction()
        }
    }

    override fun dispose() {
        // Clean up timer
        flushTimer?.stop()
        flushTimer = null
        super.dispose()
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
     * Stop progress indicator (when process completes)
     */
    fun stopProgress() {
        SwingUtilities.invokeLater {
            progressBar.isIndeterminate = false
            progressBar.value = 100
        }
    }

    /**
     * Get the console view (for additional operations if needed)
     */
    fun getConsole(): TerminalExecutionConsole = console
}
