package com.zps.zest.completion.prompts

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager

/**
 * Loads and parses custom prompts from .zest/custom_prompts.md
 */
class ZestCustomPromptsLoader(private val project: Project) {
    private val logger = Logger.getInstance(ZestCustomPromptsLoader::class.java)
    
    data class CustomPrompt(
        val shortcut: Int,  // 1-9 for Shift+1 through Shift+9
        val title: String,
        val prompt: String
    )
    
    /**
     * Load custom prompts from the configuration file
     */
    fun loadCustomPrompts(): List<CustomPrompt> {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            configManager.ensureZestFolderExists()
            
            val content = configManager.readZestConfigFile("custom_prompts.md")
            if (content == null) {
                logger.info("No custom prompts file found, using defaults")
                return parsePromptsContent(getDefaultCustomPromptsContent())
            }
            
            return parsePromptsContent(content)
        } catch (e: Exception) {
            logger.error("Failed to load custom prompts", e)
            return emptyList()
        }
    }
    
    /**
     * Parse the markdown content to extract prompts
     */
    private fun parsePromptsContent(content: String): List<CustomPrompt> {
        val prompts = mutableListOf<CustomPrompt>()
        val lines = content.lines()
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // Look for headers like "## Shift+1: Title"
            if (line.startsWith("## Shift+") && line.contains(":")) {
                try {
                    // Extract shortcut number
                    val shortcutMatch = Regex("## Shift\\+(\\d):\\s*(.+)").find(line)
                    if (shortcutMatch != null) {
                        val shortcutNum = shortcutMatch.groupValues[1].toInt()
                        val title = shortcutMatch.groupValues[2].trim()
                        
                        // Read the prompt description (next non-empty line)
                        var promptText = ""
                        i++
                        while (i < lines.size) {
                            val nextLine = lines[i].trim()
                            if (nextLine.isEmpty()) {
                                i++
                                continue
                            }
                            if (nextLine.startsWith("##")) {
                                // Hit the next prompt header
                                i--
                                break
                            }
                            promptText = nextLine
                            break
                        }
                        
                        if (shortcutNum in 1..9 && promptText.isNotEmpty()) {
                            prompts.add(CustomPrompt(
                                shortcut = shortcutNum,
                                title = title,
                                prompt = promptText
                            ))
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse prompt line: $line", e)
                }
            }
            i++
        }
        
        return prompts.sortedBy { it.shortcut }
    }
    
    /**
     * Get a specific prompt by shortcut number
     */
    fun getPromptByShortcut(shortcut: Int): CustomPrompt? {
        return loadCustomPrompts().find { it.shortcut == shortcut }
    }
    
    /**
     * Create or update a custom prompt
     */
    fun saveCustomPrompt(shortcut: Int, title: String, prompt: String): Boolean {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            val currentContent = configManager.readZestConfigFile("custom_prompts.md") 
                ?: getDefaultCustomPromptsContent()
            
            // Parse existing prompts
            val lines = currentContent.lines().toMutableList()
            var found = false
            var i = 0
            
            // Find and replace existing prompt
            while (i < lines.size) {
                val line = lines[i]
                if (line.trim().startsWith("## Shift+$shortcut:")) {
                    found = true
                    lines[i] = "## Shift+$shortcut: $title"
                    
                    // Replace the next non-empty line (the prompt)
                    i++
                    while (i < lines.size && lines[i].trim().isEmpty()) {
                        i++
                    }
                    if (i < lines.size && !lines[i].trim().startsWith("##")) {
                        lines[i] = prompt
                    } else {
                        // Insert the prompt
                        lines.add(i, prompt)
                        lines.add(i, "")
                    }
                    break
                }
                i++
            }
            
            // If not found, append at the end
            if (!found) {
                if (lines.isNotEmpty() && lines.last().trim().isNotEmpty()) {
                    lines.add("")
                }
                lines.add("## Shift+$shortcut: $title")
                lines.add(prompt)
            }
            
            return configManager.writeZestConfigFile(
                "custom_prompts.md",
                lines.joinToString("\n")
            )
        } catch (e: Exception) {
            logger.error("Failed to save custom prompt", e)
            return false
        }
    }
    
    private fun getDefaultCustomPromptsContent(): String {
        return """
# Custom Prompts for Zest

Define your own prompts for quick access using Shift+1 through Shift+9.

## Shift+1: Add Comments
Add detailed comments explaining the logic

## Shift+2: Optimize Performance  
Optimize this code for better performance

## Shift+3: Add Error Handling
Add comprehensive error handling
        """.trimIndent()
    }

    /**
     * Force update the custom prompts file with the new defaults (removes old prompts)
     */
    fun forceUpdateToNewDefaults(): Boolean {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            configManager.ensureZestFolderExists()
            
            // Write the new default content, overwriting any existing content
            return configManager.writeZestConfigFile(
                "custom_prompts.md",
                getDefaultCustomPromptsContent()
            )
        } catch (e: Exception) {
            logger.error("Failed to force update custom prompts", e)
            return false
        }
    }
    
    companion object {
        fun getInstance(project: Project): ZestCustomPromptsLoader {
            return ZestCustomPromptsLoader(project)
        }
    }
}