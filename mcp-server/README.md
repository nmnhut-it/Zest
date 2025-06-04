# Zest MCP Server

MCP (Model Context Protocol) server that connects to IntelliJ's Zest Agent Proxy for intelligent code exploration.

## Overview

This MCP server provides tools for:
- **Code Exploration**: Natural language queries to explore your codebase
- **Query Augmentation**: Enhance queries with relevant code context
- **Configuration Management**: Adjust exploration depth and performance

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
Explore your codebase with natural language queries.

```typescript
explore_code({
  query: "How does the authentication system work?",
  generateReport: true,  // Optional: generate detailed report
  config: {              // Optional: override configuration
    maxToolCalls: 15,
    includeTests: true,
    deepExploration: true
  }
})
```

#### 2. `augment_query`
Augment a query with relevant code context for better LLM responses.

```typescript
augment_query({
  query: "implement a new payment method"
})
// Returns: "implement a new payment method in the PaymentService class 
// following the existing PaymentMethod interface pattern..."
```

#### 3. `status`
Check connection status and project information.

```typescript
status()
// Returns: connection status, project name, index status
```

#### 4. `get_config` / `update_config`
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