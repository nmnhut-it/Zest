package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestMethodContextCollector

/**
 * Enhanced prompt builder for method-level code rewrites with better class context
 */
class ZestMethodPromptBuilder {
    
    /**
     * Build an enhanced prompt for rewriting a specific method with full class context
     */
    fun buildMethodRewritePrompt(context: ZestMethodContextCollector.MethodContext): String {
        val classInfo = analyzeClassContext(context)
        
        return """
You are an expert ${context.language} developer. Improve this method while maintaining its core functionality and class design principles.

**Class Context:**
${buildClassContextSection(context, classInfo)}

**Target Method to Improve:**
```${context.language.lowercase()}
${context.methodContent}
```

**Method Information:**
- Method: `${context.methodSignature}`
- Class: ${context.containingClass ?: "unknown"}
- File: ${context.fileName}
- Role: ${inferMethodRole(context)}

${buildSurroundingMethodsContext(context)}

**Improvement Guidelines:**
1. **Maintain Interface:** Keep the same method signature: `${context.methodSignature}`

2. **Class Integration:** 
   - Utilize available class fields appropriately
   - Consider interactions with other class methods
   - Follow established patterns in the class
   - Maintain consistency with class design principles

3. **Code Quality Improvements:**
   - Add comprehensive error handling and validation
   - Improve readability with clear variable names and structure
   - Follow ${context.language} best practices and idioms
   - Add meaningful comments for complex logic
   - Optimize performance while maintaining clarity
   - Ensure null safety and type safety
   - Handle edge cases appropriately

4. **Best Practices:**
   - Use appropriate data structures and algorithms
   - Minimize side effects where possible
   - Follow SOLID principles
   - Ensure thread safety if applicable
   - Add appropriate logging/debugging aids if beneficial

5. **Integration Requirements:**
   - Maintain compatibility with surrounding methods
   - Respect class invariants and contracts
   - Consider the method's role in the overall class design

**Output Format:**
Provide ONLY the improved method code without any explanations, markdown formatting, or additional text.

**Improved Method:**
        """.trimIndent()
    }
    
    /**
     * Analyze class context to determine how to present it
     */
    private fun analyzeClassContext(context: ZestMethodContextCollector.MethodContext): ClassContextInfo {
        val classContent = context.classContext
        val lineCount = classContent.lines().size
        val isShort = lineCount <= MAX_SHORT_CLASS_LINES
        
        return ClassContextInfo(
            isShort = isShort,
            lineCount = lineCount,
            hasFields = classContent.contains(Regex("(private|protected|public)\\s+\\w+\\s+\\w+[;=]")),
            methodCount = countMethods(classContent, context.language),
            classSignature = if (isShort) classContent else extractClassSignature(classContent, context.language)
        )
    }
    
    /**
     * Build the class context section of the prompt
     */
    private fun buildClassContextSection(context: ZestMethodContextCollector.MethodContext, classInfo: ClassContextInfo): String {
        return if (classInfo.isShort && context.classContext.isNotBlank()) {
            // Show full class for short classes
            """
**Full Class (${classInfo.lineCount} lines):**
```${context.language.lowercase()}
${context.classContext}
```"""
        } else if (context.classContext.isNotBlank()) {
            // Show class signature for longer classes
            """
**Class Structure (${classInfo.lineCount} lines total, ${classInfo.methodCount} methods):**
```${context.language.lowercase()}
${classInfo.classSignature}
```

*Note: Showing class signature only. Full class has ${classInfo.lineCount} lines.*"""
        } else {
            "**Class Context:** Not available"
        }
    }
    
    /**
     * Extract class signature (fields + method signatures without bodies)
     */
    private fun extractClassSignature(classContent: String, language: String): String {
        if (classContent.isBlank()) return ""
        
        val lines = classContent.lines()
        val signature = mutableListOf<String>()
        var inMethod = false
        var braceCount = 0
        var methodStartLine = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip empty lines and comments in signature
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                if (!inMethod) signature.add(line) // Keep comments outside methods
                continue
            }
            
            // Track brace levels
            val openBraces = line.count { it == '{' }
            val closeBraces = line.count { it == '}' }
            
