# Troubleshooting Summary for Black JCef Window

## The Issue
You're seeing a black/blank window when running "Test Diff View". This is typically because JCef is not enabled in your IDE.

## Quick Solution

1. **Run JCef Diagnostics**:
   - Go to: Zest → JCef Diagnostics
   - This will tell you if JCef is enabled

2. **Enable JCef** (Most likely solution):
   - Press `Shift+Shift` (Search Everywhere)
   - Type "Registry" and open it
   - Find `ide.browser.jcef.enabled`
   - Set it to `true`
   - **RESTART IntelliJ IDEA**

3. **After Restart**:
   - Run "Test Diff View" again
   - You should see a light blue test page
   - Right-click to open DevTools

## Accessing DevTools

Since you can't see the right-click menu, try these:

1. **Click in the black window first**, then right-click
2. **Press F12** after clicking in the window
3. **Use external browser**: Navigate to `http://localhost:9222`

## What You Should See

With the current test mode enabled, you should see:
- Light blue background
- "🎉 JCef Test - It's Working!" heading
- Instructions for DevTools
- Code length information

If you still see black after enabling JCef and restarting, check:
- IDE logs: Help → Show Log in Explorer
- Look for "JBCefBrowser created successfully" or error messages

## Next Steps

Once you see the test page:
1. Edit `FloatingCodeWindow.kt`
2. Change line ~291: `val testSimpleHtml = false`
3. Rebuild to see the actual diff viewer

The test mode is currently ON to help diagnose the issue.
