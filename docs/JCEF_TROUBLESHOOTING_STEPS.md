# JCef Troubleshooting Steps

## If you see no content in the JCef browser:

### 1. Enable JCef in Registry (MOST IMPORTANT)
- Press `Shift+Shift` (Search Everywhere)
- Type "Registry" and open it
- Search for `ide.browser.jcef.enabled`
- Set it to `true`
- **RESTART IntelliJ IDEA** (This is required!)

### 2. Test Basic JCef First
- After restart, run: Zest → Test Simple JCef
- This should show a simple HTML page
- If this works, JCef is enabled correctly

### 3. Test the Diff View
- Run: Zest → Test Diff View
- Right-click in the window and select "Open DevTools"
- Check the Console tab for any JavaScript errors

### 4. Enable Simple HTML Test Mode
If the diff view is still blank:
1. Edit `FloatingCodeWindow.kt`
2. Find line ~280: `val testSimpleHtml = false`
3. Change to: `val testSimpleHtml = true`
4. Rebuild and test again

### 5. Check Console Logs
Look for these messages in the IDE log:
- `=== generateDiffHtml called ===`
- `Generated HTML length: [number]`
- `Escaped original length: [number]`

### 6. Remote Debugging
If DevTools doesn't open:
- Navigate to `http://localhost:9222` in Chrome/Edge
- Find your JCef instance and click "inspect"

## Common Solutions:

1. **Blank Window**: Registry not enabled - enable and restart
2. **JavaScript Errors**: Check DevTools console
3. **No DevTools**: Use remote debugging URL
4. **Compilation Errors**: Clean and rebuild project

## Build Steps:
```bash
./gradlew clean
./gradlew buildPlugin
```

Then run the plugin and test!
