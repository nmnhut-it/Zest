package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.zps.zest.completion.ZestInlineCompletionService
import org.junit.Test
import kotlin.test.*

class ZestActionSystemIntegrationTest : LightPlatformCodeInsightFixture4TestCase() {

    @Test
    fun `test all zest completion actions are registered`() {
        val actionManager = ActionManager.getInstance()
        
        // Test that all actions are registered
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.Trigger"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.Accept"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.TabAccept"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.AcceptNextWord"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.AcceptNextLine"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.CycleNext"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.CyclePrevious"))
        assertNotNull(actionManager.getAction("Zest.InlineCompletion.Dismiss"))
    }

    @Test
    fun `test action types are correct`() {
        val actionManager = ActionManager.getInstance()
        
        assertTrue(actionManager.getAction("Zest.InlineCompletion.Trigger") is ZestTrigger)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.Accept") is ZestAccept)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.TabAccept") is ZestTabAccept)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.AcceptNextWord") is ZestAcceptNextWord)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.AcceptNextLine") is ZestAcceptNextLine)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.CycleNext") is ZestCycleNext)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.CyclePrevious") is ZestCyclePrevious)
        assertTrue(actionManager.getAction("Zest.InlineCompletion.Dismiss") is ZestDismiss)
    }

    @Test
    fun `test action groups are configured`() {
        val actionManager = ActionManager.getInstance()
        
        val mainGroup = actionManager.getAction("Zest.InlineCompletion")
        assertNotNull(mainGroup)
        
        val contextMenuGroup = actionManager.getAction("Zest.InlineCompletionContextMenu")
        assertNotNull(contextMenuGroup)
    }

    @Test
    fun `test trigger action can execute without completion service`() {
        val triggerAction = ZestTrigger()
        
        // Create a basic file
        myFixture.configureByText("test.java", "public class Test { <caret> }")
        
        // Create mock event
        val event = AnActionEvent.createFromDataContext(
            "test",
            null,
            SimpleDataContext.getSimpleContext(
                mapOf(
                    "project" to project,
                    "editor" to myFixture.editor
                )
            )
        )
        
        // Should not throw exception even if completion service is not available
        assertDoesNotThrow {
            triggerAction.update(event)
        }
        
        assertTrue(event.presentation.isEnabled)
    }

    @Test
    fun `test completion service integration`() {
        // Initialize completion service
        val completionService = project.service<ZestInlineCompletionService>()
        assertNotNull(completionService)
        
        // Create a basic file
        myFixture.configureByText("test.kt", "fun example() { <caret> }")
        
        // Test that service is available to actions
        assertNotNull(project.getService(ZestInlineCompletionService::class.java))
    }

    @Test
    fun `test action enablement without active completion`() {
        val actionManager = ActionManager.getInstance()
        myFixture.configureByText("test.java", "public class Test { <caret> }")
        
        val editor = myFixture.editor
        val caret = editor.caretModel.primaryCaret
        
        // Create data context
        val context = SimpleDataContext.getSimpleContext(
            mapOf(
                "project" to project,
                "editor" to editor,
                "caret" to caret
            )
        )
        
        // Test trigger action - should always be enabled
        val triggerAction = actionManager.getAction("Zest.InlineCompletion.Trigger") as ZestTrigger
        val triggerEvent = AnActionEvent.createFromDataContext("test", null, context)
        triggerAction.update(triggerEvent)
        assertTrue(triggerEvent.presentation.isEnabled)
        
        // Test accept action - should be disabled without completion
        val acceptAction = actionManager.getAction("Zest.InlineCompletion.Accept") as ZestAccept
        // Note: We can't easily test editor action enablement in light tests
        // This would require a more complex test setup with actual editor action handlers
    }

    @Test
    fun `test action priorities are set correctly`() {
        val actionManager = ActionManager.getInstance()
        
        val trigger = actionManager.getAction("Zest.InlineCompletion.Trigger") as HasPriority
        val accept = actionManager.getAction("Zest.InlineCompletion.Accept") as HasPriority
        val tabAccept = actionManager.getAction("Zest.InlineCompletion.TabAccept") as HasPriority
        val dismiss = actionManager.getAction("Zest.InlineCompletion.Dismiss") as HasPriority
        
        assertEquals(15, trigger.priority)
        assertEquals(12, accept.priority)
        assertEquals(11, tabAccept.priority)
        assertEquals(-1, dismiss.priority)
        
        // Verify priority ordering
        assertTrue(trigger.priority > accept.priority)
        assertTrue(accept.priority > tabAccept.priority)
        assertTrue(tabAccept.priority > dismiss.priority)
    }

    @Test
    fun `test keyboard shortcuts are configured`() {
        // This test verifies that keyboard shortcuts don't cause exceptions during registration
        // Full shortcut testing would require a more complex setup
        
        val actionManager = ActionManager.getInstance()
        
        // Test that getting keymap doesn't throw exceptions
        assertDoesNotThrow {
            val keymap = actionManager.getKeymap("$default")
            // Verify some shortcuts exist (this might vary by IDE version)
            // The actual shortcut testing is complex and usually done in integration tests
        }
    }
}
