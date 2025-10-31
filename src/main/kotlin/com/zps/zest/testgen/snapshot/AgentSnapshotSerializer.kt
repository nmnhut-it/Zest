package com.zps.zest.testgen.snapshot

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.langchain4j.data.message.*
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Checkpoint timing for systematic snapshot naming
 */
enum class CheckpointTiming {
    BEFORE,  // Before phase execution
    AFTER,   // After phase completion
    ERROR,   // On error/failure
    CANCEL   // On user cancellation
}

class AgentSnapshotSerializer {

    companion object {
        // Lazy logger that works without IntelliJ platform
        private val LOG by lazy {
            try {
                Logger.getInstance(AgentSnapshotSerializer::class.java)
            } catch (e: NoClassDefFoundError) {
                null
            }
        }

        private val gson: Gson = GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create()

        private const val SNAPSHOT_DIR = ".zest-agent-snapshots"
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        // Track sequence numbers per session for systematic checkpoint naming
        private val sessionSequences = ConcurrentHashMap<String, AtomicInteger>()

        private fun log(message: String, error: Throwable? = null) {
            LOG?.let {
                if (error != null) it.error(message, error) else it.info(message)
            } ?: run {
                if (error != null) {
                    System.err.println("$message: ${error.message}")
                    error.printStackTrace()
                } else {
                    println(message)
                }
            }
        }

        private fun logWarn(message: String) {
            LOG?.warn(message) ?: System.err.println("WARN: $message")
        }

        @JvmStatic
        fun serializeToJson(snapshot: AgentSnapshot): String {
            return try {
                gson.toJson(snapshot)
            } catch (e: Exception) {
                log("Failed to serialize agent snapshot", e)
                throw e
            }
        }

        @JvmStatic
        fun deserializeFromJson(json: String): AgentSnapshot? {
            return try {
                val snapshot = gson.fromJson(json, AgentSnapshot::class.java)

                if (snapshot.version != AgentSnapshot.SNAPSHOT_VERSION) {
                    logWarn("Snapshot version mismatch: ${snapshot.version} != ${AgentSnapshot.SNAPSHOT_VERSION}")
                }

                snapshot
            } catch (e: JsonSyntaxException) {
                log("Failed to deserialize agent snapshot", e)
                null
            } catch (e: Exception) {
                log("Error restoring agent snapshot", e)
                null
            }
        }

        @JvmStatic
        fun saveToFile(snapshot: AgentSnapshot, project: Project): File {
            val snapshotDir = getSnapshotDirectory(project)
            snapshotDir.mkdirs()

            val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
            val fileName = "${snapshot.agentType.name.lowercase()}-${timestamp}.json"
            val file = File(snapshotDir, fileName)

            val json = serializeToJson(snapshot)
            Files.writeString(file.toPath(), json)

            log("Saved agent snapshot to: ${file.absolutePath}")
            return file
        }

        /**
         * Save checkpoint with systematic naming: {sessionId}-{seq}-{timing}-{agent}.json
         * Automatically adds timing and sequence metadata to the snapshot.
         */
        @JvmStatic
        fun saveCheckpoint(
            snapshot: AgentSnapshot,
            project: Project,
            timing: CheckpointTiming
        ): File {
            val snapshotDir = getSnapshotDirectory(project)
            snapshotDir.mkdirs()

            // Get next sequence number for this session
            val sequence = sessionSequences
                .computeIfAbsent(snapshot.sessionId) { AtomicInteger(0) }
                .incrementAndGet()

            // Generate systematic filename
            val sessionIdShort = snapshot.sessionId.take(8)  // Shorten for readability
            val seqStr = String.format("%03d", sequence)
            val timingStr = timing.name.lowercase()
            val agentStr = snapshot.agentType.name.lowercase()
            val fileName = "${sessionIdShort}-${seqStr}-${timingStr}-${agentStr}.json"

            val file = File(snapshotDir, fileName)

            // Add timing and sequence to metadata
            val enhancedSnapshot = snapshot.copy(
                metadata = snapshot.metadata + mapOf(
                    "checkpoint_timing" to timing.name,
                    "checkpoint_sequence" to sequence.toString()
                )
            )

            val json = serializeToJson(enhancedSnapshot)
            Files.writeString(file.toPath(), json)

            log("Saved checkpoint: ${file.name} (${timing.name})")
            return file
        }

        /**
         * Clear sequence tracking for a completed session
         */
        @JvmStatic
        fun clearSession(sessionId: String) {
            sessionSequences.remove(sessionId)
        }

        @JvmStatic
        fun loadFromFile(filePath: String): AgentSnapshot? {
            return try {
                val json = Files.readString(Paths.get(filePath))
                deserializeFromJson(json)
            } catch (e: Exception) {
                log("Failed to load snapshot from file: $filePath", e)
                null
            }
        }

        @JvmStatic
        fun listSnapshots(project: Project): List<SnapshotMetadata> {
            val snapshotDir = getSnapshotDirectory(project)
            if (!snapshotDir.exists()) {
                return emptyList()
            }

            return snapshotDir.listFiles { file -> file.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val snapshot = loadFromFile(file.absolutePath)
                        snapshot?.let {
                            SnapshotMetadata(
                                filePath = file.absolutePath,
                                fileName = file.name,
                                agentType = it.agentType,
                                sessionId = it.sessionId,
                                timestamp = it.timestamp,
                                description = it.description,
                                messageCount = it.chatMessages.size,
                                originalPrompt = it.originalPrompt
                            )
                        }
                    } catch (e: Exception) {
                        logWarn("Failed to read snapshot: ${file.name}")
                        null
                    }
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()
        }

        @JvmStatic
        fun deleteSnapshot(filePath: String): Boolean {
            return try {
                Files.deleteIfExists(Paths.get(filePath))
            } catch (e: Exception) {
                log("Failed to delete snapshot: $filePath", e)
                false
            }
        }

        private fun getSnapshotDirectory(project: Project): File {
            return File(project.basePath, SNAPSHOT_DIR)
        }

        @JvmStatic
        fun compressString(input: String): String {
            val bytes = input.toByteArray()
            val baos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(bytes)
            }
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
        }

