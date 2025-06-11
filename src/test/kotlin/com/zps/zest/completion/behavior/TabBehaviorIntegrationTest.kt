package com.zps.zest.completion.behavior

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.actions.ZestTabAccept
import com.zps.zest.completion.data.ZestInlineCompletionItem
import io.mockk.*
import org.junit.Test
import kotlin.test.*

class TabBehaviorIntegrationTest : LightPlatformCodeInsightFixture4TestCase() {

    private lateinit var completionService: ZestInlineCompletionService
    private lateinit var tabBehaviorManager: ZestTabBehaviorManager
    private lateinit var tabAcceptAction: ZestTabAccept

    override fun setUp() {
        super.setUp()
        completionService = project.service<ZestInlineCompletionService>()
        tabBehaviorManager = project.service<ZestTabBehaviorManager>()
        tabAcceptAction = ZestTabAccept()
    }

    @Test
    fun `test full tab behavior flow - should accept code completion`() {
        // Setup: Java class with incomplete statement
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    int result = <caret>
                }
            }
        """.trimIndent())
        
        // Mock a code completion
        val codeCompletion = ZestInlineCompletionItem(
            insertText = "getValue()",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        // Test that tab behavior manager would accept this
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            codeCompletion
        )
        
        assertTrue(shouldAccept, "Should accept code completion in coding context")
        
        // Test that the action would be enabled if completion was visible
        // Note: We can't easily mock the completion service visibility in a unit test,
        // but we can test the logic components
        assertNotNull(tabAcceptAction, "Tab accept action should be available")
    }

    @Test
    fun `test full tab behavior flow - should reject indentation completion`() {
        // Setup: Java class at line start
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        // Mock an indentation completion
        val indentationCompletion = ZestInlineCompletionItem(
            insertText = "    public void method() {",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        // Test that tab behavior manager would reject this
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            indentationCompletion
        )
        
        assertFalse(shouldAccept, "Should reject indentation completion at line start")
    }

    @Test
    fun `test tab behavior with Python indentation context`() {
        // Setup: Python function definition
        myFixture.configureByText("test.py", """
            def example_function():
            <caret>
        """.trimIndent())
        
        // Test indentation detection
        assertTrue(
            tabBehaviorManager.isIndentationExpected(myFixture.editor, myFixture.caretOffset),
            "Should expect indentation after Python function definition"
        )
        
        // Test with whitespace completion
        val whitespaceCompletion = ZestInlineCompletionItem(
            insertText = "    ",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAcceptWhitespace = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            whitespaceCompletion
        )
        
        assertFalse(shouldAcceptWhitespace, "Should not accept whitespace completion in indentation context")
        
        // Test with code completion
        val codeCompletion = ZestInlineCompletionItem(
            insertText = "    return value",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAcceptCode = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            codeCompletion
        )
        
        // Should reject because it starts with indentation at line start
        assertFalse(shouldAcceptCode, "Should reject code completion that starts with indentation at line start")
    }

    @Test
    fun `test tab behavior with JavaScript context`() {
        // Setup: JavaScript function
        myFixture.configureByText("test.js", """
            function example() {
                const value = calculateSomething(<caret>);
            }
        """.trimIndent())
        
        // Test with parameter completion
        val paramCompletion = ZestInlineCompletionItem(
            insertText = "param1, param2",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            paramCompletion
        )
        
        assertTrue(shouldAccept, "Should accept parameter completion in function call")
    }

    @Test
    fun `test edge case - empty line in indented block`() {
        // Setup: Empty line in indented block
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    int x = 5;
                    
            <caret>
                    int y = 10;
                }
            }
        """.trimIndent())
        
        // Test indentation analysis
        assertTrue(
            tabBehaviorManager.isIndentationExpected(myFixture.editor, myFixture.caretOffset),
            "Should expect indentation on empty line in block"
        )
        
        // Test with indented code completion
        val indentedCompletion = ZestInlineCompletionItem(
            insertText = "        System.out.println(\"debug\");",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            indentedCompletion
        )
        
        assertFalse(shouldAccept, "Should not accept indented completion on empty line")
    }

    @Test
    fun `test mixed indentation detection and handling`() {
        // Setup: File with mixed indentation (this is intentionally bad style)
        myFixture.configureByText("test.java", """
            public class Test {
            	    public void method() {  // Tab + spaces
                        int x = 5;<caret>
            	    }
            }
        """.trimIndent())
        
        // Analyze indentation pattern
        val pattern = tabBehaviorManager.analyzeIndentationPattern(myFixture.editor)
        
        // Should detect mixed indentation
        assertTrue(pattern.hasMixedIndentation || pattern.detectedPattern.hasMixedIndentation, 
                  "Should detect mixed indentation")
        
        // Test completion in this context
        val completion = ZestInlineCompletionItem(
            insertText = " // comment",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertTrue(shouldAccept, "Should accept code completion even with mixed indentation")
    }

    @Test
    fun `test multi-line completion handling`() {
        // Setup: Method body
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    <caret>
                }
            }
        """.trimIndent())
        
        // Test multi-line completion
        val multilineCompletion = ZestInlineCompletionItem(
            insertText = """if (condition) {
                doSomething();
            }""",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            multilineCompletion
        )
        
        assertTrue(shouldAccept, "Should accept multi-line code completion")
    }

    @Test
    fun `test action enablement with mock completion service`() {
        // Create a mock completion service for testing action enablement
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        // Mock that completion is visible
        every { mockService.isInlineCompletionVisibleAt(any(), any()) } returns true
        
        // Mock current completion
        val testCompletion = ZestInlineCompletionItem(
            insertText = "testValue",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        every { mockService.getCurrentCompletion() } returns testCompletion
        
        // Note: Full action testing would require more complex mocking of the service injection
        // This test validates that the action has the expected structure
        assertNotNull(tabAcceptAction, "Tab accept action should exist")
        assertEquals(11, tabAcceptAction.priority, "Tab accept should have priority 11")
    }

    @Test
    fun `test performance with large file context`() {
        // Setup: Large file to test performance
        val largeContent = buildString {
            appendLine("public class LargeTest {")
            repeat(1000) { i ->
                appendLine("    public void method$i() {")
                appendLine("        int value$i = $i;")
                appendLine("    }")
            }
            append("    <caret>")
            appendLine("}")
        }
        
        myFixture.configureByText("LargeTest.java", largeContent)
        
        // Test that analysis is still fast with large context
        val startTime = System.currentTimeMillis()
        
        val completion = ZestInlineCompletionItem(
            insertText = "performCalculation()",
            replaceRange = ZestInlineCompletionItem.Range(myFixture.caretOffset, myFixture.caretOffset)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertTrue(shouldAccept, "Should accept code completion even in large file")
        assertTrue(duration < 100, "Analysis should complete quickly (was ${duration}ms)")
    }
}
