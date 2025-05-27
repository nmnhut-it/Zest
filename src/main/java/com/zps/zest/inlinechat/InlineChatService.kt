package com.zps.zest.inlinechat

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Location

/**
 * Service for managing inline chat state and diff information.
 * Uses IntelliJ's built-in diff system for accurate code comparison.
 */
@Service(Service.Level.PROJECT)
class InlineChatService(private val project: Project) : Disposable {

    companion object {
        // Debug flags - set to true to enable debug output
        const val DEBUG_SERVICE = false
        const val DEBUG_DIFF_SEGMENTS = false
        const val DEBUG_CODE_EXTRACTION = false
    }

    var inlineChatInputVisible = false
    var inlineChatDiffActionState = mutableMapOf<String, Boolean>()
    var location: Location? = null
    var selectionStartLine: Int = 0
    var selectionStartOffset: Int = 0
    var selectionEndOffset: Int = 0

    // Store LLM response for reference
    var llmResponse: String? = null

    // Store extracted code (if any) from the response
    var extractedCode: String? = null

    // Store diff segments for highlighting
    var diffSegments = mutableListOf<DiffSegment>()

    // Keep track of original code to generate diff
    var originalCode: String? = null

    // Preview manager for inline preview
    var editorPreview: Any? = null

    // Current floating toolbar (if shown)
    var floatingToolbar: InlineChatFloatingToolbar? = null

    val hasDiffAction: Boolean
        get() = inlineChatDiffActionState.any { it.value == true }

