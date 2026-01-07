package com.zps.zest.continuedev

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.zps.zest.core.ZestNotifications

/**
 * Action to install Zest MCP configuration for Continue.dev.
 * Creates ~/.continue/mcpServers/zest.json with MCP server config.
 *
 * Continue.dev uses JSON MCP format compatible with Claude Desktop, Cursor, and Cline.
 */
class SetupContinueMcpAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val service = ContinueConfigurationService.getInstance(project)
        val result = service.setupContinueConfiguration()

        if (result.success) {
            if (result.alreadyConfigured) {
                ZestNotifications.showInfo(
                    project,
                    "Continue.dev MCP",
                    "Zest MCP is already configured for Continue.dev.\n\nConfig: ${result.configPath}"
                )
            }
            // Success notification is shown by the service
        } else {
            ZestNotifications.showError(
                project,
                "Continue.dev MCP Setup Failed",
                result.message
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
