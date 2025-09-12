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
     * Build an enhanced prompt for rewriting a specific method with full class context
     */
    fun buildMethodRewritePrompt(context: MethodContext): String {
        val classInfo = analyzeClassContext(context)
        val cocosGuidance = buildCocos2dxGuidance(context)
        val prompt = buildRewriteBasePrompt(context, classInfo, cocosGuidance)
        return prependCustomRules(prompt)
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
        val prompt = buildCustomBasePrompt(context, classInfo, cocosGuidance, customInstruction)
        return prependCustomRules(prompt)
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
        val focusedInstructions = getFocusedInstructions(improvementType)
        val prompt = buildFocusedBasePrompt(context, classInfo, cocosGuidance, improvementType, focusedInstructions)
        return prependCustomRules(prompt)
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
        val hints = modernizationHints(context)
        val cocosHints = cocosModernizationGuidelines(context)
        return """
Modernize this ${context.language} method using current language standards:
```${context.language.lowercase()}
${context.methodContent}
```
**Modernization Focus:**
${hints}${cocosHints}
**Rules:**
- Keep the same functionality
- Maintain signature: `${context.methodSignature}`  
- Use latest ${context.language} best practices
${if (context.isCocos2dx) "- CRITICAL: Maintain Cocos2d-x compatibility and old syntax patterns" else ""}
**Modernized Method:**
        """.trimIndent()
    }

    // Base prompt builders

    private fun buildRewriteBasePrompt(
        context: MethodContext,
        classInfo: ClassContextInfo,
        cocosGuidance: String
    ): String {
        return """
You are a code modification assistant. Execute the requested task on this method while preserving all unrelated code.
${rewriteTaskRules(context)}
${cocosGuidance}
**Class Context:**
${buildClassContextSection(context, classInfo)}
${relatedClassesOrEmpty(context)}
${methodToModifyBlock(context)}
${methodInfoBlock(context)}
${rewriteExecutionRules(context)}
${buildCocos2dxSpecificGuidelines(context)}
${outputFormatBlock()}
        """.trimIndent()
    }

    private fun buildCustomBasePrompt(
        context: MethodContext,
        classInfo: ClassContextInfo,
        cocosGuidance: String,
        customInstruction: String
    ): String {
        return """
You are a precise code modification assistant. Your ONLY job is to execute the EXACT task requested.
${customExecutionRules()}
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
${customExecutionRequirements(context)}
${buildCocos2dxSpecificGuidelines(context)}
${outputFormatBlock()}
        """.trimIndent()
    }

    private fun buildFocusedBasePrompt(
        context: MethodContext,
        classInfo: ClassContextInfo,
        cocosGuidance: String,
        improvementType: ImprovementType,
        focusedInstructions: String
    ): String {
        return """
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
    }

    // Sections and small helpers for base prompts

    private fun rewriteTaskRules(context: MethodContext): String {
        return """
TASK-SPECIFIC RULES:
1. **FOCUS ON THE REQUESTED TASK ONLY** - Do not make unrelated changes
2. **PRESERVE EXISTING CODE STYLE** - Keep formatting, patterns, and conventions as-is
3. **MAINTAIN METHOD SIGNATURE** - Keep `${context.methodSignature}` exactly as-is
4. **DO NOT REFACTOR** unless specifically implementing a method or completing TODO
5. **PRESERVE ALL UNRELATED CODE** - Every line not part of the task stays unchanged
        """.trimIndent()
    }

    private fun methodToModifyBlock(context: MethodContext): String {
        return """
**Method to Modify:**
```${context.language.lowercase()}
${context.methodContent}
```""".trimIndent()
    }

    private fun methodInfoBlock(context: MethodContext): String {
        return """
**Method Information:**
- Method: `${context.methodSignature}`
- Class: ${context.containingClass ?: "unknown"}
- File: ${context.fileName}
- Role: ${inferMethodRole(context)}
${buildSurroundingMethodsContext(context)}""".trimIndent()
    }

    private fun rewriteExecutionRules(context: MethodContext): String {
        return """
**TASK EXECUTION RULES:**
- If task is "Add logging": ONLY add log statements, change nothing else
- If task is "Add error handling": ONLY add try-catch blocks, change nothing else  
- If task is "Complete implementation": Implement functionality while preserving existing patterns
- If task is "Fix issue": ONLY fix the specific problem, change nothing else
- If task is "Optimize performance": ONLY apply optimizations, preserve logic and style
        """.trimIndent()
    }

    private fun outputFormatBlock(): String {
        return """
**Output Format:**
Provide the COMPLETE method including the method signature. Start with the method declaration and include the entire method body with closing braces. Do not provide explanations or markdown formatting.
**Modified Method:**""".trimIndent()
    }

    private fun relatedClassesOrEmpty(context: MethodContext): String {
        if (context.relatedClasses.isEmpty()) return ""
        return buildRelatedClassesSection(context.relatedClasses)
    }

    private fun customExecutionRules(): String {
        return """
CRITICAL EXECUTION RULES:
1. **DO ONLY WHAT IS EXPLICITLY REQUESTED** - Nothing more, nothing less
2. **PRESERVE ALL EXISTING CODE** that is not part of the requested task
3. **DO NOT** refactor, rename, optimize, or "improve" anything unless specifically instructed
4. **MAINTAIN** exact formatting, style, and patterns of the original code
5. **KEEP EVERY LINE UNCHANGED** unless the task specifically requires changing it
6. **DO NOT** add comments, logging, or error handling unless explicitly requested
7. **FOLLOW INSTRUCTIONS LITERALLY** - Do not interpret or enhance the request
        """.trimIndent()
    }

    private fun customExecutionRequirements(context: MethodContext): String {
        return """
**EXECUTION REQUIREMENTS:**
- Execute ONLY the task described above
- Do NOT modify any other part of the code
- Keep the method signature exactly as-is unless task specifically requires changing it
- Preserve all existing patterns, styles, and formatting
- Do NOT add "improvements" or "best practices" unless explicitly requested
        """.trimIndent()
    }

    // Custom rules and experience

    private fun prependCustomRules(basePrompt: String): String {
        val sections = listOfNotNull(
            getCustomRulesSection(),
            getLearnedPreferencesSection()
        ).filter { it.isNotBlank() }
        if (sections.isEmpty()) return basePrompt
        return sections.joinToString("\n\n") + "\n\n---\n\n" + basePrompt
    }

    private fun getCustomRulesSection(): String? {
        val customRules = rulesLoader?.loadCustomRules() ?: return null
        return """
**CUSTOM PROJECT RULES:**
$customRules
        """.trimIndent()
    }

    private fun getLearnedPreferencesSection(): String? {
        val patterns = experienceTracker?.getRecentPatterns().orEmpty()
        if (patterns.isEmpty()) return null
        return """
**LEARNED PREFERENCES FROM THIS PROJECT:**
${patterns.joinToString("\n") { "- $it" }}
        """.trimIndent()
    }

    // Related classes

    private fun buildRelatedClassesSection(relatedClasses: Map<String, String>): String {
        return buildString {
            appendLine("**Related Classes Used in Method:**")
            relatedClasses.forEach { (className, _) -> appendLine("- $className") }
            appendLine()
            appendLine("The structures of these classes are included in the class context above.")
        }
    }

    // Class context analysis

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

    private fun buildClassContextSection(context: MethodContext, classInfo: ClassContextInfo): String {
        if (context.classContext.isBlank()) return "**Class Context:** Not available"
        if (classInfo.isShort) {
            return """
**Full Class (${classInfo.lineCount} lines):**
```${context.language.lowercase()}
${context.classContext}
```""".trimIndent()
        }
        return """
**Class Structure (${classInfo.lineCount} lines total, ${classInfo.methodCount} methods):**
```${context.language.lowercase()}
${classInfo.classSignature}
```
*Note: Showing class signature only. Full class has ${classInfo.lineCount} lines.*""".trimIndent()
    }

    // Signature extraction

    private fun extractClassSignature(classContent: String, language: String): String {
        if (classContent.isBlank()) return ""
        val state = SignatureState()
        val out = mutableListOf<String>()
        classContent.lines().forEach { line ->
            handleSignatureLine(line, language, state, out)
        }
        return out.joinToString("\n")
    }

    private fun handleSignatureLine(
        line: String,
        language: String,
        state: SignatureState,
        out: MutableList<String>
    ) {
        val trimmed = line.trim()
        val open = countOpenBraces(line)
        val close = countCloseBraces(line)

        if (isSkippableCommentOrEmpty(trimmed)) {
            if (!isInMethod(state)) out.add(line)
            return
        }

        if (isInMethod(state)) {
            updateMethodDepth(state, open, close)
            updateClassDepth(state, open, close)
            return
        }

        if (isClassDecl(trimmed)) {
            out.add(line); updateClassDepth(state, open, close); return
        }

        if (state.awaitingBraceIndex != null && opensBrace(trimmed)) {
            convertAwaitingSignature(out, state)
            state.inMethodBodyDepth = 1
            updateClassDepth(state, open, close)
            return
        }

        if (isFieldDeclaration(trimmed, language)) {
            out.add(line); return
        }

        if (isMethodDeclaration(trimmed, language)) {
            addMethodSignatureLine(out, line, trimmed, state, open, close)
            return
        }

        if (state.braceBalance <= 1) out.add(line)
    }

    private fun addMethodSignatureLine(
        out: MutableList<String>,
        line: String,
        trimmed: String,
        state: SignatureState,
        open: Int,
        close: Int
    ) {
        if (trimmed.endsWith("{") || open > 0) {
            out.add(sanitizeMethodDeclForSignature(line))
            state.inMethodBodyDepth = 1
            updateClassDepth(state, open, close)
            return
        }
        out.add(line)
        state.awaitingBraceIndex = out.lastIndex
    }

    private fun convertAwaitingSignature(out: MutableList<String>, state: SignatureState) {
        val idx = state.awaitingBraceIndex ?: return
        out[idx] = sanitizeMethodDeclForSignature(out[idx])
        state.awaitingBraceIndex = null
    }

    private fun sanitizeMethodDeclForSignature(line: String): String {
        return line.replace(" {", ";").replace("{", "").trimEnd()
    }

    private fun isSkippableCommentOrEmpty(trimmed: String): Boolean {
        return trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
    }

    private fun isInMethod(state: SignatureState): Boolean = state.inMethodBodyDepth > 0

    private fun opensBrace(trimmed: String): Boolean = trimmed.endsWith("{") || trimmed.contains("{")

    private fun updateMethodDepth(state: SignatureState, open: Int, close: Int) {
        state.inMethodBodyDepth += open - close
        if (state.inMethodBodyDepth <= 0) state.inMethodBodyDepth = 0
    }

    private fun updateClassDepth(state: SignatureState, open: Int, close: Int) {
        state.braceBalance += open - close
    }

    private fun countOpenBraces(line: String): Int = line.count { it == '{' }

    private fun countCloseBraces(line: String): Int = line.count { it == '}' }

    private fun isClassDecl(trimmed: String): Boolean {
        return trimmed.matches(Regex(".*\\b(class|interface|enum)\\s+\\w+.*"))
    }

    private data class SignatureState(
        var inMethodBodyDepth: Int = 0,
        var awaitingBraceIndex: Int? = null,
        var braceBalance: Int = 0
    )

    // Declarations and counting

    private fun isFieldDeclaration(line: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "scala" ->
                line.matches(Regex("(private|protected|public|static|final)\\s+.*\\s+\\w+\\s*[;=].*")) &&
                        !line.contains("(")
            "kotlin" ->
                line.matches(Regex("(private|protected|public|internal)?\\s*(val|var)\\s+\\w+.*")) ||
                        line.matches(Regex("(private|protected|public|internal)?\\s*\\w+\\s*:\\s*\\w+.*"))
            else -> line.contains("=") && !line.contains("(") && !line.contains("function")
        }
    }

    private fun isMethodDeclaration(line: String, language: String): Boolean {
        return when (language.lowercase()) {
            "java", "scala" ->
                line.matches(Regex(".*\\b(public|private|protected|static|final|abstract).*\\w+\\s*\\(.*\\).*")) ||
                        line.matches(Regex(".*\\w+\\s+\\w+\\s*\\(.*\\).*"))
            "kotlin" -> line.matches(Regex(".*\\bfun\\s+\\w+\\s*\\(.*\\).*"))
            "javascript", "typescript" ->
                line.matches(Regex(".*\\bfunction\\s+\\w+\\s*\\(.*\\).*")) ||
                        line.matches(Regex(".*\\w+\\s*\\(.*\\)\\s*\\{?.*")) ||
                        line.matches(Regex(".*\\w+:\\s*function.*"))
            "python" -> line.matches(Regex(".*\\bdef\\s+\\w+\\s*\\(.*\\):.*"))
            else -> line.contains("(") && line.contains(")")
        }
    }

    private fun countMethods(classContent: String, language: String): Int {
        return classContent.lines().count { isMethodDeclaration(it.trim(), language) }
    }

    // Method role inference

    private fun inferMethodRole(context: MethodContext): String {
        val name = context.methodName.lowercase()
        methodRoleByPrefix(name)?.let { return it }
        methodRoleByExact(name)?.let { return it }
        return "Business logic method"
    }

    private fun methodRoleByPrefix(name: String): String? {
        return when {
            name.startsWith("get") -> "Getter/Accessor method"
            name.startsWith("set") -> "Setter/Mutator method"
            name.startsWith("is") || name.startsWith("has") || name.startsWith("can") -> "Boolean query method"
            name.startsWith("create") || name.startsWith("build") || name.startsWith("make") -> "Factory/Builder method"
            name.startsWith("init") || name.startsWith("setup") -> "Initialization method"
            name.startsWith("process") || name.startsWith("handle") || name.startsWith("execute") -> "Processing/Business logic method"
            name.startsWith("validate") || name.startsWith("check") -> "Validation method"
            name.startsWith("parse") || name.startsWith("convert") || name.startsWith("transform") -> "Data transformation method"
            name.startsWith("save") || name.startsWith("store") || name.startsWith("persist") -> "Persistence method"
            name.startsWith("load") || name.startsWith("fetch") || name.startsWith("retrieve") -> "Data retrieval method"
            name.startsWith("delete") || name.startsWith("remove") || name.startsWith("clear") -> "Cleanup/Removal method"
            else -> null
        }
    }

    private fun methodRoleByExact(name: String): String? {
        return when (name) {
            "main" -> "Application entry point"
            "run" -> "Execution method"
            "close", "dispose" -> "Resource cleanup method"
            "tostring" -> "String representation method"
            "equals" -> "Equality comparison method"
            "hashcode" -> "Hash code generation method"
            else -> null
        }
    }

    // Surrounding methods

    private fun buildSurroundingMethodsContext(context: MethodContext): String {
        if (context.surroundingMethods.isEmpty()) {
            return "**Context:** This method stands alone in the class."
        }
        val b = StringBuilder()
        b.appendLine("**Surrounding Methods for Context:**")
        context.surroundingMethods.forEach { method ->
            val position = if (method.position == MethodPosition.BEFORE) "Before" else "After"
            b.appendLine("$position: `${method.signature}`")
        }
        return b.toString()
    }

    // Cocos2d-x guidance

    private fun buildCocos2dxGuidance(context: MethodContext): String {
        if (!context.isCocos2dx) return ""
        return """
${cocosHeader()}
${cocosSyntaxRules()}
${cocosFrameworkContext(context)}
${cocosSpecificPatterns(context)}
        """.trimIndent()
    }

    private fun cocosHeader(): String {
        return "🎮 **COCOS2D-X JAVASCRIPT PROJECT DETECTED**"
    }

    private fun cocosSyntaxRules(): String {
        return """
**CRITICAL SYNTAX REQUIREMENTS:**
- Use OLD VERSION cocos2d-x-js syntax patterns
- PREFER cc.Node() over cc.Node.create() - direct constructor calls preferred
- PREFER cc.Sprite() over cc.Sprite.create() - direct constructor calls preferred  
- PREFER cc.Scene() over cc.Scene.create() - direct constructor calls preferred
- Use .extend() pattern for class inheritance: var MyClass = cc.Layer.extend({...})
- Use object literal methods: methodName: function() {...}
- Include lifecycle methods: ctor, onEnter, onExit, init, update when appropriate
        """.trimIndent()
    }

    private fun cocosFrameworkContext(context: MethodContext): String {
        val version = if (context.cocosFrameworkVersion != null)
            "- Detected Cocos2d-x version: ${context.cocosFrameworkVersion}" else "- Using legacy Cocos2d-x patterns"
        val type = if (context.cocosContextType != null) "- Method context: ${context.cocosContextType}" else ""
        return """
**Framework Context:**
$version
$type
        """.trimIndent()
    }

    private fun cocosSpecificPatterns(context: MethodContext): String {
        val hints = context.cocosCompletionHints.joinToString("\n") { "- $it" }
        return """
**Cocos2d-x Specific Patterns:**
$hints
        """.trimIndent()
    }

    private fun buildCocos2dxSpecificGuidelines(context: MethodContext): String {
        if (!context.isCocos2dx) return ""
        val g = StringBuilder()
        g.appendLine("7. **Cocos2d-x Framework Guidelines:**")
        g.appendLine("   - Follow cocos2d-x-js old version syntax patterns")
        g.appendLine("   - Use direct constructor calls over .create() methods")
        g.appendLine("   - Maintain consistency with cocos2d-x naming conventions")
        g.appendLine("   - Ensure compatibility with cocos2d-x ${context.cocosFrameworkVersion ?: "framework"}")
        g.appendLine("   - Follow the established patterns in your cocos2d-x project")
        return g.toString()
    }

    // Focused improvements

    private fun getFocusedInstructions(improvementType: ImprovementType): String {
        return when (improvementType) {
            ImprovementType.ERROR_HANDLING -> """
Focus specifically on improving error handling:
- Add try-catch blocks where appropriate
- Validate input parameters using class fields if available
- Handle edge cases and null values
- Add meaningful error messages
- Use appropriate exception types for this class context""".trimIndent()

            ImprovementType.PERFORMANCE -> """
Focus specifically on performance optimization:
- Reduce unnecessary object creation
- Optimize loops and data structures
- Leverage class fields for caching if appropriate
- Minimize redundant operations
- Use more efficient algorithms while maintaining class contracts""".trimIndent()

            ImprovementType.READABILITY -> """
Focus specifically on improving readability:
- Extract complex logic into private helper methods
- Add clear variable names that fit class naming conventions
- Add helpful comments explaining the method's role in the class
- Simplify conditional statements
- Improve code organization while respecting class structure""".trimIndent()

            ImprovementType.TESTING -> """
Focus on making the method more testable:
- Reduce dependencies on class state where possible
- Extract side effects into separate methods
- Make behavior more predictable
- Add parameter validation
- Separate concerns while maintaining class cohesion""".trimIndent()
        }
    }

    // Modernization helpers

    private fun modernizationHints(context: MethodContext): String {
        return when (context.language.lowercase()) {
            "java" -> "Use modern Java features: streams, optional, var keyword, records, pattern matching"
            "kotlin" -> "Use Kotlin idioms: extension functions, data classes, sealed classes, coroutines"
            "javascript" ->
                if (context.isCocos2dx)
                    "Modernize while maintaining Cocos2d-x compatibility: use const/let, but keep old syntax patterns (cc.Node() over cc.Node.create())"
                else
                    "Use modern JavaScript: const/let, arrow functions, destructuring, async/await"
            "typescript" ->
                if (context.isCocos2dx)
                    "Use TypeScript features while maintaining Cocos2d-x patterns: strict typing with old syntax (cc.Sprite() over cc.Sprite.create())"
                else
                    "Use TypeScript features: strict typing, interfaces, generics, utility types"
            "python" -> "Use modern Python: type hints, f-strings, dataclasses, context managers"
            else -> "Use modern language features and best practices"
        }
    }

    private fun cocosModernizationGuidelines(context: MethodContext): String {
        if (!context.isCocos2dx) return ""
        return """
            
**Cocos2d-x Modernization Guidelines:**
- Keep using old version syntax patterns (this is REQUIRED for Cocos2d-x)
- Use cc.Node() instead of cc.Node.create() (direct constructors preferred)
- Maintain .extend() inheritance patterns: var MyClass = cc.Layer.extend({...})
- Keep object literal method syntax: methodName: function() {...}
- Preserve lifecycle method patterns: ctor, onEnter, onExit
- Do NOT modernize to ES6 classes if it breaks Cocos2d-x compatibility
        """.trimIndent()
    }

    // Data

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
        private const val MAX_SHORT_CLASS_LINES = 50
    }
}