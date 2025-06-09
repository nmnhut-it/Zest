package com.zps.zest.diff;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

/**
 * Generates HTML for diff viewers with syntax highlighting and theme support
 */
public class DiffHtmlGenerator {
    
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
            case "java": return "java";
            case "kt": case "kts": return "kotlin";
            case "js": case "jsx": return "javascript";
            case "ts": case "tsx": return "typescript";
            case "py": return "python";
            case "cpp": case "cc": case "cxx": case "c++": return "cpp";
            case "c": return "c";
            case "cs": return "csharp";
            case "go": return "go";
            case "rs": return "rust";
            case "rb": return "ruby";
            case "php": return "php";
            case "swift": return "swift";
            case "scala": return "scala";
            case "html": case "htm": return "html";
            case "css": return "css";
            case "scss": case "sass": return "scss";
            case "xml": return "xml";
            case "json": return "json";
            case "yaml": case "yml": return "yaml";
            case "sql": return "sql";
            case "sh": case "bash": return "bash";
            case "md": case "markdown": return "markdown";
            case "vue": return "vue";
            case "jsx": return "jsx";
            case "tsx": return "tsx";
            default: return "text";
        }
    }
    
    /**
     * Map language to Prism.js component name
     */
    private static String getPrismLanguage(String language) {
        // Most languages map directly, but some need special handling
        switch (language) {
            case "vue": return "markup";  // Vue uses markup highlighting
            case "text": return "clike";  // Default to C-like for unknown
            default: return language;
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
}
