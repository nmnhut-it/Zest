package com.zps.zest.chatui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color
import java.awt.Font
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Utility class for rendering markdown content in chat messages
 */
object MarkdownRenderer {
    
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        HeadingAnchorExtension.create()
    )
    
    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .build()
        
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .build()
    
    /**
     * Create a JEditorPane configured for markdown rendering
     */
    fun createMarkdownPane(markdownText: String): JEditorPane {
        val editorPane = JEditorPane()
        editorPane.contentType = "text/html"
        editorPane.isEditable = false
        editorPane.isOpaque = false
        
        // Configure HTML editor kit with custom styling
        val htmlKit = HTMLEditorKit()
        val styleSheet = createStyleSheet()
        htmlKit.styleSheet = styleSheet
        editorPane.editorKit = htmlKit
        
        // Convert markdown to HTML and set content
        val html = markdownToHtml(markdownText)
        editorPane.text = html
        
        return editorPane
    }
    
    /**
     * Convert markdown text to HTML with syntax highlighting
     */
    fun markdownToHtml(markdownText: String): String {
        val document = parser.parse(markdownText)
        val baseHtml = htmlRenderer.render(document)
        
        // Wrap in a styled container
        return """
            <html>
            <head>
                <style>
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 13px;
                        line-height: 1.5;
                        margin: 8px;
                        color: ${getTextColor()};
                    }
                    pre { 
                        background-color: ${getCodeBackgroundColor()};
                        border: 1px solid ${getBorderColor()};
                        border-radius: 6px;
                        padding: 12px;
                        overflow-x: auto;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 12px;
                    }
                    code {
                        background-color: ${getInlineCodeBackgroundColor()};
                        border-radius: 3px;
                        padding: 2px 4px;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 12px;
                    }
                    pre code {
                        background-color: transparent;
                        padding: 0;
                    }
                    blockquote {
                        border-left: 4px solid ${getAccentColor()};
                        margin: 0;
                        padding-left: 16px;
                        color: ${getSecondaryTextColor()};
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin-top: 16px;
                        margin-bottom: 8px;
                        font-weight: 600;
                    }
                    ul, ol {
                        margin: 8px 0;
                        padding-left: 20px;
                    }
                    li {
                        margin: 4px 0;
                    }
                    table {
                        border-collapse: collapse;
                        margin: 8px 0;
                    }
                    th, td {
                        border: 1px solid ${getBorderColor()};
                        padding: 8px 12px;
                        text-align: left;
                    }
                    th {
                        background-color: ${getHeaderBackgroundColor()};
                        font-weight: 600;
                    }
                </style>
            </head>
            <body>$baseHtml</body>
            </html>
        """.trimIndent()
    }
    
    private fun createStyleSheet(): StyleSheet {
        val styleSheet = StyleSheet()
        
        // Base font styling
        styleSheet.addRule("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 13px; }")
        styleSheet.addRule("pre { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: 12px; }")
        styleSheet.addRule("code { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: 12px; }")
        
        return styleSheet
    }
    
    // Theme-aware color methods
    private fun getTextColor(): String {
        return if (UIUtil.isUnderDarcula()) "#CCCCCC" else "#333333"
    }
    
    private fun getSecondaryTextColor(): String {
        return if (UIUtil.isUnderDarcula()) "#AAAAAA" else "#666666"
    }
    
    private fun getCodeBackgroundColor(): String {
        return if (UIUtil.isUnderDarcula()) "#2B2D30" else "#F6F8FA"
    }
    
    private fun getInlineCodeBackgroundColor(): String {
        return if (UIUtil.isUnderDarcula()) "#3C3F41" else "#F3F4F6"
    }
    
    private fun getBorderColor(): String {
        return if (UIUtil.isUnderDarcula()) "#4C5052" else "#D1D5DB"
    }
    
    private fun getAccentColor(): String {
        return if (UIUtil.isUnderDarcula()) "#6366F1" else "#4F46E5"
    }
    
    private fun getHeaderBackgroundColor(): String {
        return if (UIUtil.isUnderDarcula()) "#3C3F41" else "#F9FAFB"
    }
}