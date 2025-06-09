# Comparison: Old vs New Diff Viewer Implementation

## Technology Stack

### Old Implementation (JEditorPane)
- **Rendering Engine**: Swing's `JEditorPane`
- **HTML Support**: Limited HTML 3.2 support
- **CSS Support**: Very limited, inline styles only
- **JavaScript**: Not supported
- **Syntax Highlighting**: Basic, manual HTML generation

### New Implementation (JBCefBrowser)
- **Rendering Engine**: Chromium Embedded Framework (CEF)
- **HTML Support**: Full HTML5 support
- **CSS Support**: Full CSS3 support with external stylesheets
- **JavaScript**: Full ES6+ JavaScript support
- **Syntax Highlighting**: Prism.js with 30+ language support

## Code Structure Comparison

### Old SimpleGitDiffViewer
```java
// Old approach - JEditorPane
diffPane = new JEditorPane();
diffPane.setEditable(false);
diffPane.setContentType("text/html");
diffPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

// Limited styling with HTMLEditorKit
HTMLEditorKit kit = new HTMLEditorKit();
diffPane.setEditorKit(kit);
StyleSheet styleSheet = kit.getStyleSheet();
styleSheet.addRule("body { font-family: monospace; ... }");
```

### New SimpleGitDiffViewer
```java
// New approach - JBCefBrowser
browser = DiffBrowserManager.getOrCreateSimpleDiffBrowser();

// Full HTML with external resources
String html = DiffHtmlGenerator.generateDiffHtml(diffText, filePath, isDarkTheme);
browser.loadHTML(html);

// DevTools support
browser.openDevtools();
```

## HTML Generation

### Old Approach
```java
// Manual HTML building with limited styling
html.append("<tr class='diff-line addition'>");
html.append("<td class='line-number'></td>");
html.append("<td class='line-number'>").append(newLineNumber++).append("</td>");
html.append("<td class='line-content'>").append(escapeHtml(line.substring(1))).append("</td>");
html.append("</tr>");
```

### New Approach
```java
// Sophisticated HTML with CDN resources and syntax highlighting
html.append("<link href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/");
html.append(isDarkTheme ? "prism-tomorrow" : "prism");
html.append(".min.css\" rel=\"stylesheet\" />\n");

// Syntax-highlighted code
html.append("<code class=\"language-" + language + "\">" + escapeHtml(code) + "</code>");
```

## Performance Optimizations

### Old Implementation
- New JEditorPane instance for each dialog
- No resource sharing
- Limited rendering performance

### New Implementation
- Shared browser instances via `DiffBrowserManager`
- Reusable browser components
- Better memory management
- Faster subsequent dialog openings

## User Experience Improvements

### Old Features
- Basic diff display
- Simple HTML rendering
- Limited styling options
- No debugging tools

### New Features
- Chrome DevTools (F12)
- Context menus with zoom controls
- Better syntax highlighting
- Modern GitHub-style UI
- Responsive design
- JavaScript interactivity potential

## Theme Support

### Old Implementation
```java
// Basic color adjustments
styleSheet.addRule(".addition { background-color: #e6ffed; }");
styleSheet.addRule(".deletion { background-color: #ffeef0; }");
```

### New Implementation
```java
// Full theme-aware styling with proper color schemes
boolean isDarkTheme = UIUtil.isUnderDarcula();
String html = DiffHtmlGenerator.generateDiffHtml(diffText, filePath, isDarkTheme);

// Dynamic CSS based on theme
"background-color: " + (isDarkTheme ? "#0d1117" : "#ffffff") + ";\n"
```

## Developer Experience

### Old Approach
- Limited debugging options
- Hard to inspect rendered HTML
- Manual styling required
- No external library support

### New Approach
- Full Chrome DevTools
- Easy HTML/CSS inspection
- External CDN libraries (Prism.js, diff2html)
- Modern web development practices

## Browser Management Pattern

### New Pattern (from FloatingCodeWindow)
```java
public class DiffBrowserManager {
    private static JBCefBrowser sharedSimpleDiffBrowser;
    private static JBCefBrowser sharedGitHubStyleBrowser;
    
    public static JBCefBrowser getOrCreateSimpleDiffBrowser() {
        if (sharedSimpleDiffBrowser == null || sharedSimpleDiffBrowser.isDisposed()) {
            sharedSimpleDiffBrowser = new JBCefBrowser();
        }
        return sharedSimpleDiffBrowser;
    }
}
```

This pattern ensures:
- Single browser instance per viewer type
- Automatic recreation if disposed
- Better resource utilization
- Faster dialog opening times

## Migration Benefits

1. **Better Rendering**: Full modern web standards support
2. **Developer Tools**: Built-in debugging with Chrome DevTools
3. **Performance**: Shared instances reduce memory usage
4. **Maintainability**: Cleaner separation of concerns
5. **Future-Proof**: Can leverage modern web technologies
6. **User Experience**: More responsive and visually appealing
