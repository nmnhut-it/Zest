import fetch from 'node-fetch';
import { performance } from 'perf_hooks';

/**
 * Test suite for Zest OpenAI Tool Server
 */
class ServerTester {
  constructor(baseUrl = 'http://localhost:8000', apiKey = '') {
    this.baseUrl = baseUrl;
    this.headers = {
      'Content-Type': 'application/json'
    };
    
    if (apiKey) {
      this.headers['Authorization'] = `Bearer ${apiKey}`;
    }
    
    this.testResults = [];
  }

  async testEndpoint(method, endpoint, data = null, expectedStatus = 200) {
    const testName = `${method} ${endpoint}`;
    console.log(`\n🧪 Testing ${testName}`);
    
    const startTime = performance.now();
    
    try {
      const options = {
        method,
        headers: this.headers
      };
      
      if (data && method !== 'GET') {
        options.body = JSON.stringify(data);
      }
      
      const response = await fetch(`${this.baseUrl}${endpoint}`, options);
      const responseTime = performance.now() - startTime;
      
      const success = response.status === expectedStatus;
      console.log(`   Status: ${response.status} ${success ? '✅' : '❌'} (${responseTime.toFixed(2)}ms)`);
      
      let responseData = null;
      if (response.ok) {
        responseData = await response.json();
        console.log(`   Response: ${JSON.stringify(responseData).substring(0, 100)}...`);
      } else {
        const errorText = await response.text();
        console.log(`   Error: ${errorText}`);
      }
      
      this.testResults.push({
        name: testName,
        success,
        status: response.status,
        responseTime,
        data: responseData
      });
      
      return { success, data: responseData };
      
    } catch (error) {
      console.log(`   ❌ Exception: ${error.message}`);
      this.testResults.push({
        name: testName,
        success: false,
        error: error.message,
        responseTime: performance.now() - startTime
      });
      return { success: false, error: error.message };
    }
  }

  async runBasicTests() {
    console.log('🏃 Running Basic Tests\n');
    
    // Health check
    const health = await this.testEndpoint('GET', '/health');
    if (!health.success) {
      console.log('\n❌ Server not responding. Is it running?');
      return false;
    }
    
    const proxyConnected = health.data?.proxy_connected || false;
    if (!proxyConnected) {
      console.log('\n⚠️  Warning: Proxy not connected. Some tests may fail.');
    }
    
    // Test OpenAPI schema
    await this.testEndpoint('GET', '/openapi.json');
    
    // Test Swagger docs
    await this.testEndpoint('GET', '/docs', null, 200);
    
    // Test tools listing
    await this.testEndpoint('GET', '/tools');
    
    // Test status
    await this.testEndpoint('GET', '/status');
    
    // Test config
    await this.testEndpoint('GET', '/config');
    
    return proxyConnected;
  }

  async runProxyTests() {
    console.log('\n🏃 Running Proxy-Dependent Tests\n');
    
    // Test explore
    await this.testEndpoint('POST', '/explore', {
      query: 'What programming languages are used in this project?',
      generate_report: false
    });
    
    // Test search
    await this.testEndpoint('POST', '/search', {
      query: 'main method',
      max_results: 3
    });
    
    // Test find by name
    await this.testEndpoint('POST', '/find-by-name', {
      name: 'Main',
      type: 'class'
    });
    
    // Test augment
    await this.testEndpoint('POST', '/augment', {
      query: 'How does authentication work?'
    });
    
    // Test execute tool
    await this.testEndpoint('POST', '/execute-tool', {
      tool: 'list_files',
      parameters: { directory: '/' }
    });
    
    // Test read file (may fail if file doesn't exist)
    await this.testEndpoint('POST', '/read-file', {
      file_path: 'README.md'
    });
    
    // Test find relationships
    await this.testEndpoint('POST', '/find-relationships', {
      element_id: 'com.example.Main'
    });
    
    // Test find usages
    await this.testEndpoint('POST', '/find-usages', {
      element_id: 'com.example.Main'
    });
    
    // Test class info
    await this.testEndpoint('POST', '/class-info', {
      class_name: 'com.example.Main'
    });
  }

  async runValidationTests() {
    console.log('\n🏃 Running Validation Tests\n');
    
    // Test missing required fields
    await this.testEndpoint('POST', '/explore', {}, 400);
    
    // Test invalid field types
    await this.testEndpoint('POST', '/search', {
      query: 'test',
      max_results: 'invalid'
    }, 400);
    
    // Test invalid enum values
    await this.testEndpoint('POST', '/find-by-name', {
      name: 'Test',
      type: 'invalid_type'
    }, 400);
  }

