package com.zps.zest.testgen.ui.persistence

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.zps.zest.testgen.model.TestGenerationRequest
import com.zps.zest.testgen.model.TestGenerationSession
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing test generation session persistence.
 * Handles saving, loading, and listing saved sessions.
 */
@Service(Service.Level.PROJECT)
class SessionRepository(private val project: Project) {
    
    companion object {
        private val LOG = Logger.getInstance(SessionRepository::class.java)
        private const val SESSIONS_DIR = ".zest/test-sessions"
        private const val SESSION_FILE_EXTENSION = "ztgs" // Zest Test Generation Session
        private const val AUTO_SAVE_INTERVAL_MS = 30000L // 30 seconds
        
        fun getInstance(project: Project): SessionRepository {
            return project.getService(SessionRepository::class.java)
        }
    }
    
    private val serializer = SessionSerializer()
    private val autoSaveTimers = ConcurrentHashMap<String, javax.swing.Timer>()
    
    /**
     * Session metadata for listing
     */
    data class SessionMetadata(
        val sessionId: String,
        val fileName: String,
        val filePath: String,
        val createdAt: LocalDateTime,
        val lastModified: LocalDateTime,
        val status: String,
        val targetClass: String?,
        val targetFilePath: String? = null,
        val progressPercent: Int,
        val fileSize: Long
    )
    
    /**
     * Get the sessions directory for the project
     */
    private fun getSessionsDirectory(): File {
        val projectDir = project.basePath ?: throw IllegalStateException("Project base path not found")
        val sessionsDir = File(projectDir, SESSIONS_DIR)
        
        if (!sessionsDir.exists()) {
            if (!sessionsDir.mkdirs()) {
                LOG.warn("Failed to create sessions directory: ${sessionsDir.absolutePath}")
            }
        }
        
        return sessionsDir
    }
    
    /**
     * Save a session to disk
     */
    fun saveSession(
        sessionData: SessionSerializer.SessionRestorationData,
        fileName: String? = null
    ): Result<File> {
        return try {
            val sessionsDir = getSessionsDirectory()
            val actualFileName = fileName ?: generateFileName(sessionData.sessionId)
            val sessionFile = File(sessionsDir, actualFileName)
            
            // Serialize the session data directly without needing TestGenerationSession
            val json = serializer.serializeSessionData(sessionData)
            
            // Write to file
            FileUtil.writeToFile(sessionFile, json)
            
            LOG.info("Session saved: ${sessionFile.absolutePath}")
            Result.success(sessionFile)
            
        } catch (e: IOException) {
            LOG.error("Failed to save session", e)
            Result.failure(e)
        } catch (e: Exception) {
            LOG.error("Error saving session", e)
            Result.failure(e)
        }
    }
    
    /**
     * Load a session from disk
     */
    fun loadSession(fileName: String): Result<SessionSerializer.SessionRestorationData> {
        return try {
            val sessionsDir = getSessionsDirectory()
            val sessionFile = File(sessionsDir, fileName)
            
            if (!sessionFile.exists()) {
                return Result.failure(IOException("Session file not found: $fileName"))
            }
            
            val json = FileUtil.loadFile(sessionFile)
            val sessionData = serializer.deserializeSession(json)
            
            if (sessionData != null) {
                LOG.info("Session loaded: ${sessionFile.absolutePath}")
                Result.success(sessionData)
            } else {
                Result.failure(IOException("Failed to deserialize session"))
            }
            
        } catch (e: IOException) {
            LOG.error("Failed to load session", e)
            Result.failure(e)
        } catch (e: Exception) {
            LOG.error("Error loading session", e)
            Result.failure(e)
        }
    }
    
