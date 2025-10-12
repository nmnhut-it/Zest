package com.zps.zest.completion.metrics

import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Represents a single metrics event in the session log.
 * Contains all information about the event including payload and response.
 */
data class SessionLogEntry(
    val timestamp: Long,
    val relativeTimeMs: Long,
    val eventType: String,
    val completionId: String,
    val endpoint: String,
    val httpMethod: String,
    val jsonPayload: JsonObject,
    val responseCode: Int,
    val responseTimeMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
) {

    /**
     * Get formatted timestamp
     */
    fun getFormattedTimestamp(): String {
        val dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
        return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    }

    /**
     * Get relative time in human-readable format
     */
    fun getFormattedRelativeTime(): String {
        val seconds = relativeTimeMs / 1000
        val millis = relativeTimeMs % 1000
        val minutes = seconds / 60
        val secs = seconds % 60

        return when {
            minutes > 0 -> String.format("%02d:%02d.%03d", minutes, secs, millis)
            else -> String.format("00:%02d.%03d", secs, millis)
        }
    }

    /**
     * Get pretty-printed JSON payload
     */
    fun getPrettyJsonPayload(): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(jsonPayload)
    }

    /**
     * Get CURL command equivalent
     */
    fun getCurlCommand(authToken: String?): String {
        return buildString {
            appendLine("curl -X $httpMethod '$endpoint' \\")
            appendLine("  -H 'Content-Type: application/json' \\")
            appendLine("  -H 'Accept: application/json' \\")
            if (!authToken.isNullOrBlank()) {
                appendLine("  -H 'Authorization: Bearer $authToken' \\")
            }
            val jsonStr = jsonPayload.toString().replace("'", "\\'")
            append("  -d '$jsonStr'")
        }
    }

    /**
     * Get short summary line for table display
     */
    fun getSummaryLine(): String {
        val status = if (success) "✅ $responseCode" else "❌ $responseCode"
        return "${getFormattedTimestamp()} | $eventType | $completionId | $status | ${responseTimeMs}ms"
    }

    /**
     * Get detailed report format
     */
    fun getDetailedReport(authToken: String? = null): String {
        return buildString {
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("EVENT: ${eventType.uppercase()}")
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("Timestamp: ${getFormattedTimestamp()}")
            appendLine("Relative Time: ${getFormattedRelativeTime()}")
            appendLine("Completion ID: $completionId")
            appendLine("Endpoint: $httpMethod $endpoint")
            appendLine("Response: ${if (success) "✅" else "❌"} $responseCode ${if (success) "OK" else "FAILED"}")
            appendLine("Response Time: ${responseTimeMs}ms")
            if (errorMessage != null) {
                appendLine("Error: $errorMessage")
            }
            appendLine()
            appendLine("JSON Payload:")
            appendLine(getPrettyJsonPayload())
            appendLine()
            appendLine("CURL Command:")
            appendLine(getCurlCommand(authToken))
            appendLine("═══════════════════════════════════════════════════════")
        }
    }
}
