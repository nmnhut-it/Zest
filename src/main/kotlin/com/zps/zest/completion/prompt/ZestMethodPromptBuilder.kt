package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestMethodContextCollector

/**
 * Builds focused prompts for method-level code rewrites
 */
class ZestMethodPromptBuilder {
    
    /**
     * Build a prompt for rewriting a specific method
     */
    fun buildMethodRewritePrompt(context: ZestMethodContextCollector.MethodContext): String {
        return """
You are an expert ${context.language} developer. Improve this method while maintaining its core functionality.

**Method to Improve:**
```${context.language.lowercase()}
${context.methodContent}
```

**Context Information:**
- Method: ${context.methodName}
- Class: ${context.containingClass ?: "unknown"}
- File: ${context.fileName}

${buildSurroundingMethodsContext(context)}

**Instructions:**
1. Keep the same method signature: `${context.methodSignature}`
2. Improve the implementation by:
   - Adding proper error handling
   - Improving readability and code structure
   - Following ${context.language} best practices
   - Adding meaningful comments where helpful
   - Optimizing performance if possible
   - Ensuring null safety where applicable

3. Maintain compatibility with surrounding methods
4. Return ONLY the improved method code, no explanations

**Improved Method:**
        """.trimIndent()
    }
    
    /**
     * Build a custom prompt with specific user instructions
     */
    fun buildCustomMethodPrompt(
        context: ZestMethodContextCollector.MethodContext,
        customInstruction: String
    ): String {
        return """
You are an expert ${context.language} developer. Improve this method according to the specific instructions.

**Method to Improve:**
```${context.language.lowercase()}
${context.methodContent}
```

**Context Information:**
- Method: ${context.methodName}
- Class: ${context.containingClass ?: "unknown"}
- Signature: ${context.methodSignature}

${buildSurroundingMethodsContext(context)}

**Custom Instructions:**
${customInstruction}

**General Guidelines:**
1. Keep the same method signature unless specifically requested to change it
2. Follow ${context.language} best practices
3. Maintain compatibility with surrounding code
4. Return ONLY the improved method code, no explanations

**Improved Method:**
        """.trimIndent()
    }
    
    /**
     * Build a focused prompt for specific improvement types
     */
    fun buildFocusedImprovementPrompt(
        context: ZestMethodContextCollector.MethodContext,
        improvementType: ImprovementType
    ): String {
        val focusedInstructions = when (improvementType) {
            ImprovementType.ERROR_HANDLING -> """
Focus specifically on improving error handling:
- Add try-catch blocks where appropriate
- Validate input parameters
- Handle edge cases and null values
- Add meaningful error messages
- Use appropriate exception types"""
            
            ImprovementType.PERFORMANCE -> """
Focus specifically on performance optimization:
- Reduce unnecessary object creation
- Optimize loops and data structures
- Minimize database/IO operations
- Cache frequently used values
- Use more efficient algorithms"""
            
            ImprovementType.READABILITY -> """
Focus specifically on improving readability:
- Extract complex logic into smaller methods
- Add clear variable names
- Add helpful comments
- Simplify conditional statements
- Improve code organization"""
            
            ImprovementType.TESTING -> """
Focus on making the method more testable:
- Reduce dependencies
- Extract side effects
- Make behavior more predictable
- Add parameter validation
- Separate concerns"""
        }
        
        return """
You are an expert ${context.language} developer. ${improvementType.description}

**Method to Improve:**
```${context.language.lowercase()}
${context.methodContent}
```

**Focused Instructions:**
${focusedInstructions}

**Constraints:**
- Keep the same method signature: `${context.methodSignature}`
- Maintain the method's core functionality
- Follow ${context.language} best practices

**Improved Method:**
        """.trimIndent()
    }
    
    /**
     * Build context information about surrounding methods
     */
    private fun buildSurroundingMethodsContext(context: ZestMethodContextCollector.MethodContext): String {
        if (context.surroundingMethods.isEmpty()) {
            return "**Context:** This method stands alone."
        }
        
        val contextBuilder = StringBuilder()
        contextBuilder.appendLine("**Surrounding Methods for Context:**")
        
        context.surroundingMethods.forEach { method ->
            val position = if (method.position == ZestMethodContextCollector.MethodPosition.BEFORE) "Before" else "After"
            contextBuilder.appendLine("$position: ${method.signature}")
        }
        
        return contextBuilder.toString()
    }
    
    /**
     * Build a quick refactoring prompt for simple improvements
     */
    fun buildQuickRefactorPrompt(context: ZestMethodContextCollector.MethodContext): String {
        return """
Quickly refactor this ${context.language} method for better readability:

```${context.language.lowercase()}
${context.methodContent}
```

Keep signature: `${context.methodSignature}`
Improve: naming, structure, comments
Return only the improved method:
        """.trimIndent()
    }
    
    /**
     * Build a modernization prompt to update code to current standards
     */
    fun buildModernizationPrompt(context: ZestMethodContextCollector.MethodContext): String {
        val modernizationHints = when (context.language.lowercase()) {
            "java" -> "Use modern Java features: streams, optional, var keyword, records, pattern matching"
            "kotlin" -> "Use Kotlin idioms: extension functions, data classes, sealed classes, coroutines"
            "javascript" -> "Use modern JavaScript: const/let, arrow functions, destructuring, async/await"
            "typescript" -> "Use TypeScript features: strict typing, interfaces, generics, utility types"
            "python" -> "Use modern Python: type hints, f-strings, dataclasses, context managers"
            else -> "Use modern language features and best practices"
        }
        
        return """
Modernize this ${context.language} method using current language standards:

```${context.language.lowercase()}
${context.methodContent}
```

**Modernization Focus:**
${modernizationHints}

**Rules:**
- Keep the same functionality
- Maintain signature: `${context.methodSignature}`  
- Use latest ${context.language} best practices

**Modernized Method:**
        """.trimIndent()
    }
    
    enum class ImprovementType(val description: String) {
        ERROR_HANDLING("Improve error handling and robustness"),
        PERFORMANCE("Optimize for better performance"),
        READABILITY("Enhance code readability and maintainability"),
        TESTING("Make the code more testable")
    }
}
