package com.zps.zest.inlinechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Project activity that adds a gutter icon when text is selected
 */
class SelectionGutterIconManager : ProjectActivity {
    // Use the plugin icon as the gutter icon
    private val selectionIcon: Icon by lazy {
        IconLoader.getIcon("/META-INF/pluginIcon.svg", SelectionGutterIconManager::class.java)
    }
    
    // Special icon for when TODOs are detected
    private val todoIcon: Icon by lazy {
        AllIcons.General.TodoDefault
    }

    private val editorToHighlighter = mutableMapOf<Editor, RangeHighlighter>()
    private var activeEditor: Editor? = null
    private var currentSelectionListener: SelectionListener? = null

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val editor = source.selectedTextEditor
                    editor?.let { setActiveEditor(it) }
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val editors = source.getEditors(file)
                    for (fileEditor in editors) {
                        if (fileEditor is TextEditor) {
                            val editor = fileEditor.editor
                            if (editor == activeEditor) {
                                removeSelectionListener(editor)
                                removeHighlighter(editor)
                                activeEditor = null
                            }
                        }
                    }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.newEditor != null) {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor
                        if (editor != null && editor != activeEditor) {
                            activeEditor?.let {
                                removeSelectionListener(it)
                                removeHighlighter(it)
                            }
                            setActiveEditor(editor)
                        }
                    }
                }
            }
        )

        FileEditorManager.getInstance(project).selectedTextEditor?.let {
            setActiveEditor(it)
        }
    }

    private fun setActiveEditor(editor: Editor) {
        activeEditor = editor
        addSelectionListener(editor)
        updateSelectionGutterIcon(editor)
    }

    private fun addSelectionListener(editor: Editor) {
        currentSelectionListener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                updateSelectionGutterIcon(editor)
            }
        }
        editor.selectionModel.addSelectionListener(currentSelectionListener!!)
    }

    private fun removeSelectionListener(editor: Editor) {
        currentSelectionListener?.let {
            editor.selectionModel.removeSelectionListener(it)
            currentSelectionListener = null
        }
    }

    private fun updateSelectionGutterIcon(editor: Editor) {
        invokeLater {
            removeHighlighter(editor)

            // Wrap in a read action to avoid access violations
            val hasSelection = ReadAction.compute<Boolean, RuntimeException> { 
                editor.selectionModel.hasSelection() 
            }
            
            if (!hasSelection) return@invokeLater

            val selectionStart = ReadAction.compute<Int, RuntimeException> { 
                editor.selectionModel.selectionStart 
            }
            
            val lineNumber = ReadAction.compute<Int, RuntimeException> { 
                editor.document.getLineNumber(selectionStart) 
            }
            
            val lineStart = ReadAction.compute<Int, RuntimeException> { 
                editor.document.getLineStartOffset(lineNumber) 
            }
            
            // Check if selection contains TODOs
            val (icon, tooltip) = ReadAction.compute<Pair<Icon, String>, RuntimeException> {
                val selectedText = editor.selectionModel.selectedText ?: ""
                if (ZestContextProvider.containsTodos(selectedText)) {
                    val todos = ZestContextProvider.extractTodos(selectedText)
                    val todoCount = todos.size
                    Pair(todoIcon, "Open Zest inline AI - $todoCount TODO(s) detected")
                } else {
                    Pair(selectionIcon, "Open Zest inline AI")
                }
            }

            val markupModel = editor.markupModel
            val highlighter = markupModel.addRangeHighlighter(
                lineStart, lineStart + 1,
                HighlighterLayer.LAST,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )

            highlighter.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon(): Icon = icon

                override fun equals(other: Any?): Boolean {
                    return other is GutterIconRenderer && other.javaClass == this.javaClass
                }

                override fun hashCode(): Int = javaClass.hashCode()

                override fun getTooltipText(): String = tooltip

                override fun getClickAction(): AnAction {
                    return InlineChatAction()
                }
            }

            editorToHighlighter[editor] = highlighter
        }
    }

    private fun removeHighlighter(editor: Editor) {
        editorToHighlighter.remove(editor)?.let { highlighter ->
            if (highlighter.isValid) {
                editor.markupModel.removeHighlighter(highlighter)
            }
        }
    }
}