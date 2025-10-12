package com.zps.zest.completion.metrics

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Service for logging complete metrics session.
 * Tracks all metrics events with full details for debugging and analysis.
 */
@Service(Service.Level.PROJECT)
class MetricsSessionLogger(private val project: Project) {
    private val logger = Logger.getInstance(MetricsSessionLogger::class.java)

    private val sessionEvents = ConcurrentLinkedDeque<SessionLogEntry>()
    val sessionStartTime = System.currentTimeMillis()
    private val maxEventsInMemory = 1000

    /**
     * Log a metrics event to the session
     */
    fun logEvent(entry: SessionLogEntry) {
        sessionEvents.addFirst(entry)

        // Trim to max size (keep most recent)
        while (sessionEvents.size > maxEventsInMemory) {
            sessionEvents.removeLast()
        }

        logger.debug("Session log: ${entry.eventType} for ${entry.completionId} -> ${entry.responseCode}")
    }

    /**
     * Get all logged events (newest first)
     */
    fun getAllEvents(): List<SessionLogEntry> {
        return sessionEvents.toList()
    }

    /**
     * Get events by type
     */
    fun getEventsByType(eventType: String): List<SessionLogEntry> {
        return sessionEvents.filter { it.eventType == eventType }
    }

    /**
     * Get events by completion ID
     */
    fun getEventsByCompletionId(completionId: String): List<SessionLogEntry> {
        return sessionEvents.filter { it.completionId == completionId }
    }

    /**
     * Get events within time range
     */
    fun getEventsSince(sinceMs: Long): List<SessionLogEntry> {
        val cutoffTime = System.currentTimeMillis() - sinceMs
        return sessionEvents.filter { it.timestamp >= cutoffTime }
    }

    /**
     * Get session statistics
     */
    fun getSessionStats(): SessionStats {
        val events = sessionEvents.toList()
        val totalEvents = events.size
        val successfulEvents = events.count { it.success }
        val successRate = if (totalEvents > 0) {
            (successfulEvents.toFloat() / totalEvents) * 100
        } else 0f

        val averageResponseTime = if (events.isNotEmpty()) {
            events.map { it.responseTimeMs }.average().toLong()
        } else 0L

        val eventsByType = events.groupingBy { it.eventType }.eachCount()

        val totalDataSent = events.sumOf {
            it.jsonPayload.toString().toByteArray().size.toLong()
        }

        val sessionDuration = System.currentTimeMillis() - sessionStartTime

        return SessionStats(
            totalEvents = totalEvents,
            successfulEvents = successfulEvents,
            failedEvents = totalEvents - successfulEvents,
            eventsByType = eventsByType,
            successRate = successRate,
            averageResponseTime = averageResponseTime,
            totalDataSentBytes = totalDataSent,
            sessionDurationMs = sessionDuration
        )
    }

    /**
     * Generate complete session report
     */
    fun generateSessionReport(): String {
        val events = sessionEvents.reversed()  // Oldest first
        val stats = getSessionStats()
        val config = ConfigurationManager.getInstance(project)
        val authToken = config.authToken

        return buildString {
            appendLine("Zest Metrics Session Log")
            appendLine("Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
            appendLine("Plugin Version: ${MetricsUtils.getPluginVersion()}")
            appendLine("User: ${MetricsUtils.getActualUsername(project)}")
            appendLine("Project: ${project.name}")
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()

            // Event details
            events.forEachIndexed { index, entry ->
                appendLine("[${entry.getFormattedRelativeTime()}] EVENT #${index + 1}: ${entry.eventType.uppercase()}")
                appendLine("Endpoint: ${entry.httpMethod} ${entry.endpoint}")
                appendLine("Payload:")
                appendLine(entry.getPrettyJsonPayload())
                appendLine("Response: ${if (entry.success) "✅" else "❌"} ${entry.responseCode} (${entry.responseTimeMs}ms)")
                if (entry.errorMessage != null) {
                    appendLine("Error: ${entry.errorMessage}")
                }
                appendLine()
            }

            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("SESSION STATISTICS")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("Duration: ${stats.sessionDurationMs / 1000}s")
            appendLine("Total Events: ${stats.totalEvents}")
            appendLine("Successful: ${stats.successfulEvents}")
            appendLine("Failed: ${stats.failedEvents}")
            appendLine("Success Rate: ${String.format("%.1f%%", stats.successRate)}")
            appendLine("Average Response Time: ${stats.averageResponseTime}ms")
            appendLine("Total Data Sent: ${stats.totalDataSentBytes} bytes")
            appendLine()
            appendLine("Events by Type:")
            stats.eventsByType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                appendLine("  - $type: $count")
            }
        }
    }

    /**
     * Export session log to file
     */
    fun exportToFile(filePath: String): Boolean {
        return try {
            val report = generateSessionReport()
            File(filePath).writeText(report)
            logger.info("Session log exported to: $filePath")
            true
        } catch (e: Exception) {
            logger.error("Failed to export session log", e)
            false
        }
    }

    /**
     * Clear all session events
     */
    fun clearSession() {
        sessionEvents.clear()
        logger.info("Session log cleared")
    }

    companion object {
        fun getInstance(project: Project): MetricsSessionLogger {
            return project.getService(MetricsSessionLogger::class.java)
        }
    }
}

/**
 * Session statistics data class
 */
data class SessionStats(
    val totalEvents: Int,
    val successfulEvents: Int,
    val failedEvents: Int,
    val eventsByType: Map<String, Int>,
    val successRate: Float,
    val averageResponseTime: Long,
    val totalDataSentBytes: Long,
    val sessionDurationMs: Long
)
