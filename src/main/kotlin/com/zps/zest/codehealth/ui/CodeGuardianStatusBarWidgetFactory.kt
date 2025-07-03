package com.zps.zest.codehealth.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.jetbrains.annotations.Nls

/**
 * Factory for creating Code Guardian status bar widgets
 */
class CodeGuardianStatusBarWidgetFactory : StatusBarWidgetFactory {
    
    override fun getId(): String = CodeGuardianStatusBarWidget.WIDGET_ID
    
    override fun getDisplayName(): @Nls String = "Code Guardian Status"
    
    override fun isAvailable(project: Project): Boolean = true
    
    override fun createWidget(project: Project): StatusBarWidget {
        return CodeGuardianStatusBarWidget(project)
    }
    
    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
    
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
