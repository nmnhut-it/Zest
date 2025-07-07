package com.zps.zest.completion.prompt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.zps.zest.ClassAnalyzer
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI

/**
 * Builds prompts for lean completion strategy with full file context
 * Updated to support structured prompts for better caching
 */
class ZestLeanPromptBuilder(private val project: Project) {

    companion object {
        // Enhanced system prompt that mentions related code
        private const val LEAN_SYSTEM_PROMPT =
            """You are an expert code completion AI. Your task is to complete code at the cursor position marked as <CURSOR>.

Instructions:
1. Analyze the full file context and related classes to understand the code structure, patterns, and style
2. Consider the methods being called and classes being used in the current context
3. Complete ONLY what should come after <CURSOR> on the current line
4. If the statement naturally continues to multiple lines (e.g., method body, block), include the full logical completion
5. Follow existing code patterns, naming conventions, and formatting from both the current file and related classes
6. Do NOT include any code that was already before the cursor
7. Do NOT include the <CURSOR> tag in your output
8. Output your completion inside <completion> tags

Response Format:
<completion>
[Your code completion here]
</completion>

Important:
- Complete only the current line (stop at the line break)
- If the statement naturally continues to the next line (e.g., multi-line method), include the continuation
- Match the indentation and style of the surrounding code
- Use the APIs and patterns shown in the related classes"""
    }

    /**
     * Build structured prompt with separate system and user components including related classes
     */
    fun buildStructuredReasoningPrompt(context: ZestLeanContextCollectorPSI.LeanContext): StructuredPrompt {
        val userPrompt = buildEnhancedUserPrompt(context)

        return StructuredPrompt(
            systemPrompt = LEAN_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            metadata = mapOf(
                "fileName" to context.fileName,
                "language" to context.language,
                "hasPreservedMethods" to context.preservedMethods.isNotEmpty(),
                "hasPreservedFields" to context.preservedFields.isNotEmpty(),
                "hasCalledMethods" to context.calledMethods.isNotEmpty(),
                "hasUsedClasses" to context.usedClasses.isNotEmpty(),
                "hasRelatedClasses" to context.relatedClassContents.isNotEmpty(),
                "contextType" to context.contextType.name,
                "offset" to context.cursorOffset
            )
        )
    }

    /**
     * Build enhanced user prompt with file context and related classes
     */
    private fun buildEnhancedUserPrompt(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val contextInfo = buildEnhancedContextInfo(context)
        val relatedClassesSection = buildRelatedClassesSection(context)

        return """File: ${context.fileName}
Language: ${context.language}
$contextInfo

Full file with cursor position:
```${context.language.lowercase()}
${context.markedContent}
```

$relatedClassesSection

Complete the code at the <CURSOR> position."""
    }

    /**
     * Build the user prompt with file context
     */
    private fun buildUserPrompt(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val contextInfo = buildContextInfo(context)

        return """File: ${context.fileName}
Language: ${context.language}
$contextInfo

Full file with cursor position:
```${context.language.lowercase()}
${context.markedContent}
```

Complete the code at the <CURSOR> position."""
    }

    /**
     * Build enhanced context information including analysis results
     */
    private fun buildEnhancedContextInfo(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val info = mutableListOf<String>()

        if (context.contextType != ZestLeanContextCollectorPSI.CursorContextType.UNKNOWN) {
            info.add("Context type: ${context.contextType.name.lowercase().replace('_', ' ')}")
        }

        if (context.calledMethods.isNotEmpty()) {
            info.add("Called methods: ${context.calledMethods.take(10).joinToString(", ")}")
        }

        if (context.usedClasses.isNotEmpty()) {
            info.add("Used classes: ${context.usedClasses.take(5).joinToString(", ")}")
        }

        if (context.preservedMethods.isNotEmpty()) {
            info.add("Related methods in file: ${context.preservedMethods.size} methods preserved")
        }

        if (context.preservedFields.isNotEmpty()) {
            info.add("Related fields: ${context.preservedFields.size} fields in context")
        }

        if (context.isTruncated) {
            info.add("Note: File content has been truncated to fit context window")
        }

        return if (info.isNotEmpty()) {
            "Additional context:\n" + info.joinToString("\n")
        } else {
            ""
        }
    }

