package com.zps.zest.inlinechat

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.awt.Color
import java.awt.Font

/**
 * Registers the diff highlighting pass factory
 */
class DiffHighlighterRegister : TextEditorHighlightingPassFactoryRegistrar {
    override fun registerHighlightingPassFactory(register: TextEditorHighlightingPassRegistrar, project: Project) {
        register.registerTextEditorHighlightingPass(
            DiffHighlightingPassFactory(), TextEditorHighlightingPassRegistrar.Anchor.FIRST,
            Pass.EXTERNAL_TOOLS, false, false
        )
    }
}

/**
 * Factory for creating diff highlighting passes
 */
class DiffHighlightingPassFactory : TextEditorHighlightingPassFactory {
    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (!file.isValid) {
            return null
        }
        return DiffHighLightingPass(file.project, editor.document, editor)
    }
}

/**
 * Highlighting pass that shows the diff between original code and AI-suggested changes
 */
class DiffHighLightingPass(project: Project, document: Document, val editor: Editor) :
    TextEditorHighlightingPass(project, document, true), DumbAware {

    private val logger = Logger.getInstance(DiffHighLightingPass::class.java)

    private val file = FileDocumentManager.getInstance().getFile(myDocument)
    private val highlights = mutableListOf<HighlightInfo>()
    private var lineAttributesMap = emptyMap<String, TextAttributes>()
    private var textAttributesMap = emptyMap<String, TextAttributes>()
    
    // Service reference to get diff data
    private val inlineChatService = project.getService(InlineChatService::class.java)

    init {
        colorsScheme = EditorColorsManager.getInstance().globalScheme
        val headerColor = Color(64f / 255, 166f / 255, 1f, 0.5f)  // Bright blue with 50% opacity
        val insertColor = Color(0f, 128f / 255, 0f, 0.3f)         // Green with 30% opacity
        val deleteColor = Color(220f / 255, 20f / 255, 60f / 255, 0.3f)  // Crimson with 30% opacity
        
        lineAttributesMap = mapOf(
            "header" to TextAttributes(
                Color(0, 0, 200),  // Blue text
                headerColor,
                null,
                null,
                Font.BOLD
            ),
            "footer" to TextAttributes(
                Color(0, 0, 200),  // Blue text
                headerColor,
                null,
                null,
                Font.BOLD
            ),
            "commentsFirstLine" to TextAttributes(
                Color(100, 100, 100),  // Gray text
                colorsScheme?.getColor(EditorColors.DOCUMENTATION_COLOR),
                null,
                null,
                Font.ITALIC
            ),
            "comments" to TextAttributes(
                Color(100, 100, 100),  // Gray text
                colorsScheme?.getColor(EditorColors.DOCUMENTATION_COLOR),
                null,
                null,
                Font.ITALIC
            ),
            "waiting" to TextAttributes(
                null,
                Color(200, 200, 200, 80),  // Light gray with low opacity
                null,
                null,
                0
            ),
            "inProgress" to TextAttributes(
                null, 
                Color(255, 215, 0, 80),  // Gold with low opacity
                null, 
                null, 
                0
            ),
            "unchanged" to TextAttributes(
                null, 
                Color(230, 230, 230, 40),  // Very light gray with low opacity
                null, 
                null, 
                0
            ),
            "inserted" to TextAttributes(
                null, 
                insertColor, 
                null, 
                null, 
                0
            ),
            "deleted" to TextAttributes(
                null, 
                deleteColor, 
                null, 
                null, 
                0
            ),
        )

        textAttributesMap = mapOf(
            "inserted" to TextAttributes(
                Color(0, 128, 0),  // Dark green text
                Color(144, 238, 144, 80),  // Light green with low opacity
                null, 
                null, 
                0
            ),
            "deleted" to TextAttributes(
                Color(220, 20, 60),  // Crimson red text
                Color(255, 192, 203, 80),  // Light pink with low opacity
                null,
                null,
                0
            ),
        )
    }

    override fun doCollectInformation(progress: ProgressIndicator) {
        // Use ReadAction to ensure thread safety
        ReadAction.run<Throwable> {
            val uri = file?.url ?: return@run
            val diffSegments = inlineChatService.diffSegments
            
            // Sort segments by line number to ensure correct processing
            val sortedSegments = diffSegments.sortedBy { it.startLine }
            
            // Process segments in order
            sortedSegments.forEach { segment ->
                when (segment.type) {
                    DiffSegmentType.UNCHANGED -> highlightSegment(segment, "unchanged")
                    DiffSegmentType.INSERTED -> highlightSegment(segment, "inserted")
                    DiffSegmentType.DELETED -> highlightSegment(segment, "deleted")
                    DiffSegmentType.HEADER -> highlightSegment(segment, "header")
                    DiffSegmentType.FOOTER -> highlightSegment(segment, "footer")
                    DiffSegmentType.COMMENT -> highlightSegment(segment, "comments")
                }
            }
        }
    }

    private fun highlightSegment(segment: DiffSegment, type: String) {
        val startLine = segment.startLine
        val endLine = segment.endLine
        val startOffset = if (startLine >= 0 && startLine < myDocument.lineCount) {
            myDocument.getLineStartOffset(startLine)
        } else {
            return
        }
        
        val endOffset = if (endLine >= 0 && endLine < myDocument.lineCount) {
            myDocument.getLineEndOffset(endLine)
        } else {
            return
        }
        
        val textRange = TextRange(startOffset, endOffset)
        val attributes = when {
            type.startsWith("comments") -> lineAttributesMap["comments"] ?: return
            else -> lineAttributesMap[type] ?: return
        }
        
        val builder = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(textRange)
            .textAttributes(attributes)
            .descriptionAndTooltip("Zest inline diff")
            .severity(HighlightSeverity.TEXT_ATTRIBUTES)
        
        val highlight = builder.create() ?: return
        highlights.add(highlight)
    }

    override fun doApplyInformationToEditor() {
        // First, clear any existing highlighters for this pass
        UpdateHighlightersUtil.setHighlightersToEditor(
            myProject, myDocument, 0, 0,
            emptyList(), colorsScheme, id
        )
        
        // Only apply highlights if we have any
        if (highlights.isNotEmpty()) {
            // Find the min and max range to only update the affected part of the document
            val minOffset = highlights.minOfOrNull { it.startOffset } ?: 0
            val maxOffset = highlights.maxOfOrNull { it.endOffset } ?: myDocument.textLength
            
            // Apply only in the range that contains our highlights
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject, myDocument, minOffset, maxOffset,
                highlights, colorsScheme, id
            )
        }
    }
}

/**
 * Type of diff segment
 */
enum class DiffSegmentType {
    UNCHANGED,
    INSERTED,
    DELETED,
    HEADER,
    FOOTER,
    COMMENT
}

/**
 * Represents a segment of a diff
 */
data class DiffSegment(
    val startLine: Int,
    val endLine: Int,
    val type: DiffSegmentType,
    val content: String
)