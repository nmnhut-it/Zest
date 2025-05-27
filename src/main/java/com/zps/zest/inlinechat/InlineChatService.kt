package com.zps.zest.inlinechat

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Location

/**
 * Service for managing inline chat state and diff information.
 * Uses IntelliJ's built-in diff system for accurate code comparison.
 */
@Service(Service.Level.PROJECT)
class InlineChatService(private val project: Project) : Disposable {

    var inlineChatInputVisible = false
    var inlineChatDiffActionState = mutableMapOf<String, Boolean>()
    var location: Location? = null
    
    // Store LLM response for reference
    var llmResponse: String? = null
    
    // Store extracted code (if any) from the response
    var extractedCode: String? = null
    
    // Store diff segments for highlighting
    var diffSegments = mutableListOf<DiffSegment>()
    
    // Keep track of original code to generate diff
    var originalCode: String? = null

    val hasDiffAction: Boolean
        get() = inlineChatDiffActionState.any { it.value }

    /**
     * Parse the LLM response and update the diff segments
     */
    fun processLlmResponse(response: String, originalText: String) {
        llmResponse = response
        originalCode = originalText
        
        // Extract code from the response
        extractedCode = extractCodeFromResponse(response)
        
        // Generate diff segments
        if (extractedCode != null) {
            diffSegments = generateDiffSegments(originalText, extractedCode!!)
            
            // Enable diff actions
            inlineChatDiffActionState["Zest.InlineChat.Accept"] = true
            inlineChatDiffActionState["Zest.InlineChat.Discard"] = true
        }
    }
    
    /**
     * Extract code blocks from a markdown response
     */
    private fun extractCodeFromResponse(response: String): String? {
        val codeBlockRegex = Regex("```(?:java)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(response)
        return match?.groups?.get(1)?.value?.trim()
    }
    
    /**
     * Generate diff segments by comparing original and new code using IntelliJ's diff API
     */
    private fun generateDiffSegments(original: String, modified: String): MutableList<DiffSegment> {
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
            
            // Convert LineFragments to DiffSegments
            var currentLine = 0
            
            // Add header segment
            segments.add(DiffSegment(0, 0, DiffSegmentType.HEADER, "AI Suggested Changes"))
            
            for (fragment in lineFragments) {
                val startLine1 = fragment.startLine1
                val endLine1 = fragment.endLine1
                val startLine2 = fragment.startLine2
                val endLine2 = fragment.endLine2
                
                // Handle unchanged lines before this fragment
                if (currentLine < startLine2) {
                    // These lines are unchanged
                    for (line in currentLine until startLine2) {
                        segments.add(DiffSegment(
                            line + 1,
                            line + 1,
                            DiffSegmentType.UNCHANGED,
                            getLineContent(modified, line)
                        ))
                    }
                }
                
                // Process the changed fragment
                when {
                    // Pure insertion (no lines in original, lines in modified)
                    startLine1 == endLine1 && startLine2 < endLine2 -> {
                        for (line in startLine2 until endLine2) {
                            segments.add(DiffSegment(
                                line + 1,
                                line + 1,
                                DiffSegmentType.INSERTED,
                                getLineContent(modified, line)
                            ))
                        }
                    }
                    // Pure deletion (lines in original, no lines in modified)
                    startLine1 < endLine1 && startLine2 == endLine2 -> {
                        // For deletions, we add a marker at the current position
                        segments.add(DiffSegment(
                            startLine2 + 1,
                            startLine2 + 1,
                            DiffSegmentType.DELETED,
                            "// ${endLine1 - startLine1} line(s) deleted"
                        ))
                    }
                    // Modification (both have lines)
                    else -> {
                        // Show the modified lines as insertions
                        for (line in startLine2 until endLine2) {
                            segments.add(DiffSegment(
                                line + 1,
                                line + 1,
                                DiffSegmentType.INSERTED,
                                getLineContent(modified, line)
                            ))
                        }
                    }
                }
                
                currentLine = endLine2
            }
            
            // Handle any remaining unchanged lines
            val totalLines = modified.split("\n").size
            if (currentLine < totalLines) {
                for (line in currentLine until totalLines) {
                    segments.add(DiffSegment(
                        line + 1,
                        line + 1,
                        DiffSegmentType.UNCHANGED,
                        getLineContent(modified, line)
                    ))
                }
            }
            
            // Add footer segment
            val lastLine = segments.lastOrNull()?.endLine ?: 1
            segments.add(DiffSegment(
                lastLine + 1,
                lastLine + 1,
                DiffSegmentType.FOOTER,
                "End of changes"
            ))
            
        } catch (e: Exception) {
            // Fallback to simple line-by-line comparison if diff API fails
            segments.addAll(generateSimpleDiffSegments(original, modified))
        }
        
        return segments
    }
    
    /**
     * Get the content of a specific line from text
     */
    private fun getLineContent(text: String, lineIndex: Int): String {
        val lines = text.split("\n")
        return if (lineIndex in lines.indices) lines[lineIndex] else ""
    }
    
    /**
     * Fallback simple diff generation (original implementation)
     */
    private fun generateSimpleDiffSegments(original: String, modified: String): List<DiffSegment> {
        val segments = mutableListOf<DiffSegment>()
        
        // Add header segment
        segments.add(DiffSegment(0, 0, DiffSegmentType.HEADER, "AI Suggested Changes"))
        
        // Split into lines
        val originalLines = original.split("\n")
        val modifiedLines = modified.split("\n")
        
        // Simple line-by-line comparison
        var currentLine = 1
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
        llmResponse = null
        extractedCode = null
        diffSegments.clear()
        inlineChatDiffActionState.clear()
        originalCode = null
    }

    override fun dispose() {
        inlineChatInputVisible = false
        inlineChatDiffActionState.clear()
        location = null
        llmResponse = null
        extractedCode = null
        diffSegments.clear()
        originalCode = null
    }
}