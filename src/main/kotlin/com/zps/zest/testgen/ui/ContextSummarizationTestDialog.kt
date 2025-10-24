package com.zps.zest.testgen.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.zps.zest.ClassAnalyzer
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import com.zps.zest.testgen.agents.ContextSummarizationService
import com.zps.zest.testgen.analysis.UsageAnalyzer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Test dialog for context summarization system.
 * Allows manual testing of summarization with real class data.
 */
class ContextSummarizationTestDialog(
    private val project: Project
) : DialogWrapper(project) {

    private val classNameInput = JTextField(30)
    private val detailLevelCombo = JComboBox(arrayOf("MINIMAL", "MODERATE", "DETAILED", "FULL"))
    private val isTargetClassCheckbox = JCheckBox("Is Target Class")
    private val includeUsageCheckbox = JCheckBox("Include Usage Analysis", true)

    private val originalTextArea = JBTextArea()
    private val summarizedTextArea = JBTextArea()
    private val usageContextArea = JBTextArea()
    private val statsLabel = JBLabel()

    init {
        title = "Test Context Summarization"
        isTargetClassCheckbox.isSelected = true
        detailLevelCombo.selectedIndex = 2 // DETAILED
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = JBUI.Borders.empty(10)

        // Input panel
        val inputPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(5)

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        inputPanel.add(JBLabel("Class Name:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.gridwidth = 2
        inputPanel.add(classNameInput, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        gbc.gridwidth = 1
        inputPanel.add(JBLabel("Detail Level:"), gbc)

        gbc.gridx = 1
        inputPanel.add(detailLevelCombo, gbc)

        gbc.gridx = 2
        inputPanel.add(isTargetClassCheckbox, gbc)

        gbc.gridx = 1
        gbc.gridy = 2
        gbc.gridwidth = 2
        inputPanel.add(includeUsageCheckbox, gbc)

        gbc.gridx = 1
        gbc.gridy = 3
        gbc.gridwidth = 2
        val testButton = JButton("Run Summarization Test")
        testButton.addActionListener { runSummarizationTest() }
        inputPanel.add(testButton, gbc)

        gbc.gridx = 0
        gbc.gridy = 4
        gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.BOTH
        inputPanel.add(statsLabel, gbc)

        panel.add(inputPanel, BorderLayout.NORTH)

        // Results panel with tabs
        val tabbedPane = JTabbedPane()

        // Original context tab
        originalTextArea.isEditable = false
        originalTextArea.lineWrap = true
        originalTextArea.wrapStyleWord = true
        val originalScroll = JBScrollPane(originalTextArea)
        originalScroll.preferredSize = Dimension(800, 300)
        tabbedPane.addTab("📄 Original Context", originalScroll)

        // Usage context tab
        usageContextArea.isEditable = false
        usageContextArea.lineWrap = true
        usageContextArea.wrapStyleWord = true
        val usageScroll = JBScrollPane(usageContextArea)
        usageScroll.preferredSize = Dimension(800, 300)
        tabbedPane.addTab("🔍 Usage Analysis", usageScroll)

        // Summarized context tab
        summarizedTextArea.isEditable = false
        summarizedTextArea.lineWrap = true
        summarizedTextArea.wrapStyleWord = true
        val summarizedScroll = JBScrollPane(summarizedTextArea)
        summarizedScroll.preferredSize = Dimension(800, 300)
        tabbedPane.addTab("✨ Summarized Context", summarizedScroll)

        panel.add(tabbedPane, BorderLayout.CENTER)

        return panel
    }

    private fun runSummarizationTest() {
        val className = classNameInput.text.trim()
        if (className.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPane,
                "Please enter a class name",
                "Input Required",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        try {
            // Find the class
            val psiClass = findClass(className)
            if (psiClass == null) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Class not found: $className\nTry using fully qualified name (e.g., com.example.MyClass)",
                    "Class Not Found",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }

            // Get original context
            val originalContext = ClassAnalyzer.collectClassContext(psiClass)
            originalTextArea.text = originalContext

            // Get usage context if requested
            var usageContext: com.zps.zest.testgen.analysis.UsageContext? = null
            if (includeUsageCheckbox.isSelected) {
                val methods = psiClass.methods
                if (methods.isNotEmpty()) {
                    val usageAnalyzer = UsageAnalyzer(project)
                    // Analyze first public method as example
                    val targetMethod = methods.firstOrNull { it.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC) }
                        ?: methods.firstOrNull()

                    if (targetMethod != null) {
                        usageContext = usageAnalyzer.analyzeMethod(targetMethod)
                        usageContextArea.text = usageContext.formatForLLM()
                    } else {
                        usageContextArea.text = "No methods found to analyze"
                    }
                } else {
                    usageContextArea.text = "No methods in class"
                }
            } else {
                usageContextArea.text = "Usage analysis disabled"
            }

            // Run summarization
            val llmService = project.getService(NaiveLLMService::class.java)
            val summarizationService = ContextSummarizationService(project, llmService)

            val detailLevel = when (detailLevelCombo.selectedItem as String) {
                "MINIMAL" -> ContextSummarizationService.DetailLevel.MINIMAL
                "MODERATE" -> ContextSummarizationService.DetailLevel.MODERATE
                "DETAILED" -> ContextSummarizationService.DetailLevel.DETAILED
                "FULL" -> ContextSummarizationService.DetailLevel.FULL
                else -> ContextSummarizationService.DetailLevel.MODERATE
            }

            summarizedTextArea.text = "⏳ Summarizing with LLM, please wait...\n"

            // Run in background
            SwingUtilities.invokeLater {
                try {
                    val summary = summarizationService.summarizeWithUsageContext(
                        className,
                        originalContext,
                        usageContext,
                        psiClass,
                        detailLevel,
                        isTargetClassCheckbox.isSelected
                    )

                    summarizedTextArea.text = summary

                    // Calculate and display stats
                    val originalTokens = summarizationService.estimateTokens(originalContext)
                    val summaryTokens = summarizationService.estimateTokens(summary)
                    val reduction = ((originalTokens - summaryTokens).toDouble() / originalTokens * 100).toInt()

                    val dependencies = ClassAnalyzer.detectExternalDependencies(psiClass)
                    val usageStats = if (usageContext != null) {
                        "${usageContext.totalUsages} call sites, ${usageContext.discoveredEdgeCases.size} edge cases"
                    } else {
                        "N/A"
                    }

                    statsLabel.text = """
                        <html>
                        <b>Statistics:</b><br>
                        Original: $originalTokens tokens<br>
                        Summary: $summaryTokens tokens<br>
                        Reduction: $reduction%<br>
                        Dependencies: ${dependencies.size} (${dependencies.joinToString(", ")})<br>
                        Usage Analysis: $usageStats
                        </html>
                    """.trimIndent()

                } catch (e: Exception) {
                    summarizedTextArea.text = "❌ Error during summarization:\n${e.message}\n\n${e.stackTraceToString()}"
                }
            }

        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                contentPane,
                "Error: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun findClass(className: String): PsiClass? {
        // Try as fully qualified name first
        val javaPsiFacade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        var psiClass = javaPsiFacade.findClass(className, GlobalSearchScope.allScope(project))

        if (psiClass != null) {
            return psiClass
        }

        // Try as simple name
        val cache = PsiShortNamesCache.getInstance(project)
        val classes = cache.getClassesByName(className, GlobalSearchScope.projectScope(project))

        return classes.firstOrNull()
    }

    override fun createActions() = arrayOf(okAction)
}
