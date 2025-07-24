package com.zps.zest.completion.diff

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.*

/**
 * Simple diff view in a new editor tab with Accept/Reject buttons
 */
class SimpleDiffTab(
    private val project: Project,
    private val originalContent: String,
    private val modifiedContent: String,
    private val fileType: FileType,
    private val methodName: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) : FileEditor, UserDataHolderBase() {
    
    companion object {
        private val logger = Logger.getInstance(SimpleDiffTab::class.java)
        
        fun showDiff(
            project: Project,
            originalContent: String,
            modifiedContent: String,
            fileType: FileType,
            methodName: String,
            onAccept: () -> Unit,
            onReject: () -> Unit
        ) {
            val diffTab = SimpleDiffTab(
                project, originalContent, modifiedContent,
                fileType, methodName, onAccept, onReject
            )
            
            // Create a virtual file for the diff
            val virtualFile = DiffVirtualFile("$methodName - Method Rewrite", diffTab)
            
            // Open in editor
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }
    
    private val component: JComponent
    private val diffEditor: Editor
    
    init {
        component = JBPanel<JBPanel<*>>(BorderLayout())
        
        // Create header with buttons
        val headerPanel = createHeaderPanel()
        component.add(headerPanel, BorderLayout.NORTH)
        
        // Create diff editor
        diffEditor = createDiffEditor()
        component.add(JBScrollPane(diffEditor.component), BorderLayout.CENTER)
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = JBColor.PanelBackground
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(10)
        )
        
        // Title
        val titleLabel = JLabel("Method Rewrite: $methodName()")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        
        val acceptButton = JButton("Accept Changes")
        acceptButton.addActionListener {
            logger.info("User accepted method rewrite")
            onAccept()
            closeTab()
        }
        
        val rejectButton = JButton("Reject")
        rejectButton.addActionListener {
            logger.info("User rejected method rewrite")
            onReject()
            closeTab()
        }
        
        buttonPanel.add(acceptButton)
        buttonPanel.add(rejectButton)
        panel.add(buttonPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createDiffEditor(): Editor {
        // Create unified diff content
        val diffContent = createUnifiedDiff()
        
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(diffContent)
        val editor = editorFactory.createEditor(document, project, fileType, true) as EditorEx
        
        // Configure editor
        editor.isViewer = true
        val settings = editor.settings
        settings.isLineNumbersShown = true
        settings.isLineMarkerAreaShown = true
        settings.isFoldingOutlineShown = false
        
        // Apply syntax highlighting
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, fileType)
        editor.highlighter = highlighter
        
        // Highlight diff lines
        highlightDiffLines(editor)
        
        return editor
    }
    
    private fun createUnifiedDiff(): String {
        val originalLines = originalContent.lines()
        val modifiedLines = modifiedContent.lines()
        val diff = StringBuilder()
        
        diff.append("--- Original\n")
        diff.append("+++ AI Improved\n")
        diff.append("@@ Method: $methodName @@\n")
        
        // Simple line-by-line diff
        val maxLines = maxOf(originalLines.size, modifiedLines.size)
        
        for (i in 0 until maxLines) {
            val origLine = originalLines.getOrNull(i)
            val modLine = modifiedLines.getOrNull(i)
            
            when {
                origLine == null && modLine != null -> {
                    diff.append("+ $modLine\n")
                }
                origLine != null && modLine == null -> {
                    diff.append("- $origLine\n")
                }
                origLine != modLine -> {
                    diff.append("- $origLine\n")
                    diff.append("+ $modLine\n")
                }
                else -> {
                    diff.append("  $origLine\n")
                }
            }
        }
        
        return diff.toString()
    }
    
    private fun highlightDiffLines(editor: Editor) {
        val document = editor.document
        val markupModel = editor.markupModel
        
        var lineNum = 0
        for (i in 0 until document.lineCount) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, minOf(lineStart + 2, lineEnd)))
            
            val attributes = when {
                lineText.startsWith("+ ") -> com.intellij.openapi.editor.markup.TextAttributes().apply {
                    backgroundColor = JBColor(java.awt.Color(200, 255, 200), java.awt.Color(50, 80, 50))
                }
                lineText.startsWith("- ") -> com.intellij.openapi.editor.markup.TextAttributes().apply {
                    backgroundColor = JBColor(java.awt.Color(255, 200, 200), java.awt.Color(80, 50, 50))
                }
                lineText.startsWith("@@") -> com.intellij.openapi.editor.markup.TextAttributes().apply {
                    backgroundColor = JBColor(java.awt.Color(200, 200, 255), java.awt.Color(50, 50, 80))
                }
                else -> null
            }
            
            attributes?.let {
                markupModel.addRangeHighlighter(
                    lineStart, lineEnd,
                    com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
                    it,
                    com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
                )
            }
        }
    }
    
    private fun closeTab() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        
        for (file in openFiles) {
            if (file is DiffVirtualFile && file.diffTab == this) {
                fileEditorManager.closeFile(file)
                break
            }
        }
    }
    
    // FileEditor implementation
    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent? = diffEditor.contentComponent
    override fun getName(): String = "Method Rewrite"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(diffEditor)
    }
}

/**
 * Virtual file for diff tabs
 */
class DiffVirtualFile(
    private val name: String,
    val diffTab: SimpleDiffTab
) : VirtualFile() {
    
    override fun getName(): String = name
    override fun getFileSystem() = DiffFileSystem.instance
    override fun getPath(): String = "/$name"
    override fun isWritable(): Boolean = false
    override fun isDirectory(): Boolean = false
    override fun isValid(): Boolean = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile>? = null
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long) = throw UnsupportedOperationException()
    override fun contentsToByteArray(): ByteArray = ByteArray(0)
    override fun getTimeStamp(): Long = System.currentTimeMillis()
    override fun getLength(): Long = 0
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
    override fun getInputStream() = throw UnsupportedOperationException()
}

/**
 * Simple file system for diff virtual files
 */
object DiffFileSystem : com.intellij.openapi.vfs.VirtualFileSystem() {
    val instance = this
    
    override fun getProtocol(): String = "diff"
    override fun findFileByPath(path: String): VirtualFile? = null
    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
    override fun addVirtualFileListener(listener: com.intellij.openapi.vfs.VirtualFileListener) {}
    override fun removeVirtualFileListener(listener: com.intellij.openapi.vfs.VirtualFileListener) {}
    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {}
    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {}
    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {}
    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile = throw UnsupportedOperationException()
    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile = throw UnsupportedOperationException()
    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile = throw UnsupportedOperationException()
    override fun isReadOnly(): Boolean = true
}