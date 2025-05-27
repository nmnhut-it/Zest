package com.zps.zest.inlinechat

import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange

/**
 * Simple test code vision provider that always shows a button
 */
class TestCodeVisionProvider : CodeVisionProvider<Unit>, DumbAware {
    override val id: String = "Zest.Test.CodeVision"
    override val name: String = "Zest Test Code Vision"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    
    init {
        System.out.println("=== TestCodeVisionProvider created ===")
    }
    
    override fun precomputeOnUiThread(editor: Editor) {
        System.out.println("=== TestCodeVisionProvider.precomputeOnUiThread called ===")
        // No UI precomputation needed
    }
    
    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        System.out.println("=== TestCodeVisionProvider.computeCodeVision called ===")
        
        // Always show a test button on the first line
        val document = editor.document
        if (document.lineCount == 0) {
            return CodeVisionState.READY_EMPTY
        }
        
        val entry = TextCodeVisionEntry(
            "Test Code Vision Button (Working!)",
            id,
            AllIcons.General.InspectionsOK
        )
        
        val textRange = TextRange(0, document.getLineEndOffset(0))
        
        System.out.println("TestCodeVisionProvider: Creating entry at range $textRange")
        
        return CodeVisionState.Ready(listOf(textRange to entry))
    }
    
    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        Messages.showInfoMessage(
            editor.project,
            "Code Vision is working! The button was clicked.",
            "Code Vision Test"
        )
    }
}