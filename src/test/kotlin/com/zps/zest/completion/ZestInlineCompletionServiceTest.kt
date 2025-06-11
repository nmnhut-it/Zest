package com.zps.zest.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.zps.zest.completion.data.CompletionContext
import org.junit.Test

/**
 * Tests for ZestInlineCompletionService
 */
class ZestInlineCompletionServiceTest : BasePlatformTestCase() {
    
    @Test
    fun testCompletionContextCreation() {
        val editor = myFixture.editor
        val document = editor.document
        
        // Set up test content
        document.setText("public class Test {\n    public void method() {\n        // cursor here\n    }\n}")
        val cursorOffset = document.text.indexOf("// cursor here")
        editor.caretModel.moveToOffset(cursorOffset)
        
        // Create completion context
        val context = CompletionContext.from(editor, cursorOffset, manually = true)
        
        // Verify context
        assertNotNull(context)
        assertEquals(cursorOffset, context.offset)
        assertTrue(context.manually)
        assertTrue(context.prefixCode.contains("public void method()"))
        assertTrue(context.suffixCode.contains("}\n}"))
    }
    
    @Test
    fun testCompletionContextLineAnalysis() {
        val editor = myFixture.editor
        val document = editor.document
        
        document.setText("    int x = 10;\n    // complete here")
        val cursorOffset = document.text.indexOf("// complete here")
        editor.caretModel.moveToOffset(cursorOffset)
        
        val context = CompletionContext.from(editor, cursorOffset, manually = false)
        
        // Test line analysis methods
        val linePrefix = context.getLinePrefixText(document)
        assertEquals("    ", linePrefix)
        
        assertFalse(context.isAtLineStart(document))
    }
    
    @Test
    fun testServiceInitialization() {
        val service = project.getService(ZestInlineCompletionService::class.java)
        assertNotNull(service)
    }
}
