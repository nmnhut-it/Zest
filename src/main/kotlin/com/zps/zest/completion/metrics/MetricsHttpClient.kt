package com.zps.zest.completion.metrics

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dedicated HTTP client for sending metrics.
 * Single responsibility: HTTP communication only.
 * No dependency on LLMService - fully decoupled.
 */
class MetricsHttpClient(private val project: Project) {
    private val logger = Logger.getInstance(MetricsHttpClient::class.java)
    private val sessionLogger by lazy {
        MetricsSessionLogger.getInstance(project)
    }

    /**
     * Send a metric to the remote server
     */
    suspend fun sendMetric(
        endpoint: MetricsEndpoint,
        eventType: String,
        body: JsonObject
    ): Boolean = withContext(Dispatchers.IO) {
        val requestStartTime = System.currentTimeMillis()
        val completionId = body.get("completion_id")?.asString ?: "unknown"

        try {
            val config = ConfigurationManager.getInstance(project)
            val baseUrl = determineBaseUrl(config.apiUrl ?: "")
            val url = endpoint.buildUrl(baseUrl, eventType)
            val authToken = config.authToken

            logger.debug("Sending metric to: $url")

            val responseCode = sendHttpRequest(url, authToken, body)
            val responseTimeMs = System.currentTimeMillis() - requestStartTime
            val success = responseCode in 200..299

            // Log to session
            sessionLogger.logEvent(SessionLogEntry(
                timestamp = requestStartTime,
                relativeTimeMs = requestStartTime - sessionLogger.sessionStartTime,
                eventType = eventType,
                completionId = completionId,
                endpoint = url,
                httpMethod = "POST",
                jsonPayload = body,
                responseCode = responseCode,
                responseTimeMs = responseTimeMs,
                success = success
            ))

            success  // Return value (last expression)
        } catch (e: Exception) {
            val responseTimeMs = System.currentTimeMillis() - requestStartTime
            logger.warn("Failed to send metric: ${e.message}", e)

            // Log failure to session
            val config = ConfigurationManager.getInstance(project)
            val baseUrl = determineBaseUrl(config.apiUrl ?: "")
            val url = endpoint.buildUrl(baseUrl, eventType)

            sessionLogger.logEvent(SessionLogEntry(
                timestamp = requestStartTime,
                relativeTimeMs = requestStartTime - sessionLogger.sessionStartTime,
                eventType = eventType,
                completionId = completionId,
                endpoint = url,
                httpMethod = "POST",
                jsonPayload = body,
                responseCode = 0,
                responseTimeMs = responseTimeMs,
                success = false,
                errorMessage = e.message
            ))

            false  // Return value (last expression)
        }
    }

    /**
     * Determine base URL based on API configuration
     */
    private fun determineBaseUrl(apiUrl: String): String {
        return if (apiUrl.contains("talk.zingplay")) {
            "https://zest-internal.zingplay.com"
        } else {
            "https://zest.zingplay.com"
        }
    }

    /**
     * Send HTTP POST request with JSON body
     * Returns HTTP response code
     */
    private fun sendHttpRequest(
        urlString: String,
        authToken: String?,
        body: JsonObject
    ): Int {
        val connection = URL(urlString).openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")

                // Add auth token if present
                authToken?.takeIf { it.isNotEmpty() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }

                connectTimeout = 5000  // 5 seconds
                readTimeout = 5000
                doOutput = true
            }

            // Write request body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            // Get response code
            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                logger.debug("Metric HTTP response code: $responseCode")

                // Read error response for debugging
                try {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    if (errorBody != null) {
                        logger.debug("Metric error response: $errorBody")
                    }
                } catch (e: Exception) {
                    // Ignore error reading error response
                }
            }

            return responseCode
        } finally {
            connection.disconnect()
        }
    }
}
