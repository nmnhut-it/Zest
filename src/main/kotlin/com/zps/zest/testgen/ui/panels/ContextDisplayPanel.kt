package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.langchain4j.ui.ChatMemoryPanel
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel that displays the chat memory for the context agent.
 * Shows the conversation history in a tree structure.
 */
class ContextDisplayPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var contextAgentMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var chatMemoryPanel: ChatMemoryPanel? = null
    
    init {
        setupUI()
    }

    private fun setupUI() {
        // Initially show a placeholder message
        val placeholderPanel = JPanel(BorderLayout())
        placeholderPanel.border = EmptyBorder(20, 20, 20, 20)

        val messageLabel = JBLabel("Context agent chat will appear here when a test generation session starts")
        messageLabel.horizontalAlignment = SwingConstants.CENTER
        messageLabel.foreground = UIUtil.getContextHelpForeground()
        messageLabel.font = messageLabel.font.deriveFont(Font.ITALIC)
        placeholderPanel.add(messageLabel, BorderLayout.CENTER)

        add(placeholderPanel, BorderLayout.CENTER)
    }

    /**
     * Clear the panel
     */
    fun clear() {
        SwingUtilities.invokeLater {
            // Reset chat memory
            contextAgentMemory = null
            chatMemoryPanel = null

            // Show placeholder again
            removeAll()
            setupUI()
            revalidate()
            repaint()
        }
    }
    
    /**
     * Set chat memory for the context agent
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory?) {
        SwingUtilities.invokeLater {
            this.contextAgentMemory = chatMemory

            // Remove existing content and add the chat memory panel
            removeAll()

            if (chatMemory != null) {
                // Create and add the chat memory panel
                chatMemoryPanel = ChatMemoryPanel(project, chatMemory, "ContextAgent")
                add(chatMemoryPanel, BorderLayout.CENTER)
            } else {
                // Show placeholder
                setupUI()
            }

            revalidate()
            repaint()
        }
    }

    // Stub methods for backwards compatibility - these no longer do anything
    // but are kept to avoid breaking existing code that might call them

    fun addFile(data: Any) {
        // No longer used - context files are not displayed anymore
    }

    fun updateFile(data: Any) {
        // No longer used - context files are not displayed anymore
    }

    fun getContextFiles(): List<Any> {
        // No longer used - return empty list
        return emptyList()
    }

    fun getLastKnownMessageCount(): Int {
        return contextAgentMemory?.messages()?.size ?: 0
    }
}