        @JvmStatic
        fun decompressString(compressed: String): String {
            val bytes = java.util.Base64.getDecoder().decode(compressed)
            val bais = java.io.ByteArrayInputStream(bytes)
            return GZIPInputStream(bais).use { gzip ->
                String(gzip.readBytes())
            }
        }

        @JvmStatic
        fun convertChatMessage(message: ChatMessage): SerializableChatMessage {
            return when (message) {
                is SystemMessage -> SerializableChatMessage(
                    type = MessageType.SYSTEM,
                    contentCompressed = compressString(message.text())
                )
                is UserMessage -> SerializableChatMessage(
                    type = MessageType.USER,
                    contentCompressed = compressString(message.singleText())
                )
                is AiMessage -> {
                    val toolCalls = message.toolExecutionRequests()?.map { request ->
                        SerializableToolCall(
                            id = request.id(),
                            name = request.name(),
                            arguments = request.arguments()
                        )
                    }
                    SerializableChatMessage(
                        type = MessageType.AI,
                        contentCompressed = compressString(message.text() ?: ""),
                        toolCalls = toolCalls
                    )
                }
                is ToolExecutionResultMessage -> SerializableChatMessage(
                    type = MessageType.TOOL_RESULT,
                    contentCompressed = compressString(message.text() ?: ""),
                    toolResults = listOf(
                        SerializableToolResult(
                            id = message.id() ?: java.util.UUID.randomUUID().toString(),
                            toolName = message.toolName() ?: "unknown",
                            result = message.text() ?: ""
                        )
                    )
                )
                else -> SerializableChatMessage(
                    type = MessageType.USER,
                    contentCompressed = compressString(message.toString())
                )
            }
        }

        @JvmStatic
        fun restoreChatMessage(serialized: SerializableChatMessage): ChatMessage {
            val content = decompressString(serialized.contentCompressed)
            return when (serialized.type) {
                MessageType.SYSTEM -> SystemMessage(content)
                MessageType.USER -> UserMessage(content)
                MessageType.AI -> {
                    val toolRequests = serialized.toolCalls?.map { call ->
                        ToolExecutionRequest.builder()
                            .id(call.id)
                            .name(call.name)
                            .arguments(call.arguments)
                            .build()
                    }
                    if (toolRequests != null && toolRequests.isNotEmpty()) {
                        AiMessage(content, toolRequests)
                    } else {
                        AiMessage(content)
                    }
                }
                MessageType.TOOL_RESULT -> {
                    val result = serialized.toolResults?.firstOrNull()
                    ToolExecutionResultMessage(
                        result?.id ?: java.util.UUID.randomUUID().toString(),
                        result?.toolName ?: "unknown",
                        content
                    )
                }
            }
        }
    }
}

data class SnapshotMetadata(
    val filePath: String,
    val fileName: String,
    val agentType: AgentType,
    val sessionId: String,
    val timestamp: Long,
    val description: String,
    val messageCount: Int,
    val originalPrompt: String
)
