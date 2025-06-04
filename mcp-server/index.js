import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import fetch from "node-fetch";
import { promisify } from "util";
import { exec } from "child_process";

const execAsync = promisify(exec);

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
        const response = await fetch(`http://localhost:${port}/health`, {
          timeout: 1000
        });
        
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
          const response = await fetch(`${this.proxyUrl}/explore`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(params)
          });
          
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
