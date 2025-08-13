package com.zps.zest.testgen.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.TestGenerationService
import com.zps.zest.testgen.model.TestGenerationSession
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for viewing agent debug history and memory
 */
class AgentDebugDialog(
    private val project: Project,
    private val session: TestGenerationSession?
) : DialogWrapper(project, false) {
    

    
    private val service = project.getService(TestGenerationService::class.java)
    private val tabbedPane = JBTabbedPane()
    
    init {
        title = "Agent Debug History"
        setSize(1200, 800)
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = UIUtil.getPanelBackground()
        
        // Add title and session info
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Create tabs for each agent
        setupAgentTabs()
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        
        // Add refresh button at bottom
        val bottomPanel = createBottomPanel()
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        val titleLabel = JLabel("🐛 Agent Execution Debug History")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        session?.let {
            val sessionInfo = JLabel("Session: ${it.sessionId.substring(0, 8)}... | Status: ${it.status}")
            sessionInfo.foreground = UIUtil.getInactiveTextColor()
            panel.add(sessionInfo, BorderLayout.EAST)
        }
        
        return panel
    }
    
    private fun setupAgentTabs() {
        val agents = listOf(
            "CoordinatorAgent" to "📋",
            "ContextAgent" to "🔍", 
            "TestWriterAgent" to "✍️",
            "ValidatorAgent" to "✅",
            "TestMergerAgent" to "🔀"
        )
        
        for ((agentName, icon) in agents) {
            val agentPanel = createAgentPanel(agentName)
            tabbedPane.addTab("$icon ${agentName.replace("Agent", "")}", agentPanel)
        }
        
        // Add a tab for LangChain4j memory/chat history
        val memoryPanel = createMemoryPanel()
        tabbedPane.addTab("💭 Chat Memory", memoryPanel)
        
        // Add a tab for combined timeline
        val timelinePanel = createTimelinePanel()
        tabbedPane.addTab("📅 Timeline", timelinePanel)
    }
    
    private fun createAgentPanel(agentName: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.background = UIUtil.getPanelBackground()
        
        val clearButton = JButton("Clear")
        clearButton.addActionListener {
            clearAgentHistory(agentName)
        }
        toolbar.add(clearButton)
        
        val exportButton = JButton("Export")
        exportButton.addActionListener {
            exportAgentHistory(agentName)
        }
        toolbar.add(exportButton)
        
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener {
            refreshAgentPanel(agentName, panel)
        }
        toolbar.add(refreshButton)
        
        panel.add(toolbar, BorderLayout.NORTH)
        
        // Content area with execution history
        val contentArea = createAgentContentArea(agentName)
        panel.add(contentArea, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createAgentContentArea(agentName: String): JComponent {
        val textArea = JBTextArea()
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        textArea.background = if (UIUtil.isUnderDarcula()) Color(43, 43, 43) else Color(250, 250, 250)
        
        // Load execution history for this agent
        val history = getAgentHistory(agentName)
        if (history.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("=== $agentName Execution History ===\n\n")
            
            for (entry in history) {
                sb.append("📍 Session: ${entry.sessionId.substring(0, 8)}...\n")
                sb.append("⏰ Time: ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(entry.timestamp)}\n")
                sb.append("📊 Phase: ${entry.phase}\n")
                sb.append("⏱️ Duration: ${entry.duration}ms\n")
                sb.append("\n--- Input ---\n")
                sb.append(entry.input.take(500))
                if (entry.input.length > 500) sb.append("\n... [truncated]")
                sb.append("\n\n--- Output ---\n")
                sb.append(entry.output.take(1000))
                if (entry.output.length > 1000) sb.append("\n... [truncated]")
                sb.append("\n\n" + "=".repeat(50) + "\n\n")
            }
            
            textArea.text = sb.toString()
        } else {
            textArea.text = "No execution history available for $agentName\n\n" +
                           "History will be populated when agents execute.\n\n" +
                           "Note: Since agents don't use streaming LLM directly,\n" +
                           "we capture their inputs/outputs through the service layer."
        }
        
        val scrollPane = JBScrollPane(textArea)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        
        return scrollPane
    }
    
    private fun createMemoryPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        val textArea = JBTextArea()
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        textArea.background = if (UIUtil.isUnderDarcula()) Color(43, 43, 43) else Color(250, 250, 250)
        
        // Display LangChain4j memory/chat history
        val memoryContent = StringBuilder()
        memoryContent.append("=== LangChain4j Chat Memory ===\n\n")
        memoryContent.append("📝 This would show the MessageWindowChatMemory content\n")
        memoryContent.append("   for each agent that uses LangChain4j.\n\n")
        memoryContent.append("Currently tracking:\n")
        memoryContent.append("- ContextAgent: Uses AiServices with MessageWindowChatMemory(10)\n")
        memoryContent.append("- Other agents: Use direct LLM calls (no memory)\n\n")
        memoryContent.append("To enable full debugging:\n")
        memoryContent.append("1. Add ChatMemoryStore to track all messages\n")
        memoryContent.append("2. Implement ChatModelListener for real-time monitoring\n")
        memoryContent.append("3. Use TokenCountEstimator for usage tracking\n")
        
        textArea.text = memoryContent.toString()
        
        val scrollPane = JBScrollPane(textArea)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createTimelinePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        
        val textArea = JBTextArea()
        textArea.isEditable = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        textArea.background = if (UIUtil.isUnderDarcula()) Color(43, 43, 43) else Color(250, 250, 250)
        
        // Create timeline of all agent executions
        val allEntries = mutableListOf<AgentExecutionEntry>()
        for ((_, entries) in agentExecutionHistory) {
            allEntries.addAll(entries)
        }
        allEntries.sortBy { it.timestamp }
        
        val sb = StringBuilder()
        sb.append("=== Agent Execution Timeline ===\n\n")
        
        if (allEntries.isNotEmpty()) {
            var lastTime = 0L
            for (entry in allEntries) {
                val timeDiff = if (lastTime > 0) entry.timestamp - lastTime else 0
                if (timeDiff > 0) {
                    sb.append("   ⏱️ [+${timeDiff}ms]\n")
                }
                
                val icon = when {
                    entry.agentName.contains("Coordinator") -> "📋"
                    entry.agentName.contains("Context") -> "🔍"
                    entry.agentName.contains("Writer") -> "✍️"
                    entry.agentName.contains("Validator") -> "✅"
                    entry.agentName.contains("Merger") -> "🔀"
                    else -> "🤖"
                }
                
                sb.append("$icon ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(entry.timestamp)}")
                sb.append(" - ${entry.agentName}: ${entry.phase}")
                sb.append(" (${entry.duration}ms)\n")
                
                lastTime = entry.timestamp
            }
        } else {
            sb.append("No agent executions recorded yet.\n")
        }
        
        textArea.text = sb.toString()
        
        val scrollPane = JBScrollPane(textArea)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createBottomPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(5, 10, 5, 10)
        
        val refreshAllButton = JButton("Refresh All")
        refreshAllButton.addActionListener {
            refreshAll()
        }
        panel.add(refreshAllButton)
        
        val clearAllButton = JButton("Clear All")
        clearAllButton.addActionListener {
            clearAllHistory()
        }
        panel.add(clearAllButton)
        
        val exportAllButton = JButton("Export All")
        exportAllButton.addActionListener {
            exportAllHistory()
        }
        panel.add(exportAllButton)
        
        return panel
    }
    
    private fun getAgentHistory(agentName: String): List<AgentExecutionEntry> {
        return agentExecutionHistory[agentName] ?: emptyList()
    }
    
    private fun clearAgentHistory(agentName: String) {
        agentExecutionHistory[agentName]?.clear()
        refreshAgentPanel(agentName, null)
    }
    
    private fun exportAgentHistory(agentName: String) {
        val history = getAgentHistory(agentName)
        if (history.isNotEmpty()) {
            val sb = StringBuilder()
            for (entry in history) {
                sb.append("Session: ${entry.sessionId}\n")
                sb.append("Time: ${entry.timestamp}\n")
                sb.append("Phase: ${entry.phase}\n")
                sb.append("Input: ${entry.input}\n")
                sb.append("Output: ${entry.output}\n")
                sb.append("Duration: ${entry.duration}ms\n")
                sb.append("\n---\n\n")
            }
            
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(sb.toString())
            clipboard.setContents(selection, selection)
            
            Messages.showInfoMessage(
                project,
                "Exported ${history.size} entries for $agentName to clipboard",
                "Export Complete"
            )
        } else {
            Messages.showInfoMessage(
                project,
                "No history to export for $agentName",
                "No History"
            )
        }
    }
    
    private fun refreshAgentPanel(agentName: String, panel: JPanel?) {
        // Refresh the content of a specific agent panel
        SwingUtilities.invokeLater {
            panel?.revalidate()
            panel?.repaint()
        }
    }
    
    private fun refreshAll() {
        SwingUtilities.invokeLater {
            for (i in 0 until tabbedPane.tabCount) {
                val component = tabbedPane.getComponentAt(i)
                component.revalidate()
                component.repaint()
            }
        }
    }
    
    private fun clearAllHistory() {
        val result = Messages.showYesNoDialog(
            project,
            "Clear all agent execution history?",
            "Clear History",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            agentExecutionHistory.clear()
            refreshAll()
        }
    }
    
    private fun exportAllHistory() {
        val sb = StringBuilder()
        sb.append("=== Complete Agent Execution History ===\n\n")
        
        for ((agentName, entries) in agentExecutionHistory) {
            sb.append("\n### $agentName ###\n\n")
            for (entry in entries) {
                sb.append("Session: ${entry.sessionId}\n")
                sb.append("Time: ${entry.timestamp}\n")
                sb.append("Phase: ${entry.phase}\n")
                sb.append("Duration: ${entry.duration}ms\n")
                sb.append("\n")
            }
        }
        
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(sb.toString())
        clipboard.setContents(selection, selection)
        
        Messages.showInfoMessage(
            project,
            "Exported all agent history to clipboard",
            "Export Complete"
        )
    }
    
    companion object {
        private val LOG = Logger.getInstance(AgentDebugDialog::class.java)

        // Store agent execution history globally
        private val agentExecutionHistory = mutableMapOf<String, MutableList<AgentExecutionEntry>>()

        data class AgentExecutionEntry(
            val timestamp: Long,
            val sessionId: String,
            val agentName: String,
            val phase: String,
            val input: String,
            val output: String,
            val duration: Long = 0,
            val metadata: Map<String, Any> = emptyMap()
        )
        /**
         * Record an agent execution for debugging
         */
        fun recordAgentExecution(
            sessionId: String,
            agentName: String,
            phase: String,
            input: String,
            output: String,
            duration: Long = 0,
            metadata: Map<String, Any> = emptyMap()
        ) {
            val entry = AgentExecutionEntry(
                System.currentTimeMillis(),
                sessionId,
                agentName,
                phase,
                input,
                output,
                duration,
                metadata
            )
            
            agentExecutionHistory.computeIfAbsent(agentName) { mutableListOf() }.add(entry)
            
            // Keep only last 100 entries per agent to avoid memory issues
            val history = agentExecutionHistory[agentName]
            if (history != null && history.size > 100) {
                history.removeAt(0)
            }
            
            LOG.debug("Recorded execution for $agentName: $phase")
        }
    }

}