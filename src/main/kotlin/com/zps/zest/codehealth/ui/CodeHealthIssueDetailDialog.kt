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
import java.awt.RenderingHints

class CodeHealthIssueDetailDialog(
    private val project: Project,
    private val method: CodeHealthAnalyzer.MethodHealthResult,
    private val issue: CodeHealthAnalyzer.HealthIssue
) : DialogWrapper(project, true) {
    
    init {
        title = "Code Health: ${formatMethodName(method.fqn)}"
        setOKButtonText("Close")
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        mainPanel.preferredSize = Dimension(
            (screenSize.width * 0.7).toInt().coerceAtMost(1200),
            (screenSize.height * 0.8).toInt().coerceAtMost(900)
        )
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Simplified header
        mainPanel.add(createSimplifiedHeader(), BorderLayout.NORTH)
        
        // Single code editor panel with everything integrated
        mainPanel.add(createIntegratedCodePanel(), BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createSimplifiedHeader(): JComponent {
        val toolbar = JPanel(BorderLayout())
        toolbar.background = UIUtil.getPanelBackground()
        toolbar.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
            EmptyBorder(10, 15, 10, 15)
        )
        
        // Left side: Method info and health score
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0))
        leftPanel.isOpaque = false
        
        val methodLabel = JBLabel(formatMethodName(method.fqn))
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 14f)
        methodLabel.icon = AllIcons.Nodes.Method
        leftPanel.add(methodLabel)
        
        val scoreLabel = JBLabel("Health: ${method.healthScore}/100")
        scoreLabel.foreground = getScoreColor(method.healthScore)
        leftPanel.add(scoreLabel)
        
        toolbar.add(leftPanel, BorderLayout.WEST)
        
        // Right side: Action buttons
        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        actionPanel.isOpaque = false
        
        val goToMethodBtn = JButton(AllIcons.Actions.EditSource)
        goToMethodBtn.toolTipText = "Go to Method"
        goToMethodBtn.addActionListener { navigateToMethod() }
        actionPanel.add(goToMethodBtn)
        
        val copyBtn = JButton(AllIcons.Actions.Copy)
        copyBtn.toolTipText = "Copy Details"
        copyBtn.addActionListener { copyDetailsToClipboard() }
        actionPanel.add(copyBtn)
        
        val falsePositiveBtn = JButton(AllIcons.Actions.Cancel)
        falsePositiveBtn.toolTipText = "Mark as False Positive"
        falsePositiveBtn.addActionListener { markAsFalsePositive() }
        actionPanel.add(falsePositiveBtn)
        
        toolbar.add(actionPanel, BorderLayout.EAST)
        
        return toolbar
    }
    
    private fun createIntegratedCodePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        // Create code editor with line numbers
        val codeTextPane = JTextPane()
        codeTextPane.isEditable = false
        codeTextPane.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        codeTextPane.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        codeTextPane.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        
        // Build the integrated content
        val integratedCode = buildIntegratedCodeView()
        displayIntegratedCode(codeTextPane, integratedCode)
        
        // Add line numbers
        val scrollPane = JBScrollPane(codeTextPane)
        scrollPane.border = null
        val lineNumbers = LineNumberComponent(codeTextPane)
        scrollPane.setRowHeaderView(lineNumbers)
        
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Add subtle severity indicator on the side
        val severityIndicator = createSeverityIndicator()
        panel.add(severityIndicator, BorderLayout.WEST)
        
        return panel
    }
    
    private fun buildIntegratedCodeView(): String {
        return buildString {
            // Add comprehensive header comment with all issue details
            appendLine("/**")
            appendLine(" * CODE HEALTH ANALYSIS REPORT")
            appendLine(" * " + "=".repeat(60))
            appendLine(" * ")
            appendLine(" * Method: ${method.fqn}")
            appendLine(" * Health Score: ${method.healthScore}/100")
            appendLine(" * Analysis Date: ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
            appendLine(" * ")
            appendLine(" * ISSUE: ${issue.title}")
            appendLine(" * " + "-".repeat(60))
            appendLine(" * Category: ${issue.issueCategory}")
            appendLine(" * Severity: ${getSeverityText(issue.severity)} (${issue.severity}/5)")
            appendLine(" * ")
            appendLine(" * DESCRIPTION:")
            wrapAndAppendComment(issue.description, " * ")
            appendLine(" * ")
            appendLine(" * IMPACT:")
            wrapAndAppendComment(issue.impact, " * ")
            appendLine(" * ")
            appendLine(" * SUGGESTED FIX:")
            wrapAndAppendComment(issue.suggestedFix, " * ")
            appendLine(" */")
            appendLine()
            
            // Add the actual code with AI annotations
            val code = when {
                method.annotatedCode.isNotBlank() -> method.annotatedCode
                method.originalCode.isNotBlank() -> method.originalCode
                method.codeContext.isNotBlank() -> method.codeContext
                else -> "// Code preview not available\n// Navigate to the method to view the actual code"
            }
            append(code)
        }
    }
    
    private fun StringBuilder.wrapAndAppendComment(text: String, prefix: String, maxWidth: Int = 80) {
        val words = text.split(" ")
        var currentLine = StringBuilder()
        
        for (word in words) {
            if (currentLine.length + word.length + 1 > maxWidth - prefix.length) {
                if (currentLine.isNotEmpty()) {
                    appendLine(prefix + currentLine.toString().trim())
                    currentLine = StringBuilder()
                }
            }
            if (currentLine.isNotEmpty()) currentLine.append(" ")
            currentLine.append(word)
        }
        
        if (currentLine.isNotEmpty()) {
            appendLine(prefix + currentLine.toString().trim())
        }
    }
    
    private fun createSeverityIndicator(): JComponent {
        val indicator = JPanel()
        indicator.preferredSize = Dimension(5, 0)
        indicator.background = getSeverityIndicatorColor(issue.severity)
        return indicator
    }
    
    private fun getSeverityIndicatorColor(severity: Int): Color {
        return when (severity) {
            5 -> JBColor(Color(220, 53, 69), Color(220, 53, 69))     // Red
            4 -> JBColor(Color(255, 138, 0), Color(255, 138, 0))     // Orange
            3 -> JBColor(Color(255, 193, 7), Color(255, 193, 7))     // Yellow
            2 -> JBColor(Color(40, 167, 69), Color(40, 167, 69))     // Green
            else -> JBColor(Color(23, 162, 184), Color(23, 162, 184)) // Cyan
        }
    }
    
    private fun getSeverityText(severity: Int): String {
        return when (severity) {
            5 -> "CRITICAL"
            4 -> "HIGH"
            3 -> "MEDIUM"
            2 -> "LOW"
            else -> "INFO"
        }
    }
    
    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> JBColor(Color(40, 167, 69), Color(40, 167, 69))    // Green
            score >= 60 -> JBColor(Color(255, 193, 7), Color(255, 193, 7))    // Yellow
            else -> JBColor(Color(220, 53, 69), Color(220, 53, 69))           // Red
        }
    }
    
    // Line number component for the code editor
    private inner class LineNumberComponent(private val textPane: JTextPane) : JComponent() {
        init {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            preferredSize = Dimension(50, 0)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 0, 1)
        }
        
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            
            g2d.color = background
            g2d.fillRect(0, 0, width, height)
            
            g2d.color = UIUtil.getInactiveTextColor()
            g2d.font = font
            
            val fontMetrics = g2d.fontMetrics
            val fontHeight = fontMetrics.height
            
            try {
                val doc = textPane.document
                val text = doc.getText(0, doc.length)
                val lines = text.split("\n")
                
                for (i in lines.indices) {
                    val lineNumber = (i + 1).toString()
                    val x = width - fontMetrics.stringWidth(lineNumber) - 5
                    val y = (i + 1) * fontHeight
                    g2d.drawString(lineNumber, x, y)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    
    
    private fun displayIntegratedCode(textPane: JTextPane, code: String) {
        val doc = textPane.styledDocument
        val defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        
        // Define styles for different parts
        val headerCommentStyle = doc.addStyle("headerComment", defaultStyle)
        StyleConstants.setForeground(headerCommentStyle, JBColor(Color(0, 128, 0), Color(98, 151, 85)))
        StyleConstants.setBackground(headerCommentStyle, JBColor(Color(245, 255, 245), Color(30, 40, 30)))
        StyleConstants.setItalic(headerCommentStyle, true)
        
        val keywordStyle = doc.addStyle("keyword", defaultStyle)
        StyleConstants.setForeground(keywordStyle, JBColor(Color(0, 0, 128), Color(134, 179, 255)))
        StyleConstants.setBold(keywordStyle, true)
        
        val commentStyle = doc.addStyle("comment", defaultStyle)
        StyleConstants.setForeground(commentStyle, JBColor(Color(128, 128, 128), Color(128, 128, 128)))
        StyleConstants.setItalic(commentStyle, true)
        
        val criticalStyle = doc.addStyle("critical", defaultStyle)
        StyleConstants.setForeground(criticalStyle, JBColor(Color(220, 53, 69), Color(255, 100, 100)))
        StyleConstants.setBold(criticalStyle, true)
        
        val warningStyle = doc.addStyle("warning", defaultStyle)
        StyleConstants.setForeground(warningStyle, JBColor(Color(255, 138, 0), Color(255, 180, 100)))
        StyleConstants.setBold(warningStyle, true)
        
        val suggestionStyle = doc.addStyle("suggestion", defaultStyle)
        StyleConstants.setForeground(suggestionStyle, JBColor(Color(40, 167, 69), Color(150, 255, 150)))
        
        try {
            val lines = code.split("\n")
            var inHeaderComment = false
            
            for (line in lines) {
                // Check if we're in the header comment block
                if (line.trim().startsWith("/**")) {
                    inHeaderComment = true
                }
                
                val style = when {
                    // Header comment block
                    inHeaderComment -> {
                        if (line.trim().endsWith("*/")) inHeaderComment = false
                        headerCommentStyle
                    }
                    // AI annotations
                    line.contains("🔴 CRITICAL:") || line.contains("CRITICAL:") -> criticalStyle
                    line.contains("🟠 WARNING:") || line.contains("WARNING:") -> warningStyle
                    line.contains("🟡 SUGGESTION:") || line.contains("SUGGESTION:") -> suggestionStyle
                    // Regular comments
                    line.trim().startsWith("//") || line.trim().startsWith("/*") || line.trim().startsWith("*") -> commentStyle
                    // Keywords - more comprehensive list
                    line.contains(Regex("\\b(public|private|protected|static|final|void|int|String|boolean|class|interface|extends|implements|return|if|else|for|while|try|catch|throw|new|this|super)\\b")) -> keywordStyle
                    // Default
                    else -> defaultStyle
                }
                
                doc.insertString(doc.length, line + "\n", style)
            }
        } catch (e: BadLocationException) {
            // Silently handle
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
                // TODO: Navigate to file and line
            }
        } else {
            // Java method - navigate using PSI
            val psiMethod = findPsiMethod(method.fqn)
            if (psiMethod != null) {
                psiMethod.navigate(true)
            }
        }
        close(OK_EXIT_CODE)
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
    
    
    override fun createActions(): Array<Action> {
        // Only show OK button (labeled as "Close")
        return arrayOf(okAction)
    }
}