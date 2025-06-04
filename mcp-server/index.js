import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import fetch from "node-fetch";
import { promisify } from "util";
import { exec } from "child_process";
import AbortController from "abort-controller";

const execAsync = promisify(exec);

// Helper function to create fetch with timeout
async function fetchWithTimeout(url, options = {}, timeoutMs = 30000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal
    });
    clearTimeout(timeout);
    return response;
  } catch (error) {
    clearTimeout(timeout);
    if (error.name === 'AbortError') {
      throw new Error(`Request timeout after ${timeoutMs}ms`);
    }
    throw error;
  }
}

/**
 * Zest MCP Server - Connects to IntelliJ's Agent Proxy for code exploration
 */
class ZestMcpServer {
  constructor() {
    this.proxyUrl = null;
    this.proxyPort = null;
    this.connected = false;
    
    // Create MCP server
    this.server = new McpServer({
      name: "Zest Code Explorer",
      version: "1.0.0",
      description: "IntelliJ code exploration through MCP"
    });
    
    this.setupTools();
    this.setupResources();
  }
  
  /**
   * Scans for available proxy server.
   */
  async findProxy(startPort = 8765, endPort = 8775) {
    console.error(`Scanning for Zest Agent Proxy on ports ${startPort}-${endPort}...`);
    
    for (let port = startPort; port <= endPort; port++) {
      try {
        const response = await fetchWithTimeout(`http://localhost:${port}/health`, {}, 2000);
        
        if (response.ok) {
          const data = await response.json();
          if (data.service === "agent-proxy") {
            this.proxyUrl = `http://localhost:${port}`;
            this.proxyPort = port;
            this.connected = true;
            console.error(`✓ Found Zest Agent Proxy on port ${port}`);
            console.error(`  Project: ${data.project}`);
            return true;
          }
        }
      } catch (e) {
        // Port not available, continue scanning
      }
    }
    
    console.error("✗ No Zest Agent Proxy found. Please start it from IntelliJ IDEA.");
    return false;
  }
  
