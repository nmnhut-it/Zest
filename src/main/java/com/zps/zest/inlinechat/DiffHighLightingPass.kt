package com.zps.zest.inlinechat

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.JBColor
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
        const val DEBUG_HIGHLIGHTING = false
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
        
        // Simple color scheme for better visibility - use JBColor for theme support
        val insertColor = JBColor(Color(198, 255, 198), Color(59, 91, 59))  // Light green / dark green
        val deleteColor = JBColor(Color(255, 220, 220), Color(91, 59, 59))  // Light red / dark red
        val unchangedColor = null  // No background for unchanged
        
        lineAttributesMap = mapOf(
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
            "unchanged" to TextAttributes(
                null, 
                unchangedColor, 
                null, 
                null, 
                0
            ),
            "header" to TextAttributes(
                null,
                null,
                null,
                null,
                0
            ),
            "footer" to TextAttributes(
                null,
                null,
                null,
                null,
                0
            ),
            "comments" to TextAttributes(
                null,
                null,
                null,
                null,
                Font.ITALIC
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
        
        // Skip highlighting if we're using floating window mode
        val inlineChatService = project.getService(InlineChatService::class.java)
        if (inlineChatService.floatingCodeWindow != null || inlineChatService.diffSegments.isEmpty()) {
            if (DEBUG_HIGHLIGHTING) {
                System.out.println("  -> Skipping highlighting: Floating window is active or no diff segments")
            }
            return
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

    override fun doApplyInformationToEditor() {
        // This method is called on EDT, so we don't need to wrap in invokeLater
        
        // First, clear any existing highlighters for this pass
        try {
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject, myDocument, 0, myDocument.textLength,
                emptyList(), colorsScheme, id
            )
            
            // Check the state to see if we should apply highlights - use ReadAction for service access
            val inlineChatService = ApplicationManager.getApplication().runReadAction<InlineChatService?> {
                try {
                    myProject.getService(InlineChatService::class.java)
                } catch (e: Exception) {
                    null
                }
            }
            
            // Skip applying highlights if service indicates we're in a clean state
            val skipHighlights = inlineChatService?.diffSegments?.isEmpty() == true
            
            // Only apply highlights if we have any and aren't in a clean state
            if (highlights.isNotEmpty() && !skipHighlights) {
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
            } else {
                if (DEBUG_HIGHLIGHTING) {
                    System.out.println("No highlights to apply - cleared all existing highlights")
                    System.out.println("Skip condition: ${inlineChatService?.diffSegments?.isEmpty() == true}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error applying highlighting", e)
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