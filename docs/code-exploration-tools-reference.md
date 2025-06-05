# Code Exploration Tools Reference

This document provides a comprehensive reference for all code exploration tools available through the Zest Agent Proxy and MCP Server.

## Overview

The code exploration tools are designed to help you navigate and understand codebases efficiently. They can be accessed through:
1. **Agent Proxy HTTP API** - Direct HTTP calls to the proxy server
2. **MCP Server** - Model Context Protocol integration for AI assistants
3. **IntelliJ Actions** - Direct use within IntelliJ IDEA

## Core Tools

### 1. search_code
**Description**: Search for code using natural language queries. Uses semantic search to find conceptually related code.

**Parameters**:
- `query` (string, required): Natural language search query
- `maxResults` (integer, optional): Maximum number of results (default: 10, range: 1-50)

**Tips for effective queries**:
- Be specific: "validate user email format" instead of "validation"
- Use technical terms: "authentication" instead of "login"
- Include context: "database connection pooling" instead of "connection"

**Example**:
```json
{
  "tool": "search_code",
  "parameters": {
    "query": "user authentication validation logic",
    "maxResults": 5
  }
}
```

### 2. find_by_name
**Description**: Find code elements by exact name matching. Case-sensitive search for classes, methods, or packages.

**Parameters**:
- `name` (string, required): Name to search for (case-sensitive)
- `type` (string, optional): Type of element - "class", "method", "package", or "any" (default: "any")

**Important**: This tool is case-sensitive!
- ✓ "UserService" will find UserService, UserServiceImpl
- ✗ "userservice" will NOT find UserService

**Example**:
```json
{
  "tool": "find_by_name",
  "parameters": {
    "name": "UserService",
    "type": "class"
  }
}
```

### 3. read_file
**Description**: Read the complete contents of a source file.

**Parameters**:
- `filePath` (string, required): Path to the file (relative to project root)

**Example**:
```json
{
  "tool": "read_file",
  "parameters": {
    "filePath": "src/main/java/com/example/UserService.java"
  }
}
```

### 4. find_relationships
**Description**: Find relationships between code elements (inheritance, usage, calls, etc.).

**Parameters**:
- `elementId` (string, required): Fully qualified class name
- `relationType` (string, optional): Type of relationship to find

**Relationship Types**:
- `EXTENDS`: Classes that extend this class
- `IMPLEMENTS`: Classes that implement this interface
- `USES`: Classes that this class uses
- `USED_BY`: Classes that use this class
- `CALLS`: Methods that this method calls
- `CALLED_BY`: Methods that call this method
- `OVERRIDES`: Methods that override this method
- `OVERRIDDEN_BY`: Methods overridden by this method

**Example**:
```json
{
  "tool": "find_relationships",
  "parameters": {
    "elementId": "com.example.service.UserService",
    "relationType": "USED_BY"
  }
}
```

### 5. find_usages
**Description**: Find all places where a class or method is used in the codebase.

**Parameters**:
- `elementId` (string, required): Fully qualified name of class or method

**Example**:
```json
{
  "tool": "find_usages",
  "parameters": {
    "elementId": "com.example.service.UserService#validateUser"
  }
}
```

### 6. get_class_info
**Description**: Get detailed information about a class including fields, methods, and annotations.

**Parameters**:
- `className` (string, required): Fully qualified class name

**Example**:
```json
{
  "tool": "get_class_info",
  "parameters": {
    "className": "com.example.model.User"
  }
}
```

### 7. find_methods
**Description**: Find all methods in a class, including inherited methods.

**Parameters**:
- `className` (string, required): Fully qualified class name
- `includeInherited` (boolean, optional): Include inherited methods (default: false)

**Example**:
```json
{
  "tool": "find_methods",
  "parameters": {
    "className": "com.example.service.UserService",
    "includeInherited": true
  }
}
```

### 8. find_similar
**Description**: Find code similar to a given element using semantic similarity.

**Parameters**:
- `elementId` (string, required): ID of the element to find similar code for
- `maxResults` (integer, optional): Maximum results (default: 10)

