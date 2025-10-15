package com.zps.zest.pochi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Manages Pochi CLI configuration by generating config.jsonc from Zest settings.
 * Maps Zest's OpenAI-compatible API settings to Pochi's provider format.
 *
 * Config location: ~/.pochi/config.jsonc
 */
class PochiConfigManager(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(PochiConfigManager::class.java)
        private const val POCHI_FOLDER = ".pochi"
        private const val CONFIG_FILE = "config.jsonc"
        private const val PROVIDER_ID = "zest"
    }

    /**
     * Generates Pochi config from current Zest settings and writes to ~/.pochi/config.jsonc
     * @return true if config was successfully written
     */
    fun ensurePochiConfigExists(): Boolean {
        try {
            val config = ConfigurationManager.getInstance(project)
            val apiUrl = config.apiUrl
            val apiKey = config.authTokenNoPrompt ?: ""
            val model = config.codeModel ?: ConfigurationManager.CODE_EXPERT

            // Get user home directory
            val userHome = System.getProperty("user.home")
            val pochiDir = Paths.get(userHome, POCHI_FOLDER)
            val configPath = pochiDir.resolve(CONFIG_FILE)

            // Create .pochi directory if it doesn't exist
            if (!Files.exists(pochiDir)) {
                Files.createDirectories(pochiDir)
                LOG.info("Created Pochi directory at: $pochiDir")
            }

            // Extract base URL (remove /api/chat/completions if present)
            val baseURL = extractBaseUrl(apiUrl)

            // Generate config content
            val configContent = generateConfigContent(baseURL, apiKey, model)

            // Write config file
            Files.writeString(configPath, configContent)
            LOG.info("Generated Pochi config at: $configPath")

            return true
        } catch (e: Exception) {
            LOG.error("Failed to generate Pochi config", e)
            return false
        }
    }

    /**
     * Extracts base URL from full API URL
     * Example: https://chat.zingplay.com/api/chat/completions -> https://chat.zingplay.com/api/v1
     */
    private fun extractBaseUrl(apiUrl: String): String {
        return when {
            // Zingplay endpoints need /api preserved
            apiUrl.contains("zingplay.com") && apiUrl.contains("/api/chat/completions") -> {
                apiUrl.replace("/chat/completions", "/v1")
                // Result: https://chat.zingplay.com/api/v1
            }
            // Other OpenAI-compatible endpoints
            apiUrl.contains("/api/chat/completions") -> {
                apiUrl.replace("/api/chat/completions", "/v1")
            }
            apiUrl.endsWith("/v1") -> apiUrl
            else -> "$apiUrl/v1"
        }
    }

    /**
     * Generates Pochi config content in JSONC format
     */
    private fun generateConfigContent(baseURL: String, apiKey: String, model: String): String {
        return """
{
  "${'$'}schema": "https://getpochi.com/config.schema.json",
  "providers": {
    "$PROVIDER_ID": {
      "apiKey": "$apiKey",
      "baseURL": "$baseURL",
      "models": {
        "$model": {
          "contextWindow": 131072,
          "maxTokens": 8000
        }
      }
    }
  }
}
""".trimIndent()
    }

    /**
     * Gets the model identifier to use with Pochi CLI
     * Format: zest/model-name
     */
    fun getPochiModelId(): String {
        val config = ConfigurationManager.getInstance(project)
        val model = config.codeModel ?: ConfigurationManager.CODE_EXPERT
        return "$PROVIDER_ID/$model"
    }

    /**
     * Checks if Pochi config exists
     */
    fun isPochiConfigured(): Boolean {
        val userHome = System.getProperty("user.home")
        val configPath = Paths.get(userHome, POCHI_FOLDER, CONFIG_FILE)
        return Files.exists(configPath)
    }
}
