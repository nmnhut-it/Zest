# Agent Proxy and MCP Server Integration

## Overview

This document describes the new Agent Proxy Server and MCP (Model Context Protocol) integration that enables external tools to access IntelliJ's code exploration capabilities.

## Architecture

### 1. Agent Proxy Server (`AgentProxyServer.java`)

The Agent Proxy Server is an HTTP server that runs inside IntelliJ and exposes the `ImprovedToolCallingAutonomousAgent` functionality over the network.

**Key Features:**
- HTTP endpoints for code exploration and query augmentation
- Configurable exploration depth and performance settings
- Auto-port detection (scans ports 8765-8775)
- Health check and status monitoring

**Endpoints:**
- `GET /health` - Health check
- `POST /explore` - Perform code exploration with a query
- `POST /augment` - Augment a query with code context
- `GET/POST /config` - View/update configuration
- `GET /status` - Get server and project status

### 2. Configuration (`AgentProxyConfiguration.java`)

Provides flexible configuration for exploration depth and performance:

**Presets:**
- **Quick Augmentation**: Fast responses (5 tool calls, 15s timeout)
- **Default (Balanced)**: Balanced performance (10 tool calls, 30s timeout)
- **Deep Exploration**: Comprehensive analysis (20 tool calls, 60s timeout)

**Configurable Parameters:**
- `maxToolCalls` - Maximum number of tool calls per exploration
- `maxRounds` - Maximum exploration rounds
- `toolsPerRound` - Tools to execute per round
- `maxSearchResults` - Maximum search results
- `maxFileReads` - Maximum files to read
- `timeoutSeconds` - Overall timeout
- `includeTests` - Include test files in exploration
- `deepExploration` - Enable deep exploration mode

### 3. MCP Server (`mcp-server/index.js`)

A Node.js MCP server that connects to the Agent Proxy and provides tools for AI assistants.

**Tools:**
- `explore_code` - Explore codebase with natural language queries
- `augment_query` - Augment queries with code context
- `get_config` - Get current proxy configuration
- `update_config` - Update proxy configuration
- `status` - Check connection status

**Resources:**
- `zest://status` - Connection status
- `zest://config` - Current configuration

### 4. Enhanced Agent Mode (`AgentModeAugmentationService.java`)

A new service that uses `ImprovedToolCallingAutonomousAgent` for comprehensive query augmentation in Agent Mode and Project Mode.

**Features:**
- Performs deep code exploration using multiple tool calls
- Generates comprehensive exploration reports
- Uses LLM to rewrite queries with full code context
- Fallback to rule-based augmentation when LLM unavailable
- Different augmentation strategies for Agent Mode vs Project Mode

## Usage

### Starting the Agent Proxy Server

1. In IntelliJ, go to **Tools → Zest → Start Agent Proxy Server**
2. Configure the settings:
   - Choose a port (default: 8765)
   - Select a configuration preset
   - Adjust advanced settings if needed
3. Click OK to start the server

### Using the MCP Server

1. Install dependencies:
   ```bash
   cd mcp-server
   npm install
   ```

2. Configure your MCP client (e.g., Claude Desktop):
   ```json
   {
     "mcpServers": {
       "zest": {
         "command": "node",
         "args": ["path/to/mcp-server/index.js"]
       }
     }
   }
   ```

3. The MCP server will automatically scan for the Agent Proxy on startup.

### Example MCP Tool Usage

```typescript
// Explore code
explore_code({
  query: "How does the authentication system work?",
  generateReport: true,
  config: {
    maxToolCalls: 15,
    deepExploration: true
  }
})

// Augment a query
augment_query({
  query: "implement a new payment method"
})
// Returns: "implement a new payment method in the PaymentService class..."
```

## Integration with Existing Modes

### Agent Mode Enhancement

When Agent Mode is active, queries are automatically augmented using the exploration agent:

1. User types a query in the chat
2. The `agentModeEnhanced.js` interceptor catches the request
3. Calls `augmentQueryWithExploration` via the JavaScript bridge
4. Performs comprehensive code exploration
5. Rewrites the query with full code context
6. Sends the augmented query to the LLM

### Project Mode Enhancement

Similar to Agent Mode but with:
- Shallower exploration (fewer tool calls)
- Focus on understanding rather than implementation
- No test file inclusion by default

## Security Considerations

- The proxy server only binds to localhost
- No authentication implemented (assumes local use only)
- File access is restricted to the project directory
- All operations are read-only by default

## Future Enhancements

1. **WebSocket Support**: Real-time streaming of exploration results
2. **Authentication**: Token-based auth for network security
3. **Multiple Project Support**: Proxy multiple projects on different ports
4. **Caching**: Cache exploration results for faster responses
5. **Custom Tools**: Allow plugins to register additional MCP tools

## Troubleshooting

### Proxy Server Won't Start
- Check if port is already in use
- Ensure project is properly indexed
- Check IntelliJ logs for errors

### MCP Server Can't Connect
- Verify proxy is running (check health endpoint)
- Ensure correct port range (8765-8775)
- Check firewall settings

### Slow Exploration
- Use Quick Augmentation preset for faster results
- Reduce maxToolCalls and maxRounds
- Ensure project index is up to date

## API Reference

See the individual class documentation for detailed API information:
- `AgentProxyServer` - HTTP endpoints and request/response formats
- `AgentProxyConfiguration` - Configuration options
- `AgentModeAugmentationService` - Augmentation strategies
- MCP Server README - Tool specifications and examples
