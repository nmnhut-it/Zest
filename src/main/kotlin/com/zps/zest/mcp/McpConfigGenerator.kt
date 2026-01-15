package com.zps.zest.mcp

import com.intellij.openapi.project.Project

/**
 * Generates MCP server configuration snippets for various AI clients.
 * Supports JSON and YAML formats for easy copy-paste setup.
 */
object McpConfigGenerator {

    private const val DEFAULT_PORT = 45450
    private const val SERVER_NAME = "zest-intellij"

    /**
     * Supported MCP client types with their configuration formats.
     */
    enum class ClientType(
        val displayName: String,
        val configFileName: String,
        val configPath: String,
        val docsUrl: String
    ) {
        CLAUDE_DESKTOP(
            "Claude Desktop",
            "claude_desktop_config.json",
            "~/Library/Application Support/Claude/ (macOS)\n%APPDATA%\\Claude\\ (Windows)",
            "https://modelcontextprotocol.io/quickstart/user"
        ),
        CURSOR(
            "Cursor",
            ".cursor/mcp.json",
            "~/.cursor/mcp.json",
            "https://docs.cursor.com/context/model-context-protocol"
        ),
        CONTINUE_DEV(
            "Continue.dev",
            "zest.json",
            "~/.continue/mcpServers/zest.json",
            "https://docs.continue.dev/customize/model-context-protocol"
        ),
        CLINE(
            "Cline (VS Code)",
            "cline_mcp_settings.json",
            "VS Code Settings → Cline → MCP Servers",
            "https://github.com/cline/cline#mcp-support"
        ),
        WINDSURF(
            "Windsurf",
            "mcp_config.json",
            "~/.codeium/windsurf/mcp_config.json (macOS/Linux)\n%APPDATA%\\Codeium\\Windsurf\\ (Windows)",
            "https://docs.windsurf.com/windsurf/cascade/mcp"
        ),
        CLAUDE_CODE(
            "Claude Code",
            "settings.json",
            "~/.claude/settings.json",
            "https://docs.anthropic.com/en/docs/claude-code/mcp"
        ),
        KILO_CODE(
            "Kilo Code (VS Code)",
            "mcp_settings.json",
            "~/.config/Code/User/globalStorage/kilocode.kilo-code/settings/ (Linux)\n~/Library/Application Support/Code/User/globalStorage/kilocode.kilo-code/settings/ (macOS)",
            "https://kilo.ai/docs/features/mcp/using-mcp-in-kilo-code"
        ),
        GEMINI_CLI(
            "Gemini CLI",
            "settings.json",
            "~/.gemini/settings.json",
            "https://geminicli.com/docs/tools/mcp-server/"
        ),
        QWEN_CODER(
            "Qwen Code",
            "settings.json",
            "~/.qwen/settings.json",
            "https://qwenlm.github.io/qwen-code-docs/en/users/features/mcp/"
        ),
        GENERIC(
            "Generic MCP Client",
            "mcp-config.json",
            "(varies by client)",
            "https://modelcontextprotocol.io"
        )
    }

    /**
     * Get the MCP server URL (Streamable HTTP transport).
     * Uses /mcp endpoint per MCP 2025-03-26 spec.
     */
    fun getServerUrl(port: Int = DEFAULT_PORT): String = "http://localhost:$port/mcp"

    /**
     * Generate JSON configuration for a specific client.
     */
    fun generateJsonConfig(client: ClientType, port: Int = DEFAULT_PORT, projectPath: String? = null): String {
        val url = getServerUrl(port)

        return when (client) {
            ClientType.CLAUDE_DESKTOP -> generateClaudeDesktopConfig(url)
            ClientType.CURSOR -> generateCursorConfig(url)
            ClientType.CONTINUE_DEV -> generateContinueDevConfig(url)
            ClientType.CLINE -> generateClineConfig(url)
            ClientType.WINDSURF -> generateWindsurfConfig(url)
            ClientType.CLAUDE_CODE -> generateClaudeCodeConfig(url)
            ClientType.KILO_CODE -> generateKiloCodeConfig(url)
            ClientType.GEMINI_CLI -> generateGeminiCliConfig(url)
            ClientType.QWEN_CODER -> generateQwenCoderConfig(url)
            ClientType.GENERIC -> generateGenericConfig(url)
        }
    }

