package com.zps.zest.codehealth.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.zps.zest.codehealth.CodeHealthReportStorage

/**
 * Factory for creating Code Guardian tool window
 */
class CodeGuardianToolWindowFactory : ToolWindowFactory, DumbAware {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentPanel = CodeGuardianReportPanel(project)
        
        // Load today's data if available
        val storage = CodeHealthReportStorage.getInstance(project)
        val todayData = storage.getReportForDate(java.time.LocalDate.now())
        if (todayData != null) {
            contentPanel.updateResults(todayData)
        }
        
        val content = ContentFactory.getInstance().createContent(contentPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
    
    override fun shouldBeAvailable(project: Project): Boolean = true
    
    companion object {
        const val TOOL_WINDOW_ID = "Code Guardian"
        
        fun getPanel(project: Project): CodeGuardianReportPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            val content = toolWindow?.contentManager?.getContent(0)
            return content?.component as? CodeGuardianReportPanel
        }
    }
}
