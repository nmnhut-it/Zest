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
 * Supports Claude Desktop, Cursor, Cline, Windsurf, and Continue.dev.
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
     * Supported AI client types with their config locations.
     */
    enum class ClientType(
        val displayName: String,
        val configFileName: String
    ) {
        CONTINUE_DEV("Continue.dev", "zest.json"),
        CLAUDE_DESKTOP("Claude Desktop", "claude_desktop_config.json"),
        CURSOR("Cursor", "mcp.json"),
        CLINE("Cline", "cline_mcp_settings.json"),
        WINDSURF("Windsurf", "mcp_config.json"),
        CLAUDE_CODE("Claude Code", "settings.json"),
        KILO_CODE("Kilo Code", "mcp_settings.json"),
        GEMINI_CLI("Gemini CLI", "settings.json"),
        QWEN_CODER("Qwen Coder", "mcp_config.json")
    }

    /**
     * Get config directory path for each client type.
     */
    private fun getConfigPath(client: ClientType): Path {
        val userHome = System.getProperty("user.home")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

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
                // Cline uses VS Code settings directory
                if (isWindows) {
                    Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Code", "User", "globalStorage", "saoudrizwan.claude-dev")
                } else {
                    Paths.get(userHome, ".config", "Code", "User", "globalStorage", "saoudrizwan.claude-dev")
                }
            }
            ClientType.WINDSURF -> Paths.get(userHome, ".windsurf")
            ClientType.CLAUDE_CODE -> Paths.get(userHome, ".claude")
            ClientType.KILO_CODE -> {
                if (isWindows) {
                    Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Code", "User", "globalStorage", "kilocode.kilo-code")
                } else {
                    Paths.get(userHome, ".config", "Code", "User", "globalStorage", "kilocode.kilo-code")
                }
            }
            ClientType.GEMINI_CLI -> Paths.get(userHome, ".gemini")
            ClientType.QWEN_CODER -> Paths.get(userHome, ".qwen-coder")
        }
    }

    /**
     * Setup MCP configuration for the specified client.
     */
    fun setupClient(client: ClientType): SetupResult {
        return try {
            val configDir = getConfigPath(client)
            Files.createDirectories(configDir)

            val configPath = configDir.resolve(client.configFileName)
            val url = "http://localhost:$DEFAULT_PORT/mcp"

            val alreadyExists = Files.exists(configPath)
            if (alreadyExists) {
                val existingContent = Files.readString(configPath)
                if (existingContent.contains(SERVER_NAME)) {
                    return SetupResult(
                        success = true,
                        configPath = configPath,
                        message = "Zest MCP already configured for ${client.displayName}",
                        alreadyConfigured = true,
                        client = client
                    )
                }
                updateConfig(configPath, url, client)
            } else {
                createConfig(configPath, url, client)
            }

            showNotification(client, configPath)

            SetupResult(
                success = true,
                configPath = configPath,
                message = "${client.displayName} MCP configuration created",
                alreadyConfigured = false,
                client = client
            )
        } catch (e: Exception) {
            LOG.error("Failed to setup ${client.displayName} configuration", e)
            SetupResult(
                success = false,
                configPath = null,
                message = "Failed: ${e.message}",
                alreadyConfigured = false,
                client = client
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
            ClientType.CLINE -> "\"$SERVER_NAME\": { \"url\": \"$url\", \"disabled\": false }"
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

    private fun showNotification(client: ClientType, configPath: Path) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            ZestNotifications.showInfo(
                project,
                "${client.displayName} MCP Setup",
                "Zest MCP configured at:\n$configPath\n\nRestart ${client.displayName} to use IntelliJ tools."
            )
        }
    }

    /**
     * Check if a client is available/installed on the system.
     * Checks both config directories and CLI commands in PATH.
     */
    fun isClientAvailable(client: ClientType): Boolean {
        // First check if config directory exists
        val configDir = getConfigPath(client)
        if (Files.exists(configDir) || Files.exists(configDir.parent)) {
            return true
        }

        // Also check for CLI commands in PATH
        return when (client) {
            ClientType.CLAUDE_CODE -> isCommandInPath("claude")
            ClientType.CURSOR -> isCommandInPath("cursor")
            ClientType.WINDSURF -> isCommandInPath("windsurf")
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
        val client: ClientType
    )
}
