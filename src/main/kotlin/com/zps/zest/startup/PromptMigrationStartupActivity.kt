package com.zps.zest.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zps.zest.settings.PromptMigrationService

/**
 * Startup activity to check and migrate prompts when project opens
 */
class PromptMigrationStartupActivity : ProjectActivity {
    
    override suspend fun execute(project: Project) {
        // Run migration check in background
        val migrationService = PromptMigrationService.getInstance(project)
        
        if (migrationService.needsMigration()) {
            migrationService.checkAndMigratePrompts()
        }
    }
}
