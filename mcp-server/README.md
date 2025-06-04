# Zest MCP Server

MCP (Model Context Protocol) server that connects to IntelliJ's Zest Agent Proxy for intelligent code exploration.

## Overview

This MCP server provides tools for:
- **Code Exploration**: Natural language queries to explore your codebase
- **Query Augmentation**: Enhance queries with relevant code context
- **Direct Tool Access**: Execute individual code exploration tools
- **Configuration Management**: Adjust exploration depth and performance

## Features

### Complete Tool Access
Access all 12+ code exploration tools individually or through comprehensive exploration:
- `search_code` - Semantic code search
- `find_by_name` - Find elements by name
- `read_file` - Read source files
- `find_relationships` - Explore code relationships
- `find_usages` - Find where code is used
- `get_class_info` - Get class details
- And many more...

### Increased Timeouts
- Default timeout: 120 seconds (2 minutes)
- Deep exploration: 180 seconds (3 minutes)
- Quick mode: 60 seconds (1 minute)

## Setup

### 1. Start the Agent Proxy in IntelliJ

1. In IntelliJ IDEA with your project open, go to **Tools → Zest → Start Agent Proxy Server**
2. Configure the proxy settings:
   - **Port**: Default is 8765
   - **Configuration**: Choose between:
     - **Quick Augmentation**: Fast responses, shallow exploration
     - **Default (Balanced)**: Good balance of speed and thoroughness
     - **Deep Exploration**: Comprehensive but slower
3. Click OK to start the proxy

### 2. Install MCP Server Dependencies

```bash
cd mcp-server
npm install
```

### 3. Configure MCP Client

Add to your MCP client configuration (e.g., Claude Desktop):

```json
{
  "mcpServers": {
    "zest": {
      "command": "node",
      "args": ["path/to/mcp-server/index.js"],
      "env": {}
    }
  }
}
```

## Usage

### Available Tools

#### 1. `explore_code`
Comprehensive code exploration with multiple tool calls.

```typescript
explore_code({
  query: "How does the authentication system work?",
  generateReport: true,
  config: {
    maxToolCalls: 15,
    includeTests: true,
    deepExploration: true
  }
})
```

#### 2. `execute_tool`
Execute any individual code exploration tool.

```typescript
execute_tool({
  tool: "search_code",
  parameters: {
    query: "user validation logic",
    maxResults: 10
  }
})
```

#### 3. `list_tools`
Get a list of all available code exploration tools with descriptions.

```typescript
list_tools()
// Returns detailed information about all 12+ available tools
```

#### 4. Individual Tool Shortcuts
Direct access to common tools:

```typescript
// Semantic search
search_code({
  query: "authentication logic",
  maxResults: 10
})

// Find by name (case-sensitive)
find_by_name({
  name: "UserService",
  type: "class"
})

// Read file
read_file({
  filePath: "src/main/java/com/example/UserService.java"
})

// Find relationships
find_relationships({
  elementId: "com.example.UserService",
  relationType: "USED_BY"
})

// Find usages
find_usages({
  elementId: "com.example.UserService#validateUser"
})

// Get class info
get_class_info({
  className: "com.example.model.User"
})
```

#### 5. `augment_query`
Augment a query with relevant code context for better LLM responses.

```typescript
augment_query({
  query: "implement a new payment method"
})
// Returns: "implement a new payment method in the PaymentService class 
// following the existing PaymentMethod interface pattern..."
```

#### 6. `status`
Check connection status and project information.

```typescript
status()
// Returns: connection status, project name, index status
```

#### 7. `get_config` / `update_config`
View and modify proxy configuration.

```typescript
get_config()
// Returns current configuration

update_config({
  maxToolCalls: 5,
  timeoutSeconds: 20,
  includeTests: false
})
```

### Available Resources

- `zest://status` - Current connection status
- `zest://config` - Current proxy configuration

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `maxToolCalls` | Maximum number of tool calls per exploration | 10 |
| `maxRounds` | Maximum exploration rounds | 3 |
| `toolsPerRound` | Tools to execute per round | 3 |
| `maxSearchResults` | Maximum search results to return | 10 |
| `maxFileReads` | Maximum files to read | 5 |
| `timeoutSeconds` | Overall timeout for exploration | 30 |
| `includeTests` | Include test files in exploration | false |
| `deepExploration` | Enable deep exploration mode | false |

## Examples

### Example 1: Understanding Code Architecture
```
User: Use explore_code to understand how the user service handles authentication