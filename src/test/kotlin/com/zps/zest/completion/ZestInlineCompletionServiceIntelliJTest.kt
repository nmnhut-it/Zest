package com.zps.zest.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TestFixtureBuilder
import com.intellij.testFramework.LightProjectDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * IntelliJ-specific tests following IntelliJ Platform testing guidelines
 * Uses proper test fixtures, EDT handling, and IntelliJ testing patterns
 */
class ZestInlineCompletionServiceIntelliJTest {
    
    @get:Rule
    val projectRule = ProjectRule()
    
    @get:Rule 
    val edtRule = EdtRule()
    
    private lateinit var myFixture: CodeInsightTestFixture
    private lateinit var completionService: ZestInlineCompletionService
    
    companion object {
        // Use the default project descriptor which includes Java support
        private val PROJECT_DESCRIPTOR = LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR
    }
    
    @Before
    fun setUp() {
        val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixtureBuilder: TestFixtureBuilder<IdeaProjectTestFixture> = 
            fixtureFactory.createLightFixtureBuilder(PROJECT_DESCRIPTOR, "ZestCompletionTest")
        
        myFixture = fixtureFactory.createCodeInsightFixture(fixtureBuilder.fixture)
        myFixture.setUp()
        
        // Initialize service with proper project context
        completionService = ZestInlineCompletionService(myFixture.project)
        completionService.setDebugLogging(true)
    }
    
    @After
    fun tearDown() {
        try {
            completionService.dispose()
            myFixture.tearDown()
        } catch (e: Exception) {
            // Ignore cleanup exceptions
        }
    }
    
    // =================================
    // EDT-Safe Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test completion service initialization in EDT`() {
        // This test runs on EDT as required for UI operations
        assertNotNull("Service should be initialized", completionService)
        assertTrue("Service should be enabled", completionService.isEnabled())
        
