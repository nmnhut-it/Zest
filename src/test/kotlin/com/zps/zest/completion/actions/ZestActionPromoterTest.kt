package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

class ZestActionPromoterTest : LightPlatformCodeInsightFixture4TestCase() {

    @Test
    fun `test action promotion by priority`() {
        val promoter = ZestActionPromoter()
        val context = mockk<DataContext>(relaxed = true)
        
        // Create test actions with different priorities
        val lowPriorityAction = TestAction(1)
        val highPriorityAction = TestAction(10)
        val mediumPriorityAction = TestAction(5)
        val noPriorityAction = TestActionNoPriority()
        val zestAction = TestZestAction(15)
        
        val actions = listOf(
            lowPriorityAction,
            highPriorityAction,
            mediumPriorityAction,
            noPriorityAction,
            zestAction
        )
        
        val promoted = promoter.promote(actions, context)
        
        // Should be sorted by priority descending
        assertEquals(zestAction, promoted[0]) // priority 15
        assertEquals(highPriorityAction, promoted[1]) // priority 10
        assertEquals(mediumPriorityAction, promoted[2]) // priority 5
        assertEquals(lowPriorityAction, promoted[3]) // priority 1
        assertEquals(noPriorityAction, promoted[4]) // priority 0 (default)
    }

    @Test
    fun `test zest completion actions get priority`() {
        val promoter = ZestActionPromoter()
        val context = mockk<DataContext>(relaxed = true)
        
        val zestTrigger = ZestTrigger()
        val zestAccept = ZestAccept()
        val zestDismiss = ZestDismiss()
        val regularAction = TestActionNoPriority()
        
        val actions = listOf(regularAction, zestDismiss, zestAccept, zestTrigger)
        val promoted = promoter.promote(actions, context)
        
        // Zest actions should come first, in order of their priority
        assertEquals(zestTrigger, promoted[0]) // priority 15
        assertEquals(zestAccept, promoted[1]) // priority 12
        assertEquals(zestDismiss, promoted[2]) // priority -1
        assertEquals(regularAction, promoted[3]) // priority 0 (default)
    }

    @Test
    fun `test empty action list`() {
        val promoter = ZestActionPromoter()
        val context = mockk<DataContext>(relaxed = true)
        
        val promoted = promoter.promote(emptyList(), context)
        assertEquals(0, promoted.size)
    }

    @Test
    fun `test single action`() {
        val promoter = ZestActionPromoter()
        val context = mockk<DataContext>(relaxed = true)
        
        val action = TestAction(5)
        val promoted = promoter.promote(listOf(action), context)
        
        assertEquals(1, promoted.size)
        assertEquals(action, promoted[0])
    }

    // Test helper classes
    
    private class TestAction(override val priority: Int) : AnAction(), HasPriority {
        override fun actionPerformed(e: AnActionEvent) {}
    }
    
    private class TestActionNoPriority : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {}
    }
    
    private class TestZestAction(override val priority: Int) : ZestInlineCompletionAction(
        object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: com.intellij.openapi.editor.Editor, caret: com.intellij.openapi.editor.Caret?, service: com.zps.zest.completion.ZestInlineCompletionService) {}
        }
    )
}
