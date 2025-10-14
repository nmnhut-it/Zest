package com.zps.zest.pochi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Helper to find and extract bundled ripgrep binary for Pochi CLI.
 * Reuses ripgrep bundled in RipgrepCodeTool for MCP tools.
 */
object RipgrepPathHelper {
    private val LOG = Logger.getInstance(RipgrepPathHelper::class.java)
    private var cachedRipgrepDir: String? = null
    private var searchCompleted = false

    /**
     * Find directory containing ripgrep binary.
     * Returns null if ripgrep cannot be found or extracted.
     */
    fun findRipgrepDirectory(project: Project): String? {
        if (searchCompleted) {
            return cachedRipgrepDir
        }

        searchCompleted = true

        try {
            // Try to extract bundled ripgrep
            val rgPath = extractBundledRipgrep()
            if (rgPath != null) {
                // Return directory containing the binary
                val dir = File(rgPath).parentFile?.absolutePath
                LOG.info("Found ripgrep directory: $dir")
                cachedRipgrepDir = dir
                return dir
            }

            // Try system ripgrep
            if (isRipgrepInPath()) {
                LOG.info("Ripgrep found in system PATH")
                return null // Already in PATH, no need to add
            }

        } catch (e: Exception) {
            LOG.warn("Failed to find ripgrep", e)
        }

        LOG.warn("Ripgrep not available for Pochi")
        return null
    }

    /**
     * Extract bundled ripgrep binary - installs next to pochi.cmd for easy access
     */
    private fun extractBundledRipgrep(): String? {
        try {
            val platform = detectPlatform()
            val resourcePath = "/bin/rg-$platform${if (platform.contains("windows")) ".exe" else ""}"

            LOG.info("Looking for bundled ripgrep at: $resourcePath")

            // Check if bundled binary exists
            val bundledBinary: InputStream? = RipgrepPathHelper::class.java.getResourceAsStream(resourcePath)
            if (bundledBinary == null) {
                LOG.info("No bundled ripgrep found at: $resourcePath")
                return null
            }

            // Strategy 1: Install next to pochi.cmd (best - Pochi finds it automatically)
            val pochiDir = findPochiDirectory()
            if (pochiDir != null) {
                val binaryName = if (platform.contains("windows")) "rg.exe" else "rg"
                val rgPath = pochiDir.resolve(binaryName)

                // Check if already exists
                if (Files.exists(rgPath) && isExecutableValid(rgPath.toString())) {
                    LOG.info("Found existing ripgrep next to pochi: $rgPath")
                    bundledBinary.close()
                    return rgPath.toString()
                }

                // Extract to pochi's directory
                try {
                    LOG.info("Installing ripgrep next to pochi: $rgPath")
                    Files.copy(bundledBinary, rgPath, StandardCopyOption.REPLACE_EXISTING)
                    bundledBinary.close()

                    // Make executable on Unix
                    if (!platform.contains("windows")) {
                        Files.setPosixFilePermissions(
                            rgPath,
                            setOf(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE
                            )
                        )
                    }

                    // Verify it works
                    if (isExecutableValid(rgPath.toString())) {
                        LOG.info("Successfully installed ripgrep next to pochi: $rgPath")
                        return rgPath.toString()
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to install ripgrep next to pochi, will try AppData fallback", e)
                }
            }

            // Strategy 2: Fallback - install to AppData (if pochi directory not found)
            val appDataDir = getAppDataDirectory()
            val binaryName = if (platform.contains("windows")) "rg.exe" else "rg"
            val extractedPath = appDataDir.resolve(binaryName)

            // Re-read bundled binary for fallback attempt
            val fallbackBinary: InputStream? = RipgrepPathHelper::class.java.getResourceAsStream(resourcePath)
            if (fallbackBinary == null) {
                return null
            }

            LOG.info("Extracting ripgrep to AppData: $extractedPath")
            Files.copy(fallbackBinary, extractedPath, StandardCopyOption.REPLACE_EXISTING)
            fallbackBinary.close()

            // Make executable on Unix
            if (!platform.contains("windows")) {
                Files.setPosixFilePermissions(
                    extractedPath,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                )
            }

            // Verify it works
            if (isExecutableValid(extractedPath.toString())) {
                LOG.info("Successfully extracted ripgrep to AppData: $extractedPath")
                return extractedPath.toString()
            }

        } catch (e: Exception) {
            LOG.warn("Failed to extract ripgrep", e)
        }

        return null
    }

    /**
     * Find directory where pochi.cmd is installed (usually %APPDATA%\npm)
     */
    private fun findPochiDirectory(): Path? {
        try {
            val os = System.getProperty("os.name").lowercase()

            if (os.contains("windows")) {
                // Try common npm global location
                val appData = System.getenv("APPDATA")
                if (appData != null) {
                    val npmDir = Paths.get(appData, "npm")
                    val pochiCmd = npmDir.resolve("pochi.cmd")
                    if (Files.exists(pochiCmd)) {
                        LOG.info("Found pochi directory: $npmDir")
                        return npmDir
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to find pochi directory", e)
        }

        return null
    }

    /**
     * Get persistent AppData directory for storing ripgrep binary
     */
    private fun getAppDataDirectory(): Path {
        val os = System.getProperty("os.name").lowercase()
        val appDataPath: Path = when {
            os.contains("windows") -> {
                val appData = System.getenv("APPDATA")
                if (appData != null) {
                    Paths.get(appData, "Zest", "tools")
                } else {
                    Paths.get(System.getProperty("user.home"), ".zest", "tools")
                }
            }
            os.contains("mac") -> {
                Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Zest", "tools")
            }
            else -> {
                val xdgDataHome = System.getenv("XDG_DATA_HOME")
                if (xdgDataHome != null) {
                    Paths.get(xdgDataHome, "zest", "tools")
                } else {
                    Paths.get(System.getProperty("user.home"), ".local", "share", "zest", "tools")
                }
            }
        }

        Files.createDirectories(appDataPath)
        return appDataPath
    }

    /**
     * Detect platform for binary selection
     */
    private fun detectPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        return when {
            os.contains("windows") -> "windows-x64"
            os.contains("mac") || os.contains("darwin") -> {
                if (arch.contains("aarch64")) "macos-arm64" else "macos-x64"
            }
            os.contains("linux") -> "linux-x64"
            else -> "unknown"
        }
    }

    /**
     * Check if ripgrep is available in system PATH
     */
    private fun isRipgrepInPath(): Boolean {
        return isCommandAvailable("rg") || isCommandAvailable("rg.exe")
    }

    /**
     * Check if executable is valid by running --version
     */
    private fun isExecutableValid(path: String): Boolean {
        return isCommandAvailable(path)
    }

    /**
     * Check if command is available and works
     */
    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
