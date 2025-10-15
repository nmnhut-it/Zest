package com.zps.zest.pochi

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.terminal.TerminalExecutionConsole
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.MethodContextFormatter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Service for executing Pochi CLI and streaming results.
 * Uses Pochi's AI agent capabilities to rewrite code.
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
    }

    private val configManager = PochiConfigManager(project)

    /**
     * Find available pochi scripts (ps1, cmd, sh)
     * Returns map of shell type to script path
     */
    private fun findPochiScripts(): Map<String, String> {
        val scripts = mutableMapOf<String, String>()
        val os = System.getProperty("os.name")

        if (os.startsWith("Windows")) {
            val appData = System.getenv("APPDATA")
            if (appData != null) {
                val npmPath = "$appData\\npm"

                // Check for PowerShell script
                val ps1File = File("$npmPath\\pochi.ps1")
                if (ps1File.exists()) {
                    scripts["powershell"] = ps1File.absolutePath
                    LOG.info("Found pochi.ps1: ${ps1File.absolutePath}")
                }

                // Check for cmd script
                val cmdFile = File("$npmPath\\pochi.cmd")
                if (cmdFile.exists()) {
                    scripts["cmd"] = cmdFile.absolutePath
                    LOG.info("Found pochi.cmd: ${cmdFile.absolutePath}")
                }

                // Check for Unix script (for Git Bash)
                val shFile = File("$npmPath\\pochi")
                if (shFile.exists()) {
                    scripts["bash"] = shFile.absolutePath
                    LOG.info("Found pochi (sh): ${shFile.absolutePath}")
                }
            }
        }

        return scripts
    }

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
     * Builds the prompt for Pochi to rewrite code
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
     * Executes Pochi CLI using GeneralCommandLine (official IntelliJ API)
     */
    private fun executePochiCli(
        prompt: String,
        onChunk: (String) -> Unit,
        future: CompletableFuture<String>
    ) {
        Thread {
            try {
                val modelId = configManager.getPochiModelId()

                // Ensure ripgrep exists in npm directory
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val appData = System.getenv("APPDATA")
                    val npmPath = "$appData\\npm"
                    RipgrepPathHelper.extractRipgrepToNpmDirectory(npmPath)
                }

                // Find available pochi scripts
                val pochiScripts = findPochiScripts()

                // Build GeneralCommandLine based on available script
                val commandLine = when {
                    // Prefer cmd.exe with pochi.cmd (most reliable on Windows)
                    pochiScripts.containsKey("cmd") -> {
                        GeneralCommandLine(
                            "cmd.exe", "/c",
                            pochiScripts["cmd"],
                            "--model", modelId,
                            "--max-steps", MAX_STEPS.toString(),
                            "--max-retries", MAX_RETRIES.toString()
                        )
                    }
                    // PowerShell with pochi.ps1
                    pochiScripts.containsKey("powershell") -> {
                        GeneralCommandLine(
                            "powershell.exe", "-File",
                            pochiScripts["powershell"],
                            "-model", modelId,
                            "-max-steps", MAX_STEPS.toString(),
                            "-max-retries", MAX_RETRIES.toString()
                        )
                    }
                    // Fallback to direct execution
                    else -> {
                        GeneralCommandLine(
                            "pochi",
                            "--model", modelId,
                            "--max-steps", MAX_STEPS.toString(),
                            "--max-retries", MAX_RETRIES.toString()
                        )
                    }
                }

                // Set working directory
                commandLine.setWorkDirectory(project.basePath)

                // Set environment with full Windows PATH
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val fullPath = getFullWindowsPath()
                    if (fullPath.isNotEmpty()) {
                        commandLine.environment["Path"] = fullPath
                        commandLine.environment["PATH"] = fullPath
                        LOG.info("Set full PATH for Pochi (${fullPath.length} chars)")
                    }
                }

                LOG.info("Executing Pochi via GeneralCommandLine in: ${project.basePath}")
                LOG.info("Command: ${commandLine.commandLineString}")

                // Create ProcessHandler
                val processHandler = OSProcessHandler(commandLine)

                // Create TerminalExecutionConsole for visual display
                val console = TerminalExecutionConsole(project, processHandler)

                val outputBuffer = StringBuilder()

                // Listen to process output
                processHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        val text = event.text
                        outputBuffer.append(text)
                        onChunk(text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        LOG.info("Pochi terminated with exit code: ${event.exitCode}")
                        if (event.exitCode == 0) {
                            future.complete(outputBuffer.toString())
                        } else {
                            val error = "Pochi CLI failed (exit code ${event.exitCode}):\n${outputBuffer.toString().take(500)}"
                            future.completeExceptionally(Exception(error))
                        }
                    }
                })

                // Write prompt to stdin
                Thread {
                    try {
                        Thread.sleep(500) // Let process start
                        processHandler.processInput?.write(prompt.toByteArray(Charsets.UTF_8))
                        processHandler.processInput?.flush()
                        processHandler.processInput?.close()
                    } catch (e: Exception) {
                        LOG.warn("Failed to write prompt to stdin", e)
                    }
                }.start()

                // Start process
                processHandler.startNotify()

            } catch (e: Exception) {
                LOG.error("Error executing Pochi CLI", e)
                future.completeExceptionally(e)
            }
        }.start()
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