    /**
     * Build additional context information if available
     */
    private fun buildContextInfo(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val info = mutableListOf<String>()

        if (context.contextType != ZestLeanContextCollectorPSI.CursorContextType.UNKNOWN) {
            info.add("Context type: ${context.contextType.name.lowercase().replace('_', ' ')}")
        }

        if (context.preservedMethods.isNotEmpty()) {
            info.add("Related methods: ${context.preservedMethods.size} potentially related methods in context")
        }

        if (context.preservedFields.isNotEmpty()) {
            info.add("Related fields: ${context.preservedFields.size} fields in context")
        }

        if (context.isTruncated) {
            info.add("Note: File content has been truncated to fit context window")
        }

        return if (info.isNotEmpty()) {
            "Additional context:\n" + info.joinToString("\n")
        } else {
            ""
        }
    }

    /**
     * Build section with related class contents
     */
    private fun buildRelatedClassesSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        println("ZestLeanPromptBuilder: Building related classes section")
        println("  Used classes: ${context.usedClasses.size} - ${context.usedClasses.take(5).joinToString(", ")}")
        println("  Related class contents available: ${context.relatedClassContents.size}")
        
        // If we have used classes but no content loaded, we need to load them
        if (context.usedClasses.isEmpty()) {
            println("  No used classes found, returning empty string")
            return ""
        }

        val sb = StringBuilder()
        sb.append("\nRelated classes used in the current method:\n")
        
        // For each used class, either use loaded content or try to load it
        val classesToShow = mutableListOf<Pair<String, String>>()
        
        // First, add all classes that have content already loaded
        context.relatedClassContents.forEach { (className, content) ->
            classesToShow.add(className to content)
        }
        
        // Then, for used classes without content, try to load them synchronously
        val missingClasses = context.usedClasses - context.relatedClassContents.keys
        if (missingClasses.isNotEmpty()) {
            println("  Missing content for ${missingClasses.size} used classes, attempting to load...")
            missingClasses.forEach { className ->
                val classContent = loadClassStructure(className)
                if (classContent.isNotEmpty()) {
                    classesToShow.add(className to classContent)
                }
            }
        }
        
        // Sort and limit to most relevant classes
        val relevantClasses = classesToShow
            .sortedBy { (className, _) -> 
                // Prioritize classes that are directly used
                if (className in context.calledMethods) 0 else 1
            }
            .take(5) // Limit to 5 most relevant classes
        
        println("  Including ${relevantClasses.size} relevant classes in prompt")
        
        relevantClasses.forEach { (className, classContent) ->
            println("  Adding class: $className (content length: ${classContent.length})")
            sb.append("\n```java\n")
            sb.append("// Class: $className\n")
            sb.append(classContent)
            sb.append("\n```\n")
        }
        
        val result = sb.toString()
        println("  Final related classes section length: ${result.length}")
        return result
    }
    
    /**
     * Load class structure synchronously for prompt building
     */
    private fun loadClassStructure(className: String): String {
        if (className.isEmpty()) return ""
        
        return try {
            ApplicationManager.getApplication().runReadAction<String> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.allScope(project))
                
                if (psiClass != null && !isJavaLangClass(psiClass)) {
                    buildString {
                        ClassAnalyzer.appendClassStructure(this, psiClass)
                    }
                } else {
                    ""
                }
            }
        } catch (e: Exception) {
            println("  Error loading class structure for $className: ${e.message}")
            ""
        }
    }
    
    private fun isJavaLangClass(psiClass: PsiClass): Boolean {
        val qualifiedName = psiClass.qualifiedName ?: return false
        return qualifiedName.startsWith("java.") ||
                qualifiedName.startsWith("javax.") ||
                qualifiedName.startsWith("kotlin.")
    }

    /**
     * Build a simplified prompt focused on completing the current line
     * (Keep for backward compatibility)
     */
    fun buildReasoningPrompt(context: ZestLeanContextCollectorPSI.LeanContext): String {
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