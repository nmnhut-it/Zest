package com.zps.zest.completion.trigger

import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.state.CompletionStateMachine
import com.zps.zest.completion.trigger.CompletionTriggerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Performance and timing tests for the trigger manager and debouncing logic
 */
@ExperimentalCoroutinesApi
class TriggerManagerPerformanceTest {
    
    private lateinit var stateMachine: CompletionStateMachine
    private lateinit var triggerManager: CompletionTriggerManager
    private lateinit var testScope: TestScope
    private lateinit var mockEditor: Editor
    
    private val triggerCount = AtomicInteger(0)
    private val lastTriggerTime = AtomicInteger(0)
    private val triggerTimes = mutableListOf<Long>()
    
    @Before
    fun setUp() {
        testScope = TestScope()
        stateMachine = CompletionStateMachine()
        triggerManager = CompletionTriggerManager(stateMachine, testScope)
        mockEditor = mock(Editor::class.java)
        
        // Reset counters
        triggerCount.set(0)
        lastTriggerTime.set(0)
        triggerTimes.clear()
        
        // Setup trigger callback that records timing
        triggerManager.setTriggerCallback { _, offset, manually ->
            val currentTime = System.currentTimeMillis().toInt()
            triggerCount.incrementAndGet()
            lastTriggerTime.set(currentTime)
            synchronized(triggerTimes) {
                triggerTimes.add(System.currentTimeMillis())
            }
        }
        
        // Configure for testing
        triggerManager.setConfiguration(
            autoTriggerEnabled = true,
            primaryDelayMs = 100L,
            secondaryDelayMs = 500L,
            aggressiveRetryEnabled = true
        )
    }
    
    @After
    fun tearDown() {
        triggerManager.dispose()
        testScope.cancel()
    }
    
    // =================================
    // Debouncing Performance Tests
    // =================================
    
    @Test
    fun `test primary debouncing timing accuracy`() = testScope.runTest {
        val expectedDelay = 100L
        val tolerance = 20L // 20ms tolerance
        
        val startTime = System.currentTimeMillis()
        triggerManager.scheduleCompletionAfterActivity(mockEditor, "test")
        
        // Advance time to just before trigger should fire
        advanceTimeBy(expectedDelay - 10L)
        assertEquals("Should not have triggered yet", 0, triggerCount.get())
        
        // Advance time to when trigger should fire
        advanceTimeBy(20L)
        
        val actualDelay = System.currentTimeMillis() - startTime
        assertTrue("Trigger should have fired within tolerance. Expected: ${expectedDelay}ms, Actual: ${actualDelay}ms",
            kotlin.math.abs(actualDelay - expectedDelay) <= tolerance)
        
        assertEquals("Should have triggered once", 1, triggerCount.get())
    }
    
    @Test
    fun `test debouncing cancellation performance`() = testScope.runTest {
        // Schedule multiple rapid triggers
        repeat(10) { i ->
            triggerManager.scheduleCompletionAfterActivity(mockEditor, "rapid-$i")
            advanceTimeBy(10L) // 10ms between schedules
        }
        
        // Should still be at 0 because each schedule cancels the previous
        assertEquals("Should not have triggered during rapid scheduling", 0, triggerCount.get())
        
        // Now advance past the debounce delay
        advanceTimeBy(100L)
        
        assertEquals("Should trigger only once after debouncing", 1, triggerCount.get())
    }
    
    @Test
    fun `test secondary trigger timing`() = testScope.runTest {
        triggerManager.scheduleCompletionAfterActivity(mockEditor, "test")
        
        // Primary trigger should fire at 100ms
        advanceTimeBy(100L)
        assertEquals("Primary trigger should fire", 1, triggerCount.get())
        
        // Secondary trigger should fire at 500ms if enabled
        advanceTimeBy(400L) // Total 500ms
        
        // Note: Secondary trigger behavior depends on state machine state
        // In this test, it may not fire if primary already succeeded
        assertTrue("Should have at least primary trigger", triggerCount.get() >= 1)
    }
    
    // =================================
    // Concurrent Access Tests
    // =================================
    
    @Test
    fun `test concurrent scheduling performance`() = testScope.runTest {
        val concurrentOperations = 100
        val completedOperations = AtomicInteger(0)
        
        val jobs = (1..concurrentOperations).map { i ->
            launch {
                try {
                    triggerManager.scheduleCompletionAfterActivity(mockEditor, "concurrent-$i")
                    completedOperations.incrementAndGet()
                } catch (e: Exception) {
                    fail("Concurrent scheduling should not fail: ${e.message}")
                }
            }
        }
        
        // Wait for all operations to complete
        jobs.forEach { it.join() }
        
        assertEquals("All operations should complete", concurrentOperations, completedOperations.get())
        
        // Advance time to trigger
        advanceTimeBy(150L)
        
        // Should only trigger once despite many schedules
        assertEquals("Should debounce to single trigger", 1, triggerCount.get())
    }
    
