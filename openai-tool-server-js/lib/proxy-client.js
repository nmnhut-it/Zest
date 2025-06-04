import fetch from 'node-fetch';

/**
 * Client for interacting with Zest Agent Proxy
 */
export class ZestProxyClient {
  constructor(proxyUrl = null) {
    this.proxyUrl = proxyUrl;
    this.proxyPort = null;
    this.connected = false;
    this.timeout = 3600000; // 1 hour default timeout
  }

  /**
   * Scan for available proxy server
   */
  async findProxy(startPort = 8765, endPort = 8775) {
    if (this.proxyUrl) {
      // Try the configured URL first
      try {
        const response = await this.fetchWithTimeout(`${this.proxyUrl}/health`, {}, 2000);
        if (response.ok) {
          const data = await response.json();
          if (data.service === 'agent-proxy') {
            this.connected = true;
            console.log(`✓ Connected to configured Zest Agent Proxy at ${this.proxyUrl}`);
            return true;
          }
        }
      } catch (error) {
        console.warn(`Failed to connect to configured proxy at ${this.proxyUrl}`);
      }
    }

    // Scan ports
    console.log(`Scanning for Zest Agent Proxy on ports ${startPort}-${endPort}...`);
    
    for (let port = startPort; port <= endPort; port++) {
      try {
        const response = await this.fetchWithTimeout(
          `http://localhost:${port}/health`,
          {},
          2000
        );
        
        if (response.ok) {
          const data = await response.json();
          if (data.service === 'agent-proxy') {
            this.proxyUrl = `http://localhost:${port}`;
            this.proxyPort = port;
            this.connected = true;
            console.log(`✓ Found Zest Agent Proxy on port ${port}`);
            console.log(`  Project: ${data.project}`);
            return true;
          }
        }
      } catch (error) {
        // Port not available, continue scanning
      }
    }
    
    console.warn('✗ No Zest Agent Proxy found. Please start it from IntelliJ IDEA.');
    return false;
  }

  /**
   * Fetch with timeout support
   */
  async fetchWithTimeout(url, options = {}, timeoutMs = null) {
    const timeout = timeoutMs || this.timeout;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeout);
    
    try {
      const response = await fetch(url, {
        ...options,
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          ...options.headers
        }
      });
      clearTimeout(timeoutId);
      return response;
    } catch (error) {
      clearTimeout(timeoutId);
      if (error.name === 'AbortError') {
        throw new Error(`Request timeout after ${timeout}ms`);
      }
      throw error;
    }
  }

  /**
   * Make a request to the proxy
   */
  async request(endpoint, method = 'GET', data = null) {
    if (!this.connected) {
      await this.findProxy();
      if (!this.connected) {
        throw new Error('Not connected to Zest Agent Proxy');
      }
    }

    const options = {
      method,
      headers: {
        'Content-Type': 'application/json'
      }
    };

    if (data && method !== 'GET') {
      options.body = JSON.stringify(data);
    }

    try {
      const response = await this.fetchWithTimeout(
        `${this.proxyUrl}${endpoint}`,
        options
      );

      if (!response.ok) {
        const error = await response.text();
        throw new Error(`Proxy error: ${response.status} - ${error}`);
      }

      return await response.json();
    } catch (error) {
      // Reset connection on error
      if (error.message.includes('ECONNREFUSED')) {
        this.connected = false;
      }
      throw error;
    }
  }

  // API methods
  async health() {
    return this.request('/health');
  }

  async status() {
    return this.request('/status');
  }

  async listTools() {
    return this.request('/tools');
  }

  async explore(params) {
    return this.request('/explore', 'POST', params);
  }

  async executeTool(params) {
    return this.request('/execute-tool', 'POST', params);
  }

  async augment(params) {
    return this.request('/augment', 'POST', params);
  }

  async getConfig() {
    return this.request('/config');
  }

  async updateConfig(params) {
    return this.request('/config', 'POST', params);
  }
}
