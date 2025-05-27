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
 * Highlighting pass that shows the diff between original code and AI-suggested changes.
 * Enhanced to work with IntelliJ's diff fragments and provide better visualization.
 */
class DiffHighLightingPass(project: Project, document: Document, val editor: Editor) :
    TextEditorHighlightingPass(project, document, true), DumbAware {
    
    companion object {
        // Debug flag - set to true to enable debug output
        const val DEBUG_HIGHLIGHTING = true
    }

    private val logger = Logger.getInstance(DiffHighLightingPass::class.java)

    private val file = FileDocumentManager.getInstance().getFile(myDocument)
    private val highlights = mutableListOf<HighlightInfo>()
    private var lineAttributesMap = emptyMap<String, TextAttributes>()
    private var textAttributesMap = emptyMap<String, TextAttributes>()
    
    // Service reference to get diff data
    private val inlineChatService = project.getService(InlineChatService::class.java)

    init {
        colorsScheme = EditorColorsManager.getInstance().globalScheme
        
        // Enhanced color scheme for better visibility
        val headerColor = Color(64f / 255, 166f / 255, 1f, 0.5f)  // Bright blue with 50% opacity
        val insertColor = Color(0f, 128f / 255, 0f, 0.3f)         // Green with 30% opacity
        val deleteColor = Color(220f / 255, 20f / 255, 60f / 255, 0.3f)  // Crimson with 30% opacity
        val modifiedColor = Color(255f / 255, 165f / 255, 0f, 0.3f)  // Orange with 30% opacity
        
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
                Color(240, 240, 240, 20),  // Very light gray with very low opacity
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
            "modified" to TextAttributes(
                null,
                modifiedColor,
                null,
                null,
                0
            )
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
            "modified" to TextAttributes(
                Color(184, 134, 11),  // Dark goldenrod text
                Color(255, 223, 186, 80),  // Peach with low opacity
                null,
                null,
                0
            )
        )
    }

    override fun doCollectInformation(progress: ProgressIndicator) {
        if (DEBUG_HIGHLIGHTING) {
            System.out.println("=== DiffHighLightingPass.doCollectInformation ===")
        }
        
        // Use ReadAction to ensure thread safety
        ReadAction.run<Throwable> {
            val uri = file?.url ?: return@run
            val diffSegments = inlineChatService.diffSegments
            
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("File URI: $uri")
                System.out.println("Editor file URI: ${editor.virtualFile?.url}")
                System.out.println("DiffSegments count: ${diffSegments.size}")
                System.out.println("Document line count: ${myDocument.lineCount}")
                System.out.println("InlineChatService location: ${inlineChatService.location}")
            }
            
            // Check if this highlighting pass is for the same file that has the diff segments
            val currentFileUri = editor.virtualFile?.url
            val diffFileUri = inlineChatService.location?.uri
            
            if (currentFileUri != null && diffFileUri != null && currentFileUri != diffFileUri) {
                if (DEBUG_HIGHLIGHTING) {
                    System.out.println("  -> Skipping highlighting: Different file (current: $currentFileUri, diff: $diffFileUri)")
                }
                return@run
            }
            
            // Sort segments by line number to ensure correct processing
            val sortedSegments = diffSegments.sortedBy { it.startLine }
            
            // Keep track of processed lines to avoid overlapping highlights
            val processedLines = mutableSetOf<Int>()
            
            // Process segments in order
            sortedSegments.forEach { segment ->
                if (DEBUG_HIGHLIGHTING) {
                    System.out.println("Processing segment: ${segment.type} lines ${segment.startLine}-${segment.endLine}")
                }
                
                // Skip if these lines have already been processed
                val linesToProcess = (segment.startLine..segment.endLine).filter { !processedLines.contains(it) }
                if (linesToProcess.isEmpty()) {
                    if (DEBUG_HIGHLIGHTING) {
                        System.out.println("  -> Skipping, lines already processed")
                    }
                    return@forEach
                }
                
                when (segment.type) {
                    DiffSegmentType.UNCHANGED -> highlightSegment(segment, "unchanged", processedLines)
                    DiffSegmentType.INSERTED -> highlightSegment(segment, "inserted", processedLines)
                    DiffSegmentType.DELETED -> highlightSegment(segment, "deleted", processedLines)
                    DiffSegmentType.HEADER -> highlightSegment(segment, "header", processedLines)
                    DiffSegmentType.FOOTER -> highlightSegment(segment, "footer", processedLines)
                    DiffSegmentType.COMMENT -> highlightSegment(segment, "comments", processedLines)
                }
            }
            
            // Add subtle highlighting for modified lines (lines that were changed but not purely inserted/deleted)
            highlightModifiedLines(sortedSegments)
            
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("Total highlights collected: ${highlights.size}")
            }
        }
    }

    private fun highlightSegment(segment: DiffSegment, type: String, processedLines: MutableSet<Int>) {
        val startLine = segment.startLine
        val endLine = segment.endLine
        
        if (DEBUG_HIGHLIGHTING) {
            System.out.println("  highlightSegment: type=$type, lines=$startLine-$endLine")
        }
        
        // Validate line numbers
        if (startLine < 0 || endLine < 0 || startLine >= myDocument.lineCount || endLine >= myDocument.lineCount) {
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("    -> Invalid line numbers! Document has ${myDocument.lineCount} lines")
            }
            return
        }
        
        val startOffset = myDocument.getLineStartOffset(startLine)
        val endOffset = myDocument.getLineEndOffset(endLine)
        
        val textRange = TextRange(startOffset, endOffset)
        val attributes = when {
            type.startsWith("comments") -> lineAttributesMap["comments"] ?: return
            else -> lineAttributesMap[type] ?: return
        }
        
        if (DEBUG_HIGHLIGHTING) {
            System.out.println("    -> Creating highlight at offset $startOffset-$endOffset")
        }
        
        // Build highlight with descriptive tooltip
        val tooltip = when (segment.type) {
            DiffSegmentType.INSERTED -> "Added by AI suggestion"
            DiffSegmentType.DELETED -> "Removed by AI suggestion"
            DiffSegmentType.UNCHANGED -> "Unchanged"
            DiffSegmentType.HEADER -> "AI suggestion start"
            DiffSegmentType.FOOTER -> "AI suggestion end"
            DiffSegmentType.COMMENT -> "AI comment"
        }
        
        val builder = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(textRange)
            .textAttributes(attributes)
            .descriptionAndTooltip(tooltip)
            .severity(HighlightSeverity.TEXT_ATTRIBUTES)
        
        val highlight = builder.create()
        if (highlight != null) {
            highlights.add(highlight)
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("    -> Highlight created successfully")
            }
        } else {
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("    -> Failed to create highlight!")
            }
            return
        }
        
        // Mark lines as processed
        for (line in startLine..endLine) {
            processedLines.add(line)
        }
    }
    
    /**
     * Highlight lines that were modified (not purely inserted or deleted)
     */
    private fun highlightModifiedLines(segments: List<DiffSegment>) {
        // Look for patterns where a deletion is immediately followed by an insertion
        // This typically indicates a modification
        for (i in 0 until segments.size - 1) {
            val current = segments[i]
            val next = segments[i + 1]
            
            if (current.type == DiffSegmentType.DELETED && next.type == DiffSegmentType.INSERTED) {
                // Check if they're adjacent or very close
                if (kotlin.math.abs(current.endLine - next.startLine) <= 1) {
                    // This is likely a modification - add special highlighting
                    val modifiedRange = TextRange(
                        myDocument.getLineStartOffset(next.startLine.coerceIn(0, myDocument.lineCount - 1)),
                        myDocument.getLineEndOffset(next.endLine.coerceIn(0, myDocument.lineCount - 1))
                    )
                    
                    val builder = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
                        .range(modifiedRange)
                        .textAttributes(lineAttributesMap["modified"] ?: return)
                        .descriptionAndTooltip("Modified by AI suggestion")
                        .severity(HighlightSeverity.TEXT_ATTRIBUTES)
                    
                    val highlight = builder.create() ?: continue
                    highlights.add(highlight)
                }
            }
        }
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
            
            // Log highlighting statistics for debugging
            logger.debug("Applied ${highlights.size} diff highlights")
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