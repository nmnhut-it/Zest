package com.zps.zest.completion.metrics

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.ConfigurationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extension functions for LLMService to support metrics API calls
 */

/**
 * Send inline completion metrics to the API
 * This is a fire-and-forget operation that doesn't expect a response
 */
suspend fun LLMService.sendInlineCompletionMetrics(
    eventType: String,
    completionId: String,
    elapsed: Long,
    completionContent: String? = null,
    metadata: Map<String, Any>? = null
): Boolean = withContext(Dispatchers.IO) {
    try {
        val configStatus = getConfigStatus()
        if (!configStatus.isConfigured) {
            return@withContext false
        }
        
        val apiUrl = configStatus.apiUrl ?: return@withContext false
        val authToken = ConfigurationManager.getInstance(project).authToken
        val dummyMsg = JsonParser.parseString(
            """
                [
        {
          "role": "user",
          "content": "dummy"
        }
      ]
            """.trimIndent()
        )

        // Build minimal request body
        val requestBody = JsonObject().apply {
            addProperty("model", "local-model-mini")
            addProperty("stream", false)
            addProperty("custom_tool", "Zest|INLINE_COMPLETION_LOGGING|$eventType")
            addProperty("completion_id", completionId)
            add("messages", dummyMsg)
            addProperty("elapsed", elapsed)
            
            // Add optional fields
            completionContent?.let { addProperty("completion_content", it) }
            
            // Add metadata as nested object if present
            metadata?.takeIf { it.isNotEmpty() }?.let { meta ->
                add("metadata", Gson().toJsonTree(meta))
            }
        }
        
        // Print curl command for debugging
        val curlCommand = buildString {
            append("curl -X POST '${apiUrl}' \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -H 'Accept: application/json' \\\n")
            authToken?.takeIf { it.isNotEmpty() }?.let {
                append("  -H 'Authorization: Bearer ${it}' \\\n")
            }
            append("  -d '")
            // Pretty print JSON for readability
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            append(gson.toJson(requestBody).replace("'", "\\'"))
            append("'")
        }
//        println("[ZestMetrics] Sending metric request:")
//        println(curlCommand)
//        println()
        
        // Send HTTP request
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                
                // Add auth token if present
                authToken?.takeIf { it.isNotEmpty() }?.let { 
                    setRequestProperty("Authorization", "Bearer $it")
                }
                
                connectTimeout = 5000 // 5 seconds for metrics
                readTimeout = 5000
                doOutput = true
            }
            
            // Write request body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }
            
            // Check response code and read response body
            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            
            println("[ZestMetrics] Response code: $responseCode (success: $success)")
            
            // Read response body - use errorStream for non-2xx codes
            val responseBody = try {
                val inputStream = if (success) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                
                inputStream?.bufferedReader()?.use { reader ->
                    reader.readText()
                } ?: "No response body"
            } catch (e: Exception) {
                "Error reading response: ${e.message}"
            }
            
            println("[ZestMetrics] Response body:")
            println(responseBody)
            println()
            
            success
        } finally {
            connection.disconnect()
        }
        
    } catch (e: Exception) {
        // Log the error for debugging
        println("[ZestMetrics] Error sending metrics: ${e.message}")
        e.printStackTrace()
        // Silently fail - metrics are non-critical
        false
    }
}

/**
 * Convenience method to send a metric event
 */
suspend fun LLMService.sendMetricEvent(event: MetricEvent): Boolean {
    val metadata = when (event) {
        is MetricEvent.Completed -> event.metadata
        else -> event.metadata
    }
    
    val completionContent = when (event) {
        is MetricEvent.Completed -> event.completionContent
        else -> null
    }
    
    return sendInlineCompletionMetrics(
        eventType = event.eventType,
        completionId = event.completionId,
        elapsed = event.elapsed,
        completionContent = completionContent,
        metadata = metadata.takeIf { it.isNotEmpty() }
    )
}

/**
 * Extension property to get the project from LLMService
 * Uses reflection to access the private project field
 */
private val LLMService.project: com.intellij.openapi.project.Project
    get() = this.javaClass.getDeclaredField("project").apply {
        isAccessible = true
    }.get(this) as com.intellij.openapi.project.Project
