package com.zps.zest.codehealth.testplan.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.testplan.TestPlanOverviewVirtualFile
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Overview editor for test plans dashboard
 */
class TestPlanOverviewEditor(
    private val project: Project,
    private val virtualFile: TestPlanOverviewVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    private val component: JComponent
    
    init {
        component = createEditorComponent()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Test Plans Overview"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {}
    
    override fun setState(state: FileEditorState) {
        // No-op for overview editor
    }
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        mainPanel.add(createToolbar(), BorderLayout.NORTH)
        
        // Add content
        mainPanel.add(createContentPanel(), BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Generate all tests action
        actionGroup.add(object : AnAction("Generate All Tests", "Generate test files from all plans", AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                generateAllTests()
            }
        })
        
        // Refresh action
        actionGroup.add(object : AnAction("Refresh", "Refresh test plans overview", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshOverview()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TestPlanOverviewEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun createContentPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(50, 50, 50, 50)
        
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.CENTER
        gbc.insets = Insets(20, 0, 20, 0)
        
        // Title
        val titleLabel = JBLabel("🧪 Test Plans Overview")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 28f)
        panel.add(titleLabel, gbc)
        
        // Subtitle
        gbc.gridy = 1
        val subtitleLabel = JBLabel("Comprehensive test planning and generation dashboard")
        subtitleLabel.font = subtitleLabel.font.deriveFont(16f)
        subtitleLabel.foreground = UIUtil.getInactiveTextColor()
        panel.add(subtitleLabel, gbc)
        
        // Features list
        gbc.gridy = 2
        gbc.insets = Insets(40, 0, 20, 0)
        val featuresPanel = createFeaturesPanel()
        panel.add(featuresPanel, gbc)
        
        // Coming soon note
        gbc.gridy = 3
        gbc.insets = Insets(20, 0, 0, 0)
        val comingSoonLabel = JBLabel("Full dashboard implementation coming in Phase 3")
        comingSoonLabel.font = comingSoonLabel.font.deriveFont(Font.ITALIC, 14f)
        comingSoonLabel.foreground = UIUtil.getInactiveTextColor()
        panel.add(comingSoonLabel, gbc)
        
        return panel
    }
    
    private fun createFeaturesPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        
        val features = listOf(
            "📊 Testability scoring and analysis",
            "🧪 Intelligent test plan generation", 
            "⚡ Bulk test file creation",
            "🔄 Multiple framework support",
            "📋 Progress tracking and monitoring",
            "📝 Markdown export capabilities"
        )
        
        features.forEach { feature ->
            val featureLabel = JBLabel(feature)
            featureLabel.font = featureLabel.font.deriveFont(16f)
            featureLabel.alignmentX = Component.CENTER_ALIGNMENT
            panel.add(featureLabel)
            panel.add(Box.createVerticalStrut(10))
        }
        
        return panel
    }
    
    private fun generateAllTests() {
        Messages.showInfoMessage(
            project,
            "Bulk test generation will be implemented in Phase 3.",
            "Feature Coming Soon"
        )
    }
    
    private fun refreshOverview() {
        Messages.showInfoMessage(
            project,
            "Test plans overview refreshed.",
            "Refresh Complete"
        )
    }
}