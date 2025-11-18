package com.zps.zest.qwen

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Service that auto-generates Qwen Code CLI configuration with MCP server support.
 * Creates .qwen/ directory structure with settings.json pointing to Zest MCP server.
 */
@Service(Service.Level.PROJECT)
class QwenConfigurationService(private val project: Project) {

    companion object {
        private val logger = Logger.getInstance(QwenConfigurationService::class.java)

        private const val QWEN_FOLDER = ".qwen"
        private const val SETTINGS_FILE = "settings.json"
        private const val ENV_FILE = ".env"

        @JvmStatic
        fun getInstance(project: Project): QwenConfigurationService {
            return project.getService(QwenConfigurationService::class.java)
        }
    }

    /**
     * Initialize Qwen configuration on project startup
     */
    fun initializeQwenConfiguration() {
        try {
            val projectPath = project.basePath ?: return
            val qwenPath = Paths.get(projectPath, QWEN_FOLDER)

            if (!Files.exists(qwenPath)) {
                createQwenConfiguration(projectPath)
                showSetupNotification()
            } else {
                updateQwenConfiguration(projectPath)
            }

        } catch (e: Exception) {
            logger.error("Failed to initialize Qwen configuration", e)
        }
    }

    /**
     * Show notification about Qwen setup
     */
    private fun showSetupNotification() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.zps.zest.ZestNotifications.showInfo(
                project,
                "Qwen Code Integration",
                "Qwen configuration created at .qwen/ with MCP server support"
            )
        }
    }

    /**
     * Create Qwen configuration with MCP server
     */
    private fun createQwenConfiguration(projectPath: String) {
        val qwenPath = Paths.get(projectPath, QWEN_FOLDER)
        Files.createDirectories(qwenPath)

        createEnvFile(projectPath)
        createSettingsJson(projectPath)
        createReadme(projectPath)

        logger.info("Qwen configuration created successfully at: $qwenPath")
    }

    /**
     * Update existing Qwen configuration
     */
    private fun updateQwenConfiguration(projectPath: String) {
        val envPath = Paths.get(projectPath, ENV_FILE)
        if (!Files.exists(envPath)) {
            createEnvFile(projectPath)
        }

        val settingsPath = Paths.get(projectPath, QWEN_FOLDER, SETTINGS_FILE)
        if (!Files.exists(settingsPath)) {
            createSettingsJson(projectPath)
        }

        val readmePath = Paths.get(projectPath, QWEN_FOLDER, "README.md")
        if (!Files.exists(readmePath)) {
            createReadme(projectPath)
        }
    }

    /**
     * Generate .env file with OpenAI-compatible API credentials from Zest settings.
     * Safely merges with existing .env file if present.
     */
    private fun createEnvFile(projectPath: String) {
        val globalSettings = com.zps.zest.settings.ZestGlobalSettings.getInstance()

        val rawApiUrl = globalSettings.apiUrl.ifEmpty { "https://api.openai.com/v1" }
        val apiUrl = if (rawApiUrl.endsWith("/chat/completions")) {
            rawApiUrl.substringBeforeLast("/chat/completions")
        } else {
            rawApiUrl
        }
        val apiKey = globalSettings.authToken.ifEmpty { "your-api-key-here" }
        val model = globalSettings.codeModel.ifEmpty { "gpt-4" }

        val envPath = Paths.get(projectPath, ENV_FILE)

        val existingContent = if (Files.exists(envPath)) {
            Files.readString(envPath)
        } else {
            ""
        }

        val existingLines = existingContent.lines().toMutableList()
        val zestVars = setOf("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_MODEL")

        val filteredLines = existingLines.filterNot { line ->
            val key = line.trim().substringBefore('=')
            key in zestVars || line.trim().startsWith("# OpenAI-Compatible API Configuration") ||
            line.trim().startsWith("# Auto-generated from Zest")
        }

        val newEnvContent = buildString {
            if (filteredLines.isNotEmpty()) {
                appendLine(filteredLines.joinToString("\n"))
                appendLine()
            }

            appendLine("# OpenAI-Compatible API Configuration")
            appendLine("# Auto-generated from Zest IntelliJ Plugin settings")
            appendLine("# Modify in: IntelliJ → Settings → Tools → Zest")
            appendLine()
            appendLine("OPENAI_API_KEY=$apiKey")
            appendLine("OPENAI_BASE_URL=$apiUrl")
            append("OPENAI_MODEL=$model")
        }

        Files.writeString(envPath, newEnvContent)
        logger.info("Updated .env with API credentials from Zest settings")
    }

    /**
     * Generate .qwen/settings.json with MCP server configuration (SSE transport)
     */
    private fun createSettingsJson(projectPath: String) {
        val settings = """
{
  "mcpServers": {
    "zest-intellij": {
      "url": "http://localhost:45450/sse",
      "timeout": 30000
    }
  }
}
        """.trimIndent()

        val qwenPath = Paths.get(projectPath, QWEN_FOLDER)
        Files.createDirectories(qwenPath)

        val settingsPath = Paths.get(projectPath, QWEN_FOLDER, SETTINGS_FILE)
        Files.writeString(settingsPath, settings)
        logger.info("Created settings.json with MCP SSE server configuration")
    }

    /**
     * Create README for Qwen MCP integration
     */
    private fun createReadme(projectPath: String) {
        val readmeContent = """
# Qwen Code Integration for IntelliJ (Zest Plugin)

This directory contains auto-generated configuration for Qwen Code CLI integration with IntelliJ.

## MCP Server (Model Context Protocol)

The settings.json configures the **zest-intellij** MCP server in **HTTP mode**:

### Transport: HTTP (SSE)

The MCP server runs within the IntelliJ plugin as an HTTP server:
- **Auto-starts** when IntelliJ opens
- **Runs on port 45450** (http://localhost:45450/mcp)
- **Application-level** - Single server serves all open projects
- **Direct IntelliJ SDK access** - Uses PSI for code analysis
- **HTTP/SSE communication** - JSON-RPC over HTTP with Server-Sent Events

### Available MCP Tools

**getCurrentFile(projectPath)**
- Gets the currently open file in the IntelliJ editor
- Returns file path, language, and content
- Parameter: projectPath (absolute path to project)

**lookupMethod(projectPath, className, methodName)**
- Looks up method signatures using PSI
- Works with project classes, JARs, and JDK
- Returns method signatures with parameters and return types
- Parameters:
  - projectPath: Absolute path to project
  - className: Fully qualified class name
  - methodName: Method name to find

**lookupClass(projectPath, className)**
- Looks up class implementation using PSI
- Works with project classes, JARs, and JDK
- Returns class structure, fields, methods, inner classes
- Parameters:
  - projectPath: Absolute path to project
  - className: Fully qualified class name (use \$ for inner classes)

### Available MCP Prompts (Slash Commands)

The MCP server exposes prompts that appear as slash commands in Qwen Code CLI:

**test** - Generate unit tests for selected code
- Arguments: code (optional)
- Usage: `/test` or `/test <code snippet>`
- Generates comprehensive JUnit tests with edge cases

**review** - Code quality review and improvements
- Arguments: code (optional)
- Usage: `/review` or `/review <code snippet>`
- Analyzes code quality, bugs, performance, security

**explain** - Explain how code works
- Arguments: code (optional)
- Usage: `/explain` or `/explain <code snippet>`
- Provides clear explanation of code behavior and patterns

**refactor** - Suggest refactoring improvements
- Arguments: code (optional)
- Usage: `/refactor` or `/refactor <code snippet>`
- Recommends design patterns, duplication removal, naming improvements

**bugs** - Find potential bugs
- Arguments: code (optional)
- Usage: `/bugs` or `/bugs <code snippet>`
- Identifies null pointers, race conditions, memory leaks, logic errors

**optimize** - Performance optimization suggestions
- Arguments: code (optional)
- Usage: `/optimize` or `/optimize <code snippet>`
- Analyzes time/space complexity and resource usage

**commit** - Generate git commit message
- Arguments: diff (optional)
- Usage: `/commit` or `/commit <git diff>`
- Creates conventional commit messages from changes

## Usage with Qwen

The MCP server is automatically available when you use Qwen in this project:

```bash
# Using MCP tools
qwen "analyze the current file"
qwen "explain how UserService.getUserById works"
qwen "review the Customer class structure"

# Using MCP prompts as slash commands
qwen /test "function calculateTotal(items) { return items.reduce((a,b) => a + b.price, 0); }"
qwen /review
qwen /bugs "if (user.name = 'admin') { grantAccess(); }"
qwen /commit
```

## Configuration

API credentials are automatically sourced from Zest plugin settings:
- Base URL: Configured in IntelliJ → Settings → Tools → Zest
- API Key: From Zest settings
- Model: Selected model from Zest

No manual API configuration needed!

## Troubleshooting

**MCP server not responding:**
- Verify IntelliJ plugin is running
- Check port 45450 is not in use by another application
- Restart IntelliJ to restart the MCP server
- Check IntelliJ logs: Help → Show Log in Files

**Prompts/tools not working:**
- Verify project is open in IntelliJ (provide correct projectPath)
- Check that files are open in editor for getCurrentFile
- Use fully qualified class names for lookupMethod/lookupClass
- Ensure project path matches exactly (use forward slashes on Windows)

**Connection errors:**
- Visit http://localhost:45450/sse to test server is running
- Check firewall isn't blocking port 45450
- Verify no other MCP servers using same port

## Resources

- Qwen Code Documentation: https://docs.qwen.dev
- MCP Specification: https://modelcontextprotocol.io
- MCP Java SDK: https://github.com/modelcontextprotocol/java-sdk
""".trimIndent()

        val readmePath = Paths.get(projectPath, QWEN_FOLDER, "README.md")
        if (!Files.exists(readmePath)) {
            Files.writeString(readmePath, readmeContent, StandardOpenOption.CREATE)
            logger.info("Created README.md")
        }
    }
}

/**
 * Project listener to initialize Qwen configuration on project open
 */
class QwenConfigurationProjectListener : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        try {
            val service = QwenConfigurationService.getInstance(project)
            service.initializeQwenConfiguration()
        } catch (e: Exception) {
            Logger.getInstance(QwenConfigurationProjectListener::class.java)
                .error("Failed to initialize Qwen configuration on project open", e)
        }
    }
}