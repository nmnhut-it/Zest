package com.zps.zest.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.zps.zest.ZestNotifications
import com.zps.zest.completion.metrics.ActionMetricsHelper
import com.zps.zest.completion.metrics.FeatureType
import com.zps.zest.rules.ZestRulesLoader
import java.nio.file.Paths

/**
 * Action to create a default zest_rules.md file if it doesn't exist
 */
class CreateZestRulesAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        // Run update() on background thread to avoid EDT blocking
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ActionMetricsHelper.trackAction(
            project,
            FeatureType.CREATE_RULES_FILE,
            "Zest.CreateRulesFile",
            e,
            emptyMap()
        )

        val rulesLoader = ZestRulesLoader(project)
        
        if (rulesLoader.rulesFileExists()) {
            // Check if existing rules are minimal and offer to upgrade
            if (rulesLoader.isCurrentRulesFileMinimal()) {
                val upgraded = rulesLoader.forceUpgradeToSimplifiedRules()
                if (upgraded) {
                    ZestNotifications.showInfo(
                        project,
                        "Rules File Upgraded",
                        "Upgraded your rules file with streamlined quality-focused rules while preserving your custom content.\nOpening enhanced rules file for editing."
                    )
                } else {
                    ZestNotifications.showInfo(
                        project,
                        "Rules File Exists",
                        "The rules file exists. Opening it for editing.\nNote: Rules are now stored in .zest/rules.md"
                    )
                }
            } else {
                ZestNotifications.showInfo(
                    project,
                    "Rules File Exists",
                    "The rules file exists. Opening it for editing.\nNote: Rules are now stored in .zest/rules.md"
                )
            }
            // Open existing file
            openRulesFile(project)
        } else {
            // Create new file
            if (rulesLoader.createDefaultRulesFile()) {
                ZestNotifications.showInfo(
                    project,
                    "Rules File Created",
                    "Created rules file in .zest/rules.md. Add your custom LLM rules to this file."
                )
                // Open the newly created file
                openRulesFile(project)
            } else {
                ZestNotifications.showError(
                    project,
                    "Failed to Create Rules File",
                    "Could not create zest_rules.md. Please check project permissions."
                )
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
    
    private fun openRulesFile(project: Project) {
        val projectBasePath = project.basePath ?: return
        
        // Try new location first (.zest/rules.md)
        val newRulesPath = Paths.get(projectBasePath, ".zest", ZestRulesLoader.NEW_RULES_FILE_NAME)
        var file = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(newRulesPath)
        
        // Fallback to legacy location if new one doesn't exist
        if (file == null) {
            val legacyRulesPath = Paths.get(projectBasePath, ZestRulesLoader.RULES_FILE_NAME)
            file = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(legacyRulesPath)
        }
        
        file?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }
}
