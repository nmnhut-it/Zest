package com.zps.zest.completion

import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.state.CompletionStateMachine
import com.zps.zest.completion.trigger.CompletionTriggerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Test to verify the refactored completion service components work correctly
 */
class CompletionRefactoringTest {
    
    private lateinit var stateMachine: CompletionStateMachine
    private lateinit var triggerManager: CompletionTriggerManager
    private lateinit var scope: CoroutineScope
    
    @Before
    fun setup() {
        stateMachine = CompletionStateMachine()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        triggerManager = CompletionTriggerManager(stateMachine, scope)
    }
    
    @Test
    fun `test state machine basic transitions`() {
        // Should start in Idle state
        assertTrue("Should start in idle state", stateMachine.isIdle)
        assertFalse("Should not be requesting", stateMachine.isRequesting)
        assertFalse("Should not be displaying", stateMachine.isDisplaying)
        
        // Test transition to waiting
        val waitingEvent = CompletionStateMachine.Event.StartWaiting
        assertTrue("Should allow transition to waiting", stateMachine.handleEvent(waitingEvent))
        assertTrue("Should be in waiting state", stateMachine.isWaiting)
        
        // Test transition to requesting
        val requestId = stateMachine.generateRequestId()
        val requestingEvent = CompletionStateMachine.Event.StartRequesting(requestId)
        assertTrue("Should allow transition to requesting", stateMachine.handleEvent(requestingEvent))
        assertTrue("Should be in requesting state", stateMachine.isRequesting)
        assertEquals("Should have active request ID", requestId, stateMachine.activeRequestId)
    }
    
    @Test
    fun `test state machine invalid transitions`() {
        // Try to go directly from Idle to Displaying (should fail)
        val completion = createTestCompletion()
        val context = createTestContext()
        val displayEvent = CompletionStateMachine.Event.CompletionReceived(completion, context)
        
        assertFalse("Should not allow direct transition to displaying", stateMachine.handleEvent(displayEvent))
        assertTrue("Should remain in idle state", stateMachine.isIdle)
    }
    
    @Test
    fun `test completion flow through state machine`() {
        val completion = createTestCompletion()
        val context = createTestContext()
        
        // Start waiting
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting))
        
        // Start requesting
        val requestId = stateMachine.generateRequestId()
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId)))
        
        // Receive completion
        assertTrue(stateMachine.handleEvent(
            CompletionStateMachine.Event.CompletionReceived(completion, context)
        ))
        assertTrue("Should be displaying", stateMachine.isDisplaying)
        assertEquals("Should have completion", completion, stateMachine.currentCompletion)
        assertEquals("Should have context", context, stateMachine.currentContext)
        
        // Start accepting
        assertTrue(stateMachine.handleEvent(
            CompletionStateMachine.Event.StartAccepting("FULL_COMPLETION")
        ))
        assertTrue("Should be accepting", stateMachine.isAccepting)
        
        // Complete acceptance
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.AcceptanceComplete))
        assertTrue("Should be in cooldown", stateMachine.isInCooldown)
    }
    
    @Test
    fun `test trigger manager configuration`() {
        var triggerCallCount = 0
        var lastTriggerManual = false
        
        triggerManager.setTriggerCallback { _, _, manually ->
            triggerCallCount++
            lastTriggerManual = manually
        }
        
        // Configure trigger manager
        triggerManager.setConfiguration(
            autoTriggerEnabled = true,
            primaryDelayMs = 50L,
            secondaryDelayMs = 100L,
            aggressiveRetryEnabled = true
        )
        
        val triggerInfo = triggerManager.getTriggerInfo()
        assertTrue("Auto trigger should be enabled", triggerInfo["autoTriggerEnabled"] as Boolean)
        assertEquals("Primary delay should be 50ms", 50L, triggerInfo["primaryDelayMs"])
    }
    
    @Test
    fun `test state machine request ID generation`() {
        val id1 = stateMachine.generateRequestId()
        val id2 = stateMachine.generateRequestId()
        val id3 = stateMachine.generateRequestId()
        
        assertTrue("Request IDs should be positive", id1 > 0)
        assertTrue("Request IDs should be increasing", id2 > id1)
        assertTrue("Request IDs should be increasing", id3 > id2)
        assertTrue("Request IDs should be unique", setOf(id1, id2, id3).size == 3)
    }
    
    @Test
    fun `test state machine canTriggerCompletion logic`() {
        // Should be able to trigger when idle
        assertTrue("Should be able to trigger when idle", stateMachine.canTriggerCompletion())
        
        // Should not be able to trigger when requesting
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        val requestId = stateMachine.generateRequestId()
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId))
        assertFalse("Should not be able to trigger when requesting", stateMachine.canTriggerCompletion())
        
        // Reset to idle
        stateMachine.handleEvent(CompletionStateMachine.Event.Reset)
        assertTrue("Should be able to trigger after reset", stateMachine.canTriggerCompletion())
    }
    
    @Test
    fun `test state machine error handling`() {
        val errorMessage = "Test error message"
        val errorEvent = CompletionStateMachine.Event.Error(errorMessage)
        
        assertTrue("Should handle error event", stateMachine.handleEvent(errorEvent))
        assertTrue("Should be in error state", stateMachine.hasError)
        
        val stateInfo = stateMachine.getStateInfo()
        assertEquals("Should have error message", errorMessage, stateInfo["message"])
    }
    
    @Test
    fun `test state machine force reset`() {
        // Put state machine in a complex state
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        val requestId = stateMachine.generateRequestId()
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId))
        
        assertFalse("Should not be idle", stateMachine.isIdle)
        
        // Force reset
        stateMachine.forceReset()
        assertTrue("Should be idle after force reset", stateMachine.isIdle)
        assertNull("Should not have active request", stateMachine.activeRequestId)
    }
    
    @Test
    fun `test state transition listeners`() = runTest {
        var transitionCount = 0
        var lastOldState: CompletionStateMachine.State? = null
        var lastNewState: CompletionStateMachine.State? = null
        var lastEvent: CompletionStateMachine.Event? = null
        
        val listener = object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                transitionCount++
                lastOldState = oldState
                lastNewState = newState
                lastEvent = event
            }
        }
        
        stateMachine.addListener(listener)
        
        // Make a state transition
        val event = CompletionStateMachine.Event.StartWaiting
        stateMachine.handleEvent(event)
        
        assertEquals("Should have one transition", 1, transitionCount)
        assertTrue("Old state should be Idle", lastOldState is CompletionStateMachine.State.Idle)
        assertTrue("New state should be Waiting", lastNewState is CompletionStateMachine.State.Waiting)
        assertEquals("Event should match", event, lastEvent)
    }
    
    // Helper methods
    
    private fun createTestCompletion(): ZestInlineCompletionItem {
        return ZestInlineCompletionItem(
            insertText = "test completion text",
            replaceRange = ZestInlineCompletionItem.Range(0, 0),
            confidence = 0.8f,
            metadata = null,
            completionId = "test-completion-123"
        )
    }
    
    private fun createTestContext(): CompletionContext {
        return CompletionContext(
            fileName = "TestFile.kt",
            language = "kotlin",
            offset = 100,
            prefixCode = "val x = ",
            suffixCode = "",
            manually = false
        )
    }
}