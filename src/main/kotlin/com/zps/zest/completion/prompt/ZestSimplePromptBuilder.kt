package com.zps.zest.completion.prompt

import com.zps.zest.completion.context.ZestSimpleContextCollector

/**
 * Simple prompt builder focused on fill-in-the-middle completion using prefix/suffix context
 * Now includes support for Qwen 2.5 Coder FIM format and example-enhanced prompts
 */
class ZestSimplePromptBuilder {
    
    fun buildCompletionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return when {
            isCodeLanguage(context.language) -> buildFimPrompt(context)
            else -> buildTextCompletionPrompt(context)
        }
    }
    
    /**
     * Build a Fill-In-the-Middle (FIM) style prompt for code completion
     * Using Qwen 2.5 Coder format with <|fim_prefix|>, <|fim_suffix|>, <|fim_middle|>
     */
    private fun buildFimPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return """<|fim_prefix|>${context.prefixCode}<|fim_suffix|>${context.suffixCode}<|fim_middle|>"""
    }
    
    /**
     * Build a simple text completion prompt for non-code files
     */
    private fun buildTextCompletionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return """${context.prefixCode}[COMPLETE]${context.suffixCode}

Complete the text at [COMPLETE]:"""
    }
    
    /**
     * Instruction-based prompt with examples for better quality
     */
    fun buildInstructionPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        val examples = getExamplesForLanguage(context.language)
        
        return """Complete the ${context.language} code. Here are examples:

$examples

Now complete this code:
${context.prefixCode}<CURSOR>${context.suffixCode}

Insert only the code at <CURSOR>:"""
    }
    
    /**
     * Instruction prompt with examples focused on the current context
     */
    fun buildInstructionPromptWithContext(context: ZestSimpleContextCollector.SimpleContext): String {
        val contextType = detectContextType(context)
        val examples = getContextSpecificExamples(context.language, contextType)
        
        return """Complete the ${context.language} code. ${getContextDescription(contextType)}

Examples:
$examples

Complete:
${context.prefixCode}<CURSOR>${context.suffixCode}

Insert code at <CURSOR>:"""
    }
    
    /**
     * Minimal prompt with just a few inline examples
     */
    fun buildMinimalPrompt(context: ZestSimpleContextCollector.SimpleContext): String {
        return "${context.prefixCode}│${context.suffixCode}"
    }
    
    /**
     * Enhanced minimal prompt with tiny examples
     */
    fun buildMinimalPromptWithExamples(context: ZestSimpleContextCollector.SimpleContext): String {
        val quickExample = getQuickExample(context.language, detectContextType(context))
        return """// Example: $quickExample
${context.prefixCode}│${context.suffixCode}"""
    }
    
    private fun getExamplesForLanguage(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
Example 1 - Method body:
public int add(int a, int b) {<CURSOR>}
→ return a + b;

Example 2 - Variable assignment:
String name = <CURSOR>;
→ "Hello World"

Example 3 - Method call:
System.out.<CURSOR>("Hello");
→ println"""
            
            "kotlin" -> """
Example 1 - Function body:
fun add(a: Int, b: Int): Int {<CURSOR>}
→ return a + b

Example 2 - Variable assignment:
val name = <CURSOR>
→ "Hello World"

Example 3 - Property access:
person.<CURSOR>
→ name"""
            
            "javascript" -> """
Example 1 - Function body:
function add(a, b) {<CURSOR>}
→ return a + b;

Example 2 - Variable assignment:
const name = <CURSOR>;
→ "Hello World"

Example 3 - Method call:
console.<CURSOR>("Hello");
→ log"""
            
            "python" -> """
Example 1 - Function body:
def add(a, b):<CURSOR>
→ return a + b

Example 2 - Variable assignment:
name = <CURSOR>
→ "Hello World"

Example 3 - Method call:
print(<CURSOR>)
→ "Hello World\""""
            
            else -> """
Example 1 - Complete statement:
x = <CURSOR>
→ 42

Example 2 - Method call:
print(<CURSOR>)
→ "Hello\""""
        }
    }
    
    private fun getContextSpecificExamples(language: String, contextType: ContextType): String {
        return when (contextType) {
            ContextType.METHOD_BODY -> getMethodBodyExamples(language)
            ContextType.VARIABLE_ASSIGNMENT -> getVariableExamples(language) 
            ContextType.METHOD_CALL -> getMethodCallExamples(language)
            ContextType.CLASS_MEMBER -> getClassMemberExamples(language)
            ContextType.UNKNOWN -> getGeneralExamples(language)
        }
    }
    
    private fun getMethodBodyExamples(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
public int calculate() {<CURSOR>} → return x + y;
public void process() {<CURSOR>} → System.out.println("Processing");
public String getName() {<CURSOR>} → return this.name;"""
            
            "kotlin" -> """
fun calculate(): Int {<CURSOR>} → return x + y
fun process() {<CURSOR>} → println("Processing")
fun getName(): String {<CURSOR>} → return name"""
            
            "javascript" -> """
function calculate() {<CURSOR>} → return x + y;
function process() {<CURSOR>} → console.log("Processing");
function getName() {<CURSOR>} → return this.name;"""
            
            else -> "function() {<CURSOR>} → return value;"
        }
    }
    
    private fun getVariableExamples(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
String name = <CURSOR>; → "John Doe"
int count = <CURSOR>; → 0
List<String> items = <CURSOR>; → new ArrayList<>()"""
            
            "kotlin" -> """
val name = <CURSOR> → "John Doe"
var count = <CURSOR> → 0
val items = <CURSOR> → listOf<String>()"""
            
            "javascript" -> """
const name = <CURSOR>; → "John Doe"
let count = <CURSOR>; → 0
const items = <CURSOR>; → []"""
            
            else -> "x = <CURSOR> → value"
        }
    }
    
    private fun getMethodCallExamples(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
System.out.<CURSOR>("Hello"); → println
list.<CURSOR>(); → size
string.<CURSOR>(); → length"""
            
            "kotlin" -> """
println(<CURSOR>) → "Hello"
list.<CURSOR>() → size
string.<CURSOR> → length"""
            
            "javascript" -> """
console.<CURSOR>("Hello"); → log
array.<CURSOR>; → length
string.<CURSOR>(); → toLowerCase"""
            
            else -> "call(<CURSOR>) → argument"
        }
    }
    
    private fun getClassMemberExamples(language: String): String {
        return when (language.lowercase()) {
            "java" -> """
private String <CURSOR>; → name
public void <CURSOR>() {} → method
private static final int <CURSOR> = 0; → CONSTANT"""
            
            "kotlin" -> """
private val <CURSOR> = "" → name
fun <CURSOR>() {} → method
companion object { const val <CURSOR> = 0 } → CONSTANT"""
            
            else -> "member <CURSOR> → name"
        }
    }
    
    private fun getGeneralExamples(language: String): String {
        return when (language.lowercase()) {
            "java" -> "statement; <CURSOR> → next statement"
            "kotlin" -> "statement <CURSOR> → next statement"
            "javascript" -> "statement; <CURSOR> → next statement"
            else -> "code <CURSOR> → more code"
        }
    }
    
    private fun getQuickExample(language: String, contextType: ContextType): String {
        return when (contextType) {
            ContextType.METHOD_BODY -> "return value;"
            ContextType.VARIABLE_ASSIGNMENT -> "\"value\""
            ContextType.METHOD_CALL -> "method()"
            ContextType.CLASS_MEMBER -> "private field"
            ContextType.UNKNOWN -> "statement"
        }
    }
    
    private fun detectContextType(context: ZestSimpleContextCollector.SimpleContext): ContextType {
        val prefix = context.prefixCode.takeLast(100).lowercase()
        
        return when {
            // Method body - ends with { after method signature
            prefix.contains(Regex("""(public|private|protected|fun|function|def)\s+[^{]*\{\s*$""")) -> 
                ContextType.METHOD_BODY
                
            // Variable assignment - ends with = 
            prefix.matches(Regex(""".*\s*=\s*$""")) -> 
                ContextType.VARIABLE_ASSIGNMENT
                
            // Method call - ends with . or (
            prefix.matches(Regex(""".*[.]\s*$""")) || prefix.matches(Regex(""".*\(\s*$""")) -> 
                ContextType.METHOD_CALL
                
            // Class member - at class level
            prefix.contains(Regex("""(class|interface)\s+\w+[^{]*\{[^{}]*$""")) -> 
                ContextType.CLASS_MEMBER
                
            else -> ContextType.UNKNOWN
        }
    }
    
    private fun getContextDescription(contextType: ContextType): String {
        return when (contextType) {
            ContextType.METHOD_BODY -> "You are completing inside a method body."
            ContextType.VARIABLE_ASSIGNMENT -> "You are completing a variable assignment."
            ContextType.METHOD_CALL -> "You are completing a method call."
            ContextType.CLASS_MEMBER -> "You are adding a class member."
            ContextType.UNKNOWN -> "Complete the code appropriately."
        }
    }
    
    private fun isCodeLanguage(language: String): Boolean {
        val codeLanguages = setOf(
            "java", "kotlin", "javascript", "typescript", "python", 
            "html", "css", "xml", "json", "yaml", "sql", "groovy", 
            "scala", "go", "rust", "cpp", "c", "swift", "php", "ruby"
        )
        return codeLanguages.contains(language.lowercase())
    }
    
    enum class ContextType {
        METHOD_BODY,
        VARIABLE_ASSIGNMENT,
        METHOD_CALL,
        CLASS_MEMBER,
        UNKNOWN
    }
}
