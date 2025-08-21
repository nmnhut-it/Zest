package com.zps.zest.codehealth.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.CodeHealthAnalyzer
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.*

class CodeHealthIssueDetailDialog(
    private val project: Project,
    private val method: CodeHealthAnalyzer.MethodHealthResult,
    private val issue: CodeHealthAnalyzer.HealthIssue
) : DialogWrapper(project, true) {
    
    init {
        title = "Code Health Issue Details"
        setOKButtonText("Close")
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(900, 700)
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Header with issue title and severity
        mainPanel.add(createHeaderPanel(), BorderLayout.NORTH)
        
        // Main content with issue details and code
        mainPanel.add(createContentPanel(), BorderLayout.CENTER)
        
        // Footer with action buttons
        mainPanel.add(createActionPanel(), BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val severityColor = getSeverityColor(issue.severity)
        panel.background = severityColor
        panel.border = EmptyBorder(15, 20, 15, 20)
        
        // Issue title - use appropriate text color based on background
        val titleLabel = JBLabel(issue.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.foreground = getContrastingTextColor(severityColor)
        panel.add(titleLabel, BorderLayout.WEST)
        
        // Severity badge
        val severityPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        severityPanel.isOpaque = false
        
        val severityBadge = JBLabel("Severity: ${issue.severity}/5")
        severityBadge.font = severityBadge.font.deriveFont(Font.BOLD, 14f)
        val textColor = getContrastingTextColor(severityColor)
        severityBadge.foreground = textColor
        severityBadge.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(textColor, 2),
            EmptyBorder(5, 10, 5, 10)
        )
        severityPanel.add(severityBadge)
        
        panel.add(severityPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createContentPanel(): JComponent {
        val contentPanel = JPanel(BorderLayout())
        contentPanel.background = UIUtil.getPanelBackground()
        contentPanel.border = EmptyBorder(20, 20, 20, 20)
        
        // Split pane for details and code
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitPane.dividerLocation = 250
        splitPane.border = null
        
        // Top: Issue details
        splitPane.topComponent = createDetailsPanel()
        
        // Bottom: Code view
        splitPane.bottomComponent = createCodePanel()
        
        contentPanel.add(splitPane, BorderLayout.CENTER)
        
        return contentPanel
    }
    
    private fun createDetailsPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = UIUtil.getPanelBackground()
        
        // Method info
        panel.add(createInfoRow("📍 Method:", formatMethodName(method.fqn)))
        panel.add(Box.createVerticalStrut(10))
        
        // Issue category
        panel.add(createInfoRow("📂 Category:", issue.issueCategory))
        panel.add(Box.createVerticalStrut(10))
        
        // Health score
        panel.add(createInfoRow("💯 Health Score:", "${method.healthScore}/100"))
        panel.add(Box.createVerticalStrut(15))
        
        // Description
        panel.add(createSectionHeader("📋 Description"))
        panel.add(createWrappedTextArea(issue.description))
        panel.add(Box.createVerticalStrut(15))
        
        // Impact
        panel.add(createSectionHeader("⚠️ Impact"))
        panel.add(createWrappedTextArea(issue.impact))
        panel.add(Box.createVerticalStrut(15))
        
        // Suggested fix
        panel.add(createSectionHeader("💡 Suggested Fix"))
        panel.add(createWrappedTextArea(issue.suggestedFix))
        
        return JBScrollPane(panel)
    }
    
    private fun createCodePanel(): JComponent {
        val codePanel = JPanel(BorderLayout())
        codePanel.background = UIUtil.getPanelBackground()
        
        // Header - show what type of code preview we're displaying
        val headerText = when {
            method.annotatedCode.isNotBlank() -> "📝 Code Preview (with AI comments)"
            method.originalCode.isNotBlank() -> "📝 Code Preview (original)"
            else -> "📝 Code Preview"
        }
        val headerLabel = JBLabel(headerText)
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 14f)
        headerLabel.border = EmptyBorder(10, 10, 10, 10)
        codePanel.add(headerLabel, BorderLayout.NORTH)
        
        // Code text pane
        val codeTextPane = JTextPane()
        codeTextPane.isEditable = false
        codeTextPane.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        
        // Set up syntax highlighting
        val doc = codeTextPane.styledDocument
        val defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        
        // Define styles
        val keywordStyle = doc.addStyle("keyword", defaultStyle)
        StyleConstants.setForeground(keywordStyle, JBColor(Color(0, 0, 128), Color(134, 179, 255)))
        StyleConstants.setBold(keywordStyle, true)
        
        val stringStyle = doc.addStyle("string", defaultStyle)
        StyleConstants.setForeground(stringStyle, JBColor(Color(0, 128, 0), Color(165, 194, 97)))
        
        val commentStyle = doc.addStyle("comment", defaultStyle)
        StyleConstants.setForeground(commentStyle, JBColor(Color(128, 128, 128), Color(128, 128, 128)))
        StyleConstants.setItalic(commentStyle, true)
        
        val errorStyle = doc.addStyle("error", defaultStyle)
        StyleConstants.setBackground(errorStyle, JBColor(Color(255, 230, 230), Color(90, 30, 30)))
        StyleConstants.setForeground(errorStyle, JBColor(Color(139, 0, 0), Color(255, 100, 100)))
        
        // Load and display code
        val codeContent = loadMethodCode()
        if (codeContent != null) {
            displayCodeWithHighlight(codeTextPane, codeContent)
        } else {
            doc.insertString(0, "// Code preview not available\n// Navigate to the method to view the actual code", commentStyle)
        }
        
        val scrollPane = JBScrollPane(codeTextPane)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        codePanel.add(scrollPane, BorderLayout.CENTER)
        
        return codePanel
    }
    
    private fun createActionPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 15, 10))
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0),
            EmptyBorder(15, 20, 15, 20)
        )
        
        // Go to Method button
        val goToMethodBtn = JButton("🔍 Go to Method")
        goToMethodBtn.font = goToMethodBtn.font.deriveFont(14f)
        goToMethodBtn.addActionListener {
            navigateToMethod()
            close(OK_EXIT_CODE)
        }
        panel.add(goToMethodBtn)
        
        // Fix with AI button
        val fixWithAIBtn = JButton("🤖 Fix with AI")
        fixWithAIBtn.font = fixWithAIBtn.font.deriveFont(14f)
        fixWithAIBtn.addActionListener {
            fixWithAI()
        }
        panel.add(fixWithAIBtn)
        
        // Mark as False Positive button
        val falsePositiveBtn = JButton("❌ Mark as False Positive")
        falsePositiveBtn.font = falsePositiveBtn.font.deriveFont(14f)
        falsePositiveBtn.addActionListener {
            markAsFalsePositive()
        }
        panel.add(falsePositiveBtn)
        
        // Copy Details button
        val copyBtn = JButton("📋 Copy Details")
        copyBtn.font = copyBtn.font.deriveFont(14f)
        copyBtn.addActionListener {
            copyDetailsToClipboard()
        }
        panel.add(copyBtn)
        
        return panel
    }
    
    private fun createInfoRow(label: String, value: String): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Integer.MAX_VALUE, 30)
        
        val labelComponent = JBLabel(label)
        labelComponent.font = labelComponent.font.deriveFont(Font.BOLD)
        panel.add(labelComponent)
        
        panel.add(Box.createHorizontalStrut(10))
        
        val valueComponent = JBLabel(value)
        panel.add(valueComponent)
        
        return panel
    }
    
    private fun createSectionHeader(title: String): JComponent {
        val label = JBLabel(title)
        label.font = label.font.deriveFont(Font.BOLD, 13f)
        label.border = EmptyBorder(5, 0, 5, 0)
        return label
    }
    
    private fun createWrappedTextArea(text: String): JComponent {
        val textArea = JTextArea(text)
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = UIUtil.getPanelBackground()
        textArea.font = textArea.font.deriveFont(12f)
        textArea.border = EmptyBorder(5, 10, 5, 10)
        return textArea
    }
    
    private fun loadMethodCode(): String? {
        // Use LLM-provided annotated code if available, otherwise fall back to original code
        return when {
            method.annotatedCode.isNotBlank() -> method.annotatedCode
            method.originalCode.isNotBlank() -> method.originalCode
            method.codeContext.isNotBlank() -> method.codeContext
            else -> "// Code preview not available\n// The code analysis was performed but no code content was provided"
        }
    }
    
    private fun displayCodeWithHighlight(textPane: JTextPane, code: String) {
        val doc = textPane.styledDocument
        val defaultStyle = doc.getStyle(StyleContext.DEFAULT_STYLE)
        val errorStyle = doc.getStyle("error")
        val keywordStyle = doc.getStyle("keyword")
        val commentStyle = doc.getStyle("comment")
        
        // Create styles for LLM comments with different severity levels
        val criticalStyle = doc.addStyle("critical", defaultStyle)
        StyleConstants.setBackground(criticalStyle, JBColor(Color(255, 235, 235), Color(80, 30, 30)))
        StyleConstants.setForeground(criticalStyle, JBColor(Color(139, 0, 0), Color(255, 100, 100)))
        StyleConstants.setBold(criticalStyle, true)
        
        val warningStyle = doc.addStyle("warning", defaultStyle)
        StyleConstants.setBackground(warningStyle, JBColor(Color(255, 248, 225), Color(80, 65, 20)))
        StyleConstants.setForeground(warningStyle, JBColor(Color(204, 85, 0), Color(255, 180, 100)))
        StyleConstants.setBold(warningStyle, true)
        
        val suggestionStyle = doc.addStyle("suggestion", defaultStyle)
        StyleConstants.setBackground(suggestionStyle, JBColor(Color(240, 255, 240), Color(20, 60, 20)))
        StyleConstants.setForeground(suggestionStyle, JBColor(Color(107, 142, 35), Color(150, 255, 150)))
        StyleConstants.setBold(suggestionStyle, true)
        
        try {
            // Insert code with enhanced syntax highlighting including LLM comments
            val lines = code.split("\n")
            for (line in lines) {
                val style = when {
                    // LLM Critical comments
                    line.trim().startsWith("// 🔴 CRITICAL:") || line.contains("🔴 CRITICAL:") -> criticalStyle
                    // LLM Warning comments  
                    line.trim().startsWith("// 🟠 WARNING:") || line.contains("🟠 WARNING:") -> warningStyle
                    // LLM Suggestion comments
                    line.trim().startsWith("// 🟡 SUGGESTION:") || line.contains("🟡 SUGGESTION:") -> suggestionStyle
                    // Regular comments
                    line.trim().startsWith("//") -> commentStyle
                    // Keywords
                    line.contains("public") || line.contains("void") || line.contains("private") ||
                    line.contains("try") || line.contains("catch") || line.contains("if") ||
                    line.contains("for") || line.contains("while") || line.contains("return") -> keywordStyle
                    // Default code
                    else -> defaultStyle
                }
                doc.insertString(doc.length, line + "\n", style)
            }
        } catch (e: BadLocationException) {
            // Handle error gracefully
            println("Error highlighting code: ${e.message}")
        }
    }
    
    private fun navigateToMethod() {
        // Navigate to the method in the editor
        if (method.fqn.contains(":")) {
            // JavaScript/TypeScript file with line numbers
            val parts = method.fqn.split(":")
            if (parts.size >= 2) {
                val filePath = parts[0]
                val lineNumber = parts[1].toIntOrNull() ?: 1
                // Navigate to file and line
                // Implementation depends on your navigation logic
            }
        } else {
            // Java method - navigate using PSI
            val psiMethod = findPsiMethod(method.fqn)
            if (psiMethod != null) {
                psiMethod.navigate(true)
            }
        }
    }
    
    private fun findPsiMethod(fqn: String): com.intellij.psi.PsiMethod? {
        val lastDotIndex = fqn.lastIndexOf('.')
        if (lastDotIndex == -1) return null
        
        val className = fqn.substring(0, lastDotIndex)
        val methodName = fqn.substring(lastDotIndex + 1)
        
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project))
        
        return psiClass?.findMethodsByName(methodName, false)?.firstOrNull()
    }
    
    private fun fixWithAI() {
        Messages.showInfoMessage(
            project,
            "AI-powered fix suggestion will be implemented soon.\n" +
            "The AI will analyze the issue and suggest code changes.",
            "Fix with AI"
        )
        // TODO: Implement AI fix integration
    }
    
    private fun markAsFalsePositive() {
        issue.falsePositive = true
        Messages.showInfoMessage(
            project,
            "Issue marked as false positive.\n" +
            "It will not appear in future reports.",
            "False Positive Marked"
        )
        close(OK_EXIT_CODE)
    }
    
    private fun copyDetailsToClipboard() {
        val details = buildString {
            appendLine("Code Health Issue Report")
            appendLine("=" .repeat(50))
            appendLine("Method: ${method.fqn}")
            appendLine("Health Score: ${method.healthScore}/100")
            appendLine()
            appendLine("Issue: ${issue.title}")
            appendLine("Category: ${issue.issueCategory}")
            appendLine("Severity: ${issue.severity}/5")
            appendLine()
            appendLine("Description:")
            appendLine(issue.description)
            appendLine()
            appendLine("Impact:")
            appendLine(issue.impact)
            appendLine()
            appendLine("Suggested Fix:")
            appendLine(issue.suggestedFix)
        }
        
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(details)
        clipboard.setContents(selection, selection)
        
        Messages.showInfoMessage(project, "Issue details copied to clipboard!", "Copied")
    }
    
    private fun getSeverityColor(severity: Int): Color {
        return when (severity) {
            5 -> JBColor(Color(139, 0, 0), Color(139, 0, 0))           // Critical - Dark Red (good contrast)
            4 -> JBColor(Color(204, 85, 0), Color(204, 85, 0))         // High - Dark Orange (good contrast)
            3 -> JBColor(Color(204, 119, 34), Color(204, 119, 34))     // Medium - Burnt Orange (good contrast)
            2 -> JBColor(Color(107, 142, 35), Color(124, 179, 66))     // Low - Olive Green (good contrast)
            else -> JBColor(Color(46, 125, 50), Color(67, 160, 71))    // Info - Green (good contrast)
        }
    }
    
    private fun getContrastingTextColor(backgroundColor: Color): Color {
        // Calculate luminance to determine if we need light or dark text
        val luminance = (0.299 * backgroundColor.red + 0.587 * backgroundColor.green + 0.114 * backgroundColor.blue) / 255
        return if (luminance > 0.5) {
            JBColor.BLACK  // Use black text on light backgrounds
        } else {
            JBColor.WHITE  // Use white text on dark backgrounds
        }
    }
    
    private fun formatMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            val lineInfo = fqn.substring(colonIndex)
            fileName + lineInfo
        } else {
            // Java method FQN - show class.method
            val lastDot = fqn.lastIndexOf('.')
            if (lastDot > 0) {
                val className = fqn.substring(0, lastDot).substringAfterLast('.')
                val methodName = fqn.substring(lastDot + 1)
                "$className.$methodName"
            } else {
                fqn
            }
        }
    }
    
    private fun extractMethodName(fqn: String): String {
        return fqn.substringAfterLast('.').substringAfterLast(':')
    }
    
    override fun createActions(): Array<Action> {
        // Only show OK button (labeled as "Close")
        return arrayOf(okAction)
    }
}