    /**
     * Delete a saved session
     */
    fun deleteSession(fileName: String): Boolean {
        return try {
            val sessionsDir = getSessionsDirectory()
            val sessionFile = File(sessionsDir, fileName)
            
            if (sessionFile.exists() && sessionFile.delete()) {
                LOG.info("Session deleted: ${sessionFile.absolutePath}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            LOG.error("Failed to delete session", e)
            false
        }
    }
    
    /**
     * List all saved sessions
     */
    fun listSessions(): List<SessionMetadata> {
        return try {
            val sessionsDir = getSessionsDirectory()
            
            if (!sessionsDir.exists()) {
                return emptyList()
            }
            
            sessionsDir.listFiles { file ->
                file.isFile && file.extension == SESSION_FILE_EXTENSION
            }?.mapNotNull { file ->
                try {
                    // Load and parse basic metadata without full deserialization
                    val json = FileUtil.loadFile(file)
                    extractMetadata(file, json)
                } catch (e: Exception) {
                    LOG.warn("Failed to read session metadata from ${file.name}", e)
                    null
                }
            }?.sortedByDescending { it.lastModified } ?: emptyList()
            
        } catch (e: Exception) {
            LOG.error("Failed to list sessions", e)
            emptyList()
        }
    }
    
    /**
     * Enable auto-save for a session
     */
    fun enableAutoSave(
        sessionId: String,
        dataProvider: () -> SessionSerializer.SessionRestorationData?
    ) {
        // Cancel existing timer if any
        disableAutoSave(sessionId)
        
        val timer = javax.swing.Timer(AUTO_SAVE_INTERVAL_MS.toInt()) {
            dataProvider()?.let { data ->
                val fileName = generateAutoSaveFileName(sessionId)
                saveSession(data, fileName)
            }
        }
        
        timer.start()
        autoSaveTimers[sessionId] = timer
        LOG.info("Auto-save enabled for session: $sessionId")
    }
    
    /**
     * Disable auto-save for a session
     */
    fun disableAutoSave(sessionId: String) {
        autoSaveTimers.remove(sessionId)?.let { timer ->
            timer.stop()
            LOG.info("Auto-save disabled for session: $sessionId")
        }
    }
    
    /**
     * Generate a file name for a session
     */
    private fun generateFileName(sessionId: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "session_${timestamp}_${sessionId.take(8)}.$SESSION_FILE_EXTENSION"
    }
    
    /**
     * Generate an auto-save file name
     */
    private fun generateAutoSaveFileName(sessionId: String): String {
        return "autosave_${sessionId.take(8)}.$SESSION_FILE_EXTENSION"
    }
    
    /**
     * Extract metadata from session JSON without full deserialization
     */
    private fun extractMetadata(file: File, json: String): SessionMetadata? {
        return try {
            // Quick regex extraction for performance
            val sessionIdMatch = """"sessionId"\s*:\s*"([^"]+)"""".toRegex().find(json)
            val statusMatch = """"status"\s*:\s*"([^"]+)"""".toRegex().find(json)
            val targetClassMatch = """"targetClass"\s*:\s*"([^"]+)"""".toRegex().find(json)
            val targetFilePathMatch = """"targetFilePath"\s*:\s*"([^"]+)"""".toRegex().find(json)
            val progressMatch = """"progressPercent"\s*:\s*(\d+)""".toRegex().find(json)
            val createdAtMatch = """"createdAt"\s*:\s*(\d+)""".toRegex().find(json)
            
            val sessionId = sessionIdMatch?.groupValues?.get(1) ?: "unknown"
            val status = statusMatch?.groupValues?.get(1) ?: "UNKNOWN"
            val targetClass = targetClassMatch?.groupValues?.get(1)
            val targetFilePath = targetFilePathMatch?.groupValues?.get(1)
            val progress = progressMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val createdAtMillis = createdAtMatch?.groupValues?.get(1)?.toLongOrNull() ?: file.lastModified()
            
            SessionMetadata(
                sessionId = sessionId,
                fileName = file.name,
                filePath = file.absolutePath,
                createdAt = Instant.ofEpochMilli(createdAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime(),
                lastModified = Instant.ofEpochMilli(file.lastModified())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime(),
                status = status,
                targetClass = targetClass,
                targetFilePath = targetFilePath,
                progressPercent = progress,
                fileSize = file.length()
            )
        } catch (e: Exception) {
            LOG.warn("Failed to extract metadata", e)
            null
        }
    }
    
    /**
     * Clean up old auto-save files
     */
    fun cleanupAutoSaves(keepDays: Int = 7) {
        try {
            val sessionsDir = getSessionsDirectory()
            val cutoffTime = System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)
            
            sessionsDir.listFiles { file ->
                file.isFile && 
                file.name.startsWith("autosave_") && 
                file.lastModified() < cutoffTime
            }?.forEach { file ->
                if (file.delete()) {
                    LOG.info("Deleted old auto-save: ${file.name}")
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to cleanup auto-saves", e)
        }
    }
}