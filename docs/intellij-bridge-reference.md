# IntelliJ Bridge API Reference

## Overview

The IntelliJ Bridge enables bidirectional communication between the JCEF browser and IntelliJ IDEA.

## JavaScript → Java Communication

### Core Method
```javascript
window.intellijBridge.callIDE(action, data)
```

Returns: Promise resolving to response object

### Available Actions

#### Editor Operations
- `getSelectedText` - Get currently selected text
- `insertText` - Insert text at cursor
- `getCurrentFileName` - Get active file name
- `getProjectInfo` - Get project details

#### Code Operations
- `showCodeDiffAndReplace` - Show diff dialog
- `extractCodeFromResponse` - Extract code blocks
- `replaceInFile` - Replace text in file
- `batchReplaceInFile` - Multiple replacements

#### File Operations
- `getProjectPath` - Get project base path
- `listFiles` - List files recursively
- `readFile` - Read file content
- `searchInFiles` - Search text in files
- `findFunctions` - Find JavaScript functions
- `getDirectoryTree` - Get directory structure

#### Git Operations
- `filesSelectedForCommit` - Select files for commit
- `commitWithMessage` - Commit with message
- `gitPush` - Push to remote
- `getFileDiff` - Get file diff

## Chunked Messaging

For large payloads, the bridge automatically chunks messages:

```javascript
// Automatic chunking for large data
const largeData = { /* ... large object ... */ };
await window.intellijBridge.callIDE('action', largeData);
```

### Chunk Format
```javascript
{
    action: 'chunk',
    chunkData: {
        messageId: 'unique-id',
        chunkIndex: 0,
        totalChunks: 5,
        chunk: 'data...'
    }
}
```

## Java → JavaScript Communication

### Executing JavaScript
```java
browserManager.executeJavaScript("console.log('Hello from Java')");
```

### Calling JavaScript Functions
```java
String script = String.format(
    "window.agentCallback('%s', %s)",
    agentId,
    gson.toJson(data)
);
browserManager.executeJavaScript(script);
```

## Adding New Bridge Actions

### 1. Define Action in JavaScriptBridgeActions.java
```java
case "myNewAction":
    return myService.handleAction(data);
```

### 2. Implement Service Method
```java
public String handleAction(JsonObject data) {
    try {
        String param = data.get("param").getAsString();
        
        // Use IntelliJ APIs
        VirtualFile file = project.getBaseDir();
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("result", "data");
        return gson.toJson(response);
    } catch (Exception e) {
        return createErrorResponse(e.getMessage());
    }
}
```

### 3. Call from JavaScript
```javascript
const result = await window.intellijBridge.callIDE('myNewAction', {
    param: 'value'
});
```

## Response Format

### Success Response
```json
{
    "success": true,
    "data": { /* action-specific data */ }
}
```

### Error Response
```json
{
    "success": false,
    "error": "Error message"
}
```

## Security Considerations

1. **Path Validation**: All file paths validated against project root
2. **Read-Only Operations**: No destructive file operations
3. **Size Limits**: 10MB file size limit
4. **Mode Restrictions**: Some actions restricted to "Agent Mode"

## Performance Tips

1. **Use ReadAction**: Wrap read operations in `ReadAction.compute()`
2. **Async Operations**: Use `ApplicationManager.executeOnPooledThread()` for long tasks
3. **Batch Operations**: Combine multiple operations when possible
4. **Cache Results**: Implement caching for repeated operations

## Error Handling

```javascript
try {
    const result = await window.intellijBridge.callIDE('action', data);
    if (result.success) {
        // Handle success
    } else {
        console.error('Action failed:', result.error);
    }
} catch (error) {
    console.error('Bridge error:', error);
}
```

## Debugging

### Enable Logging
```java
private static final Logger LOG = Logger.getInstance(MyClass.class);
LOG.info("Processing action: " + action);
```

### Browser Console
```javascript
// Check if bridge is available
console.log(window.intellijBridge);

// Test bridge communication
window.intellijBridge.callIDE('getProjectInfo', {})
    .then(result => console.log('Project info:', result))
    .catch(error => console.error('Bridge error:', error));
```
