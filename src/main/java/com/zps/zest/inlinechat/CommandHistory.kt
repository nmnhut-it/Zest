package com.zps.zest.inlinechat

import com.intellij.openapi.components.*

/**
 * State class for persisting command history
 */
class CommandHistoryState : BaseState() {
    var history by list<String>()
}

/**
 * Service for managing and persisting command history
 */
@Service
@State(
    name = "com.zps.zest.inlinechat.CommandHistory",
    storages = [Storage("zest-inline-chat-command-history.xml")]
)
class CommandHistory : SimplePersistentStateComponent<CommandHistoryState>(CommandHistoryState()) {
    private val maxHistorySize = 30

    fun getHistory(): List<String> {
        return state.history.toList()
    }

    fun addCommand(command: String) {
        val existingIndex = state.history.indexOfFirst { it == command }

        if (existingIndex != -1) {
            state.history.removeAt(existingIndex)
        }

        state.history.add(0, command)

        if (state.history.size > maxHistorySize) {
            state.history = state.history.take(maxHistorySize).toMutableList()
        }
    }

    fun deleteCommand(value: String) {
        state.history.removeAll { it == value }
    }

    fun clearHistory() {
        state.history.clear()
    }
}