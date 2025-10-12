package com.zps.zest.context.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.zps.zest.context.CodeContextAgent
import com.zps.zest.context.CodeContextReport
import com.zps.zest.context.ExploreContextTool
import com.zps.zest.langchain4j.ZestLangChain4jService
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import java.awt.*
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Simple test dialog for CodeContextAgent exploration.
 */
class CodeContextTestDialog(private val project: Project) : DialogWrapper(project, true) {

    private val targetField = JBTextField(50)
    private val scopeCombo = JComboBox(arrayOf("CLASS", "METHOD", "FEATURE", "PACKAGE"))
    private val focusCombo = JComboBox(arrayOf("ALL", "ARCHITECTURE", "USAGE", "DEPENDENCIES"))
    private val maxToolCallsSpinner = JSpinner(SpinnerNumberModel(20, 5, 50, 5))
    private val resultsArea = JBTextArea(30, 100)
    private val statusLabel = JBLabel("Ready")
    private var currentAgent: CodeContextAgent? = null

    init {
        title = "Code Context Agent Test"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(1000, 700)

        panel.add(createInputPanel(), BorderLayout.NORTH)
        panel.add(createResultsPanel(), BorderLayout.CENTER)
        panel.add(createStatusPanel(), BorderLayout.SOUTH)

        return panel
    }

    private fun createInputPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Test Configuration")
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Target
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JLabel("Target:"), gbc)

        gbc.gridx = 1
        gbc.gridwidth = 3
        gbc.weightx = 1.0
        targetField.toolTipText = "File path or fully qualified class name"
        panel.add(targetField, gbc)

        // Scope
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JLabel("Scope:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 0.3
        panel.add(scopeCombo, gbc)

        // Focus
        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(JLabel("Focus:"), gbc)

        gbc.gridx = 3
        gbc.weightx = 0.3
        panel.add(focusCombo, gbc)

        // Max tool calls
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.0
        panel.add(JLabel("Max Tool Calls:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 0.3
        panel.add(maxToolCallsSpinner, gbc)

        // Quick test buttons
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 4
        panel.add(createQuickTestPanel(), gbc)

        // Execute button
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.CENTER
        val executeButton = JButton("Explore Context")
        executeButton.addActionListener { executeTest() }
        executeButton.preferredSize = JBUI.size(200, 35)
        executeButton.background = JBColor(Color(0, 120, 215), Color(0, 120, 215))
        executeButton.foreground = JBColor.WHITE
        panel.add(executeButton, gbc)

        return panel
    }

    private fun createQuickTestPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.border = TitledBorder("Quick Tests")

        val testThisFileButton = JButton("Test This File")
        testThisFileButton.toolTipText = "Use ExploreContextTool.java"
        testThisFileButton.addActionListener {
            targetField.text = "com.zps.zest.context.ExploreContextTool"
            scopeCombo.selectedItem = "CLASS"
            focusCombo.selectedItem = "ALL"
        }
        panel.add(testThisFileButton)

        val testChatServiceButton = JButton("Test ChatUIService")
        testChatServiceButton.addActionListener {
            targetField.text = "com.zps.zest.chatui.ChatUIService"
            scopeCombo.selectedItem = "CLASS"
            focusCombo.selectedItem = "ARCHITECTURE"
        }
        panel.add(testChatServiceButton)

        val testToolServerButton = JButton("Test ToolApiServer")
        testToolServerButton.addActionListener {
            targetField.text = "com.zps.zest.mcp.ToolApiServer"
            scopeCombo.selectedItem = "CLASS"
            focusCombo.selectedItem = "DEPENDENCIES"
        }
        panel.add(testToolServerButton)

        return panel
    }

    private fun createResultsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder("Exploration Results (Markdown)")

        resultsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        resultsArea.isEditable = false
        resultsArea.lineWrap = true
        resultsArea.wrapStyleWord = true

        val scrollPane = JBScrollPane(resultsArea)
        scrollPane.preferredSize = JBUI.size(950, 500)
        panel.add(scrollPane, BorderLayout.CENTER)

        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT))

        val viewMemoryButton = JButton("View Memory")
        viewMemoryButton.toolTipText = "View agent's conversation history"
        viewMemoryButton.addActionListener {
            currentAgent?.let { agent ->
                val memoryDialog = ChatMemoryDialog(project, agent.chatMemory, "CodeContextAgent")
                memoryDialog.show()
            } ?: run {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "No agent memory available. Run an exploration first.",
                    "No Memory",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
        toolbar.add(viewMemoryButton)

        val clearButton = JButton("Clear")
        clearButton.addActionListener {
            resultsArea.text = ""
            statusLabel.text = "Ready"
        }
        toolbar.add(clearButton)

        panel.add(toolbar, BorderLayout.SOUTH)

        return panel
    }

    private fun createStatusPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = JBUI.Borders.empty(5)
        panel.add(JLabel("Status:"))
        panel.add(statusLabel)
        return panel
    }

    private fun executeTest() {
        val target = targetField.text.trim()

        if (target.isEmpty()) {
            resultsArea.text = "Error: Please enter a target (file path or class name)"
            statusLabel.text = "Error: No target specified"
            return
        }

        statusLabel.text = "Exploring..."
        resultsArea.text = "Starting exploration of: $target\n\nPlease wait...\n"

        SwingUtilities.invokeLater {
            try {
                val langChainService = project.getService(ZestLangChain4jService::class.java)
                val naiveLlmService = project.getService(NaiveLLMService::class.java)

                val scope = scopeCombo.selectedItem as String
                val focus = focusCombo.selectedItem as String
                val maxToolCalls = maxToolCallsSpinner.value as Int

                // Create agent directly to store reference
                currentAgent = CodeContextAgent(project, langChainService, naiveLlmService, maxToolCalls)

                val startTime = System.currentTimeMillis()

                val scopeEnum = CodeContextReport.Scope.valueOf(scope)
                val focusEnum = CodeContextReport.Focus.valueOf(focus)

                val reportFuture = currentAgent!!.exploreContext(target, scopeEnum, focusEnum)
                val report = reportFuture.join()

                val endTime = System.currentTimeMillis()
                val executionTime = endTime - startTime

                resultsArea.text = report.toMarkdown()
                resultsArea.append("\n\n--- Execution Time: ${executionTime}ms ---")

                statusLabel.text = "Exploration completed successfully (${executionTime}ms)"

            } catch (e: Exception) {
                resultsArea.text = "Error during exploration:\n\n${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
                statusLabel.text = "Exploration failed"
                currentAgent = null
            }
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }
}
