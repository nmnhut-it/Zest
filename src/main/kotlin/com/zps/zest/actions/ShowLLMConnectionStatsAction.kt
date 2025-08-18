package com.zps.zest.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.zps.zest.langchain4j.util.LLMService

/**
 * Action to show LLM connection pool statistics
 */
class ShowLLMConnectionStatsAction : AnAction("Show LLM Connection Stats") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        try {
            // Get the LLM service
            val llmService = project.getService(LLMService::class.java)
            val stats = llmService.connectionStats
            
            // Build the message
            val message = buildString {
                appendLine("=== LLM Connection Statistics ===")
                appendLine()
                appendLine("Total Requests: ${stats.totalRequests}")
                appendLine("Average Latency: ${stats.averageLatency}ms")
                appendLine("Min Latency: ${stats.minLatency}ms")
                appendLine("Max Latency: ${stats.maxLatency}ms")
                appendLine()
                appendLine("Connection Features:")
                appendLine("- HTTP/2 Support: Enabled")
                appendLine("- Connection Pooling: Enabled (automatic)")
                appendLine("- Keep-Alive: Enabled (automatic)")
                appendLine()
                appendLine("Performance Tips:")
                appendLine("- Average latency < 100ms is excellent")
                appendLine("- Average latency < 200ms is good")
                appendLine("- Average latency > 500ms may indicate network issues")
                
                if (stats.totalRequests == 0L) {
                    appendLine()
                    appendLine("No requests have been made yet.")
                    appendLine("Try triggering some code completions to see statistics.")
                }
            }
            
            Messages.showInfoMessage(project, message, "LLM Connection Statistics")
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to get connection statistics: ${e.message}",
                "Error"
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
