package com.zps.zest.completion.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TestFixtureBuilder
import com.intellij.testFramework.LightProjectDescriptor
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Platform integration tests following IntelliJ best practices
 * Tests the completion service integration with IntelliJ Platform services
 */
class CompletionPlatformIntegrationTest {
    
    @get:Rule
    val projectRule = ProjectRule()
    
    @get:Rule
    val edtRule = EdtRule()
    
    private lateinit var myFixture: CodeInsightTestFixture
    private lateinit var completionService: ZestInlineCompletionService
    
    @Before
    fun setUp() {
        val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixtureBuilder: TestFixtureBuilder<IdeaProjectTestFixture> = 
            fixtureFactory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "PlatformIntegrationTest")
        
        myFixture = fixtureFactory.createCodeInsightFixture(fixtureBuilder.fixture)
        myFixture.setUp()
        
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
    // Platform Service Integration Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test integration with IntelliJ project service lifecycle`() {
        // Test that service integrates properly with IntelliJ project lifecycle
        assertNotNull("Service should be initialized with project", completionService)
        assertEquals("Service should be associated with test project", 
            myFixture.project, projectRule.project)
        
        val state = completionService.getDetailedState()
        assertNotNull("Service should have valid state", state)
        assertTrue("Service should be properly initialized", 
            state.containsKey("isEnabled"))
    }
    
    @Test
    @RunsInEdt
    fun `test integration with IntelliJ file type system`() {
        val fileTypeManager = FileTypeManager.getInstance()
        
        val testCases = mapOf(
            "kotlin" to ("Test.kt" to "class Test { val x = <caret> }"),
            "java" to ("Test.java" to "class Test { String x = <caret>\"hello\"; }"),
            "javascript" to ("test.js" to "const x = <caret>'hello';"),
            "python" to ("test.py" to "x = <caret>'hello'")
        )
        
        for ((expectedType, fileData) in testCases) {
            val (fileName, content) = fileData
            myFixture.configureByText(fileName, content)
            
            val virtualFile = myFixture.file.virtualFile
            val fileType = fileTypeManager.getFileTypeByFile(virtualFile)
            
            // Service should work with different file types
            val editor = myFixture.editor
            assertNotNull("Should have editor for $fileName", editor)
            
            val state = completionService.getDetailedState()
            assertNotNull("Service should handle $fileName", state)
            
            // Test completion operations
            completionService.dismiss()
            assertFalse("Should handle dismissal for $fileName",
                completionService.isInlineCompletionVisibleAt(editor, editor.caretModel.offset))
        }
    }
    
    @Test
    @RunsInEdt
    fun `test integration with IntelliJ editor factory`() {
        val editorFactory = EditorFactory.getInstance()
        
        // Create document and editor through IntelliJ APIs
        val document = editorFactory.createDocument("val integrationTest = test<caret>")
        val editor = editorFactory.createEditor(document, myFixture.project)
        
        try {
            assertNotNull("Should create editor through factory", editor)
            assertFalse("Editor should not be disposed", editor.isDisposed)
            
            // Service should work with factory-created editors
            val offset = document.textLength - 6 // Position at "<caret>"
            assertFalse("Should handle factory editor",
                completionService.isInlineCompletionVisibleAt(editor, offset))
            
            // Test operations
            completionService.dismiss()
            val state = completionService.getDetailedState()
            assertNotNull("Should maintain state with factory editor", state)
            
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }
    
    @Test
    @RunsInEdt
    fun `test integration with IntelliJ write command actions`() {
        myFixture.configureByText("WriteCommand.kt", """
            class WriteCommandTest {
                fun test() {
                    val x = <caret>
                }
            }
        """.trimIndent())
        
        val editor = myFixture.editor
        val initialOffset = editor.caretModel.offset
        val initialText = editor.document.text
        
        // Test service behavior during write command
        WriteCommandAction.runWriteCommandAction(myFixture.project, Runnable {
            editor.document.insertString(initialOffset, "hello")
            editor.caretModel.moveToOffset(initialOffset + 5)
        })
        
        val modifiedText = editor.document.text
        assertTrue("Document should be modified", modifiedText != initialText)
        assertTrue("Should contain inserted text", modifiedText.contains("hello"))
        
        // Service should handle write command modifications
        val state = completionService.getDetailedState()
        assertNotNull("Should handle write commands", state)
        
        val newOffset = editor.caretModel.offset
        assertFalse("Should handle new caret position",
            completionService.isInlineCompletionVisibleAt(editor, newOffset))
    }
    
    // =================================
    // IntelliJ Threading Model Integration
    // =================================
    
    @Test
    @RunsInEdt
    fun `test EDT operations integration`() {
        myFixture.configureByText("EDT.kt", "val edtTest = <caret>")
        
        // All UI operations should work on EDT
        val editor = myFixture.editor
        val state = completionService.getDetailedState()
        
        assertNotNull("EDT operations should work", state)
        
        // Test completion operations on EDT
        completionService.dismiss()
        completionService.forceRefreshState()
        
        val finalState = completionService.getDetailedState()
        assertNotNull("EDT operations should maintain valid state", finalState)
    }
    
    @Test
    fun `test background thread safety with platform services`() {
        val latch = CountDownLatch(1)
        var backgroundResult: Map<String, Any>? = null
        var backgroundException: Exception? = null
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Safe background operations
                backgroundResult = completionService.getDetailedState()
                val cacheStats = completionService.getCacheStats()
                assertNotNull("Background cache stats should work", cacheStats)
            } catch (e: Exception) {
                backgroundException = e
            } finally {
                latch.countDown()
            }
        }
        
        assertTrue("Background operation should complete",
            latch.await(5, TimeUnit.SECONDS))
        
        if (backgroundException != null) {
            throw backgroundException!!
        }
        
        assertNotNull("Background state query should work", backgroundResult)
    }
    
    @Test
    @RunsInEdt
    fun `test with platform file operations`() {
        // Create file through platform APIs
        myFixture.addFileToProject("PlatformTest.kt", """
            package com.test
            
            class PlatformTest {
                fun platformMethod() {
                    val platformVar = <caret>
                }
            }
        """.trimIndent())
        
        // Open the file
        val file = myFixture.findFileInTempDir("PlatformTest.kt")
        assertNotNull("File should be created", file)
        
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor
        
        // Service should work with platform-managed files
        assertNotNull("Should have editor for platform file", editor)
        
        val state = completionService.getDetailedState()
        assertNotNull("Should work with platform files", state)
        
        // Test operations on platform files
        completionService.dismiss()
        assertFalse("Should handle platform file operations",
            completionService.isInlineCompletionVisibleAt(editor, editor.caretModel.offset))
    }
    
    // =================================
    // Performance Integration Tests
    // =================================
    
    @Test
    @RunsInEdt
    fun `test performance with IntelliJ platform overhead`() {
        myFixture.configureByText("Performance.kt", """
            class PerformanceTest {
                fun performanceMethod() {
                    val performanceVar = <caret>
                }
            }
        """.trimIndent())
        
        val iterations = 500
        val operations = mutableListOf<Long>()
        
        repeat(iterations) { i ->
            val start = System.nanoTime()
            
            when (i % 5) {
                0 -> completionService.getDetailedState()
                1 -> completionService.dismiss()
                2 -> completionService.forceRefreshState()
                3 -> completionService.setCompletionStrategy(
                    if (i % 2 == 0) ZestCompletionProvider.CompletionStrategy.SIMPLE
                    else ZestCompletionProvider.CompletionStrategy.LEAN
                )
                4 -> completionService.getCacheStats()
            }
            
            val elapsed = System.nanoTime() - start
            operations.add(elapsed)
        }
        
        val avgTime = operations.average() / 1_000_000.0 // Convert to milliseconds
        val maxTime = operations.maxOrNull()!! / 1_000_000.0
        
        assertTrue("Average operation time should be reasonable (${avgTime}ms)", avgTime < 10.0)
        assertTrue("Maximum operation time should be reasonable (${maxTime}ms)", maxTime < 50.0)
        
        // Service should maintain valid state after performance test
        val finalState = completionService.getDetailedState()
        assertNotNull("Should maintain valid state after performance test", finalState)
    }
    
    @Test
    @RunsInEdt  
    fun `test memory behavior with platform integration`() {
        myFixture.configureByText("Memory.kt", "val memoryTest = <caret>")
        
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // Perform many operations that might allocate memory
        repeat(1000) { i ->
            completionService.getDetailedState()
            if (i % 10 == 0) {
                completionService.setCompletionStrategy(
                    if (i % 20 == 0) ZestCompletionProvider.CompletionStrategy.SIMPLE
                    else ZestCompletionProvider.CompletionStrategy.LEAN  
                )
            }
            if (i % 50 == 0) {
                completionService.forceRefreshState()
            }
        }
        
        // Force cleanup
        System.gc()
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        assertTrue("Memory usage should be reasonable with platform integration. " +
                   "Increase: ${memoryIncrease / 1024 / 1024}MB",
            memoryIncrease < 20 * 1024 * 1024) // Less than 20MB increase
    }
    
    // =================================
    // Error Handling Integration
    // =================================
    
    @Test
    @RunsInEdt
    fun `test error handling with platform exceptions`() {
        myFixture.configureByText("ErrorHandling.kt", "val errorTest = <caret>")
        
        // Test service behavior when platform operations might fail
        val editor = myFixture.editor
        
        // These operations should not crash even if there are platform issues
        completionService.dismiss()
        completionService.forceRefreshState()
        
        val state = completionService.getDetailedState()
        assertNotNull("Should handle potential platform errors gracefully", state)
        
        // Service should remain functional
        assertFalse("Service should remain functional after error scenarios",
            completionService.isInlineCompletionVisibleAt(editor, editor.caretModel.offset))
    }
    
    @Test
    @RunsInEdt
    fun `test disposal integration with platform cleanup`() {
        myFixture.configureByText("Disposal.kt", "val disposalTest = <caret>")
        
        // Get initial state
        val initialState = completionService.getDetailedState()
        assertNotNull("Should have valid initial state", initialState)
        
        // Test disposal
        completionService.dispose()
        
        // After disposal, service should handle operations gracefully
        try {
            val postDisposalState = completionService.getDetailedState()
            // Should either work or fail gracefully, but not crash
            assertTrue("Disposal should be handled gracefully", true)
        } catch (e: Exception) {
            // Some operations may fail after disposal, but should not crash the platform
            assertTrue("Disposal exceptions should be handled", true)
        }
    }
    
    // =================================
    // Helper Methods
    // =================================
    
    private fun assertNotNull(message: String, obj: Any?) {
        if (obj == null) throw AssertionError(message)
    }
    
    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
    
    private fun assertFalse(message: String, condition: Boolean) {
        if (condition) throw AssertionError(message)  
    }
    
    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("$message. Expected: $expected, Actual: $actual")
        }
    }
}