    /**
     * Generate YAML configuration for clients that support it.
     */
    fun generateYamlConfig(port: Int = DEFAULT_PORT): String {
        val url = getServerUrl(port)
        return """
# Zest MCP Server Configuration (YAML)
# Copy this to your MCP client's config file
# Uses Streamable HTTP transport (MCP 2025-03-26 spec)

mcpServers:
  $SERVER_NAME:
    url: "$url"
    # Optional: Add environment variables if needed
    # env:
    #   INTELLIJ_PROJECT: "/path/to/project"
        """.trimIndent()
    }

    private fun generateClaudeDesktopConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateCursorConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateContinueDevConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateClineConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url",
      "disabled": false
    }
  }
}
    """.trimIndent()

    private fun generateWindsurfConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "serverUrl": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateClaudeCodeConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "type": "url",
      "url": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateKiloCodeConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url",
      "disabled": false
    }
  }
}
    """.trimIndent()

    private fun generateGeminiCliConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
    """.trimIndent()

    private fun generateQwenCoderConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "httpUrl": "$url",
      "timeout": 660000
    }
  }
}
    """.trimIndent()

    private fun generateGenericConfig(url: String): String = """
{
  "mcpServers": {
    "$SERVER_NAME": {
      "url": "$url"
    }
  }
}
    """.trimIndent()

    /**
     * Generate a complete setup guide with all available configurations.
     */
    fun generateFullSetupGuide(port: Int = DEFAULT_PORT): String {
        val url = getServerUrl(port)
        return buildString {
            appendLine("# Zest MCP Server Configuration Guide")
            appendLine()
            appendLine("Server URL: `$url`")
            appendLine("Transport: SSE (Server-Sent Events)")
            appendLine()
            appendLine("---")
            appendLine()

            for (client in ClientType.values()) {
                appendLine("## ${client.displayName}")
                appendLine()
                appendLine("**Config file:** `${client.configFileName}`")
                appendLine("**Location:** ${client.configPath}")
                appendLine("**Docs:** ${client.docsUrl}")
                appendLine()
                appendLine("```json")
                appendLine(generateJsonConfig(client, port))
                appendLine("```")
                appendLine()
            }

            appendLine("---")
            appendLine()
            appendLine("## YAML Format (Alternative)")
            appendLine()
            appendLine("```yaml")
            appendLine(generateYamlConfig(port))
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Available Tools")
            appendLine()
            appendLine("| Tool | Description |")
            appendLine("|------|-------------|")
            appendLine("| `getCurrentFile` | Get currently open file in editor |")
            appendLine("| `lookupClass` | Look up class/method signatures |")
            appendLine("| `getJavaCodeUnderTest` | Interactive class picker with analysis |")
            appendLine("| `showFile` | Open file in IntelliJ editor |")
            appendLine("| `findUsages` | Find all usages of a symbol |")
            appendLine("| `findImplementations` | Find interface implementations |")
            appendLine("| `getTypeHierarchy` | Get class hierarchy |")
            appendLine("| `getCallHierarchy` | Get callers/callees of a method |")
            appendLine("| `rename` | Rename symbol across project |")
            appendLine("| `getMethodBody` | Get method body for analysis |")
            appendLine()
            appendLine("## Available Prompts")
            appendLine()
            appendLine("| Prompt | Description |")
            appendLine("|--------|-------------|")
            appendLine("| `review` | Code quality review |")
            appendLine("| `explain` | Explain how code works |")
            appendLine("| `commit` | Git commit assistant |")
            appendLine("| `zest-test-context` | Gather test context |")
            appendLine("| `zest-test-plan` | Create test plan |")
            appendLine("| `zest-test-write` | Write tests |")
            appendLine("| `zest-test-fix` | Fix failing tests |")
            appendLine("| `zest-test-review` | Review test quality |")
            appendLine("| `zest-analyze-gaps` | Analyze test coverage gaps |")
        }
    }
}