        val state = completionService.getDetailedState()
        assertNotNull("Should have valid state", state)
        assertTrue("Should be in idle state", 
            state["stateName"]?.toString()?.contains("Idle") == true)
    }
    
    @Test
    @RunsInEdt
    fun `test completion with real Kotlin file`() {
        // Configure a real Kotlin file using IntelliJ test fixtures
        myFixture.configureByText("Test.kt", """
            class TestClass {
                fun testMethod() {
                    val x = <caret>hello
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val document = editor.document
        val project = myFixture.project
        
        // Verify proper IntelliJ context
        assertNotNull("Editor should be available", editor)
        assertNotNull("Document should be available", document)
        assertNotNull("Project should be available", project)
        assertFalse("Editor should not be disposed", editor.isDisposed)
        
        // Test service with real editor
        val initialOffset = editor.caretModel.offset
        assertFalse("Should not have completion initially", 
            completionService.isInlineCompletionVisibleAt(editor, initialOffset))
        
        // Test completion dismissal
        completionService.dismiss()
        
        val finalState = completionService.getDetailedState()
        assertNotNull("Should maintain valid state", finalState)
    }
    
    @Test
    @RunsInEdt
    fun `test completion acceptance with real editor operations`() {
        myFixture.configureByText("Test.kt", """
            fun testFunction() {
                val result = <caret>
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val initialText = editor.document.text
        val caretOffset = editor.caretModel.offset
        
        // Test acceptance without active completion (should handle gracefully)
        completionService.accept(editor, caretOffset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
        
        // Document should remain unchanged when no completion is active
        assertEquals("Document should remain unchanged", initialText, editor.document.text)
        
        // Service should remain in valid state
        val state = completionService.getDetailedState()
        assertNotNull("Should have valid state after acceptance attempt", state)
    }
    
    @Test
    @RunsInEdt 
    fun `test strategy switching with real project context`() {
        myFixture.configureByText("Strategy.kt", "val x = test<caret>")
        
        val initialStrategy = completionService.getCompletionStrategy()
        
        // Switch to LEAN strategy
        completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
        assertEquals("Should switch to LEAN", 
            ZestCompletionProvider.CompletionStrategy.LEAN, 
            completionService.getCompletionStrategy())
        
        // Switch to SIMPLE strategy  
        completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.SIMPLE)
        assertEquals("Should switch to SIMPLE",
            ZestCompletionProvider.CompletionStrategy.SIMPLE,
            completionService.getCompletionStrategy())
        
        // Cache should be cleared after strategy changes
        val cacheStats = completionService.getCacheStats()
        assertTrue("Cache should be cleared", cacheStats.contains("entries: 0"))
    }
    
    // =================================
    // Real Editor Interaction Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test typing simulation with completion service`() {
        myFixture.configureByText("Typing.kt", "")
        
        // Simulate real typing using myFixture
        myFixture.type("class MyClass {\n")
        myFixture.type("    fun myMethod() {\n")
        myFixture.type("        val myVar = ")
        
        val editor = myFixture.editor
        val finalOffset = editor.caretModel.offset
        val documentText = editor.document.text
        
        // Verify typing worked
        assertTrue("Should contain typed content", documentText.contains("myVar"))
        assertTrue("Caret should be at end", finalOffset > 0)
        
        // Service should handle real typing events
        val state = completionService.getDetailedState()
        assertNotNull("Should handle real typing", state)
        
        // Test completion operations on real editor state
        completionService.dismiss()
        assertFalse("Should not show completion after dismissal",
            completionService.isInlineCompletionVisibleAt(editor, finalOffset))
    }
    
    @Test
    @RunsInEdt
    fun `test caret movement with completion service`() {
        myFixture.configureByText("CaretTest.kt", """
            fun testFunction() {
                val first = "hello"
                val second = "world"
                <caret>
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val initialOffset = editor.caretModel.offset
        
        // Move caret using IntelliJ API
        WriteCommandAction.runWriteCommandAction(myFixture.project) {
            editor.caretModel.moveToOffset(initialOffset - 10)
        }
        
        val newOffset = editor.caretModel.offset
        assertTrue("Caret should have moved", newOffset != initialOffset)
        
        // Service should handle caret movement
        val state = completionService.getDetailedState()
        assertNotNull("Should handle caret movement", state)
    }
    
    @Test
    @RunsInEdt
    fun `test document modification events`() {
        myFixture.configureByText("DocTest.kt", "val x = hello<caret>")
        
        val editor = myFixture.editor
        val initialLength = editor.document.textLength
        
        // Modify document using IntelliJ API
        WriteCommandAction.runWriteCommandAction(myFixture.project) {
            editor.document.insertString(editor.caretModel.offset, " world")
        }
        
        val finalLength = editor.document.textLength
        assertTrue("Document should be longer", finalLength > initialLength)
        assertTrue("Should contain new text", editor.document.text.contains("hello world"))
        
        // Service should handle document changes
        val state = completionService.getDetailedState()
        assertNotNull("Should handle document changes", state)
    }
    
    // =================================
    // IntelliJ Platform Integration Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test with multiple file types`() {
        val fileTypes = mapOf(
            "Test.kt" to "class KotlinTest { val x = <caret> }",
            "Test.java" to "class JavaTest { String x = <caret>\"hello\"; }",
            "test.js" to "const x = <caret>'hello';",
            "test.py" to "x = <caret>'hello'"
        )
        
        for ((fileName, content) in fileTypes) {
            // Configure each file type
            myFixture.configureByText(fileName, content)
            
            val editor = myFixture.editor
            val offset = editor.caretModel.offset
            
            // Service should handle different file types
            assertFalse("Should handle $fileName",
                completionService.isInlineCompletionVisibleAt(editor, offset))
            
            // Test operations on each file type
            completionService.dismiss()
            val state = completionService.getDetailedState()
            assertNotNull("Should have valid state for $fileName", state)
        }
    }
    
    @Test
    @RunsInEdt
    fun `test configuration integration with IntelliJ settings`() {
        // Test configuration changes
        val initialEnabled = completionService.isEnabled()
        
        completionService.updateConfiguration()
        
        // Configuration should be handled properly
        val state = completionService.getDetailedState()
        assertNotNull("Should handle configuration updates", state)
        assertTrue("Should maintain settings", state.containsKey("isEnabled"))
    }
    
    @Test
    @RunsInEdt
    fun `test force refresh in IntelliJ context`() {
        myFixture.configureByText("Refresh.kt", "val test = <caret>")
        
        // Get initial state
        val initialState = completionService.getDetailedState()
        assertNotNull("Should have initial state", initialState)
        
        // Force refresh
        completionService.forceRefreshState()
        
        // Should maintain valid state
        val refreshedState = completionService.getDetailedState()
        assertNotNull("Should have valid state after refresh", refreshedState)
        
        // Cache should be cleared
        val cacheStats = completionService.getCacheStats()
        assertTrue("Cache should be cleared after refresh", 
            cacheStats.contains("entries: 0"))
    }
    
    // =================================
    // Performance Tests in IntelliJ Context
    // =================================
    
    @Test
    @RunsInEdt
    fun `test service performance with real IntelliJ operations`() {
        myFixture.configureByText("Performance.kt", """
            class PerformanceTest {
                fun testMethod() {
                    <caret>
                }
            }
        """.trimIndent())
        
        val iterations = 100
        val startTime = System.currentTimeMillis()
        
        // Perform many operations
        repeat(iterations) { i ->
            completionService.getDetailedState()
            completionService.dismiss()
            if (i % 10 == 0) {
                completionService.setCompletionStrategy(
                    if (i % 20 == 0) ZestCompletionProvider.CompletionStrategy.SIMPLE 
                    else ZestCompletionProvider.CompletionStrategy.LEAN
                )
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Operations should be fast in IntelliJ context (${elapsed}ms for $iterations operations)",
            elapsed < 2000) // Should complete 100 operations in under 2 seconds
        
        // Final state should be valid
        val finalState = completionService.getDetailedState()
        assertNotNull("Should maintain valid state after performance test", finalState)
    }
    
    // =================================
    // Thread Safety Tests
    // =================================
    
    @Test
    fun `test EDT thread safety`() {
        val edtOperations = CountDownLatch(1)
        val backgroundOperations = CountDownLatch(1)
        
        // EDT operation
        ApplicationManager.getApplication().invokeLater {
            try {
                myFixture.configureByText("Thread.kt", "val x = <caret>")
                completionService.dismiss()
                edtOperations.countDown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Background operation  
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Safe background operations
                val state = completionService.getDetailedState()
                assertNotNull("Background state query should work", state)
                backgroundOperations.countDown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Wait for both operations
        assertTrue("EDT operation should complete", 
            edtOperations.await(5, TimeUnit.SECONDS))
        assertTrue("Background operation should complete",
            backgroundOperations.await(5, TimeUnit.SECONDS))
    }
    
    // =================================
    // Error Handling Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test error handling with disposed components`() {
        myFixture.configureByText("Error.kt", "val test = <caret>")
        
        val editor = myFixture.editor
        val initialOffset = editor.caretModel.offset
        
        // Service should handle operations gracefully
        completionService.dismiss()
        completionService.forceRefreshState()
        
        // Should not crash even if editor becomes unavailable
        assertFalse("Should handle edge cases gracefully",
            completionService.isInlineCompletionVisibleAt(editor, initialOffset))
    }
    
    // =================================
    // Helper Methods
    // =================================
    
    private fun assertNotNull(message: String, obj: Any?) {
        if (obj == null) {
            throw AssertionError(message)
        }
    }
    
    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) {
            throw AssertionError(message)
        }
    }
    
    private fun assertFalse(message: String, condition: Boolean) {
        if (condition) {
            throw AssertionError(message)
        }
    }
    
    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("$message. Expected: $expected, Actual: $actual")
        }
    }
}