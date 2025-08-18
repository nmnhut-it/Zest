package com.zps.zest.events

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.util.messages.Topic

/**
 * Listener for caret position changes in Zest context
 */
interface ZestCaretListener {
    fun caretPositionChanged(editor: Editor, event: CaretEvent) {}
    
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(ZestCaretListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
