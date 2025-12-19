package com.zps.zest.continuedev

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Service that generates Continue.dev configuration with MCP server support.
 * Creates ~/.continue/mcpServers/zest.json pointing to Zest MCP server.
 *
 * Continue.dev uses JSON MCP format compatible with Claude Desktop, Cursor, and Cline.
 */
@Service(Service.Level.PROJECT)
class ContinueConfigurationService(private val project: Project) {

    companion object {
        private val logger = Logger.getInstance(ContinueConfigurationService::class.java)

        private const val CONTINUE_FOLDER = ".continue"
        private const val MCP_SERVERS_FOLDER = "mcpServers"
        private const val CONFIG_FILE = "zest.json"
        private const val README_FILE = "README.md"

        @JvmStatic
        fun getInstance(project: Project): ContinueConfigurationService {
            return project.getService(ContinueConfigurationService::class.java)
        }

        /**
         * Get user's home directory .continue path
         */
        fun getGlobalContinuePath(): Path {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, CONTINUE_FOLDER, MCP_SERVERS_FOLDER)
        }

        /**
         * Get the config file path
         */
        fun getConfigFilePath(): Path {
            return getGlobalContinuePath().resolve(CONFIG_FILE)
        }
    }

    /**
     * Setup Continue.dev MCP configuration - triggered manually by user action
     * Creates config in user's home directory: ~/.continue/mcpServers/zest.json
     */
    fun setupContinueConfiguration(): SetupResult {
        return try {
            val mcpServersPath = getGlobalContinuePath()
            Files.createDirectories(mcpServersPath)

            val configPath = mcpServersPath.resolve(CONFIG_FILE)
            val alreadyExists = Files.exists(configPath)

            if (alreadyExists) {
                // Check if zest-intellij already configured
                val existingContent = Files.readString(configPath)
                if (existingContent.contains("zest-intellij")) {
                    return SetupResult(
                        success = true,
                        configPath = configPath,
                        message = "Zest MCP already configured in Continue.dev",
                        alreadyConfigured = true
                    )
                }
                // Update existing config to add zest-intellij
                updateMcpConfigJson(configPath)
            } else {
                createMcpConfigJson(configPath)
            }

            createReadme(mcpServersPath)
            showSetupNotification(configPath)

            SetupResult(
                success = true,
                configPath = configPath,
                message = "Continue.dev MCP configuration created successfully",
                alreadyConfigured = false
            )
        } catch (e: Exception) {
            logger.error("Failed to setup Continue.dev configuration", e)
            SetupResult(
                success = false,
                configPath = null,
                message = "Failed to create config: ${e.message}",
                alreadyConfigured = false
            )
        }
    }

    /**
     * Show notification about Continue.dev setup
     */
    private fun showSetupNotification(configPath: Path) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.zps.zest.ZestNotifications.showInfo(
                project,
                "Continue.dev MCP Integration",
                "Zest MCP config created at:\n$configPath\n\nRestart Continue.dev to use IntelliJ tools."
            )
        }
    }

    /**
     * Generate MCP configuration JSON for Continue.dev
     * Uses SSE transport format compatible with Claude Desktop, Cursor, Cline
     */
    private fun createMcpConfigJson(configPath: Path) {
        val config = """
{
  "mcpServers": {
    "zest-intellij": {
      "url": "http://localhost:45450/sse",
      "transport": "sse"
    }
  }
}
        """.trimIndent()

        Files.writeString(configPath, config, StandardOpenOption.CREATE)
        logger.info("Created Continue.dev MCP config: $configPath")
    }

    /**
     * Update existing MCP configuration to add zest-intellij
     */
    private fun updateMcpConfigJson(configPath: Path) {
        try {
            val existingContent = Files.readString(configPath)
            if (!existingContent.contains("zest-intellij")) {
                // Add zest-intellij to existing config
                val updatedContent = if (existingContent.contains("\"mcpServers\"")) {
                    // Insert into existing mcpServers block
                    existingContent.replace(
                        "\"mcpServers\": {",
                        "\"mcpServers\": {\n    \"zest-intellij\": {\n      \"url\": \"http://localhost:45450/sse\",\n      \"transport\": \"sse\"\n    },"
                    )
                } else {
                    // Create new config (shouldn't happen but handle gracefully)
                    """
{
  "mcpServers": {
    "zest-intellij": {
      "url": "http://localhost:45450/sse",
      "transport": "sse"
    }
  }
}
                    """.trimIndent()
                }
                Files.writeString(configPath, updatedContent)
                logger.info("Updated Continue.dev MCP config with zest-intellij")
            }
        } catch (e: Exception) {
            logger.warn("Could not update existing Continue.dev config", e)
            throw e
        }
    }

    /**
     * Create README explaining Zest MCP integration for Continue.dev
     */
    private fun createReadme(mcpServersPath: Path) {
        val readmeContent = """
# Zest MCP Integration for Continue.dev

This folder contains MCP (Model Context Protocol) server configurations for Continue.dev.

## Zest IntelliJ MCP Server

The `zest.json` file configures Continue.dev to connect to Zest's MCP server running in IntelliJ IDEA.

### Requirements

- IntelliJ IDEA with Zest plugin installed and running
- MCP server running on port 45450 (auto-starts with IntelliJ)

### Available Tools

| Tool | Description |
|------|-------------|
| `getCurrentFile` | Get currently open file in IntelliJ editor |
| `lookupClass` | Look up class implementation (project, JARs, JDK) |
| `lookupMethod` | Look up method signatures |
| `analyzeMethodUsage` | Analyze how a method is used in codebase |
| `getJavaCodeUnderTest` | Interactive GUI to select Java code for testing |
| `validateCode` | Validate Java code for compilation errors |
| `showFile` | Open a file in IntelliJ editor |

### Available Prompts

| Prompt | Description |
|--------|-------------|
| `review` | Code quality review |
| `explain` | Explain how code works |
| `commit` | Git commit assistant |
| `zest-test-context` | Gather test context |
| `zest-test-plan` | Create test plan |
| `zest-test-write` | Write tests |
| `zest-test-fix` | Fix failing tests |

### Usage in Continue.dev

Once configured, you can use Zest tools in Continue.dev:

```
@zest-intellij getCurrentFile
@zest-intellij lookupClass com.example.MyClass
@zest-intellij analyzeMethodUsage com.example.Service doSomething
```

### Troubleshooting

**MCP server not responding:**
- Verify IntelliJ IDEA is running with Zest plugin
- Check http://localhost:45450/sse is accessible
- Restart IntelliJ to restart MCP server

**Tools not appearing in Continue.dev:**
- Restart Continue.dev after adding config
- Check Continue.dev logs for connection errors

## Resources

- [Continue.dev MCP Documentation](https://docs.continue.dev/customize/model-context-protocol)
- [MCP Specification](https://modelcontextprotocol.io)
- [Zest Plugin](https://plugins.jetbrains.com/plugin/zest)
        """.trimIndent()

        val readmePath = mcpServersPath.resolve(README_FILE)
        if (!Files.exists(readmePath)) {
            Files.writeString(readmePath, readmeContent, StandardOpenOption.CREATE)
            logger.info("Created README.md for Continue.dev MCP integration")
        }
    }

    /**
     * Result of setup operation
     */
    data class SetupResult(
        val success: Boolean,
        val configPath: Path?,
        val message: String,
        val alreadyConfigured: Boolean
    )
}
