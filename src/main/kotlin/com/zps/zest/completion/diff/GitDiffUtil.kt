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
                
                // Get the original content from git HEAD directly
                val originalContent = getOriginalContentFromGit(project, filePath, fileStatus)
                
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
     * Gets the original content from git HEAD for the file
     */
    private fun getOriginalContentFromGit(project: Project, filePath: String, fileStatus: String): String {
        return when (fileStatus) {
            "A" -> "" // Added files have no original content
            else -> {
                // For modified and deleted files, get the full original content from git HEAD
                try {
                    val gitService = GitService(project)
                    val projectPath = project.basePath ?: return ""
                    
                    // Use the same git command execution that GitService uses
                    val processBuilder = ProcessBuilder()
                    val command = "git show HEAD:\"$filePath\""
                    
                    if (System.getProperty("os.name").lowercase().contains("windows")) {
                        processBuilder.command("cmd.exe", "/c", command)
                    } else {
                        processBuilder.command("bash", "-c", command)
                    }
                    
                    processBuilder.directory(java.io.File(projectPath))
                    val process = processBuilder.start()
                    
                    // Read the output
                    val output = StringBuilder()
                    java.io.BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            output.append(line).append("\n")
                        }
                    }
                    
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        output.toString().removeSuffix("\n") // Remove trailing newline
                    } else {
                        logger.warn("Failed to get original content for $filePath from git HEAD")
                        ""
                    }
                } catch (e: Exception) {
                    logger.warn("Error getting original content from git HEAD for $filePath", e)
                    ""
                }
            }
        }
    }
}