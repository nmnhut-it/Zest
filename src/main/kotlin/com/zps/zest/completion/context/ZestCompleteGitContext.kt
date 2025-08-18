package com.zps.zest.completion.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.zps.zest.browser.utils.GitCommandExecutor

/**
 * Collects complete git context including all modified files with actual code diffs
 */
class ZestCompleteGitContext(private val project: Project) {
    private val logger = Logger.getInstance(ZestCompleteGitContext::class.java)
    
    data class CompleteGitInfo(
        val recentCommitMessage: String?,
        val allModifiedFiles: List<ModifiedFile>,
        val currentFileStatus: String,
        val actualDiffs: List<FileDiff> // NEW: Actual code diffs
    )
    
    data class ModifiedFile(
        val path: String,
        val status: String, // M, A, D, R
        val summary: String? = null // Brief description of changes
    )
    
    data class FileDiff(
        val filePath: String,
        val status: String,
        val diffContent: String, // Actual diff content
        val isRelevant: Boolean = true // Filter out noise
    )
    
    fun getAllModifiedFiles(): CompleteGitInfo {
        return try {
            // Get last commit message
            val lastCommit = GitCommandExecutor.executeWithGenericException(
                project.basePath!!, 
                "git log -1 --pretty=format:\"%s\""
            ).trim()
            
            // Get ALL modified files with status
            val statusOutput = GitCommandExecutor.executeWithGenericException(
                project.basePath!!, 
                "git status --porcelain"
            )
            
            val modifiedFiles = statusOutput.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val status = line.substring(0, 2).trim()
                    val path = line.substring(3)
                    val summary = getDetailedFileSummary(path, status)
                    ModifiedFile(path, status, summary)
                }
            
            // Get actual diffs for relevant files
            val actualDiffs = getActualDiffs(modifiedFiles)
            
            CompleteGitInfo(
                recentCommitMessage = if (lastCommit.isNotBlank()) lastCommit else null,
                allModifiedFiles = modifiedFiles,
                currentFileStatus = "editing",
                actualDiffs = actualDiffs
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            System.out.println("Git context collection was cancelled (normal behavior)")
            throw e
        } catch (e: Exception) {
            System.out.println("Failed to get git context: ${e.message}")
            CompleteGitInfo(null, emptyList(), "unknown", emptyList())
        }
    }
    
    private fun getActualDiffs(modifiedFiles: List<ModifiedFile>): List<FileDiff> {
        return modifiedFiles.mapNotNull { file ->
            try {
                when (file.status) {
                    "M" -> {
                        // Get actual diff for modified files
                        val diffOutput = GitCommandExecutor.executeWithGenericException(
                            project.basePath!!, 
                            "git diff \"${file.path}\""
                        )
                        
                        if (diffOutput.isNotBlank() && isRelevantFile(file.path)) {
                            FileDiff(
                                filePath = file.path,
                                status = file.status,
                                diffContent = cleanDiffOutput(diffOutput),
                                isRelevant = isRelevantFile(file.path)
                            )
                        } else null
                    }
                    "A" -> {
                        // For new files, show the content (limited)
                        val fileContent = getNewFileContent(file.path)
                        if (fileContent != null && isRelevantFile(file.path)) {
                            FileDiff(
                                filePath = file.path,
                                status = file.status,
                                diffContent = "NEW FILE:\n$fileContent",
                                isRelevant = true
                            )
                        } else null
                    }
                    else -> null // Skip deleted, renamed files for now
                }
            } catch (e: Exception) {
                logger.warn("Failed to get diff for ${file.path}", e)
                null
            }
        }
    }
    
    private fun cleanDiffOutput(diffOutput: String): String {
        val lines = diffOutput.lines()
        val cleanedLines = mutableListOf<String>()
        
        for (line in lines) {
            when {
                line.startsWith("diff --git") -> continue // Skip git headers
                line.startsWith("index ") -> continue // Skip index lines
                line.startsWith("--- ") || line.startsWith("+++ ") -> {
                    // Keep file markers but clean them up
                    cleanedLines.add(line)
                }
                line.startsWith("@@") -> {
                    // Keep hunk headers
                    cleanedLines.add(line)
                }
                line.startsWith("+") || line.startsWith("-") || line.startsWith(" ") -> {
                    // Keep actual diff content
                    cleanedLines.add(line)
                }
            }
        }
        
        // Limit diff size to avoid overwhelming the prompt
        val limitedLines = cleanedLines.take(50).toMutableList() // Convert to mutable list
        if (cleanedLines.size > 50) {
            limitedLines.add("... (diff truncated)")
        }
        
        return limitedLines.joinToString("\n")
    }
    
    private fun isRelevantFile(path: String): Boolean {
        val relevantExtensions = setOf(
            "java", "kt", "js", "ts", "py", "html", "css", "xml", 
            "json", "yaml", "yml", "sql", "groovy", "scala"
        )
        val extension = path.substringAfterLast('.', "").lowercase()
        
        // Skip certain noise files
        val skipPatterns = listOf(
            ".git", "node_modules", ".idea", "target/", "build/",
            ".class", ".jar", ".log", "package-lock.json"
        )
        
        return relevantExtensions.contains(extension) && 
               skipPatterns.none { path.contains(it) }
    }
    
