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
                        margin: 1px 0;
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
     * Convert markdown to complete JCEF HTML with embedded highlight.js
     */
    fun markdownToJCEFHtml(markdownText: String): String {
        val document = parser.parse(markdownText)
        val baseHtml = htmlRenderer.render(document)
        
        // Load highlight.js and theme CSS
        val isDarkTheme = UIUtil.isUnderDarcula()
        val highlightCss = loadResource("js/${if (isDarkTheme) "github-dark" else "github"}.css") ?: ""
        val highlightJs = loadResource("js/highlight.min.js") ?: ""
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Zest Chat</title>
                <style>
                    $highlightCss
                    
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 14px;
                        margin: 0;
                        padding: 16px;
                        line-height: 1.6;
                        background: ${if (isDarkTheme) "#1e1e1e" else "#ffffff"};
                        color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
                    }
                    
                    pre { 
                        background: ${if (isDarkTheme) "#2d2d30" else "#f6f8fa"};
                        border: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        border-radius: 6px;
                        padding: 12px;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 13px;
                        line-height: 1.4;
                        overflow-x: auto;
                        position: relative;
                    }
                    
                    code {
                        background: ${if (isDarkTheme) "#3c3f41" else "#f3f4f6"};
                        border-radius: 3px;
                        padding: 2px 4px;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 13px;
                    }
                    
                    pre code {
                        background: transparent;
                        padding: 0;
                        border-radius: 0;
                    }
                    
                    .copy-button {
                        position: absolute;
                        top: 8px;
                        right: 8px;
                        background: ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        border: none;
                        border-radius: 4px;
                        padding: 4px 8px;
                        font-size: 12px;
                        cursor: pointer;
                        color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
                        opacity: 0.7;
                        transition: opacity 0.2s;
                    }
                    
                    .copy-button:hover {
                        opacity: 1;
                        background: ${if (isDarkTheme) "#5a5d5e" else "#d0d7de"};
                    }
                    
                    blockquote {
                        border-left: 4px solid ${if (isDarkTheme) "#6366f1" else "#4f46e5"};
                        margin: 16px 0;
                        padding-left: 16px;
                        color: ${if (isDarkTheme) "#aaaaaa" else "#666666"};
                        font-style: italic;
                    }
                    
                    h1, h2, h3, h4, h5, h6 {
                        font-weight: 600;
                        margin: 24px 0 16px 0;
                        line-height: 1.25;
                    }
                    
                    h1 { font-size: 24px; }
                    h2 { font-size: 20px; }
                    h3 { font-size: 16px; }
                    
                    ul, ol {
                        margin: 16px 0;
                        padding-left: 32px;
                    }
                    
                    li {
                        margin: 4px 0;
                    }
                    
                    table {
                        border-collapse: collapse;
                        margin: 16px 0;
                        width: 100%;
                    }
                    
                    th, td {
                        border: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        padding: 8px 12px;
                        text-align: left;
                    }
                    
                    th {
                        background: ${if (isDarkTheme) "#3c3f41" else "#f9fafb"};
                        font-weight: 600;
                    }
                    
                    p {
                        margin: 12px 0;
                    }
                    
                    hr {
                        border: none;
                        border-top: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
                        margin: 24px 0;
                    }
                    
                    /* Message separators */
                    .message-separator {
                        height: 2px;
                        background: linear-gradient(to right, 
                            transparent, 
                            ${if (isDarkTheme) "#464647" else "#e1e4e8"}, 
                            transparent);
                        margin: 32px 0;
                        opacity: 0.6;
                    }
                </style>
            </head>
            <body>
                $baseHtml
                <script>
                    $highlightJs
                    
                    document.addEventListener('DOMContentLoaded', function() {
                        // Initialize highlight.js
                        if (typeof hljs !== 'undefined') {
                            hljs.highlightAll();
                            addCopyButtons();
                        }
                    });
                    
                    function addCopyButtons() {
                        document.querySelectorAll('pre code').forEach(function(block) {
                            const pre = block.parentNode;
                            if (pre.querySelector('.copy-button')) return; // Already has button
                            
                            const button = document.createElement('button');
                            button.className = 'copy-button';
                            button.textContent = 'Copy';
                            button.onclick = function() {
                                navigator.clipboard.writeText(block.textContent).then(function() {
                                    button.textContent = 'Copied!';
                                    setTimeout(function() {
                                        button.textContent = 'Copy';
                                    }, 2000);
                                });
                            };
                            
                            pre.style.position = 'relative';
                            pre.appendChild(button);
                        });
                    }
                    
                    // Expose functions for Java bridge
                    window.chatFunctions = {
                        scrollToElement: function(elementId) {
                            const element = document.getElementById(elementId);
                            if (element) {
                                element.scrollIntoView({ behavior: 'smooth', block: 'start' });
                            }
                        },
                        
                        addMessage: function(html) {
                            document.body.innerHTML += html;
                            if (typeof hljs !== 'undefined') {
                                hljs.highlightAll();
                                addCopyButtons();
                            }
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Load a resource file as a string (same as DiffResourceLoader)
     */
    fun loadResource(path: String): String? {
        return try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(path)
            if (resourceStream != null) {
                resourceStream.bufferedReader().use { it.readText() }
            } else {
                LOG.warn("Resource not found: $path")
                null
            }
        } catch (e: Exception) {
            LOG.error("Error loading resource: $path", e)
            null
        }
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