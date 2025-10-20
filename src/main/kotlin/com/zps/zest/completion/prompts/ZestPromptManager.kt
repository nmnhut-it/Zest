package com.zps.zest.completion.prompts

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.completion.MethodContext

/**
 * Unified prompt manager for pre-defined and custom prompts.
 * Loads prompts from markdown files (resources and user config).
 */
class ZestPromptManager(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ZestPromptManager::class.java)
        private const val PROMPTS_RESOURCE_PATH = "/prompts/predefined"

        private val PROMPT_FILES = listOf(
            "fill-code.md",
            "open-git-ui.md",
            "write-test.md",
            "refactor-method.md",
            "add-logging.md",
            "add-error-handling.md",
            "complete-implementation.md",
            "complete-todo.md",
            "split-method.md",
            "optimize-performance.md",
            "add-debug-logging.md",
            "fix-issue.md"
        )
    }

    data class PromptAction(
        val id: String,
        val icon: String,
        val title: String,
        val instruction: String,
        val requiresMethod: Boolean = true,
        val category: String = "default",
        val disabledReason: String? = null
    ) {
        fun isDisabled(hasMethod: Boolean): Boolean = requiresMethod && !hasMethod
        fun getDisabledReason(hasMethod: Boolean): String? =
            if (isDisabled(hasMethod)) (disabledReason ?: "requires cursor in method") else null

        fun formatInstruction(methodContext: MethodContext?): String {
            var formatted = instruction
            methodContext?.let {
                formatted = formatted.replace("{methodName}", it.methodName)
                if (formatted.contains("{todoText}")) {
                    val todoText = extractTodoText(it.methodContent)
                    formatted = formatted.replace("{todoText}", todoText.ifEmpty { "functionality" })
                }
            }
            return formatted
        }

        private fun extractTodoText(content: String): String {
            val todoRegex = Regex("//\\s*(TODO|FIXME|XXX|HACK)\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)
            return todoRegex.find(content)?.groupValues?.get(2)?.trim() ?: ""
        }
    }

    private val customPromptsLoader = ZestCustomPromptsLoader.getInstance(project)
    private val predefinedPrompts = mutableMapOf<String, PromptAction>()

    init {
        loadPredefinedPrompts()
    }

    /**
     * Load all pre-defined prompts from resources
     */
    private fun loadPredefinedPrompts() {
        PROMPT_FILES.forEach { fileName ->
            try {
                val resourcePath = "$PROMPTS_RESOURCE_PATH/$fileName"
                val content = this::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()

                if (content != null) {
                    val action = parsePromptFile(fileName.removeSuffix(".md"), content)
                    if (action != null) {
                        predefinedPrompts[action.id] = action
                        LOG.info("Loaded prompt: ${action.id}")
                    }
                } else {
                    LOG.warn("Could not load resource: $resourcePath")
                }
            } catch (e: Exception) {
                LOG.error("Failed to load prompt file: $fileName", e)
            }
        }
    }

    /**
     * Parse markdown prompt file with frontmatter
     */
    private fun parsePromptFile(id: String, content: String): PromptAction? {
        try {
            val lines = content.lines()

            // Extract frontmatter (between --- markers)
            val frontmatterStart = lines.indexOfFirst { it.trim() == "---" }
            val frontmatterEnd = lines.drop(frontmatterStart + 1).indexOfFirst { it.trim() == "---" } + frontmatterStart + 1

            if (frontmatterStart == -1 || frontmatterEnd == -1) {
                LOG.warn("No frontmatter found in prompt: $id")
                return null
            }

            val frontmatter = lines.subList(frontmatterStart + 1, frontmatterEnd)
            val metadata = parseFrontmatter(frontmatter)

            return PromptAction(
                id = id,
                icon = metadata["icon"] ?: "📝",
                title = metadata["title"] ?: id.replace("-", " ").capitalize(),
                instruction = metadata["instruction"] ?: "",
                requiresMethod = metadata["requiresMethod"]?.toBoolean() ?: true,
                category = metadata["category"] ?: "default"
            )
        } catch (e: Exception) {
            LOG.error("Failed to parse prompt file: $id", e)
            return null
        }
    }

    /**
     * Parse YAML-style frontmatter
     */
    private fun parseFrontmatter(lines: List<String>): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        lines.forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                metadata[key] = value
            }
        }
        return metadata
    }

    /**
     * Get prompts for a specific context category
     */
    fun getPromptsForCategory(category: String): List<PromptAction> {
        return predefinedPrompts.values.filter { it.category == category }
    }

    /**
     * Get global prompts (not requiring method context)
     */
    fun getGlobalPrompts(): List<PromptAction> {
        return predefinedPrompts.values.filter { !it.requiresMethod }
    }

    /**
     * Get prompts for empty/minimal methods
     */
    fun getEmptyMethodPrompts(): List<PromptAction> {
        return getPromptsForCategory("empty-method") +
               listOf(
                   predefinedPrompts["add-logging"],
                   predefinedPrompts["add-error-handling"],
                   predefinedPrompts["write-test"]
               ).filterNotNull()
    }

    /**
     * Get prompts for methods with TODO comments
     */
    fun getTodoMethodPrompts(): List<PromptAction> {
        return getPromptsForCategory("todo-method") +
               listOf(
                   predefinedPrompts["add-logging"],
                   predefinedPrompts["add-error-handling"],
                   predefinedPrompts["write-test"]
               ).filterNotNull()
    }

    /**
     * Get prompts for complex methods
     */
    fun getComplexMethodPrompts(): List<PromptAction> {
        return getPromptsForCategory("complex-method") +
               listOf(predefinedPrompts["write-test"]).filterNotNull()
    }

    /**
     * Get default method prompts
     */
    fun getDefaultMethodPrompts(): List<PromptAction> {
        return getPromptsForCategory("default-method") +
               listOf(
                   predefinedPrompts["add-logging"],
                   predefinedPrompts["add-error-handling"],
                   predefinedPrompts["write-test"]
               ).filterNotNull()
    }

    /**
     * Get context-aware prompts based on method analysis
     */
    fun getContextAwarePrompts(methodContext: MethodContext?): List<PromptAction> {
        val globalPrompts = getGlobalPrompts()

        if (methodContext == null) {
            // Return global + disabled method prompts
            return globalPrompts + predefinedPrompts.values.filter { it.requiresMethod }
        }

        val methodContent = methodContext.methodContent

        val methodPrompts = when {
            isEmptyOrMinimalMethod(methodContent) -> getEmptyMethodPrompts()
            hasTodoComment(methodContent) -> getTodoMethodPrompts()
            isComplexMethod(methodContent) -> getComplexMethodPrompts()
            else -> getDefaultMethodPrompts()
        }

        return globalPrompts + methodPrompts
    }

    /**
     * Get custom prompts from user config
     */
    fun getCustomPrompts(): List<ZestCustomPromptsLoader.CustomPrompt> {
        return customPromptsLoader.loadCustomPrompts()
    }

    /**
     * Check if method is empty or minimal
     */
    private fun isEmptyOrMinimalMethod(content: String): Boolean {
        val body = content.substringAfter("{").substringBeforeLast("}")
        val cleanBody = body.trim()
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        return cleanBody.isEmpty() || cleanBody == "return null;" || cleanBody == "return;" || cleanBody.length < 10
    }

    /**
     * Check if method has TODO comment
     */
    private fun hasTodoComment(content: String): Boolean {
        return content.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE))
    }

    /**
     * Check if method is complex
     */
    private fun isComplexMethod(content: String): Boolean {
        val lines = content.lines().size
        val cyclomaticComplexity = content.count { it == '(' } +
                content.split(Regex("\\b(if|for|while|switch|case|catch)\\b")).size - 1
        return lines > 30 || cyclomaticComplexity > 10
    }
}
