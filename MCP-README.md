# Model Context Protocol (MCP) Support in Zest

This document explains how to use the Model Context Protocol (MCP) integration in Zest.

## What is MCP?

The Model Context Protocol (MCP) is a standardized protocol for interaction between AI models and tools. It enables:

- Standardized tool discovery and execution
- Consistent communication format using JSON-RPC
- Improved interoperability between different AI models and tools

With MCP, Zest can communicate with any MCP-compatible LLM service, providing a more powerful and flexible integration with AI models.

## Setting Up MCP

### Configuration

1. Go to **Tools → Configure MCP** to open the MCP configuration dialog.
2. Check the **Enable MCP** checkbox to activate MCP functionality.
3. Enter the URI of your MCP server in the **MCP Server URI** field.
4. Click **OK** to save the configuration.

Default server URI: `http://localhost:8080/mcp`

### Testing the Connection

After configuring MCP, you can test the connection:

1. Go to **Tools → Test MCP Connection**.
2. Zest will attempt to connect to the configured MCP server.
3. The result will be displayed in the AI Assistant window or in a notification.

## Using MCP with Zest

When MCP is enabled, Zest will automatically use the MCP protocol to communicate with AI models. This allows:

1. **Tool Execution**: Zest tools will be exposed to the AI model, allowing the model to discover and use them.
2. **Seamless Integration**: The same UI will work with or without MCP enabled.
3. **Automatic Fallback**: If MCP is enabled but unavailable, Zest will automatically fall back to the standard API.

### How It Works

Zest implements the MCP client using standard HTTP requests to communicate with the MCP server. When you send a prompt:

1. Zest initializes the MCP client if needed
2. Your prompt is sent to the MCP server
3. The server responds with text that may contain tool invocations
4. Zest processes any tool invocations using its existing tool registry
5. The complete response is displayed in the chat interface

## Implementing an MCP Server

If you want to implement your own MCP server, refer to the [Model Context Protocol specification](https://modelcontextprotocol.org/docs/concepts/architecture).

The current implementation uses the MCP Schema version `2024-11-05`.

## Tool Integration

Zest's existing tools are automatically made available to MCP-compatible models. The tools are converted to MCP tool definitions using the `McpToolAdapter` class, which:

1. Converts Zest's `AgentTool` instances to MCP `Tool` objects
2. Generates appropriate JSON schemas for tool parameters
3. Handles execution of tools when requested by the model

## Implementation Details

Zest's MCP implementation consists of several components:

- `McpClient`: Handles communication with MCP servers
- `McpToolAdapter`: Converts between Zest tools and MCP tool definitions
- `McpService`: Project-level service managing MCP functionality
- `McpAgentHandler`: Integrates MCP with the existing agent service
- `McpConfigurationDialog`: UI for configuring MCP settings

These components work together to provide a seamless integration with MCP-compatible models.

## Troubleshooting

If you encounter issues with MCP:

1. **Connection Problems**: Check that the MCP server is running and accessible at the configured URI.
2. **Tool Execution Failures**: Check the IDE log for detailed error messages.
3. **Integration Issues**: Verify that your MCP server implements the correct protocol version.

## Additional Resources

- [Model Context Protocol Documentation](https://modelcontextprotocol.org/docs/concepts/architecture)
- [MCP Specification](https://modelcontextprotocol.org/specification/2024-11-05)
- [MCP Schema Source](https://github.com/modelcontextprotocol/specification)