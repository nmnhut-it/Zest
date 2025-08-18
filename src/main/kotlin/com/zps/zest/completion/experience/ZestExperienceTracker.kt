package com.zps.zest.completion.experience

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import com.zps.zest.langchain4j.util.LLMService
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tracks user modifications after accepting AI suggestions to learn preferences
 */
class ZestExperienceTracker(private val project: Project) {
    private val logger = Logger.getInstance(ZestExperienceTracker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llmService by lazy { LLMService(project) }
    
    data class RewriteSession(
        val methodName: String,
        val originalCode: String,
        val aiSuggestion: String,
        val timestamp: LocalDateTime
    )
    
    // Track active sessions
    private val activeSessions = mutableMapOf<String, RewriteSession>()
    
    /**
     * Start tracking a rewrite acceptance
     */
    fun trackRewriteAcceptance(
        sessionId: String,
        methodName: String,
        originalCode: String,
        aiSuggestion: String,
        editor: Editor
    ) {
        // Store session info
        activeSessions[sessionId] = RewriteSession(
            methodName = methodName,
            originalCode = originalCode,
            aiSuggestion = aiSuggestion,
            timestamp = LocalDateTime.now()
        )
        
        // Schedule check for user modifications
        scope.launch {
            delay(10000) // Wait 10 seconds
            
            withContext(Dispatchers.Main) {
                checkForUserModifications(sessionId, editor)
            }
        }
    }
    
    /**
     * Check if user modified the code after accepting
     */
    private fun checkForUserModifications(sessionId: String, editor: Editor) {
        try {
            val session = activeSessions[sessionId] ?: return
            
            // Get current code from editor
            val currentCode = editor.document.text
            
            // Find the method in current code (simplified - in real implementation would be more robust)
            val methodStart = currentCode.indexOf(session.methodName)
            if (methodStart == -1) return
            
            // Extract method content (simplified)
            val methodEnd = findMethodEnd(currentCode, methodStart)
            val currentMethodCode = currentCode.substring(methodStart, methodEnd).trim()
            
            // Compare with AI suggestion
            if (currentMethodCode != session.aiSuggestion.trim()) {
                // User made changes - analyze them
                analyzeAndRecordExperience(session, currentMethodCode)
            }
            
            // Clean up session
            activeSessions.remove(sessionId)
            
        } catch (e: Exception) {
            logger.error("Failed to check for user modifications", e)
        }
    }
    
    /**
     * Use LLM to analyze the changes and record the learning
     */
    private fun analyzeAndRecordExperience(session: RewriteSession, userFinalCode: String) {
        scope.launch {
            try {
                // Build prompt for LLM to analyze the change
                val analysisPrompt = """
                    The AI suggested this code:
                    ```
                    ${session.aiSuggestion}
                    ```
                    
                    The user modified it to:
                    ```
                    $userFinalCode
                    ```
                    
                    In one sentence, what pattern or preference does this change indicate?
                    Focus on actionable insights like "User prefers X over Y" or "User adds Z when AI doesn't".
                """.trimIndent()
                
                // Query LLM for analysis
                val queryParams = LLMService.LLMQueryParams(analysisPrompt)
                    .useLiteCodeModel()
                    .withMaxTokens(100)
                    .withTemperature(0.3)
                
                val analysis = llmService.queryWithParams(queryParams, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.QUICK_ACTION_LOGGING)
                
                if (analysis != null && analysis.isNotBlank()) {
                    recordExperience(session.methodName, analysis.trim())
                }
                
            } catch (e: Exception) {
                logger.error("Failed to analyze user modifications", e)
            }
        }
    }
    
    /**
     * Record the experience to the file
     */
    private fun recordExperience(methodName: String, learning: String) {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            val currentContent = configManager.readZestConfigFile("experience.md")
                ?: getDefaultExperienceContent()
            
            // Parse current patterns
            val lines = currentContent.lines().toMutableList()
            val patternsIndex = lines.indexOfFirst { it.trim() == "## Recent Patterns" }
            
            if (patternsIndex != -1) {
                // Find where to insert the new pattern
                var insertIndex = patternsIndex + 1
                while (insertIndex < lines.size && 
                       !lines[insertIndex].startsWith("---") && 
                       !lines[insertIndex].startsWith("##")) {
                    if (lines[insertIndex].trim().isEmpty() && 
                        insertIndex + 1 < lines.size && 
                        lines[insertIndex + 1].startsWith("---")) {
                        break
                    }
                    insertIndex++
                }
                
                // Insert the new pattern
                lines.add(insertIndex, "- $learning")
                
                // Keep only recent patterns (last 20)
                val patternStart = patternsIndex + 1
                var patternEnd = insertIndex + 1
                val patterns = mutableListOf<String>()
                for (i in patternStart until patternEnd) {
                    if (lines[i].trim().startsWith("-")) {
                        patterns.add(lines[i])
                    }
                }
                
                // Remove old patterns and add back only recent ones
                for (i in patternEnd - 1 downTo patternStart) {
                    if (lines[i].trim().startsWith("-")) {
                        lines.removeAt(i)
                    }
                }
                
                patterns.takeLast(20).forEach { pattern ->
                    lines.add(patternStart, pattern)
                }
            }
            
            // Add timestamped entry
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val entry = "\n## $timestamp - $methodName\n**Learning**: $learning"
            
            // Find where to add the entry (after the --- separator)
            val separatorIndex = lines.indexOfFirst { it.trim() == "---" }
            if (separatorIndex != -1) {
                lines.add(separatorIndex + 1, entry)
            } else {
                lines.add(entry)
            }
            
            // Save updated content
            configManager.writeZestConfigFile(
                "experience.md",
                lines.joinToString("\n")
            )
            
            logger.info("Recorded experience: $learning")
            
        } catch (e: Exception) {
            logger.error("Failed to record experience", e)
        }
    }
    
    /**
     * Get recent patterns for inclusion in prompts
     */
    fun getRecentPatterns(): List<String> {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            val content = configManager.readZestConfigFile("experience.md")
                ?: return emptyList()
            
            val patterns = mutableListOf<String>()
            val lines = content.lines()
            var inPatternsSection = false
            
            for (line in lines) {
                when {
                    line.trim() == "## Recent Patterns" -> inPatternsSection = true
                    line.trim() == "---" && inPatternsSection -> break
                    inPatternsSection && line.trim().startsWith("-") -> {
                        patterns.add(line.trim().substring(1).trim())
                    }
                }
            }
            
            return patterns
        } catch (e: Exception) {
            logger.error("Failed to load recent patterns", e)
            return emptyList()
        }
    }
    
    /**
     * Simple method end finder (would be more sophisticated in real implementation)
     */
    private fun findMethodEnd(code: String, methodStart: Int): Int {
        var braceCount = 0
        var inMethod = false
        var i = methodStart
        
        while (i < code.length) {
            when (code[i]) {
                '{' -> {
                    braceCount++
                    inMethod = true
                }
                '}' -> {
                    braceCount--
                    if (inMethod && braceCount == 0) {
                        return i + 1
                    }
                }
            }
            i++
        }
        
        return code.length
    }
    
    private fun getDefaultExperienceContent(): String {
        return """
# Zest AI Experience Learning

## Recent Patterns
(Patterns learned from user modifications will appear here)

---

# Experience Log
(Detailed entries will appear below)
        """.trimIndent()
    }
    
    fun dispose() {
        scope.cancel()
        activeSessions.clear()
    }
    
    companion object {
        fun getInstance(project: Project): ZestExperienceTracker {
            return ZestExperienceTracker(project)
        }
    }
}