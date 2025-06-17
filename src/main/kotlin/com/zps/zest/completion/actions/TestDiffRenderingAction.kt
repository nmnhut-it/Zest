package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.zps.zest.completion.ZestInlineMethodDiffRenderer
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.gdiff.GDiff

/**
 * Test action to verify diff rendering fixes
 */
class TestDiffRenderingAction : AnAction("Test Diff Rendering") {
    private val logger = Logger.getInstance(TestDiffRenderingAction::class.java)
    private val diffRenderer = ZestInlineMethodDiffRenderer()
    
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        
        ApplicationManager.getApplication().invokeLater {
            // Get cursor position
            val offset = editor.caretModel.offset
            val document = editor.document
            
            // Calculate a reasonable method range around cursor
            val currentLine = document.getLineNumber(offset)
            val methodStartLine = maxOf(0, currentLine - 10)
            val methodEndLine = minOf(document.lineCount - 1, currentLine + 10)
            val methodStartOffset = document.getLineStartOffset(methodStartLine)
            val methodEndOffset = document.getLineEndOffset(methodEndLine)
            
            // Create test method context with the actual code around cursor
            val methodContent = document.getText(com.intellij.openapi.util.TextRange(methodStartOffset, methodEndOffset))
            
            val methodContext = ZestMethodContextCollector.MethodContext(
                fileName = "TestFile.java",
                language = "java",
                methodName = "testMethod",
                methodContent = methodContent,
                methodStartOffset = methodStartOffset,
                methodEndOffset = methodEndOffset,
                methodSignature = "private String testMethod(Long userId)",
                containingClass = "TestClass",
                surroundingMethods = emptyList(),
                classContext = "",
                fullFileContent = document.text,
                cursorOffset = offset
            )
            
            // Create a modified version that changes a line but keeps the last line
            val lines = methodContent.lines()
            val modifiedLines = lines.mapIndexed { index, line ->
                when {
                    index == 1 && line.contains("if") -> {
                        // Modify condition line
                        line.replace("null", "null || userId < 0")
                    }
                    index == 2 && line.contains("throw") -> {
                        // Modify exception message
                        line.replace("cannot be null", "cannot be null or negative")
                    }
                    else -> line // Keep other lines unchanged, including the last one
                }
            }
            val rewrittenMethod = modifiedLines.joinToString("\n")
            
            logger.info("Original method (${lines.size} lines):")
            lines.forEachIndexed { idx, line -> logger.info("  [$idx] $line") }
            
            logger.info("Modified method (${modifiedLines.size} lines):")
            modifiedLines.forEachIndexed { idx, line -> logger.info("  [$idx] $line") }
            
            // Create test diff result (this will be replaced by actual diff calculation)
            val diffResult = GDiff.DiffResult(
                changes = listOf(
                    GDiff.DiffChange(
                        type = GDiff.ChangeType.CHANGE,
                        sourceLineNumber = 1,
                        targetLineNumber = 1,
                        sourceLines = listOf(lines.getOrNull(1) ?: ""),
                        targetLines = listOf(modifiedLines.getOrNull(1) ?: "")
                    ),
                    GDiff.DiffChange(
                        type = GDiff.ChangeType.CHANGE,
                        sourceLineNumber = 2,
                        targetLineNumber = 2,
                        sourceLines = listOf(lines.getOrNull(2) ?: ""),
                        targetLines = listOf(modifiedLines.getOrNull(2) ?: "")
                    )
                ),
                identical = false
            )
            
            // Show the diff
            diffRenderer.show(
                editor,
                methodContext,
                diffResult,
                rewrittenMethod,
                onAccept = {
                    logger.info("Test diff accepted")
                },
                onReject = {
                    logger.info("Test diff rejected")
                }
            )
            
            logger.info("Test diff rendering shown - check if all lines are visible, especially the last line")
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }
}
