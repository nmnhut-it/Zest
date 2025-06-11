package com.zps.zest.completion.behavior

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.zps.zest.completion.data.ZestInlineCompletionItem
import org.junit.Test
import kotlin.test.*

class CompletionContextAnalyzerTest : LightPlatformCodeInsightFixture4TestCase() {

    @Test
    fun `test analyzeCompletion for different content types`() {
        // Test whitespace-only completion
        val whitespaceCompletion = ZestInlineCompletionItem(
            insertText = "    ",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val whitespaceAnalysis = CompletionContextAnalyzer.analyzeCompletion(whitespaceCompletion)
        assertTrue(whitespaceAnalysis.isWhitespaceOnly, "Should detect whitespace-only completion")
        assertTrue(whitespaceAnalysis.isIndentationLike, "Should detect indentation-like completion")
        assertEquals(CompletionContextAnalyzer.ContentType.WHITESPACE, whitespaceAnalysis.contentType)
        
        // Test code completion
        val codeCompletion = ZestInlineCompletionItem(
            insertText = "getValue()",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val codeAnalysis = CompletionContextAnalyzer.analyzeCompletion(codeCompletion)
        assertFalse(codeAnalysis.isWhitespaceOnly, "Should not detect code as whitespace-only")
        assertTrue(codeAnalysis.isCodeLike, "Should detect code-like completion")
        assertTrue(codeAnalysis.hasStructuralCharacters, "Should detect structural characters")
        assertEquals(CompletionContextAnalyzer.ContentType.CODE, codeAnalysis.contentType)
        
        // Test indentation with code
        val indentedCodeCompletion = ZestInlineCompletionItem(
            insertText = "    return value;",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val indentedAnalysis = CompletionContextAnalyzer.analyzeCompletion(indentedCodeCompletion)
        assertTrue(indentedAnalysis.startsWithWhitespace, "Should detect leading whitespace")
        assertTrue(indentedAnalysis.isCodeLike, "Should still detect as code-like")
        assertEquals("    ", indentedAnalysis.leadingWhitespace)
        assertEquals("return value;", indentedAnalysis.codeContent)
    }

    @Test
    fun `test analyzeEditingContext for different positions`() {
        // Test at line start
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        val lineStartContext = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        val lineStartAnalysis = CompletionContextAnalyzer.analyzeEditingContext(lineStartContext)
        
        assertTrue(lineStartAnalysis.isAtLineStart, "Should detect line start position")
        assertTrue(lineStartAnalysis.isInIndentationPosition, "Should be in indentation position")
        assertEquals(CompletionContextAnalyzer.EditingMode.LINE_START, lineStartAnalysis.editingMode)
        
        // Test in coding context
        myFixture.configureByText("test.java", """
            public class Test {
                int x = <caret>
            }
        """.trimIndent())
        
        val codingContext = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        val codingAnalysis = CompletionContextAnalyzer.analyzeEditingContext(codingContext)
        
        assertFalse(codingAnalysis.isAtLineStart, "Should not be at line start")
        assertTrue(codingAnalysis.hasContentOnLine, "Should have content on line")
        assertEquals(CompletionContextAnalyzer.EditingMode.CODING, codingAnalysis.editingMode)
    }

    @Test
    fun `test shouldAcceptOnTab decision making`() {
        // Test high-confidence rejection: indentation in indentation context
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        val indentationCompletion = ZestInlineCompletionItem(
            insertText = "    int x = 5;",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val decision1 = CompletionContextAnalyzer.shouldAcceptOnTab(
            myFixture.editor, myFixture.caretOffset, indentationCompletion, project
        )
        
        assertFalse(decision1.shouldAcceptCompletion, "Should reject indentation in indentation context")
        assertTrue(decision1.confidence >= 0.8, "Should have high confidence")
        assertTrue(decision1.fallbackToIndentation, "Should fallback to indentation")
        
        // Test high-confidence acceptance: code in coding context
        myFixture.configureByText("test.java", """
            public class Test {
                int result = <caret>
            }
        """.trimIndent())
        
        val codeCompletion = ZestInlineCompletionItem(
            insertText = "calculateValue()",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val decision2 = CompletionContextAnalyzer.shouldAcceptOnTab(
            myFixture.editor, myFixture.caretOffset, codeCompletion, project
        )
        
        assertTrue(decision2.shouldAcceptCompletion, "Should accept code in coding context")
        assertTrue(decision2.confidence >= 0.8, "Should have high confidence")
        assertFalse(decision2.fallbackToIndentation, "Should not fallback to indentation")
    }

    @Test
    fun `test content type detection`() {
        // Test various content types
        val testCases = listOf(
            "    " to CompletionContextAnalyzer.ContentType.WHITESPACE,
            "    int x;" to CompletionContextAnalyzer.ContentType.INDENTATION,
            "getValue()" to CompletionContextAnalyzer.ContentType.CODE,
            "myVariable" to CompletionContextAnalyzer.ContentType.IDENTIFIER,
            "line1\nline2" to CompletionContextAnalyzer.ContentType.MULTILINE,
        )
        
        testCases.forEach { (text, expectedType) ->
            val completion = ZestInlineCompletionItem(
                insertText = text,
                replaceRange = ZestInlineCompletionItem.Range(0, 0)
            )
            
            val analysis = CompletionContextAnalyzer.analyzeCompletion(completion)
            assertEquals(expectedType, analysis.contentType, "Content type should match for: '$text'")
        }
    }

    @Test
    fun `test editing mode detection`() {
        // Test line start mode
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        var context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        var analysis = CompletionContextAnalyzer.analyzeEditingContext(context)
        assertEquals(CompletionContextAnalyzer.EditingMode.LINE_START, analysis.editingMode)
        
        // Test indenting mode
        myFixture.configureByText("test.java", """
            public class Test {
                <caret>
            }
        """.trimIndent())
        
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        analysis = CompletionContextAnalyzer.analyzeEditingContext(context)
        assertEquals(CompletionContextAnalyzer.EditingMode.INDENTING, analysis.editingMode)
        
        // Test coding mode
        myFixture.configureByText("test.java", """
            public class Test {
                int x = <caret>
            }
        """.trimIndent())
        
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        analysis = CompletionContextAnalyzer.analyzeEditingContext(context)
        assertEquals(CompletionContextAnalyzer.EditingMode.CODING, analysis.editingMode)
    }

    @Test
    fun `test context type detection for different languages`() {
        val languageTests = listOf(
            "test.java" to CompletionContextAnalyzer.ContextType.JVM_LANGUAGE,
            "test.kt" to CompletionContextAnalyzer.ContextType.JVM_LANGUAGE,
            "test.py" to CompletionContextAnalyzer.ContextType.PYTHON,
            "test.js" to CompletionContextAnalyzer.ContextType.JAVASCRIPT,
            "test.ts" to CompletionContextAnalyzer.ContextType.JAVASCRIPT,
            "test.go" to CompletionContextAnalyzer.ContextType.GO,
            "test.rs" to CompletionContextAnalyzer.ContextType.RUST,
            "test.c" to CompletionContextAnalyzer.ContextType.C_FAMILY,
            "test.cpp" to CompletionContextAnalyzer.ContextType.C_FAMILY,
            "test.html" to CompletionContextAnalyzer.ContextType.MARKUP,
            "test.json" to CompletionContextAnalyzer.ContextType.DATA_FORMAT,
            "test.unknown" to CompletionContextAnalyzer.ContextType.GENERIC,
        )
        
        languageTests.forEach { (filename, expectedType) ->
            myFixture.configureByText(filename, "content<caret>")
            
            val context = IndentationAnalyzer.analyzeIndentationContext(
                myFixture.editor, myFixture.caretOffset, project
            )
            val analysis = CompletionContextAnalyzer.analyzeEditingContext(context)
            
            assertEquals(expectedType, analysis.contextType, "Context type should match for: $filename")
        }
    }

    @Test
    fun `test code-like content detection`() {
        val codeCompletion = ZestInlineCompletionItem(
            insertText = "myFunction(param1, param2)",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val analysis = CompletionContextAnalyzer.analyzeCompletion(codeCompletion)
        
        assertTrue(analysis.isCodeLike, "Should detect function call as code-like")
        assertTrue(analysis.hasStructuralCharacters, "Should detect structural characters")
        assertFalse(analysis.isIndentationLike, "Should not be indentation-like")
        
        val keywordCompletion = ZestInlineCompletionItem(
            insertText = "if (condition) {",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val keywordAnalysis = CompletionContextAnalyzer.analyzeCompletion(keywordCompletion)
        assertTrue(keywordAnalysis.isCodeLike, "Should detect keyword as code-like")
    }

    @Test
    fun `test multiline completion analysis`() {
        val multilineCompletion = ZestInlineCompletionItem(
            insertText = """if (condition) {
                doSomething();
            }""",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val analysis = CompletionContextAnalyzer.analyzeCompletion(multilineCompletion)
        
        assertTrue(analysis.containsNewlines, "Should detect newlines")
        assertTrue(analysis.isCodeLike, "Should detect as code-like")
        assertEquals(CompletionContextAnalyzer.ContentType.MULTILINE, analysis.contentType)
    }

    @Test
    fun `test edge cases in decision making`() {
        // Test empty completion
        myFixture.configureByText("test.java", "public class Test {<caret>}")
        
        val emptyCompletion = ZestInlineCompletionItem(
            insertText = "",
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val emptyDecision = CompletionContextAnalyzer.shouldAcceptOnTab(
            myFixture.editor, myFixture.caretOffset, emptyCompletion, project
        )
        
        // Empty completion should generally be rejected
        assertFalse(emptyDecision.shouldAcceptCompletion, "Should reject empty completion")
        
        // Test very long completion
        val longCompletion = ZestInlineCompletionItem(
            insertText = "verylongfunctionnamethatgoesonnandononand".repeat(10),
            replaceRange = ZestInlineCompletionItem.Range(0, 0)
        )
        
        val longDecision = CompletionContextAnalyzer.shouldAcceptOnTab(
            myFixture.editor, myFixture.caretOffset, longCompletion, project
        )
        
        // Long code completion should still be accepted if in right context
        assertTrue(longDecision.shouldAcceptCompletion, "Should accept long code completion")
    }
}