    /**
     * Parse the LLM response and update the diff segments
     */
    fun processLlmResponse(response: String, originalText: String, selectionStartLine: Int = 0) {
        if (DEBUG_SERVICE) {
            System.out.println("=== InlineChatService.processLlmResponse ===")
            System.out.println("Original text length: ${originalText.length}")
            System.out.println("Response length: ${response.length}")
            System.out.println("Selection start line: $selectionStartLine")
            System.out.println("Response preview: ${response.take(200)}...")
        }

        llmResponse = response
        originalCode = originalText
        this.selectionStartLine = selectionStartLine  // Store the selection start line

        // Extract code from the response
        extractedCode = extractCodeFromResponse(response)

        if (DEBUG_SERVICE) {
            System.out.println("Extracted code: ${extractedCode?.take(200) ?: "NULL"}")
        }

        // Generate diff segments
        if (extractedCode != null) {
            diffSegments = generateDiffSegments(originalText, extractedCode!!, selectionStartLine)

            if (DEBUG_SERVICE) {
                System.out.println("Generated ${diffSegments.size} diff segments")
                System.out.println("Setting diff action states to true")
            }

            // Enable diff actions
            inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
            inlineChatDiffActionState["Zest.InlineChat.Discard"] = true

            if (DEBUG_SERVICE) {
                System.out.println("Diff action states: $inlineChatDiffActionState")
                System.out.println("Selection start line for Code Vision: $selectionStartLine")
            }

            // Notify Code Vision providers about the change
            ApplicationManager.getApplication().invokeLater {
                if (DEBUG_SERVICE) {
                    System.out.println("Triggering DaemonCodeAnalyzer restart for Code Vision update")
                }
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()

                // Show floating toolbar
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    if (DEBUG_SERVICE) {
                        System.out.println("Showing floating toolbar for Accept/Reject actions")
                    }
                    floatingToolbar = InlineChatFloatingToolbar(project, editor)
                    floatingToolbar?.show()
                }
            }
        } else {
            if (DEBUG_SERVICE) {
                System.out.println("No code extracted from response!")
            }
        }
    }

    /**
     * Extract code blocks from a markdown response
     */
    private fun extractCodeFromResponse(response: String): String? {
        if (DEBUG_CODE_EXTRACTION) {
            System.out.println("=== InlineChatService.extractCodeFromResponse ===")
            System.out.println("Looking for code blocks in response...")
        }

        val codeBlockRegex = Regex("```(?:java)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(response)

        if (DEBUG_CODE_EXTRACTION) {
            System.out.println("Regex match found: ${match != null}")
            if (match != null) {
                System.out.println("Match groups count: ${match.groups.size}")
                System.out.println("Extracted code preview: ${match.groups[1]?.value?.take(100)}")
            }
        }

        return match?.groups?.get(1)?.value?.trim()
    }

    /**
     * Generate diff segments by comparing original and new code using IntelliJ's diff API
     */
    fun generateDiffSegments(
        original: String,
        modified: String,
        selectionStartLine: Int = 0
    ): MutableList<DiffSegment> {
        if (DEBUG_DIFF_SEGMENTS) {
            System.out.println("=== InlineChatService.generateDiffSegments ===")
            System.out.println("Original lines: ${original.split("\n").size}")
            System.out.println("Modified lines: ${modified.split("\n").size}")
            System.out.println("Selection start line: $selectionStartLine")
        }

        val segments = mutableListOf<DiffSegment>()

        try {
            // Use IntelliJ's ComparisonManager for accurate diff calculation
            val comparisonManager = ComparisonManager.getInstance()
            val lineFragments = comparisonManager.compareLines(
                original,
                modified,
                ComparisonPolicy.DEFAULT,
                DumbProgressIndicator.INSTANCE
            )

            if (DEBUG_DIFF_SEGMENTS) {
                System.out.println("ComparisonManager returned ${lineFragments.size} fragments")
            }

            // Convert LineFragments to DiffSegments
            var currentLine = 0

            // Add header segment at the selection start
            segments.add(
                DiffSegment(
                    selectionStartLine,
                    selectionStartLine,
                    DiffSegmentType.HEADER,
                    "AI Suggested Changes"
                )
            )

            for ((index, fragment) in lineFragments.withIndex()) {
                val startLine1 = fragment.startLine1
                val endLine1 = fragment.endLine1
                val startLine2 = fragment.startLine2
                val endLine2 = fragment.endLine2

                if (DEBUG_DIFF_SEGMENTS) {
                    System.out.println("Fragment $index: original[$startLine1-$endLine1] -> modified[$startLine2-$endLine2]")
                }

                // Handle unchanged lines before this fragment
                if (currentLine < startLine2) {
                    // These lines are unchanged
                    for (line in currentLine until startLine2) {
                        segments.add(
                            DiffSegment(
                                selectionStartLine + line,
                                selectionStartLine + line,
                                DiffSegmentType.UNCHANGED,
                                getLineContent(modified, line)
                            )
                        )
                    }
                }

                // Process the changed fragment
                when {
                    // Pure insertion (no lines in original, lines in modified)
                    startLine1 == endLine1 && startLine2 < endLine2 -> {
                        if (DEBUG_DIFF_SEGMENTS) {
                            System.out.println("  -> INSERTION at lines $startLine2-$endLine2")
                        }
                        for (line in startLine2 until endLine2) {
                            segments.add(
                                DiffSegment(
                                    selectionStartLine + line,
                                    selectionStartLine + line,
                                    DiffSegmentType.INSERTED,
                                    getLineContent(modified, line)
                                )
                            )
                        }
                    }
                    // Pure deletion (lines in original, no lines in modified)
                    startLine1 < endLine1 && startLine2 == endLine2 -> {
                        if (DEBUG_DIFF_SEGMENTS) {
                            System.out.println("  -> DELETION of ${endLine1 - startLine1} lines")
                        }
                        // For deletions, we add a marker at the current position
                        segments.add(
                            DiffSegment(
                                selectionStartLine + startLine2,
                                selectionStartLine + startLine2,
                                DiffSegmentType.DELETED,
                                "// ${endLine1 - startLine1} line(s) deleted"
                            )
                        )
                    }
                    // Modification (both have lines)
                    else -> {
                        if (DEBUG_DIFF_SEGMENTS) {
                            System.out.println("  -> MODIFICATION")
                        }
                        // Show the modified lines as insertions
                        for (line in startLine2 until endLine2) {
                            segments.add(
                                DiffSegment(
                                    selectionStartLine + line,
                                    selectionStartLine + line,
                                    DiffSegmentType.INSERTED,
                                    getLineContent(modified, line)
                                )
                            )
                        }
                    }
                }

                currentLine = endLine2
            }

            // Handle any remaining unchanged lines
            val totalLines = modified.split("\n").size
            if (currentLine < totalLines) {
                for (line in currentLine until totalLines) {
                    segments.add(
                        DiffSegment(
                            selectionStartLine + line,
                            selectionStartLine + line,
                            DiffSegmentType.UNCHANGED,
                            getLineContent(modified, line)
                        )
                    )
                }
            }

            // Add footer segment
            val lastLine = segments.lastOrNull()?.endLine ?: selectionStartLine
            segments.add(
                DiffSegment(
                    lastLine + 1,
                    lastLine + 1,
                    DiffSegmentType.FOOTER,
                    "End of changes"
                )
            )

            if (DEBUG_DIFF_SEGMENTS) {
                System.out.println("Final segment count: ${segments.size}")
                segments.forEachIndexed { idx, segment ->
                    System.out.println("  Segment $idx: ${segment.type} lines ${segment.startLine}-${segment.endLine}")
                }
            }

        } catch (e: Exception) {
            if (DEBUG_DIFF_SEGMENTS) {
                System.out.println("ERROR in diff generation: ${e.message}")
                e.printStackTrace()
            }
            // Fallback to simple line-by-line comparison if diff API fails
            segments.addAll(generateSimpleDiffSegments(original, modified, selectionStartLine))
        }

        return segments
    }

    /**
     * Get the content of a specific line from text
     */
    private fun getLineContent(text: String, lineIndex: Int): String {
        val lines = text.split("\n")
        return if (lineIndex in lines.indices) {
            lines[lineIndex]
        } else {
            if (DEBUG_DIFF_SEGMENTS) {
                System.out.println("WARNING: Line index $lineIndex out of bounds (text has ${lines.size} lines)")
            }
            ""
        }
    }

    /**
     * Fallback simple diff generation (original implementation)
     */
    private fun generateSimpleDiffSegments(
        original: String,
        modified: String,
        selectionStartLine: Int = 0
    ): List<DiffSegment> {
        val segments = mutableListOf<DiffSegment>()

        // Add header segment
        segments.add(
            DiffSegment(
                selectionStartLine,
                selectionStartLine,
                DiffSegmentType.HEADER,
                "AI Suggested Changes"
            )
        )

        // Split into lines
        val originalLines = original.split("\n")
        val modifiedLines = modified.split("\n")

        // Simple line-by-line comparison
        var currentLine = selectionStartLine
        var i = 0
        var j = 0

        while (i < originalLines.size || j < modifiedLines.size) {
            when {
                i >= originalLines.size -> {
                    // Remaining lines in modified are all insertions
                    val startLine = currentLine
                    val content = StringBuilder()

                    while (j < modifiedLines.size) {
                        content.append(modifiedLines[j]).append("\n")
                        j++
                        currentLine++
                    }

                    segments.add(DiffSegment(startLine, currentLine - 1, DiffSegmentType.INSERTED, content.toString()))
                }

                j >= modifiedLines.size -> {
                    // Remaining lines in original are all deletions
                    // Skip deletions as they don't appear in the current file
                    i = originalLines.size
                }

                originalLines[i] == modifiedLines[j] -> {
                    // Matching lines
                    segments.add(DiffSegment(currentLine, currentLine, DiffSegmentType.UNCHANGED, originalLines[i]))
                    i++
                    j++
                    currentLine++
                }

                else -> {
                    // Different lines - mark as insertion
                    segments.add(DiffSegment(currentLine, currentLine, DiffSegmentType.INSERTED, modifiedLines[j]))
                    j++
                    currentLine++

                    // Check if the original line appears later
                    var found = false
                    for (k in j until modifiedLines.size) {
                        if (originalLines[i] == modifiedLines[k]) {
                            found = true
                            break
                        }
                    }

                    if (!found) {
                        // Original line was removed, skip it
                        i++
                    }
                }
            }
        }

        // Add footer segment
        segments.add(DiffSegment(currentLine, currentLine, DiffSegmentType.FOOTER, "End of changes"))

        return segments
    }

    /**
     * Apply the suggested changes to the document
     */
    fun applyChanges(): String? {
        return extractedCode
    }

    /**
     * Clear the current state
     */
    fun clearState() {
        if (DEBUG_SERVICE) {
            System.out.println("=== InlineChatService.clearState ===")
        }
        llmResponse = null
        extractedCode = null
        diffSegments.clear()

        // Make sure to fully clear the diff action state map
        inlineChatDiffActionState.clear()
        inlineChatDiffActionState["Zest.InlineChat.Accept"] = false
        inlineChatDiffActionState["Zest.InlineChat.Discard"] = false

        originalCode = null
        selectionStartLine = 0
        selectionStartOffset = 0
        selectionEndOffset = 0
        editorPreview = null
        location = null
        inlineChatInputVisible = false

        // Hide floating toolbar if shown - only do this when the task is done or cancelled
        // NOT when it's still in progress
        if (hasDiffAction || inlineChatInputVisible) {
            // If we have active diff actions or input visible, keep the toolbar
        } else {
            // Otherwise, hide it
            floatingToolbar?.hide()
            floatingToolbar = null
        }

        if (DEBUG_SERVICE) {
            System.out.println("All state cleared - diffSegments: ${diffSegments.size}, diffActionState: ${inlineChatDiffActionState.size}")
            System.out.println("hasDiffAction after clearing: ${hasDiffAction}")
        }
    }

    /**
     * Force clear all diff highlights from the editor
     */
    fun forceClearAllHighlights() {
        if (DEBUG_SERVICE) {
            System.out.println("=== InlineChatService.forceClearAllHighlights ===")
        }

        // Clear all state first - state clearing is safe to do from any thread
        clearState()

        // All UI operations must be done on EDT - use invokeAndWait to ensure completion
        if (ApplicationManager.getApplication().isDispatchThread) {
            // Already on EDT, execute directly
            performClearHighlights()
        } else {
            // Not on EDT, use invokeAndWait
            ApplicationManager.getApplication().invokeAndWait {
                performClearHighlights()
            }
        }
    }

    /**
     * Helper method to perform the actual highlight clearing on EDT
     */
    private fun performClearHighlights() {
        try {
            // Get the current editor - this is a read operation
            val editor = ApplicationManager.getApplication().runReadAction<Editor?> {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            } ?: return

            // Force DaemonCodeAnalyzer to restart completely on EDT
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()

            // Clear all highlighters manually - READ operations first, then UI modifications
            val highlightersToRemove = ApplicationManager.getApplication().runReadAction<List<RangeHighlighter>> {
                try {
                    val markupModel = editor.markupModel
                    val highlighters = markupModel.allHighlighters

                    // Find highlighters that match our criteria
                    highlighters.filter { highlighter ->
                        if (!highlighter.isValid) return@filter false

                        // Check if it's one of our layers or if it has one of our tooltips
                        val tooltip = highlighter.errorStripeTooltip
                        (
                                highlighter.layer == com.intellij.openapi.editor.markup.HighlighterLayer.ADDITIONAL_SYNTAX ||
                                        highlighter.layer == com.intellij.openapi.editor.markup.HighlighterLayer.LAST ||
                                        highlighter.layer == com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION + 1 ||
                                        (tooltip is String && (
                                                tooltip.contains("AI suggestion") ||
                                                        tooltip.contains("Added by AI") ||
                                                        tooltip.contains("Removed by AI") ||
                                                        tooltip.contains("Unchanged") ||
                                                        tooltip.contains("AI comment")
                                                ))
                                )
                    }
                } catch (e: Exception) {
                    if (DEBUG_SERVICE) {
                        System.out.println("Error finding highlighters: ${e.message}")
                    }
                    emptyList()
                }
            }

            // Now remove the highlighters we found - this is a UI operation
            highlightersToRemove.forEach { highlighter ->
                try {
                    if (highlighter.isValid) {
                        editor.markupModel.removeHighlighter(highlighter)
                        if (DEBUG_SERVICE) {
                            System.out.println("Removed highlighter: ${highlighter.errorStripeTooltip}")
                        }
                    }
                } catch (e: Exception) {
                    if (DEBUG_SERVICE) {
                        System.out.println("Error removing highlighter: ${e.message}")
                    }
                }
            }

            // Force editor repaint on EDT
            editor.contentComponent.repaint()

            if (DEBUG_SERVICE) {
                System.out.println("Forced clear complete")
            }

            // Add a small delay and force another refresh
            javax.swing.Timer(500, { _ ->
                try {
                    // This timer callback will run on EDT
                    // Force another DaemonCodeAnalyzer restart to be safe
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                    editor.contentComponent.repaint()
                    if (DEBUG_SERVICE) {
                        System.out.println("Secondary refresh complete")
                    }
                } catch (e: Exception) {
                    if (DEBUG_SERVICE) {
                        System.out.println("Error in timer callback: ${e.message}")
                    }
                }
            }).apply {
                isRepeats = false
                start()
            }
        } catch (e: Exception) {
            if (DEBUG_SERVICE) {
                System.out.println("Error in performClearHighlights: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Show the floating toolbar for Accept/Reject actions
     */
    fun showFloatingToolbar(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            // Only hide the existing toolbar if we're going to show a new one at a different position
            // This prevents flickering when refreshing the UI
            if (floatingToolbar != null) {
                floatingToolbar?.hide()
            }
            floatingToolbar = InlineChatFloatingToolbar(project, editor)

            // Check if we need to show a warning about structural changes
            if (originalCode != null && extractedCode != null) {
                val isValid = validateImplementation(originalCode!!, extractedCode!!)
                if (!isValid) {
                    floatingToolbar?.setWarningMessage(
                        "The suggested changes may have significantly altered the code structure. Please review carefully."
                    )
                }
            }

            floatingToolbar?.show()

            // Make sure toolbar is visible while task is active
            inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
            inlineChatDiffActionState["Zest.InlineChat.Discard"] = true
        }
    }

    override fun dispose() {
        inlineChatInputVisible = false
        inlineChatDiffActionState.clear()
        location = null
        llmResponse = null
        extractedCode = null
        diffSegments.clear()
        originalCode = null
        selectionStartLine = 0
        selectionStartOffset = 0
        selectionEndOffset = 0
        floatingToolbar?.hide()
        floatingToolbar = null
    }

    /**
     * Validates that the implemented code maintains the overall structure of the original code.
     */
    fun validateImplementation(originalCode: String, implementedCode: String): Boolean {
        // Remove whitespace and comments for comparison
        val normalizedOriginal = normalizeForComparison(originalCode)
        val normalizedImplemented = normalizeForComparison(implementedCode)

        // Check if the implemented code has roughly similar structure
        // by comparing size (allowing for reasonable expansion)
        val originalSize = normalizedOriginal.length
        val implementedSize = normalizedImplemented.length

        // Implementation should not be smaller than original (unless it's refactoring)
        if (implementedSize < originalSize * 0.7) {
            return false
        }

        // Implementation should not be drastically larger (allowing for reasonable expansion)
        if (implementedSize > originalSize * 5) {
            return false
        }

        // Check that key structural elements are preserved
        val originalStructure = extractStructuralElements(originalCode)
        val implementedStructure = extractStructuralElements(implementedCode)

        // Count how many structural elements are preserved
        var preservedCount = 0
        for (element in originalStructure) {
            if (implementedStructure.contains(element)) {
                preservedCount++
            }
        }

        // At least 60% of structural elements should be preserved
        return if (originalStructure.isNotEmpty()) {
            preservedCount.toDouble() / originalStructure.size >= 0.6
        } else {
            true // If no structural elements found, consider it valid
        }
    }

    /**
     * Normalizes code for comparison by removing whitespace and comments.
     */
    private fun normalizeForComparison(code: String): String {
        // Remove single-line comments
        var normalized = code.replace(Regex("//.*?$", RegexOption.MULTILINE), "")

        // Remove multi-line comments
        normalized = normalized.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        // Remove extra whitespace
        normalized = normalized.replace(Regex("\\s+"), " ")

        // Trim
        return normalized.trim()
    }

    /**
     * Extracts structural elements (class names, method signatures) from code.
     */
    private fun extractStructuralElements(code: String): Set<String> {
        val elements = mutableSetOf<String>()

        // Extract class declarations
        val classPattern = Regex("\\b(class|interface|enum)\\s+(\\w+)")
        classPattern.findAll(code).forEach { match ->
            elements.add("${match.groupValues[1]}:${match.groupValues[2]}")
        }

        // Extract method signatures (simplified)
        val methodPattern = Regex("\\b(public|private|protected)?\\s*(static)?\\s*\\w+\\s+(\\w+)\\s*\\(")
        methodPattern.findAll(code).forEach { match ->
            elements.add("method:${match.groupValues[3]}")
        }

        // Extract field declarations
        val fieldPattern = Regex("\\b(public|private|protected)?\\s*(static)?\\s*(final)?\\s*\\w+\\s+(\\w+)\\s*[=;]")
        fieldPattern.findAll(code).forEach { match ->
            elements.add("field:${match.groupValues[4]}")
        }

        return elements
    }
}