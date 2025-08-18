package com.zps.zest.completion.state

import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for the completion state machine
 */
class CompletionStateMachineTest {
    
    private lateinit var stateMachine: CompletionStateMachine
    private var transitionCount = 0
    private var lastTransition: Triple<CompletionStateMachine.State, CompletionStateMachine.State, CompletionStateMachine.Event>? = null
    
    @Before
    fun setUp() {
        stateMachine = CompletionStateMachine()
        transitionCount = 0
        lastTransition = null
        
        // Add listener to track transitions
        stateMachine.addListener(object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                transitionCount++
                lastTransition = Triple(oldState, newState, event)
            }
        })
    }
    
    // =================================
    // Basic State Tests
    // =================================
    
    @Test
    fun `test initial state is idle`() {
        assertTrue("Should start in idle state", stateMachine.isIdle)
        assertFalse("Should not be waiting", stateMachine.isWaiting)
        assertFalse("Should not be requesting", stateMachine.isRequesting)
        assertFalse("Should not be displaying", stateMachine.isDisplaying)
        assertFalse("Should not be accepting", stateMachine.isAccepting)
        assertFalse("Should not be in cooldown", stateMachine.isInCooldown)
        assertFalse("Should not have error", stateMachine.hasError)
    }
    
    @Test
    fun `test state query methods`() {
        // Test in idle state
        assertTrue("canTriggerCompletion should be true in idle", stateMachine.canTriggerCompletion())
        assertFalse("canAcceptCompletion should be false in idle", stateMachine.canAcceptCompletion())
        assertNull("Should not have current completion", stateMachine.currentCompletion)
        assertNull("Should not have current context", stateMachine.currentContext)
        assertNull("Should not have active request ID", stateMachine.activeRequestId)
    }
    
    // =================================
    // State Transition Tests
    // =================================
    
    @Test
    fun `test valid idle to waiting transition`() {
        val event = CompletionStateMachine.Event.StartWaiting
        
        assertTrue("Should allow idle->waiting transition", stateMachine.handleEvent(event))
        assertTrue("Should be in waiting state", stateMachine.isWaiting)
        assertFalse("Should not be in idle state", stateMachine.isIdle)
        
        assertEquals("Should have one transition", 1, transitionCount)
        assertNotNull("Should have recorded transition", lastTransition)
        
        val (oldState, newState, recordedEvent) = lastTransition!!
        assertTrue("Old state should be Idle", oldState is CompletionStateMachine.State.Idle)
        assertTrue("New state should be Waiting", newState is CompletionStateMachine.State.Waiting)
        assertEquals("Event should match", event, recordedEvent)
    }
    
    @Test
    fun `test invalid idle to displaying transition`() {
        val completion = createTestCompletion()
        val context = createTestContext()
        val event = CompletionStateMachine.Event.CompletionReceived(completion, context)
        
        assertFalse("Should not allow idle->displaying transition", stateMachine.handleEvent(event))
        assertTrue("Should remain in idle state", stateMachine.isIdle)
        assertEquals("Should have no transitions", 0, transitionCount)
    }
    
    @Test
    fun `test complete valid flow idle to waiting to requesting to displaying to accepting to cooldown`() {
        val completion = createTestCompletion()
        val context = createTestContext()
        
        // Idle -> Waiting
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting))
        assertTrue("Should be waiting", stateMachine.isWaiting)
        
        // Waiting -> Requesting
        val requestId = stateMachine.generateRequestId()
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId)))
        assertTrue("Should be requesting", stateMachine.isRequesting)
        assertEquals("Should have active request ID", requestId, stateMachine.activeRequestId)
        
        // Requesting -> Displaying
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context)))
        assertTrue("Should be displaying", stateMachine.isDisplaying)
        assertEquals("Should have current completion", completion, stateMachine.currentCompletion)
        assertEquals("Should have current context", context, stateMachine.currentContext)
        assertTrue("Should be able to accept", stateMachine.canAcceptCompletion())
        
        // Displaying -> Accepting
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.StartAccepting("FULL_COMPLETION")))
        assertTrue("Should be accepting", stateMachine.isAccepting)
        assertFalse("Should not be able to trigger during acceptance", stateMachine.canTriggerCompletion())
        
        // Accepting -> Cooldown
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.AcceptanceComplete))
        assertTrue("Should be in cooldown", stateMachine.isInCooldown)
        
        assertEquals("Should have 5 transitions", 5, transitionCount)
    }
    
    @Test
    fun `test dismissal from various states`() {
        val completion = createTestCompletion()
        val context = createTestContext()
        
        // Test dismissal from waiting
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss("test")))
        assertTrue("Should return to idle", stateMachine.isIdle)
        
        // Test dismissal from displaying
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(stateMachine.generateRequestId()))
        stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context))
        assertTrue("Should be displaying", stateMachine.isDisplaying)
        
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss("test")))
        assertTrue("Should return to idle", stateMachine.isIdle)
    }
    
    // =================================
    // Error Handling Tests
    // =================================
    
    @Test
    fun `test error event from any state`() {
        val errorMessage = "Test error"
        
        // Error from idle
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.Error(errorMessage)))
        assertTrue("Should be in error state", stateMachine.hasError)
        
        // Reset and test error from requesting
        stateMachine.handleEvent(CompletionStateMachine.Event.Reset)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(stateMachine.generateRequestId()))
        
        assertTrue(stateMachine.handleEvent(CompletionStateMachine.Event.Error(errorMessage)))
        assertTrue("Should be in error state", stateMachine.hasError)
        
        val stateInfo = stateMachine.getStateInfo()
        assertEquals("Should have error message", errorMessage, stateInfo["message"])
    }
    
    @Test
    fun `test force reset from any state`() {
        val completion = createTestCompletion()
        val context = createTestContext()
        
        // Get to a complex state
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(stateMachine.generateRequestId()))
        stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context))
        assertFalse("Should not be idle", stateMachine.isIdle)
        
        // Force reset
        stateMachine.forceReset()
        assertTrue("Should be idle after force reset", stateMachine.isIdle)
        assertNull("Should not have current completion", stateMachine.currentCompletion)
        assertNull("Should not have active request ID", stateMachine.activeRequestId)
    }
    
    // =================================
    // Request ID Management Tests
    // =================================
    
    @Test
    fun `test request ID generation`() {
        val id1 = stateMachine.generateRequestId()
        val id2 = stateMachine.generateRequestId()
        val id3 = stateMachine.generateRequestId()
        
        assertTrue("Request IDs should be positive", id1 > 0)
        assertTrue("Request IDs should be increasing", id2 > id1)
        assertTrue("Request IDs should be increasing", id3 > id2)
        assertTrue("Request IDs should be unique", setOf(id1, id2, id3).size == 3)
    }
    
    @Test
    fun `test active request ID tracking`() {
        val requestId = stateMachine.generateRequestId()
        
        // No active request initially
        assertNull("Should not have active request", stateMachine.activeRequestId)
        
        // Start requesting
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId))
        assertEquals("Should have active request ID", requestId, stateMachine.activeRequestId)
        
        // Complete flow
        val completion = createTestCompletion()
        val context = createTestContext()
        stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context))
        
        // Should still have request ID until dismissed or completed
        assertEquals("Should still have active request ID", requestId, stateMachine.activeRequestId)
    }
    
    // =================================
    // Cooldown Tests
    // =================================
    
    @Test
    fun `test cooldown state timing`() = runTest {
        val completion = createTestCompletion()
        val context = createTestContext()
        
        // Get to cooldown state
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(stateMachine.generateRequestId()))
        stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context))
        stateMachine.handleEvent(CompletionStateMachine.Event.StartAccepting("FULL_COMPLETION"))
        stateMachine.handleEvent(CompletionStateMachine.Event.AcceptanceComplete)
        
        assertTrue("Should be in cooldown", stateMachine.isInCooldown)
        assertFalse("Should not be able to trigger during cooldown", stateMachine.canTriggerCompletion())
        
        val stateInfo = stateMachine.getStateInfo()
        assertTrue("Should have remaining cooldown time", 
            (stateInfo["remainingCooldown"] as Long) > 0)
    }
    
    // =================================
    // State Info Tests
    // =================================
    
    @Test
    fun `test state info provides comprehensive data`() {
        val stateInfo = stateMachine.getStateInfo()
        
        assertTrue("Should have state name", stateInfo.containsKey("stateName"))
        assertTrue("Should have timestamp", stateInfo.containsKey("timestamp"))
        assertEquals("Should show Idle state", "Idle", stateInfo["stateName"])
        
        // Test state info in different states
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        val waitingInfo = stateMachine.getStateInfo()
        assertEquals("Should show Waiting state", "Waiting", waitingInfo["stateName"])
        assertTrue("Should have waiting time", waitingInfo.containsKey("waitingTime"))
    }
    
    // =================================
    // Listener Management Tests
    // =================================
    
    @Test
    fun `test listener management`() {
        var callCount = 0
        val listener = object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                callCount++
            }
        }
        
        // Add listener
        stateMachine.addListener(listener)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        assertEquals("Listener should be called", 1, callCount)
        
        // Remove listener
        stateMachine.removeListener(listener)
        stateMachine.handleEvent(CompletionStateMachine.Event.Reset)
        assertEquals("Listener should not be called after removal", 1, callCount)
    }
    
    @Test
    fun `test listener error handling`() {
        val faultyListener = object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                throw RuntimeException("Test exception")
            }
        }
        
        stateMachine.addListener(faultyListener)
        
        // Should not crash the state machine
        assertTrue("Should handle listener exceptions gracefully",
            stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting))
        assertTrue("Should be in waiting state despite listener error", stateMachine.isWaiting)
    }
    
    // =================================
    // Helper Methods
    // =================================
    
    private fun createTestCompletion(): ZestInlineCompletionItem {
        return ZestInlineCompletionItem(
            insertText = "test completion",
            replaceRange = ZestInlineCompletionItem.Range(0, 0),
            confidence = 0.8f,
            metadata = null,
            completionId = "test-${System.currentTimeMillis()}"
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