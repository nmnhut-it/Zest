# Fix for Black/Blank JCef Screen

If you're seeing a black or blank JCef window, follow these steps:

## 1. Run JCef Diagnostics First

Go to: **Zest → JCef Diagnostics**

This will show:
- Whether JCef is supported
- System properties status
- Resource availability

## 2. Most Common Solution: Enable JCef

1. Press `Shift+Shift` (Search Everywhere)
2. Type "Registry" and open it
3. Find `ide.browser.jcef.enabled`
4. Set it to `true`
5. **RESTART IntelliJ IDEA** (This is required!)

## 3. Right-Click for DevTools

In the black window:
- **Right-click** anywhere
- Select "Open DevTools" from the menu
- Check the Console tab for errors

Note: On some systems, you need to:
- Right-click and release (Windows)
- Right-click and hold briefly (macOS)

## 4. Alternative DevTools Access

If right-click doesn't work:
1. Open Chrome or Edge
2. Navigate to: `http://localhost:9222`
3. Find your JCef instance and click "inspect"

## 5. Test with Simple HTML

The code is set to show a simple test page first:
- If you see a light blue page with "JCef Test", then JCef works
- If it's still black, JCef is not enabled

## 6. Check Console Output

Look in the IDE log (Help → Show Log in Explorer) for:
```
=== Generated HTML length: [number] ===
=== First 500 chars of HTML: <!DOCTYPE html>...
```

If you see these but the screen is black, JCef needs to be enabled.

## 7. Quick Fixes to Try

1. **Clean and Rebuild**:
   ```
   ./gradlew clean
   ./gradlew buildPlugin
   ```

2. **Invalidate Caches**:
   - File → Invalidate Caches and Restart

3. **Check IDE Version**:
   - JCef requires IntelliJ IDEA 2020.2+
   - Some versions have JCef bugs

## 8. If Nothing Works

1. Check if your OS/IDE combination supports JCef
2. Try updating to the latest IDE version
3. Check firewall/antivirus blocking localhost:9222
4. Report the issue with:
   - IDE version
   - OS version
   - JCef Diagnostics output

## Known Issues

- **macOS**: Some versions require specific Java runtime
- **Linux**: May need additional system libraries
- **Windows**: Antivirus can block JCef initialization

## Success Indicators

When working correctly, you should see:
1. Light blue test page (in test mode)
2. Side-by-side diff view (in production mode)
3. Right-click menu with DevTools option
4. Console logs in DevTools
