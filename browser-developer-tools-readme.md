# Browser Developer Tools Integration

This document explains how to use the new developer tools integration for the ZPS Chat embedded browser.

## Overview

The ZPS Chat integration now includes full support for Chrome Developer Tools, allowing you to:

1. Inspect HTML elements and DOM structure
2. View and debug JavaScript code
3. Monitor network requests
4. Analyze console output and errors
5. Profile performance
6. Debug and troubleshoot the chat interface

## Accessing Developer Tools

There are three ways to access the developer tools:

### 1. From the Tools Menu

Go to **Tools → Toggle ZPS Chat Developer Tools**

This will open the developer tools panel in a separate window. The same menu item can be used to close the tools when you're done.

### 2. Using Keyboard Shortcut

Press **Shift+Ctrl+F12** to toggle the developer tools panel.

### 3. From the Browser Context Menu

Right-click anywhere in the ZPS Chat browser window and select **Open DevTools** from the context menu.

## Debugging Features

### Inspecting Chat Interface Elements

We've added a special utility to help debug issues with the chat interface:

1. Go to **Tools → Inspect ZPS Chat Interface**
2. This will open the developer tools and run a diagnostic script
3. Check the console tab to see detailed information about:
   - The chat input element
   - The send button
   - Message container elements
   - Current state of the interface

### Remote Debugging

The embedded browser has remote debugging enabled on port 9222 (default).

You can connect to this port using:
- Chrome's DevTools (chrome://inspect)
- IntelliJ IDEA Ultimate's JavaScript debugger
- Any other tool that supports the Chrome DevTools Protocol

### JavaScript Console API

When debugging chat integration issues, you can also use the browser's console directly:

```javascript
// Get the chat input element
const chatInput = document.getElementById('chat-input');

// Examine its properties
console.log(chatInput);

// Try sending a message programmatically
const sendButton = document.getElementById('send-message-button');
if (sendButton && !sendButton.disabled) {
  sendButton.click();
}
```

## Technical Details

The developer tools integration is built using:

1. JBCefBrowser's built-in DevTools support
2. JCEF (Java Chromium Embedded Framework)
3. Chrome DevTools Protocol

The debug port (9222 by default) can be changed by modifying the registry key:
`ide.browser.jcef.debug.port`

## Troubleshooting Common Issues

### Developer Tools Not Opening

If developer tools don't open when requested:

1. Check if `ide.browser.jcef.contextMenu.devTools.enabled` registry key is set to true
2. Ensure you have a proper JCEF installation with your IDE
3. Try restarting the IDE

### Chat Interface Interaction Issues

If you're having trouble with chat interactions:

1. Use the **Inspect ZPS Chat Interface** tool to diagnose issues
2. Check the console for JavaScript errors
3. Use the Elements panel to verify the structure of chat input and buttons
4. Verify event listeners are properly attached

### Need More Help?

If you encounter issues with developer tools:
1. Check the IDE logs for any errors related to JCEF or DevTools
2. Look for "WebBrowserService", "JCEFBrowserManager", or "DevTools" log entries
