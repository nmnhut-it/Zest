package com.zps.zest.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.core.ZestNotifications
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Service to install Zest skills (SKILL.md files) to AI coding clients.
 *
 * Skills are loaded from multiple sources with priority:
 * 1. Project: $PROJECT/.zest/skills/ (project-specific)
 * 2. User Dev: ~/.zest/dev-skills/ (development override, no rebuild needed)
 * 3. Bundled: JAR resources (default)
 *
 * To modify skills without rebuilding:
 * - Copy skills to ~/.zest/dev-skills/
 * - Edit there, changes are picked up on next install
 */
@Service(Service.Level.PROJECT)
class SkillInstallationService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(SkillInstallationService::class.java)

        /** Skills that Zest provides */
        private val SKILL_NAMES = listOf("bitzero-test", "bitzero-review", "bitzero-methodology")

        /** User-level dev skills directory (no rebuild needed) */
        private val USER_DEV_SKILLS_DIR: Path = Paths.get(System.getProperty("user.home"), ".zest", "dev-skills")

        @JvmStatic
        fun getInstance(project: Project): SkillInstallationService {
            return project.getService(SkillInstallationService::class.java)
        }

        /**
         * Get the user dev skills directory.
         * Create it if it doesn't exist when in dev mode.
         */
        @JvmStatic
        fun getDevSkillsDir(): Path = USER_DEV_SKILLS_DIR
    }

    /** Where a skill was loaded from */
    enum class SkillSource(val displayName: String) {
        PROJECT("Project (.zest/skills)"),
        USER_DEV("Dev (~/.zest/dev-skills)"),
        BUNDLED("Bundled (plugin)")
    }

    /** Installation scope - where to install skills */
    enum class InstallScope(val displayName: String) {
        USER("User (global)"),
        PROJECT("Project (local)")
    }

    /** AI clients that support skills */
    enum class SkillClient(val displayName: String, val skillsDirName: String, val projectSkillsDir: String) {
        QWEN_CODER("Qwen Coder", "skills", ".qwen/skills"),
        CLAUDE_CODE("Claude Code", "skills", ".claude/skills");

        fun getSkillsPath(): Path {
            val userHome = System.getProperty("user.home")
            return when (this) {
                QWEN_CODER -> Paths.get(userHome, ".qwen", skillsDirName)
                CLAUDE_CODE -> Paths.get(userHome, ".claude", skillsDirName)
            }
        }

        fun getProjectSkillsPath(projectPath: String): Path {
            return Paths.get(projectPath, projectSkillsDir)
        }

        fun getConfigPath(): Path {
            val userHome = System.getProperty("user.home")
            return when (this) {
                QWEN_CODER -> Paths.get(userHome, ".qwen")
                CLAUDE_CODE -> Paths.get(userHome, ".claude")
            }
        }
    }

    /** Result of skill installation */
    data class InstallResult(
        val success: Boolean,
        val client: SkillClient,
        val skillsInstalled: List<String>,
        val message: String,
        val sources: Map<String, SkillSource> = emptyMap(),
        val alreadyInstalled: Boolean = false,
        val scope: InstallScope = InstallScope.USER
    )

    /** Project-level skills directory */
    private fun getProjectSkillsDir(): Path {
        return Paths.get(project.basePath ?: "", ".zest", "skills")
    }

    /**
     * Check if a client is available on the system.
     */
    fun isClientAvailable(client: SkillClient): Boolean {
        val configPath = client.getConfigPath()
        if (Files.exists(configPath)) {
            return true
        }
        return when (client) {
            SkillClient.QWEN_CODER -> isCommandInPath("qwen")
            SkillClient.CLAUDE_CODE -> isCommandInPath("claude")
        }
    }

    /**
     * Find where a skill exists, checking in priority order.
     * Returns null if skill not found anywhere.
     */
    fun findSkillSource(skillName: String): Pair<SkillSource, Path>? {
        // 1. Check project directory
        val projectPath = getProjectSkillsDir().resolve(skillName).resolve("SKILL.md")
        if (Files.exists(projectPath)) {
            return SkillSource.PROJECT to projectPath
        }

        // 2. Check user dev directory
        val userDevPath = USER_DEV_SKILLS_DIR.resolve(skillName).resolve("SKILL.md")
        if (Files.exists(userDevPath)) {
            return SkillSource.USER_DEV to userDevPath
        }

        // 3. Check bundled resources
        val resourcePath = "/skills/$skillName/SKILL.md"
        if (javaClass.getResourceAsStream(resourcePath) != null) {
            return SkillSource.BUNDLED to Paths.get(resourcePath)
        }

        return null
    }

    /**
     * Get skill content from the highest priority source.
     */
    fun getSkillContent(skillName: String): String? {
        val (source, path) = findSkillSource(skillName) ?: return null

        return when (source) {
            SkillSource.PROJECT, SkillSource.USER_DEV -> {
                Files.readString(path)
            }
            SkillSource.BUNDLED -> {
                javaClass.getResourceAsStream("/skills/$skillName/SKILL.md")?.bufferedReader()?.readText()
            }
        }
    }

    /**
     * Install all skills to the specified client at user (global) level.
     */
    fun installSkills(client: SkillClient): InstallResult {
        return installSkills(client, InstallScope.USER)
    }

    /**
     * Install all skills to the specified client at the given scope.
     * Skills are loaded from external dirs first, then bundled.
     */
    fun installSkills(client: SkillClient, scope: InstallScope): InstallResult {
        return try {
            val skillsDir = when (scope) {
                InstallScope.USER -> client.getSkillsPath()
                InstallScope.PROJECT -> {
                    val projectPath = project.basePath
                        ?: return InstallResult(
                            success = false,
                            client = client,
                            skillsInstalled = emptyList(),
                            message = "Project path not available",
                            scope = scope
                        )
                    client.getProjectSkillsPath(projectPath)
                }
            }
            Files.createDirectories(skillsDir)

            val installed = mutableListOf<String>()
            val sources = mutableMapOf<String, SkillSource>()
            var anyNew = false

            for (skillName in SKILL_NAMES) {
                val targetDir = skillsDir.resolve(skillName)
                val (wasNew, source) = installSkill(skillName, targetDir)
                if (wasNew) anyNew = true
                installed.add(skillName)
                sources[skillName] = source
            }

            showNotification(client, installed, sources, anyNew, scope)

            InstallResult(
                success = true,
                client = client,
                skillsInstalled = installed,
                message = if (anyNew) "Skills installed (${scope.displayName})" else "Skills already up to date",
                sources = sources,
                alreadyInstalled = !anyNew,
                scope = scope
            )
        } catch (e: Exception) {
            LOG.error("Failed to install skills to ${client.displayName}", e)
            InstallResult(
                success = false,
                client = client,
                skillsInstalled = emptyList(),
                message = "Failed: ${e.message}",
                scope = scope
            )
        }
    }

    /**
     * Install a single skill from the highest priority source.
     * Returns (wasNewlyInstalled, source).
     */
    private fun installSkill(skillName: String, targetDir: Path): Pair<Boolean, SkillSource> {
        val skillMdPath = targetDir.resolve("SKILL.md")
        val alreadyExists = Files.exists(skillMdPath)

        Files.createDirectories(targetDir)

        val (source, sourcePath) = findSkillSource(skillName)
            ?: throw IllegalStateException("Skill not found: $skillName")

        when (source) {
            SkillSource.PROJECT, SkillSource.USER_DEV -> {
                // Copy from external file
                Files.copy(sourcePath, skillMdPath, StandardCopyOption.REPLACE_EXISTING)
                LOG.info("Installed skill $skillName from ${source.displayName}: $sourcePath")
            }
            SkillSource.BUNDLED -> {
                // Copy from bundled resources
                val resourcePath = "/skills/$skillName/SKILL.md"
                javaClass.getResourceAsStream(resourcePath)?.use { input ->
                    Files.copy(input, skillMdPath, StandardCopyOption.REPLACE_EXISTING)
                } ?: throw IllegalStateException("Bundled skill resource not found: $resourcePath")
                LOG.info("Installed skill $skillName from bundled resources")
            }
        }

        return !alreadyExists to source
    }

    /**
     * Initialize dev skills directory with bundled skills for editing.
     * Call this to set up dev mode - skills can then be edited without rebuild.
     */
    fun initializeDevSkills(): List<String> {
        Files.createDirectories(USER_DEV_SKILLS_DIR)

        val initialized = mutableListOf<String>()
        for (skillName in SKILL_NAMES) {
            val targetDir = USER_DEV_SKILLS_DIR.resolve(skillName)
            val skillMdPath = targetDir.resolve("SKILL.md")

            // Only copy if doesn't exist (don't overwrite edits)
            if (!Files.exists(skillMdPath)) {
                Files.createDirectories(targetDir)
                val resourcePath = "/skills/$skillName/SKILL.md"
                javaClass.getResourceAsStream(resourcePath)?.use { input ->
                    Files.copy(input, skillMdPath)
                    initialized.add(skillName)
                }
            }
        }

        if (initialized.isNotEmpty()) {
            LOG.info("Initialized dev skills: $initialized at $USER_DEV_SKILLS_DIR")
        }

        return initialized
    }

    /**
     * Get list of all skill names.
     */
    fun getSkillNames(): List<String> = SKILL_NAMES

    /**
     * Get all available clients that support skills.
     */
    fun getAvailableClients(): List<SkillClient> {
        return SkillClient.entries.filter { isClientAvailable(it) }
    }

    /**
     * Get info about where each skill will be loaded from.
     */
    fun getSkillSourceInfo(): Map<String, SkillSource?> {
        return SKILL_NAMES.associateWith { skillName ->
            findSkillSource(skillName)?.first
        }
    }

    private fun showNotification(
        client: SkillClient,
        skills: List<String>,
        sources: Map<String, SkillSource>,
        isNew: Boolean,
        scope: InstallScope = InstallScope.USER
    ) {
        val scopeLabel = if (scope == InstallScope.PROJECT) " (Project)" else ""
        val title = "${client.displayName} Skills$scopeLabel"

        val skillList = skills.joinToString("\n") { name ->
            val source = sources[name]?.displayName ?: "unknown"
            "- $name ($source)"
        }

        val message = if (isNew) {
            "Installed ${skills.size} skills:\n$skillList\n\nRestart ${client.displayName} to use them."
        } else {
            "Skills up to date:\n$skillList"
        }

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            ZestNotifications.showInfo(project, title, message)
        }
    }

    private fun isCommandInPath(command: String): Boolean {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val process = if (isWindows) {
                ProcessBuilder("where", command)
            } else {
                ProcessBuilder("which", command)
            }
            process.redirectErrorStream(true)
            val result = process.start()
            result.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
