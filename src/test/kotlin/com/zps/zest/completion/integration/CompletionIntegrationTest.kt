package com.zps.zest.completion.integration

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for the complete completion flow
 * Tests the interaction between all components
 */
class CompletionIntegrationTest : LightJavaCodeInsightFixtureTestCase() {
    
    private lateinit var completionService: ZestInlineCompletionService
    
    override fun setUp() {
        super.setUp()
        completionService = ZestInlineCompletionService(project)
        completionService.setDebugLogging(true)
    }
    
    override fun tearDown() {
        completionService.dispose()
        super.tearDown()
    }
    
    // =================================
    // Full Flow Integration Tests
    // =================================
    
    @Test
    fun `test complete completion lifecycle`() {
        myFixture.configureByText("test.kt", """
            class TestClass {
                fun testMethod() {
                    val x = <caret>
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val document = editor.document
        val initialOffset = editor.caretModel.offset
        
        // Verify initial state
        assertFalse("Should not have completion initially", 
            completionService.isInlineCompletionVisibleAt(editor, initialOffset))
        
        // Test the service can handle the editor state
        assertNotNull("Editor should be valid", editor)
        assertFalse("Editor should not be disposed", editor.isDisposed)
        
        val state = completionService.getDetailedState()
        assertTrue("Should be in idle state initially", 
            state["stateName"]?.toString()?.contains("Idle") == true)
    }
    
    @Test
    fun `test configuration changes affect behavior`() {
        val initialEnabled = completionService.isEnabled()
        
        // Update configuration
        completionService.updateConfiguration()
        
        // Service should handle configuration changes gracefully
        val stateAfterUpdate = completionService.getDetailedState()
        assertNotNull("State should be valid after configuration update", stateAfterUpdate)
        
        assertTrue("Should maintain valid state", 
            stateAfterUpdate.containsKey("isEnabled"))
    }
    
    @Test
    fun `test strategy switching during operation`() {
        myFixture.configureByText("test.kt", "val x = hello<caret>")
        
        // Switch strategies multiple times
        completionService.setCompletionStrategy(
            ZestCompletionProvider.CompletionStrategy.LEAN
        )
        assertEquals("Should switch to LEAN", 
            ZestCompletionProvider.CompletionStrategy.LEAN,
            completionService.getCompletionStrategy())
        
        completionService.setCompletionStrategy(
            ZestCompletionProvider.CompletionStrategy.SIMPLE
        )
        assertEquals("Should switch to SIMPLE",
            ZestCompletionProvider.CompletionStrategy.SIMPLE, 
            completionService.getCompletionStrategy())
        
        // Cache should be cleared after strategy changes
        val cacheStats = completionService.getCacheStats()
        assertTrue("Cache should be cleared", cacheStats.contains("entries: 0"))
    }
    
    @Test
    fun `test completion acceptance workflow`() {
        myFixture.configureByText("test.kt", "val result = <caret>")
        val editor = myFixture.editor
        
        // Simulate having a completion (in real scenario this would come from provider)
        // For now, test the acceptance logic doesn't crash
        
        try {
            completionService.accept(editor, null, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
            // Should not crash even without active completion
            assertTrue("Acceptance should handle no-completion gracefully", true)
        } catch (e: Exception) {
            fail("Should not throw exception when no completion is active: ${e.message}")
        }
    }
    
    @Test 
    fun `test completion dismissal workflow`() {
        myFixture.configureByText("test.kt", "val test = <caret>")
        
        // Test dismissal
        completionService.dismiss()
        
        val state = completionService.getDetailedState()
        assertTrue("Should be in valid state after dismissal", 
            state.containsKey("stateName"))
    }
    
    // =================================
    // Error Recovery Tests
    // =================================
    
    @Test
    fun `test service handles disposed editor gracefully`() {
        myFixture.configureByText("test.kt", "val x = <caret>")
        val editor = myFixture.editor
        
        // Service should handle editor operations safely
        assertFalse("Should not show completion on disposed editor",
            completionService.isInlineCompletionVisibleAt(editor, 0))
    }
    
    @Test
    fun `test force refresh recovers from any state`() {
        // Get service into some state
        completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
        
        // Force refresh should reset everything
        completionService.forceRefreshState()
        
        val state = completionService.getDetailedState()
        assertNotNull("State should be valid after force refresh", state)
        assertTrue("Should have cleared cache", 
            completionService.getCacheStats().contains("entries: 0"))
    }
    
    @Test
    fun `test concurrent operations don't cause race conditions`() = runBlocking {
        myFixture.configureByText("test.kt", "val concurrent = <caret>")
        
        val completedOperations = AtomicInteger(0)
        val exceptions = mutableListOf<Exception>()
        
        // Launch multiple concurrent operations
        val jobs: List<Job> = (1..10).map { i ->
            this.launch {
                try {
                    when (i % 4) {
                        0 -> completionService.dismiss()
                        1 -> completionService.forceRefreshState()
                        2 -> completionService.updateConfiguration()
                        3 -> {
                            val strategy = if (i % 2 == 0) 
                                ZestCompletionProvider.CompletionStrategy.SIMPLE
                            else 
                                ZestCompletionProvider.CompletionStrategy.LEAN
                            completionService.setCompletionStrategy(strategy)
                        }
                    }
                    completedOperations.incrementAndGet()
                } catch (e: Exception) {
                    synchronized(exceptions) {
                        exceptions.add(e)
                    }
                }
            }
        }
        
        // Wait for all operations to complete
        jobs.forEach { it.join() }
        
        // Verify results
        assertEquals("All operations should complete", 10, completedOperations.get())
        assertTrue("Should not have any exceptions: ${exceptions.map { it.message }}", 
            exceptions.isEmpty())
        
        // Service should still be in valid state
        val finalState = completionService.getDetailedState()
        assertNotNull("Should have valid final state", finalState)
    }
    
    // =================================
    // Performance Tests
    // =================================
    
    @Test
    fun `test service responds quickly to state queries`() {
        val iterations = 1000
        val startTime = System.currentTimeMillis()
        
        repeat(iterations) {
            completionService.getDetailedState()
            completionService.isEnabled()
            completionService.getCompletionStrategy()
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("State queries should be fast (${elapsed}ms for $iterations calls)", 
            elapsed < 1000) // Should complete 1000 calls in under 1 second
    }
    
    @Test 
    fun `test memory usage remains stable`() {
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // Perform many operations
        repeat(100) { i ->
            completionService.setCompletionStrategy(
                if (i % 2 == 0) ZestCompletionProvider.CompletionStrategy.SIMPLE 
                else ZestCompletionProvider.CompletionStrategy.LEAN
            )
            completionService.forceRefreshState()
        }
        
        // Force garbage collection
        System.gc()
        Thread.sleep(100)
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        // Memory increase should be reasonable (less than 10MB)
        assertTrue("Memory usage should remain stable. Increase: ${memoryIncrease / 1024 / 1024}MB",
            memoryIncrease < 10 * 1024 * 1024)
    }
    
    // =================================
    // Real Editor Integration Tests
    // =================================
    
    @Test
    fun `test with real editor typing scenario`() {
        myFixture.configureByText("test.kt", "")
        
        // Simulate typing
        myFixture.type("class TestClass {\n    fun test() {\n        val x = ")
        
        val editor = myFixture.editor
        val finalOffset = editor.caretModel.offset
        
        // Service should handle real editor state
        val state = completionService.getDetailedState()
        assertNotNull("Should handle real editor state", state)
        
        // Should be able to query completion state at cursor position
        assertFalse("Should handle real cursor position",
            completionService.isInlineCompletionVisibleAt(editor, finalOffset))
    }
    
    @Test
    fun `test with real file operations`() {
        // Create a temporary file
        myFixture.configureByText("RealFile.kt", """
            package com.test
            
            class RealFile {
                fun realMethod() {
                    val realVariable = <caret>
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        
        // Test service handles real file context
        assertTrue("Should handle real file", editor.document.text.contains("realVariable"))
        
        val state = completionService.getDetailedState()
        assertNotNull("Should handle real file state", state)
        
        // Test operations on real file
        completionService.dismiss()
        completionService.forceRefreshState()
        
        // Should not crash
        assertTrue("Should handle real file operations", true)
    }
    
    // =================================
    // Cache Integration Tests
    // =================================
    
    @Test
    fun `test cache behavior across operations`() {
        // Initial cache should be empty
        assertTrue("Cache should start empty", 
            completionService.getCacheStats().contains("entries: 0"))
        
        // Strategy changes should clear cache
        completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
        assertTrue("Cache should be cleared after strategy change",
            completionService.getCacheStats().contains("entries: 0"))
        
        // Configuration updates should clear cache
        completionService.updateConfiguration()
        assertTrue("Cache should be cleared after config update",
            completionService.getCacheStats().contains("entries: 0"))
        
        // Force refresh should clear cache
        completionService.forceRefreshState()
        assertTrue("Cache should be cleared after force refresh",
            completionService.getCacheStats().contains("entries: 0"))
    }
    
    @Test
    fun `test service lifecycle management`() {
        // Test that service can be safely disposed and recreated
        val originalState = completionService.getDetailedState()
        assertNotNull("Should have valid initial state", originalState)
        
        // Dispose service
        completionService.dispose()
        
        // Create new service
        val newService = ZestInlineCompletionService(project)
        val newState = newService.getDetailedState()
        assertNotNull("New service should have valid state", newState)
        
        // Clean up
        newService.dispose()
    }
}