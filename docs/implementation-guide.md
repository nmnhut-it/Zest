# Zest Browser Integration Implementation Guide

## Architecture Overview

Zest's browser integration uses a multi-layered architecture to provide robust code extraction from AI assistants:

```
┌────────────────┐     ┌────────────────┐     ┌────────────────┐
│  Web Browser   │     │  Java Bridge   │     │  IntelliJ IDE  │
│                │◄───►│                │◄───►│                │
└────────────────┘     └────────────────┘     └────────────────┘
       ▲  │                                           ▲
       │  │                                           │
       │  ▼                                           │
┌─────────────────┐                         ┌──────────────────┐
│ API Integration │                         │ Editor Services  │
└─────────────────┘                         └──────────────────┘
```

## Component Responsibilities

### 1. JavaScript Bridge (intellijBridge.js)

Provides the API for browser scripts to call IntelliJ functions:

```javascript
window.intellijBridge = {
  // Call Java code with action and data parameters
  callIDE: function(action, data) { ... },
  
  // Extract code from API responses
  extractCodeFromResponse: function(data) { ... }
};
```

### 2. Response Parser (responseParser.js)

Extracts code blocks from API responses:

```javascript
// Parse response for code blocks
window.parseResponseForCode = function(responseData) {
  // Identify assistant messages
  // Extract code blocks using regex
  // Return array of code blocks with metadata
};

// Process extracted code blocks
window.processExtractedCode = function(codeBlocks) {
  // Select most appropriate code block
  // Send to IDE via bridge
};
```

### 3. Code Extractor (codeExtractor.js)

Extracts code from DOM elements:

```javascript
// Main extraction function
window.extractCodeToIntelliJ = function(textToReplace) {
  // Find and expand collapsed code blocks
  // Try CodeMirror editors first
  // Fall back to standard code blocks
};
```

### 4. Request Interceptor (interceptor.js)

Intercepts and modifies API requests and responses:

```javascript
// Override fetch to intercept requests
window.fetch = function(input, init) {
  // Enhance requests with project info
  // Handle responses to extract code
  // Fall back to DOM extraction if needed
};
```

## Implementation Details

### JavaScript Bridge Implementation

The bridge uses JBCefJSQuery to enable JavaScript-to-Java communication:

```java
// Create a JS query
jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

// Add a handler for the query
jsQuery.addHandler((query) -> {
    // Process query and return response
});

// Create JavaScript-friendly bridge
String bridgeScript = loadResourceAsString("/js/intellijBridge.js");
String jsQueryInject = jsQuery.inject("request", "successCallback", "errorCallback");
bridgeScript = bridgeScript.replace("[[JBCEF_QUERY_INJECT]]", jsQueryInject);
```

### Code Extraction from API Responses

The response parser uses regular expressions to find code blocks:

```javascript
// Extract code blocks from message content
function extractCodeBlocks(content) {
    const codeBlocks = [];
    const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
    
    let match;
    while ((match = codeBlockRegex.exec(content)) !== null) {
        const language = match[1].trim().toLowerCase() || 'text';
        const code = match[2];
        
        codeBlocks.push({
            language: language,
            code: code,
            fullMatch: match[0]
        });
    }
    
    return codeBlocks;
}
```

### Finding Collapsed Code Blocks

The code extractor identifies and expands collapsed code blocks:

```javascript
// Find and click expand buttons
function findAndClickExpandButtons() {
    // Get all elements with "Expand" text
    const expandElements = Array.from(document.querySelectorAll('*')).filter(element => {
        return element.textContent.trim().toLowerCase() === 'expand';
    });
    
    // Find clickable parents and click them
    expandElements.forEach(element => {
        findClickableParent(element).click();
    });
}
```

### Handling Extracted Code in Java

The JavaScriptBridge processes extracted code:

