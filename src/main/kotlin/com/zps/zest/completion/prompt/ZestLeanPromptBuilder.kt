package com.zps.zest.completion.prompt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
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
        // Shortened system prompt with code repetition requirement
        private const val LEAN_SYSTEM_PROMPT =
            """You are an expert code completion assistant. Complete the code at <CURSOR> position.

Rules:
1. Analyze file context and patterns
2. Complete ONLY what comes after <CURSOR>
3. Match existing code style and indentation
4. For multi-line completions (methods, blocks), include full logical unit

Response Format:
Line before cursor:
```
[exact line with cursor]
```

Completion:
<completion>
[your code here]
</completion>

Note: Never include <CURSOR> tag or code before cursor in completion."""
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
                "hasSyntaxInstructions" to !context.syntaxInstructions.isNullOrBlank(),
                "contextType" to context.contextType.name,
                "offset" to context.cursorOffset
            )
        )
    }

    /**
     * Build enhanced user prompt with file context and related classes
     */
    private fun buildEnhancedUserPrompt(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val contextInfo = buildContextInfo(context)
        val relatedClassesSection = buildRelatedClassesSection(context)
        
        // Extract the line containing the cursor for AI to repeat
        val lineWithCursor = extractLineWithCursor(context.markedContent, context.cursorOffset)

        return buildString {
            append("File: ${context.fileName}\n")
            append("Language: ${context.language}\n")
            
            // Add syntax instructions if present
            if (!context.syntaxInstructions.isNullOrBlank()) {
                append("\n${context.syntaxInstructions}\n")
            }
            
            if (contextInfo.isNotBlank()) {
                append(contextInfo)
            }
            
            append("\nFull file with cursor position:\n")
            append("```${context.language.lowercase()}\n")
            append(context.markedContent)
            append("\n```\n")
            
            append(relatedClassesSection)
            
            // Add the line with cursor for AI to repeat
            append("\nThe line containing <CURSOR> is:\n")
            append("```\n")
            append(lineWithCursor)
            append("\n```\n")
            
            append("\nProvide completion following the response format.")
        }
    }
    
    /**
     * Extract the line containing the cursor from marked content
     */
    private fun extractLineWithCursor(markedContent: String, cursorOffset: Int): String {
        val lines = markedContent.lines()
        for (line in lines) {
            if (line.contains("<CURSOR>")) {
                return line
            }
        }
        // Fallback: return a portion around cursor if tag not found
        return "Unable to extract line with cursor"
    }

    /**
     * Build enhanced context information including analysis results
     */
    private fun buildContextInfo(context: ZestLeanContextCollectorPSI.LeanContext): String {
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

}