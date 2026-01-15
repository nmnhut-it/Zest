package com.zps.zest.mcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
 * Action to install Zest skills to AI coding clients.
 * Shows dialog to select clients and installation scope (User/Project).
 */
class InstallSkillsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SkillInstallationService.getInstance(project)

        val dialog = SkillClientSelectionDialog(service, project)
        if (dialog.showAndGet()) {
            val selectedClients = dialog.getSelectedClients()
            val scope = dialog.getSelectedScope()

            if (selectedClients.isEmpty()) {
                ZestNotifications.showWarning(project, "Skills Setup", "No clients selected")
                return
            }

            var successCount = 0
            var alreadyCount = 0
            val errors = mutableListOf<String>()

            for (client in selectedClients) {
                val result = service.installSkills(client, scope)
                when {
                    result.success && result.alreadyInstalled -> alreadyCount++
                    result.success -> successCount++
                    else -> errors.add("${client.displayName}: ${result.message}")
                }
            }

            // Summary notification
            val scopeLabel = if (scope == SkillInstallationService.InstallScope.PROJECT) " (Project)" else ""
            val summary = buildString {
                if (successCount > 0) append("Installed: $successCount$scopeLabel\n")
                if (alreadyCount > 0) append("Already installed: $alreadyCount\n")
                if (errors.isNotEmpty()) append("Failed: ${errors.size}")
            }

            if (errors.isEmpty()) {
                ZestNotifications.showInfo(project, "Skills Installation Complete", summary.trim())
            } else {
                ZestNotifications.showWarning(
                    project,
                    "Skills Installation",
                    "$summary\n\n${errors.joinToString("\n")}"
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    /**
     * Dialog for selecting AI clients and installation scope.
     */
    private class SkillClientSelectionDialog(
        private val service: SkillInstallationService,
        private val project: Project
    ) : DialogWrapper(true) {

        private val checkboxes = mutableMapOf<SkillInstallationService.SkillClient, JBCheckBox>()
        private lateinit var scopeComboBox: com.intellij.openapi.ui.ComboBox<SkillInstallationService.InstallScope>

        init {
            title = "Install Zest Skills"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(10, 10, 10, 10)

            // Header
            val header = JBLabel(
                "<html>Install BitZero skills to AI clients:<br>" +
                    "<small>- bitzero-test: Generate tests (unit/integration/e2e)<br>" +
                    "- bitzero-review: Review code for security and testability<br>" +
                    "- bitzero-methodology: Strategies for testing tightly-coupled code</small></html>"
            )
            header.border = JBUI.Borders.emptyBottom(10)
            panel.add(header, BorderLayout.NORTH)

            // Center panel with scope and clients
            val centerPanel = JPanel(BorderLayout())

            // Scope selection
            val scopePanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
            scopePanel.add(JBLabel("Install to: "))
            scopeComboBox = com.intellij.openapi.ui.ComboBox(SkillInstallationService.InstallScope.entries.toTypedArray())
            scopeComboBox.selectedItem = SkillInstallationService.InstallScope.PROJECT  // Default to project
            scopeComboBox.renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? SkillInstallationService.InstallScope)?.displayName ?: value.toString()
                    return this
                }
            }
            scopePanel.add(scopeComboBox)
            scopePanel.border = JBUI.Borders.emptyBottom(10)
            centerPanel.add(scopePanel, BorderLayout.NORTH)

            // Checkboxes for each client
            val clientsPanel = JPanel(GridLayout(0, 1, 5, 5))

            for (client in SkillInstallationService.SkillClient.entries) {
                val available = service.isClientAvailable(client)
                val checkbox = JBCheckBox(client.displayName)
                checkbox.isSelected = available
                checkbox.toolTipText = if (available) {
                    "Install skills to ${client.getSkillsPath()}"
                } else {
                    "${client.displayName} not detected (config directory not found)"
                }
                checkboxes[client] = checkbox
                clientsPanel.add(checkbox)
            }
            centerPanel.add(clientsPanel, BorderLayout.CENTER)

            panel.add(centerPanel, BorderLayout.CENTER)

            // Footer
            val footer = JBLabel(
                "<html><small>Project installs to .qwen/skills/ or .claude/skills/ in project root.<br>" +
                    "User installs to ~/.qwen/skills/ or ~/.claude/skills/ globally.</small></html>"
            )
            footer.border = JBUI.Borders.emptyTop(10)
            panel.add(footer, BorderLayout.SOUTH)

            return panel
        }

        fun getSelectedClients(): List<SkillInstallationService.SkillClient> {
            return checkboxes.filter { it.value.isSelected }.keys.toList()
        }

        fun getSelectedScope(): SkillInstallationService.InstallScope {
            return scopeComboBox.selectedItem as? SkillInstallationService.InstallScope
                ?: SkillInstallationService.InstallScope.PROJECT
        }
    }
}

