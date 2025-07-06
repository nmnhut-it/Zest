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

**Full File with Cursor Marker:**
```${context.language.lowercase()}
${context.markedContent}
```

**Analysis Instructions:**
1. Understand the current context and what the user is likely trying to type
2. Consider the file structure, imports, and existing patterns
3. Provide a completion that follows the established code style

**Response Format:**
You must provide your response in this EXACT format to ensure proper integration:

<prefix>
[Copy 1-2 lines of code that appear IMMEDIATELY BEFORE the cursor, exactly as shown]
</prefix>

<completion>
[Your actual code completion - what should be inserted at the cursor]
</completion>

<suffix>
[Copy 1-2 lines of code that appear IMMEDIATELY AFTER the cursor, exactly as shown]
</suffix>

**Important Requirements:**
- The <prefix> section must contain the exact code before the cursor (no modifications)
- The <completion> section contains ONLY the new code to insert
- The <suffix> section must contain the exact code after the cursor (no modifications)
- Do NOT include the cursor marker (<CURSOR>) in any section
- Follow the existing code style and patterns
- Ensure the completion is syntactically correct
- Completion should not exceed 5 lines and 50 words.

**Note:** The completion should be a natural fragment that continues from the cursor - it doesn't need to be syntactically complete. It can end mid-statement, mid-block, or mid-expression, just like real typing.

**Example Response Structure:**
<prefix>
    public User findBy
</prefix>

<completion>
Id(Long id) {
        return userRepository.findById(id).orElse(null);
    }
</completion>

<suffix>
    
    public List<User> findAllActive() {
</suffix>
        """.trimIndent()
    }
}
