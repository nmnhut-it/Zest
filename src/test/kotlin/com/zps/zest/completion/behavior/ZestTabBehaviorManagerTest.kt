package com.zps.zest.completion.behavior

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.zps.zest.completion.data.ZestInlineCompletionItem
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZestTabBehaviorManagerTest : LightPlatformCodeInsightFixture4TestCase() {

    private lateinit var tabBehaviorManager: ZestTabBehaviorManager

    override fun setUp() {
        super.setUp()
        tabBehaviorManager = project.service<ZestTabBehaviorManager>()
    }

    @Test
    fun `test should reject completion with indentation at line start`() {
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "    int x = 5;",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertFalse(shouldAccept, "Should not accept indentation completion at line start")
    }

    @Test
    fun `test should accept code completion with existing content`() {
        myFixture.configureByText("test.java", """
            public class Test {
                int x = <caret>
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "getValue()",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertTrue(shouldAccept, "Should accept code completion with existing content")
    }

    @Test
    fun `test should reject whitespace-only completion in indentation context`() {
        myFixture.configureByText("test.py", """
            def example():
            <caret>
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "    ",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertFalse(shouldAccept, "Should not accept whitespace completion in indentation context")
    }

    @Test
    fun `test should accept function call completion`() {
        myFixture.configureByText("test.kt", """
            fun main() {
                val result = <caret>
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "calculateValue()",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertTrue(shouldAccept, "Should accept function call completion")
    }

    @Test
    fun `test should handle mixed indentation properly`() {
        myFixture.configureByText("test.js", """
            function example() {
                    if (true) {
            <caret>
                    }
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "        console.log('hello');",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        // Should reject because it starts with indentation at line start
        assertFalse(shouldAccept, "Should handle mixed indentation by rejecting at line start")
    }

    @Test
    fun `test should accept completion with structural characters`() {
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    System.out.println(<caret>
                }
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "\"Hello, World!\");",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertTrue(shouldAccept, "Should accept completion with structural characters")
    }

    @Test
    fun `test indentation analysis for different languages`() {
        // Test Java indentation
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        assertTrue(
            tabBehaviorManager.isIndentationExpected(myFixture.editor, myFixture.caretOffset),
            "Should expect indentation in Java class body"
        )
        
        // Test Python indentation
        myFixture.configureByText("test.py", """
            def function():
            <caret>
        """.trimIndent())
        
        assertTrue(
            tabBehaviorManager.isIndentationExpected(myFixture.editor, myFixture.caretOffset),
            "Should expect indentation after Python function definition"
        )
    }

    @Test
    fun `test indentation pattern analysis`() {
        myFixture.configureByText("test.java", """
            public class Test {
                int a = 1;
                int b = 2;
            <caret>
            }
        """.trimIndent())
        
        val pattern = tabBehaviorManager.analyzeIndentationPattern(myFixture.editor)
        
        // Should detect space-based indentation (IntelliJ default for Java)
        assertTrue(pattern.detectedPattern.confidence > 0.5, "Should have reasonable confidence in pattern detection")
    }

    @Test
    fun `test should not accept indentation completion mid-line`() {
        myFixture.configureByText("test.java", """
            public class Test {
                int x =<caret> 5;
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "    ",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        // Even though not at line start, whitespace completion should be rejected
        assertFalse(shouldAccept, "Should not accept whitespace completion mid-line")
    }

    @Test
    fun `test should accept multi-line code completion`() {
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    <caret>
                }
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = """if (condition) {
                doSomething();
            }""",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        assertTrue(shouldAccept, "Should accept multi-line code completion")
    }

    @Test
    fun `test edge case with empty line in indented block`() {
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
                    int x = 5;
                    
            <caret>
                    int y = 10;
                }
            }
        """.trimIndent())
        
        val completion = ZestInlineCompletionItem(
            insertText = "        System.out.println(x);",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val shouldAccept = tabBehaviorManager.shouldAcceptCompletionOnTab(
            myFixture.editor,
            myFixture.caretOffset,
            completion
        )
        
        // Should reject because it starts with indentation at line start
        assertFalse(shouldAccept, "Should reject indentation completion on empty line in block")
    }
}
