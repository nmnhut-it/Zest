package com.zps.zest.codehealth.ui.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Editor provider for Code Health virtual files
 */
class CodeHealthEditorProvider : FileEditorProvider, DumbAware {
    
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileSystem is CodeHealthVirtualFileSystem
    }
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return when (file) {
            is CodeHealthIssueVirtualFile -> CodeHealthIssueEditor(project, file)
            is CodeHealthOverviewVirtualFile -> CodeHealthOverviewEditor(project, file)
            else -> throw IllegalArgumentException("Unsupported file type: ${file.javaClass}")
        }
    }
    
    override fun getEditorTypeId(): String = "code-health-editor"
    
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}