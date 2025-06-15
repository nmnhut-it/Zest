package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestLeanContextCollector

/**
 * Builds prompts for lean completion strategy with full file context and reasoning
 */
class ZestLeanPromptBuilder {
    
    /**
     * Build a reasoning prompt that includes full file context
     */
    fun buildReasoningPrompt(context: ZestLeanContextCollector.LeanContext): String {
        return """
You are an expert code completion AI. Analyze the code context and provide a thoughtful completion.

**File Context:**
- File: ${context.fileName}
- Language: ${context.language}
- Cursor Line: ${context.cursorLine}
- Context Type: ${context.contextType}

**Full File with Cursor Marker:**
```${context.language.lowercase()}
${context.markedContent}
```

**Analysis Instructions:**
1. Understand the current context and what the user is likely trying to accomplish
2. Consider the file structure, imports, and existing patterns
3. Provide a completion that follows the established code style

**Completion Requirements:**
- Provide ONLY the code that should be inserted at the cursor
- Do NOT include the cursor marker or surrounding context
- Follow the existing code style and patterns
- Be concise but complete
- Ensure the completion is syntactically correct

**Code Completion:**
        """.trimIndent()
    }
}
