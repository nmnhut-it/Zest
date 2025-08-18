package com.zps.zest.completion.data

/**
 * Collection of inline completion suggestions
 */
data class ZestInlineCompletionList(
    val isIncomplete: Boolean,
    val items: List<ZestInlineCompletionItem>
) {
    companion object {
        val EMPTY = ZestInlineCompletionList(false, emptyList())
        
        fun single(item: ZestInlineCompletionItem): ZestInlineCompletionList {
            return ZestInlineCompletionList(false, listOf(item))
        }
    }
    
    fun isEmpty(): Boolean = items.isEmpty()
    
    fun isNotEmpty(): Boolean = items.isNotEmpty()
    
    fun firstItem(): ZestInlineCompletionItem? = items.firstOrNull()
    
    fun getItem(index: Int): ZestInlineCompletionItem? = items.getOrNull(index)
}
