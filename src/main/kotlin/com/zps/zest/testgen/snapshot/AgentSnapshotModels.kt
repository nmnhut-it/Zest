package com.zps.zest.testgen.snapshot

/**
 * Data models for serializing agent snapshots to JSON for testing and debugging.
 * Based on SessionSerializer pattern.
 */

data class AgentSnapshot(
    val version: String = SNAPSHOT_VERSION,
    val agentType: AgentType,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
    val originalPrompt: String,

    val chatMessages: List<SerializableChatMessage>,

    val contextToolsState: ContextToolsSnapshot?,
    val planningToolsState: PlanningToolsSnapshot?,
    val mergingToolsState: MergingToolsSnapshot?,

    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        const val SNAPSHOT_VERSION = "1.0"
    }
}

enum class AgentType {
    CONTEXT,
    COORDINATOR,
    TEST_WRITER,
    TEST_MERGER
}

data class SerializableChatMessage(
    val type: MessageType,
    val contentCompressed: String,
    val toolCalls: List<SerializableToolCall>? = null,
    val toolResults: List<SerializableToolResult>? = null
)

enum class MessageType {
    SYSTEM,
    USER,
    AI,
    TOOL_RESULT
}

data class SerializableToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class SerializableToolResult(
    val id: String,
    val toolName: String,
    val result: String
)

data class ContextToolsSnapshot(
    val analyzedClasses: Map<String, String>,
    val pathToFQN: Map<String, String>,
    val contextNotes: List<String>,
    val readFiles: Map<String, String>,
    val buildFiles: Map<String, String>,
    val methodUsages: Map<String, String>,
    val frameworkInfo: String?,
    val projectDependencies: String?,
    val contextCollectionDone: Boolean,
    val discoveredCallers: Set<String>,
    val investigatedCallers: Set<String>,
    val referencedFiles: Set<String>
)

data class PlanningToolsSnapshot(
    val targetClass: String?,
    val targetMethods: List<String>,
    val scenarios: String?,
    val reasoning: String?,
    val testingNotes: String?
)

data class MergingToolsSnapshot(
    val lastExistingTestCode: String?,
    val lastMergedResult: String?,
    val fixStrategy: String
)
