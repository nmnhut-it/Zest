import OpenAI from 'openai';
import fetch from 'node-fetch';

// Configuration
const TOOL_SERVER_URL = 'http://localhost:8000';
const TOOL_SERVER_API_KEY = process.env.OPENAI_TOOL_SERVER_API_KEY || '';

// Initialize OpenAI client
const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY
});

// Define Zest tools for OpenAI
const ZEST_TOOLS = [
  {
    type: 'function',
    function: {
      name: 'explore_code',
      description: 'Explore and understand code using natural language queries. Use this for complex questions about code architecture, functionality, or implementation details.',
      parameters: {
        type: 'object',
        properties: {
          query: {
            type: 'string',
            description: 'Natural language question about the code'
          },
          generate_report: {
            type: 'boolean',
            description: 'Whether to generate a detailed report',
            default: false
          }
        },
        required: ['query']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'search_code',
      description: 'Search for specific code elements, patterns, or implementations',
      parameters: {
        type: 'object',
        properties: {
          query: {
            type: 'string',
            description: 'Search query'
          },
          max_results: {
            type: 'integer',
            description: 'Maximum number of results',
            default: 10
          }
        },
        required: ['query']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'find_by_name',
      description: 'Find classes, methods, or packages by exact name',
      parameters: {
        type: 'object',
        properties: {
          name: {
            type: 'string',
            description: 'Name to search for (case-sensitive)'
          },
          type: {
            type: 'string',
            enum: ['class', 'method', 'package', 'any'],
            description: 'Type of element to find',
            default: 'any'
          }
        },
        required: ['name']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'read_file',
      description: 'Read the contents of a specific file',
      parameters: {
        type: 'object',
        properties: {
          file_path: {
            type: 'string',
            description: 'Path to the file to read'
          }
        },
        required: ['file_path']
      }
    }
  }
];

// Helper function to call Zest tool server
async function callZestTool(endpoint, data) {
  const headers = {
    'Content-Type': 'application/json'
  };
  
  if (TOOL_SERVER_API_KEY) {
    headers['Authorization'] = `Bearer ${TOOL_SERVER_API_KEY}`;
  }

  const response = await fetch(`${TOOL_SERVER_URL}${endpoint}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(data)
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Tool server error: ${response.status} - ${error}`);
  }

  return response.json();
}

// Function to handle tool calls
async function handleToolCall(toolCall) {
  const functionName = toolCall.function.name;
  const args = JSON.parse(toolCall.function.arguments);
  
  console.log(`\n🔧 Calling ${functionName} with args:`, args);
  
  try {
    let result;
    
    switch (functionName) {
      case 'explore_code':
        result = await callZestTool('/explore', args);
        break;
      case 'search_code':
        result = await callZestTool('/search', args);
        break;
      case 'find_by_name':
        result = await callZestTool('/find-by-name', args);
        break;
      case 'read_file':
        result = await callZestTool('/read-file', args);
        break;
      default:
        throw new Error(`Unknown function: ${functionName}`);
    }
    
    // Extract relevant content
    if (result.success === false) {
      return `Error: ${result.error || 'Unknown error'}`;
    }
    
    return result.content || result.summary || JSON.stringify(result, null, 2);
  } catch (error) {
    return `Error: ${error.message}`;
  }
}

// Main example function
async function main() {
  console.log('🤖 OpenAI + Zest Code Analysis Example\n');
  
  // Check tool server connection
  try {
    const health = await fetch(`${TOOL_SERVER_URL}/health`);
    const healthData = await health.json();
    console.log('✓ Tool server status:', healthData.status);
    console.log('✓ Proxy connected:', healthData.proxy_connected);
    
    if (!healthData.proxy_connected) {
      console.warn('\n⚠️  Warning: Proxy not connected. Please start Zest Agent Proxy in IntelliJ.');
    }
  } catch (error) {
    console.error('❌ Failed to connect to tool server:', error.message);
    return;
  }
  
  // Example queries
  const queries = [
    "Can you help me understand how the authentication system works in this codebase? Find the main authentication classes and explain the flow.",
    "What REST API endpoints are available in this application? List them with their purposes.",
    "Find all database models and explain how data persistence is handled.",
    "What design patterns are used in this project? Show me examples."
  ];
  
  // Process first query as example
  const userQuery = queries[0];
  console.log(`\n❓ User Query: ${userQuery}\n`);
  
  // Initial conversation
  const messages = [
    {
      role: 'system',
      content: 'You are a helpful code analysis assistant with access to a codebase through Zest tools. Use the tools to explore and understand the code, then provide clear explanations.'
    },
    {
      role: 'user',
      content: userQuery
    }
  ];
  
  try {
    // Request with tools
    console.log('🔍 Analyzing codebase...\n');
    
    const response = await openai.chat.completions.create({
      model: 'gpt-4',
      messages,
      tools: ZEST_TOOLS,
      tool_choice: 'auto'
    });
    
    const assistantMessage = response.choices[0].message;
    messages.push(assistantMessage);
    
    // Handle tool calls if any
    if (assistantMessage.tool_calls) {
      console.log(`📊 Assistant is making ${assistantMessage.tool_calls.length} tool call(s)...\n`);
      
      // Execute all tool calls
      for (const toolCall of assistantMessage.tool_calls) {
        const result = await handleToolCall(toolCall);
        
        // Add tool response to conversation
        messages.push({
          role: 'tool',
          tool_call_id: toolCall.id,
          content: result
        });
      }
      
      // Get final response from assistant
      console.log('\n📝 Generating analysis...\n');
      
      const finalResponse = await openai.chat.completions.create({
        model: 'gpt-4',
        messages
      });
      
      console.log('=' * 80);
      console.log('\n📋 Assistant\'s Analysis:\n');
      console.log(finalResponse.choices[0].message.content);
      console.log('\n' + '=' * 80);
    } else {
      // Direct response without tools
      console.log('\n📋 Assistant\'s Response:\n');
      console.log(assistantMessage.content);
    }
    
  } catch (error) {
    console.error('\n❌ Error:', error.message);
    if (error.response) {
      console.error('Response:', error.response.data);
    }
  }
}

// Run the example
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(console.error);
}

export { ZEST_TOOLS, handleToolCall, callZestTool };
