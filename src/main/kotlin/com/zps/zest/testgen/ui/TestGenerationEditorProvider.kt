package com.zps.zest.testgen.ui

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * File editor provider for test generation virtual files
 */
class TestGenerationEditorProvider : FileEditorProvider, DumbAware {
    
    companion object {
        const val EDITOR_TYPE_ID = "test-generation-editor"
    }
    
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is TestGenerationVirtualFile
    }
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is TestGenerationVirtualFile) { "Invalid file type: ${file.javaClass}" }
        return TestGenerationEditor(project, file)
    }
    
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID
    
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}