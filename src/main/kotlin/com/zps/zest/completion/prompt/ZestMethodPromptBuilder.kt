package com.zps.zest.completion.prompt

import com.intellij.openapi.project.Project
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.MethodPosition
import com.zps.zest.completion.Cocos2dxContextType
import com.zps.zest.completion.SurroundingMethod
import com.zps.zest.rules.ZestRulesLoader
import com.zps.zest.completion.experience.ZestExperienceTracker
import com.zps.zest.langchain4j.ZestLangChain4jService

/**
 * Enhanced prompt builder for method-level code rewrites with better class context
 */
class ZestMethodPromptBuilder(private val project: Project? = null) {
    
    private val rulesLoader: ZestRulesLoader? = project?.let { ZestRulesLoader(it) }
    private val experienceTracker: ZestExperienceTracker? = project?.let { ZestExperienceTracker.getInstance(it) }
    
    
    
    /**
     * Load custom rules and prepend them to the prompt if available
     */
    private fun prependCustomRules(basePrompt: String): String {
        val sections = mutableListOf<String>()
        
        // Add custom rules if available
        val customRules = rulesLoader?.loadCustomRules()
        if (customRules != null) {
            sections.add("""
**CUSTOM PROJECT RULES:**
${customRules}
            """.trimIndent())
        }
        
        // Add experience patterns if available
        val patterns = experienceTracker?.getRecentPatterns() ?: emptyList()
        if (patterns.isNotEmpty()) {
            sections.add("""
**LEARNED PREFERENCES FROM THIS PROJECT:**
${patterns.joinToString("\n") { "- $it" }}
            """.trimIndent())
        }
        
        return if (sections.isNotEmpty()) {
            sections.joinToString("\n\n") + "\n\n---\n\n" + basePrompt
        } else {
            basePrompt
        }
    }
    
    /**
     * Build an enhanced prompt for rewriting a specific method with full class context
     */
    fun buildMethodRewritePrompt(context: MethodContext): String {
        val classInfo = analyzeClassContext(context)
        val cocosGuidance = buildCocos2dxGuidance(context)
        
        val basePrompt = """
You are a code modification assistant. Execute the requested task on this method while preserving all unrelated code.

TASK-SPECIFIC RULES:
1. **FOCUS ON THE REQUESTED TASK ONLY** - Do not make unrelated changes
2. **PRESERVE EXISTING CODE STYLE** - Keep formatting, patterns, and conventions as-is
3. **MAINTAIN METHOD SIGNATURE** - Keep `${context.methodSignature}` exactly as-is
4. **DO NOT REFACTOR** unless specifically implementing a method or completing TODO
5. **PRESERVE ALL UNRELATED CODE** - Every line not part of the task stays unchanged

${cocosGuidance}

**Class Context:**
${buildClassContextSection(context, classInfo)}

${if (context.relatedClasses.isNotEmpty()) buildRelatedClassesSection(context.relatedClasses) else ""}


**Method to Modify:**
```${context.language.lowercase()}
${context.methodContent}
```

**Method Information:**
- Method: `${context.methodSignature}`
- Class: ${context.containingClass ?: "unknown"}
- File: ${context.fileName}
- Role: ${inferMethodRole(context)}

${buildSurroundingMethodsContext(context)}

**TASK EXECUTION RULES:**
- If task is "Add logging": ONLY add log statements, change nothing else
- If task is "Add error handling": ONLY add try-catch blocks, change nothing else  
- If task is "Complete implementation": Implement functionality while preserving existing patterns
- If task is "Fix issue": ONLY fix the specific problem, change nothing else
- If task is "Optimize performance": ONLY apply optimizations, preserve logic and style

${buildCocos2dxSpecificGuidelines(context)}

**Output Format:**
Provide the COMPLETE method including the method signature. Start with the method declaration and include the entire method body with closing braces. Do not provide explanations or markdown formatting.

**Modified Method:**
        """.trimIndent()
        
        return prependCustomRules(basePrompt)
    }
    
