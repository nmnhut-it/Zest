package com.zps.zest.completion.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JComponent
import kotlin.test.assertTrue

class MultiLineDiffRendererTest {
    
    @Test
    fun testBasicRendering() {
        // Create mock objects
        val scheme = mock(EditorColorsScheme::class.java)
        val font = Font("Monospaced", Font.PLAIN, 12)
        
        `when`(scheme.defaultBackground).thenReturn(Color.WHITE)
        `when`(scheme.defaultForeground).thenReturn(Color.BLACK)
        `when`(scheme.getFont(EditorFontType.PLAIN)).thenReturn(font)
        
        // Test data
        val originalLines = listOf(
            "public void process(String input) {",
            "    validate(input);",
            "    transform(input);",
            "}"
        )
        
        val modifiedLines = listOf(
            "public void process(String input, Options opts) {",
            "    if (opts.validate) {",
            "        validate(input);",
            "    }",
            "    transform(input, opts);",
            "}"
        )
        
        // Create renderer
        val renderer = MultiLineDiffRenderer(
            originalLines,
            modifiedLines,
            scheme,
            "java"
        )
        
        // Test width calculation
        val mockInlay = createMockInlay()
        val width = renderer.calcWidthInPixels(mockInlay)
        assertTrue(width > 0, "Width should be positive")
        
        // Test height calculation
        val height = renderer.calcHeightInPixels(mockInlay)
        assertTrue(height > 0, "Height should be positive")
        
        // Test painting (basic smoke test)
        val image = BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        val rect = Rectangle(0, 0, 800, 200)
        
        try {
            renderer.paint(mockInlay, g2d, rect, null)
            // If we get here without exception, painting works
            assertTrue(true)
        } finally {
            g2d.dispose()
        }
    }
    
    @Test
    fun testTextWrapping() {
        val scheme = mock(EditorColorsScheme::class.java)
        val font = Font("Monospaced", Font.PLAIN, 12)
        
        `when`(scheme.defaultBackground).thenReturn(Color.WHITE)
        `when`(scheme.defaultForeground).thenReturn(Color.BLACK)
        `when`(scheme.getFont(EditorFontType.PLAIN)).thenReturn(font)
        
        // Long line that should wrap
        val originalLines = listOf(
            "public void processVeryLongMethodNameWithManyParametersAndStuff(String firstParameter, String secondParameter, String thirdParameter) {"
        )
        
        val modifiedLines = listOf(
            "public void processVeryLongMethodNameWithManyParametersAndStuff(String firstParameter, String secondParameter, String thirdParameter, Options opts) {"
        )
        
        val renderer = MultiLineDiffRenderer(
            originalLines,
            modifiedLines,
            scheme,
            "java"
        )
        
        val mockInlay = createMockInlay(400) // Narrow width to force wrapping
        val height = renderer.calcHeightInPixels(mockInlay)
        
        // Height should be more than single line due to wrapping
        val metrics = Toolkit.getDefaultToolkit().getFontMetrics(font)
        val singleLineHeight = metrics.height + 4
        assertTrue(height > singleLineHeight * 2, "Height should account for wrapped lines")
    }
    
    @Test
    fun testConfigIntegration() {
        val config = DiffRenderingConfig.getInstance()
        val scheme = mock(EditorColorsScheme::class.java)
        val font = Font("Monospaced", Font.PLAIN, 12)
        
        `when`(scheme.defaultBackground).thenReturn(Color.WHITE)
        `when`(scheme.defaultForeground).thenReturn(Color.BLACK)
        `when`(scheme.getFont(EditorFontType.PLAIN)).thenReturn(font)
        
        val originalLines = listOf("line1", "line2")
        val modifiedLines = listOf("modified1", "modified2", "modified3")
        
        // Check if config determines multi-line rendering
        val shouldRenderMultiLine = config.shouldRenderAsMultiLine(
            originalLines.size,
            modifiedLines.size
        )
        
        if (shouldRenderMultiLine) {
            val renderer = MultiLineDiffRenderer(
                originalLines,
                modifiedLines,
                scheme,
                "java",
                config
            )
            
            // Verify renderer uses config values
            val mockInlay = createMockInlay()
            val width = renderer.calcWidthInPixels(mockInlay)
            assertTrue(width > 0)
        }
    }
    
    private fun createMockInlay(editorWidth: Int = 800): Inlay<*> {
        val inlay = mock(Inlay::class.java)
        val editor = mock(Editor::class.java)
        val component = mock(JComponent::class.java)
        val graphics = BufferedImage(editorWidth, 600, BufferedImage.TYPE_INT_RGB).createGraphics()
        val metrics = graphics.fontMetrics
        
        `when`(inlay.editor).thenReturn(editor)
        `when`(editor.contentComponent).thenReturn(component)
        `when`(component.width).thenReturn(editorWidth)
        `when`(component.getFontMetrics(any(Font::class.java))).thenReturn(metrics)
        
        return inlay
    }
}
