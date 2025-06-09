package com.zps.zest.inlinechat

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Loads diff viewer resources from the plugin's resources folder
 */
object DiffResourceLoader {
    private val LOG = Logger.getInstance(DiffResourceLoader::class.java)
    
    /**
     * Load a resource file as a string
     */
    fun loadResource(path: String): String? {
        return try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(path)
            if (resourceStream != null) {
                BufferedReader(InputStreamReader(resourceStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
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
     * Get the URL for a resource
     */
    fun getResourceUrl(path: String): String? {
        return try {
            val url = this::class.java.classLoader.getResource(path)
            url?.toExternalForm()
        } catch (e: Exception) {
            LOG.error("Error getting resource URL: $path", e)
            null
        }
    }
    
    /**
     * Generate inline HTML with all resources embedded
     */
    fun generateInlineHtml(originalCode: String, suggestedCode: String, isDarkTheme: Boolean, language: String = "java"): String {
        LOG.info("Generating inline HTML for diff viewer with language: $language")
        
        // Check if resources exist
        val requiredResources = listOf(
            "js/${if (isDarkTheme) "github-dark" else "github"}.css",
            "js/diff2html.min.css",
            "js/diff.min.js",
            "js/diff2html-ui.min.js",
            "js/highlight.min.js"  // Add highlight.js
        )
        
        val missingResources = requiredResources.filter { 
            this::class.java.classLoader.getResource(it) == null 
        }
        
        if (missingResources.isNotEmpty()) {
            LOG.error("Missing resources: ${missingResources.joinToString(", ")}")
            return generateFallbackHtml(originalCode, suggestedCode, isDarkTheme, missingResources)
        }
        
        // Load CSS files
        val highlightCss = loadResource("js/${if (isDarkTheme) "github-dark" else "github"}.css") ?: ""
        val diff2htmlCss = loadResource("js/diff2html.min.css") ?: ""
        
        // Load JS files
        val diffJs = loadResource("js/diff.min.js") ?: ""
        val diff2htmlJs = loadResource("js/diff2html-ui.min.js") ?: ""
        val highlightJs = loadResource("js/highlight.min.js") ?: ""
        
        if (highlightCss.isEmpty() || diff2htmlCss.isEmpty() || diffJs.isEmpty() || diff2htmlJs.isEmpty() || highlightJs.isEmpty()) {
            LOG.warn("Some resources could not be loaded")
            LOG.warn("highlightCss: ${highlightCss.length}, diff2htmlCss: ${diff2htmlCss.length}, diffJs: ${diffJs.length}, diff2htmlJs: ${diff2htmlJs.length}, highlightJs: ${highlightJs.length}")
        }
        
        // Return HTML with inline resources
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Code Diff</title>
    <style>
        /* Highlight.js theme */
        $highlightCss
        
        /* Diff2Html styles */
        $diff2htmlCss
        
        /* Minimal custom styles - let diff2html handle most styling */
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        }
        #diff-container {
            height: 100vh;
            overflow: auto;
        }
        
        /* Hide file header as we don't need it */
        .d2h-file-header {
            display: none !important;
        }
        
        /* Ensure syntax highlighting is visible */
        .d2h-code-line-ctn {
            font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
        }
        
        /* For dark theme, ensure highlighted syntax has proper contrast */
        ${if (isDarkTheme) """
        .hljs-keyword { color: #569cd6 !important; }
        .hljs-string { color: #ce9178 !important; }
        .hljs-number { color: #b5cea8 !important; }
        .hljs-comment { color: #6a9955 !important; }
        .hljs-class .hljs-title { color: #4ec9b0 !important; }
        .hljs-function .hljs-title { color: #dcdcaa !important; }
        .hljs-variable { color: #9cdcfe !important; }
        .hljs-type { color: #4ec9b0 !important; }
        """ else ""}
    </style>
</head>
<body>
    <div id="diff-container"></div>
    <script>
        /* highlight.js library */
        $highlightJs
    </script>
    <script>
        /* jsdiff library */
        $diffJs
    </script>
    <script>
        /* diff2html library */
        $diff2htmlJs
    </script>
    <script>
        // Application code
        console.log('Diff viewer initialized');
        
        function createUnifiedDiff(original, suggested, language) {
            console.log('Creating diff...');
            console.log('Original length:', original.length);
            console.log('Suggested length:', suggested.length);
            console.log('Language:', language);
            
            // Use a filename with extension to help highlight.js detect the language
            const extensions = {
                'java': '.java',
                'javascript': '.js',
                'typescript': '.ts',
                'python': '.py',
                'cpp': '.cpp',
                'csharp': '.cs',
                'kotlin': '.kt',
                'go': '.go',
                'rust': '.rs',
                'ruby': '.rb',
                'php': '.php',
                'swift': '.swift',
                'scala': '.scala',
                'html': '.html',
                'css': '.css',
                'xml': '.xml',
                'json': '.json',
                'yaml': '.yaml',
                'sql': '.sql',
                'text': '.txt'
            };
            
            const extension = extensions[language.toLowerCase()] || '';
            const filename = 'code' + extension;
            console.log('Using filename:', filename);
            
            const patch = Diff.createPatch(
                filename,
                original,
                suggested,
                'Original',
                'AI Suggested',
                { context: 999 }  // Show all lines
            );
            
            console.log('Diff created, patch length:', patch.length);
            console.log('First 500 chars of patch:', patch.substring(0, 500));
            
            // Return the full patch - diff2html needs the header
            return patch;
        }
        
        // Embedded code data
        const original = ${escapeForJavaScript(originalCode)};
        const suggested = ${escapeForJavaScript(suggestedCode)};
        const language = '${language}';
        
        console.log('Input data loaded');
        
        const diffString = createUnifiedDiff(original, suggested, language);
        console.log('Diff string generated');
        
        const configuration = {
            outputFormat: 'side-by-side',
            drawFileList: false,
            matching: 'lines',
            highlight: true,
            fileContentToggle: false,
            renderNothingWhenEmpty: false,
            synchronisedScroll: true,
            colorScheme: '${if (isDarkTheme) "dark" else "light"}'  // Use diff2html's built-in dark mode
        };
        
        console.log('Configuration:', configuration);
        
        const targetElement = document.getElementById('diff-container');
        console.log('Target element found:', targetElement !== null);
        
        try {
            const diff2htmlUi = new Diff2HtmlUI(targetElement, diffString, configuration);
            diff2htmlUi.draw();
            console.log('Diff drawn successfully');
            
            // Check if highlight.js is available
            if (typeof hljs !== 'undefined') {
                console.log('highlight.js is available, version:', hljs.versionString || 'unknown');
                console.log('Registered languages:', Object.keys(hljs.listLanguages ? hljs.listLanguages() : []));
            } else {
                console.warn('highlight.js is NOT available');
            }
            
            diff2htmlUi.highlightCode();
            console.log('Syntax highlighting applied');
            
            // Debug: Check if highlighting was actually applied
            setTimeout(() => {
                const codeElements = document.querySelectorAll('.d2h-code-line-ctn');
                console.log('Code elements found:', codeElements.length);
                if (codeElements.length > 0) {
                    const hasHighlighting = Array.from(codeElements).some(el => 
                        el.querySelector('.hljs-keyword, .hljs-string, .hljs-number, .hljs-comment')
                    );
                    console.log('Syntax highlighting detected:', hasHighlighting);
                    if (!hasHighlighting) {
                        console.warn('No syntax highlighting classes found. Check if highlight.js is working properly.');
                    }
                }
            }, 100);
        } catch (error) {
            console.error('Error rendering diff:', error);
            targetElement.innerHTML = '<div style="color: red; padding: 20px;">Error rendering diff: ' + error.message + '</div>';
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
    
    private fun escapeForJavaScript(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("'", "\\'") + "\""
    }
    
    /**
     * Generate a fallback HTML page when resources are missing
     */
    private fun generateFallbackHtml(
        originalCode: String, 
        suggestedCode: String, 
        isDarkTheme: Boolean,
        missingResources: List<String>
    ): String {
        val bgColor = if (isDarkTheme) "#1e1e1e" else "#ffffff"
        val textColor = if (isDarkTheme) "#d4d4d4" else "#333333"
        
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Diff Viewer - Resources Missing</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: $bgColor;
            color: $textColor;
            padding: 20px;
            margin: 0;
        }
        .error {
            background: ${if (isDarkTheme) "#5a1e1e" else "#ffe6e6"};
            border: 1px solid ${if (isDarkTheme) "#8b3a3a" else "#ff9999"};
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 20px;
        }
        .code-block {
            background: ${if (isDarkTheme) "#2d2d2d" else "#f5f5f5"};
            border: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
            padding: 15px;
            border-radius: 4px;
            overflow-x: auto;
            margin: 10px 0;
        }
        pre {
            margin: 0;
            font-family: 'JetBrains Mono', Consolas, monospace;
            font-size: 13px;
            line-height: 1.5;
        }
        h2 {
            margin-top: 30px;
            margin-bottom: 10px;
        }
        ul {
            margin: 10px 0;
        }
    </style>
</head>
<body>
    <div class="error">
        <h2>⚠️ Diff Viewer Resources Missing</h2>
        <p>The following resources could not be loaded:</p>
        <ul>
            ${missingResources.joinToString("") { "<li>$it</li>" }}
        </ul>
        <p>Please ensure these files are present in <code>src/main/resources/js/</code></p>
        <p>See the documentation for setup instructions.</p>
    </div>
    
    <h2>Original Code</h2>
    <div class="code-block">
        <pre>${escapeHtml(originalCode)}</pre>
    </div>
    
    <h2>Suggested Code</h2>
    <div class="code-block">
        <pre>${escapeHtml(suggestedCode)}</pre>
    </div>
</body>
</html>
        """.trimIndent()
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
