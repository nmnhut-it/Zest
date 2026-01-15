package com.zps.zest.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger

/**
 * Service to handle migration of prompts from old verbose versions to new concise versions
 */
@Service(Service.Level.PROJECT)
class PromptMigrationService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(PromptMigrationService::class.java)

        // Old prompt signatures to detect
        private const val OLD_SYSTEM_PROMPT_SIGNATURE = "You are an assistant that verifies understanding"
        private const val OLD_CODE_PROMPT_SIGNATURE = "You are an expert programming assistant with a sophisticated"

        // Migration version tracking
        private const val CURRENT_PROMPT_VERSION = 2

        fun getInstance(project: Project): PromptMigrationService {
            return project.getService(PromptMigrationService::class.java)
        }
    }

    /**
     * Check and migrate prompts if needed
     */
    fun checkAndMigratePrompts() {
        val globalSettings = ZestGlobalSettings.getInstance()
        val projectSettings = ZestProjectSettings.getInstance(project)

        // Check if migration is needed
        if (projectSettings.promptVersion >= CURRENT_PROMPT_VERSION) {
            LOG.info("Prompts are already up to date (version ${projectSettings.promptVersion})")
            return
        }

        LOG.info("Starting prompt migration from version ${projectSettings.promptVersion} to $CURRENT_PROMPT_VERSION")

        var migratedCount = 0

        // Migrate system prompt if it's the old version
        if (globalSettings.systemPrompt.contains(OLD_SYSTEM_PROMPT_SIGNATURE)) {
            LOG.info("Migrating old system prompt to concise version")
            globalSettings.systemPrompt = ZestGlobalSettings.DEFAULT_SYSTEM_PROMPT
            migratedCount++
        }

        // Migrate code prompt if it's the old version
        if (globalSettings.codeSystemPrompt.contains(OLD_CODE_PROMPT_SIGNATURE)) {
            LOG.info("Migrating old code prompt to concise version")
            globalSettings.codeSystemPrompt = ZestGlobalSettings.DEFAULT_CODE_SYSTEM_PROMPT
            migratedCount++
        }

        // Update version
        projectSettings.promptVersion = CURRENT_PROMPT_VERSION

        if (migratedCount > 0) {
            LOG.info("Successfully migrated $migratedCount prompts to concise versions")
        } else {
            LOG.info("No prompts needed migration, but updated version to $CURRENT_PROMPT_VERSION")
        }
    }

    /**
     * Force reset all prompts to latest defaults
     */
    fun resetToLatestDefaults() {
        val globalSettings = ZestGlobalSettings.getInstance()

        globalSettings.systemPrompt = ZestGlobalSettings.DEFAULT_SYSTEM_PROMPT
        globalSettings.codeSystemPrompt = ZestGlobalSettings.DEFAULT_CODE_SYSTEM_PROMPT
        globalSettings.commitPromptTemplate = ZestGlobalSettings.DEFAULT_COMMIT_PROMPT_TEMPLATE

        val projectSettings = ZestProjectSettings.getInstance(project)
        projectSettings.promptVersion = CURRENT_PROMPT_VERSION

        LOG.info("Reset all prompts to latest concise defaults")
    }

    /**
     * Check if prompts need migration
     */
    fun needsMigration(): Boolean {
        val projectSettings = ZestProjectSettings.getInstance(project)
        return projectSettings.promptVersion < CURRENT_PROMPT_VERSION
    }
}