/**
 * Action to install skills to Qwen Coder only.
 * Shows when Qwen Coder is detected.
 */
class InstallSkillsToQwenCoderAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SkillInstallationService.getInstance(project)
        // Default to PROJECT scope since we're running inside IntelliJ with a project open
        val result = service.installSkills(
            SkillInstallationService.SkillClient.QWEN_CODER,
            SkillInstallationService.InstallScope.PROJECT
        )

        if (!result.success) {
            ZestNotifications.showError(
                project,
                "Qwen Coder Skills Installation Failed",
                result.message
            )
        }
        // Success notification is shown by service
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = SkillInstallationService.getInstance(project)
        e.presentation.isEnabledAndVisible =
            service.isClientAvailable(SkillInstallationService.SkillClient.QWEN_CODER)
    }
}

/**
 * Action to install skills to Claude Code only.
 * Shows when Claude Code is detected.
 */
class InstallSkillsToClaudeCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SkillInstallationService.getInstance(project)
        // Default to PROJECT scope since we're running inside IntelliJ with a project open
        val result = service.installSkills(
            SkillInstallationService.SkillClient.CLAUDE_CODE,
            SkillInstallationService.InstallScope.PROJECT
        )

        if (!result.success) {
            ZestNotifications.showError(
                project,
                "Claude Code Skills Installation Failed",
                result.message
            )
        }
        // Success notification is shown by service
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = SkillInstallationService.getInstance(project)
        e.presentation.isEnabledAndVisible =
            service.isClientAvailable(SkillInstallationService.SkillClient.CLAUDE_CODE)
    }
}

/**
 * Action to enable dev mode - copies bundled skills to ~/.zest/dev-skills/
 * for editing without rebuilding the plugin.
 */
class EnableSkillsDevModeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SkillInstallationService.getInstance(project)

        val devDir = SkillInstallationService.getDevSkillsDir()
        val initialized = service.initializeDevSkills()

        val message = if (initialized.isEmpty()) {
            "Dev skills already exist at:\n$devDir\n\nEdit SKILL.md files there, then re-install skills."
        } else {
            "Created ${initialized.size} skills at:\n$devDir\n\n" +
                "Skills: ${initialized.joinToString(", ")}\n\n" +
                "Edit SKILL.md files there, then re-install skills to apply changes."
        }

        ZestNotifications.showInfo(project, "Skills Dev Mode", message)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * Action to show where skills are currently loaded from.
 */
class ShowSkillSourcesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SkillInstallationService.getInstance(project)

        val sources = service.getSkillSourceInfo()
        val message = buildString {
            append("Skill sources (highest priority wins):\n\n")

            for ((name, source) in sources) {
                val sourceDesc = source?.displayName ?: "Not found"
                append("• $name: $sourceDesc\n")
            }

            append("\nPriority order:\n")
            append("1. Project: \${PROJECT}/.zest/skills/\n")
            append("2. Dev: ~/.zest/dev-skills/\n")
            append("3. Bundled: plugin resources")
        }

        ZestNotifications.showInfo(project, "Skill Sources", message)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * Action to enable dev mode for MCP prompts.
 * Copies bundled prompts to ~/.zest/dev-prompts/ for editing without rebuild.
 */
class EnablePromptsDevModeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val loader = PromptLoader.getInstance(project)

        val devDir = PromptLoader.getDevPromptsDir()
        val initialized = loader.initializeDevPrompts()

        val message = if (initialized.isEmpty()) {
            "Dev prompts already exist at:\n$devDir\n\nEdit .md files there, changes take effect immediately."
        } else {
            "Created ${initialized.size} prompts at:\n$devDir\n\n" +
                "Files: ${initialized.joinToString(", ")}\n\n" +
                "Edit .md files there, changes take effect immediately."
        }

        ZestNotifications.showInfo(project, "Prompts Dev Mode", message)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * Action to show where MCP prompts are currently loaded from.
 */
class ShowPromptSourcesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val loader = PromptLoader.getInstance(project)

        val sources = loader.promptSourceInfo
        val message = buildString {
            append("Prompt sources (highest priority wins):\n\n")

            for ((name, source) in sources) {
                append("• ${name.fileName}: ${source.displayName}\n")
            }

            append("\nPriority order:\n")
            append("1. Project: \${PROJECT}/.zest/prompts/\n")
            append("2. Dev: ~/.zest/dev-prompts/\n")
            append("3. Bundled: defaults")
        }

        ZestNotifications.showInfo(project, "Prompt Sources", message)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * Action to reload prompts from disk (clears cache).
 */
class ReloadPromptsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val loader = PromptLoader.getInstance(project)

        loader.clearCache()
        ZestNotifications.showInfo(project, "Prompts Reloaded", "Prompt cache cleared. Next use will load fresh from disk.")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
