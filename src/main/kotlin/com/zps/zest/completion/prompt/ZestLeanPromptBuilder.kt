package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestLeanContextCollector

/**
 * Lean prompt builder that creates reasoning-based prompts for full file completion
 */
class ZestLeanPromptBuilder {
    
    fun buildReasoningPrompt(context: ZestLeanContextCollector.LeanContext): String {
        val contextualInstructions = getContextualInstructions(context.contextType)
        val languageHints = getLanguageSpecificHints(context.language)
        
        return """
You are an expert ${context.language} developer. Analyze the following code file and generate the completion text that should be inserted at the [CURSOR] position.

**File: ${context.fileName}**
**Context: ${context.contextType.name}**

```${context.language}
${context.markedContent}
```

**Analysis Instructions:**
${contextualInstructions}

**Language-Specific Guidelines:**
${languageHints}

**Task:**
Analyze the full file context and generate ONLY the text that should be inserted at [CURSOR]. Do not return the complete file - just the completion text. This should be 30 words max. 

**Response Format:**
<reasoning>
[Brief analysis in MAX 50 words - be concise and focused]
1. **Context**: What's at [CURSOR]?
2. **Pattern**: What code pattern fits?
3. **Completion**: What should be inserted?
</reasoning>

<completion>
[Insert ONLY the text that should replace [CURSOR] - no full file, no explanations]
</completion>
""".trimIndent()
    }
    
    private fun getContextualInstructions(contextType: ZestLeanContextCollector.CursorContextType): String {
        return when (contextType) {
            ZestLeanContextCollector.CursorContextType.METHOD_BODY -> """
- Analyze the method signature and understand its purpose
- Look at existing method implementations for patterns
- Consider what logic would complete this method appropriately
- Think about error handling, edge cases, and return values
- Ensure the completion fits the method's responsibility"""
            
            ZestLeanContextCollector.CursorContextType.CLASS_DECLARATION -> """
- Analyze the class name and existing structure
- Look at import statements for clues about intended functionality
- Consider what fields, constructors, or methods are missing
- Think about common design patterns for this type of class
- Ensure proper encapsulation and class responsibilities"""
            
            ZestLeanContextCollector.CursorContextType.IMPORT_SECTION -> """
- Analyze what classes are being used in the code below
- Look for unresolved references that need imports
- Consider standard library vs external dependencies
- Group imports logically (standard library, third-party, internal)
- Remove unused imports if any"""
            
            ZestLeanContextCollector.CursorContextType.VARIABLE_ASSIGNMENT -> """
- Analyze the variable type and its intended use
- Look at the surrounding context for clues about the value
- Consider initialization patterns used elsewhere in the code
- Think about null safety and proper initialization
- Ensure the assignment makes sense in the current scope"""
            
            ZestLeanContextCollector.CursorContextType.AFTER_OPENING_BRACE -> """
- Analyze what kind of block this is (method, class, conditional, etc.)
- Look at similar blocks in the codebase for patterns
- Consider what content is typically expected in this context
- Think about proper indentation and code organization
- Ensure the block content serves its intended purpose"""
            
            ZestLeanContextCollector.CursorContextType.UNKNOWN -> """
- Analyze the surrounding code structure carefully
- Look for contextual clues about what should come next
- Consider common coding patterns and conventions
- Think about what would be most helpful to the developer
- Ensure the completion follows established code style"""
        }
    }
    
    private fun getLanguageSpecificHints(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
- Follow Java naming conventions (camelCase for methods/variables, PascalCase for classes)
- Use appropriate access modifiers (private, protected, public)
- Consider checked exceptions and proper exception handling
- Use generics appropriately for type safety
- Follow JavaDoc conventions for documentation"""
            
            "kotlin" -> """
- Use Kotlin idioms (data classes, extension functions, null safety)
- Prefer val over var when possible
- Use proper null safety operators (?., ?:, !!)
- Consider using Kotlin standard library functions
- Follow Kotlin naming conventions"""
            
            "javascript", "typescript" -> """
- Use modern ES6+ syntax (const/let, arrow functions, destructuring)
- Consider async/await for asynchronous operations
- Use proper error handling with try-catch
- Follow JavaScript/TypeScript naming conventions
- Use type annotations if this is TypeScript"""
            
            "python" -> """
- Follow PEP 8 style guidelines
- Use proper indentation (4 spaces)
- Consider list comprehensions and generator expressions
- Use appropriate Python idioms and built-in functions
- Handle exceptions with try-except blocks"""
            
            else -> """
- Follow the language's established conventions and idioms
- Use consistent naming and formatting patterns
- Consider best practices for the specific language
- Ensure code is readable and maintainable"""
        }
    }
    
    /**
     * Build a simpler prompt for quick completions when reasoning might be overkill
     */
    fun buildSimpleLeanPrompt(context: ZestLeanContextCollector.LeanContext): String {
        return """
Complete the ${context.language} code at [CURSOR]. Analyze the full file context but return only the completion text.

```${context.language}
${context.markedContent}
```

<reasoning>
[MAX 20 words - quick analysis of what to complete]
</reasoning>

<completion>
[Text to insert at [CURSOR]]
</completion>
""".trimIndent()
    }
    
    /**
     * Build a focused prompt for specific completion types
     */
    fun buildFocusedPrompt(context: ZestLeanContextCollector.LeanContext): String {
        val focusArea = when (context.contextType) {
            ZestLeanContextCollector.CursorContextType.IMPORT_SECTION -> 
                "Generate the import statement(s) needed."
            ZestLeanContextCollector.CursorContextType.METHOD_BODY -> 
                "Generate the method implementation."
            ZestLeanContextCollector.CursorContextType.CLASS_DECLARATION -> 
                "Generate the appropriate class member (field, method, constructor)."
            ZestLeanContextCollector.CursorContextType.VARIABLE_ASSIGNMENT -> 
                "Generate the appropriate value or expression for the assignment."
            else -> 
                "Generate the most logical completion."
        }
        
        return """
Complete this ${context.language} file. ${focusArea}

```${context.language}
${context.markedContent}
```

<reasoning>
[MAX 30 words - focused analysis for ${context.contextType.name.lowercase().replace("_", " ")}]
</reasoning>

<completion>
[Completion text to insert at [CURSOR]]
</completion>
""".trimIndent()
    }
}
