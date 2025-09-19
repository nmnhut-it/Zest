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
import javax.swing.Timer
import javax.swing.border.EmptyBorder

/**
 * Panel that displays the chat memory for the context agent.
 * Shows the conversation history in a tree structure.
 */
class ContextDisplayPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var contextAgentMemory: dev.langchain4j.memory.chat.MessageWindowChatMemory? = null
    private var chatMemoryPanel: ChatMemoryPanel? = null
    private val updateTimer = Timer(1000) { checkForUpdates() }

    init {
        updateTimer.isRepeats = true
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
            // Stop the update timer
            updateTimer.stop()

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

            if (chatMemory != null) {
                if (chatMemoryPanel == null) {
                    // Only create the panel if it doesn't exist
                    removeAll()
                    chatMemoryPanel = ChatMemoryPanel(project, chatMemory, "ContextAgent")
                    add(chatMemoryPanel, BorderLayout.CENTER)
                    revalidate()
                    repaint()

                    // Start the update timer when panel is created
                    updateTimer.start()
                } else {
                    // Reuse existing panel, just update the memory
                    chatMemoryPanel!!.setChatMemory(chatMemory)
                }
            } else {
                // Clear everything
                updateTimer.stop()
                chatMemoryPanel = null
                removeAll()
                setupUI()
                revalidate()
                repaint()
            }
        }
    }

    private fun checkForUpdates() {
        SwingUtilities.invokeLater {
            // Only check for updates if we have a panel
            chatMemoryPanel?.checkForUpdates()
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