package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * A test action that creates a real diff of the selected code
 * and applies appropriate highlighting to show changes.
 */
class TestCommentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        
        // Check if there's a selection
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Please select some text to test the diff highlighting.", "No Selection")
            return
        }
        
        // Get the selection start and end offsets
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        
        // Get the corresponding line numbers
        val startLine = document.getLineNumber(startOffset)
        val endLine = document.getLineNumber(endOffset)
        
        // Get the original text
        val originalText = document.getText(TextRange(startOffset, endOffset))
        
        // Create a modified version of the text
        val modifiedText = createModifiedText(originalText)
        
        // Get the InlineChatService to update diff segments
        val service = project.getService(InlineChatService::class.java)
        
        // Store the original text for reference
        service.originalCode = originalText
        
        // Clear any existing highlights first
        service.diffSegments = ArrayList()
        
        // Apply highlighting to the selected lines
        WriteCommandAction.runWriteCommandAction(project) {
            createDiffHighlighting(document, project, service, startLine, endLine, originalText, modifiedText)
        }
        
        // Force editor highlighting refresh
        ApplicationManager.getApplication().invokeLater {
            editor.contentComponent.repaint()
            
            // Scroll to make sure the highlighted section is visible
            editor.scrollingModel.scrollTo(
                editor.offsetToLogicalPosition(startOffset),
                ScrollType.MAKE_VISIBLE
            )
            
            // Show a notification about what was done
            Messages.showInfoMessage(
                project,
                "Diff highlighting applied to selection. Green = added, Red = removed, Gray = unchanged.",
                "Diff Test Applied"
            )
        }
    }
    
    /**
     * Creates a modified version of the text with some realistic changes
     */
    private fun createModifiedText(originalText: String): String {
        val lines = originalText.split("\n").toMutableList()
        
        // Only make changes if we have enough lines
        if (lines.size < 2) return originalText
        
        // Create different types of changes
        val modifiedLines = lines.toMutableList()
        
        // 1. Make changes to about 30% of the lines
        val linesToModify = (lines.size * 0.3).toInt().coerceAtLeast(1)
        val indices = (0 until lines.size).toMutableList().shuffled().take(linesToModify)
        
        for (index in indices.sorted()) {
            val line = lines[index]
            
            // Decide what type of change to make
            val random = (0..2).random()
            when (random) {
                0 -> {
                    // Modify the line (add a comment)
                    if (line.trim().isNotEmpty()) {
                        modifiedLines[index] = "$line // Modified by Zest test"
                    }
                }
                1 -> {
                    // Insert a new line after this one
                    val indentation = line.takeWhile { it == ' ' || it == '\t' }
                    modifiedLines.add(index + 1, "$indentation// New line inserted by Zest test")
                }
                2 -> {
                    // Delete the line
                    if (modifiedLines.size > 1) { // Ensure we don't delete all lines
                        modifiedLines.removeAt(index)
                    }
                }
            }
        }
        
        return modifiedLines.joinToString("\n")
    }
    
    /**
     * Creates diff highlighting based on comparing original and modified text
     */
    private fun createDiffHighlighting(
        document: Document, 
        project: Project, 
        service: InlineChatService,
        startLine: Int, 
        endLine: Int, 
        originalText: String, 
        modifiedText: String
    ) {
        // Split both texts into lines
        val originalLines = originalText.split("\n")
        val modifiedLines = modifiedText.split("\n")
        
        // Create diff segments
        val diffSegments = mutableListOf<DiffSegment>()
        
        // Add a header line
        if (startLine > 0) {
            diffSegments.add(DiffSegment(
                startLine - 1,
                startLine - 1,
                DiffSegmentType.HEADER,
                "// === ZEST DIFF TEST: Original vs Modified Text ==="
            ))
        }
        
        // Simplistic diff algorithm (in real code, you'd use a proper diff algorithm)
        // This is just for testing
        var originalIndex = 0
        var modifiedIndex = 0
        var currentLine = startLine
        
        while (originalIndex < originalLines.size || modifiedIndex < modifiedLines.size) {
            if (originalIndex >= originalLines.size) {
                // All remaining modified lines are insertions
                while (modifiedIndex < modifiedLines.size && currentLine <= document.lineCount - 1) {
                    diffSegments.add(DiffSegment(
                        currentLine,
                        currentLine,
                        DiffSegmentType.INSERTED,
                        modifiedLines[modifiedIndex]
                    ))
                    modifiedIndex++
                    currentLine++
                }
                break
            }
            
            if (modifiedIndex >= modifiedLines.size) {
                // All remaining original lines are deletions
                while (originalIndex < originalLines.size && currentLine <= document.lineCount - 1) {
                    diffSegments.add(DiffSegment(
                        currentLine,
                        currentLine,
                        DiffSegmentType.DELETED,
                        originalLines[originalIndex]
                    ))
                    originalIndex++
                    currentLine++
                }
                break
            }
            
            // Compare current lines
            if (originalLines[originalIndex] == modifiedLines[modifiedIndex]) {
                // Lines are the same
                diffSegments.add(DiffSegment(
                    currentLine,
                    currentLine,
                    DiffSegmentType.UNCHANGED,
                    originalLines[originalIndex]
                ))
                originalIndex++
                modifiedIndex++
                currentLine++
            } else {
                // Lines are different
                // Look ahead to see if we can find a match
                var foundMatch = false
                
                // Look for the current original line in the upcoming modified lines
                for (lookAhead in 1..3) {
                    if (modifiedIndex + lookAhead < modifiedLines.size && 
                        originalLines[originalIndex] == modifiedLines[modifiedIndex + lookAhead]) {
                        // Found a match - lines were inserted
                        for (i in 0 until lookAhead) {
                            if (currentLine <= document.lineCount - 1) {
                                diffSegments.add(DiffSegment(
                                    currentLine,
                                    currentLine,
                                    DiffSegmentType.INSERTED,
                                    modifiedLines[modifiedIndex + i]
                                ))
                                currentLine++
                            }
                        }
                        modifiedIndex += lookAhead
                        foundMatch = true
                        break
                    }
                }
                
                if (!foundMatch) {
                    // Look for the current modified line in the upcoming original lines
                    for (lookAhead in 1..3) {
                        if (originalIndex + lookAhead < originalLines.size && 
                            modifiedLines[modifiedIndex] == originalLines[originalIndex + lookAhead]) {
                            // Found a match - lines were deleted
                            for (i in 0 until lookAhead) {
                                if (currentLine <= document.lineCount - 1) {
                                    diffSegments.add(DiffSegment(
                                        currentLine,
                                        currentLine,
                                        DiffSegmentType.DELETED,
                                        originalLines[originalIndex + i]
                                    ))
                                    currentLine++
                                }
                            }
                            originalIndex += lookAhead
                            foundMatch = true
                            break
                        }
                    }
                }
                
                if (!foundMatch) {
                    // No match found, treat as modification (delete + insert)
                    if (currentLine <= document.lineCount - 1) {
                        diffSegments.add(DiffSegment(
                            currentLine,
                            currentLine,
                            DiffSegmentType.DELETED,
                            originalLines[originalIndex]
                        ))
                        diffSegments.add(DiffSegment(
                            currentLine,
                            currentLine,
                            DiffSegmentType.INSERTED,
                            modifiedLines[modifiedIndex]
                        ))
                        originalIndex++
                        modifiedIndex++
                        currentLine++
                    }
                }
            }
        }
        
        // Add a footer line
        if (currentLine < document.lineCount) {
            diffSegments.add(DiffSegment(
                currentLine,
                currentLine,
                DiffSegmentType.FOOTER,
                "// === END OF DIFF TEST ==="
            ))
        }
        
        // Add one comment line explaining the test
        if (diffSegments.size > 2) {
            val commentLineIndex = (diffSegments.size / 2).coerceIn(1, diffSegments.size - 2)
            val commentLine = diffSegments[commentLineIndex].startLine
            
            diffSegments.add(DiffSegment(
                commentLine,
                commentLine,
                DiffSegmentType.COMMENT,
                "/* This is a simulated diff for testing the highlighting system */"
            ))
        }
        
        // Update the service with the diff segments
        service.diffSegments = diffSegments
        
        // Force document update to refresh highlighting
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() ?: false
        
        e.presentation.isEnabled = project != null && editor != null && hasSelection
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}