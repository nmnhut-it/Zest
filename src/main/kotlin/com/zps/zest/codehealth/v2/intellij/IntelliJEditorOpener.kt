package com.zps.zest.codehealth.v2.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.zps.zest.codehealth.ui.editor.CodeHealthOverviewVirtualFile
import com.zps.zest.codehealth.v2.coordinator.EditorOpener

/**
 * IntelliJ implementation of EditorOpener.
 * Opens the Code Health Overview editor in the IDE.
 */
class IntelliJEditorOpener(private val project: Project) : EditorOpener {

    override fun openHealthOverviewEditor(): Boolean {
        if (project.isDisposed) {
            return false
        }

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val overviewFile = CodeHealthOverviewVirtualFile()
                val editorManager = FileEditorManagerEx.getInstanceEx(project)
                editorManager.openFile(overviewFile, true)
            }
        }

        return true
    }
}
