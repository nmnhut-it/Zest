package com.zps.zest.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.core.ZestNotifications
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Service to setup Zest MCP configuration for various AI coding clients.
 * Supports both user-level (global) and project-level installation.
 *
 * Project-level configs are stored in the project directory and can be version-controlled.
 * User-level configs are stored in the user's home directory.
 */
@Service(Service.Level.PROJECT)
class AiClientConfigService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AiClientConfigService::class.java)
        private const val SERVER_NAME = "zest-intellij"
        private const val DEFAULT_PORT = 45450

        @JvmStatic
        fun getInstance(project: Project): AiClientConfigService {
            return project.getService(AiClientConfigService::class.java)
        }
    }

    /**
     * Installation scope - where to install the config.
     */
    enum class InstallScope(val displayName: String) {
        USER("User (global)"),
        PROJECT("Project (local)")
    }

    /**
     * Supported AI client types with their config locations.
     * Some clients use different file names for project-level configs.
     */
    enum class ClientType(
        val displayName: String,
        val configFileName: String,
        val projectConfigFileName: String,
        val projectConfigDir: String,
        val supportsProjectScope: Boolean
    ) {
        CONTINUE_DEV("Continue.dev", "zest.json", "zest.json", ".continue/mcpServers", true),
        CLAUDE_DESKTOP("Claude Desktop", "claude_desktop_config.json", "", "", false),
        CURSOR("Cursor", "mcp.json", "mcp.json", ".cursor", true),
        CLINE("Cline", "cline_mcp_settings.json", "", "", false),
        WINDSURF("Windsurf", "mcp_config.json", "mcp_config.json", ".windsurf", true),
        CLAUDE_CODE("Claude Code", "settings.json", "mcp.json", "", true),  // Uses .mcp.json at project root
        KILO_CODE("Kilo Code", "mcp_settings.json", "mcp.json", ".kilocode", true),
        GEMINI_CLI("Gemini CLI", "settings.json", "settings.json", ".gemini", true),
        QWEN_CODER("Qwen Coder", "settings.json", "settings.json", ".qwen", true)
    }

    /**
     * Get config directory path for user-level (global) installation.
     */
    private fun getUserConfigPath(client: ClientType): Path {
        val userHome = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("windows")
        val isMac = osName.contains("mac")

        return when (client) {
            ClientType.CONTINUE_DEV -> Paths.get(userHome, ".continue", "mcpServers")
            ClientType.CLAUDE_DESKTOP -> {
                if (isWindows) {
                    Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Claude")
                } else {
                    Paths.get(userHome, "Library", "Application Support", "Claude")
                }
            }
            ClientType.CURSOR -> Paths.get(userHome, ".cursor")
            ClientType.CLINE -> {
                // Cline uses VS Code globalStorage directory
                when {
                    isWindows -> Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Code", "User", "globalStorage", "saoudrizwan.claude-dev")
                    isMac -> Paths.get(userHome, "Library", "Application Support", "Code", "User", "globalStorage", "saoudrizwan.claude-dev")
                    else -> Paths.get(userHome, ".config", "Code", "User", "globalStorage", "saoudrizwan.claude-dev")
                }
            }
            ClientType.WINDSURF -> {
                if (isWindows) {
                    Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Codeium", "Windsurf")
                } else {
                    Paths.get(userHome, ".codeium", "windsurf")
                }
            }
            ClientType.CLAUDE_CODE -> Paths.get(userHome, ".claude")
            ClientType.KILO_CODE -> {
                // Kilo Code uses VS Code globalStorage directory
                when {
                    isWindows -> Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Code", "User", "globalStorage", "kilocode.kilo-code", "settings")
                    isMac -> Paths.get(userHome, "Library", "Application Support", "Code", "User", "globalStorage", "kilocode.kilo-code", "settings")
                    else -> Paths.get(userHome, ".config", "Code", "User", "globalStorage", "kilocode.kilo-code", "settings")
                }
            }
            ClientType.GEMINI_CLI -> Paths.get(userHome, ".gemini")
            ClientType.QWEN_CODER -> Paths.get(userHome, ".qwen")
        }
    }

    /**
     * Get config directory path for project-level installation.
     * Returns null if project path is not available.
     */
    private fun getProjectConfigPath(client: ClientType): Path? {
        val projectPath = project.basePath ?: return null

        return when (client) {
            // Claude Code uses .mcp.json at project root (no subdirectory)
            ClientType.CLAUDE_CODE -> Paths.get(projectPath)
            // Others use their config directory in the project
            else -> {
                if (client.projectConfigDir.isNotEmpty()) {
                    Paths.get(projectPath, client.projectConfigDir)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Get the config file path based on scope and client.
     */
    private fun getConfigPath(client: ClientType, scope: InstallScope): Path? {
        return when (scope) {
            InstallScope.USER -> getUserConfigPath(client)
            InstallScope.PROJECT -> getProjectConfigPath(client)
        }
    }

    /**
     * Get the config file name based on scope.
     */
    private fun getConfigFileName(client: ClientType, scope: InstallScope): String {
        return when (scope) {
            InstallScope.USER -> client.configFileName
            InstallScope.PROJECT -> client.projectConfigFileName
        }
    }

    /**
     * Setup MCP configuration for the specified client at user (global) level.
     */
    fun setupClient(client: ClientType): SetupResult {
        return setupClient(client, InstallScope.USER)
    }

    /**
     * Setup MCP configuration for the specified client at the given scope.
     */
    fun setupClient(client: ClientType, scope: InstallScope): SetupResult {
        // Check if client supports the requested scope
        if (scope == InstallScope.PROJECT && !client.supportsProjectScope) {
            return SetupResult(
                success = false,
                configPath = null,
                message = "${client.displayName} does not support project-level configuration",
                alreadyConfigured = false,
                client = client,
                scope = scope
            )
        }

        return try {
            val configDir = getConfigPath(client, scope)
                ?: return SetupResult(
                    success = false,
                    configPath = null,
                    message = "Project path not available",
                    alreadyConfigured = false,
                    client = client,
                    scope = scope
                )

            Files.createDirectories(configDir)

            val configFileName = getConfigFileName(client, scope)
            val configPath = configDir.resolve(configFileName)
            val url = "http://localhost:$DEFAULT_PORT/mcp"

            val alreadyExists = Files.exists(configPath)
            if (alreadyExists) {
                val existingContent = Files.readString(configPath)
                if (existingContent.contains(SERVER_NAME)) {
                    return SetupResult(
                        success = true,
                        configPath = configPath,
                        message = "Zest MCP already configured for ${client.displayName} (${scope.displayName})",
                        alreadyConfigured = true,
                        client = client,
                        scope = scope
                    )
                }
                updateConfig(configPath, url, client)
            } else {
                createConfig(configPath, url, client)
            }

            showNotification(client, configPath, scope)

            SetupResult(
                success = true,
                configPath = configPath,
                message = "${client.displayName} MCP configuration created (${scope.displayName})",
                alreadyConfigured = false,
                client = client,
                scope = scope
            )
        } catch (e: Exception) {
            LOG.error("Failed to setup ${client.displayName} configuration", e)
            SetupResult(
                success = false,
                configPath = null,
                message = "Failed: ${e.message}",
                alreadyConfigured = false,
                client = client,
                scope = scope
            )
        }
    }

    /**
     * Create new config file with Zest MCP settings.
     */
    private fun createConfig(configPath: Path, url: String, client: ClientType) {
        val config = generateConfig(url, client)
        Files.writeString(configPath, config, StandardOpenOption.CREATE)
        LOG.info("Created ${client.displayName} MCP config: $configPath")
    }

    /**
     * Update existing config to add Zest MCP server.
     */
    private fun updateConfig(configPath: Path, url: String, client: ClientType) {
        val existingContent = Files.readString(configPath)

        val updatedContent = if (existingContent.contains("\"mcpServers\"")) {
            // Add to existing mcpServers block
            val zestEntry = generateServerEntry(url, client)
            existingContent.replace(
                "\"mcpServers\": {",
                "\"mcpServers\": {\n    $zestEntry,"
            )
        } else if (existingContent.trim().startsWith("{")) {
            // JSON exists but no mcpServers - add it
            val serverBlock = generateServerBlock(url, client)
            existingContent.trimEnd().dropLast(1) + ",\n  $serverBlock\n}"
        } else {
            // Create new config
            generateConfig(url, client)
        }

        Files.writeString(configPath, updatedContent)
        LOG.info("Updated ${client.displayName} MCP config with Zest")
    }

    private fun generateServerEntry(url: String, client: ClientType): String {
        return when (client) {
            ClientType.WINDSURF -> "\"$SERVER_NAME\": { \"serverUrl\": \"$url\" }"
            ClientType.CLINE, ClientType.KILO_CODE -> "\"$SERVER_NAME\": { \"url\": \"$url\", \"disabled\": false }"
            ClientType.CLAUDE_CODE -> "\"$SERVER_NAME\": { \"type\": \"url\", \"url\": \"$url\" }"
            ClientType.QWEN_CODER -> "\"$SERVER_NAME\": { \"httpUrl\": \"$url\", \"timeout\": 660000 }"
            else -> "\"$SERVER_NAME\": { \"url\": \"$url\" }"
        }
    }

    private fun generateServerBlock(url: String, client: ClientType): String {
        return "\"mcpServers\": {\n    ${generateServerEntry(url, client)}\n  }"
    }

    private fun generateConfig(url: String, client: ClientType): String {
        return when (client) {
            ClientType.WINDSURF -> """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "serverUrl": "$url"
    }
  }
}
            """.trimIndent()
            ClientType.CLINE, ClientType.KILO_CODE -> """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url",
      "disabled": false
    }
  }
}
            """.trimIndent()
            ClientType.CLAUDE_CODE -> """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "type": "url",
      "url": "$url"
    }
  }
}
            """.trimIndent()
            ClientType.QWEN_CODER -> """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "httpUrl": "$url",
      "timeout": 660000
    }
  }
}
            """.trimIndent()
            else -> """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
            """.trimIndent()
        }
    }

    private fun showNotification(client: ClientType, configPath: Path, scope: InstallScope = InstallScope.USER) {
        val scopeLabel = if (scope == InstallScope.PROJECT) " (Project)" else ""
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            ZestNotifications.showInfo(
                project,
                "${client.displayName} MCP Setup$scopeLabel",
                "Zest MCP configured at:\n$configPath\n\nRestart ${client.displayName} to use IntelliJ tools."
            )
        }
    }

    /**
     * Check if a client is available/installed on the system.
     * Checks both config directories and CLI commands in PATH.
     */
    fun isClientAvailable(client: ClientType): Boolean {
        // First check if user-level config directory exists
        val configDir = getUserConfigPath(client)
        if (Files.exists(configDir) || Files.exists(configDir.parent)) {
            return true
        }

        // Also check for CLI commands in PATH
        return when (client) {
            ClientType.CLAUDE_CODE -> isCommandInPath("claude")
            ClientType.CURSOR -> isCommandInPath("cursor")
            ClientType.WINDSURF -> isCommandInPath("windsurf")
            ClientType.QWEN_CODER -> isCommandInPath("qwen")
            ClientType.GEMINI_CLI -> isCommandInPath("gemini")
            else -> false
        }
    }

    /**
     * Check if a command exists in PATH.
     */
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

    /**
     * Get all available clients that are likely installed.
     */
    fun getAvailableClients(): List<ClientType> {
        return ClientType.values().filter { isClientAvailable(it) }
    }

    data class SetupResult(
        val success: Boolean,
        val configPath: Path?,
        val message: String,
        val alreadyConfigured: Boolean,
        val client: ClientType,
        val scope: InstallScope = InstallScope.USER
    )

    /**
     * Get clients that support project-level configuration.
     */
    fun getProjectScopeClients(): List<ClientType> {
        return ClientType.entries.filter { it.supportsProjectScope }
    }
}
