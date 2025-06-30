package com.zps.zest.diff;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Generates HTML for diff viewers with syntax highlighting and theme support
 */
public class DiffHtmlGenerator {
    private static final Logger LOG = Logger.getInstance(DiffHtmlGenerator.class);

    /**
     * Generate HTML for a git diff with syntax highlighting
     */
    public static String generateDiffHtml(String diffText, String filePath, boolean isDarkTheme) {
        String language = detectLanguage(filePath);
        return generateDiffHtml(diffText, filePath, isDarkTheme, language);
    }

    /**
     * Generate HTML for a git diff with syntax highlighting and specified language
     */
    public static String generateDiffHtml(String diffText, String filePath, boolean isDarkTheme, String language) {
        StringBuilder html = new StringBuilder();

        // HTML header with embedded CSS and JS
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>Diff View</title>\n");

        // Add Prism.js for syntax highlighting (CDN)
        html.append("<link href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/");
        html.append(isDarkTheme ? "prism-tomorrow" : "prism");
        html.append(".min.css\" rel=\"stylesheet\" />\n");

        // Add diff2html CSS (CDN)
        html.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.45/bundles/css/diff2html.min.css\">\n");

        // Custom CSS
        html.append("<style>\n");
        html.append(getCustomCss(isDarkTheme));
        html.append("</style>\n");

        html.append("</head>\n<body>\n");

        // Add file header
        html.append("<div class=\"file-header\">\n");
        html.append("<div class=\"file-path\">").append(escapeHtml(filePath)).append("</div>\n");
        html.append("<div class=\"actions\">\n");
        html.append("<button class=\"devtools-btn\" onclick=\"openDevTools()\">DevTools (F12)</button>\n");
        html.append("</div>\n");
        html.append("</div>\n");

        // Process the diff
        if (diffText == null || diffText.trim().isEmpty()) {
            html.append("<div class=\"no-changes\">No changes found for this file</div>\n");
        } else {
            html.append("<div id=\"diff-container\">\n");
            html.append(formatDiffContent(diffText, language, isDarkTheme));
            html.append("</div>\n");
        }