    /**
     * Build related classes section
     */
    private fun buildRelatedClassesSection(relatedClasses: Map<String, String>): String {
        return buildString {
            appendLine("**Related Classes Used in Method:**")
            relatedClasses.forEach { (className, _) ->
                appendLine("- $className")
            }
            appendLine()
            appendLine("The structures of these classes are included in the class context above.")
        }
    }
    
    /**
     * Analyze class context to determine how to present it
     */
    private fun analyzeClassContext(context: MethodContext): ClassContextInfo {
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
    private fun buildClassContextSection(context: MethodContext, classInfo: ClassContextInfo): String {
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
    private fun inferMethodRole(context: MethodContext): String {
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
        context: MethodContext,
        customInstruction: String
    ): String {
        val classInfo = analyzeClassContext(context)
        val cocosGuidance = buildCocos2dxGuidance(context)
        
        val basePrompt = """
You are a precise code modification assistant. Your ONLY job is to execute the EXACT task requested.

CRITICAL EXECUTION RULES:
1. **DO ONLY WHAT IS EXPLICITLY REQUESTED** - Nothing more, nothing less
2. **PRESERVE ALL EXISTING CODE** that is not part of the requested task
3. **DO NOT** refactor, rename, optimize, or "improve" anything unless specifically instructed
4. **MAINTAIN** exact formatting, style, and patterns of the original code
5. **KEEP EVERY LINE UNCHANGED** unless the task specifically requires changing it
6. **DO NOT** add comments, logging, or error handling unless explicitly requested
7. **FOLLOW INSTRUCTIONS LITERALLY** - Do not interpret or enhance the request

${cocosGuidance}

**Method to Modify:**
```${context.language.lowercase()}
${context.methodContent}
```

**Method Information:**
- Signature: `${context.methodSignature}`
- Class: ${context.containingClass ?: "unknown"}


${buildSurroundingMethodsContext(context)}

**TASK TO EXECUTE:**
${customInstruction}

**EXECUTION REQUIREMENTS:**
- Execute ONLY the task described above
- Do NOT modify any other part of the code
- Keep the method signature exactly as-is unless task specifically requires changing it
- Preserve all existing patterns, styles, and formatting
- Do NOT add "improvements" or "best practices" unless explicitly requested

${buildCocos2dxSpecificGuidelines(context)}

**Output Format:**
Provide the COMPLETE modified method including the method signature. Start with the method declaration and include the entire method body with closing braces. Do not provide explanations or markdown formatting.

**Modified Method:**
        """.trimIndent()
        
        return prependCustomRules(basePrompt)
    }
    
    /**
     * Build context information about surrounding methods
     */
    private fun buildSurroundingMethodsContext(context: MethodContext): String {
        if (context.surroundingMethods.isEmpty()) {
            return "**Context:** This method stands alone in the class."
        }
        
        val contextBuilder = StringBuilder()
        contextBuilder.appendLine("**Surrounding Methods for Context:**")
        
        context.surroundingMethods.forEach { method ->
            val position = if (method.position == MethodPosition.BEFORE) "Before" else "After"
            contextBuilder.appendLine("$position: `${method.signature}`")
        }
        
        return contextBuilder.toString()
    }
    
    /**
     * Build Cocos2d-x specific guidance section
     */
    private fun buildCocos2dxGuidance(context: MethodContext): String {
        if (!context.isCocos2dx) return ""
        
        return """
🎮 **COCOS2D-X JAVASCRIPT PROJECT DETECTED**

**CRITICAL SYNTAX REQUIREMENTS:**
- Use OLD VERSION cocos2d-x-js syntax patterns
- PREFER cc.Node() over cc.Node.create() - direct constructor calls preferred
- PREFER cc.Sprite() over cc.Sprite.create() - direct constructor calls preferred  
- PREFER cc.Scene() over cc.Scene.create() - direct constructor calls preferred
- Use .extend() pattern for class inheritance: var MyClass = cc.Layer.extend({...})
- Use object literal methods: methodName: function() {...}
- Include lifecycle methods: ctor, onEnter, onExit, init, update when appropriate

**Framework Context:**
${if (context.cocosFrameworkVersion != null) "- Detected Cocos2d-x version: ${context.cocosFrameworkVersion}" else "- Using legacy Cocos2d-x patterns"}
${if (context.cocosContextType != null) "- Method context: ${context.cocosContextType}" else ""}

**Cocos2d-x Specific Patterns:**
${context.cocosCompletionHints.joinToString("\n") { "- $it" }}
        """.trimIndent()
    }
    
    /**
     * Build Cocos2d-x specific improvement guidelines
     */
    private fun buildCocos2dxSpecificGuidelines(context: MethodContext): String {
        if (!context.isCocos2dx) return ""
        
        val guidelines = StringBuilder()
        guidelines.appendLine("7. **Cocos2d-x Framework Guidelines:**")
        
        // Simplified default Cocos2d-x guidelines
        guidelines.appendLine("   - Follow cocos2d-x-js old version syntax patterns")
        guidelines.appendLine("   - Use direct constructor calls over .create() methods")
        guidelines.appendLine("   - Maintain consistency with cocos2d-x naming conventions")
        
        guidelines.appendLine("   - Ensure compatibility with cocos2d-x ${context.cocosFrameworkVersion ?: "framework"}")
        guidelines.appendLine("   - Follow the established patterns in your cocos2d-x project")
        
        return guidelines.toString()
    }
    
    /**
     * Build a focused prompt for specific improvement types
     */
    fun buildFocusedImprovementPrompt(
        context: MethodContext,
        improvementType: ImprovementType
    ): String {
        val classInfo = analyzeClassContext(context)
        val cocosGuidance = buildCocos2dxGuidance(context)
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
        
        val basePrompt = """
You are an expert ${context.language} developer. ${improvementType.description}

${cocosGuidance}

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

${buildCocos2dxSpecificGuidelines(context)}

**Improved Method:**
        """.trimIndent()
        
        return prependCustomRules(basePrompt)
    }
    
    /**
     * Build a quick refactoring prompt for simple improvements
     */
    fun buildQuickRefactorPrompt(context: MethodContext): String {
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
    fun buildModernizationPrompt(context: MethodContext): String {
        val modernizationHints = when (context.language.lowercase()) {
            "java" -> "Use modern Java features: streams, optional, var keyword, records, pattern matching"
            "kotlin" -> "Use Kotlin idioms: extension functions, data classes, sealed classes, coroutines"
            "javascript" -> if (context.isCocos2dx) {
                "Modernize while maintaining Cocos2d-x compatibility: use const/let, but keep old syntax patterns (cc.Node() over cc.Node.create())"
            } else {
                "Use modern JavaScript: const/let, arrow functions, destructuring, async/await"
            }
            "typescript" -> if (context.isCocos2dx) {
                "Use TypeScript features while maintaining Cocos2d-x patterns: strict typing with old syntax (cc.Sprite() over cc.Sprite.create())"
            } else {
                "Use TypeScript features: strict typing, interfaces, generics, utility types"
            }
            "python" -> "Use modern Python: type hints, f-strings, dataclasses, context managers"
            else -> "Use modern language features and best practices"
        }
        
        val cocosModernizationHints = if (context.isCocos2dx) {
            """
            
**Cocos2d-x Modernization Guidelines:**
- Keep using old version syntax patterns (this is REQUIRED for Cocos2d-x)
- Use cc.Node() instead of cc.Node.create() (direct constructors preferred)
- Maintain .extend() inheritance patterns: var MyClass = cc.Layer.extend({...})
- Keep object literal method syntax: methodName: function() {...}
- Preserve lifecycle method patterns: ctor, onEnter, onExit
- Do NOT modernize to ES6 classes if it breaks Cocos2d-x compatibility
            """.trimIndent()
        } else ""
        
        return """
Modernize this ${context.language} method using current language standards:

```${context.language.lowercase()}
${context.methodContent}
```

**Modernization Focus:**
${modernizationHints}${cocosModernizationHints}

**Rules:**
- Keep the same functionality
- Maintain signature: `${context.methodSignature}`  
- Use latest ${context.language} best practices
${if (context.isCocos2dx) "- CRITICAL: Maintain Cocos2d-x compatibility and old syntax patterns" else ""}

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