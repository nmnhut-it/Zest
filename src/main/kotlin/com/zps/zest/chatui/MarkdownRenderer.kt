package com.zps.zest.chatui

import com.intellij.openapi.diagnostic.Logger
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
 * Includes fallbacks for JBR CSS parsing issues
 */
object MarkdownRenderer {
    
    private val LOG = Logger.getInstance(MarkdownRenderer::class.java)
    
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
     * Create a JEditorPane configured for markdown rendering with width constraints
     */
    fun createMarkdownPane(markdownText: String, maxWidth: Int = 500): JEditorPane {
        val editorPane = JEditorPane()
        editorPane.contentType = "text/html"
        editorPane.isEditable = false
        editorPane.isOpaque = false
        
        // Configure HTML editor kit with safe styling to avoid JBR CSS parsing issues
        val htmlKit = HTMLEditorKit()
        val styleSheet = createSafeStyleSheet()
        htmlKit.styleSheet = styleSheet
        editorPane.editorKit = htmlKit
        
        // Convert markdown to HTML with safe CSS and set content
        val html = markdownToHtml(markdownText, maxWidth)
        editorPane.text = html
        
        // Set size for full width support
        if (maxWidth == Int.MAX_VALUE) {
            // Full width mode
            editorPane.preferredSize = com.intellij.util.ui.JBUI.size(600, 100) // Default reasonable width
            editorPane.minimumSize = com.intellij.util.ui.JBUI.size(200, 50)
            editorPane.maximumSize = com.intellij.util.ui.JBUI.size(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            // Constrained width mode
            editorPane.preferredSize = com.intellij.util.ui.JBUI.size(maxWidth, 100)
            editorPane.minimumSize = com.intellij.util.ui.JBUI.size(200, 50)
            editorPane.maximumSize = com.intellij.util.ui.JBUI.size(maxWidth, Int.MAX_VALUE)
        }
        
        return editorPane
    }
    
    /**
     * Escape HTML entities to prevent XSS and parsing issues
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    /**
     * Convert markdown text to HTML for Swing JEditorPane (legacy)
     */
    fun markdownToHtml(markdownText: String, maxWidth: Int = 500): String {
        val document = parser.parse(markdownText)
        val baseHtml = htmlRenderer.render(document)
        
        // Simple HTML for Swing compatibility
        return """
            <html>
            <head>
                <style type="text/css">
                    body { 
                        font-family: SansSerif;
                        font-size: 11px;
                        margin: 6px;
                        width: 100%;
                        color: ${getTextColor()};
                    }
                    pre { 
                        background-color: ${getCodeBackgroundColor()};
                        border: 1px solid ${getBorderColor()};
                        padding: 6px;
                        font-family: Monospaced;
                        font-size: 10px;
                    }
                    code {
                        background-color: ${getInlineCodeBackgroundColor()};
                        font-family: Monospaced;
                        font-size: 10px;
                    }
                    blockquote {
                        border-left: 3px solid ${getAccentColor()};
                        margin: 4px 0;
                        padding-left: 12px;
                        color: ${getSecondaryTextColor()};
                    }
                    h1, h2, h3, h4, h5, h6 {
                        font-weight: bold;
                        margin: 6px 0;
                        font-size: 12px;
                    }
                    ul, ol {
                        margin: 3px 0;
                        padding-left: 16px;
                        font-size: 11px;
                    }
                    li {
                        margin: 3px 0;
                    }
                    table {
                        border-collapse: collapse;
                        margin: 3px 0;
                        font-size: 10px;
                    }
                    th, td {
                        border: 1px solid ${getBorderColor()};
                        padding: 3px 6px;
                        text-align: left;
                    }
                    th {
                        background-color: ${getHeaderBackgroundColor()};
                        font-weight: bold;
                    }
                    p {
                        margin: 3px 0;
                        font-size: 11px;
                    }
                </style>
            </head>
            <body>$baseHtml</body>
            </html>
        """.trimIndent()
    }
    
    
    /**
     * Create a safe StyleSheet without problematic CSS properties that cause JBR issues
     */
    private fun createSafeStyleSheet(): StyleSheet {
        val styleSheet = StyleSheet()
        
        try {
            // Only use CSS properties that are well-supported by Swing's HTML renderer
            styleSheet.addRule("body { font-family: SansSerif; font-size: 13px; margin: 8px; }")
            styleSheet.addRule("pre { font-family: Monospaced; font-size: 12px; background-color: #f5f5f5; border: 1px solid #ddd; padding: 8px; }")
            styleSheet.addRule("code { font-family: Monospaced; font-size: 12px; background-color: #f5f5f5; padding: 2px; }")
            styleSheet.addRule("h1, h2, h3 { font-weight: bold; margin: 8px 0; }")
            styleSheet.addRule("p { margin: 4px 0; }")
            styleSheet.addRule("ul, ol { margin: 4px 0; padding-left: 20px; }")
            styleSheet.addRule("blockquote { margin: 4px 0; padding-left: 12px; border-left: 3px solid #ccc; }")
        } catch (e: Exception) {
            LOG.warn("Failed to add CSS rules to StyleSheet", e)
        }
        
        return styleSheet
    }
    
    /**
     * Legacy method kept for compatibility
     */
    private fun createStyleSheet(): StyleSheet = createSafeStyleSheet()
    
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
        return if (UIUtil.isUnderDarcula()  ) "#3C3F41" else "#F9FAFB"
    }
}