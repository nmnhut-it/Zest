import fetch from 'node-fetch';

/**
 * Direct API client for Zest Tool Server
 */
class ZestAPIClient {
  constructor(baseUrl = 'http://localhost:8000', apiKey = null) {
    this.baseUrl = baseUrl;
    this.headers = {
      'Content-Type': 'application/json'
    };
    
    if (apiKey) {
      this.headers['Authorization'] = `Bearer ${apiKey}`;
    }
  }

  async request(endpoint, method = 'GET', data = null) {
    const options = {
      method,
      headers: this.headers
    };

    if (data && method !== 'GET') {
      options.body = JSON.stringify(data);
    }

    const response = await fetch(`${this.baseUrl}${endpoint}`, options);
    
    if (!response.ok) {
      const error = await response.text();
      throw new Error(`API Error ${response.status}: ${error}`);
    }

    return response.json();
  }

  // API methods
  async health() {
    return this.request('/health');
  }

  async exploreCode(query, generateReport = false, config = null) {
    return this.request('/explore', 'POST', {
      query,
      generate_report: generateReport,
      config
    });
  }

  async searchCode(query, maxResults = 10) {
    return this.request('/search', 'POST', {
      query,
      max_results: maxResults
    });
  }

  async findByName(name, type = 'any') {
    return this.request('/find-by-name', 'POST', {
      name,
      type
    });
  }

  async readFile(filePath) {
    return this.request('/read-file', 'POST', {
      file_path: filePath
    });
  }

  async findRelationships(elementId, relationType = null) {
    const payload = { element_id: elementId };
    if (relationType) {
      payload.relation_type = relationType;
    }
    return this.request('/find-relationships', 'POST', payload);
  }

  async findUsages(elementId) {
    return this.request('/find-usages', 'POST', {
      element_id: elementId
    });
  }

  async getClassInfo(className) {
    return this.request('/class-info', 'POST', {
      class_name: className
    });
  }

  async listTools() {
    return this.request('/tools');
  }

  async executeTool(tool, parameters) {
    return this.request('/execute-tool', 'POST', {
      tool,
      parameters
    });
  }

  async getStatus() {
    return this.request('/status');
  }

  async getConfig() {
    return this.request('/config');
  }

  async updateConfig(config) {
    return this.request('/config', 'POST', config);
  }

  async augmentQuery(query) {
    return this.request('/augment', 'POST', { query });
  }
}

// Helper function to print results
function printResult(title, result) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`📌 ${title}`);
  console.log('='.repeat(60));
  
  if (result.success !== false) {
    if (result.content) {
      console.log(result.content);
    } else if (result.summary) {
      console.log(result.summary);
    } else {
      console.log(JSON.stringify(result, null, 2));
    }
  } else {
    console.log(`❌ Error: ${result.error || 'Unknown error'}`);
  }
}

// Main demo function
async function main() {
  console.log('🚀 Zest OpenAI Tool Server - Direct API Example\n');
  
  // Initialize client
  const apiKey = process.env.OPENAI_TOOL_SERVER_API_KEY;
  const client = new ZestAPIClient('http://localhost:8000', apiKey);
  
  try {
    // 1. Check health
    console.log('🏥 Checking server health...');
    const health = await client.health();
    console.log(`Status: ${health.status}`);
    console.log(`Proxy connected: ${health.proxy_connected}`);
    
    if (!health.proxy_connected) {
      console.log('\n⚠️  Warning: Proxy not connected. Please start Zest Agent Proxy in IntelliJ.');
      console.log('Some examples may not work without the proxy connection.\n');
    }
    
    // 2. List available tools
    console.log('\n🔧 Fetching available tools...');
    const tools = await client.listTools();
    console.log(`Total tools: ${tools.count}`);
    console.log('\nFirst 5 tools:');
    tools.tools.slice(0, 5).forEach(tool => {
      console.log(`  - ${tool.name}: ${tool.description}`);
    });
    
    // Only run code exploration examples if proxy is connected
    if (health.proxy_connected) {
      // 3. Explore project architecture
      const exploreResult = await client.exploreCode(
        'What is the overall architecture of this project?',
        true // generate report
      );
      printResult('Project Architecture Analysis', exploreResult);
      
      // 4. Search for REST endpoints
      const searchResult = await client.searchCode('REST endpoints', 5);
      printResult('REST Endpoints Search', searchResult);
      
      // 5. Find specific classes
      const findResult = await client.findByName('Controller', 'class');
      printResult('Controller Classes', findResult);
      
      // 6. Get current configuration
      const config = await client.getConfig();
      console.log('\n⚙️  Current Configuration:');
      console.log(JSON.stringify(config, null, 2));
      
      // 7. Augment a query
      const augmentResult = await client.augmentQuery(
        'How does the authentication work?'
      );
      console.log('\n🔍 Augmented Query:');
      console.log(augmentResult.augmentedQuery || augmentResult);
      
      // 8. Execute a specific tool
      const toolResult = await client.executeTool('list_files', {
        directory: '/',
        maxFiles: 10
      });
      printResult('Root Directory Files', toolResult);
      
      // 9. Get proxy status
      const status = await client.getStatus();
      console.log('\n📊 Proxy Status:');
      console.log(JSON.stringify(status, null, 2));
    }
    
  } catch (error) {
    console.error('\n❌ Error:', error.message);
  }
}

// Export for use in other modules
export { ZestAPIClient };

// Run if executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(console.error);
}
