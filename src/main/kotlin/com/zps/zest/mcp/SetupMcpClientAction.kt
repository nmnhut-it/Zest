package com.zps.zest.mcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.zps.zest.core.ZestNotifications
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Action to setup Zest MCP for multiple AI coding clients.
 * Shows a dialog to select which clients to configure.
 */
class SetupMcpClientAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = AiClientConfigService.getInstance(project)

        val dialog = ClientSelectionDialog(service)
        if (dialog.showAndGet()) {
            val selectedClients = dialog.getSelectedClients()
            if (selectedClients.isEmpty()) {
                ZestNotifications.showWarning(project, "MCP Setup", "No clients selected")
                return
            }

            var successCount = 0
            var alreadyConfiguredCount = 0
            val errors = mutableListOf<String>()

            for (client in selectedClients) {
                val result = service.setupClient(client)
                when {
                    result.success && result.alreadyConfigured -> alreadyConfiguredCount++
                    result.success -> successCount++
                    else -> errors.add("${client.displayName}: ${result.message}")
                }
            }

            // Summary notification
            val summary = buildString {
                if (successCount > 0) append("Configured: $successCount\n")
                if (alreadyConfiguredCount > 0) append("Already configured: $alreadyConfiguredCount\n")
                if (errors.isNotEmpty()) append("Failed: ${errors.size}")
            }

            if (errors.isEmpty()) {
                ZestNotifications.showInfo(project, "MCP Setup Complete", summary.trim())
            } else {
                ZestNotifications.showWarning(project, "MCP Setup", "$summary\n\n${errors.joinToString("\n")}")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    /**
     * Dialog for selecting AI clients to configure.
     */
    private class ClientSelectionDialog(
        private val service: AiClientConfigService
    ) : DialogWrapper(true) {

        private val checkboxes = mutableMapOf<AiClientConfigService.ClientType, JBCheckBox>()

        init {
            title = "Setup Zest MCP for AI Clients"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(10, 10, 10, 10)

            // Header
            val header = JBLabel("Select AI clients to configure with Zest MCP:")
            header.border = JBUI.Borders.emptyBottom(10)
            panel.add(header, BorderLayout.NORTH)

            // Checkboxes for each client
            val clientsPanel = JPanel(GridLayout(0, 1, 5, 5))

            for (client in AiClientConfigService.ClientType.values()) {
                val available = service.isClientAvailable(client)
                val checkbox = JBCheckBox(client.displayName)
                checkbox.isSelected = available
                checkbox.toolTipText = if (available) {
                    "Click to configure ${client.displayName}"
                } else {
                    "${client.displayName} may not be installed (config directory not found)"
                }
                checkboxes[client] = checkbox
                clientsPanel.add(checkbox)
            }

            panel.add(clientsPanel, BorderLayout.CENTER)

            // Footer with info
            val footer = JBLabel("<html><small>Config will be created in each client's settings directory.<br>Restart the AI client after setup.</small></html>")
            footer.border = JBUI.Borders.emptyTop(10)
            panel.add(footer, BorderLayout.SOUTH)

            return panel
        }

        fun getSelectedClients(): List<AiClientConfigService.ClientType> {
            return checkboxes.filter { it.value.isSelected }.keys.toList()
        }
    }
}

/**
 * Base class for client-specific MCP setup actions.
 * Only shows when the client is detected on the system.
 */
abstract class BaseSetupMcpAction(private val clientType: AiClientConfigService.ClientType) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        setupClient(e, clientType)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = AiClientConfigService.getInstance(project)
        e.presentation.isEnabledAndVisible = service.isClientAvailable(clientType)
    }
}

/** Claude Desktop - shows when Claude Desktop config directory exists */
class SetupClaudeDesktopMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLAUDE_DESKTOP)

/** Cursor - shows when ~/.cursor exists */
class SetupCursorMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CURSOR)

/** Cline - shows when VS Code Cline extension directory exists */
class SetupClineMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLINE)

/** Windsurf - shows when ~/.windsurf exists */
class SetupWindsurfMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.WINDSURF)

/** Claude Code CLI - shows when ~/.claude exists */
class SetupClaudeCodeMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLAUDE_CODE)

/** Kilo Code - shows when VS Code Kilo Code extension directory exists */
class SetupKiloCodeMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.KILO_CODE)

/** Continue.dev - shows when ~/.continue exists */
class SetupContinueDevMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CONTINUE_DEV)

/** Qwen Coder - shows when qwen-coder config exists */
class SetupQwenCoderMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.QWEN_CODER)

/** Gemini CLI - shows when gemini config exists */
class SetupGeminiCliMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.GEMINI_CLI)

/**
 * Helper function to setup a single client.
 */
private fun setupClient(e: AnActionEvent, client: AiClientConfigService.ClientType) {
    val project = e.project ?: return
    val service = AiClientConfigService.getInstance(project)
    val result = service.setupClient(client)

    if (result.success) {
        if (result.alreadyConfigured) {
            ZestNotifications.showInfo(
                project,
                "${client.displayName} MCP",
                "Already configured.\n\nConfig: ${result.configPath}"
            )
        }
        // Success notification shown by service
    } else {
        ZestNotifications.showError(
            project,
            "${client.displayName} MCP Setup Failed",
            result.message
        )
    }
}
