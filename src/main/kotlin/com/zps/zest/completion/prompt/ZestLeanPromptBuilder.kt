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
        // Shortened system prompt with markdown formatting
        private const val LEAN_SYSTEM_PROMPT =
            """You are an expert code completion assistant. You are three steps ahead of user. You help user by giving code they are going to type at [CURSOR] position. Complete the code at `[CURSOR]` position.

## Rules:
1. Analyze file context and patterns
2. Complete ONLY what comes after `[CURSOR]`
3. Match existing code style and indentation
4. Aim at completing what user are trying to type at the cursor position - long responses are unnecessary. You can just give short line of code with variable names, language keywords ... limit it to 4-5 words.  

## Response Format:
 
<code>
[your code here]
</code>

**Note:** Never include `[CURSOR]` tag or code before cursor in completion."""
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
                "hasAstPatterns" to context.astPatternMatches.isNotEmpty(),
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
        val vcsSection = "";
        val astPatternSection = buildAstPatternSection(context)
        
        // Extract the line containing the cursor for AI to repeat
        val lineWithCursor = extractLineWithCursor(context.markedContent, context.cursorOffset)

        return buildString {
            append("## File Information\n")
            append("- **File:** ${context.fileName}\n")
            append("- **Language:** ${context.language}\n")
            
            // Add syntax instructions if present
            if (!context.syntaxInstructions.isNullOrBlank()) {
                append("\n### Framework-Specific Instructions\n")
                append(context.syntaxInstructions)
                append("\n")
            }
            
            if (contextInfo.isNotBlank()) {
                append("\n### Context Analysis\n")
                append(contextInfo)
                append("\n")
            }
            
            // Add VCS context before file content
            if (vcsSection.isNotBlank()) {
                append("\n### Version Control Context\n")
                append(vcsSection)
            }
            
            append("\n## Current File Content\n")
            append("```${context.language.lowercase()}\n")
            append(context.markedContent)
            append("\n```\n")
            
            if (relatedClassesSection.isNotBlank()) {
                append("\n## Related Classes\n")
                append(relatedClassesSection)
            }
            
            
            // Add AST pattern matches
            if (astPatternSection.isNotBlank()) {
                append("\n## Similar Code Patterns\n")
                append(astPatternSection)
            }
            
            // Add the line with cursor for AI to repeat
            append("\n## Target Line\n")
            append("The line containing `[CURSOR]` is:\n")
            append("```\n")
            append(lineWithCursor)
            append("\n```\n")
            
            append("\n**Task:** Provide completion following the response format.")
        }
    }
    
    /**
     * Extract the line containing the cursor from marked content
     */
    private fun extractLineWithCursor(markedContent: String, cursorOffset: Int): String {
        val lines = markedContent.lines()
        for (line in lines) {
            if (line.contains("[CURSOR]")) {
                return line
            }
        }
        // Fallback: return a portion around cursor if tag not found
        return "Unable to extract line with cursor"
    }

    /**
     * Extract file path components for comparison
     */
    private fun extractFileComponents(filePath: String): FileComponents {
        val path = filePath.replace('\\', '/')
        val lastSlash = path.lastIndexOf('/')
        val directory = if (lastSlash > 0) path.substring(0, lastSlash) else ""
        val fileName = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
        val lastDot = fileName.lastIndexOf('.')
        val baseName = if (lastDot > 0) fileName.substring(0, lastDot) else fileName
        val extension = if (lastDot > 0) fileName.substring(lastDot + 1) else ""
        
        return FileComponents(directory, baseName, extension)
    }

    /**
     * Data class for file components
     */
    private data class FileComponents(
        val directory: String,
        val baseName: String,
        val extension: String
    )

    /**
     * Build enhanced context information including analysis results
     */
    private fun buildContextInfo(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val info = mutableListOf<String>()

        if (context.contextType != ZestLeanContextCollectorPSI.CursorContextType.UNKNOWN) {
            info.add("- **Context type:** ${context.contextType.name.lowercase().replace('_', ' ')}")
        }

        if (context.calledMethods.isNotEmpty()) {
            info.add("- **Called methods:** `${context.calledMethods.take(10).joinToString("`, `")}`")
        }

        if (context.usedClasses.isNotEmpty()) {
            info.add("- **Used classes:** `${context.usedClasses.take(5).joinToString("`, `")}`")
        }

        if (context.preservedMethods.isNotEmpty()) {
            info.add("- **Related methods in file:** ${context.preservedMethods.size} methods preserved")
        }

        if (context.preservedFields.isNotEmpty()) {
            info.add("- **Related fields:** ${context.preservedFields.size} fields in context")
        }

        if (context.isTruncated) {
            info.add("- **Note:** File content has been truncated to fit context window")
        }

        return info.joinToString("\n")
    }

    /**
     * Build section with related class contents
     */
    private fun buildRelatedClassesSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        println("ZestLeanPromptBuilder: Building related classes section")
        
        // Use ranked classes if available, otherwise fall back to used classes
        val classesToUse = if (context.rankedClasses.isNotEmpty()) {
            println("  Using ${context.rankedClasses.size} ranked classes")
            context.rankedClasses
        } else {
            println("  No ranked classes, using ${context.usedClasses.size} used classes")
            context.usedClasses.toList()
        }
        
        if (classesToUse.isEmpty()) {
            println("  No classes found, returning empty string")
            return ""
        }

        val sb = StringBuilder()
        sb.append("Classes used in the current method:\n\n")
        
        // For each class, either use loaded content or try to load it
        val classesToShow = mutableListOf<Triple<String, String, Double>>()
        
        // Process classes in ranked order
        classesToUse.forEach { className ->
            val content = context.relatedClassContents[className]
            val score = context.relevanceScores[className] ?: 0.0
            
            if (content != null) {
                classesToShow.add(Triple(className, content, score))
            } else {
                // Try to load missing content
                val classContent = loadClassStructure(className)
                if (classContent.isNotEmpty()) {
                    classesToShow.add(Triple(className, classContent, score))
                }
            }
        }
        
        // Classes are already ranked by relevance, just take what we have
        val relevantClasses = classesToShow.take(5) // Limit to 5 most relevant classes
        
        println("  Including ${relevantClasses.size} relevant classes in prompt")
        
        relevantClasses.forEachIndexed { index, (className, classContent, score) ->
            println("  Adding class: $className (score: ${String.format("%.2f", score)}, content length: ${classContent.length})")
            sb.append("### ${index + 1}. Class: `$className`")
            if (score > 0) {
                sb.append(" (relevance: ${String.format("%.2f", score)})")
            }
            sb.append("\n```java\n")
            sb.append(classContent)
            sb.append("\n```\n\n")
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
     * Build AST pattern section from matches
     */
    private fun buildAstPatternSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        return "";
    }
    
    /**
     * Truncate code to specified length
     */
    private fun truncateCode(code: String, maxLength: Int): String {
        return if (code.length <= maxLength) {
            code
        } else {
            code.take(maxLength) + "\n... (truncated)"
        }
    }

}