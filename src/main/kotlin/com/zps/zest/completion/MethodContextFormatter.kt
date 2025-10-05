package com.zps.zest.completion

/**
 * Formats method context into markdown for ChatUIService.
 * Simple utility to create structured, readable messages about methods to be rewritten.
 */
object MethodContextFormatter {

    fun format(context: MethodContext, instruction: String): String {
        return buildString {
            appendLine("## Method Rewrite Request")
            appendLine()

            // File and location
            appendLine("**File:** `${context.fileName}`")

            // Class information
            if (context.containingClass != null) {
                appendLine("**Class:** `${context.containingClass}`")
            }

            // Method signature
            appendLine("**Method:** `${context.methodSignature ?: context.methodName}`")
            appendLine()

            // Current implementation
            appendLine("### Current Implementation")
            appendLine("```${getLanguageForHighlight(context.language)}")
            appendLine(context.methodContent)
            appendLine("```")
            appendLine()

            // User's task
            appendLine("### Task")
            appendLine(instruction)
            appendLine()

            // Additional context if available
            if (shouldIncludeAdditionalContext(context)) {
                appendLine("### Additional Context")

                if (context.surroundingMethods.isNotEmpty()) {
                    val methodCount = context.surroundingMethods.size
                    appendLine("- Containing class has $methodCount other method${if (methodCount == 1) "" else "s"}")
                }

                if (context.relatedClasses.isNotEmpty()) {
                    appendLine("- Found ${context.relatedClasses.size} related class${if (context.relatedClasses.size == 1) "" else "es"}")
                }

                if (context.isCocos2dx) {
                    appendLine("- Framework: Cocos2d-x detected")
                }

                appendLine()
            }

            // Instructions for AI
            appendLine("---")
            appendLine("*The method content is shown above. For full file context, use `readFile()` first. Then make focused single edits with `replaceCodeInFile()`.*")
        }
    }

    private fun getLanguageForHighlight(language: String): String {
        return when (language.lowercase()) {
            "java" -> "java"
            "kotlin", "kt" -> "kotlin"
            "javascript", "js" -> "javascript"
            "typescript", "ts" -> "typescript"
            else -> ""
        }
    }

    private fun shouldIncludeAdditionalContext(context: MethodContext): Boolean {
        return context.surroundingMethods.isNotEmpty() ||
               context.relatedClasses.isNotEmpty() ||
               context.isCocos2dx
    }
}