  async runAuthTests(wrongApiKey = 'wrong-key') {
    console.log('\n🏃 Running Authentication Tests\n');
    
    // Save original headers
    const originalHeaders = { ...this.headers };
    
    // Test with wrong API key
    this.headers['Authorization'] = `Bearer ${wrongApiKey}`;
    await this.testEndpoint('GET', '/tools', null, 401);
    
    // Test without API key
    delete this.headers['Authorization'];
    await this.testEndpoint('GET', '/tools', null, 401);
    
    // Test with malformed auth header
    this.headers['Authorization'] = 'InvalidFormat';
    await this.testEndpoint('GET', '/tools', null, 401);
    
    // Restore original headers
    this.headers = originalHeaders;
  }

  async runPerformanceTests() {
    console.log('\n⚡ Running Performance Tests\n');
    
    const queries = [
      'What is the main purpose of this application?',
      'Find all database models',
      'List all API endpoints',
      'What design patterns are used?',
      'Find security-related code'
    ];
    
    console.log('📊 Testing concurrent requests...');
    const startTime = performance.now();
    
    const promises = queries.map((query, i) => {
      console.log(`   Query ${i + 1}: ${query.substring(0, 50)}...`);
      return this.testEndpoint('POST', '/search', {
        query,
        max_results: 5
      });
    });
    
    await Promise.all(promises);
    
    const totalTime = performance.now() - startTime;
    console.log(`\n   Total time: ${(totalTime / 1000).toFixed(2)}s`);
    console.log(`   Average per query: ${(totalTime / queries.length / 1000).toFixed(2)}s`);
  }

  generateReport() {
    console.log('\n📊 Test Report\n');
    console.log('='.repeat(60));
    
    const totalTests = this.testResults.length;
    const passedTests = this.testResults.filter(t => t.success).length;
    const failedTests = totalTests - passedTests;
    const avgResponseTime = this.testResults.reduce((sum, t) => sum + t.responseTime, 0) / totalTests;
    
    console.log(`Total Tests: ${totalTests}`);
    console.log(`Passed: ${passedTests} ✅`);
    console.log(`Failed: ${failedTests} ❌`);
    console.log(`Success Rate: ${((passedTests / totalTests) * 100).toFixed(1)}%`);
    console.log(`Average Response Time: ${avgResponseTime.toFixed(2)}ms`);
    
    if (failedTests > 0) {
      console.log('\n❌ Failed Tests:');
      this.testResults
        .filter(t => !t.success)
        .forEach(t => {
          console.log(`  - ${t.name}: ${t.error || `Status ${t.status}`}`);
        });
    }
    
    console.log('\n' + '='.repeat(60));
  }
}

// Main test runner
async function main() {
  console.log('🚀 Zest OpenAI Tool Server Test Suite\n');
  
  // Parse command line arguments
  const args = process.argv.slice(2);
  const url = args.find(arg => arg.startsWith('--url='))?.split('=')[1] || 'http://localhost:8000';
  const apiKey = args.find(arg => arg.startsWith('--api-key='))?.split('=')[1] || '';
  const runPerf = args.includes('--performance');
  const runAuth = args.includes('--auth');
  
  console.log(`Server URL: ${url}`);
  console.log(`API Key: ${apiKey ? 'Set' : 'Not set'}`);
  console.log('');
  
  const tester = new ServerTester(url, apiKey);
  
  try {
    // Run basic tests
    const proxyConnected = await tester.runBasicTests();
    
    // Run validation tests
    await tester.runValidationTests();
    
    // Run auth tests if API key is configured
    if (apiKey && runAuth) {
      await tester.runAuthTests();
    }
    
    // Run proxy-dependent tests if connected
    if (proxyConnected) {
      await tester.runProxyTests();
      
      // Run performance tests if requested
      if (runPerf) {
        await tester.runPerformanceTests();
      }
    }
    
    // Generate report
    tester.generateReport();
    
  } catch (error) {
    console.error('\n❌ Test suite error:', error.message);
  }
}

// Run tests
if (import.meta.url === `file://${process.argv[1]}`) {
  console.log('Usage: node test-server.js [--url=http://localhost:8000] [--api-key=your-key] [--performance] [--auth]\n');
  main().catch(console.error);
}

export { ServerTester };
