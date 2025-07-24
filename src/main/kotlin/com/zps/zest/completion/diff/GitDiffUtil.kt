package com.zps.zest.completion.diff

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.zps.zest.browser.GitService
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Utility for showing git diffs using the new FileDiffDialog
 * Delegates to GitService for git operations
 */
object GitDiffUtil {
    private val logger = Logger.getInstance(GitDiffUtil::class.java)
    
    /**
     * Shows the git diff for a file compared to HEAD
     */
    fun showGitDiff(project: Project, filePath: String, fileStatus: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullPath = if (File(filePath).isAbsolute) {
                    Paths.get(filePath)
                } else {
                    Paths.get(project.basePath ?: "", filePath)
                }
                
                val file = fullPath.toFile()
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(file.absolutePath)
                
                // Get current content
                val currentContent = if (file.exists()) {
                    Files.readString(fullPath)
                } else {
                    ""
                }
                
                // Use GitService to get the file diff
                val gitService = GitService(project)
                val diffRequest = JsonObject().apply {
                    addProperty("filePath", filePath)
                    addProperty("status", fileStatus)
                }
                
                val diffResponse = gitService.getFileDiff(diffRequest)
                val gitDiff = try {
                    val gson = com.google.gson.Gson()
                    val response = gson.fromJson(diffResponse, JsonObject::class.java)
                    if (response.get("success").asBoolean) {
                        response.get("diff").asString
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse git diff response", e)
                    ""
                }
                
                // Parse the git diff to extract original content
                val originalContent = extractOriginalContentFromDiff(gitDiff, fileStatus, currentContent)
                
                val (leftContent, rightContent, title) = when {
                    fileStatus.contains("deleted", ignoreCase = true) || fileStatus == "D" -> {
                        Triple(originalContent, "", "Git Diff: $filePath (Deleted)")
                    }
                    fileStatus.contains("added", ignoreCase = true) || fileStatus == "A" -> {
                        Triple("", currentContent, "Git Diff: $filePath (Added)")
                    }
                    else -> {
                        Triple(originalContent, currentContent, "Git Diff: $filePath (Modified)")
                    }
                }
                
                // Show diff dialog
                ApplicationManager.getApplication().invokeLater {
                    FileDiffDialog.show(
                        project = project,
                        virtualFile = virtualFile,
                        originalContent = leftContent,
                        modifiedContent = rightContent,
                        title = title,
                        showButtons = false  // No accept/reject for git diffs
                    )
                }
                
            } catch (e: Exception) {
                logger.error("Error showing git diff", e)
            }
        }
    }
    
    /**
     * Extracts the original content from a git diff
     */
    private fun extractOriginalContentFromDiff(gitDiff: String, fileStatus: String, currentContent: String): String {
        if (gitDiff.isEmpty()) return ""
        
        return when (fileStatus) {
            "A" -> "" // Added files have no original content
            "D" -> {
                // For deleted files, extract content from diff lines starting with '-'
                val lines = gitDiff.lines()
                val contentLines = mutableListOf<String>()
                var inContent = false
                
                for (line in lines) {
                    if (line.startsWith("@@")) {
                        inContent = true
                        continue
                    }
                    if (inContent && line.startsWith("-") && !line.startsWith("---")) {
                        contentLines.add(line.substring(1)) // Remove the '-' prefix
                    }
                }
                contentLines.joinToString("\n")
            }
            else -> {
                // For modified files, try to extract original content or fall back to current
                val lines = gitDiff.lines()
                val originalLines = mutableListOf<String>()
                var inContent = false
                
                for (line in lines) {
                    if (line.startsWith("@@")) {
                        inContent = true
                        continue
                    }
                    if (inContent) {
                        when {
                            line.startsWith(" ") -> originalLines.add(line.substring(1)) // Unchanged line
                            line.startsWith("-") && !line.startsWith("---") -> originalLines.add(line.substring(1)) // Removed line
                            // Skip added lines (start with '+')
                        }
                    }
                }
                
                if (originalLines.isNotEmpty()) {
                    originalLines.joinToString("\n")
                } else {
                    // Fallback: if we can't parse the diff, show current content as both sides
                    currentContent
                }
            }
        }
    }
}