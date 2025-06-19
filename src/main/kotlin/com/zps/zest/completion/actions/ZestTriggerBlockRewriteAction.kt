package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.zps.zest.completion.ZestMethodRewriteService
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget

/**
 * Action to trigger method rewrite in background with status bar progress
 * BREAKING CHANGE: Removed dialog, uses background processing with status bar updates
 */
class ZestTriggerBlockRewriteAction : AnAction("Trigger Block Rewrite"), HasPriority {
    private val logger = Logger.getInstance(ZestTriggerBlockRewriteAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val methodRewriteService = project.serviceOrNull<ZestMethodRewriteService>()

        if (methodRewriteService == null) {
            logger.warn("ZestMethodRewriteService not available")
            return
        }

        // Check if another rewrite is in progress
        if (methodRewriteService.isRewriteInProgress()) {
            Messages.showWarningDialog(
                project,
                "A method rewrite operation is already in progress. Please wait for it to complete or cancel it first.",
                "Method Rewrite In Progress"
            )
            return
        }

        val offset = editor.caretModel.primaryCaret.offset

        // Find method at cursor position
        val contextCollector = ZestMethodContextCollector(project)
        val methodContext = contextCollector.findMethodAtCursor(editor, offset)

        if (methodContext == null) {
            Messages.showWarningDialog(
                project,
                "Could not identify a method at the current cursor position. Place cursor inside a method to rewrite it.",
                "No Method Found"
            )
            return
        }

        // Get status bar widget for progress updates
        val statusBarWidget = getStatusBarWidget(project)

        // Generate smart instruction based on method context
        val smartInstruction = generateSmartInstruction(methodContext)

        logger.info("Starting background method rewrite for ${methodContext.methodName} with instruction: $smartInstruction")

        // Update status bar immediately
        statusBarWidget?.updateMethodRewriteState(
            ZestCompletionStatusBarWidget.MethodRewriteState.ANALYZING,
            "Analyzing method '${methodContext.methodName}'"
        )

        // Start rewrite in background with status bar updates
        methodRewriteService.rewriteCurrentMethodWithStatusCallback(
            editor = editor,
            methodContext = methodContext,
            customInstruction = smartInstruction,
            dialog = null,  // No dialog
            statusCallback = { status ->
                statusBarWidget?.updateMethodRewriteStatus(status)
            }
        )
    }

    /**
     * Get the status bar widget for progress updates
     */
    private fun getStatusBarWidget(project: com.intellij.openapi.project.Project): ZestCompletionStatusBarWidget? {
        return try {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.getWidget(ZestCompletionStatusBarWidget.WIDGET_ID) as? ZestCompletionStatusBarWidget
        } catch (e: Exception) {
            logger.warn("Could not get status bar widget", e)
            null
        }
    }

    /**
     * Generate smart instruction based on method context
     */
    private fun generateSmartInstruction(methodContext: ZestMethodContextCollector.MethodContext): String {
        val methodContent = methodContext.methodContent
        val methodName = methodContext.methodName

        return when {
            // Empty or minimal method body
            isEmptyOrMinimalMethod(methodContent) ->
                "Implement this $methodName method with proper functionality"

            // TODO/FIXME comments
            hasTodoComment(methodContent) -> {
                val todoText = extractTodoText(methodContent)
                if (todoText.isNotEmpty()) {
                    "Implement the TODO: $todoText"
                } else {
                    "Implement the TODO functionality"
                }
            }

            // Placeholder implementations
            hasPlaceholderCode(methodContent) ->
                "Replace placeholder implementation with proper logic"

            // Complex method that needs refactoring
            isComplexMethod(methodContent) ->
                "Refactor this method for better readability and maintainability"

            // Missing error handling
            lacksErrorHandling(methodContent) ->
                "Add proper error handling and input validation"

            // Performance issues
            hasPerformanceIssues(methodContent) ->
                "Optimize this method for better performance"

            // Method name suggests specific functionality
            methodName.contains("parse", ignoreCase = true) ->
                "Improve parsing logic with better error handling and validation"

            methodName.contains("validate", ignoreCase = true) ->
                "Enhance validation logic with comprehensive checks"

            methodName.contains("process", ignoreCase = true) ->
                "Improve processing logic with better error handling"

            methodName.contains("init", ignoreCase = true) ->
                "Enhance initialization with proper setup and validation"

            // Default improvements
            else -> "Improve code quality, readability, and add proper error handling"
        }
    }

    // Helper methods for method analysis
    private fun isEmptyOrMinimalMethod(content: String): Boolean {
        val body = content.substringAfter("{").substringBeforeLast("}")
        val cleanBody = body.trim()
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        return cleanBody.isEmpty() || cleanBody == "return null;" || cleanBody == "return;" || cleanBody.length < 10
    }

    private fun hasTodoComment(content: String): Boolean {
        return content.contains(Regex("//\\s*(TODO|FIXME|XXX|HACK)", RegexOption.IGNORE_CASE))
    }

    private fun extractTodoText(content: String): String {
        val todoRegex = Regex("//\\s*(TODO|FIXME|XXX|HACK)\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)
        return todoRegex.find(content)?.groupValues?.get(2)?.trim() ?: ""
    }

    private fun hasPlaceholderCode(content: String): Boolean {
        return content.contains("throw new UnsupportedOperationException") ||
                content.contains("throw UnsupportedOperationException") ||
                content.contains("NotImplementedError") ||
                content.contains("TODO()") ||
                content.contains("return null") ||
                content.contains("return 0") ||
                content.contains("return false") ||
                content.contains("return \"\"") ||
                content.contains("return []") ||
                content.contains("return {}")
    }

    private fun isComplexMethod(content: String): Boolean {
        val lines = content.lines().size
        val cyclomaticComplexity = content.count { it == '(' } +
                content.split(Regex("\\b(if|for|while|switch|case|catch)\\b")).size - 1
        return lines > 30 || cyclomaticComplexity > 10
    }

    private fun lacksErrorHandling(content: String): Boolean {
        return !content.contains("try") &&
                !content.contains("catch") &&
                !content.contains("throw") &&
                !content.contains("if") && // Basic validation
                content.contains("(") // Has parameters that should be validated
    }

    private fun hasPerformanceIssues(content: String): Boolean {
        return content.contains(Regex("for\\s*\\(.*\\)\\s*\\{[^}]*for\\s*\\(")) || // Nested loops
                content.contains("++") && content.contains("new ") || // Object creation in loops
                content.split("new ").size > 5 // Multiple object creations
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val methodRewriteService = project?.serviceOrNull<ZestMethodRewriteService>()

        val isAvailable = project != null && editor != null && methodRewriteService != null && !editor.isDisposed

        e.presentation.isEnabledAndVisible = isAvailable

        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.text = "Block Rewrite (In Progress...)"
            e.presentation.description = "A block rewrite is currently in progress"
            e.presentation.isEnabled = false
        } else {
            e.presentation.text = "Trigger Block Rewrite"
            e.presentation.description = "Rewrite the current block with AI improvements (background process)"
            e.presentation.isEnabled = isAvailable
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override val priority: Int = 17
}