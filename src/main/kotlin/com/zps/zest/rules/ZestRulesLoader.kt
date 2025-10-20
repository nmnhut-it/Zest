package com.zps.zest.rules

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.zps.zest.ConfigurationManager
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
        const val RULES_FILE_NAME = "zest_rules.md" // Legacy name
        const val NEW_RULES_FILE_NAME = "rules.md"
        const val DEFAULT_RULES_HEADER = """
# Zest Custom Rules

## High-Impact Rules:

### Code Clarity:
- Use descriptive, meaningful names for variables, functions, and classes
- Follow single responsibility principle - each unit should do one thing well
- Keep functions small and focused (under 30 lines when possible)
- Prefer self-documenting code over comments

### Error Handling:
- Never swallow exceptions silently - always log or re-throw with context
- Use specific exception types instead of generic Exception
- Provide meaningful error messages that help debugging
- Validate inputs early and fail fast

### Null Safety:
- Avoid null references - use Optional, nullable types, or default values
- Always check for null before dereferencing
- Document when null is a valid return value
- Use language-specific null-safety features

### Maintainability:
- Follow DRY (Don't Repeat Yourself) - extract common logic
- Avoid magic numbers and strings - use named constants or enums
- Write modular code with clear interfaces
- Keep dependencies explicit and minimal

### Thread Safety:
- Document thread-safety assumptions
- Use immutable objects when possible
- Properly synchronize shared mutable state
- Avoid blocking operations in critical paths

### Testing:
- Write unit tests for business logic and edge cases
- Test both success and failure scenarios
- Use descriptive test names that explain what's being tested
- Mock external dependencies in tests

### Documentation:
- Add brief inline comments for complex logic
- Document public APIs with input/output examples
- Keep documentation up-to-date with code changes
- Explain the "why" not the "what"

## Your Rules:

<!-- Add your project-specific rules below this line -->

"""
    }
    
    /**
     * Load custom rules from the project's zest_rules.md file
     * @return The custom rules content or null if no rules file exists
     */
    fun loadCustomRules(): String? {
        try {
            // Ensure .zest folder exists
            val configManager = ConfigurationManager.getInstance(project)
            configManager.ensureZestFolderExists()
            
            // Try to load from new location first
            val newContent = configManager.readZestConfigFile(NEW_RULES_FILE_NAME)
            if (newContent != null) {
                // Check if this is a dummy/minimal rules file and upgrade if needed
                if (isDummyRulesFile(newContent)) {
                    logger.info("Detected bloated/minimal rules file, upgrading to simplified high-impact rules")
                    upgradeToSimplifiedRules()
                    // Re-read the upgraded content
                    val upgradedContent = configManager.readZestConfigFile(NEW_RULES_FILE_NAME)
                    if (upgradedContent != null) {
                        val rulesContent = extractRulesContent(upgradedContent)
                        if (rulesContent.isNotBlank()) {
                            logger.info("Successfully loaded upgraded rules from .zest/$NEW_RULES_FILE_NAME")
                            return rulesContent.trim()
                        }
                    }
                } else {
                    val rulesContent = extractRulesContent(newContent)
                    if (rulesContent.isNotBlank()) {
                        logger.info("Successfully loaded custom rules from .zest/$NEW_RULES_FILE_NAME")
                        return rulesContent.trim()
                    }
                }
            }
            
            // Fall back to legacy location
            val rulesFile = findRulesFile()
            
            if (rulesFile == null) {
                logger.info("No rules file found in project")
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
            // Use configuration manager to create in .zest folder
            val configManager = ConfigurationManager.getInstance(project)
            configManager.ensureZestFolderExists()
            
            // Check if already exists in new location
            val existingContent = configManager.readZestConfigFile(NEW_RULES_FILE_NAME)
            if (existingContent != null) {
                logger.info("Rules file already exists in .zest folder")
                return false
            }
            
            // Create with default content
            return configManager.writeZestConfigFile(NEW_RULES_FILE_NAME, getDefaultRulesContent())
            
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
                // Check for rules sections
                line.trim().startsWith("## Default Coding Standards:") ||
                line.trim().startsWith("## High-Impact Rules:") ||
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
        // Check new location first
        val configManager = ConfigurationManager.getInstance(project)
        val newLocationExists = configManager.readZestConfigFile(NEW_RULES_FILE_NAME) != null
        
        // Also check legacy location
        val legacyExists = findRulesFile() != null
        
        return newLocationExists || legacyExists
    }
    
    private fun getDefaultRulesContent(): String {
        return DEFAULT_RULES_HEADER
    }
    
    /**
     * Check if the existing rules file contains only minimal/placeholder content
     * Only returns true if the file is truly a placeholder with no user content
     */
    private fun isDummyRulesFile(content: String): Boolean {
        // Extract only the actual rules content (skip headers and examples)
        val rulesContent = extractRulesContent(content)

        // Check if content has the placeholder comment but no actual rules below it
        val hasPlaceholderOnly = content.contains("<!-- Add your", ignoreCase = true) &&
                                 rulesContent.trim().isEmpty()

        // Check if it's the old bloated default rules (has many sections we want to simplify)
        val hasBloatedDefaults = content.contains("## Default Coding Standards:", ignoreCase = true) &&
                                 content.contains("### Code Quality & Style:", ignoreCase = true) &&
                                 content.contains("### Java/Kotlin Best Practices:", ignoreCase = true) &&
                                 content.contains("### Testing & Documentation:", ignoreCase = true) &&
                                 content.contains("### Performance & Security:", ignoreCase = true)

        // User has actual content if:
        // - Rules section has substantial content (>10 lines of actual rules)
        // - Rules contain specific project details (not just generic advice)
        val hasUserContent = rulesContent.lines().filter { it.isNotBlank() }.size > 10 &&
                            !hasBloatedDefaults

        // Only migrate if:
        // 1. File is truly placeholder-only (no rules), OR
        // 2. File has the old bloated defaults AND no significant user additions beyond that
        return hasPlaceholderOnly ||
               (hasBloatedDefaults && !hasUserContent)
    }
    
    /**
     * Upgrade existing bloated/minimal rules to simplified high-impact rules while preserving user content
     */
    private fun upgradeToSimplifiedRules(): Boolean {
        try {
            val configManager = ConfigurationManager.getInstance(project)

            // Read existing content
            val existingContent = configManager.readZestConfigFile(NEW_RULES_FILE_NAME) ?: ""

            // Extract any existing user rules
            val existingUserRules = extractUserRulesOnly(existingContent)

            // Create new content with simplified defaults + preserved user rules
            val upgradedContent = if (existingUserRules.isNotBlank()) {
                DEFAULT_RULES_HEADER + "\n" + existingUserRules
            } else {
                DEFAULT_RULES_HEADER
            }

            // Write the upgraded content
            val success = configManager.writeZestConfigFile(NEW_RULES_FILE_NAME, upgradedContent)

            if (success) {
                logger.info("Successfully upgraded rules file with simplified high-impact defaults")
            }

            return success

        } catch (e: Exception) {
            logger.error("Failed to upgrade rules file", e)
            return false
        }
    }
    
    /**
     * Extract only user-added content from existing rules (preserve custom rules)
     */
    private fun extractUserRulesOnly(content: String): String {
        val lines = content.lines()
        val userRules = mutableListOf<String>()
        var inUserSection = false
        var foundActualContent = false
        
        for (line in lines) {
            when {
                // Start collecting after "## Your Rules:" or similar
                line.trim().startsWith("## Your Rules:") || 
                line.trim().startsWith("## Custom Rules:") ||
                line.trim().startsWith("## Project Rules:") -> {
                    inUserSection = true
                    continue
                }
                
                // Skip comment placeholders
                line.trim().startsWith("<!-- Add your") || 
                line.trim().startsWith("<!-- Add your project-specific") -> {
                    continue
                }
                
                // Collect actual user content
                inUserSection -> {
                    // Skip empty lines at the beginning
                    if (line.isNotBlank()) {
                        foundActualContent = true
                    }
                    if (foundActualContent) {
                        userRules.add(line)
                    }
                }
            }
        }
        
        return if (foundActualContent) {
            userRules.joinToString("\n").trim()
        } else {
            ""
        }
    }
    
    /**
     * Force upgrade rules file to simplified defaults (for testing/maintenance)
     */
    fun forceUpgradeToSimplifiedRules(): Boolean {
        return upgradeToSimplifiedRules()
    }
    
    /**
     * Check if current rules file is minimal (for testing/diagnostics)
     */
    fun isCurrentRulesFileMinimal(): Boolean {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            val content = configManager.readZestConfigFile(NEW_RULES_FILE_NAME)
            return content != null && isDummyRulesFile(content)
        } catch (e: Exception) {
            logger.error("Failed to check if rules file is minimal", e)
            return false
        }
    }
    
    /**
     * Get the path to the rules file
     */
    fun getRulesFilePath(): String {
        // Return new location path
        val configManager = ConfigurationManager.getInstance(project)
        return configManager.getZestConfigFilePath(NEW_RULES_FILE_NAME)?.toString()
            ?: Paths.get(project.basePath ?: "", RULES_FILE_NAME).toString()
    }
}
