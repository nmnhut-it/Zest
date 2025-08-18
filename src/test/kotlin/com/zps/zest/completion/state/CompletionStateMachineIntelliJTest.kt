package com.zps.zest.completion.state

import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TestFixtureBuilder
import com.intellij.testFramework.LightProjectDescriptor
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * IntelliJ Platform-specific tests for the completion state machine
 * Follows IntelliJ testing best practices with proper EDT handling
 */
class CompletionStateMachineIntelliJTest {
    
    @get:Rule
    val projectRule = ProjectRule()
    
    @get:Rule 
    val edtRule = EdtRule()
    
    private lateinit var myFixture: CodeInsightTestFixture
    private lateinit var stateMachine: CompletionStateMachine
    
    @Before
    fun setUp() {
        val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixtureBuilder: TestFixtureBuilder<IdeaProjectTestFixture> = 
            fixtureFactory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "StateMachineTest")
        
        myFixture = fixtureFactory.createCodeInsightFixture(fixtureBuilder.fixture)
        myFixture.setUp()
        
        stateMachine = CompletionStateMachine()
    }
    
    @After
    fun tearDown() {
        try {
            myFixture.tearDown()
        } catch (e: Exception) {
            // Ignore cleanup exceptions
        }
    }
    
    // =================================
    // EDT-Safe State Machine Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test state machine initialization in IntelliJ context`() {
        // Test on EDT as required for IntelliJ operations
        assertTrue("Should start in idle state", stateMachine.isIdle)
        assertFalse("Should not be requesting", stateMachine.isRequesting)
        assertFalse("Should not be displaying", stateMachine.isDisplaying)
        
        val stateInfo = stateMachine.getStateInfo()
        assertNotNull("Should have state info", stateInfo)
        assertEquals("Should be in Idle state", "Idle", stateInfo["stateName"])
    }
    
    @Test
    @RunsInEdt
    fun `test state transitions with real IntelliJ project context`() {
        // Configure real file for context
        myFixture.configureByText("StateTest.kt", """
            class StateTest {
                fun test() {
                    val x = <caret>
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val context = CompletionContext.from(editor, editor.caretModel.offset, manually = false)
        val completion = createTestCompletion()
        
        // Test full state flow with real IntelliJ context
        assertTrue("Should allow waiting", 
            stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting))
        assertTrue("Should be waiting", stateMachine.isWaiting)
        
        val requestId = stateMachine.generateRequestId()
        assertTrue("Should allow requesting",
            stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(requestId)))
        assertTrue("Should be requesting", stateMachine.isRequesting)
        assertEquals("Should have active request", requestId, stateMachine.activeRequestId)
        
        assertTrue("Should allow completion received",
            stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context)))
        assertTrue("Should be displaying", stateMachine.isDisplaying)
        assertEquals("Should have completion", completion, stateMachine.currentCompletion)
        assertEquals("Should have context", context, stateMachine.currentContext)
    }
    
    @Test
    @RunsInEdt
    fun `test state machine with different file types`() {
        val fileConfigs = mapOf(
            "Test.kt" to "val x = <caret>hello",
            "Test.java" to "String x = <caret>\"hello\";",
            "test.js" to "const x = <caret>'hello';"
        )
        
        for ((fileName, content) in fileConfigs) {
            myFixture.configureByText(fileName, content)
            
            val editor = myFixture.editor
            val context = CompletionContext.from(editor, editor.caretModel.offset, manually = false)
            
            // State machine should handle different language contexts
            assertNotNull("Should create context for $fileName", context)
            assertEquals("Should have correct file name", fileName, context.fileName)
            
            // Test state transitions with different contexts
            stateMachine.forceReset() // Reset between file types
            assertTrue("Should start idle for $fileName", stateMachine.isIdle)
            
            assertTrue("Should allow waiting for $fileName",
                stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting))
        }
    }
    
    @Test
    @RunsInEdt
    fun `test listener integration with IntelliJ message bus pattern`() {
        var transitionCount = 0
        var lastTransition: Triple<CompletionStateMachine.State, CompletionStateMachine.State, CompletionStateMachine.Event>? = null
        
        // Add listener (similar to IntelliJ message bus pattern)
        val listener = object : CompletionStateMachine.StateTransitionListener {
            override fun onStateChanged(
                oldState: CompletionStateMachine.State,
                newState: CompletionStateMachine.State,
                event: CompletionStateMachine.Event
            ) {
                transitionCount++
                lastTransition = Triple(oldState, newState, event)
            }
        }
        
        stateMachine.addListener(listener)
        
        // Trigger state change
        val event = CompletionStateMachine.Event.StartWaiting
        assertTrue("Should handle event", stateMachine.handleEvent(event))
        
        // Verify listener was called (like message bus)
        assertEquals("Should have one transition", 1, transitionCount)
        assertNotNull("Should have recorded transition", lastTransition)
        
        val (oldState, newState, recordedEvent) = lastTransition!!
        assertTrue("Old state should be Idle", oldState is CompletionStateMachine.State.Idle)
        assertTrue("New state should be Waiting", newState is CompletionStateMachine.State.Waiting)
        assertEquals("Event should match", event, recordedEvent)
        
        // Test listener removal (like unsubscribing from message bus)
        stateMachine.removeListener(listener)
        stateMachine.handleEvent(CompletionStateMachine.Event.Reset)
        assertEquals("Listener should not be called after removal", 1, transitionCount)
    }
    
    @Test
    @RunsInEdt
    fun `test error handling with IntelliJ project context`() {
        myFixture.configureByText("ErrorTest.kt", "val error = <caret>")
        
        val errorMessage = "Test error with IntelliJ context"
        val errorEvent = CompletionStateMachine.Event.Error(errorMessage)
        
        assertTrue("Should handle error event", stateMachine.handleEvent(errorEvent))
        assertTrue("Should be in error state", stateMachine.hasError)
        
        val stateInfo = stateMachine.getStateInfo()
        assertEquals("Should have error message", errorMessage, stateInfo["message"])
        
        // Recovery should work
        assertTrue("Should allow reset from error", 
            stateMachine.handleEvent(CompletionStateMachine.Event.Reset))
        assertTrue("Should be back to idle", stateMachine.isIdle)
        assertFalse("Should not have error", stateMachine.hasError)
    }
    
    @Test
    @RunsInEdt
    fun `test force reset in IntelliJ context`() {
        myFixture.configureByText("ResetTest.kt", """
            class ResetTest {
                fun complexMethod() {
                    val complex = <caret>
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val context = CompletionContext.from(editor, editor.caretModel.offset, manually = false)
        val completion = createTestCompletion()
        
        // Get to complex state
        stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
        stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(stateMachine.generateRequestId()))
        stateMachine.handleEvent(CompletionStateMachine.Event.CompletionReceived(completion, context))
        
        assertFalse("Should not be idle", stateMachine.isIdle)
        assertNotNull("Should have completion", stateMachine.currentCompletion)
        
        // Force reset should work from any state
        stateMachine.forceReset()
        assertTrue("Should be idle after force reset", stateMachine.isIdle)
        assertNull("Should not have completion", stateMachine.currentCompletion)
        assertNull("Should not have active request", stateMachine.activeRequestId)
    }
    
    @Test
    @RunsInEdt
    fun `test performance with IntelliJ operations`() {
        myFixture.configureByText("Performance.kt", "val perf = <caret>")
        
        val iterations = 1000
        val startTime = System.currentTimeMillis()
        
        // Perform many state operations (similar to real usage)
        repeat(iterations) { i ->
            when (i % 4) {
                0 -> stateMachine.getStateInfo()
                1 -> stateMachine.generateRequestId() 
                2 -> stateMachine.canTriggerCompletion()
                3 -> stateMachine.canAcceptCompletion()
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("State operations should be fast in IntelliJ context (${elapsed}ms for $iterations ops)",
            elapsed < 1000)
        
        // State should remain consistent
        assertTrue("Should maintain valid state", stateMachine.isIdle)
    }
    
    @Test
    @RunsInEdt
    fun `test cooldown behavior with timing`() {
        myFixture.configureByText("Cooldown.kt", "val cooldown = <caret>")
        
        val editor = myFixture.editor
        val context = CompletionContext.from(editor, editor.caretModel.offset, manually = false)
        val completion = createTestCompletion()
        
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
    // Integration with IntelliJ Threading Model
    // =================================
    
    @Test
    fun `test thread safety with IntelliJ threading model`() {
        // This test doesn't use @RunsInEdt to test background operations
        val backgroundOperations = 100
        val results = mutableListOf<Boolean>()
        val exceptions = mutableListOf<Exception>()
        
        // Run operations that are safe for background threads
        val threads = (1..backgroundOperations).map { i ->
            Thread {
                try {
                    val result = when (i % 5) {
                        0 -> stateMachine.isIdle
                        1 -> stateMachine.canTriggerCompletion()
                        2 -> stateMachine.getStateInfo().isNotEmpty()
                        3 -> stateMachine.generateRequestId() > 0
                        else -> !stateMachine.hasError
                    }
                    synchronized(results) {
                        results.add(result)
                    }
                } catch (e: Exception) {
                    synchronized(exceptions) {
                        exceptions.add(e)
                    }
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        assertEquals("All operations should complete", backgroundOperations, results.size)
        assertTrue("Should not have exceptions: ${exceptions.map { it.message }}", 
            exceptions.isEmpty())
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
            completionId = "intellij-test-${System.currentTimeMillis()}"
        )
    }
    
    // Simplified assertions for clarity
    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
    
    private fun assertFalse(message: String, condition: Boolean) {
        if (condition) throw AssertionError(message)
    }
    
    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message. Expected: $expected, Actual: $actual")
    }
    
    private fun assertNotNull(message: String, obj: Any?) {
        if (obj == null) throw AssertionError(message)
    }
    
    private fun assertNull(message: String, obj: Any?) {
        if (obj != null) throw AssertionError("$message. Expected null but was: $obj")
    }
}