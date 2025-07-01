package com.zps.zest.codehealth

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Configuration page for Code Health settings
 */
class CodeHealthConfigurable(private val project: Project) : Configurable {
    
    private val enabledCheckBox = JBCheckBox("Enable daily health check")
    private val checkTimeField = JBTextField(10)
    private val maxMethodsField = JBTextField(10)
    private val skipVerificationCheckBox = JBCheckBox("Skip verification step (faster analysis)")
    
    override fun getDisplayName(): String = "Code Health"
    
    override fun createComponent(): JComponent {
        val tracker = CodeHealthTracker.getInstance(project)
        val state = tracker.state
        
        // Initialize UI components with current values
        enabledCheckBox.isSelected = state.enabled
        checkTimeField.text = state.checkTime
        maxMethodsField.text = CodeHealthTracker.MAX_METHODS_TO_TRACK.toString()
        skipVerificationCheckBox.isSelected = CodeHealthAnalyzer.SKIP_VERIFICATION
        
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Code Health monitors methods you modify throughout the day and analyzes them for potential issues."))
            .addSeparator()
            .addComponent(enabledCheckBox)
            .addLabeledComponent("Check time (HH:MM):", checkTimeField)
            .addLabeledComponent("Max methods to track:", maxMethodsField)
            .addComponent(skipVerificationCheckBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(5)
            }
    }
    
    override fun isModified(): Boolean {
        val tracker = CodeHealthTracker.getInstance(project)
        val state = tracker.state
        
        return enabledCheckBox.isSelected != state.enabled ||
                checkTimeField.text != state.checkTime ||
                skipVerificationCheckBox.isSelected != CodeHealthAnalyzer.SKIP_VERIFICATION
    }
    
    override fun apply() {
        val tracker = CodeHealthTracker.getInstance(project)
        val state = tracker.state
        
        state.enabled = enabledCheckBox.isSelected
        state.checkTime = checkTimeField.text
        CodeHealthAnalyzer.SKIP_VERIFICATION = skipVerificationCheckBox.isSelected
        
        // Validate time format
        try {
            java.time.LocalTime.parse(state.checkTime)
        } catch (e: Exception) {
            state.checkTime = "13:00" // Reset to default if invalid
            checkTimeField.text = state.checkTime
        }
    }
    
    override fun reset() {
        val tracker = CodeHealthTracker.getInstance(project)
        val state = tracker.state
        
        enabledCheckBox.isSelected = state.enabled
        checkTimeField.text = state.checkTime
        skipVerificationCheckBox.isSelected = CodeHealthAnalyzer.SKIP_VERIFICATION
    }
}
