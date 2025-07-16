package com.zps.zest.completion.prompt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.zps.zest.ClassAnalyzer
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI
import com.zps.zest.completion.context.ZestCompleteGitContext

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
                "hasVcsContext" to (context.uncommittedChanges != null),
                "modifiedFilesCount" to (context.uncommittedChanges?.allModifiedFiles?.size ?: 0),
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
        val vcsSection = buildVcsContextSection(context)
        
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
     * Build VCS context section showing top 3 most relevant uncommitted changes
     */
    private fun buildVcsContextSection(context: ZestLeanContextCollectorPSI.LeanContext): String {
        val gitInfo = context.uncommittedChanges ?: return ""
        
        if (gitInfo.allModifiedFiles.isEmpty()) {
            return ""
        }
        
        val sb = StringBuilder()
        sb.append("**Uncommitted changes in workspace** _(may or may not be related to current task)_\n\n")
        
        // Calculate relevance scores for all modified files
        val rankedFiles = rankFilesByRelevance(
            currentFile = context.fileName,
            modifiedFiles = gitInfo.allModifiedFiles,
            actualDiffs = gitInfo.actualDiffs
        )
        
        // Take top 3 most relevant files
        val topFiles = rankedFiles.take(3)
        
        // Show summary of all changes
        sb.append("### Modified Files\n")
        sb.append("_Showing ${topFiles.size} of ${gitInfo.allModifiedFiles.size} files (ranked by path similarity):_\n\n")
        topFiles.forEach { (file, score) ->
            val status = when (file.status) {
                "M" -> "🔄 Modified"
                "A" -> "➕ Added"
                "D" -> "➖ Deleted"
                else -> file.status
            }
            sb.append("- **$status:** `${file.path}`")
            if (file.summary != null) {
                sb.append(" — _${file.summary}_")
            }
            sb.append("\n")
        }
        
        sb.append("\n> **Note:** These changes provide context about ongoing work but may not directly relate to the completion needed.\n")
        
        // Show truncated diffs for top files
        if (topFiles.any { (file, _) -> gitInfo.actualDiffs.any { it.filePath == file.path } }) {
            sb.append("\n### Change Details\n")
            
            topFiles.forEach { (file, _) ->
                val diff = gitInfo.actualDiffs.find { it.filePath == file.path }
                if (diff != null && diff.diffContent.isNotBlank()) {
                    sb.append("\n#### `${file.path}`\n")
                    sb.append("```diff\n")
                    sb.append(truncateDiff(diff.diffContent, 10))
                    sb.append("\n```\n")
                }
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Rank files by relevance to current file using cosine similarity
     */
    private fun rankFilesByRelevance(
        currentFile: String,
        modifiedFiles: List<ZestCompleteGitContext.ModifiedFile>,
        actualDiffs: List<ZestCompleteGitContext.FileDiff>
    ): List<Pair<ZestCompleteGitContext.ModifiedFile, Double>> {
        
        return modifiedFiles.map { file ->
            val relevanceScore = calculateFileRelevance(currentFile, file.path)
            file to relevanceScore
        }.sortedByDescending { it.second }
    }
    
    /**
     * Calculate relevance score between current file and a modified file
     */
    private fun calculateFileRelevance(currentFile: String, modifiedFile: String): Double {
        // Extract file components
        val currentParts = extractFileComponents(currentFile)
        val modifiedParts = extractFileComponents(modifiedFile)
        
        var score = 0.0
        
        // Same directory gets high score
        if (currentParts.directory == modifiedParts.directory) {
            score += 0.5
        }
        
        // Similar package/path structure
        val pathSimilarity = calculatePathSimilarity(currentParts.directory, modifiedParts.directory)
        score += pathSimilarity * 0.3
        
        // Similar file names (using existing string similarity)
        val nameSimilarity = calculateStringSimilarity(currentParts.baseName, modifiedParts.baseName)
        score += nameSimilarity * 0.2
        
        return score
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
     * Calculate similarity between two file paths
     */
    private fun calculatePathSimilarity(path1: String, path2: String): Double {
        val parts1 = path1.split('/')
        val parts2 = path2.split('/')
        
        var commonParts = 0
        val minLength = minOf(parts1.size, parts2.size)
        
        for (i in 0 until minLength) {
            if (parts1[i] == parts2[i]) {
                commonParts++
            }
        }
        
        return if (minLength > 0) commonParts.toDouble() / minLength else 0.0
    }
    
    /**
     * Truncate diff content to specified number of lines
     */
    private fun truncateDiff(diffContent: String, maxLines: Int): String {
        val lines = diffContent.lines()
        return if (lines.size <= maxLines) {
            diffContent
        } else {
            lines.take(maxLines).joinToString("\n") + "\n... (diff truncated, ${lines.size - maxLines} more lines)"
        }
    }
    
    /**
     * Calculate string similarity using n-grams (from existing code)
     */
    private fun calculateStringSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val ngrams1 = extractNgrams(s1.lowercase(), 2)
        val ngrams2 = extractNgrams(s2.lowercase(), 2)
        
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0
        
        val intersection = ngrams1.intersect(ngrams2).size
        val union = (ngrams1 + ngrams2).distinct().size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    /**
     * Extract n-grams from text
     */
    private fun extractNgrams(text: String, n: Int): Set<String> {
        if (text.length < n) return setOf(text)
        
        return (0..text.length - n).map { i ->
            text.substring(i, i + n)
        }.toSet()
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
        println("  Used classes: ${context.usedClasses.size} - ${context.usedClasses.take(5).joinToString(", ")}")
        println("  Related class contents available: ${context.relatedClassContents.size}")
        
        // If we have used classes but no content loaded, we need to load them
        if (context.usedClasses.isEmpty()) {
            println("  No used classes found, returning empty string")
            return ""
        }

        val sb = StringBuilder()
        sb.append("Classes used in the current method:\n\n")
        
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
        
        relevantClasses.forEachIndexed { index, (className, classContent) ->
            println("  Adding class: $className (content length: ${classContent.length})")
            sb.append("### ${index + 1}. Class: `$className`\n")
            sb.append("```java\n")
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

}