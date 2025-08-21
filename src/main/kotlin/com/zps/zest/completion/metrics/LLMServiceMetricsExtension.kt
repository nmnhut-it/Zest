package com.zps.zest.completion.metrics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.ConfigurationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
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
    actualModel: String,
    completionContent: String? = null,
    metadata: MetricMetadata,
    enumUsage: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val configStatus = getConfigStatus()
        if (!configStatus.isConfigured) {
            return@withContext false
        }
        
        // Determine the log name based on enumUsage
        val logName = when (enumUsage) {
            "INLINE_COMPLETION_LOGGING" -> "autocomplete"
            "CODE_HEALTH_LOGGING" -> "code_health"
            "BLOCK_REWRITE_LOGGING" -> "quick_action"
            else -> null // Use legacy endpoint
        }
        
        // Build URL based on log name
        val apiUrl = if (logName != null) {
            // Check if the original URL is internal (contains talk.zingplay)
            val originalUrl = configStatus.apiUrl ?: ""
            val isInternal = originalUrl.contains("talk.zingplay")
            
            val baseUrl = if (isInternal) {
                "https://zest-internal.zingplay.com"
            } else {
                "https://zest.zingplay.com"
            }
            "$baseUrl/$logName/$eventType"
        } else {
            // Use existing LiteLLM endpoint for other usage types
            configStatus.apiUrl ?: return@withContext false
        }
        
        val authToken = ConfigurationManager.getInstance(project).authToken
        
        // Build request body based on log type
        val requestBody = when (logName) {
            "autocomplete", "quick_action" -> {
                // New format for autocomplete and quick_action endpoints
                JsonObject().apply {
                    addProperty("event_type", eventType)
                    addProperty("completion_id", completionId)
                    addProperty("timestamp", System.currentTimeMillis())
                    addProperty("elapsed_ms", elapsed)
                    completionContent?.let { addProperty("completion_text", it) }
                    
                    // Add metadata as nested object
                    add("metadata", Gson().toJsonTree(metadata))
                }
            }
            "code_health" -> {
                // New format for code_health endpoint
                JsonObject().apply {
                    addProperty("event_type", eventType)
                    addProperty("timestamp", System.currentTimeMillis())
                    
                    // Add analysis data from metadata
                    if (metadata is CodeHealthMetadata) {
                        add("analysis_data", Gson().toJsonTree(metadata.analysisData))
                    } else {
                        add("analysis_data", Gson().toJsonTree(metadata))
                    }
                }
            }
            else -> {
                // Legacy format for LiteLLM
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
                
                JsonObject().apply {
                    addProperty("model", actualModel)
                    addProperty("stream", false)
                    addProperty("custom_tool", "Zest|$enumUsage|$eventType")
                    addProperty("completion_id", completionId)
                    add("messages", dummyMsg)
                    addProperty("elapsed", elapsed)
                    completionContent?.let { addProperty("completion_content", it) }
                    add("metadata", Gson().toJsonTree(metadata))
                }
            }
        }

        println("Metrics - RequestBody: " + requestBody);
        // Print curl command for debugging
        val curlCommand = buildString {
            append("curl -X POST '${apiUrl}' \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -H 'Accept: application/json' \\\n")
            authToken?.takeIf { it.isNotEmpty() }?.let {
                append("  -H 'Authorization: ${it}' \\\n")
            }
            append("  -d '")
            // Pretty print JSON for readability
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            append(gson.toJson(requestBody).replace("'", "\\'"))
            append("'")
        }
//        println("[ZestMetrics] Sending metric request to: $apiUrl")
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
            
//            println("[ZestMetrics] Response code: $responseCode (success: $success)")
            
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
//        println("[ZestMetrics] Error sending metrics: ${e.message}")
        e.printStackTrace()
        // Silently fail - metrics are non-critical
        false
    }
}

/**
 * Convenience method to send a metric event
 */
suspend fun LLMService.sendMetricEvent(event: MetricEvent, enumUsage: String): Boolean {
    // Extract completion content from events that have it
    val completionContent = when (event) {
        is MetricEvent.InlineSelect -> event.completionContent
        is MetricEvent.InlineCompletionResponse -> event.completionContent
        is MetricEvent.QuickActionSelect -> event.completionContent
        is MetricEvent.QuickActionResponse -> event.completionContent
        else -> null
    }
    
    // Extract metadata based on event type
    val metadata: MetricMetadata = when (event) {
        is MetricEvent.InlineCompletionRequest -> event.metadata
        is MetricEvent.InlineCompletionResponse -> event.metadata
        is MetricEvent.InlineView -> event.metadata
        is MetricEvent.InlineSelect -> event.metadata
        is MetricEvent.InlineDecline -> event.metadata
        is MetricEvent.InlineDismiss -> event.metadata
        is MetricEvent.QuickActionRequest -> event.metadata
        is MetricEvent.QuickActionResponse -> event.metadata
        is MetricEvent.QuickActionView -> event.metadata
        is MetricEvent.QuickActionSelect -> event.metadata
        is MetricEvent.QuickActionDecline -> event.metadata
        is MetricEvent.QuickActionDismiss -> event.metadata
        is MetricEvent.CodeHealthEvent -> event.metadata
        is MetricEvent.Custom -> event.metadata
    }
    
    return sendInlineCompletionMetrics(
        eventType = event.eventType,
        completionId = event.completionId,
        elapsed = event.elapsed,
        actualModel = event.actualModel,
        completionContent = completionContent,
        metadata = metadata,
        enumUsage = enumUsage
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
