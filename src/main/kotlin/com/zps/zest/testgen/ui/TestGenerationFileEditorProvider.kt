package com.zps.zest.testgen.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * File editor provider for test generation sessions.
 * Opens TestGenerationEditor for .testgen virtual files.
 * Implements DumbAware to allow HIDE_DEFAULT_EDITOR policy.
 */
class TestGenerationFileEditorProvider : FileEditorProvider, DumbAware {
    
    override fun accept(project: Project, file: VirtualFile): Boolean {
        // Accept TestGenerationVirtualFile instances
        return file is TestGenerationVirtualFile || 
               file.extension == "testgen" ||
               file.fileType is TestGenerationFileType
    }
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        // Create the new state machine-based editor
        return if (file is TestGenerationVirtualFile) {
            StateMachineTestGenerationEditor(project, file)
        } else {
            // Fallback for other testgen files
            val virtualFile = TestGenerationVirtualFile(file.name, null)
            StateMachineTestGenerationEditor(project, virtualFile)
        }
    }
    
    override fun getEditorTypeId(): String {
        return "test-generation-editor"
    }
    
    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }
}