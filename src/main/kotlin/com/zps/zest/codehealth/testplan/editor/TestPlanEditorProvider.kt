package com.zps.zest.codehealth.testplan.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.zps.zest.codehealth.testplan.TestPlanOverviewVirtualFile
import com.zps.zest.codehealth.testplan.TestPlanVirtualFile
import com.zps.zest.codehealth.testplan.TestPlanVirtualFileSystem

/**
 * Editor provider for test plan virtual files
 */
class TestPlanEditorProvider : FileEditorProvider, DumbAware {
    
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileSystem is TestPlanVirtualFileSystem
    }
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return when (file) {
            is TestPlanVirtualFile -> TestPlanEditor(project, file)
            is TestPlanOverviewVirtualFile -> TestPlanOverviewEditor(project, file)
            else -> throw IllegalArgumentException("Unsupported file type: ${file.javaClass}")
        }
    }
    
    override fun getEditorTypeId(): String = "test-plan-editor"
    
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}