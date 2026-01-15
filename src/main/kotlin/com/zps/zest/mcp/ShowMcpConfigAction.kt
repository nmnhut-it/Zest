package com.zps.zest.mcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to show MCP configuration dialog with setup snippets for various AI clients.
 * Supports Claude Desktop, Cursor, Continue.dev, Cline, Windsurf, and generic SSE clients.
 */
class ShowMcpConfigAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        McpConfigDialog.show(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
