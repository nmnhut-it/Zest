package com.zps.zest.mcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.zps.zest.core.ZestNotifications
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Action to setup Zest MCP for multiple AI coding clients.
 * Shows a dialog to select which clients to configure and installation scope.
 */
class SetupMcpClientAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = AiClientConfigService.getInstance(project)

        val dialog = ClientSelectionDialog(service, project)
        if (dialog.showAndGet()) {
            val selectedClients = dialog.getSelectedClients()
            val scope = dialog.getSelectedScope()

            if (selectedClients.isEmpty()) {
                ZestNotifications.showWarning(project, "MCP Setup", "No clients selected")
                return
            }

            var successCount = 0
            var alreadyConfiguredCount = 0
            val errors = mutableListOf<String>()

            for (client in selectedClients) {
                val result = service.setupClient(client, scope)
                when {
                    result.success && result.alreadyConfigured -> alreadyConfiguredCount++
                    result.success -> successCount++
                    else -> errors.add("${client.displayName}: ${result.message}")
                }
            }

            // Summary notification
            val scopeLabel = if (scope == AiClientConfigService.InstallScope.PROJECT) " (Project)" else ""
            val summary = buildString {
                if (successCount > 0) append("Configured: $successCount$scopeLabel\n")
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
     * Dialog for selecting AI clients and installation scope.
     */
    private class ClientSelectionDialog(
        private val service: AiClientConfigService,
        private val project: Project
    ) : DialogWrapper(true) {

        private val checkboxes = mutableMapOf<AiClientConfigService.ClientType, JBCheckBox>()
        private lateinit var scopeComboBox: ComboBox<AiClientConfigService.InstallScope>

        init {
            title = "Setup Zest MCP for AI Clients"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(10, 10, 10, 10)

            // Header
            val header = JBLabel("Configure AI clients with Zest MCP server:")
            header.border = JBUI.Borders.emptyBottom(10)
            panel.add(header, BorderLayout.NORTH)

            // Center panel with scope and clients
            val centerPanel = JPanel(BorderLayout())

            // Scope selection
            val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT))
            scopePanel.add(JBLabel("Install to: "))
            scopeComboBox = ComboBox(AiClientConfigService.InstallScope.entries.toTypedArray())
            scopeComboBox.selectedItem = AiClientConfigService.InstallScope.PROJECT  // Default to project
            scopeComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? AiClientConfigService.InstallScope)?.displayName ?: value.toString()
                    return this
                }
            }
            scopePanel.add(scopeComboBox)
            scopePanel.border = JBUI.Borders.emptyBottom(10)
            centerPanel.add(scopePanel, BorderLayout.NORTH)

            // Checkboxes for each client
            val clientsPanel = JPanel(GridLayout(0, 1, 5, 5))

            for (client in AiClientConfigService.ClientType.entries) {
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
            centerPanel.add(clientsPanel, BorderLayout.CENTER)

            panel.add(centerPanel, BorderLayout.CENTER)

            // Footer with info
            val footer = JBLabel(
                "<html><small>Project installs to .cursor/, .qwen/, etc. in project root.<br>" +
                    "User installs to global config directories.<br>" +
                    "Restart the AI client after setup.</small></html>"
            )
            footer.border = JBUI.Borders.emptyTop(10)
            panel.add(footer, BorderLayout.SOUTH)

            return panel
        }

        fun getSelectedClients(): List<AiClientConfigService.ClientType> {
            return checkboxes.filter { it.value.isSelected }.keys.toList()
        }

        fun getSelectedScope(): AiClientConfigService.InstallScope {
            return scopeComboBox.selectedItem as? AiClientConfigService.InstallScope
                ?: AiClientConfigService.InstallScope.PROJECT
        }
    }
}

/**
 * Base class for client-specific MCP setup actions.
 * Shows scope selection dialog if the client supports project scope.
 * Only shows when the client is detected on the system.
 */
abstract class BaseSetupMcpAction(private val clientType: AiClientConfigService.ClientType) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = AiClientConfigService.getInstance(project)

        // Show scope selection if client supports project scope
        val scope = if (clientType.supportsProjectScope) {
            showScopeSelectionDialog(project, clientType)
        } else {
            AiClientConfigService.InstallScope.USER
        }

        if (scope != null) {
            setupClientWithScope(project, service, clientType, scope)
        }
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

/** Claude Desktop - shows when Claude Desktop config directory exists (user-level only) */
class SetupClaudeDesktopMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLAUDE_DESKTOP)

/** Cursor - shows when ~/.cursor exists */
class SetupCursorMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CURSOR)

/** Cline - shows when VS Code Cline extension directory exists (user-level only) */
class SetupClineMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLINE)

/** Windsurf - shows when ~/.codeium/windsurf exists */
class SetupWindsurfMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.WINDSURF)

/** Claude Code CLI - shows when ~/.claude exists */
class SetupClaudeCodeMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CLAUDE_CODE)

/** Kilo Code - shows when VS Code Kilo Code extension directory exists */
class SetupKiloCodeMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.KILO_CODE)

/** Continue.dev - shows when ~/.continue exists */
class SetupContinueDevMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.CONTINUE_DEV)

/** Qwen Coder - shows when ~/.qwen config exists */
class SetupQwenCoderMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.QWEN_CODER)

/** Gemini CLI - shows when ~/.gemini config exists */
class SetupGeminiCliMcpAction : BaseSetupMcpAction(AiClientConfigService.ClientType.GEMINI_CLI)

/**
 * Shows scope selection dialog for clients that support both user and project scopes.
 * Returns selected scope or null if cancelled.
 */
private fun showScopeSelectionDialog(
    project: Project,
    client: AiClientConfigService.ClientType
): AiClientConfigService.InstallScope? {
    val options = arrayOf("Project (local)", "User (global)", "Cancel")
    val result = com.intellij.openapi.ui.Messages.showDialog(
        project,
        "Install MCP config for ${client.displayName}:",
        "Installation Scope",
        options,
        0,  // Default to project
        com.intellij.openapi.ui.Messages.getQuestionIcon()
    )

    return when (result) {
        0 -> AiClientConfigService.InstallScope.PROJECT
        1 -> AiClientConfigService.InstallScope.USER
        else -> null
    }
}

/**
 * Setup a single client with the specified scope.
 */
private fun setupClientWithScope(
    project: Project,
    service: AiClientConfigService,
    client: AiClientConfigService.ClientType,
    scope: AiClientConfigService.InstallScope
) {
    val result = service.setupClient(client, scope)

    if (result.success) {
        if (result.alreadyConfigured) {
            ZestNotifications.showInfo(
                project,
                "${client.displayName} MCP",
                "Already configured (${scope.displayName}).\n\nConfig: ${result.configPath}"
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
