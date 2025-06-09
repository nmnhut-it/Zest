# JCef Debugging Guide

## Common Issues and Solutions

### 1. Enable JCef in IntelliJ IDEA

If you see no content in the JCef browser window:

1. **Check Registry Settings**:
   - Press `Shift+Shift` and search for "Registry"
   - Find `ide.browser.jcef.enabled` and set it to `true`
   - Find `ide.browser.jcef.debug.port` and set it to `9222`
   - Restart IntelliJ IDEA

2. **Enable DevTools**:
   - Right-click in the JCef window
   - Select "Open DevTools" from the context menu
   - Or navigate to `http://localhost:9222` in Chrome/Edge

### 2. Testing JCef

Run the test actions in order:

1. **Test Simple JCef** (Zest → Test Simple JCef)
   - Verifies basic JCef functionality
   - Shows a simple HTML page
   - If this doesn't work, JCef is not enabled

2. **Test Diff View** (Zest → Test Diff View)
   - Tests the full diff viewer
   - Uses jsdiff and diff2html libraries

### 3. Debugging Steps

1. **Check Console Output**:
   - Open IDE log: Help → Show Log in Explorer
   - Look for lines starting with:
     - `=== TestDiffViewAction`
     - `=== Generated HTML length`
     - `JCef is supported`

2. **Browser Console**:
   - Right-click in JCef window → Open DevTools
   - Check Console tab for JavaScript errors
   - Look for:
     - `Diff viewer initialized`
     - `Creating diff...`
     - Any error messages

3. **Common Errors**:
   - "JCef is not supported": Enable in Registry
   - Blank window: Check HTML escaping
   - JavaScript errors: Check library loading

### 4. Manual DevTools Access

If the right-click menu doesn't work:
1. Navigate to `chrome://inspect` in Chrome
2. Or go to `http://localhost:9222`
3. Find your JCef instance and click "inspect"

### 5. Troubleshooting Checklist

- [ ] Registry: `ide.browser.jcef.enabled` = true
- [ ] Restart IDE after registry changes
- [ ] Run "Test Simple JCef" first
- [ ] Check IDE logs for errors
- [ ] Check browser console for JavaScript errors
- [ ] Verify HTML is being generated (check console output)
- [ ] Try the simple HTML test mode (set `testSimpleHtml = true`)

### 6. Code Modifications for Debugging

In `FloatingCodeWindow.kt`, line ~280, change:
```kotlin
val testSimpleHtml = false
```
to:
```kotlin
val testSimpleHtml = true
```

This will load a simple test page instead of the diff viewer to isolate issues.