**Example**:
```json
{
  "tool": "find_similar",
  "parameters": {
    "elementId": "com.example.service.UserService#validateEmail",
    "maxResults": 5
  }
}
```

### 9. find_callers
**Description**: Find all methods that call a specific method.

**Parameters**:
- `methodId` (string, required): Fully qualified method name (Class#method)

**Example**:
```json
{
  "tool": "find_callers",
  "parameters": {
    "methodId": "com.example.service.UserService#createUser"
  }
}
```

### 10. find_implementations
**Description**: Find all implementations of an interface or abstract class.

**Parameters**:
- `interfaceName` (string, required): Fully qualified interface/abstract class name

**Example**:
```json
{
  "tool": "find_implementations",
  "parameters": {
    "interfaceName": "com.example.repository.UserRepository"
  }
}
```

### 11. list_files_in_directory
**Description**: List all files in a directory within the project.

**Parameters**:
- `directoryPath` (string, required): Path to directory (relative to project root)

**Example**:
```json
{
  "tool": "list_files_in_directory",
  "parameters": {
    "directoryPath": "src/main/java/com/example/service"
  }
}
```

### 12. get_current_context
**Description**: Get information about the currently open file and cursor position in the IDE.

**Parameters**: None

**Example**:
```json
{
  "tool": "get_current_context",
  "parameters": {}
}
```

## Using Tools via MCP Server

The MCP server provides several ways to use these tools:

### 1. Direct Tool Execution
Use the `execute_tool` command with any tool from the list above:

```typescript
execute_tool({
  tool: "search_code",
  parameters: {
    query: "authentication logic"
  }
})
```

### 2. Tool Shortcuts
Common tools have direct shortcuts for convenience:

```typescript
// Direct search
search_code({
  query: "user validation",
  maxResults: 10
})

// Direct file read
read_file({
  filePath: "src/main/java/com/example/User.java"
})
```

### 3. Full Exploration
Use `explore_code` for comprehensive multi-tool exploration:

```typescript
explore_code({
  query: "How does the authentication system work?",
  generateReport: true,
  config: {
    maxToolCalls: 20,
    deepExploration: true
  }
})
```

## Best Practices

### 1. Start with Discovery
Always begin with `search_code` or `find_by_name` to locate entry points into the codebase.

### 2. Use Appropriate Tools for the Task
- **Understanding concepts**: Use `search_code` with descriptive queries
- **Finding specific elements**: Use `find_by_name` with exact names
- **Exploring relationships**: Use `find_relationships` after finding elements
- **Reading implementations**: Use `read_file` after discovering file paths

### 3. Combine Tools Effectively
Example workflow for understanding a feature:
1. `search_code` to find relevant code
2. `find_by_name` to locate specific classes
3. `read_file` to examine implementations
4. `find_relationships` to understand dependencies
5. `find_usages` to see how it's used

### 4. Balance Source and Test Exploration
Remember to explore both source code and test files:
- Tests often provide the best documentation of expected behavior
- Look for files ending with "Test", "Tests", or "Spec"
- Check `/test/` directories parallel to `/src/` directories

## Configuration Options

When using `explore_code` or configuring the proxy:

| Option | Description | Default |
|--------|-------------|---------|
| `maxToolCalls` | Maximum tool executions | 10 |
| `maxRounds` | Maximum exploration rounds | 3 |
| `toolsPerRound` | Tools per round | 3 |
| `maxSearchResults` | Max search results | 10 |
| `timeoutSeconds` | Overall timeout | 120 |
| `includeTests` | Include test files | false |
| `deepExploration` | Enable deep exploration | false |

## Error Handling

Common errors and solutions:

1. **"Tool not found"**: Check tool name spelling (case-sensitive)
2. **"File not found"**: Verify file path is relative to project root
3. **"Element not found"**: Ensure using fully qualified names
4. **"Timeout"**: Increase timeout or reduce exploration depth
5. **"No results"**: Try different search terms or broader queries
