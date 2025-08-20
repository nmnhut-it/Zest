package com.zps.zest.completion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JProgressBar
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.min

/**
 * Progress dialog for Quick Action service operations
 * Shows current operation status and allows cancellation
 */
class ZestQuickActionProgressDialog(
    project: Project,
    private val methodName: String,
    private val onCancel: (() -> Unit)? = null
) : DialogWrapper(project, false) {
    
    private val statusLabel = JBLabel("Initializing...")
    private val progressBar = JProgressBar()
    private val methodLabel = JBLabel("Method: $methodName()")
    private val timeLabel = JBLabel("Elapsed: 0.0s")
    
    private val startTime = System.currentTimeMillis()
    private val timeUpdateTimer = Timer(100) { updateElapsedTime() }
    
    // Progress tracking
    private var currentStage = 0
    private val totalStages = 6
    
    companion object {
        const val STAGE_INITIALIZING = 0
        const val STAGE_RETRIEVING_CONTEXT = 1
        const val STAGE_BUILDING_PROMPT = 2
        const val STAGE_QUERYING_LLM = 3
        const val STAGE_PARSING_RESPONSE = 4
        const val STAGE_ANALYZING_CHANGES = 5
        const val STAGE_COMPLETE = 6
    }
    
    init {
        title = "Quick Action Processing"
        setModal(false) // Non-blocking dialog
        isResizable = false
        init()
        
        // Start timer for elapsed time updates
        timeUpdateTimer.start()
        
        // Auto-close after 60 seconds as safety measure
        Timer(60000) {
            if (isShowing) {
                doCancelAction()
            }
        }.apply { isRepeats = false }.start()
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(400, 150)
        panel.border = JBUI.Borders.empty(15)
        
        // Main content panel
        val contentPanel = JPanel(BorderLayout())
        contentPanel.border = JBUI.Borders.emptyBottom(10)
        
        // Progress section
        val progressPanel = JPanel(BorderLayout(0, 10))
        
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
        
        contentPanel.add(progressPanel, BorderLayout.CENTER)
        
        // Info panel
        val infoPanel = JPanel(BorderLayout(0, 5))
        
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 12f)
        methodLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(methodLabel, BorderLayout.NORTH)
        
        timeLabel.font = timeLabel.font.deriveFont(Font.PLAIN, 11f)
        timeLabel.foreground = UIUtil.getContextHelpForeground()
        infoPanel.add(timeLabel, BorderLayout.CENTER)
        
        contentPanel.add(infoPanel, BorderLayout.SOUTH)
        panel.add(contentPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    /**
     * Update the current stage and status text
     */
    fun updateStage(stage: Int, statusText: String) {
        SwingUtilities.invokeLater {
            if (isShowing) {
                currentStage = min(stage, totalStages)
                statusLabel.text = statusText
                progressBar.value = currentStage
                
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
    }
    
    /**
     * Update status text without changing stage
     */
    fun updateStatus(statusText: String) {
        SwingUtilities.invokeLater {
            if (isShowing) {
                statusLabel.text = statusText
            }
        }
    }
    
    /**
     * Update elapsed time display
     */
    private fun updateElapsedTime() {
        if (isShowing) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            timeLabel.text = "Elapsed: ${String.format("%.1f", elapsed)}s"
        }
    }
    
    /**
     * Close the dialog when processing is complete
     */
    fun complete() {
        SwingUtilities.invokeLater {
            if (isShowing) {
                updateStage(STAGE_COMPLETE, "Processing complete!")
                // Close after a brief delay to show completion
                Timer(500) {
                    close(OK_EXIT_CODE)
                }.apply { isRepeats = false }.start()
            }
        }
    }
    
    override fun doCancelAction() {
        timeUpdateTimer.stop()
        onCancel?.invoke()
        super.doCancelAction()
    }
    
    override fun doOKAction() {
        timeUpdateTimer.stop()
        super.doOKAction()
    }
    
    override fun createActions() = arrayOf(cancelAction.apply { 
        putValue(javax.swing.Action.NAME, "Cancel") 
    })
    
    override fun dispose() {
        timeUpdateTimer.stop()
        super.dispose()
    }
}