    private fun getNewFileContent(path: String): String? {
        return try {
            val fullPath = "${project.basePath}/$path"
            val file = java.io.File(fullPath)
            if (file.exists() && file.length() < 10000) { // Limit to 10KB
                val content = file.readText()
                // Limit lines for prompt
                val lines = content.lines()
                if (lines.size > 30) {
                    lines.take(30).joinToString("\n") + "\n... (file truncated)"
                } else {
                    content
                }
            } else null
        } catch (e: Exception) {
            logger.warn("Failed to read new file content for $path", e)
            null
        }
    }
    
    private fun getDetailedFileSummary(path: String, status: String): String? {
        return try {
            when (status) {
                "M" -> {
                    // Get actual diff content to understand what changed
                    val diffOutput = GitCommandExecutor.executeWithGenericException(
                        project.basePath!!, 
                        "git diff --unified=0 \"$path\""
                    )
                    
                    // Parse diff to extract meaningful changes
                    extractMeaningfulChanges(diffOutput, path)
                }
                "A" -> "new file"
                "D" -> "deleted"
                "R" -> "renamed"
                "??" -> "untracked"
                else -> status
            }
        } catch (e: Exception) {
            // Fallback to basic line count if detailed analysis fails
            getBasicFileSummary(path, status)
        }
    }
    
    private fun extractMeaningfulChanges(diffOutput: String, path: String): String? {
        if (diffOutput.isBlank()) return "modified"
        
        val changes = mutableListOf<String>()
        val lines = diffOutput.lines()
        
        // Look for added/removed methods, classes, fields
        lines.forEach { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") -> {
                    extractAddedContent(line.substring(1).trim())?.let { changes.add("+ $it") }
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    extractRemovedContent(line.substring(1).trim())?.let { changes.add("- $it") }
                }
            }
        }
        
        // Limit to most important changes
        val significantChanges = changes.take(3)
        
        return if (significantChanges.isNotEmpty()) {
            significantChanges.joinToString("; ")
        } else {
            // Fallback to line counts if no significant patterns found
            getBasicFileSummary(path, "M")
        }
    }
    
    private fun extractAddedContent(line: String): String? {
        return when {
            // Method declarations
            line.contains("public") && (line.contains("(") || line.contains("void") || line.contains("return")) -> {
                val methodMatch = Regex("""(public|private|protected)?\s*\w+\s+(\w+)\s*\(""").find(line)
                methodMatch?.let { "method ${it.groupValues[2]}" }
            }
            // Class declarations  
            line.contains("class ") -> {
                val classMatch = Regex("""class\s+(\w+)""").find(line)
                classMatch?.let { "class ${it.groupValues[1]}" }
            }
            // Field declarations
            line.contains("=") && (line.contains("static") || line.contains("final") || line.contains("private")) -> {
                val fieldMatch = Regex("""\s*\w+\s+(\w+)\s*=""").find(line)
                fieldMatch?.let { "field ${it.groupValues[1]}" }
            }
            // Import statements
            line.startsWith("import ") -> {
                val importMatch = Regex("""import\s+([^;]+)""").find(line)
                importMatch?.let { "import ${it.groupValues[1].split(".").lastOrNull()}" }
            }
            // Configuration or properties
            line.contains("=") && !line.contains("(") -> {
                val configMatch = Regex("""(\w+)\s*=""").find(line)
                configMatch?.let { "config ${it.groupValues[1]}" }
            }
            else -> null
        }
    }
    
    private fun extractRemovedContent(line: String): String? {
        return when {
            line.contains("public") && line.contains("(") -> {
                val methodMatch = Regex("""(public|private|protected)?\s*\w+\s+(\w+)\s*\(""").find(line)
                methodMatch?.let { "removed method ${it.groupValues[2]}" }
            }
            line.contains("class ") -> {
                val classMatch = Regex("""class\s+(\w+)""").find(line)
                classMatch?.let { "removed class ${it.groupValues[1]}" }
            }
            line.contains("import ") -> "removed import"
            else -> null
        }
    }
    
    private fun getBasicFileSummary(path: String, status: String): String? {
        return try {
            when (status) {
                "M" -> {
                    // Get number of lines changed as fallback
                    val diffStats = GitCommandExecutor.executeWithGenericException(
                        project.basePath!!, 
                        "git diff --numstat \"$path\""
                    )
                    val parts = diffStats.split("\t")
                    if (parts.size >= 2) {
                        "${parts[0]}+ ${parts[1]}- lines"
                    } else "modified"
                }
                "A" -> "new file"
                "D" -> "deleted"
                "R" -> "renamed"
                "??" -> "untracked"
                else -> status
            }
        } catch (e: Exception) {
            null
        }
    }
}
