# Research Agent Implementation Guide

## Overview

The Research Agent provides code search and analysis capabilities within the IntelliJ JCEF browser.

## Architecture

```
Browser (JS)                    Bridge                   IntelliJ (Java)
-------------                   ------                   ---------------
ResearchAgent   →   fileAPI.js   →   JavaScriptBridge   →   FileService
     ↓                                                           ↓
Agent Framework                                          VirtualFile API
```

## Agent Mode Integration

### How It Works

1. **User sends a message in Agent Mode**
2. **Interceptor catches the API request** (interceptor.js)
3. **Calls IDE to build enhanced prompt** with context:
   - Generates keywords using LLM (up to 10)
   - Searches git history (priority, limit 2 results)
   - Searches project files (limit 5 results)
   - Caches results for 5 minutes
4. **Enhanced prompt includes context** from search results
5. **UI shows notification** during context collection

### Key Components

- **AgentModeContextEnhancer**: Orchestrates context collection
- **KeywordGeneratorService**: Uses LLM to extract search keywords
- **OpenWebUIAgentModePromptBuilder**: Builds enhanced prompts
- **agentModeNotifications.js**: Shows UI notifications

### Configuration

The context enhancement is automatic in Agent Mode. No additional configuration needed.

## File Operations

### Implemented Methods

1. **listFiles**
   - Lists files recursively with filtering
   - Excludes: .git, node_modules, build, dist, etc.
   - Supports file extension filtering

2. **readFile**
   - Reads file content with 10MB size limit
   - Returns: `{success: boolean, content: string, error?: string}`

3. **searchInFiles**
   - Text search with regex/whole word/case sensitive options
   - Returns matches with context lines
   - Limits: 1000 results default

4. **findFunctions**
   - Detects 30+ JavaScript function patterns
   - Special support for Cocos2d-x classes
   - Returns: function name, line number, signature, type

5. **getDirectoryTree**
   - Builds tree structure with configurable depth
   - Filters by extensions and exclude patterns

## JavaScript Function Detection

### Supported Patterns

```javascript
// Traditional
function name() {}
async function name() {}
function* generator() {}

// Variables
const name = function() {}
const name = () => {}
const name = async () => {}

// Object methods
obj = {
    method() {},
    method: function() {},
    method: () => {}
}

// Classes
class MyClass {
    constructor() {}
    method() {}
    static method() {}
    async method() {}
    get prop() {}
    set prop(val) {}
}

// Cocos2d-x
var Layer = cc.Layer.extend({
    ctor: function() {},
    onEnter: function() {},
    customMethod: function() {}
});

// Exports
export function name() {}
export const name = () => {}
module.exports.name = function() {}
```

## Usage in Agent UI

### Search Tab Features

1. **Text Search**
   ```javascript
   agent.queueTask({
       type: 'search_text',
       data: {
           searchText: 'TODO',
           folderPath: '/',
           options: {
               caseSensitive: false,
               wholeWord: false,
               regex: false
           }
       }
   });
   ```

2. **Find Function**
   ```javascript
   agent.queueTask({
       type: 'find_syntax',
       data: {
           functionName: 'handleClick',
           folderPath: '/'
       }
   });
   ```

3. **Analyze Usage**
   ```javascript
   agent.queueTask({
       type: 'analyze_usage',
       data: {
           functionName: 'setState',
           folderPath: '/'
       }
   });
   ```

## Extending the Research Agent

### Adding New Search Patterns

1. **Java Side** (FileService.java):
   ```java
   // Add to function patterns list
   Pattern.compile("newPattern\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)")
   ```

2. **JavaScript Side** (researchAgentIntegrated.js):
   ```javascript
   async handle_new_search_type(task) {
       const response = await this.fileAPI.customSearch(
           task.data.path,
           task.data.options
       );
       return this.formatResults(response);
   }
   ```

### Performance Optimization

1. **Caching**: Directory listings cached for 1 minute
2. **File Size Limits**: 10MB max per file
3. **Result Limits**: Configurable max results
4. **Exclude Patterns**: Skip unnecessary directories

## Troubleshooting

### Common Issues

1. **"Unknown role: RESEARCH"**
   - Ensure researchAgentIntegrated.js loads after agentFramework.js
   - Check JCEFBrowserManager.setupJavaScriptBridge()

2. **No search results**
   - Verify project path with getProjectPath()
   - Check exclude patterns aren't too broad
   - Ensure file extensions are included

3. **Performance issues**
   - Reduce search scope with specific paths
   - Use more restrictive file extensions
   - Increase result limits if needed

### Debug Commands

```javascript
// Check if Research Agent is loaded
console.log(window.AgentFramework.AgentRoles.RESEARCH);

// Test file operations directly
await window.intellijBridge.callIDE('listFiles', {
    path: '/',
    maxDepth: 2
});

// Check current project path
await window.intellijBridge.callIDE('getProjectPath', {});
```
