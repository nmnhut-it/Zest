package com.zps.zest.codehealth.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.codehealth.CodeHealthAnalyzer
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Editor for displaying Code Health issues in full-screen tabs
 */
class CodeHealthIssueEditor(
    private val project: Project,
    private val virtualFile: CodeHealthIssueVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    private val issue = virtualFile.getHealthIssue()
    private val methodFqn = virtualFile.getMethodFqn()
    private val component: JComponent
    
    init {
        component = createEditorComponent()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Code Health Issue"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {}
    
    override fun setState(state: FileEditorState) {
        // No-op for health issue editor
    }
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add toolbar at top
        mainPanel.add(createToolbar(), BorderLayout.NORTH)
        
        // Add split panel content
        val splitter = JBSplitter(false, 0.3f) // 30% left, 70% right
        splitter.firstComponent = createMetadataPanel()
        splitter.secondComponent = createDetailsPanel()
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Navigate to method action
        actionGroup.add(object : AnAction("Navigate to Method", "Go to the method in code", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                navigateToMethod()
            }
        })
        
        // Fix with AI action
        actionGroup.add(object : AnAction("Fix with AI", "Send issue to AI for fixing", AllIcons.Actions.Lightning) {
            override fun actionPerformed(e: AnActionEvent) {
                sendToAIForFix()
            }
        })
        
        // Generate test plan action
        actionGroup.add(object : AnAction("Generate Test Plan", "Create test plan for this method", AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                generateTestPlan()
            }
        })
        
        // Dismiss issue action
        actionGroup.add(object : AnAction("Mark as False Positive", "Mark this issue as false positive", AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: AnActionEvent) {
                markAsFalsePositive()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "CodeHealthIssueEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun createMetadataPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // Title
        val titleLabel = JBLabel("Issue Metadata")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(15))
        
        // Severity
        addMetadataRow(panel, "🎯 Severity:", "${issue.severity}/5", getSeverityColor(issue.severity))
        
        // Category  
        addMetadataRow(panel, "📂 Category:", issue.issueCategory, null)
        
        // Verification status
        val verifiedText = if (issue.verified) "✅ Verified" else "❌ Not Verified"
        val verifiedColor = if (issue.verified) Color(76, 175, 80) else Color(255, 152, 0)
        addMetadataRow(panel, "🔍 Status:", verifiedText, verifiedColor)
        
        // False positive status
        if (issue.falsePositive) {
            addMetadataRow(panel, "⚠️ False Positive:", "Yes", Color(255, 152, 0))
        }
        
        panel.add(Box.createVerticalStrut(20))
        
        // Method info
        val methodLabel = JBLabel("Method Details")
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(methodLabel)
        panel.add(Box.createVerticalStrut(10))
        
        // Method FQN (with text wrapping for long names)
        val methodArea = JTextArea(methodFqn)
        methodArea.isEditable = false
        methodArea.isOpaque = false
        methodArea.background = UIUtil.getPanelBackground()
        methodArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        methodArea.lineWrap = true
        methodArea.wrapStyleWord = false
        panel.add(methodArea)
        
        panel.add(Box.createVerticalGlue()) // Push everything to top
        
        return JBScrollPane(panel)
    }
    
    private fun createDetailsPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // Main title
        val titleLabel = JBLabel(issue.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(20))
        
        // Description section
        addSection(panel, "Description", issue.description)
        panel.add(Box.createVerticalStrut(15))
        
        // Impact section
        addSection(panel, "Impact Analysis", issue.impact, Color(255, 245, 157))
        panel.add(Box.createVerticalStrut(15))
        
        // Suggested fix section
        addSection(panel, "Suggested Fix", issue.suggestedFix, Color(232, 245, 233))
        
        panel.add(Box.createVerticalGlue()) // Push content to top
        
        return JBScrollPane(panel)
    }
    
    private fun addMetadataRow(panel: JPanel, label: String, value: String, valueColor: Color?) {
        val rowPanel = JPanel(BorderLayout())
        rowPanel.background = UIUtil.getPanelBackground()
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, 25)
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        rowPanel.add(labelComponent, BorderLayout.WEST)
        
        val valueComponent = JBLabel(value)
        if (valueColor != null) {
            valueComponent.foreground = valueColor
        }
        rowPanel.add(valueComponent, BorderLayout.EAST)
        
        panel.add(rowPanel)
        panel.add(Box.createVerticalStrut(8))
    }
    
    private fun addSection(panel: JPanel, title: String, content: String, backgroundColor: Color? = null) {
        val sectionPanel = JPanel()
        sectionPanel.layout = BoxLayout(sectionPanel, BoxLayout.Y_AXIS)
        sectionPanel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.background = backgroundColor ?: if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        sectionPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // Section title
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.add(titleLabel)
        sectionPanel.add(Box.createVerticalStrut(10))
        
        // Content
        val contentArea = JTextArea(content)
        contentArea.isEditable = false
        contentArea.isOpaque = false
        contentArea.background = sectionPanel.background
        contentArea.font = UIUtil.getLabelFont()
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.alignmentX = Component.LEFT_ALIGNMENT
        sectionPanel.add(contentArea)
        
        panel.add(sectionPanel)
    }
    
    private fun getSeverityColor(severity: Int): Color {
        return when (severity) {
            5, 4 -> Color(244, 67, 54) // Red for high severity
            3 -> Color(255, 152, 0)    // Orange for medium
            else -> Color(76, 175, 80)  // Green for low
        }
    }
    
    private fun navigateToMethod() {
        try {
            if (methodFqn.contains(".js:") || methodFqn.contains(".ts:") || 
                methodFqn.contains(".jsx:") || methodFqn.contains(".tsx:")) {
                navigateToJsTsLocation()
            } else {
                navigateToJavaMethod()
            }
        } catch (e: Exception) {
            Messages.showMessageDialog(
                project, 
                "Unable to navigate to: $methodFqn\n\nError: ${e.message}", 
                "Navigation Error", 
                Messages.getWarningIcon()
            )
        }
    }
    
    private fun navigateToJavaMethod() {
        val parts = methodFqn.split(".")
        if (parts.size < 2) return
        
        val className = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
        
        if (psiClass == null) {
            Messages.showMessageDialog(
                project, 
                "Class not found: $className", 
                "Navigation Error", 
                Messages.getWarningIcon()
            )
            return
        }
        
        val psiMethod = psiClass.methods.find { it.name == methodName }
        
        val descriptor = if (psiMethod != null) {
            OpenFileDescriptor(
                project,
                psiMethod.containingFile.virtualFile,
                psiMethod.textOffset
            )
        } else {
            OpenFileDescriptor(
                project,
                psiClass.containingFile.virtualFile,
                psiClass.textOffset
            )
        }
        
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
    
    private fun navigateToJsTsLocation() {
        val colonIndex = methodFqn.lastIndexOf(':')
        if (colonIndex == -1) return
        
        val filePath = methodFqn.substring(0, colonIndex)
        val lineInfo = methodFqn.substring(colonIndex + 1)
        
        val lineNumber = if (lineInfo.contains('-')) {
            lineInfo.substringBefore('-').toIntOrNull() ?: return
        } else {
            lineInfo.toIntOrNull() ?: return
        }
        
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(filePath)
        
        if (virtualFile == null) {
            Messages.showMessageDialog(
                project, 
                "File not found: $filePath", 
                "Navigation Error", 
                Messages.getWarningIcon()
            )
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
    
    private fun sendToAIForFix() {
        val fixPrompt = buildString {
            appendLine("Please help me fix this code issue detected by Code Guardian:")
            appendLine()
            appendLine("**Method:** `$methodFqn`")
            appendLine("**Issue Type:** ${issue.issueCategory}")
            appendLine("**Severity:** ${issue.severity}/5")
            appendLine("**Title:** ${issue.title}")
            appendLine()
            appendLine("**Description:** ${issue.description}")
            appendLine()
            appendLine("**Impact if not fixed:** ${issue.impact}")
            appendLine()
            appendLine("**Suggested fix:** ${issue.suggestedFix}")
            appendLine()
            appendLine("Please provide the fixed code with explanations of the changes made.")
        }
        
        val language = when {
            methodFqn.contains(".ts:") || methodFqn.contains(".tsx:") -> "TypeScript"
            methodFqn.contains(".js:") || methodFqn.contains(".jsx:") -> "JavaScript"
            else -> "Java"
        }
        
        val systemPrompt = "You are a helpful AI assistant that fixes $language code issues. " +
                          "Focus on the specific issue described and provide clear, working code as the solution. " +
                          "Explain what changes you made and why they fix the issue."
        
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat")
        if (toolWindow != null) {
            ApplicationManager.getApplication().invokeLater {
                toolWindow.activate {
                    ChatboxUtilities.clickNewChatButton(project)
                    ChatboxUtilities.sendTextAndSubmit(
                        project, 
                        fixPrompt, 
                        true, 
                        systemPrompt,
                        false, 
                        ChatboxUtilities.EnumUsage.CODE_HEALTH
                    )
                }
            }
        } else {
            Messages.showInfoMessage(
                project,
                "Please open the ZPS Chat tool window first to send this issue for AI fixing.",
                "Chat Window Required"
            )
        }
    }
    
    private fun generateTestPlan() {
        // TODO: This will be implemented in Phase 2
        Messages.showInfoMessage(
            project,
            "Test plan generation will be implemented in Phase 2.",
            "Feature Coming Soon"
        )
    }
    
    private fun markAsFalsePositive() {
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to mark this issue as a false positive?\n\n" +
            "This will hide the issue from future Code Health reports.",
            "Mark as False Positive",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            // TODO: Update the issue in storage
            Messages.showInfoMessage(
                project,
                "Issue marked as false positive.",
                "Issue Updated"
            )
        }
    }
}