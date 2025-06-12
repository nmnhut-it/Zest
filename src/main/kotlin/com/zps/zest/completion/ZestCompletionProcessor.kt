package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.ZestInlineCompletionList

/**
 * Processes and validates completion responses
 */
class ZestCompletionProcessor {
    private val logger = Logger.getInstance(ZestCompletionProcessor::class.java)
    
    /**
     * Post-process completions to ensure they are valid
     */
    fun processCompletions(
        completions: ZestInlineCompletionList,
        context: CompletionContext
    ): ZestInlineCompletionList {
        
        if (completions.isEmpty()) {
            return completions
        }
        
        val processedItems = completions.items.mapNotNull { item ->
            processCompletionItem(item, context)
        }
        
        return ZestInlineCompletionList(
            isIncomplete = completions.isIncomplete,
            items = processedItems
        )
    }
    
    private fun processCompletionItem(
        item: ZestInlineCompletionItem, 
        context: CompletionContext
    ): ZestInlineCompletionItem? {
        
        // Validate completion text
        val cleanedText = validateAndCleanCompletion(item.insertText, context)
        if (cleanedText.isBlank()) {
            return null
        }
        
        // Adjust replace range if needed
        val adjustedRange = calculateOptimalReplaceRange(cleanedText, context)
        
        return item.copy(
            insertText = cleanedText,
            replaceRange = adjustedRange
        )
    }
    
    private fun validateAndCleanCompletion(completion: String, context: CompletionContext): String {
        // Remove common artifacts from LLM responses
        var cleaned = completion
            .replace(Regex("^(Here's|Here is|The completion is).*?:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^```[a-zA-Z]*\n"), "")
            .replace(Regex("\n```$"), "")
            .trim()
        
        // Remove duplicate whitespace
        cleaned = cleaned.replace(Regex("[ \t]+"), " ")
        
        // Validate completion makes sense
        if (!isValidCompletion(cleaned, context)) {
            System.out.println("Invalid completion rejected: $cleaned")
            return ""
        }
        
        return cleaned
    }
    
    private fun isValidCompletion(completion: String, context: CompletionContext): Boolean {
        // Reject empty or whitespace-only completions
        if (completion.isBlank()) {
            return false
        }
        
        // Reject completions that are too long (likely hallucinations)
        if (completion.length > MAX_COMPLETION_LENGTH) {
            System.out.println("Completion too long: ${completion.length} characters")
            return false
        }
        
        // Reject completions that just repeat the prefix
        val trimmedPrefix = context.prefixCode.takeLast(50).trim()
        if (trimmedPrefix.isNotEmpty() && completion.startsWith(trimmedPrefix)) {
            System.out.println("Completion repeats prefix")
            return false
        }
        
        // Reject completions that contain obvious artifacts
        val artifacts = listOf(
            "assistant:", "ai:", "response:", "output:", 
            "completion:", "suggestion:", "code:"
        )
        
        val lowerCompletion = completion.toLowerCase()
        if (artifacts.any { lowerCompletion.contains(it) }) {
            System.out.println("Completion contains artifacts")
            return false
        }
        
        return true
    }
    
    private fun calculateOptimalReplaceRange(
        completion: String, 
        context: CompletionContext
    ): ZestInlineCompletionItem.Range {
        
        // Default range: insert at cursor position
        var startOffset = context.offset
        var endOffset = context.offset
        
        // Check if we should replace some existing text
        val suffixToCheck = context.suffixCode.take(20)
        
        // If completion starts with text that already exists after cursor,
        // extend the range to replace the duplicate
        val commonPrefix = findCommonPrefix(completion, suffixToCheck)
        if (commonPrefix.isNotEmpty()) {
            endOffset = context.offset + commonPrefix.length
            System.out.println("Extending replace range to avoid duplication: '$commonPrefix'")
        }
        
        return ZestInlineCompletionItem.Range(startOffset, endOffset)
    }
    
    private fun findCommonPrefix(str1: String, str2: String): String {
        val minLength = minOf(str1.length, str2.length)
        var commonLength = 0
        
        for (i in 0 until minLength) {
            if (str1[i] == str2[i]) {
                commonLength++
            } else {
                break
            }
        }
        
        return str1.take(commonLength)
    }
    
    companion object {
        private const val MAX_COMPLETION_LENGTH = 1000 // Characters
    }
}