        // Add JavaScript
        html.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js\"></script>\n");
        html.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-").append(getPrismLanguage(language)).append(".min.js\"></script>\n");
        html.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.45/bundles/js/diff2html.min.js\"></script>\n");

        html.append("<script>\n");
        html.append(getJavaScript());
        html.append("</script>\n");

        html.append("</body>\n</html>");

        return html.toString();
    }

    /**
     * Generate HTML using diff2html library for better performance
     */
    public static String generateDiff2Html(String diffText, String filePath, boolean isDarkTheme) {
        // Try to load resources from classpath first, then use CDN as fallback
        String diff2htmlCss = loadResource("js/diff2html.min.css");
        String diffJs = loadResource("js/diff.min.js");
        String diff2htmlJs = loadResource("js/diff2html-ui.min.js");
        String highlightJs = loadResource("js/highlight.min.js");
        String highlightCss = loadResource("js/" + (isDarkTheme ? "github-dark" : "github") + ".css");

        // If any critical resources fail to load, use CDN version
        boolean useEmbedded = !diff2htmlCss.isEmpty() && !diffJs.isEmpty() && !diff2htmlJs.isEmpty();

        if (!useEmbedded) {
            LOG.warn("Some resources failed to load from classpath. Using CDN fallback. " +
                    "CSS: " + !diff2htmlCss.isEmpty() + ", diffJs: " + !diffJs.isEmpty() +
                    ", diff2htmlJs: " + !diff2htmlJs.isEmpty());
            return generateDiff2HtmlWithCDN(diffText, filePath, isDarkTheme);
        }

        // Detect language for syntax highlighting
        String language = detectLanguage(filePath);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <title>Diff View</title>\n");
        html.append("    <style>\n");

        // First load diff2html CSS
        html.append("        /* Diff2Html styles */\n");
        html.append(diff2htmlCss).append("\n");

        // Then load syntax highlighting theme
        if (!highlightCss.isEmpty()) {
            html.append("        /* Syntax highlighting theme */\n");
            html.append(highlightCss).append("\n");
        }

        // Minimal custom overrides to fix line spacing and layout
        html.append("        /* Custom overrides for better integration */\n");
        html.append("        html, body {\n");
        html.append("            margin: 0;\n");
        html.append("            padding: 0;\n");
        html.append("            height: 100%;\n");
        html.append("            overflow: hidden;\n");
        html.append("            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n");
        html.append("            background: ").append(isDarkTheme ? "#0d1117" : "#ffffff").append(";\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #diff-container {\n");
        html.append("            height: 100vh;\n");
        html.append("            overflow-y: auto;\n");
        html.append("            overflow-x: hidden;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Fix line height and spacing issues */\n");
        html.append("        .d2h-diff-table {\n");
        html.append("            font-size: 13px;\n");
        html.append("            line-height: 20px;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        .d2h-code-line {\n");
         html.append("            vertical-align: top;\n");
        html.append("            white-space: pre !important;\n");
        html.append("            word-wrap: normal !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        .d2h-code-line-ctn {\n");
        html.append("            white-space: pre !important;\n");
        html.append("            word-wrap: normal !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Remove extra padding/margins that cause spacing issues */\n");
        html.append("        .d2h-code-line-prefix,\n");
        html.append("        .d2h-code-linenumber {\n");
        html.append("            padding-top: 0 !important;\n");
        html.append("            padding-bottom: 0 !important;\n");
        html.append("            line-height: 20px !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Fix table cell alignment */\n");
        html.append("        .d2h-diff-tbody > tr > td {\n");
        html.append("            vertical-align: top !important;\n");
        html.append("            padding-top: 0 !important;\n");
        html.append("            padding-bottom: 0 !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Hide file headers since we show them in the dialog */\n");
        html.append("        .d2h-file-header {\n");
        html.append("            display: none !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Ensure syntax highlighting doesn't break layout */\n");
        html.append("        .d2h-code-line-ctn * {\n");
        html.append("            line-height: inherit !important;\n");
        html.append("            vertical-align: baseline !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Loading and error states */\n");
        html.append("        #loading, #error {\n");
        html.append("            padding: 40px;\n");
        html.append("            text-align: center;\n");
        html.append("            font-size: 14px;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #loading {\n");
        html.append("            color: ").append(isDarkTheme ? "#8b949e" : "#6e7781").append(";\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #error {\n");
        html.append("            color: #f85149;\n");
        html.append("            display: none;\n");
        html.append("        }\n");
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div id=\"loading\">Loading diff...</div>\n");
        html.append("    <div id=\"error\"></div>\n");
        html.append("    <div id=\"diff-container\" style=\"display: none;\"></div>\n");
        html.append("    \n");

        // Load scripts
        html.append("    <script>\n");
        html.append("        function showError(message) {\n");
        html.append("            document.getElementById('loading').style.display = 'none';\n");
        html.append("            document.getElementById('diff-container').style.display = 'none';\n");
        html.append("            const errorEl = document.getElementById('error');\n");
        html.append("            errorEl.textContent = 'Error: ' + message;\n");
        html.append("            errorEl.style.display = 'block';\n");
        html.append("        }\n");
        html.append("    </script>\n");

        // Load highlight.js if available
        if (!highlightJs.isEmpty()) {
            html.append("    <script>\n");
            html.append("        try {\n");
            html.append(highlightJs).append("\n");
            html.append("        } catch (e) {\n");
            html.append("            console.warn('Failed to load highlight.js:', e);\n");
            html.append("        }\n");
            html.append("    </script>\n");
        }

        // Load diff.js
        html.append("    <script>\n");
        html.append("        try {\n");
        html.append(diffJs).append("\n");
        html.append("        } catch (e) {\n");
        html.append("            showError('Failed to load diff library: ' + e.message);\n");
        html.append("        }\n");
        html.append("    </script>\n");

        // Load diff2html
        html.append("    <script>\n");
        html.append("        try {\n");
        html.append(diff2htmlJs).append("\n");
        html.append("        } catch (e) {\n");
        html.append("            showError('Failed to load diff2html library: ' + e.message);\n");
        html.append("        }\n");
        html.append("    </script>\n");

        // Main initialization script
        html.append("    <script>\n");
        html.append("        (function() {\n");
        html.append("            try {\n");
        html.append("                // Configure highlight.js if available\n");
        html.append("                if (typeof hljs !== 'undefined') {\n");
        html.append("                    hljs.configure({\n");
        html.append("                        languages: ['").append(language).append("'],\n");
        html.append("                        ignoreUnescapedHTML: true\n");
        html.append("                    });\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("                // The actual diff content\n");
        html.append("                const diffString = ").append(escapeForJavaScript(diffText)).append(";\n");
        html.append("                \n");
        html.append("                if (!diffString || diffString.trim().length === 0) {\n");
        html.append("                    showError('No diff content to display');\n");
        html.append("                    return;\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("                // Configuration for diff2html\n");
        html.append("                const configuration = {\n");
        html.append("                    outputFormat: 'line-by-line',\n");
        html.append("                    matching: 'lines',\n");
        html.append("                    drawFileList: false,\n");
        html.append("                    renderNothingWhenEmpty: false,\n");
        html.append("                    fileListToggle: false,\n");
        html.append("                    fileListStartVisible: false,\n");
        html.append("                    fileContentToggle: false,\n");
        html.append("                    synchronisedScroll: false,\n");
        html.append("                    highlight: true,\n");
        html.append("                    filePathTemplate: '{filePath}',\n");
        html.append("                    colorScheme: '").append(isDarkTheme ? "dark" : "light").append("'\n");
        html.append("                };\n");
        html.append("                \n");
        html.append("                // Initialize diff2html\n");
        html.append("                const targetElement = document.getElementById('diff-container');\n");
        html.append("                const diff2htmlUi = new Diff2HtmlUI(targetElement, diffString, configuration);\n");
        html.append("                diff2htmlUi.draw();\n");
        html.append("                \n");
        html.append("                // Hide loading, show content\n");
        html.append("                document.getElementById('loading').style.display = 'none';\n");
        html.append("                targetElement.style.display = 'block';\n");
        html.append("                \n");
        html.append("                // Apply syntax highlighting\n");
        html.append("                if (diff2htmlUi.highlightCode) {\n");
        html.append("                    try {\n");
        html.append("                        diff2htmlUi.highlightCode();\n");
        html.append("                    } catch (e) {\n");
        html.append("                        console.warn('Syntax highlighting failed:', e);\n");
        html.append("                    }\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("            } catch (e) {\n");
        html.append("                showError(e.message);\n");
        html.append("                console.error('Failed to render diff:', e);\n");
        html.append("            }\n");
        html.append("        })();\n");
        html.append("        \n");
        html.append("        // Handle F12 for DevTools\n");
        html.append("        document.addEventListener('keydown', function(e) {\n");
        html.append("            if (e.key === 'F12') {\n");
        html.append("                e.preventDefault();\n");
        html.append("                // Request will be handled by Java side\n");
        html.append("            }\n");
        html.append("        });\n");
        html.append("    </script>\n");
        html.append("</body>\n");
        html.append("</html>");

        return html.toString();
    }

    /**
     * Load resource file content
     */
    private static String loadResource(String path) {
        try {
            InputStream is = DiffHtmlGenerator.class.getClassLoader().getResourceAsStream(path);
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.error("Failed to load resource: " + path, e);
        }
        return "";
    }

    /**
     * Generate HTML using CDN versions of diff2html when local resources fail
     */
    private static String generateDiff2HtmlWithCDN(String diffText, String filePath, boolean isDarkTheme) {
        LOG.info("Using CDN fallback for diff2html resources");

        String language = detectLanguage(filePath);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <title>Diff View</title>\n");

        // Load diff2html from CDN
        html.append("    <link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.45/bundles/css/diff2html.min.css\">\n");

        // Load highlight.js from CDN
        html.append("    <link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/");
        html.append(isDarkTheme ? "github-dark" : "github");
        html.append(".min.css\">\n");

        // Custom styles - same as embedded version
        html.append("    <style>\n");
        html.append("        /* Custom overrides for better integration */\n");
        html.append("        html, body {\n");
        html.append("            margin: 0;\n");
        html.append("            padding: 0;\n");
        html.append("            height: 100%;\n");
        html.append("            overflow: hidden;\n");
        html.append("            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n");
        html.append("            background: ").append(isDarkTheme ? "#0d1117" : "#ffffff").append(";\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #diff-container {\n");
        html.append("            height: 100vh;\n");
        html.append("            overflow-y: auto;\n");
        html.append("            overflow-x: hidden;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Fix line height and spacing issues */\n");
        html.append("        .d2h-diff-table {\n");
        html.append("            font-size: 13px;\n");
        html.append("            line-height: 20px;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        .d2h-code-line {\n");
        html.append("            height: auto !important;\n");
        html.append("            vertical-align: top;\n");
        html.append("            white-space: pre !important;\n");
        html.append("            word-wrap: normal !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        .d2h-code-line-ctn {\n");
        html.append("            white-space: pre !important;\n");
        html.append("            word-wrap: normal !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Remove extra padding/margins that cause spacing issues */\n");
        html.append("        .d2h-code-line-prefix,\n");
        html.append("        .d2h-code-linenumber {\n");
        html.append("            padding-top: 0 !important;\n");
        html.append("            padding-bottom: 0 !important;\n");
        html.append("            line-height: 20px !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Fix table cell alignment */\n");
        html.append("        .d2h-diff-tbody > tr > td {\n");
        html.append("            vertical-align: top !important;\n");
        html.append("            padding-top: 0 !important;\n");
        html.append("            padding-bottom: 0 !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Hide file headers since we show them in the dialog */\n");
        html.append("        .d2h-file-header {\n");
        html.append("            display: none !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Ensure syntax highlighting doesn't break layout */\n");
        html.append("        .d2h-code-line-ctn * {\n");
        html.append("            line-height: inherit !important;\n");
        html.append("            vertical-align: baseline !important;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        /* Loading and error states */\n");
        html.append("        #loading, #error {\n");
        html.append("            padding: 40px;\n");
        html.append("            text-align: center;\n");
        html.append("            font-size: 14px;\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #loading {\n");
        html.append("            color: ").append(isDarkTheme ? "#8b949e" : "#6e7781").append(";\n");
        html.append("        }\n");
        html.append("        \n");
        html.append("        #error {\n");
        html.append("            color: #f85149;\n");
        html.append("            display: none;\n");
        html.append("        }\n");
        html.append("    </style>\n");
        html.append("</head>\n<body>\n");
        html.append("    <div id=\"loading\">Loading diff...</div>\n");
        html.append("    <div id=\"error\"></div>\n");
        html.append("    <div id=\"diff-container\" style=\"display: none;\"></div>\n");

        // Load JavaScript libraries from CDN
        html.append("    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/jsdiff/5.1.0/diff.min.js\"></script>\n");
        html.append("    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.45/bundles/js/diff2html-ui.min.js\"></script>\n");
        html.append("    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"></script>\n");

        // Main script - same logic as embedded version
        html.append("    <script>\n");
        html.append("        (function() {\n");
        html.append("            try {\n");
        html.append("                // Configure highlight.js\n");
        html.append("                if (typeof hljs !== 'undefined') {\n");
        html.append("                    hljs.configure({\n");
        html.append("                        languages: ['").append(language).append("'],\n");
        html.append("                        ignoreUnescapedHTML: true\n");
        html.append("                    });\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("                const diffString = ").append(escapeForJavaScript(diffText)).append(";\n");
        html.append("                \n");
        html.append("                if (!diffString || diffString.trim().length === 0) {\n");
        html.append("                    document.getElementById('loading').style.display = 'none';\n");
        html.append("                    document.getElementById('error').textContent = 'No diff content to display';\n");
        html.append("                    document.getElementById('error').style.display = 'block';\n");
        html.append("                    return;\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("                const configuration = {\n");
        html.append("                    outputFormat: 'line-by-line',\n");
        html.append("                    matching: 'lines',\n");
        html.append("                    drawFileList: false,\n");
        html.append("                    renderNothingWhenEmpty: false,\n");
        html.append("                    fileListToggle: false,\n");
        html.append("                    fileListStartVisible: false,\n");
        html.append("                    fileContentToggle: false,\n");
        html.append("                    synchronisedScroll: false,\n");
        html.append("                    highlight: true,\n");
        html.append("                    filePathTemplate: '{filePath}',\n");
        html.append("                    colorScheme: '").append(isDarkTheme ? "dark" : "light").append("'\n");
        html.append("                };\n");
        html.append("                \n");
        html.append("                const targetElement = document.getElementById('diff-container');\n");
        html.append("                const diff2htmlUi = new Diff2HtmlUI(targetElement, diffString, configuration);\n");
        html.append("                diff2htmlUi.draw();\n");
        html.append("                \n");
        html.append("                document.getElementById('loading').style.display = 'none';\n");
        html.append("                targetElement.style.display = 'block';\n");
        html.append("                \n");
        html.append("                if (diff2htmlUi.highlightCode) {\n");
        html.append("                    try {\n");
        html.append("                        diff2htmlUi.highlightCode();\n");
        html.append("                    } catch (e) {\n");
        html.append("                        console.warn('Syntax highlighting failed:', e);\n");
        html.append("                    }\n");
        html.append("                }\n");
        html.append("                \n");
        html.append("            } catch (e) {\n");
        html.append("                console.error('Error rendering diff:', e);\n");
        html.append("                document.getElementById('loading').style.display = 'none';\n");
        html.append("                document.getElementById('error').textContent = 'Error: ' + e.message;\n");
        html.append("                document.getElementById('error').style.display = 'block';\n");
        html.append("            }\n");
        html.append("        })();\n");
        html.append("    </script>\n");
        html.append("</body>\n</html>");

        return html.toString();
    }

    /**
     * Escape text for JavaScript string literal
     */
    private static String escapeForJavaScript(String text) {
        if (text == null) return "\"\"";

        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("'", "\\'") + "\"";
    }

    /**
     * Format diff content with proper structure
     */
    private static String formatDiffContent(String diffText, String language, boolean isDarkTheme) {
        StringBuilder result = new StringBuilder();

        String[] lines = diffText.split("\n");
        int oldLineNumber = 0;
        int newLineNumber = 0;

        result.append("<table class='diff-table'>\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.startsWith("diff --git") || line.startsWith("index ") ||
                    line.startsWith("---") || line.startsWith("+++")) {
                // Skip header lines
                continue;
            } else if (line.startsWith("@@")) {
                // Hunk header
                result.append("<tr><td colspan='3' class='diff-hunk-header'>").append(escapeHtml(line)).append("</td></tr>\n");

                // Parse line numbers
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    try {
                        String oldInfo = parts[1];
                        String newInfo = parts[2];

                        if (oldInfo.startsWith("-")) {
                            oldInfo = oldInfo.substring(1);
                            String[] oldParts = oldInfo.split(",");
                            oldLineNumber = Integer.parseInt(oldParts[0]);
                        }

                        if (newInfo.startsWith("+")) {
                            newInfo = newInfo.substring(1);
                            String[] newParts = newInfo.split(",");
                            newLineNumber = Integer.parseInt(newParts[0]);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore parsing errors
                    }
                }
            } else if (line.startsWith("+")) {
                // Addition
                result.append("<tr class='diff-line addition'>\n");
                result.append("<td class='line-number'></td>\n");
                result.append("<td class='line-number'>").append(newLineNumber++).append("</td>\n");
                result.append("<td class='line-content'><span class='diff-marker'>+</span>");
                result.append(highlightCode(line.substring(1), language));
                result.append("</td>\n");
                result.append("</tr>\n");
            } else if (line.startsWith("-")) {
                // Deletion
                result.append("<tr class='diff-line deletion'>\n");
                result.append("<td class='line-number'>").append(oldLineNumber++).append("</td>\n");
                result.append("<td class='line-number'></td>\n");
                result.append("<td class='line-content'><span class='diff-marker'>-</span>");
                result.append(highlightCode(line.substring(1), language));
                result.append("</td>\n");
                result.append("</tr>\n");
            } else {
                // Context line
                result.append("<tr class='diff-line context'>\n");
                result.append("<td class='line-number'>").append(oldLineNumber++).append("</td>\n");
                result.append("<td class='line-number'>").append(newLineNumber++).append("</td>\n");
                result.append("<td class='line-content'><span class='diff-marker'> </span>");
                result.append(highlightCode(line.startsWith(" ") ? line.substring(1) : line, language));
                result.append("</td>\n");
                result.append("</tr>\n");
            }
        }

        result.append("</table>\n");

        return result.toString();
    }

    /**
     * Highlight code using Prism classes
     */
    private static String highlightCode(String code, String language) {
        if (language.equals("text") || language.isEmpty()) {
            return escapeHtml(code);
        }

        return "<code class=\"language-" + language + "\">" + escapeHtml(code) + "</code>";
    }

    /**
     * Get custom CSS for the diff viewer
     */
    private static String getCustomCss(boolean isDarkTheme) {
        return "body {\n" +
                "    margin: 0;\n" +
                "    padding: 0;\n" +
                "    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "    background-color: " + (isDarkTheme ? "#0d1117" : "#ffffff") + ";\n" +
                "    color: " + (isDarkTheme ? "#c9d1d9" : "#24292e") + ";\n" +
                "}\n" +
                "\n" +
                ".file-header {\n" +
                "    display: flex;\n" +
                "    justify-content: space-between;\n" +
                "    align-items: center;\n" +
                "    padding: 12px 16px;\n" +
                "    background-color: " + (isDarkTheme ? "#161b22" : "#f6f8fa") + ";\n" +
                "    border-bottom: 1px solid " + (isDarkTheme ? "#30363d" : "#d0d7de") + ";\n" +
                "}\n" +
                "\n" +
                ".file-path {\n" +
                "    font-weight: 600;\n" +
                "    font-size: 14px;\n" +
                "}\n" +
                "\n" +
                ".devtools-btn {\n" +
                "    padding: 4px 12px;\n" +
                "    background-color: " + (isDarkTheme ? "#21262d" : "#f3f4f6") + ";\n" +
                "    border: 1px solid " + (isDarkTheme ? "#30363d" : "#d0d7de") + ";\n" +
                "    border-radius: 6px;\n" +
                "    color: " + (isDarkTheme ? "#c9d1d9" : "#24292e") + ";\n" +
                "    cursor: pointer;\n" +
                "    font-size: 12px;\n" +
                "}\n" +
                "\n" +
                ".devtools-btn:hover {\n" +
                "    background-color: " + (isDarkTheme ? "#30363d" : "#eaeef2") + ";\n" +
                "}\n" +
                "\n" +
                ".no-changes {\n" +
                "    padding: 40px;\n" +
                "    text-align: center;\n" +
                "    font-size: 16px;\n" +
                "    color: " + (isDarkTheme ? "#8b949e" : "#6e7781") + ";\n" +
                "}\n" +
                "\n" +
                ".diff-table {\n" +
                "    width: 100%;\n" +
                "    border-collapse: collapse;\n" +
                "    font-family: 'SF Mono', Monaco, 'Cascadia Code', Consolas, monospace;\n" +
                "    font-size: 12px;\n" +
                "    line-height: 20px;\n" +
                "}\n" +
                "\n" +
                ".diff-table td {\n" +
                "    padding: 0;\n" +
                "    vertical-align: top;\n" +
                "}\n" +
                "\n" +
                ".line-number {\n" +
                "    width: 50px;\n" +
                "    padding: 0 10px;\n" +
                "    text-align: right;\n" +
                "    user-select: none;\n" +
                "    color: " + (isDarkTheme ? "#8b949e" : "#6e7781") + ";\n" +
                "    background-color: " + (isDarkTheme ? "#0d1117" : "#ffffff") + ";\n" +
                "    border-right: 1px solid " + (isDarkTheme ? "#30363d" : "#d0d7de") + ";\n" +
                "}\n" +
                "\n" +
                ".line-content {\n" +
                "    padding: 0 10px;\n" +
                "    white-space: pre;\n" +
                "    overflow-x: auto;\n" +
                "}\n" +
                "\n" +
                ".diff-marker {\n" +
                "    display: inline-block;\n" +
                "    width: 15px;\n" +
                "    font-weight: bold;\n" +
                "}\n" +
                "\n" +
                ".addition {\n" +
                "    background-color: " + (isDarkTheme ? "#0f5323" : "#dcffe4") + ";\n" +
                "}\n" +
                "\n" +
                ".addition .line-content {\n" +
                "    background-color: " + (isDarkTheme ? "#0f5323" : "#dcffe4") + ";\n" +
                "    border-left: 2px solid " + (isDarkTheme ? "#2ea043" : "#34d058") + ";\n" +
                "}\n" +
                "\n" +
                ".addition .diff-marker {\n" +
                "    color: " + (isDarkTheme ? "#2ea043" : "#34d058") + ";\n" +
                "}\n" +
                "\n" +
                ".deletion {\n" +
                "    background-color: " + (isDarkTheme ? "#5c1624" : "#ffdce0") + ";\n" +
                "}\n" +
                "\n" +
                ".deletion .line-content {\n" +
                "    background-color: " + (isDarkTheme ? "#5c1624" : "#ffdce0") + ";\n" +
                "    border-left: 2px solid " + (isDarkTheme ? "#f85149" : "#ff5555") + ";\n" +
                "}\n" +
                "\n" +
                ".deletion .diff-marker {\n" +
                "    color: " + (isDarkTheme ? "#f85149" : "#ff5555") + ";\n" +
                "}\n" +
                "\n" +
                ".diff-hunk-header {\n" +
                "    background-color: " + (isDarkTheme ? "#161b22" : "#f6f8fa") + ";\n" +
                "    color: " + (isDarkTheme ? "#8b949e" : "#6e7781") + ";\n" +
                "    padding: 4px 10px;\n" +
                "    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "    font-size: 12px;\n" +
                "    border-top: 1px solid " + (isDarkTheme ? "#30363d" : "#d0d7de") + ";\n" +
                "    border-bottom: 1px solid " + (isDarkTheme ? "#30363d" : "#d0d7de") + ";\n" +
                "}\n" +
                "\n" +
                "/* Override Prism styles for better integration */\n" +
                "code[class*=\"language-\"] {\n" +
                "    background: none !important;\n" +
                "    text-shadow: none !important;\n" +
                "    padding: 0 !important;\n" +
                "    margin: 0 !important;\n" +
                "}\n" +
                "\n" +
                "pre[class*=\"language-\"] {\n" +
                "    background: none !important;\n" +
                "    margin: 0 !important;\n" +
                "    padding: 0 !important;\n" +
                "    overflow: visible !important;\n" +
                "}\n";
    }

    /**
     * Get JavaScript for the diff viewer
     */
    private static String getJavaScript() {
        return "// Add keyboard shortcut for DevTools\n" +
                "document.addEventListener('keydown', function(e) {\n" +
                "    if (e.key === 'F12') {\n" +
                "        openDevTools();\n" +
                "    }\n" +
                "});\n" +
                "\n" +
                "// Open DevTools function\n" +
                "function openDevTools() {\n" +
                "    console.log('DevTools requested');\n" +
                "    // This will be handled by the Java side\n" +
                "}\n" +
                "\n" +
                "// Apply syntax highlighting\n" +
                "document.addEventListener('DOMContentLoaded', function() {\n" +
                "    Prism.highlightAll();\n" +
                "});\n" +
                "\n" +
                "// Add context menu\n" +
                "document.addEventListener('contextmenu', function(e) {\n" +
                "    e.preventDefault();\n" +
                "    // Context menu will be handled by Java side\n" +
                "});\n";
    }

    /**
     * Detect language from file path
     */
    private static String detectLanguage(String filePath) {
        if (filePath == null) return "text";

        String lowerPath = filePath.toLowerCase();
        String extension = "";

        int lastDot = lowerPath.lastIndexOf('.');
        if (lastDot > 0 && lastDot < lowerPath.length() - 1) {
            extension = lowerPath.substring(lastDot + 1);
        }

        // Map file extensions to languages
        switch (extension) {
            case "java":
                return "java";
            case "kt":
            case "kts":
                return "kotlin";
            case "js":
            case "jsx":
                return "javascript";
            case "ts":
            case "tsx":
                return "typescript";
            case "py":
                return "python";
            case "cpp":
            case "cc":
            case "cxx":
            case "c++":
                return "cpp";
            case "c":
                return "c";
            case "cs":
                return "csharp";
            case "go":
                return "go";
            case "rs":
                return "rust";
            case "rb":
                return "ruby";
            case "php":
                return "php";
            case "swift":
                return "swift";
            case "scala":
                return "scala";
            case "html":
            case "htm":
                return "html";
            case "css":
                return "css";
            case "scss":
            case "sass":
                return "scss";
            case "xml":
                return "xml";
            case "json":
                return "json";
            case "yaml":
            case "yml":
                return "yaml";
            case "sql":
                return "sql";
            case "sh":
            case "bash":
                return "bash";
            case "md":
            case "markdown":
                return "markdown";
            case "vue":
                return "vue";
            default:
                return "text";
        }
    }

    /**
     * Map language to Prism.js component name
     */
    private static String getPrismLanguage(String language) {
        // Most languages map directly, but some need special handling
        switch (language) {
            case "vue":
                return "markup";  // Vue uses markup highlighting
            case "text":
                return "clike";  // Default to C-like for unknown
            default:
                return language;
        }
    }

    /**
     * Escape HTML special characters
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";

        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Generate fallback HTML when resources cannot be loaded
     */
    private static String generateFallbackHtml(String diffText, String filePath, boolean isDarkTheme) {
        // Use the first generateDiffHtml method which already uses CDN
        return generateDiffHtml(diffText, filePath, isDarkTheme);
    }
}
