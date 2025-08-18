package com.zps.zest.browser.editor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

/**
 * Provider for ZestChatEditor instances.
 * Registers the ability to open Zest Chat files in editor tabs.
 */
class ZestChatEditorProvider : FileEditorProvider, DumbAware {
    
    companion object {
        const val EDITOR_TYPE_ID = "zest-chat-editor"
        private const val SESSION_ID_ATTRIBUTE = "sessionId"
    }
    
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is ZestChatVirtualFileSystem.ZestChatVirtualFile || 
               file.path.startsWith("zest-chat://")
    }
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(accept(project, file)) { "Cannot create ZestChatEditor for file: ${file.path}" }
        
        val chatFile = if (file is ZestChatVirtualFileSystem.ZestChatVirtualFile) {
            file
        } else {
            // Create from path for restored sessions
            val sessionId = file.path.removePrefix("zest-chat://").removeSuffix(".zest-chat")
            ZestChatVirtualFileSystem.createChatFile(sessionId) as ZestChatVirtualFileSystem.ZestChatVirtualFile
        }
        
        return ZestChatEditor(project, chatFile)
    }
    
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID
    
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
    
    /**
     * Serializes the editor state for persistence across IDE restarts
     */
    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        if (state is ZestChatEditorState) {
            targetElement.setAttribute(SESSION_ID_ATTRIBUTE, state.sessionId)
        }
    }
    
    /**
     * Deserializes the editor state when restoring from persistence
     */
    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState {
        val sessionId = sourceElement.getAttributeValue(SESSION_ID_ATTRIBUTE) ?: "main"
        return ZestChatEditorState(sessionId)
    }
    
    /**
     * State class for persisting chat editor state
     */
    data class ZestChatEditorState(
        val sessionId: String
    ) : FileEditorState {
        
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return otherState is ZestChatEditorState && otherState.sessionId == sessionId
        }
    }
}