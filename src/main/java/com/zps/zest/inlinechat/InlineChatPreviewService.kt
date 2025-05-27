package com.zps.zest.inlinechat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBScrollPane
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * Service for showing inline chat preview with proper diff visualization
 */
@Service(Service.Level.PROJECT)
class InlineChatPreviewService(private val project: Project) {
    
    /**
     * Show a preview of the changes in a temporary overlay
     */
    fun showInlinePreview(editor: Editor, originalText: String, modifiedText: String, startLine: Int) {
        ApplicationManager.getApplication().invokeLater {
            // Create a temporary document with the modified text
            val tempDocument = EditorFactory.getInstance().createDocument(modifiedText)
            
            // Create a temporary editor for preview
            val previewEditor = EditorFactory.getInstance().createEditor(
                tempDocument,
                project,
                editor.virtualFile,
                false
            ) as EditorEx
            
            // Copy editor settings
            previewEditor.settings.apply {
                isLineNumbersShown = editor.settings.isLineNumbersShown
                isWhitespacesShown = editor.settings.isWhitespacesShown
                isIndentGuidesShown = editor.settings.isIndentGuidesShown
                isFoldingOutlineShown = editor.settings.isFoldingOutlineShown
            }
            
            // Apply syntax highlighting
            val fileType = getFileType(editor)
            if (fileType != null) {
                previewEditor.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(fileType, EditorColorsManager.getInstance().globalScheme, project)
            }
            
            // Apply diff highlighting to the preview
            applyDiffHighlighting(previewEditor, originalText, modifiedText)
            
            // Show in a dialog
            val dialog = InlineChatPreviewDialog(project, editor, previewEditor, originalText, modifiedText)
            dialog.show()
        }
    }
    
    /**
     * Show a side-by-side diff view
     */
    fun showSideBySideDiff(editor: Editor, originalText: String, modifiedText: String, title: String = "Inline Chat Preview") {
        ApplicationManager.getApplication().invokeLater {
            val diffContentFactory = DiffContentFactory.getInstance()
            
            // Create content for both sides
            val originalContent = diffContentFactory.create(project, originalText, editor.virtualFile)
            val modifiedContent = diffContentFactory.create(project, modifiedText, editor.virtualFile)
            
            // Set titles
//            originalContent.putUserData(DiffContentFactory.CONTENT_TITLE, "Original")
//            modifiedContent.putUserData(DiffContentFactory.CONTENT_TITLE, "AI Suggestion")
            
            // Create diff request
            val diffRequest = SimpleDiffRequest(
                title,
                originalContent,
                modifiedContent,
                "Original",
                "AI Suggestion"
            )
            
            // Show diff
            DiffManager.getInstance().showDiff(project, diffRequest)
        }
    }
    
    /**
     * Apply inline ghost text preview (overlay without replacing original)
     */
    fun showGhostTextPreview(editor: Editor, originalText: String, modifiedText: String, startOffset: Int) {
        val document = editor.document
        val markupModel = editor.markupModel
        
        // Parse the diff to find inserted lines
        val originalLines = originalText.split("\n")
        val modifiedLines = modifiedText.split("\n")
        
        // Simple diff - find inserted lines
        var origIndex = 0
        var modIndex = 0
        
        while (modIndex < modifiedLines.size) {
            if (origIndex >= originalLines.size || originalLines[origIndex] != modifiedLines[modIndex]) {
                // This is an inserted or modified line
                val insertOffset = if (origIndex < originalLines.size) {
                    document.getLineEndOffset(startOffset + origIndex)
                } else {
                    document.textLength
                }
                
                // Add ghost text as an inlay hint
                val inlayModel = editor.inlayModel
                val renderer = GhostTextRenderer(modifiedLines[modIndex])
                inlayModel.addBlockElement(insertOffset, true, false, 0, renderer)
                
                modIndex++
            } else {
                origIndex++
                modIndex++
            }
        }
    }
    
    private fun applyDiffHighlighting(editor: EditorEx, originalText: String, modifiedText: String) {
        val document = editor.document
        val markupModel = editor.markupModel
        
        // Get diff segments from InlineChatService
        val inlineChatService = project.getService(InlineChatService::class.java)
        val segments = inlineChatService.generateDiffSegments(originalText, modifiedText, 0)
        
        // Apply highlighting based on segments
        segments.forEach { segment ->
            if (segment.startLine < document.lineCount && segment.endLine < document.lineCount) {
                val startOffset = document.getLineStartOffset(segment.startLine)
                val endOffset = document.getLineEndOffset(segment.endLine)
                
                val attributes = when (segment.type) {
                    DiffSegmentType.INSERTED -> TextAttributes(
                        null,
                        Color(144, 238, 144, 80), // Light green
                        null,
                        null,
                        Font.PLAIN
                    )
                    DiffSegmentType.DELETED -> TextAttributes(
                        null,
                        Color(255, 192, 203, 80), // Light red
                        null,
                        null,
                        Font.PLAIN
                    )
                    DiffSegmentType.UNCHANGED -> null
                    DiffSegmentType.HEADER -> null
                    DiffSegmentType.FOOTER -> null
                    DiffSegmentType.COMMENT -> null
                }
                
                if (attributes != null) {
                    markupModel.addRangeHighlighter(
                        startOffset,
                        endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
            }
        }
    }
    
    private fun getFileType(editor: Editor): FileType? {
        return ReadAction.compute<FileType?, Throwable> {
            editor.virtualFile?.fileType
        }
    }
}

/**
 * Dialog for showing inline chat preview
 */
class InlineChatPreviewDialog(
    project: Project,
    private val originalEditor: Editor,
    private val previewEditor: EditorEx,
    private val originalText: String,
    private val modifiedText: String
) : DialogWrapper(project, true) {
    
    init {
        title = "Inline Chat Preview"
        setOKButtonText("Accept")
        setCancelButtonText("Reject")
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = java.awt.BorderLayout()
        
        // Add the preview editor
        val scrollPane = JBScrollPane(previewEditor.component)
        scrollPane.preferredSize = Dimension(800, 600)
        
        panel.add(scrollPane, java.awt.BorderLayout.CENTER)
        
        return panel
    }
    
    override fun doOKAction() {
        // Apply the changes
        ApplicationManager.getApplication().runWriteAction {
            val document = originalEditor.document
            val selectionModel = originalEditor.selectionModel
            
            if (selectionModel.hasSelection()) {
                document.replaceString(
                    selectionModel.selectionStart,
                    selectionModel.selectionEnd,
                    modifiedText
                )
            }
        }
        
        super.doOKAction()
    }
    
    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(previewEditor)
        super.dispose()
    }
}

/**
 * Renderer for ghost text (inline preview)
 */
class GhostTextRenderer(private val text: String) : com.intellij.openapi.editor.EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return editor.contentComponent.getFontMetrics(editor.contentComponent.font).stringWidth(text)
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }
    
    override fun paint(
        inlay: Inlay<*>,
        g: java.awt.Graphics,
        targetRegion: java.awt.Rectangle,
        textAttributes: TextAttributes
    ) {
        val editor = inlay.editor
        g.color = Color(0, 128, 0, 128) // Semi-transparent green
        g.font = editor.contentComponent.font.deriveFont(Font.ITALIC)
        g.drawString(text, targetRegion.x, targetRegion.y + editor.ascent)
    }
}