            when {
                // Class declaration
                trimmed.matches(Regex(".*\\b(class|interface|enum)\\s+\\w+.*")) -> {
                    signature.add(line)
                    braceCount += openBraces - closeBraces
                }
                
                // Field declarations (not in methods)
                !inMethod && isFieldDeclaration(trimmed, language) -> {
                    signature.add(line)
                }
                
                // Method declarations
                !inMethod && isMethodDeclaration(trimmed, language) -> {
                    methodStartLine = line
                    if (trimmed.endsWith("{")) {
                        // Method starts on this line
                        signature.add(line.replace(" {", ";").replace("{", ""))
                        inMethod = true
                        braceCount += openBraces - closeBraces
                    } else {
                        // Method might continue on next line
                        signature.add(line)
                    }
                }
                
                // Method signature continuation
                !inMethod && methodStartLine.isNotEmpty() -> {
                    signature.add(line)
                    if (trimmed.endsWith("{")) {
                        // Convert to signature
                        val lastIdx = signature.size - 1
                        signature[lastIdx] = signature[lastIdx].replace(" {", ";").replace("{", "")
                        inMethod = true
                        braceCount += openBraces - closeBraces
                    }
                }
                
                // Inside method - skip until method ends
                inMethod -> {
                    braceCount += openBraces - closeBraces
                    if (braceCount <= 1) { // Back to class level
                        inMethod = false
                        methodStartLine = ""
                    }
                }
                
                // Class-level constructs (imports, etc.)
                else -> {
                    if (braceCount <= 1) { // At class level
                        signature.add(line)
                    }
                }
            }
        }
        
        return signature.joinToString("\n")
    }
    
    /**
     * Check if a line is a field declaration
     */
    private fun isFieldDeclaration(line: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "scala" -> {
                line.matches(Regex("(private|protected|public|static|final)\\s+.*\\s+\\w+\\s*[;=].*")) &&
                !line.contains("(") // Not a method
            }
            "kotlin" -> {
                line.matches(Regex("(private|protected|public|internal)?\\s*(val|var)\\s+\\w+.*")) ||
                line.matches(Regex("(private|protected|public|internal)?\\s*\\w+\\s*:\\s*\\w+.*"))
            }
            else -> {
                line.contains("=") && !line.contains("(") && !line.contains("function")
            }
        }
    }
    
    /**
     * Check if a line is a method declaration
     */
    private fun isMethodDeclaration(line: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "scala" -> {
                line.matches(Regex(".*\\b(public|private|protected|static|final|abstract).*\\w+\\s*\\(.*\\).*")) ||
                line.matches(Regex(".*\\w+\\s+\\w+\\s*\\(.*\\).*"))
            }
            "kotlin" -> {
                line.matches(Regex(".*\\bfun\\s+\\w+\\s*\\(.*\\).*"))
            }
            "javascript", "typescript" -> {
                line.matches(Regex(".*\\bfunction\\s+\\w+\\s*\\(.*\\).*")) ||
                line.matches(Regex(".*\\w+\\s*\\(.*\\)\\s*\\{?.*")) ||
                line.matches(Regex(".*\\w+:\\s*function.*"))
            }
            "python" -> {
                line.matches(Regex(".*\\bdef\\s+\\w+\\s*\\(.*\\):.*"))
            }
            else -> {
                line.contains("(") && line.contains(")")
            }
        }
    }
    
    /**
     * Count methods in class content
     */
    private fun countMethods(classContent: String, language: String): Int {
        return classContent.lines().count { line ->
            isMethodDeclaration(line.trim(), language)
        }
    }
    
    /**
     * Infer the role/purpose of the method based on its name and context
     */
    private fun inferMethodRole(context: ZestMethodContextCollector.MethodContext): String {
        val methodName = context.methodName.lowercase()
        
        return when {
            methodName.startsWith("get") -> "Getter/Accessor method"
            methodName.startsWith("set") -> "Setter/Mutator method"
            methodName.startsWith("is") || methodName.startsWith("has") || methodName.startsWith("can") -> "Boolean query method"
            methodName.startsWith("create") || methodName.startsWith("build") || methodName.startsWith("make") -> "Factory/Builder method"
            methodName.startsWith("init") || methodName.startsWith("setup") -> "Initialization method"
            methodName.startsWith("process") || methodName.startsWith("handle") || methodName.startsWith("execute") -> "Processing/Business logic method"
            methodName.startsWith("validate") || methodName.startsWith("check") -> "Validation method"
            methodName.startsWith("parse") || methodName.startsWith("convert") || methodName.startsWith("transform") -> "Data transformation method"
            methodName.startsWith("save") || methodName.startsWith("store") || methodName.startsWith("persist") -> "Persistence method"
            methodName.startsWith("load") || methodName.startsWith("fetch") || methodName.startsWith("retrieve") -> "Data retrieval method"
            methodName.startsWith("delete") || methodName.startsWith("remove") || methodName.startsWith("clear") -> "Cleanup/Removal method"
            methodName == "main" -> "Application entry point"
            methodName == "run" -> "Execution method"
            methodName == "close" || methodName == "dispose" -> "Resource cleanup method"
            methodName == "toString" -> "String representation method"
            methodName == "equals" -> "Equality comparison method"
            methodName == "hashCode" -> "Hash code generation method"
            else -> "Business logic method"
        }
    }
    
    /**
     * Build a prompt for a custom rewrite request with enhanced class context
     */
    fun buildCustomMethodPrompt(
        context: ZestMethodContextCollector.MethodContext,
        customInstruction: String
    ): String {
        val classInfo = analyzeClassContext(context)
        
        return """
You are an expert ${context.language} developer. Rewrite this method according to the specific instructions while maintaining class design principles.

**Class Context:**
${buildClassContextSection(context, classInfo)}

**Target Method:**
```${context.language.lowercase()}
${context.methodContent}
```

**Method Information:**
- Signature: `${context.methodSignature}`
- Class: ${context.containingClass ?: "unknown"}
- Role: ${inferMethodRole(context)}

${buildSurroundingMethodsContext(context)}

**Custom Instructions:**
${customInstruction}

**General Guidelines:**
1. Follow the custom instructions as the primary requirement
2. Maintain the method signature unless specifically asked to change it
3. Consider class context and integration with other methods
4. Follow ${context.language} best practices while meeting custom requirements
5. Ensure the code remains functional and integrates well with the class

**Output Format:**
Provide ONLY the rewritten method code without any explanations or markdown formatting.

**Rewritten Method:**
        """.trimIndent()
    }
    
    /**
     * Build context information about surrounding methods
     */
    private fun buildSurroundingMethodsContext(context: ZestMethodContextCollector.MethodContext): String {
        if (context.surroundingMethods.isEmpty()) {
            return "**Context:** This method stands alone in the class."
        }
        
        val contextBuilder = StringBuilder()
        contextBuilder.appendLine("**Surrounding Methods for Context:**")
        
        context.surroundingMethods.forEach { method ->
            val position = if (method.position == ZestMethodContextCollector.MethodPosition.BEFORE) "Before" else "After"
            contextBuilder.appendLine("$position: `${method.signature}`")
        }
        
        return contextBuilder.toString()
    }
    
    /**
     * Build a focused prompt for specific improvement types
     */
    fun buildFocusedImprovementPrompt(
        context: ZestMethodContextCollector.MethodContext,
        improvementType: ImprovementType
    ): String {
        val classInfo = analyzeClassContext(context)
        val focusedInstructions = when (improvementType) {
            ImprovementType.ERROR_HANDLING -> """
Focus specifically on improving error handling:
- Add try-catch blocks where appropriate
- Validate input parameters using class fields if available
- Handle edge cases and null values
- Add meaningful error messages
- Use appropriate exception types for this class context"""
            
            ImprovementType.PERFORMANCE -> """
Focus specifically on performance optimization:
- Reduce unnecessary object creation
- Optimize loops and data structures
- Leverage class fields for caching if appropriate
- Minimize redundant operations
- Use more efficient algorithms while maintaining class contracts"""
            
            ImprovementType.READABILITY -> """
Focus specifically on improving readability:
- Extract complex logic into private helper methods
- Add clear variable names that fit class naming conventions
- Add helpful comments explaining the method's role in the class
- Simplify conditional statements
- Improve code organization while respecting class structure"""
            
            ImprovementType.TESTING -> """
Focus on making the method more testable:
- Reduce dependencies on class state where possible
- Extract side effects into separate methods
- Make behavior more predictable
- Add parameter validation
- Separate concerns while maintaining class cohesion"""
        }
        
        return """
You are an expert ${context.language} developer. ${improvementType.description}

**Class Context:**
${buildClassContextSection(context, classInfo)}

**Method to Improve:**
```${context.language.lowercase()}
${context.methodContent}
```

**Focused Instructions:**
${focusedInstructions}

**Constraints:**
- Keep the same method signature: `${context.methodSignature}`
- Maintain the method's core functionality and role in the class
- Follow ${context.language} best practices
- Consider integration with other class methods and fields

**Improved Method:**
        """.trimIndent()
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
    
    /**
     * Data class to hold class context analysis results
     */
    private data class ClassContextInfo(
        val isShort: Boolean,
        val lineCount: Int,
        val hasFields: Boolean,
        val methodCount: Int,
        val classSignature: String
    )
    
    enum class ImprovementType(val description: String) {
        ERROR_HANDLING("Improve error handling and robustness"),
        PERFORMANCE("Optimize for better performance"), 
        READABILITY("Enhance code readability and maintainability"),
        TESTING("Make the code more testable")
    }
    
    companion object {
        private const val MAX_SHORT_CLASS_LINES = 50 // Classes with 50 lines or fewer are considered "short"
    }
}