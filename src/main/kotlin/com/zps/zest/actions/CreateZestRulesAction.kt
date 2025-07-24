package com.zps.zest.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.zps.zest.ZestNotifications
import com.zps.zest.rules.ZestRulesLoader
import java.nio.file.Paths

/**
 * Action to create a default zest_rules.md file if it doesn't exist
 */
class CreateZestRulesAction : AnAction("Create Zest Rules File", "Create a zest_rules.md file to define custom LLM rules", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val rulesLoader = ZestRulesLoader(project)
        
        if (rulesLoader.rulesFileExists()) {
            // Open existing file
            openRulesFile(project)
            ZestNotifications.showInfo(
                project,
                "Rules File Exists",
                "The rules file exists. Opening it for editing.\nNote: Rules are now stored in .zest/rules.md"
            )
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
        
        if (project != null) {
            val rulesLoader = ZestRulesLoader(project)
            e.presentation.text = if (rulesLoader.rulesFileExists()) {
                "Open Zest Rules File"
            } else {
                "Create Zest Rules File"
            }
        }
    }
    
    private fun openRulesFile(project: Project) {
        val projectBasePath = project.basePath ?: return
        val rulesPath = Paths.get(projectBasePath, ZestRulesLoader.RULES_FILE_NAME)
        
        VirtualFileManager.getInstance().refreshAndFindFileByNioPath(rulesPath)?.let { file ->
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}
