package com.zps.zest.completion.metrics

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.*

/**
 * Configuration panel for inline completion metrics
 */
class ZestMetricsConfiguration(private val project: Project) : Configurable {
    private var enableMetricsCheckBox: JCheckBox? = null
    private val metricsService by lazy { ZestInlineCompletionMetricsService.getInstance(project) }
    
    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        
        // Enable metrics checkbox
        enableMetricsCheckBox = JCheckBox("Enable inline completion metrics").apply {
            isSelected = true // Default to enabled
            toolTipText = "Collect anonymous usage metrics to improve inline completions"
        }
        
        panel.add(enableMetricsCheckBox)
        
        // Add some spacing
        panel.add(Box.createVerticalStrut(10))
        
        // Info label
        val infoLabel = JLabel("<html><body style='width: 300px'>" +
                "Metrics help improve the quality of inline completions by tracking usage patterns. " +
                "No code content is collected, only completion events and timing information." +
                "</body></html>")
        panel.add(infoLabel)
        
        return panel
    }
    
    override fun isModified(): Boolean {
        val currentState = metricsService.getMetricsState()
        return enableMetricsCheckBox?.isSelected != currentState.enabled
    }
    
    override fun apply() {
        enableMetricsCheckBox?.let { checkbox ->
            metricsService.setEnabled(checkbox.isSelected)
        }
    }
    
    override fun getDisplayName(): String = "Inline Completion Metrics"
    
    override fun reset() {
        val currentState = metricsService.getMetricsState()
        enableMetricsCheckBox?.isSelected = currentState.enabled
    }
}
