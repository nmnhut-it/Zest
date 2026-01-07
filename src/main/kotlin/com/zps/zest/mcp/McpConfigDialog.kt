package com.zps.zest.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Dialog showing MCP configuration snippets for various AI clients.
 * Provides easy copy-paste setup for Claude Desktop, Cursor, Continue.dev, etc.
 */
class McpConfigDialog(private val project: Project) : DialogWrapper(project) {

    private val port = 45450

    init {
        title = "Zest MCP Server Configuration"
        setOKButtonText("Close")
        setCancelButtonText("Copy All to Clipboard")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(700, 500)

        // Header with server info
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Tabbed pane for different clients
        val tabbedPane = JBTabbedPane()

        for (client in McpConfigGenerator.ClientType.values()) {
            val tabPanel = createClientTab(client)
            tabbedPane.addTab(client.displayName, tabPanel)
        }

        // Add YAML tab
        tabbedPane.addTab("YAML", createYamlTab())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val url = McpConfigGenerator.getServerUrl(port)

        val infoHtml = """
            <html>
            <body style='font-family: sans-serif;'>
            <h3 style='margin: 0;'>Zest MCP Server</h3>
            <p style='margin: 5px 0;'>
                <b>URL:</b> <code>$url</code><br/>
                <b>Transport:</b> SSE (Server-Sent Events)<br/>
                <b>Status:</b> Running on port $port
            </p>
            <p style='margin: 5px 0; color: #666;'>
                Copy the configuration for your AI client below.
            </p>
            </body>
            </html>
        """.trimIndent()

        val infoLabel = JLabel(infoHtml)
        panel.add(infoLabel, BorderLayout.CENTER)

        // Copy URL button
        val copyUrlButton = JButton("Copy URL")
        copyUrlButton.addActionListener {
            copyToClipboard(url)
            showCopiedFeedback(copyUrlButton, "URL Copied!")
        }
        panel.add(copyUrlButton, BorderLayout.EAST)

        return panel
    }

    private fun createClientTab(client: McpConfigGenerator.ClientType): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        // Client info header
        val infoHtml = """
            <html>
            <body style='font-family: sans-serif;'>
            <p><b>Config file:</b> <code>${client.configFileName}</code></p>
            <p><b>Location:</b> ${client.configPath}</p>
            <p><a href='${client.docsUrl}'>Documentation →</a></p>
            </body>
            </html>
        """.trimIndent()

        val infoLabel = JLabel(infoHtml)
        infoLabel.border = JBUI.Borders.emptyBottom(10)
        panel.add(infoLabel, BorderLayout.NORTH)

        // JSON config text area
        val config = McpConfigGenerator.generateJsonConfig(client, port)
        val textArea = createConfigTextArea(config)
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Copy button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val copyButton = JButton("Copy JSON")
        copyButton.addActionListener {
            copyToClipboard(config)
            showCopiedFeedback(copyButton, "Copied!")
        }
        buttonPanel.add(copyButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createYamlTab(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val infoHtml = """
            <html>
            <body style='font-family: sans-serif;'>
            <p><b>Alternative format:</b> Some clients support YAML configuration.</p>
            <p>Use this if your client prefers YAML over JSON.</p>
            </body>
            </html>
        """.trimIndent()

        val infoLabel = JLabel(infoHtml)
        infoLabel.border = JBUI.Borders.emptyBottom(10)
        panel.add(infoLabel, BorderLayout.NORTH)

        val yamlConfig = McpConfigGenerator.generateYamlConfig(port)
        val textArea = createConfigTextArea(yamlConfig)
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val copyButton = JButton("Copy YAML")
        copyButton.addActionListener {
            copyToClipboard(yamlConfig)
            showCopiedFeedback(copyButton, "Copied!")
        }
        buttonPanel.add(copyButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createConfigTextArea(content: String): JTextArea {
        val textArea = JTextArea(content)
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        textArea.border = JBUI.Borders.empty(10)
        textArea.caretPosition = 0
        return textArea
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun showCopiedFeedback(button: JButton, message: String) {
        val originalText = button.text
        button.text = message
        Timer(1500) {
            button.text = originalText
        }.apply {
            isRepeats = false
            start()
        }
    }

    override fun doCancelAction() {
        // Copy full guide to clipboard
        val fullGuide = McpConfigGenerator.generateFullSetupGuide(port)
        copyToClipboard(fullGuide)
        com.zps.zest.core.ZestNotifications.showInfo(
            project,
            "MCP Configuration",
            "Full setup guide copied to clipboard!"
        )
        super.doCancelAction()
    }

    companion object {
        fun show(project: Project) {
            McpConfigDialog(project).show()
        }
    }
}
