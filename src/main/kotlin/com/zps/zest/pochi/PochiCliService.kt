package com.zps.zest.pochi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.MethodContextFormatter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
     * Find pochi command - returns full path on Windows to avoid PATH issues
     */
    private fun findPochiCommand(): String {
        val os = System.getProperty("os.name")

        if (os.startsWith("Windows")) {
            // Try common npm global locations
            val appData = System.getenv("APPDATA")
            if (appData != null) {
                val pochiCmd = File("$appData\\npm\\pochi.cmd")
                if (pochiCmd.exists()) {
                    LOG.info("Found pochi at: ${pochiCmd.absolutePath}")
                    return pochiCmd.absolutePath
                }
            }

            // Fallback: try to find via PowerShell
            val psPath = findPochiViaPowerShell()
            if (psPath != null) {
                LOG.info("Found pochi via PowerShell: $psPath")
                return psPath
            }

            LOG.warn("Could not find pochi.cmd, using 'pochi' and hoping it's in PATH")
        }

        return POCHI_COMMAND
    }

    /**
     * Find pochi using PowerShell's Get-Command
     */
    private fun findPochiViaPowerShell(): String? {
        return try {
            val process = ProcessBuilder(
                "powershell.exe", "-Command",
                "(Get-Command pochi).Source"
            ).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(5, TimeUnit.SECONDS)

            if (process.exitValue() == 0 && output.isNotEmpty()) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to find pochi via PowerShell", e)
            null
        }
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
     * Executes Pochi CLI with streaming output using stdin to avoid shell escaping issues
     */
    private fun executePochiCli(
        prompt: String,
        onChunk: (String) -> Unit,
        future: CompletableFuture<String>
    ) {
        Thread {
            try {
                val modelId = configManager.getPochiModelId()
                val pochiCmd = findPochiCommand()

                // Build command without prompt - we'll pipe prompt via stdin
                // This avoids all shell escaping issues
                // Use full path to pochi on Windows to avoid PATH issues
                val command = when {
                    System.getProperty("os.name").startsWith("Windows") -> {
                        listOf(
                            "powershell.exe", "-Command",
                            "$pochiCmd --model $modelId --max-steps $MAX_STEPS --max-retries $MAX_RETRIES"
                        )
                    }
                    else -> {
                        listOf(
                            pochiCmd,
                            "--model", modelId,
                            "--max-steps", MAX_STEPS.toString(),
                            "--max-retries", MAX_RETRIES.toString()
                        )
                    }
                }

                LOG.info("Executing Pochi CLI: ${command.joinToString(" ")}")
                LOG.info("Prompt will be sent via stdin (${prompt.length} characters)")

                // Start process with full PATH
                val processBuilder = ProcessBuilder(command)

                // On Windows: Set full PATH (System + User) so Pochi has access to Node.js, npm, etc.
                // Also ensure ripgrep is installed next to pochi.cmd
                if (System.getProperty("os.name").startsWith("Windows")) {
                    // Install ripgrep next to pochi.cmd (so Pochi can find it)
                    RipgrepPathHelper.findRipgrepDirectory(project)

                    val env = processBuilder.environment()
                    val fullPath = getFullWindowsPath()

                    if (fullPath.isNotEmpty()) {
                        env["PATH"] = fullPath
                        LOG.info("Set full Windows PATH for Pochi (includes Node.js, npm, ripgrep)")
                    }
                }

                processBuilder.redirectErrorStream(true) // Merge stderr into stdout
                val process = processBuilder.start()

                // Write prompt to stdin in a separate thread
                Thread {
                    try {
                        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(prompt)
                            writer.flush()
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to write prompt to stdin", e)
                    }
                }.start()

                // Read and stream output
                val output = StringBuilder()
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            output.append(it).append("\n")
                            onChunk(it + "\n")
                        }
                    }
                }

                // Wait for process to complete
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    future.complete(output.toString())
                } else {
                    val errorOutput = output.toString()
                    LOG.warn("Pochi CLI exited with code $exitCode. Output:\n$errorOutput")
                    val errorMsg = "Pochi CLI failed (exit code $exitCode):\n${errorOutput.take(500)}"
                    future.completeExceptionally(Exception(errorMsg))
                }

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
