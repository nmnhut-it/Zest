package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.langchain4j.ui.ChatMemoryDialog
import com.zps.zest.langchain4j.ui.ChatMemoryPanel
import com.zps.zest.langchain4j.ui.DialogManager
import java.awt.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.border.EmptyBorder

/**
 * Panel that displays the chat memory for the context agent.
 * Shows the conversation history in a tree structure.
 */
class ContextDisplayPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var contextAgentMemory: dev.langchain4j.memory.ChatMemory? = null
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

        // Add button panel at the bottom
        add(createButtonPanel(), BorderLayout.SOUTH)
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 10))
        panel.border = EmptyBorder(5, 5, 5, 5)

        val snapshotButton = JButton("📸 Manage Snapshots")
        snapshotButton.preferredSize = JBUI.size(180, 40)
        snapshotButton.addActionListener { showSnapshotManagerDialog() }
        snapshotButton.toolTipText = "View and manage agent snapshots from this session"
        panel.add(snapshotButton)

        return panel
    }

    private fun showSnapshotManagerDialog() {
        val dialog = com.zps.zest.testgen.snapshot.ui.SnapshotManagerDialog(project)
        DialogManager.showDialog(dialog)
    }

    fun showActivity(message: String) {
        // Context panel shows chat memory, activity not needed here
    }

    /**
     * Clear the panel
     */
    fun clear() {
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
    
    /**
     * Set chat memory for the context agent
     */
    fun setChatMemory(chatMemory: dev.langchain4j.memory.ChatMemory?) {
        SwingUtilities.invokeLater {
            this.contextAgentMemory = chatMemory

            if (chatMemory != null) {
                if (chatMemoryPanel == null) {
                    // Only create the panel if it doesn't exist
                    removeAll()
                    chatMemoryPanel = ChatMemoryPanel(project, chatMemory, "ContextAgent")
                    add(chatMemoryPanel, BorderLayout.CENTER)
                    add(createButtonPanel(), BorderLayout.SOUTH)
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