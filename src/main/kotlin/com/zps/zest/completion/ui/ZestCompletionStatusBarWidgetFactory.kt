package com.zps.zest.completion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.jetbrains.annotations.Nls

/**
 * Factory for creating Zest completion status bar widgets
 */
class ZestCompletionStatusBarWidgetFactory : StatusBarWidgetFactory {
    
    override fun getId(): String = ZestCompletionStatusBarWidget.WIDGET_ID
    
    @Nls
    override fun getDisplayName(): String = "Zest Completion Status"
    
    override fun isAvailable(project: Project): Boolean = true
    
    override fun createWidget(project: Project): StatusBarWidget {
        return ZestCompletionStatusBarWidget(project)
    }
    
    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
    
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
