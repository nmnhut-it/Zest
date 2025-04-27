# Zest Browser Integration

This component adds an embedded web browser to the Zest plugin, allowing for bidirectional communication between IntelliJ IDEA and web content.

## Features

- **Embedded Chromium Browser**: Uses JCEF (Java Chromium Embedded Framework) for a full-featured browser experience
- **Bidirectional Communication**: JavaScript can communicate with IntelliJ and vice versa
- **Editor Integration**: Send code to the browser, execute JavaScript, and more
- **AI Assistant Integration**: Special commands to show content in the browser

## How to Use

### Basic Navigation

1. Open the Web Browser tool window from `View -> Tool Windows -> Web Browser`
2. Use the navigation controls to browse the web
3. Enter URLs in the address bar

### Demo Page

1. Go to `Tools -> Load Browser Demo Page` to see a demonstration of the integration features
2. The demo page shows how to use the JavaScript bridge to:
   - Get selected text from the editor
   - Insert text into the editor
   - Get information about the current file

### Sending Content to the Browser

1. Select text in the editor
2. Right-click and choose `Send to Web Browser`
3. The text will appear in the browser (if the current page supports the bridge)

### Executing JavaScript

1. Select JavaScript code in the editor
2. Right-click and choose `Execute in Web Browser`
3. The code will be executed in the browser's JavaScript context

### AI Assistant Integration

The browser is integrated with the AI Assistant. Try these commands:

- "Show this in the browser" - Displays code blocks from the AI response in the browser
- "Go to URL: https://example.com" - Loads the specified URL in the browser

## JavaScript Bridge API

When using the demo page or your own custom pages, you can access the following JavaScript API:

```javascript
// Check if bridge is available
if (window.intellijBridge) {
  // Get selected text from editor
  window.intellijBridge.callIDE('getSelectedText')
    .then(response => {
      if (response.success) {
        console.log('Selected text:', response.result);
      }
    });
  
  // Insert text into editor
  window.intellijBridge.callIDE('insertText', { text: 'Hello from browser!' })
    .then(response => {
      if (response.success) {
        console.log('Text inserted successfully');
      }
    });
  
  // Get current file name
  window.intellijBridge.callIDE('getCurrentFileName')
    .then(response => {
      if (response.success) {
        console.log('Current file:', response.result);
      }
    });
}

// Listen for text sent from IntelliJ
document.addEventListener('ideTextReceived', event => {
  console.log('Received from IDE:', event.detail.text);
});
```

## Testing

To verify that the browser integration is working properly:

1. Go to `Tools -> Test Browser Integration`
2. A simple test will run and show the results

## Troubleshooting

If you encounter issues with the browser integration:

- Make sure JCEF is available in your IntelliJ IDEA installation
- Check the IDE log for error messages related to the browser component
- Try restarting the IDE if the browser fails to load

## Technical Details

The browser integration uses the following components:

- JCEF (Java Chromium Embedded Framework) for the browser engine
- JavaScript bridge for bidirectional communication
- Temporary HTML files for displaying generated content