```java
private boolean handleExtractedCode(String textToReplace, String codeText, String language) {
    // Handle special case for selected text
    if ("__##use_selected_text##__".equals(textToReplace)) {
        String selectedText = getSelectedTextFromEditor();
        if (selectedText != null && !selectedText.isEmpty()) {
            textToReplace = selectedText;
        } else {
            // No text selected, just insert the code
            return insertTextToEditor(codeText);
        }
    }
    
    // Replace text or insert code
    if (textToReplace != null && !textToReplace.isEmpty()) {
        return handleCodeComplete(textToReplace, codeText);
    } else {
        return insertTextToEditor(codeText);
    }
}
```

## Key Algorithms

### 1. API Parsing and DOM Fallback

```
function handleResponse(response):
    try:
        Parse response as JSON
        Extract code blocks from JSON
        If successful:
            Send to IDE
        Else:
            Fall back to DOM extraction
    catch Error:
        Fall back to DOM extraction
```

### 2. Code Block Prioritization

```
function selectCodeBlock(codeBlocks):
    priorityLanguages = ['java', 'javascript', 'typescript', ...]
    
    For each language in priorityLanguages:
        If codeBlocks contains block with language:
            Return that block
    
    Return last code block (if any exist)
```

### 3. Finding and Clicking Expand Buttons

```
function findAndClickExpandButtons():
    Find elements with "Expand" text
    For each expandElement:
        If expandElement is clickable:
            Click it
        Else:
            Find closest clickable parent (up to 3 levels)
            If found:
                Click it
```

## Integration Flow

1. Browser loads page with Zest scripts injected
2. User interacts with AI assistant
3. Request interceptor adds context to outgoing requests
4. Response interceptor processes incoming responses
5. Response parser extracts code from JSON
6. Code is sent to IntelliJ via the bridge
7. IntelliJ shows diff and handles code insertion/replacement

## Error Handling and Fallbacks

The implementation includes multiple layers of error handling:

1. JSON parsing errors -> Fall back to DOM extraction
2. DOM extraction failures -> Log error and offer manual extraction
3. Bridge communication errors -> Log and show appropriate UI feedback

## Performance Considerations

- DOM traversal is expensive, so API parsing is attempted first
- Response parsing uses efficient regex patterns
- Delayed execution (using setTimeout) prevents UI freezing
- Caching mechanism for project info reduces bridge calls

## Security Considerations

- All injected scripts are loaded from local resources
- Code extraction is only performed on user request
- Sensitive information is not shared between contexts
- Bridge calls are authenticated by the JBCefJSQuery mechanism

## Testing Strategies

1. Test API response parsing with various response formats
2. Test DOM extraction with different UI structures
3. Test bridge communication with mock functions
4. Test error handling by simulating various failure modes

## Extending the Implementation

### Adding Support for New Chat Interfaces

1. Identify API endpoints in interceptor.js:
   ```javascript
   function isOpenWebUIEndpoint(url) {
       return url.includes('/api/chat/completions') ||
              url.includes('/api/conversation') ||
              url.includes('/new-endpoint');
   }
   ```

2. Update response parsing for different JSON structures:
   ```javascript
   function parseResponseForCode(responseData) {
       // Handle standard format
       if (responseData.messages) {
           // Process standard format
       }
       // Handle new format
       else if (responseData.newFormat) {
           // Process new format
       }
   }
   ```

### Adding New Bridge Functions

1. Add new method to intellijBridge.js:
   ```javascript
   window.intellijBridge = {
       // Existing functions...
       
       newFunction: function(data) {
           return this.callIDE('newAction', data);
       }
   };
   ```

2. Add handler in JavaScriptBridge.java:
   ```java
   case "newAction":
       String param = data.get("param").getAsString();
       boolean result = handleNewAction(param);
       response.addProperty("success", result);
       break;
   ```

3. Implement the Java functionality:
   ```java
   private boolean handleNewAction(String param) {
       // Implementation
       return true;
   }
   ```