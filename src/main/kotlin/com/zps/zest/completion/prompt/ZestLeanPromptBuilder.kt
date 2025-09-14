package com.zps.zest.completion.prompt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.zps.zest.ClassAnalyzer
import com.zps.zest.ConfigurationManager
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI

/**
 * Builds prompts for lean completion strategy with full file context
 * Updated to support structured prompts for better caching
 */
class ZestLeanPromptBuilder(private val project: Project) {

    companion object {
        private const val LEAN_SYSTEM_PROMPT =
            """You are an expert code writer. You are three steps ahead of user. You help user by giving code they are going to type at [CURSOR] position. 
## Rules:
1. Analyze file context and patterns
2. Complete ONLY what comes after `[CURSOR]`
3. Match existing code style and indentation
4. Aim at completing what user are trying to type at the cursor position - long responses are unnecessary. You can just give short line of code with variable names, language keywords ... limit it to 4-5 words.  
## Response Format:
 
<code>
[your code here]
</code>
**Note:** Never include `[CURSOR]` tag or code before cursor in the response."""
    }

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

    private fun buildEnhancedUserPrompt(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val config = ConfigurationManager.getInstance(project)
        val contextInfo = if (config.isContextAnalysisSectionIncluded()) buildContextInfo(context) else ""
        val relatedClasses = if (config.isRelatedClassesSectionIncluded()) buildRelatedClassesSection(context) else ""
        val astPatterns = if (config.isAstPatternsSectionIncluded()) buildAstPatternSection(context) else ""
        val targetLine = if (config.isTargetLineSectionIncluded()) extractLineWithCursor(context.markedContent, context.cursorOffset) else ""

        return buildString {
            if (config.isFileInfoSectionIncluded()) {
                append(fileInfoSection(context))
            }
            if (config.isFrameworkSectionIncluded()) {
                append(frameworkSection(context))
            }
            if (config.isContextAnalysisSectionIncluded()) {
                append(contextAnalysisSection(contextInfo))
            }
            if (config.isVcsSectionIncluded()) {
                append(vcsSection())
            }
            // Current file section is always included as it's essential
            append(currentFileSection(context))
            if (config.isRelatedClassesSectionIncluded()) {
                append(relatedClassesSection(relatedClasses))
            }
            if (config.isAstPatternsSectionIncluded()) {
                append(astPatternsSection(astPatterns))
            }
            if (config.isTargetLineSectionIncluded()) {
                append(targetLineSection(targetLine))
            }
            append("\n**Task:** Provide completion following the response format.")
        }
    }

    private fun fileInfoSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        return buildString {
            append("## File Information\n")
            append("- **File:** ${context.fileName}\n")
            append("- **Language:** ${context.language}\n")
        }
    }

    private fun frameworkSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val instructions = context.syntaxInstructions
        if (instructions.isNullOrBlank()) return ""
        return "\n### Framework-Specific Instructions\n$instructions\n"
    }

    private fun contextAnalysisSection(info: String): String {
        if (info.isBlank()) return ""
        return "\n### Context Analysis\n$info\n"
    }

    private fun vcsSection(): String {
        val vcsSection = ""
        if (vcsSection.isBlank()) return ""
        return "\n### Version Control Context\n$vcsSection"
    }

    private fun currentFileSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        return buildString {
            append("\n## Current File Content\n")
            append("```${context.language.lowercase()}\n")
            append(context.markedContent)
            append("\n```\n")
        }
    }

    private fun relatedClassesSection(content: String): String {
        if (content.isBlank()) return ""
        return "\n## Related Classes\n$content"
    }

    private fun astPatternsSection(content: String): String {
        if (content.isBlank()) return ""
        return "\n## Similar Code Patterns\n$content"
    }

    private fun targetLineSection(line: String): String {
        return buildString {
            append("\n## Target Line\n")
            append("The line containing `[CURSOR]` is:\n")
            append("```\n")
            append(line)
            append("\n```\n")
        }
    }

    private fun extractLineWithCursor(markedContent: String, cursorOffset: Int): String {
        return markedContent.lines().firstOrNull { it.contains("[CURSOR]") }
            ?: "Unable to extract line with cursor"
    }

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

    private data class FileComponents(
        val directory: String,
        val baseName: String,
        val extension: String
    )

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

    private fun buildRelatedClassesSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        println("ZestLeanPromptBuilder: Building related classes section")
        val classesToUse = selectClasses(context)
        if (classesToUse.isEmpty()) {
            println("  No classes found, returning empty string")
            return ""
        }

        val classesToShow = collectClassesToShow(classesToUse, context)
        val relevantClasses = classesToShow.take(5)
        println("  Including ${relevantClasses.size} relevant classes in prompt")

        val result = buildClassesText(relevantClasses)
        println("  Final related classes section length: ${result.length}")
        return result
    }

    private fun selectClasses(context: ZestLeanContextCollectorPSI.LeanContext): List<String> {
        return if (context.rankedClasses.isNotEmpty()) {
            println("  Using ${context.rankedClasses.size} ranked classes")
            context.rankedClasses
        } else {
            println("  No ranked classes, using ${context.usedClasses.size} used classes")
            context.usedClasses.toList()
        }
    }

    private fun collectClassesToShow(
        classesToUse: List<String>,
        context: ZestLeanContextCollectorPSI.LeanContext
    ): List<Triple<String, String, Double>> {
        val classesToShow = mutableListOf<Triple<String, String, Double>>()
        classesToUse.forEach { className ->
            val content = context.relatedClassContents[className]
            val score = context.relevanceScores[className] ?: 0.0
            if (content != null) {
                classesToShow.add(Triple(className, content, score))
            } else {
                val classContent = loadClassStructure(className)
                if (classContent.isNotEmpty()) {
                    classesToShow.add(Triple(className, classContent, score))
                }
            }
        }
        return classesToShow
    }

    private fun buildClassesText(classes: List<Triple<String, String, Double>>): String {
        val sb = StringBuilder()
        sb.append("Classes used in the current method:\n\n")
        classes.forEachIndexed { index, (className, classContent, score) ->
            println("  Adding class: $className (score: ${String.format("%.2f", score)}, content length: ${classContent.length})")
            sb.append("### ${index + 1}. Class: `$className`")
            if (score > 0) sb.append(" (relevance: ${String.format("%.2f", score)})")
            sb.append("\n```java\n")
            sb.append(classContent)
            sb.append("\n```\n\n")
        }
        return sb.toString()
    }

    private fun loadClassStructure(className: String): String {
        if (className.isEmpty()) return ""
        return try {
            ApplicationManager.getApplication().runReadAction<String> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.allScope(project))
                if (psiClass != null && !isJavaLangClass(psiClass)) {
                    buildString { ClassAnalyzer.appendClassStructure(this, psiClass) }
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

    private fun buildAstPatternSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        return ""
    }

    private fun truncateCode(code: String, maxLength: Int): String {
        return if (code.length <= maxLength) code else code.take(maxLength) + "\n... (truncated)"
    }
}