  /**
   * Setup MCP tools.
   */
  setupTools() {
    // Tool: Explore code with a query
    this.server.tool(
      "explore_code",
      {
        query: z.string().describe("Natural language query about the code"),
        generateReport: z.boolean().optional().describe("Generate detailed report (default: false)"),
        config: z.object({
          maxToolCalls: z.number().optional(),
          includeTests: z.boolean().optional(),
          deepExploration: z.boolean().optional()
        }).optional().describe("Configuration overrides")
      },
      async (params) => {
        if (!this.connected) {
          await this.findProxy();
          if (!this.connected) {
            return {
              content: [{
                type: "text",
                text: "Error: Not connected to Zest Agent Proxy. Please start it from IntelliJ IDEA."
              }]
            };
          }
        }
        
        try {
          const response = await fetchWithTimeout(`${this.proxyUrl}/explore`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(params)
          }, 3600000);  // 1 hour timeout for exploration
          
          const result = await response.json();
          
          if (result.success) {
            let text = `Code exploration completed for: "${params.query}"\n\n`;
            
            if (result.report) {
              text += `Summary:\n${result.report.summary}\n\n`;
              text += `Discovered ${result.report.discoveredElements?.length || 0} code elements\n`;
              
              if (result.report.structuredContext) {
                text += `\nContext:\n${result.report.structuredContext}`;
              }
            } else {
              text += result.summary || "Exploration completed";
            }
            
            return {
              content: [{ type: "text", text }]
            };
          } else {
            return {
              content: [{
                type: "text",
                text: `Error: ${result.error || "Unknown error occurred"}`
              }]
            };
          }
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error connecting to proxy: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Tool: Execute individual exploration tool
    this.server.tool(
      "execute_tool",
      {
        tool: z.string().describe("Name of the tool to execute"),
        parameters: z.object({}).passthrough().describe("Tool-specific parameters")
      },
      async (params) => {
        if (!this.connected) {
          await this.findProxy();
          if (!this.connected) {
            return {
              content: [{
                type: "text",
                text: "Error: Not connected to Zest Agent Proxy."
              }]
            };
          }
        }
        
        try {
          const response = await fetchWithTimeout(`${this.proxyUrl}/execute-tool`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(params)
          }, 180000);  // 3 minutes timeout for individual tools
          
          const result = await response.json();
          
          if (result.success) {
            return {
              content: [{
                type: "text",
                text: result.content || "Tool executed successfully"
              }]
            };
          } else {
            return {
              content: [{
                type: "text",
                text: `Error: ${result.error || "Tool execution failed"}`
              }]
            };
          }
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Tool: List available tools
    this.server.tool(
      "list_tools",
      {},
      async () => {
        if (!this.connected) {
          await this.findProxy();
          if (!this.connected) {
            return {
              content: [{
                type: "text",
                text: "Error: Not connected to Zest Agent Proxy."
              }]
            };
          }
        }
        
        try {
          const response = await fetch(`${this.proxyUrl}/tools`);
          const data = await response.json();
          
          let text = `Available Code Exploration Tools (${data.count} total):\n\n`;
          
          for (const tool of data.tools) {
            text += `### ${tool.name}\n`;
            text += `${tool.description}\n`;
            text += `Parameters: ${JSON.stringify(tool.parameters.properties || {}, null, 2)}\n\n`;
          }
          
          return {
            content: [{ type: "text", text }]
          };
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Individual tool shortcuts for common operations
    this.server.tool(
      "search_code",
      {
        query: z.string().describe("Natural language search query"),
        maxResults: z.number().optional().describe("Maximum results (default: 10)")
      },
      async (params) => this.executeToolShortcut("search_code", params)
    );
    
    this.server.tool(
      "find_by_name",
      {
        name: z.string().describe("Class, method, or package name (case-sensitive)"),
        type: z.enum(["class", "method", "package", "any"]).optional()
      },
      async (params) => this.executeToolShortcut("find_by_name", params)
    );
    
    this.server.tool(
      "read_file",
      {
        filePath: z.string().describe("Path to the file to read")
      },
      async (params) => this.executeToolShortcut("read_file", params)
    );
    
    this.server.tool(
      "find_relationships",
      {
        elementId: z.string().describe("Fully qualified class name"),
        relationType: z.enum([
          "EXTENDS", "IMPLEMENTS", "USES", "USED_BY", 
          "CALLS", "CALLED_BY", "OVERRIDES", "OVERRIDDEN_BY"
        ]).optional()
      },
      async (params) => this.executeToolShortcut("find_relationships", params)
    );
    
    this.server.tool(
      "find_usages",
      {
        elementId: z.string().describe("Class or method to find usages of")
      },
      async (params) => this.executeToolShortcut("find_usages", params)
    );
    
    this.server.tool(
      "get_class_info",
      {
        className: z.string().describe("Fully qualified class name")
      },
      async (params) => this.executeToolShortcut("get_class_info", params)
    );
    
    // Keep existing tools...
    
    // Tool: Augment query with code context
    this.server.tool(
      "augment_query",
      {
        query: z.string().describe("User query to augment with code context")
      },
      async (params) => {
        if (!this.connected) {
          await this.findProxy();
          if (!this.connected) {
            return {
              content: [{
                type: "text",
                text: "Error: Not connected to Zest Agent Proxy. Please start it from IntelliJ IDEA."
              }]
            };
          }
        }
        
        try {
          const response = await fetch(`${this.proxyUrl}/augment`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(params)
          });
          
          const result = await response.json();
          
          if (result.success) {
            return {
              content: [{
                type: "text",
                text: result.augmentedQuery
              }]
            };
          } else {
            return {
              content: [{
                type: "text",
                text: `Error: ${result.error || "Unknown error occurred"}`
              }]
            };
          }
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error connecting to proxy: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Tool: Get proxy configuration
    this.server.tool(
      "get_config",
      {},
      async () => {
        if (!this.connected) {
          return {
            content: [{
              type: "text",
              text: "Not connected to proxy"
            }]
          };
        }
        
        try {
          const response = await fetch(`${this.proxyUrl}/config`);
          const config = await response.json();
          
          return {
            content: [{
              type: "text",
              text: `Current configuration:\n${JSON.stringify(config, null, 2)}`
            }]
          };
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Tool: Update proxy configuration
    this.server.tool(
      "update_config",
      {
        maxToolCalls: z.number().optional(),
        maxRounds: z.number().optional(),
        includeTests: z.boolean().optional(),
        deepExploration: z.boolean().optional(),
        timeoutSeconds: z.number().optional()
      },
      async (params) => {
        if (!this.connected) {
          return {
            content: [{
              type: "text",
              text: "Not connected to proxy"
            }]
          };
        }
        
        try {
          const response = await fetch(`${this.proxyUrl}/config`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(params)
          });
          
          const result = await response.json();
          
          return {
            content: [{
              type: "text",
              text: `Configuration updated:\n${JSON.stringify(result.config, null, 2)}`
            }]
          };
        } catch (error) {
          return {
            content: [{
              type: "text",
              text: `Error: ${error.message}`
            }]
          };
        }
      }
    );
    
    // Tool: Check connection status
    this.server.tool(
      "status",
      {},
      async () => {
        if (!this.connected) {
          await this.findProxy();
        }
        
        if (!this.connected) {
          return {
            content: [{
              type: "text",
              text: "Not connected to any Zest Agent Proxy"
            }]
          };
        }
        
        try {
          const response = await fetch(`${this.proxyUrl}/status`);
          const status = await response.json();
          
          let text = `Connected to Zest Agent Proxy\n`;
          text += `Port: ${this.proxyPort}\n`;
          text += `Project: ${status.project}\n`;
          text += `Project Path: ${status.projectPath}\n`;
          text += `Indexed: ${status.indexed ? "Yes" : "No"}\n`;
          
          return {
            content: [{ type: "text", text }]
          };
        } catch (error) {
          this.connected = false;
          return {
            content: [{
              type: "text",
              text: `Connection lost: ${error.message}`
            }]
          };
        }
      }
    );
  }
  
  /**
   * Helper method to execute tool shortcuts.
   */
  async executeToolShortcut(toolName, parameters) {
    return this.server.callTool("execute_tool", {
      tool: toolName,
      parameters: parameters
    });
  }
  
  /**
   * Setup MCP resources.
   */
  setupResources() {
    // Resource: Connection status
    this.server.resource("status", {
      name: "Connection Status",
      description: "Current connection status to Zest Agent Proxy",
      mimeType: "text/plain"
    }, async () => {
      if (!this.connected) {
        return {
          contents: [{
            uri: "zest://status",
            mimeType: "text/plain",
            text: "Not connected to Zest Agent Proxy"
          }]
        };
      }
      
      try {
        const response = await fetch(`${this.proxyUrl}/status`);
        const status = await response.json();
        
        return {
          contents: [{
            uri: "zest://status",
            mimeType: "text/plain",
            text: `Connected to project: ${status.project} (${status.indexed ? "indexed" : "not indexed"})`
          }]
        };
      } catch (error) {
        return {
          contents: [{
            uri: "zest://status",
            mimeType: "text/plain",
            text: `Connection error: ${error.message}`
          }]
        };
      }
    });
    
    // Resource: Configuration
    this.server.resource("config", {
      name: "Proxy Configuration",
      description: "Current configuration of the Zest Agent Proxy",
      mimeType: "application/json"
    }, async () => {
      if (!this.connected) {
        return {
          contents: [{
            uri: "zest://config",
            mimeType: "application/json",
            text: "{}"
          }]
        };
      }
      
      try {
        const response = await fetch(`${this.proxyUrl}/config`);
        const config = await response.json();
        
        return {
          contents: [{
            uri: "zest://config",
            mimeType: "application/json",
            text: JSON.stringify(config, null, 2)
          }]
        };
      } catch (error) {
        return {
          contents: [{
            uri: "zest://config",
            mimeType: "application/json",
            text: JSON.stringify({ error: error.message })
          }]
        };
      }
    });
  }
  
  /**
   * Starts the MCP server.
   */
  async start() {
    // Try to connect to proxy on startup
    await this.findProxy();
    
    // Create stdio transport
    const transport = new StdioServerTransport();
    
    // Connect and start serving
    await this.server.connect(transport);
    
    console.error("Zest MCP Server started");
    console.error(`Connected to proxy: ${this.connected ? "Yes" : "No"}`);
  }
}

// Start the server
const server = new ZestMcpServer();
server.start().catch(console.error);