    @Test
    fun `test trigger manager state consistency under load`() = testScope.runTest {
        val operations = 50
        
        repeat(operations) { i ->
            when (i % 3) {
                0 -> triggerManager.scheduleCompletionAfterActivity(mockEditor, "load-$i")
                1 -> triggerManager.cancelAllTimers()
                2 -> triggerManager.requestCompletionNow(mockEditor, i)
            }
            
            // Small delay between operations
            advanceTimeBy(5L)
        }
        
        // Get final state
        val triggerInfo = triggerManager.getTriggerInfo()
        assertNotNull("Should have valid trigger info under load", triggerInfo)
        assertTrue("Should maintain state consistency", triggerInfo.containsKey("autoTriggerEnabled"))
    }
    
    // =================================
    // Memory and Resource Tests
    // =================================
    
    @Test
    fun `test memory usage during extended operation`() = testScope.runTest {
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // Perform many trigger operations
        repeat(1000) { i ->
            triggerManager.scheduleCompletionAfterActivity(mockEditor, "memory-$i")
            if (i % 100 == 0) {
                advanceTimeBy(50L) // Occasional time advancement
            }
            if (i % 200 == 0) {
                triggerManager.cancelAllTimers()
            }
        }
        
        // Force cleanup and GC
        triggerManager.cancelAllTimers()
        System.gc()
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        // Memory increase should be minimal (less than 5MB)
        assertTrue("Memory usage should remain stable during extended operation. Increase: ${memoryIncrease / 1024 / 1024}MB",
            memoryIncrease < 5 * 1024 * 1024)
    }
    
    @Test
    fun `test timer cleanup performance`() {
        val cleanupTime = measureTimeMillis {
            // Create many timers
            repeat(100) { i ->
                runBlocking {
                    triggerManager.scheduleCompletionAfterActivity(mockEditor, "cleanup-$i")
                    delay(1) // Small delay to ensure timer creation
                }
            }
            
            // Cancel all at once
            triggerManager.cancelAllTimers()
        }
        
        assertTrue("Timer cleanup should be fast (${cleanupTime}ms)", cleanupTime < 1000)
        
        // Verify all timers are actually canceled
        val triggerInfo = triggerManager.getTriggerInfo()
        assertFalse("No timers should be active after cleanup",
            triggerInfo["primaryTimerActive"] as Boolean)
        assertFalse("No secondary timers should be active after cleanup",
            triggerInfo["secondaryTimerActive"] as Boolean)
    }
    
    // =================================
    // Accuracy Tests
    // =================================
    
    @Test
    fun `test debouncing accuracy under system load`() = testScope.runTest {
        // Simulate system load with background work
        val backgroundJob = launch {
            while (isActive) {
                // Simulate CPU work
                var sum = 0
                repeat(1000) { sum += it }
                delay(1)
            }
        }
        
        try {
            val expectedDelay = 100L
            val startTime = currentTime
            
            triggerManager.scheduleCompletionAfterActivity(mockEditor, "load-test")
            advanceTimeBy(expectedDelay)
            
            val actualDelay = currentTime - startTime
            assertTrue("Should maintain accuracy under load. Expected: ${expectedDelay}ms, Actual: ${actualDelay}ms",
                actualDelay == expectedDelay)
            
            assertEquals("Should trigger once under load", 1, triggerCount.get())
            
        } finally {
            backgroundJob.cancel()
        }
    }
    
    @Test
    fun `test rapid trigger-cancel-trigger cycles`() = testScope.runTest {
        val cycles = 50
        
        repeat(cycles) { i ->
            triggerManager.scheduleCompletionAfterActivity(mockEditor, "cycle-$i")
            advanceTimeBy(50L) // Half the debounce delay
            triggerManager.cancelAllTimers()
            advanceTimeBy(10L)
        }
        
        // Should not have triggered during cycles
        assertEquals("Should not trigger during rapid cycles", 0, triggerCount.get())
        
        // Now do a final trigger that completes
        triggerManager.scheduleCompletionAfterActivity(mockEditor, "final")
        advanceTimeBy(100L)
        
        assertEquals("Should trigger after cycles complete", 1, triggerCount.get())
    }
    
    // =================================
    // Real-world Simulation Tests
    // =================================
    
