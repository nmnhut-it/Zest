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

Define your custom LLM rules below. These rules will be included at the top of all prompts sent to the LLM.
You can use this to:
- Define coding standards specific to your project
- Add domain-specific knowledge
- Set preferred coding patterns
- Include project-specific requirements

## Default Coding Standards:

### Code Quality & Style:
- Use descriptive, meaningful names for variables, methods, and classes (avoid abbreviations)
- Follow single responsibility principle - each method/class should have one clear purpose
- Keep methods under 20 lines when possible - extract complex logic into helper methods
- Prefer early returns to reduce nesting and improve readability
- Write self-documenting code - prefer clear logic over comments when possible
- Use consistent indentation and formatting

### Java/Kotlin Best Practices:
- Use Optional<T> instead of returning null in Java methods
- Prefer immutable objects and final variables when possible
- Use Kotlin data classes for value objects and DTOs
- Always implement equals() and hashCode() together in Java
- Use @NotNull/@Nullable annotations for better null safety
- Prefer dependency injection through constructor parameters
- Use sealed classes/enums instead of magic strings or numbers

### IntelliJ Plugin Development:
- Always run UI operations on EDT using ApplicationManager.getApplication().invokeLater()
- Run heavy computations in background threads with proper progress indicators
- Dispose resources properly by implementing Disposable interface
- Use project services instead of static instances for better testability
- Cache expensive operations using CachedValuesManager or similar mechanisms
- Use ReadAction/WriteAction for PSI operations
- Validate project state before accessing project-dependent resources

### Error Handling & Logging:
- Never swallow exceptions silently - always log or re-throw appropriately
- Use specific exception types instead of generic Exception
- Log with appropriate levels (DEBUG, INFO, WARN, ERROR) and meaningful context
- Validate method inputs early with clear error messages
- Use guard clauses to fail fast on invalid conditions
- Include stack traces in error logs for debugging

### Testing & Documentation:
- Write unit tests for all business logic and edge cases
- Use descriptive test method names that explain what is being tested
- Add KDoc/JavaDoc for public APIs, especially complex methods
- Include code examples in documentation for complex APIs
- Mock external dependencies in unit tests
- Test both success and failure scenarios

### Performance & Security:
- Avoid blocking operations on EDT that could freeze the UI
- Use lazy initialization for expensive resources that may not be needed
- Don't log sensitive information like passwords or API keys
- Validate and sanitize all external inputs
- Use StringBuilder for string concatenation in loops
- Cache results of expensive computations when appropriate
- Prefer immutable collections for thread safety

### Project-Specific Patterns:
- Use Result<T> or similar wrapper types for operations that can fail
- Follow repository pattern for data access layers
- Use builder pattern for complex object creation
- Implement proper cleanup in dispose() methods
- Use service locators sparingly - prefer explicit dependency injection

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
                    logger.info("Detected minimal rules file, upgrading to comprehensive default rules")
                    upgradeToComprehensiveRules()
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
     * Check if the existing rules file contains only minimal/dummy content
     */
    private fun isDummyRulesFile(content: String): Boolean {
        // Extract only the actual rules content (skip headers and examples)
        val rulesContent = extractRulesContent(content)
        
        // Check for indicators of minimal/dummy rules
        val minimalIndicators = listOf(
            "<!-- Add your custom rules below this line -->",
            "<!-- Add your project-specific rules below this line -->",
            "## Example Rules:",
            "Define your custom LLM rules below"
        )
        
        // If the content contains default boilerplate text, consider upgrading
        val hasBoilerplate = minimalIndicators.any { indicator -> 
            content.contains(indicator, ignoreCase = true) 
        }
        
        // Check if it lacks comprehensive sections like "Default Coding Standards"
        val lacksComprehensiveRules = !content.contains("## Default Coding Standards:", ignoreCase = true)
        
        // Check if it lacks key comprehensive categories that indicate old/minimal rules
        val comprehensiveCategories = listOf(
            "IntelliJ Plugin Development:",
            "Performance & Security:",
            "Testing & Documentation:",
            "Error Handling & Logging:"
        )
        
        val lacksComprehensiveCategories = comprehensiveCategories.none { category ->
            content.contains(category, ignoreCase = true)
        }
        
        // Check for very basic/generic rules that suggest it's just placeholder content
        val hasOnlyBasicRules = rulesContent.lines().size < 20 && (
            rulesContent.contains("Use descriptive variable names", ignoreCase = true) ||
            rulesContent.contains("Add null checks", ignoreCase = true) ||
            rulesContent.contains("Follow the repository pattern", ignoreCase = true)
        ) && !rulesContent.contains("ApplicationManager.getApplication().invokeLater", ignoreCase = true)
        
        // Consider upgrading if:
        // 1. Has boilerplate placeholders, OR
        // 2. Lacks comprehensive structure (both comprehensive rules and categories), OR  
        // 3. Has only basic rules without plugin-specific guidance
        return hasBoilerplate || 
               (lacksComprehensiveRules && lacksComprehensiveCategories) ||
               hasOnlyBasicRules
    }
    
    /**
     * Upgrade existing minimal rules to comprehensive default rules while preserving user content
     */
    private fun upgradeToComprehensiveRules(): Boolean {
        try {
            val configManager = ConfigurationManager.getInstance(project)
            
            // Read existing content
            val existingContent = configManager.readZestConfigFile(NEW_RULES_FILE_NAME) ?: ""
            
            // Extract any existing user rules
            val existingUserRules = extractUserRulesOnly(existingContent)
            
            // Create new content with comprehensive defaults + preserved user rules
            val upgradedContent = if (existingUserRules.isNotBlank()) {
                DEFAULT_RULES_HEADER + "\n" + existingUserRules
            } else {
                DEFAULT_RULES_HEADER
            }
            
            // Write the upgraded content
            val success = configManager.writeZestConfigFile(NEW_RULES_FILE_NAME, upgradedContent)
            
            if (success) {
                logger.info("Successfully upgraded rules file with comprehensive defaults")
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
     * Force upgrade rules file to comprehensive defaults (for testing/maintenance)
     */
    fun forceUpgradeToComprehensiveRules(): Boolean {
        return upgradeToComprehensiveRules()
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
