package com.zps.zest.browser.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.zps.zest.browser.JCEFBrowserManager
import com.zps.zest.browser.WebBrowserPanel
import com.zps.zest.browser.WebBrowserService
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Editor for Zest Chat sessions opened as full-screen editor tabs.
 * Integrates JCEF browser for web-based chat interface.
 */
class ZestChatEditor(
    private val project: Project,
    private val virtualFile: ZestChatVirtualFileSystem.ZestChatVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    companion object {
        private val LOG = Logger.getInstance(ZestChatEditor::class.java)
    }
    
    private val sessionId = virtualFile.getSessionId()
    private val component: JComponent
    private val browserPanel: WebBrowserPanel
    
    init {
        LOG.info("Creating ZestChatEditor for session: $sessionId")
        browserPanel = WebBrowserPanel(project)
        Disposer.register(this, browserPanel)
        component = createEditorComponent()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = browserPanel.component
    
    override fun getName(): String = "ZPS Chat"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {
        LOG.info("Disposing ZestChatEditor for session: $sessionId")
    }
    
    override fun setState(state: FileEditorState) {
        // No-op for chat editor
    }
    
    private fun createEditorComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = com.intellij.util.ui.UIUtil.getPanelBackground()
        
        // Add toolbar at top for chat-specific actions
        mainPanel.add(createChatToolbar(), BorderLayout.NORTH)
        
        // Add the browser panel (full JCEF integration)
        mainPanel.add(browserPanel.component, BorderLayout.CENTER)
        
        // Register this browser panel with the service so actions can find it
        WebBrowserService.getInstance(project).registerPanel(browserPanel)
        
        return mainPanel
    }
    
    private fun createChatToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Refresh action
        actionGroup.add(object : AnAction("Refresh Chat", "Refresh the chat interface", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshChat()
            }
        })
        
        // New chat session action
        actionGroup.add(object : AnAction("New Chat", "Start a new chat session", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                openNewChatSession()
            }
        })
        
        // Toggle dev tools action
        actionGroup.add(object : AnAction("Developer Tools", "Toggle browser developer tools", AllIcons.Toolwindows.ToolWindowDebugger) {
            override fun actionPerformed(e: AnActionEvent) {
                toggleDevTools()
            }
        })
        
        // Mode selector - Agent/Dev/Advice modes
        actionGroup.add(object : AnAction("Switch Mode", "Switch between Agent/Dev/Advice modes", AllIcons.Actions.Properties) {
            override fun actionPerformed(e: AnActionEvent) {
                switchChatMode()
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ZestChatEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun refreshChat() {
        ApplicationManager.getApplication().invokeLater {
            // Reload current URL to refresh chat
            val currentUrl = browserPanel.currentUrl
            if (currentUrl.isNotEmpty()) {
                browserPanel.loadUrl(currentUrl)
            }
            LOG.info("Chat refreshed for session: $sessionId")
        }
    }
    
    private fun openNewChatSession() {
        ApplicationManager.getApplication().invokeLater {
            // Generate new session ID
            val newSessionId = "chat-${System.currentTimeMillis()}"
            val newChatFile = ZestChatVirtualFileSystem.createChatFile(newSessionId)
            
            // Open new chat session in another editor tab
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openFile(newChatFile, true)
                
            LOG.info("Opening new chat session: $newSessionId")
        }
    }
    
    private fun toggleDevTools() {
        try {
            val success = browserPanel.toggleDevTools()
            LOG.info("Dev tools toggled: $success")
        } catch (e: Exception) {
            LOG.warn("Error toggling dev tools", e)
        }
    }
    
    private fun switchChatMode() {
        try {
            // For now, just switch to Agent mode - full mode switching would require more UI
            browserPanel.switchToAgentMode()
            LOG.info("Switched to Agent mode")
            
        } catch (e: Exception) {
            LOG.warn("Error switching chat mode", e)
        }
    }
    
    /**
     * Gets the browser panel for external access
     */
    fun getBrowserPanel(): WebBrowserPanel = browserPanel
    
    /**
     * Gets the session ID
     */
    fun getSessionId(): String = sessionId
    
    /**
     * Load a specific URL in this chat session
     */
    fun loadUrl(url: String) {
        browserPanel.loadUrl(url)
    }
}