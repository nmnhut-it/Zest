package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestBlockContextCollector

/**
 * Builds prompts for block-level code rewrites
 * Generates prompts that ask the AI to rewrite entire code blocks, functions, or lines
 */
class ZestBlockRewritePromptBuilder {
    
    /**
     * Build a prompt for rewriting a code block
     */
    fun buildBlockRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return when (context.blockType) {
            ZestBlockContextCollector.BlockType.METHOD -> buildMethodRewritePrompt(context)
            ZestBlockContextCollector.BlockType.CLASS -> buildClassRewritePrompt(context)
            ZestBlockContextCollector.BlockType.STATEMENT -> buildStatementRewritePrompt(context)
            ZestBlockContextCollector.BlockType.LINE -> buildLineRewritePrompt(context)
            ZestBlockContextCollector.BlockType.CODE_BLOCK -> buildCodeBlockRewritePrompt(context)
            ZestBlockContextCollector.BlockType.SELECTION -> buildSelectionRewritePrompt(context)
        }
    }
    
    /**
     * Build a prompt for rewriting a method
     */
    private fun buildMethodRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite a method.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- ${context.contextDescription}

**Current Method:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Instructions:**
1. Analyze the current method and understand its purpose
2. Improve the method by:
   - Adding proper error handling
   - Improving readability and structure
   - Adding meaningful comments where helpful
   - Following best practices for ${context.language}
   - Optimizing performance if possible
   - Ensuring type safety and null safety where applicable

3. Keep the same method signature unless there's a compelling reason to change it
4. Maintain compatibility with the surrounding code
5. Add unit test suggestions in comments if appropriate

**Output Format:**
Provide ONLY the rewritten method code without any explanations or markdown formatting.
The output should be ready to replace the original method directly.

**Rewritten Method:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for rewriting a class
     */
    private fun buildClassRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite a class.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- ${context.contextDescription}

**Current Class:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Instructions:**
1. Analyze the current class and understand its purpose and responsibilities
2. Improve the class by:
   - Following SOLID principles
   - Improving encapsulation and data hiding
   - Adding proper documentation
   - Implementing missing methods or properties
   - Adding error handling where appropriate
   - Following ${context.language} naming conventions and best practices

3. Maintain the same public interface unless there's a compelling reason to change it
4. Keep the class focused on a single responsibility
5. Add appropriate access modifiers

**Output Format:**
Provide ONLY the rewritten class code without any explanations or markdown formatting.
The output should be ready to replace the original class directly.

**Rewritten Class:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for rewriting a statement
     */
    private fun buildStatementRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite a code statement.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- ${context.contextDescription}

**Current Statement:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Instructions:**
1. Analyze the current statement and understand its purpose
2. Improve the statement by:
   - Adding error handling if needed
   - Improving readability
   - Following ${context.language} best practices
   - Making it more robust and maintainable
   - Adding null checks or type safety where applicable

3. Maintain the same logical behavior
4. Ensure compatibility with surrounding code
5. Consider performance implications

**Output Format:**
Provide ONLY the rewritten statement code without any explanations or markdown formatting.
The output should be ready to replace the original statement directly.

**Rewritten Statement:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for rewriting a single line
     */
    private fun buildLineRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite a line of code.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- Current line: ${context.originalBlock.trim()}

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Instructions:**
1. Analyze the current line and understand its purpose
2. Improve the line by:
   - Making it more readable and expressive
   - Adding error handling if appropriate
   - Following ${context.language} best practices
   - Improving variable naming if applicable
   - Adding type annotations if helpful

3. Maintain the same logical behavior
4. Ensure compatibility with surrounding code
5. Keep it concise but clear

**Output Format:**
Provide ONLY the rewritten line of code without any explanations or markdown formatting.
Do not include surrounding context - just the improved line.

**Rewritten Line:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for rewriting a code block
     */
    private fun buildCodeBlockRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite a code block.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- ${context.contextDescription}

**Current Code Block:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Instructions:**
1. Analyze the current code block and understand its purpose
2. Improve the code block by:
   - Restructuring for better readability
   - Adding appropriate error handling
   - Optimizing logic flow
   - Following ${context.language} best practices
   - Adding meaningful variable names
   - Reducing complexity where possible

3. Maintain the same overall behavior
4. Ensure compatibility with surrounding code
5. Consider edge cases and error scenarios

**Output Format:**
Provide ONLY the rewritten code block without any explanations or markdown formatting.
The output should be ready to replace the original code block directly.

**Rewritten Code Block:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for rewriting a selection
     */
    private fun buildSelectionRewritePrompt(context: ZestBlockContextCollector.BlockContext): String {
        return """
You are an expert ${context.language} developer. I need you to improve and rewrite selected code.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- Selected code for improvement

**Current Selection:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Instructions:**
1. Analyze the selected code and understand its purpose
2. Improve the selection by:
   - Enhancing readability and structure
   - Adding error handling where appropriate
   - Following ${context.language} best practices
   - Optimizing performance if possible
   - Making it more maintainable

3. Maintain the same interface and behavior
4. Ensure compatibility with surrounding code
5. Focus on the specific improvement needs of this code section

**Output Format:**
Provide ONLY the rewritten code without any explanations or markdown formatting.
The output should be ready to replace the original selection directly.

**Rewritten Code:**
        """.trimIndent()
    }
    
    /**
     * Build a prompt for a custom rewrite request
     */
    fun buildCustomRewritePrompt(
        context: ZestBlockContextCollector.BlockContext,
        customInstruction: String
    ): String {
        return """
You are an expert ${context.language} developer. I need you to rewrite code according to specific instructions.

**Context:**
- File: ${context.fileName}
- Language: ${context.language}
- ${context.contextDescription}

**Current Code:**
```${context.language.lowercase()}
${context.originalBlock}
```

**Surrounding Code for Reference:**
```${context.language.lowercase()}
${context.surroundingCode}
```

**Custom Instructions:**
${customInstruction}

**General Guidelines:**
1. Follow the custom instructions above as the primary requirement
2. Maintain compatibility with surrounding code
3. Follow ${context.language} best practices
4. Ensure the code remains functional and correct
5. Keep the same interface unless specifically requested to change it

**Output Format:**
Provide ONLY the rewritten code without any explanations or markdown formatting.
The output should be ready to replace the original code directly.

**Rewritten Code:**
        """.trimIndent()
    }
}
