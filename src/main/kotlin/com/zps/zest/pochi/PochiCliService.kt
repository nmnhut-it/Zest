package com.zps.zest.pochi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.MethodContextFormatter
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Service for executing AI CLI tools (Claude Code or Pochi) in IntelliJ terminal.
 * Supports switching between Claude Code and Pochi for code analysis and rewriting.
 * Executes in actual terminal window for best rendering and user experience.
 */
class PochiCliService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(PochiCliService::class.java)
        private const val POCHI_COMMAND = "pochi"
        private const val MAX_STEPS = 24
        private const val MAX_RETRIES = 3

        // Cached full Windows PATH (System + User)
        private var cachedFullPath: String? = null
        private var pathInitialized = false

        // AI Tool selection
        const val TOOL_POCHI = "pochi"
        const val TOOL_CLAUDE = "claude"
    }

    // Current tool (can be set via configuration later)
    private var currentTool = TOOL_CLAUDE  // Default to Claude Code

    private val configManager = PochiConfigManager(project)

    /**
     * Set which AI tool to use (claude or pochi)
     */
    fun setTool(tool: String) {
        currentTool = tool
        LOG.info("Switched AI tool to: $tool")
    }

    /**
     * Find available CLI tool scripts (ps1, cmd, sh)
     * Returns map of shell type to script path
     */
    private fun findCliScripts(toolName: String): Map<String, String> {
        val scripts = mutableMapOf<String, String>()
        val os = System.getProperty("os.name")

        if (os.startsWith("Windows")) {
            val appData = System.getenv("APPDATA")
            if (appData != null) {
                val npmPath = "$appData\\npm"

                // Check for PowerShell script
                val ps1File = File("$npmPath\\$toolName.ps1")
                if (ps1File.exists()) {
                    scripts["powershell"] = ps1File.absolutePath
                    LOG.info("Found $toolName.ps1: ${ps1File.absolutePath}")
                }

                // Check for cmd script
                val cmdFile = File("$npmPath\\$toolName.cmd")
                if (cmdFile.exists()) {
                    scripts["cmd"] = cmdFile.absolutePath
                    LOG.info("Found $toolName.cmd: ${cmdFile.absolutePath}")
                }

                // Check for Unix script (for Git Bash)
                val shFile = File("$npmPath\\$toolName")
                if (shFile.exists()) {
                    scripts["bash"] = shFile.absolutePath
                    LOG.info("Found $toolName (sh): ${shFile.absolutePath}")
                }
            }
        }

        return scripts
    }

    /**
     * Find available pochi scripts (ps1, cmd, sh)
     */
    private fun findPochiScripts() = findCliScripts("pochi")

    /**
     * Find available claude scripts (ps1, cmd, sh)
     */
    private fun findClaudeScripts() = findCliScripts("claude")

    /**
     * Get full Windows PATH (System + User) - cached for performance
     */
    private fun getFullWindowsPath(): String {
        if (pathInitialized) {
            return cachedFullPath ?: ""
        }

        pathInitialized = true

        try {
            LOG.info("Querying Windows for full PATH (System + User)...")
            val process = ProcessBuilder(
                "powershell.exe", "-Command",
                "[Environment]::GetEnvironmentVariable('PATH','Machine') + ';' + [Environment]::GetEnvironmentVariable('PATH','User')"
            ).start()

            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(5, TimeUnit.SECONDS)

            if (process.exitValue() == 0 && path.isNotEmpty()) {
                cachedFullPath = path
                LOG.info("Cached full Windows PATH (${path.length} characters)")
                return path
            } else {
                LOG.warn("Failed to get full PATH, exit code: ${process.exitValue()}")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to get full Windows PATH", e)
        }

        return ""
    }

    /**
     * Execute code rewrite using Pochi CLI
     * @param methodContext The method context to rewrite
     * @param instruction The user's instruction
     * @param onChunk Callback for streaming output chunks
     * @return CompletableFuture that completes when Pochi finishes
     */
    fun executeRewrite(
        methodContext: MethodContext,
        instruction: String,
        onChunk: (String) -> Unit
    ): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        try {
            // Ensure Pochi config exists
            if (!configManager.ensurePochiConfigExists()) {
                future.completeExceptionally(Exception("Failed to configure Pochi. Check your Zest settings."))
                return future
            }

            // Build prompt
            val prompt = buildRewritePrompt(methodContext, instruction)

            // Execute Pochi CLI
            executePochiCli(prompt, onChunk, future)

        } catch (e: Exception) {
            LOG.error("Failed to execute Pochi CLI", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * Builds the prompt for AI tools to rewrite code
     */
    private fun buildRewritePrompt(methodContext: MethodContext, instruction: String): String {
        // Use existing MethodContextFormatter to format the context
        val formattedContext = MethodContextFormatter.format(methodContext, instruction)

        return """
$formattedContext

Please analyze the code and apply the requested changes. You have access to tools like readFile, searchCode, findFiles, etc. to understand the codebase better.

Return the complete rewritten code in a code block.
""".trimIndent()
    }

    /**
     * Executes AI CLI tool in actual IntelliJ terminal window
     */
    private fun executePochiCli(
        prompt: String,
        onChunk: (String) -> Unit,
        future: CompletableFuture<String>
    ) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            try {
                val modelId = configManager.getPochiModelId()

                // Ensure ripgrep exists in npm directory
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val appData = System.getenv("APPDATA")
                    val npmPath = "$appData\\npm"
                    RipgrepPathHelper.extractRipgrepToNpmDirectory(npmPath)
                }

                // Write prompt to temp file (avoids escaping issues)
                val tempFile = Files.createTempFile("ai-prompt-", ".txt")
                Files.writeString(tempFile, prompt)
                LOG.info("Wrote prompt to temp file: $tempFile (${prompt.length} chars)")

                // Get terminal manager
                val terminalManager = TerminalToolWindowManager.getInstance(project)

                // Create terminal tab with appropriate name
                val toolDisplayName = if (currentTool == TOOL_CLAUDE) "Claude Code" else "Pochi"
                @Suppress("DEPRECATION")
                val widget = terminalManager.createLocalShellWidget(
                    project.basePath,
                    toolDisplayName
                )

                // Show Terminal window
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                toolWindow?.show()

                LOG.info("Created terminal tab: $toolDisplayName")

                // Build command to execute AI tool with temp file input
                val command = buildTerminalCommand(tempFile, modelId)
                LOG.info("Executing in terminal: $command")

                // Execute in terminal (user sees everything!)
                widget.executeCommand(command)

                // Clean up temp file after delay
                Thread {
                    Thread.sleep(120000) // 2 minutes - enough time for AI to read it
                    try {
                        Files.deleteIfExists(tempFile)
                        LOG.info("Cleaned up temp file: $tempFile")
                    } catch (e: Exception) {
                        LOG.warn("Failed to delete temp file", e)
                    }
                }.start()

                // Complete immediately - output visible in terminal
                future.complete("$toolDisplayName running in terminal tab")

            } catch (e: Exception) {
                LOG.error("Error executing AI CLI in terminal", e)
                future.completeExceptionally(e)
            }
        }
    }

    /**
     * Build terminal command to execute AI tool with prompt from temp file
     * Uses PowerShell syntax (IntelliJ terminal default on Windows)
     */
    private fun buildTerminalCommand(tempFile: java.nio.file.Path, modelId: String): String {
        val tempFilePath = tempFile.toString()

        return when (currentTool) {
            TOOL_CLAUDE -> {
                // Claude Code - simple execution with stdin
                val claudeScripts = findClaudeScripts()
                when {
                    claudeScripts.containsKey("powershell") -> {
                        // Use PowerShell script if available
                        "Get-Content '$tempFilePath' -Raw | & '${claudeScripts["powershell"]}'"
                    }
                    claudeScripts.containsKey("cmd") -> {
                        // Execute .cmd file using PowerShell call operator
                        "Get-Content '$tempFilePath' | & '${claudeScripts["cmd"]}'"
                    }
                    else -> {
                        "Get-Content '$tempFilePath' | claude"
                    }
                }
            }
            TOOL_POCHI -> {
                // Pochi - with model and parameters
                val pochiScripts = findPochiScripts()
                when {
                    pochiScripts.containsKey("powershell") -> {
                        // Use PowerShell script
                        "Get-Content '$tempFilePath' -Raw | & '${pochiScripts["powershell"]}' -model $modelId -max-steps $MAX_STEPS -max-retries $MAX_RETRIES"
                    }
                    pochiScripts.containsKey("cmd") -> {
                        // Execute .cmd file using PowerShell call operator &
                        "Get-Content '$tempFilePath' | & '${pochiScripts["cmd"]}' --model $modelId --max-steps $MAX_STEPS --max-retries $MAX_RETRIES"
                    }
                    else -> {
                        "Get-Content '$tempFilePath' | pochi --model $modelId --max-steps $MAX_STEPS --max-retries $MAX_RETRIES"
                    }
                }
            }
            else -> "Write-Host 'Unknown AI tool: $currentTool'"
        }
    }

    /**
     * Simple query execution (non-streaming)
     */
    fun executeQuery(prompt: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        try {
            if (!configManager.ensurePochiConfigExists()) {
                future.completeExceptionally(Exception("Failed to configure Pochi"))
                return future
            }

            executePochiCli(prompt, {}, future)

        } catch (e: Exception) {
            LOG.error("Failed to execute Pochi query", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * Check if Pochi CLI is available
     */
    fun isPochiAvailable(): Boolean {
        return try {
            val command = when {
                System.getProperty("os.name").startsWith("Windows") -> {
                    listOf("powershell.exe", "-Command", "$POCHI_COMMAND --version")
                }
                else -> {
                    listOf("sh", "-c", "$POCHI_COMMAND --version")
                }
            }
            val process = ProcessBuilder(command).start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            LOG.warn("Pochi CLI not available: ${e.message}")
            false
        }
    }
}
