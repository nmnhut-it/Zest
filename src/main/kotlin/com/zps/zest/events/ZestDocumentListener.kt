package com.zps.zest.events

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.util.messages.Topic

/**
 * Listener for document changes in Zest context
 */
interface ZestDocumentListener {
    fun documentOpened(document: Document, editor: Editor) {}
    fun documentClosed(document: Document, editor: Editor) {}
    fun documentChanged(document: Document, editor: Editor, event: DocumentEvent) {}
    
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(ZestDocumentListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
