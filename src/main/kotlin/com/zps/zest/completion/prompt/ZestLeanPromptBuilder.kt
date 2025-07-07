package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestLeanContextCollector

/**
 * Builds prompts for lean completion strategy with full file context
 */
class ZestLeanPromptBuilder {
    
    /**
     * Build a simplified prompt focused on completing the current line
     */
    fun buildReasoningPrompt(context: ZestLeanContextCollector.LeanContext): String {
        return """
You are an expert code completion AI. Complete the rest of the current line at the cursor position.

**File Context:**
- File: ${context.fileName}
- Language: ${context.language}

**Full File with Cursor Marker:**
```${context.language.lowercase()}
${context.markedContent}
```

**Instructions:**
Complete ONLY what should come after <CURSOR> on the current line. Follow the existing code style and patterns.

**Response Format:**
<completion>
[Code to insert at cursor - complete the rest of the current line]
</completion>

**Examples:**

Example 1 - Cursor in new line (complete new statement):
Given: `    <CURSOR>`
<completion>
if (user != null) {
        return user.isActive();
    }</completion>

Example 2 - Cursor in middle of line (complete method call):
Given: `String name = user.get<CURSOR>.toUpperCase();`
<completion>
Name()</completion>

Example 3 - Cursor at end of line (complete statement):
Given: `return orderRepository.findById(orderId).<CURSOR>`
<completion>
orElseThrow(() -> new OrderNotFoundException(orderId));</completion>

**Important:**
- Complete only the current line (stop at the line break)
- If the statement naturally continues to the next line (e.g., multi-line method), include the continuation
- Do NOT include any code that was already before the cursor
- Do NOT include cursor tag `<CURSOR>` 
- Do NOT add explanations or reasoning
        """.trimIndent()
    }
}