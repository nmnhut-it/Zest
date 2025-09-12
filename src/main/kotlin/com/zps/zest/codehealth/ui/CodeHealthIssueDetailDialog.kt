package com.zps.zest.codehealth.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.codehealth.CodeHealthAnalyzer
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.BadLocationException
import javax.swing.text.Style
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

class CodeHealthIssueDetailDialog(
    private val project: Project,
    private val method: CodeHealthAnalyzer.MethodHealthResult,
    private val issues: List<CodeHealthAnalyzer.HealthIssue>
) : DialogWrapper(project, true) {

    private lateinit var codeTextPane: JTextPane
    private lateinit var severityIndicator: JPanel
    private val listModel = DefaultListModel<CodeHealthAnalyzer.HealthIssue>()
    private val issueList = JList(listModel)

    init {
        title = "Code Health: ${formatMethodName(method.fqn)}"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = preferredDialogSize()
        panel.background = UIUtil.getPanelBackground()
        panel.add(createHeader(), BorderLayout.NORTH)
        panel.add(createMainContent(), BorderLayout.CENTER)
        return panel
    }

    private fun preferredDialogSize(): Dimension {
        val s = Toolkit.getDefaultToolkit().screenSize
        return Dimension((s.width * 0.7).toInt().coerceAtMost(1200), (s.height * 0.8).toInt().coerceAtMost(900))
    }

    private fun createHeader(): JComponent {
        val bar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                EmptyBorder(10, 15, 10, 15)
            )
        }
        bar.add(createHeaderLeft(), BorderLayout.WEST)
        bar.add(createHeaderActions(), BorderLayout.EAST)
        return bar
    }

    private fun createHeaderLeft(): JComponent {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0)).apply { isOpaque = false }
        val methodLabel = JBLabel(formatMethodName(method.fqn)).apply {
            font = font.deriveFont(Font.BOLD, 14f); icon = AllIcons.Nodes.Method
        }
        val scoreLabel = JBLabel("Health: ${method.healthScore}/100").apply {
            foreground = getScoreColor(method.healthScore)
        }
        p.add(methodLabel); p.add(scoreLabel)
        return p
    }

    private fun createHeaderActions(): JComponent {
        val p = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply { isOpaque = false }
        p.add(createButton(AllIcons.Actions.EditSource, "Go to Method") { navigateToMethod() })
        p.add(createButton(AllIcons.Actions.Copy, "Copy All Details") { copyDetailsToClipboard() })
        p.add(createButton(AllIcons.Actions.Cancel, "Mark Selected as False Positive") { markAsFalsePositive() })
        return p
    }

    private fun createMainContent(): JComponent {
        val content = JPanel(BorderLayout())
        content.add(createIssueListPanel(), BorderLayout.WEST)
        content.add(createCodePanel(), BorderLayout.CENTER)
        initializeSelectionAfterUi()
        return content
    }

    private fun createIssueListPanel(): JComponent {
        issues.forEach { listModel.addElement(it) }
        issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        issueList.cellRenderer = IssueCellRenderer()
        issueList.addListSelectionListener { if (!it.valueIsAdjusting) updateIssueSelection() }
        return JBScrollPane(issueList).apply {
            preferredSize = Dimension(280, 0)
            border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 1, 0, 1)
        }
    }

    private fun createCodePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        codeTextPane = JTextPane().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        }
        val scroll = JBScrollPane(codeTextPane).apply {
            border = null; setRowHeaderView(LineNumbers(this@CodeHealthIssueDetailDialog.codeTextPane))
        }
        severityIndicator = JPanel().apply { preferredSize = Dimension(5, 0) }
        panel.add(severityIndicator, BorderLayout.WEST)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun initializeSelectionAfterUi() {
        SwingUtilities.invokeLater {
            if (listModel.size() > 0 && issueList.selectedIndex == -1) issueList.selectedIndex =
                0 else updateIssueSelection()
        }
    }

    private fun updateIssueSelection() {
        if (!this::codeTextPane.isInitialized || !this::severityIndicator.isInitialized) return
        val issue = issueList.selectedValue ?: issues.firstOrNull()
        val code = buildIntegratedCodeView(issue)
        setStyledText(codeTextPane, code)
        severityIndicator.background = getSeverityIndicatorColor(issue?.severity ?: 1)
    }

    private fun buildIntegratedCodeView(issue: CodeHealthAnalyzer.HealthIssue?): String {
        return buildString {
            append(buildHeader(issue))
            appendLine()
            append(getCodeBody())
        }
    }

    private fun buildHeader(issue: CodeHealthAnalyzer.HealthIssue?): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return buildString {
            appendLine("/**")
            appendLine(" * CODE HEALTH ANALYSIS REPORT")
            appendLine(" * ${"=".repeat(60)}")
            appendLine(" * Method: ${method.fqn}")
            appendLine(" * Health Score: ${method.healthScore}/100")
            appendLine(" * Analysis Date: $now")
            if (issue == null) appendLine(" * No issues available.") else appendIssueDetails(issue)
            appendLine(" */")
        }
    }

    private fun StringBuilder.appendIssueDetails(issue: CodeHealthAnalyzer.HealthIssue) {
        appendLine(" * "); appendLine(" * ISSUE: ${issue.title}")
        appendLine(" * ${"-".repeat(60)}")
        appendLine(" * Category: ${issue.issueCategory}")
        appendLine(" * Severity: ${getSeverityText(issue.severity)} (${issue.severity}/5)")
        appendLine(" * "); appendLine(" * DESCRIPTION:"); wrapAndAppendComment(issue.description, " * ")
        appendLine(" * "); appendLine(" * IMPACT:"); wrapAndAppendComment(issue.impact, " * ")
        appendLine(" * "); appendLine(" * SUGGESTED FIX:"); wrapAndAppendComment(issue.suggestedFix, " * ")
    }

    private fun getCodeBody(): String {
        return when {
            method.annotatedCode.isNotBlank() -> method.annotatedCode
            method.originalCode.isNotBlank() -> method.originalCode
            method.codeContext.isNotBlank() -> method.codeContext
            else -> "// Code preview not available\n// Navigate to the method to view the actual code"
        }
    }

    private fun StringBuilder.wrapAndAppendComment(text: String, prefix: String, maxWidth: Int = 80) {
        var line = StringBuilder()
        text.split(" ").forEach { w ->
            if (line.length + w.length + 1 > maxWidth - prefix.length) {
                if (line.isNotEmpty()) appendLine(prefix + line.toString().trim())
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(" ")
            line.append(w)
        }
        if (line.isNotEmpty()) appendLine(prefix + line.toString().trim())
    }

    private fun setStyledText(textPane: JTextPane, text: String) {
        val doc = textPane.styledDocument
        try {
            doc.remove(0, doc.length)
            val styles = createStyles(doc)
            var inHeader = false
            text.lines().forEach { line ->
                val header = inHeader || line.trim().startsWith("/**")
                val style = determineStyle(line, styles, header)
                doc.insertString(doc.length, "$line\n", style)
                if (line.trim().startsWith("/**")) inHeader = true
                if (line.trim().endsWith("*/")) inHeader = false
            }
        } catch (_: BadLocationException) { /* ignore */
        }
    }

    private data class Styles(
        val def: Style, val header: Style, val keyword: Style,
        val comment: Style, val critical: Style, val warning: Style, val suggestion: Style
    )

    private fun createStyles(doc: javax.swing.text.StyledDocument): Styles {
        val base = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        fun style(
            name: String,
            fg: Color? = null,
            bg: Color? = null,
            bold: Boolean = false,
            italic: Boolean = false
        ): Style {
            val s = doc.addStyle(name, base)
            fg?.let { StyleConstants.setForeground(s, it) }
            bg?.let { StyleConstants.setBackground(s, it) }
            StyleConstants.setBold(s, bold); StyleConstants.setItalic(s, italic)
            return s
        }
        return Styles(
            base,
            style(
                "headerComment",
                JBColor(Color(0, 128, 0), Color(98, 151, 85)),
                JBColor(Color(245, 255, 245), Color(30, 40, 30)),
                italic = true
            ),
            style("keyword", JBColor(Color(0, 0, 128), Color(134, 179, 255)), bold = true),
            style("comment", JBColor(Color(128, 128, 128), Color(128, 128, 128)), italic = true),
            style("critical", JBColor(Color(220, 53, 69), Color(255, 100, 100)), bold = true),
            style("warning", JBColor(Color(255, 138, 0), Color(255, 180, 100)), bold = true),
            style("suggestion", JBColor(Color(40, 167, 69), Color(150, 255, 150)))
        )
    }

    private fun determineStyle(line: String, s: Styles, inHeader: Boolean): Style {
        val t = line.trim()
        if (inHeader) return s.header
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) return s.comment
        if (line.contains("CRITICAL:")) return s.critical
        if (line.contains("WARNING:")) return s.warning
        if (line.contains("SUGGESTION:")) return s.suggestion
        if (line.contains(Regex("\\b(public|private|protected|static|final|void|int|String|boolean|class|interface|extends|implements|return|if|else|for|while|try|catch|throw|new|this|super)\\b")))
            return s.keyword
        return s.def
    }

    private fun createButton(icon: Icon, tip: String, action: () -> Unit): JButton {
        return JButton(icon).apply { toolTipText = tip; addActionListener { action() } }
    }

    private fun getSeverityIndicatorColor(severity: Int): Color {
        return when (severity) {
            5 -> JBColor(Color(220, 53, 69), Color(220, 53, 69))
            4 -> JBColor(Color(255, 138, 0), Color(255, 138, 0))
            3 -> JBColor(Color(255, 193, 7), Color(255, 193, 7))
            2 -> JBColor(Color(40, 167, 69), Color(40, 167, 69))
            else -> JBColor(Color(23, 162, 184), Color(23, 162, 184))
        }
    }

    private fun getSeverityText(severity: Int): String {
        return when (severity) {
            5 -> "CRITICAL"; 4 -> "HIGH"; 3 -> "MEDIUM"; 2 -> "LOW"; else -> "INFO"
        }
    }

    private fun getScoreColor(score: Int): Color {
        return when {
            score >= 80 -> JBColor(Color(40, 167, 69), Color(40, 167, 69))
            score >= 60 -> JBColor(Color(255, 193, 7), Color(255, 193, 7))
            else -> JBColor(Color(220, 53, 69), Color(220, 53, 69))
        }
    }

    private inner class LineNumbers(private val text: JTextPane) : JComponent() {
        init {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            preferredSize = Dimension(50, 0)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 0, 1)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            paintBackground(g2); drawNumbers(g2)
        }

        private fun paintBackground(g2: Graphics2D) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background; g2.fillRect(0, 0, width, height)
            g2.color = UIUtil.getInactiveTextColor(); g2.font = font
        }

        private fun drawNumbers(g2: Graphics2D) {
            val fm = g2.fontMetrics
            val lines = getLinesSafe()
            for (i in lines.indices) {
                val n = (i + 1).toString()
                g2.drawString(n, width - fm.stringWidth(n) - 5, (i + 1) * fm.height)
            }
        }

        private fun getLinesSafe(): List<String> {
            return try {
                val doc = text.document
                doc.getText(0, doc.length).split("\n")
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun navigateToMethod() {
        if (method.fqn.contains(":")) {
            // TODO: Implement navigation for JS/TS with file:line
        } else {
            findPsiMethod(method.fqn)?.navigate(true)
        }
        close(OK_EXIT_CODE)
    }

    private fun findPsiMethod(fqn: String): com.intellij.psi.PsiMethod? {
        val i = fqn.lastIndexOf('.'); if (i == -1) return null
        val cls = fqn.substring(0, i);
        val m = fqn.substring(i + 1)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(cls, GlobalSearchScope.allScope(project))
        return psiClass?.findMethodsByName(m, false)?.firstOrNull()
    }

    private fun markAsFalsePositive() {
        val issue = issueList.selectedValue ?: return
        issue.falsePositive = true
        Messages.showInfoMessage(
            project,
            "Selected issue marked as false positive.\nIt will not appear in future reports.",
            "False Positive Marked"
        )
        close(OK_EXIT_CODE)
    }

    private fun copyDetailsToClipboard() {
        val details = buildString {
            appendLine("Code Health Issue Report")
            appendLine("=".repeat(50))
            appendLine("Method: ${method.fqn}")
            appendLine("Health Score: ${method.healthScore}/100")
            appendLine()
            issues.forEachIndexed { idx, issue ->
                append(buildIssueDetails(issue, idx + 1)); appendLine()
            }
        }
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        val sel = StringSelection(details)
        cb.setContents(sel, sel)
        Messages.showInfoMessage(project, "All issue details copied to clipboard!", "Copied")
    }

    private fun buildIssueDetails(issue: CodeHealthAnalyzer.HealthIssue, idx: Int): String {
        return buildString {
            appendLine("Issue #$idx: ${issue.title}")
            appendLine("Category: ${issue.issueCategory}")
            appendLine("Severity: ${issue.severity}/5 (${getSeverityText(issue.severity)})")
            appendLine("False Positive: ${issue.falsePositive}")
            appendLine(); appendLine("Description:"); appendLine(issue.description)
            appendLine(); appendLine("Impact:"); appendLine(issue.impact)
            appendLine(); appendLine("Suggested Fix:"); appendLine(issue.suggestedFix)
        }
    }

    private fun formatMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            val i = fqn.lastIndexOf(":")
            val file = fqn.substring(0, i).substringAfterLast("/").substringAfterLast("\\")
            file + fqn.substring(i)
        } else {
            val d = fqn.lastIndexOf('.')
            if (d > 0) fqn.substring(0, d).substringAfterLast('.') + "." + fqn.substring(d + 1) else fqn
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private class IssueCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val issue = value as? CodeHealthAnalyzer.HealthIssue
            c.text = issue?.let { "[${severityText(it.severity)}] ${it.title}" } ?: "No issues"
            c.icon = AllIcons.General.BalloonWarning
            return c
        }

        private fun severityText(s: Int) = when (s) {
            5 -> "CRITICAL"; 4 -> "HIGH"; 3 -> "MEDIUM"; 2 -> "LOW"; else -> "INFO"
        }
    }
}