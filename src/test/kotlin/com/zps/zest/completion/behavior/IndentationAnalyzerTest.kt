package com.zps.zest.completion.behavior

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Test
import kotlin.test.*

class IndentationAnalyzerTest : LightPlatformCodeInsightFixture4TestCase() {

    @Test
    fun `test analyzeIndentationContext at line start`() {
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        val context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor,
            myFixture.caretOffset,
            project
        )
        
        assertTrue(context.isAtLineStart, "Should be at line start")
        assertFalse(context.hasNonWhitespaceContent, "Should not have non-whitespace content")
        assertTrue(context.isInWhitespaceOnly, "Should be in whitespace only")
    }

    @Test
    fun `test analyzeIndentationContext with existing content`() {
        myFixture.configureByText("test.java", """
            public class Test {
                int x = <caret>5;
            }
        """.trimIndent())
        
        val context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor,
            myFixture.caretOffset,
            project
        )
        
        assertFalse(context.isAtLineStart, "Should not be at line start")
        assertTrue(context.hasNonWhitespaceContent, "Should have non-whitespace content")
        assertFalse(context.isInWhitespaceOnly, "Should not be in whitespace only")
        assertEquals("    int x = ", context.linePrefix)
    }

    @Test
    fun `test isInIndentationPosition for various scenarios`() {
        // Test at line start
        myFixture.configureByText("test.java", """
            public class Test {
            <caret>
            }
        """.trimIndent())
        
        var context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        assertTrue(
            IndentationAnalyzer.isInIndentationPosition(context),
            "Should be in indentation position at line start"
        )
        
        // Test in whitespace
        myFixture.configureByText("test.java", """
            public class Test {
                <caret>
            }
        """.trimIndent())
        
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        assertTrue(
            IndentationAnalyzer.isInIndentationPosition(context),
            "Should be in indentation position in whitespace"
        )
        
        // Test after code content
        myFixture.configureByText("test.java", """
            public class Test {
                int x = <caret>
            }
        """.trimIndent())
        
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        assertFalse(
            IndentationAnalyzer.isInIndentationPosition(context),
            "Should not be in indentation position after code content"
        )
    }

    @Test
    fun `test calculateExpectedIndentationLevel`() {
        myFixture.configureByText("test.java", """
            public class Test {
                public void method() {
            <caret>
                }
            }
        """.trimIndent())
        
        val context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        
        val expectedLevel = IndentationAnalyzer.calculateExpectedIndentationLevel(context)
        
        // Should expect increased indentation after opening brace
        assertTrue(expectedLevel > 0, "Should expect some indentation in method body")
    }

    @Test
    fun `test calculateIndentationLevel with different whitespace`() {
        val settings = IndentationAnalyzer.IndentationSettings(
            useTabCharacter = false,
            indentSize = 4,
            tabSize = 8,
            continuationIndentSize = 8
        )
        
        // Test space indentation
        val spaceLevel = IndentationAnalyzer.calculateIndentationLevel("    code", settings)
        assertEquals(4, spaceLevel, "Should calculate 4 spaces as level 4")
        
        // Test tab indentation
        val tabSettings = settings.copy(useTabCharacter = true)
        val tabLevel = IndentationAnalyzer.calculateIndentationLevel("\tcode", tabSettings)
        assertEquals(8, tabLevel, "Should calculate 1 tab as level 8")
        
        // Test mixed indentation
        val mixedLevel = IndentationAnalyzer.calculateIndentationLevel("  \t  code", settings)
        assertEquals(10, mixedLevel, "Should calculate mixed indentation correctly")
    }

    @Test
    fun `test getIndentationSettings for different languages`() {
        // Test Java settings
        myFixture.configureByText("test.java", "public class Test {<caret>}")
        var context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        var settings = IndentationAnalyzer.getIndentationSettings(context)
        
        assertNotNull(settings, "Should get settings for Java")
        assertFalse(settings.useTabCharacter, "Java typically uses spaces")
        
        // Test Python settings
        myFixture.configureByText("test.py", "def function():<caret>")
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        settings = IndentationAnalyzer.getIndentationSettings(context)
        
        assertNotNull(settings, "Should get settings for Python")
        
        // Test unknown file type
        myFixture.configureByText("test.unknown", "some content<caret>")
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        settings = IndentationAnalyzer.getIndentationSettings(context)
        
        assertNotNull(settings, "Should get default settings for unknown type")
    }

    @Test
    fun `test indentation context properties`() {
        myFixture.configureByText("test.java", """
            public class Test {
                int value = 42;<caret>
            }
        """.trimIndent())
        
        val context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        
        assertEquals(1, context.lineNumber, "Should be on line 1 (0-indexed)")
        assertFalse(context.isAtLineStart, "Should not be at line start")
        assertTrue(context.hasNonWhitespaceContent, "Should have non-whitespace content")
        assertEquals("    int value = 42", context.linePrefix)
        assertEquals(";", context.lineSuffix)
        assertEquals("    ", context.whitespacePrefix)
        assertFalse(context.isInWhitespaceOnly, "Should not be in whitespace only")
        assertEquals("java", context.language?.id?.lowercase())
    }

    @Test
    fun `test edge cases with empty lines and whitespace`() {
        myFixture.configureByText("test.java", """
            public class Test {
                
            <caret>    
                
            }
        """.trimIndent())
        
        val context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        
        assertTrue(context.isAtLineStart, "Should be at line start on empty line")
        assertFalse(context.hasNonWhitespaceContent, "Empty line should not have content")
        assertTrue(context.isInWhitespaceOnly, "Should be in whitespace only")
        assertEquals("", context.linePrefix)
        assertEquals("    ", context.lineSuffix)
    }

    @Test
    fun `test language-specific indentation expectations`() {
        // Test Python after colon
        myFixture.configureByText("test.py", """
            def function():
            <caret>
        """.trimIndent())
        
        var context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        assertTrue(
            IndentationAnalyzer.isInIndentationPosition(context),
            "Should expect indentation after Python function definition"
        )
        
        // Test JavaScript after brace
        myFixture.configureByText("test.js", """
            function example() {
            <caret>
        """.trimIndent())
        
        context = IndentationAnalyzer.analyzeIndentationContext(
            myFixture.editor, myFixture.caretOffset, project
        )
        assertTrue(
            IndentationAnalyzer.isInIndentationPosition(context),
            "Should expect indentation after JavaScript function brace"
        )
    }
}
