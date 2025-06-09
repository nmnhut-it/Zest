# Setting Up Offline Resources for Diff Viewer

## Required Files

Place these files in `src/main/resources/js/`:

1. **diff.min.js** - jsdiff library
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/jsdiff/5.1.0/diff.min.js

2. **diff2html.min.css** - diff2html CSS
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.47/bundles/css/diff2html.min.css

3. **diff2html-ui.min.js** - diff2html JavaScript
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.47/bundles/js/diff2html-ui.min.js

4. **github.css** - Light theme syntax highlighting
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css

5. **github-dark.css** - Dark theme syntax highlighting
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css

6. **highlight.min.js** (Optional but recommended)
   - Download from: https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js
   - Include language support for Java, Kotlin, etc.

## File Structure

```
src/main/resources/
└── js/
    ├── diff.min.js
    ├── diff2html.min.css
    ├── diff2html-ui.min.js
    ├── github.css
    ├── github-dark.css
    └── highlight.min.js (optional)
```

## Benefits of Offline Resources

1. **Faster Loading**: No network requests needed
2. **Offline Support**: Works without internet connection
3. **Consistent Performance**: No CDN delays
4. **Security**: No external dependencies

## Implementation Details

The `DiffResourceLoader` class:
- Loads resources from the plugin's JAR file
- Embeds all CSS and JS directly into the HTML
- Supports theme switching (dark/light)
- Provides fallback error handling

The `FloatingCodeWindow` class:
- Uses a shared JCef browser instance for performance
- Reuses the browser instead of creating new instances
- Supports DevTools for debugging

## Troubleshooting

If resources don't load:
1. Check the IDE log for resource loading errors
2. Verify files exist in `src/main/resources/js/`
3. Clean and rebuild the project
4. Check file permissions

## Performance Optimization

The implementation includes:
- Shared browser instance (singleton pattern)
- Inline resources (no external requests)
- Minimal HTML generation
- Efficient diff computation

This setup ensures the diff viewer loads quickly and works reliably offline.
