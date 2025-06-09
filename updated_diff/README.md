# Updated Diff Viewer Package

This directory contains the updated versions of the diff viewer classes from `com.zps.zest.diff` package, upgraded to use `JBCefBrowser` (Chrome Embedded Framework) instead of `JEditorPane` for better HTML/CSS/JS rendering.

## Changes Made

### 1. **New Classes Added**
- **`DiffBrowserManager.java`**: Manages shared browser instances for better performance (similar pattern to `FloatingCodeWindow`)
- **`DiffHtmlGenerator.java`**: Generates HTML with syntax highlighting, theme support, and modern styling

### 2. **Updated Classes**
- **`SimpleGitDiffViewer.java`**: Now uses `JBCefBrowser` instead of `JEditorPane`
- **`GitHubStyleDiffViewer.java`**: Now uses `JBCefBrowser` instead of `JEditorPane`
- **`DiffThemeUtil.java`**: Unchanged but included for completeness
- **`ShowGitDiffAction.java`**: Unchanged but included for completeness

## Key Improvements

1. **Better Rendering**: JBCefBrowser provides full HTML5/CSS3/JavaScript support
2. **Syntax Highlighting**: Uses Prism.js for proper syntax highlighting with language detection
3. **DevTools Support**: F12 or right-click to open Chrome DevTools for debugging
4. **Performance**: Shared browser instances reduce memory usage and initialization time
5. **Modern UI**: Better styling with proper theme support (dark/light)
6. **Context Menus**: Right-click menus with zoom controls and DevTools access
7. **Responsive Design**: Better handling of different screen sizes and content

## Integration Steps

1. Replace the existing files in `com.zps.zest.diff` package with these updated versions
2. Ensure all imports are resolved (especially `com.zps.zest.browser.GitService`)
3. Add the new classes (`DiffBrowserManager` and `DiffHtmlGenerator`) to the package
4. Test the functionality:
   - Open a project with Git changes
   - Use the Git Diff Viewer action
   - Verify that diffs are displayed correctly
   - Test DevTools (F12 or right-click)
   - Test both light and dark themes

## Dependencies

The updated code requires:
- IntelliJ IDEA with JCef support
- The `com.zps.zest.browser.GitService` class (already referenced in original code)
- Standard IntelliJ Platform APIs

## Features Maintained

All original features are preserved:
- List of changed files
- File status indicators (Modified, Added, Deleted, etc.)
- Async diff loading
- Error handling
- Refresh functionality
- Integration with IntelliJ's diff viewer

## Additional Features

- Chrome DevTools access
- Context menus
- Zoom controls
- Better syntax highlighting
- Improved performance with shared browser instances
- Modern, GitHub-style diff rendering

## Notes

- The shared browser instances are managed by `DiffBrowserManager` to avoid creating multiple browser instances
- HTML generation is centralized in `DiffHtmlGenerator` for consistency
- The browsers are not disposed when dialogs close, they're reused for better performance
- DevTools can be accessed via F12 key or the DevTools button/context menu