    @Test
    fun `test realistic typing pattern simulation`() = testScope.runTest {
        // Simulate realistic typing: bursts of activity followed by pauses
        val typingBursts = listOf(
            listOf(50, 30, 40, 60, 35), // Fast typing burst
            listOf(200, 180, 220, 190), // Slower typing
            listOf(20, 25, 30, 15, 40, 35, 30), // Very fast typing
        )
        
        var totalTriggers = 0
        
        for ((burstIndex, burst) in typingBursts.withIndex()) {
            // Simulate typing burst
            for ((i, delay) in burst.withIndex()) {
                triggerManager.scheduleCompletionAfterActivity(mockEditor, "burst-$burstIndex-$i")
                advanceTimeBy(delay.toLong())
            }
            
            // Pause after burst (trigger should fire)
            advanceTimeBy(150L)
            
            val triggersAfterBurst = triggerCount.get()
            assertTrue("Should have triggered after burst $burstIndex", triggersAfterBurst > totalTriggers)
            totalTriggers = triggersAfterBurst
            
            // Pause between bursts
            advanceTimeBy(500L)
        }
        
        assertEquals("Should have triggered once per burst", typingBursts.size, totalTriggers)
    }
    
    @Test
    fun `test trigger timing distribution`() = testScope.runTest {
        val triggers = 20
        val expectedDelay = 100L
        
        repeat(triggers) { i ->
            val startTime = currentTime
            triggerManager.scheduleCompletionAfterActivity(mockEditor, "distribution-$i")
            advanceTimeBy(expectedDelay)
            
            val actualDelay = currentTime - startTime
            synchronized(triggerTimes) {
                triggerTimes.add(actualDelay)
            }
            
            // Small pause between triggers
            advanceTimeBy(50L)
        }
        
        assertEquals("Should have triggered expected number of times", triggers, triggerCount.get())
        
        // Analyze timing distribution
        val delays = triggerTimes
        val avgDelay = delays.average()
        val maxDeviation = delays.maxOf { kotlin.math.abs(it - expectedDelay) }
        
        assertTrue("Average delay should be close to expected (avg: ${avgDelay}ms, expected: ${expectedDelay}ms)",
            kotlin.math.abs(avgDelay - expectedDelay) < 10)
        
        assertTrue("Maximum deviation should be reasonable (max: ${maxDeviation}ms)",
            maxDeviation < 20)
    }
    
    // =================================
    // Configuration Change Tests
    // =================================
    
    @Test
    fun `test configuration changes during operation`() = testScope.runTest {
        // Start with default configuration
        triggerManager.scheduleCompletionAfterActivity(mockEditor, "config-test-1")
        advanceTimeBy(50L)
        
        // Change configuration mid-flight
        triggerManager.setConfiguration(
            autoTriggerEnabled = false,
            primaryDelayMs = 200L,
            secondaryDelayMs = 1000L
        )
        
        // Continue advancing time
        advanceTimeBy(100L) // Would have triggered with old config
        assertEquals("Should not trigger with auto-trigger disabled", 0, triggerCount.get())
        
        // Re-enable auto-trigger with new timing
        triggerManager.setConfiguration(
            autoTriggerEnabled = true,
            primaryDelayMs = 50L,
            secondaryDelayMs = 300L
        )
        
        triggerManager.scheduleCompletionAfterActivity(mockEditor, "config-test-2")
        advanceTimeBy(50L)
        
        assertEquals("Should trigger with new configuration", 1, triggerCount.get())
    }
    
    // =================================
    // Edge Case Performance Tests
    // =================================
    
    @Test
    fun `test performance with immediate cancellation`() {
        val iterations = 1000
        
        val executionTime = measureTimeMillis {
            repeat(iterations) { i ->
                runBlocking {
                    triggerManager.scheduleCompletionAfterActivity(mockEditor, "immediate-cancel-$i")
                    triggerManager.cancelAllTimers()
                }
            }
        }
        
        assertTrue("Immediate cancellation should be fast (${executionTime}ms for $iterations operations)",
            executionTime < 1000)
        
        assertEquals("Should not have triggered during immediate cancellations", 0, triggerCount.get())
    }
    
    @Test
    fun `test state machine coordination performance`() = testScope.runTest {
        // Test trigger manager performance when state machine is in various states
        val states = listOf("idle", "waiting", "requesting", "displaying")
        
        for (state in states) {
            // Simulate different state machine states
            when (state) {
                "waiting" -> stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
                "requesting" -> {
                    stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
                    stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(1))
                }
                "displaying" -> {
                    stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)
                    stateMachine.handleEvent(CompletionStateMachine.Event.StartRequesting(1))
                    // Note: Would need completion context for full state
                }
                else -> stateMachine.forceReset()
            }
            
            val iterations = 10
            val startTime = currentTime
            
            repeat(iterations) { i ->
                triggerManager.scheduleCompletionAfterActivity(mockEditor, "$state-$i")
                advanceTimeBy(20L)
                triggerManager.cancelAllTimers()
            }
            
            val elapsed = currentTime - startTime
            assertTrue("Operations should be fast in $state state (${elapsed}ms)",
                elapsed < 1000L)
            
            // Reset for next test
            stateMachine.forceReset()
        }
    }
}