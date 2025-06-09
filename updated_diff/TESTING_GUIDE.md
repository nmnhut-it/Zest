# Testing Guide for Updated Diff Viewers

## Prerequisites
- IntelliJ IDEA with the Zest plugin
- A Git repository with some changes (modified, added, or deleted files)
- JCef support enabled in IntelliJ

## Test Scenarios

### 1. Basic Functionality Test
1. Open a project with Git changes
2. Trigger the `ShowGitDiffAction` (through menu or shortcut)
3. **Expected**: SimpleGitDiffViewer dialog opens with a list of changed files

### 2. File Selection Test
1. In the SimpleGitDiffViewer, click on different files in the list
2. **Expected**: 
   - Loading message appears briefly
   - Diff content loads in the browser panel
   - Syntax highlighting is applied based on file type

### 3. DevTools Test
1. With a diff displayed, press F12
2. **Alternative**: Click the "DevTools (F12)" button
3. **Alternative**: Right-click and select "Open DevTools"
4. **Expected**: Chrome DevTools window opens

### 4. Context Menu Test
1. Right-click in the diff viewer area
2. **Expected**: Context menu appears with options:
   - Open DevTools
   - Reload
   - Zoom In/Out/Reset

### 5. Theme Test
1. Switch IntelliJ between light and dark themes
2. Reopen the diff viewer
3. **Expected**: Colors adapt to the current theme
   - Light theme: Light backgrounds, dark text
   - Dark theme: Dark backgrounds, light text

### 6. File Status Tests
Test each file status type:
- **Modified (M)**: Shows additions and deletions
- **Added (A)**: Shows all lines as additions
- **Deleted (D)**: Shows all lines as deletions
- **Renamed (R)**: Shows rename information

### 7. Performance Test
1. Open and close the diff viewer multiple times
2. **Expected**: 
   - Fast opening after first time
   - No memory leaks
   - Browser instances are reused

### 8. Error Handling Test
1. Try to view diff for a file that no longer exists
2. **Expected**: Error message displayed gracefully

### 9. Refresh Test
1. Make changes to files while diff viewer is open
2. Click "Refresh" button
3. **Expected**: File list updates with current changes

### 10. GitHubStyleDiffViewer Test
1. Open a specific file diff using `GitHubStyleDiffViewer.showDiff()`
2. **Expected**: 
   - Single file diff displayed
   - GitHub-style formatting
   - DevTools accessible

## Debugging Tips

### Check Browser Initialization
In the logs, look for:
```
=== getOrCreateSimpleDiffBrowser called ===
Creating new JBCefBrowser instance for simple diff...
JBCefBrowser created successfully for simple diff
```

### Verify HTML Generation
1. Open DevTools (F12)
2. Inspect the Elements tab
3. Verify:
   - Prism.js is loaded
   - CSS classes are applied
   - Syntax highlighting spans are present

### Check Console for Errors
1. Open DevTools Console tab
2. Look for any JavaScript errors
3. Verify "DevTools requested from JS" appears when clicking DevTools button

## Common Issues and Solutions

### Issue: DevTools doesn't open
**Solution**: Ensure JCef debug ports are not blocked:
```java
System.setProperty("ide.browser.jcef.debug.port", "9223");
```

### Issue: Syntax highlighting not working
**Solution**: Check if CDN resources are accessible:
- Prism.js CSS and JS files
- Internet connection required for CDN

### Issue: Browser shows blank
**Solution**: 
1. Check logs for initialization errors
2. Try reloading (right-click → Reload)
3. Verify HTML content in DevTools

### Issue: Performance degradation
**Solution**: 
1. Check if browser instances are being reused
2. Verify no memory leaks in DevTools Memory profiler
3. Ensure dispose() doesn't destroy shared browsers

## Test Checklist

- [ ] SimpleGitDiffViewer opens successfully
- [ ] File list shows all changed files
- [ ] Clicking files loads their diffs
- [ ] Syntax highlighting works for different file types
- [ ] DevTools opens via F12/button/context menu
- [ ] Context menu shows all options
- [ ] Zoom controls work properly
- [ ] Theme switching works correctly
- [ ] All file statuses display properly
- [ ] Refresh updates the file list
- [ ] GitHubStyleDiffViewer works for single files
- [ ] No console errors in DevTools
- [ ] Performance is good with repeated use
- [ ] Error messages display gracefully
- [ ] Browser instances are properly reused
