package com.zps.zest.explanation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.explanation.agents.CodeExplanationAgent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Dialog for displaying comprehensive code explanation results with tabbed interface.
 * Shows explanation, related files, keywords, usage patterns, and detailed analysis.
 */
class CodeExplanationDialog(
    project: Project,
    private val result: CodeExplanationAgent.CodeExplanationResult
) : DialogWrapper(project, true) {

    init {
        title = "Code Explanation - ${extractFileName(result.filePath)}"
        setModal(false)
        isResizable = true
        
        // Debug logging to see what data we received
        println("[DEBUG] UI received data:")
        println("  - Keywords: ${result.extractedKeywords}")
        println("  - Related files: ${result.relatedFiles}")
        println("  - Usage patterns: ${result.usagePatterns}")
        println("  - Notes: ${result.notes}")
        println("  - Components: ${result.relatedComponents}")
        
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(800, 600)
        mainPanel.border = JBUI.Borders.empty(10)

        // Create tabbed pane for different views
        val tabbedPane = JBTabbedPane()

        // Overview Tab - Main explanation (always shown)
        tabbedPane.addTab("📋 Overview", createOverviewPanel())

        // Keywords Tab - Always show to help debug
        tabbedPane.addTab("🔍 Keywords", createKeywordsPanel())

        // Related Files Tab - Always show to help debug
        tabbedPane.addTab("📁 Related Files", createRelatedFilesPanel())

        // Usage Patterns Tab - Always show to help debug
        tabbedPane.addTab("🔗 Usage Patterns", createUsagePatternsPanel())

        // Analysis Notes Tab - Always show (most important for explaining how code works)
        tabbedPane.addTab("📝 How It Works", createAnalysisNotesPanel())

        // Code Components Tab - Always show to help debug
        tabbedPane.addTab("⚙️ Components", createComponentsPanel())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        // Add info bar at the bottom
        mainPanel.add(createInfoBar(), BorderLayout.SOUTH)

        return mainPanel
    }

    /**
     * Create the main overview panel with explanation text
     */
    private fun createOverviewPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        // File info header
        val headerPanel = createFileInfoHeader()
        panel.add(headerPanel, BorderLayout.NORTH)

        // Main explanation text
        val explanationArea = JBTextArea()
        explanationArea.text = result.explanation
        explanationArea.isEditable = false
        explanationArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        explanationArea.lineWrap = true
        explanationArea.wrapStyleWord = true
        explanationArea.background = UIUtil.getPanelBackground()

        val scrollPane = JBScrollPane(explanationArea)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.border = JBUI.Borders.empty()

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create file information header
     */
    private fun createFileInfoHeader(): JComponent {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = JBUI.Borders.emptyBottom(15)

        val fileLabel = JBLabel("📄 File: ${result.filePath}")
        fileLabel.font = fileLabel.font.deriveFont(Font.BOLD, 14f)

        val languageLabel = JBLabel("🏷️ Language: ${result.language}")
        languageLabel.font = languageLabel.font.deriveFont(Font.PLAIN, 12f)
        languageLabel.foreground = UIUtil.getContextHelpForeground()

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.add(fileLabel)
        infoPanel.add(Box.createVerticalStrut(5))
        infoPanel.add(languageLabel)

        headerPanel.add(infoPanel, BorderLayout.WEST)

        // Add separator
        val separator = JSeparator()
        headerPanel.add(separator, BorderLayout.SOUTH)

        return headerPanel
    }

    /**
     * Create keywords panel showing extracted keywords
     */
    private fun createKeywordsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        val headerLabel = JBLabel("🔍 Keywords extracted for searching related code:")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        panel.add(headerLabel, BorderLayout.NORTH)

        val keywordsList = if (result.extractedKeywords.isNotEmpty()) {
            // Convert to array to ensure proper JList display
            val keywordsArray = result.extractedKeywords.toTypedArray()
            println("[DEBUG] Keywords panel: displaying ${keywordsArray.size} items: ${keywordsArray.joinToString(", ")}")
            val list = JBList(keywordsArray)
            list.cellRenderer = KeywordListCellRenderer()
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list
        } else {
            val emptyList = JBList(arrayOf("No keywords extracted yet", "This may indicate the agent didn't use the keyword extraction tool"))
            emptyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyList
        }

        val scrollPane = JBScrollPane(keywordsList)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Add summary at the bottom
        val summaryLabel = if (result.extractedKeywords.isNotEmpty()) {
            JBLabel("${result.extractedKeywords.size} keywords found for code analysis")
        } else {
            JBLabel("0 keywords extracted - check if agent called extractKeywords() tool")
        }
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.font = summaryLabel.font.deriveFont(Font.ITALIC, 11f)
        summaryLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(summaryLabel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Create related files panel
     */
    private fun createRelatedFilesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        val headerLabel = JBLabel("📁 Files that contain references to this code:")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        panel.add(headerLabel, BorderLayout.NORTH)

        val filesList = if (result.relatedFiles.isNotEmpty()) {
            val filesArray = result.relatedFiles.toTypedArray()
            println("[DEBUG] Related files panel: displaying ${filesArray.size} items")
            val list = JBList(filesArray)
            list.cellRenderer = FilePathListCellRenderer()
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list
        } else {
            val emptyList = JBList(arrayOf("No related files found", "This may indicate the agent didn't use the search tools"))
            emptyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyList
        }

        val scrollPane = JBScrollPane(filesList)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane, BorderLayout.CENTER)

        val summaryLabel = if (result.relatedFiles.isNotEmpty()) {
            JBLabel("${result.relatedFiles.size} related files found")
        } else {
            JBLabel("0 related files found - check if agent called searchCode() tool")
        }
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.font = summaryLabel.font.deriveFont(Font.ITALIC, 11f)
        summaryLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(summaryLabel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Create usage patterns panel
     */
    private fun createUsagePatternsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        val headerLabel = JBLabel("🔗 Discovered usage patterns:")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        panel.add(headerLabel, BorderLayout.NORTH)

        val patternsList = if (result.usagePatterns.isNotEmpty()) {
            val patternsArray = result.usagePatterns.toTypedArray()
            println("[DEBUG] Usage patterns panel: displaying ${patternsArray.size} items")
            val list = JBList(patternsArray)
            list.cellRenderer = UsagePatternListCellRenderer()
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list
        } else {
            val emptyList = JBList(arrayOf("No usage patterns found", "This may indicate the agent didn't search for code usages"))
            emptyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyList
        }

        val scrollPane = JBScrollPane(patternsList)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane, BorderLayout.CENTER)

        val summaryLabel = if (result.usagePatterns.isNotEmpty()) {
            JBLabel("${result.usagePatterns.size} usage patterns identified")
        } else {
            JBLabel("0 usage patterns found - check if agent searched the codebase")
        }
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.font = summaryLabel.font.deriveFont(Font.ITALIC, 11f)
        summaryLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(summaryLabel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Create analysis notes panel
     */
    private fun createAnalysisNotesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        val headerLabel = JBLabel("📝 How the code works (step-by-step execution):")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        panel.add(headerLabel, BorderLayout.NORTH)

        val notesArea = JBTextArea()
        notesArea.text = if (result.notes.isNotEmpty()) {
            result.notes.joinToString("\n\n") { "• $it" }
        } else {
            """No execution notes recorded yet.

Expected content:
• Step-by-step execution flow
• Data transformations and state changes  
• Control flow logic (if/else, loops, error handling)
• Algorithm details and method call sequences

This indicates the agent may not have called the takeNote() tool to record how the code works."""
        }
        notesArea.isEditable = false
        notesArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        notesArea.lineWrap = true
        notesArea.wrapStyleWord = true
        notesArea.background = UIUtil.getPanelBackground()

        val scrollPane = JBScrollPane(notesArea)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create components panel showing related code snippets
     */
    private fun createComponentsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(15)

        val headerLabel = JBLabel("⚙️ Related code components:")
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        panel.add(headerLabel, BorderLayout.NORTH)

        val componentsList = if (result.relatedComponents.isNotEmpty()) {
            val list = JBList(result.relatedComponents.keys.toTypedArray())
            list.cellRenderer = ComponentListCellRenderer()
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION

            // Show component content when selected
            list.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val selectedComponent = list.selectedValue as? String
                    if (selectedComponent != null) {
                        val content = result.relatedComponents[selectedComponent]
                        // TODO: Show content in a separate panel or tooltip
                        println("[DEBUG] Selected component: $selectedComponent")
                        println("[DEBUG] Content: ${content?.substring(0, minOf(200, content.length ?: 0))}...")
                    }
                }
            }
            list
        } else {
            val emptyList = JBList(arrayOf("No code components read", "This may indicate the agent didn't use the readFile() tool"))
            emptyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyList
        }

        val scrollPane = JBScrollPane(componentsList)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane, BorderLayout.CENTER)

        val summaryLabel = if (result.relatedComponents.isNotEmpty()) {
            JBLabel("${result.relatedComponents.size} related components found")
        } else {
            JBLabel("0 code components read - check if agent called readFile() tool")
        }
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.font = summaryLabel.font.deriveFont(Font.ITALIC, 11f)
        summaryLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(summaryLabel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Create info bar at the bottom of the dialog
     */
    private fun createInfoBar(): JComponent {
        val infoPanel = JPanel(BorderLayout())
        infoPanel.border = JBUI.Borders.emptyTop(10)

        val separator = JSeparator()
        infoPanel.add(separator, BorderLayout.NORTH)

        val infoLabel = JBLabel("💡 Use the tabs above to explore different aspects of the code analysis")
        infoLabel.foreground = UIUtil.getContextHelpForeground()
        infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 11f)
        infoLabel.border = JBUI.Borders.emptyTop(5)
        infoPanel.add(infoLabel, BorderLayout.CENTER)

        return infoPanel
    }

    override fun createActions() = arrayOf(okAction.apply {
        putValue(Action.NAME, "Close")
    })

    /**
     * Extract filename from full path
     */
    private fun extractFileName(path: String): String {
        return path.substringAfterLast('/', path.substringAfterLast('\\'))
    }

    companion object {
        /**
         * Show a simple progress dialog while analysis is running
         */
        fun showProgressDialog(project: Project, filePath: String): DialogWrapper {
            return object : DialogWrapper(project, false) {
                init {
                    title = "Analyzing Code..."
                    isModal = false
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    val panel = JPanel(BorderLayout())
                    panel.preferredSize = Dimension(400, 100)
                    panel.border = JBUI.Borders.empty(20)

                    val progressBar = JProgressBar()
                    progressBar.isIndeterminate = true
                    panel.add(progressBar, BorderLayout.CENTER)

                    val label = JBLabel("Analyzing code and finding related components...")
                    label.border = JBUI.Borders.emptyBottom(10)
                    panel.add(label, BorderLayout.NORTH)

                    val fileLabel = JBLabel("File: ${extractFileName(filePath)}")
                    fileLabel.foreground = UIUtil.getContextHelpForeground()
                    fileLabel.font = fileLabel.font.deriveFont(Font.ITALIC, 11f)
                    fileLabel.border = JBUI.Borders.emptyTop(10)
                    panel.add(fileLabel, BorderLayout.SOUTH)

                    return panel
                }

                override fun createActions() = arrayOf(cancelAction)

                private fun extractFileName(path: String): String {
                    return path.substringAfterLast('/', path.substringAfterLast('\\'))
                }
            }.apply { show() }
        }
    }

    // Custom cell renderers for better appearance
    private class KeywordListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = "🔍 $value"
            return component
        }
    }

    private class FilePathListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val path = value.toString()
            text = "📄 ${path.substringAfterLast('/', path.substringAfterLast('\\'))}"
            toolTipText = path
            return component
        }
    }

    private class UsagePatternListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val pattern = value.toString()
            val icon = when {
                pattern.contains("Import:") -> "📦"
                pattern.contains("Method call:") -> "⚙️"
                pattern.contains("Instantiation:") -> "🏗️"
                pattern.contains("Inheritance:") -> "🔗"
                pattern.contains("Annotation:") -> "🏷️"
                else -> "•"
            }
            text = "$icon $pattern"
            return component
        }
    }

    private class ComponentListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = "⚙️ $value"
            return component
        }
    }
}