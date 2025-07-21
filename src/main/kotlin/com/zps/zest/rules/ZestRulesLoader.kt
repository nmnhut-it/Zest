package com.zps.zest.rules

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service responsible for loading custom rules from zest_rules.md file
 */
class ZestRulesLoader(private val project: Project) {
    private val logger = Logger.getInstance(ZestRulesLoader::class.java)
    
    companion object {
        const val RULES_FILE_NAME = "zest_rules.md"
        const val DEFAULT_RULES_HEADER = """
# Zest Custom Rules

Define your custom LLM rules below. These rules will be included at the top of all prompts sent to the LLM.
You can use this to:
- Define coding standards specific to your project
- Add domain-specific knowledge
- Set preferred coding patterns
- Include project-specific requirements

## Example Rules:

<!-- 
- Always use camelCase for variable names
- Prefer const over let for immutable values
- Include JSDoc comments for all public methods
- Follow the project's error handling patterns
-->

## Your Rules:

"""
    }
    
    /**
     * Load custom rules from the project's zest_rules.md file
     * @return The custom rules content or null if no rules file exists
     */
    fun loadCustomRules(): String? {
        try {
            // First, try to find the rules file in the project
            val rulesFile = findRulesFile()
            
            if (rulesFile == null) {
                logger.info("No $RULES_FILE_NAME found in project")
                return null
            }
            
            if (!rulesFile.exists()) {
                logger.info("$RULES_FILE_NAME does not exist at: ${rulesFile.path}")
                return null
            }
            
            // Read the file content
            val content = String(rulesFile.contentsToByteArray(), Charsets.UTF_8)
            
            // Extract only the rules portion (skip the header and example sections)
            val rulesContent = extractRulesContent(content)
            
            if (rulesContent.isBlank()) {
                logger.info("$RULES_FILE_NAME exists but contains no custom rules")
                return null
            }
            
            logger.info("Successfully loaded custom rules from $RULES_FILE_NAME")
            return rulesContent.trim()
            
        } catch (e: Exception) {
            logger.error("Failed to load custom rules from $RULES_FILE_NAME", e)
            return null
        }
    }
    
    /**
     * Create a default zest_rules.md file if it doesn't exist
     */
    fun createDefaultRulesFile(): Boolean {
        try {
            val projectBasePath = project.basePath ?: return false
            val rulesPath = Paths.get(projectBasePath, RULES_FILE_NAME)
            
            if (Files.exists(rulesPath)) {
                logger.info("$RULES_FILE_NAME already exists")
                return false
            }
            
            // Create the file with default content
            Files.write(rulesPath, DEFAULT_RULES_HEADER.toByteArray())
            
            // Refresh the VFS to make the file visible in the IDE
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(rulesPath)
            
            logger.info("Created default $RULES_FILE_NAME at: $rulesPath")
            return true
            
        } catch (e: Exception) {
            logger.error("Failed to create default $RULES_FILE_NAME", e)
            return false
        }
    }
    
    /**
     * Find the rules file in the project
     */
    private fun findRulesFile(): VirtualFile? {
        val baseDir = project.baseDir ?: return null
        
        // Look for the file in the project root
        return baseDir.findChild(RULES_FILE_NAME)
    }
    
    /**
     * Extract the actual rules content from the markdown file
     * Skip headers and example sections
     */
    private fun extractRulesContent(fullContent: String): String {
        val lines = fullContent.lines()
        var inRulesSection = false
        var inExampleSection = false
        val rulesLines = mutableListOf<String>()
        
        for (line in lines) {
            when {
                // Check for the "Your Rules:" section
                line.trim().startsWith("## Your Rules:") || 
                line.trim().startsWith("## Custom Rules:") ||
                line.trim().startsWith("## Project Rules:") -> {
                    inRulesSection = true
                    inExampleSection = false
                    continue
                }
                
                // Check for example section markers
                line.trim().startsWith("## Example") || 
                line.trim().startsWith("<!-- ") -> {
                    inExampleSection = true
                    continue
                }
                
                // End of example section
                line.trim() == "-->" -> {
                    inExampleSection = false
                    continue
                }
                
                // Collect rules content
                inRulesSection && !inExampleSection -> {
                    // Skip empty lines at the beginning
                    if (rulesLines.isNotEmpty() || line.isNotBlank()) {
                        rulesLines.add(line)
                    }
                }
            }
        }
        
        // If no specific rules section found, try to extract everything after the header
        if (rulesLines.isEmpty()) {
            var pastHeader = false
            for (line in lines) {
                if (pastHeader && !line.startsWith("#") && !line.contains("<!--")) {
                    if (rulesLines.isNotEmpty() || line.isNotBlank()) {
                        rulesLines.add(line)
                    }
                } else if (line.contains("Define your custom LLM rules below")) {
                    pastHeader = true
                }
            }
        }
        
        return rulesLines.joinToString("\n").trim()
    }
    
    /**
     * Check if rules file exists
     */
    fun rulesFileExists(): Boolean {
        return findRulesFile() != null
    }
    
    /**
     * Get the path to the rules file
     */
    fun getRulesFilePath(): String {
        return Paths.get(project.basePath ?: "", RULES_FILE_NAME).toString()
    }
}
