package com.zps.zest.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.project.Project
import com.zps.zest.util.safeSyncPublisher

/**
 * Factory listener that sets up event handling for Zest completion features
 */
class ZestEditorFactoryListener : com.intellij.openapi.editor.event.EditorFactoryListener {
    private val logger = Logger.getInstance(ZestEditorFactoryListener::class.java)
    private val listeners = mutableMapOf<Editor, Disposable>()
    
    override fun editorCreated(event: EditorFactoryEvent) {
        System.out.println("ZestEditorFactoryListener: editorCreated $event")
        val editor = event.editor
        val project = editor.project ?: return
        
        // Notify document opened
        project.safeSyncPublisher(ZestDocumentListener.TOPIC)?.documentOpened(editor.document, editor)
        
        // Set up listeners for this editor
        setupEditorListeners(editor, project)
    }
    
    override fun editorReleased(event: EditorFactoryEvent) {
        System.out.println("ZestEditorFactoryListener: editorReleased $event")
        val editor = event.editor
        val project = editor.project ?: return
        
        // Notify document closed
        project.safeSyncPublisher(ZestDocumentListener.TOPIC)?.documentClosed(editor.document, editor)
        
        // Clean up listeners
        listeners[editor]?.dispose()
        listeners.remove(editor)
    }
    
    private fun setupEditorListeners(editor: Editor, project: Project) {
        val caretListener = object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
//                System.out.println("CaretListener: caretPositionChanged $editor $event")
                project.safeSyncPublisher(ZestCaretListener.TOPIC)?.caretPositionChanged(editor, event)
            }
        }
        
        val documentListener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
//                System.out.println("DocumentListener: documentChanged $editor $event")
                project.safeSyncPublisher(ZestDocumentListener.TOPIC)?.documentChanged(editor.document, editor, event)
            }
        }
        
        // Register listeners
        editor.caretModel.addCaretListener(caretListener)
        editor.document.addDocumentListener(documentListener)
        
        // Store disposable for cleanup
        listeners[editor] = Disposable {
            editor.caretModel.removeCaretListener(caretListener)
            editor.document.removeDocumentListener(documentListener)
        }
    }
}
