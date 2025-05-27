package com.zps.zest.inlinechat

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Location

/**
 * Service for managing inline chat state and diff information
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
     * Generate diff segments by comparing original and new code
     * This is a simple implementation - a more sophisticated diff algorithm
     * would be needed for a production feature
     */
    private fun generateDiffSegments(original: String, modified: String): MutableList<DiffSegment> {
        val segments = mutableListOf<DiffSegment>()
        
        // Add header segment
        segments.add(DiffSegment(0, 0, DiffSegmentType.HEADER, "AI Suggested Changes"))
        
        // Split into lines
        val originalLines = original.split("\n")
        val modifiedLines = modified.split("\n")
        
        // Simple line-by-line comparison
        // For a real implementation, use a proper diff algorithm like Myers diff
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
                    i++
                    j++
                    currentLine++
                }
                else -> {
                    // Different lines - could be modification, insertion, or deletion
                    val startLine = currentLine
                    val content = StringBuilder()
                    
                    // Check if it's an insertion
                    var found = false
                    for (k in i + 1 until originalLines.size) {
                        if (originalLines[k] == modifiedLines[j]) {
                            // Found a match later - lines i to k-1 were deleted
                            // and replaced with current j
                            content.append(modifiedLines[j]).append("\n")
                            segments.add(DiffSegment(startLine, startLine, DiffSegmentType.INSERTED, content.toString()))
                            
                            i = k
                            j++
                            currentLine++
                            found = true
                            break
                        }
                    }
                    
                    if (!found) {
                        // No match found - probably a completely new line
                        content.append(modifiedLines[j]).append("\n")
                        segments.add(DiffSegment(startLine, startLine, DiffSegmentType.INSERTED, content.toString()))
                        j++
                        currentLine++
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