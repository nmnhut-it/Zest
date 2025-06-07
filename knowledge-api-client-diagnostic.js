/**
 * Knowledge API Client (Diagnostic Version)
 * Enhanced with debugging, retry logic, and better error handling
 */

const KnowledgeAPI = {
    // Configuration
    config: {
        maxRetries: 3,
        retryDelay: 1000, // ms
        verificationDelay: 2000, // ms - delay before verifying creation
        debug: true
    },

    /**
     * Get auth token from cookie or localStorage
     */
    getAuthToken: function() {
        // Check cookies first
        if (document.cookie) {
            const cookies = document.cookie.split(';');
            for (const cookie of cookies) {
                const [name, value] = cookie.trim().split('=');
                if (name === 'token' || name === 'auth-token' || name === 'authorization') {
                    return decodeURIComponent(value);
                }
            }
        }

        // Check localStorage
        const localToken = localStorage.getItem('token') || 
                          localStorage.getItem('authToken') || 
                          localStorage.getItem('auth_token') || 
                          localStorage.getItem('access_token');
        
        return localToken || null;
    },

    /**
     * Get base URL for API calls
     */
    getBaseUrl: function() {
        return window.location.origin;
    },

    /**
     * Sleep/delay function
     */
    sleep: function(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    },

    /**
     * Make authenticated API request with retry logic
     */
    makeRequest: async function(endpoint, method = 'GET', body = null, retryCount = 0) {
        const authToken = this.getAuthToken();
        if (!authToken) {
            throw new Error('No authentication token found');
        }

        const url = `${this.getBaseUrl()}${endpoint}`;
        const options = {
            method: method,
            headers: {
                'Authorization': `Bearer ${authToken}`,
                'Content-Type': 'application/json'
            }
        };

        if (body && method !== 'GET') {
            options.body = JSON.stringify(body);
        }

        if (this.config.debug) {
            console.log(`[KnowledgeAPI] Request: ${method} ${url}`);
            if (body) {
                console.log('[KnowledgeAPI] Request body:', body);
            }
        }
        
        try {
            const response = await fetch(url, options);
            
            if (!response.ok) {
                const errorText = await response.text();
                let errorDetails;
                try {
                    errorDetails = JSON.parse(errorText);
                } catch {
                    errorDetails = { message: errorText };
                }
                
                console.error(`[KnowledgeAPI] Error ${response.status}:`, errorDetails);
                
                // Retry on 5xx errors or specific 4xx errors
                if ((response.status >= 500 || response.status === 429) && retryCount < this.config.maxRetries) {
                    console.log(`[KnowledgeAPI] Retrying after ${this.config.retryDelay}ms (attempt ${retryCount + 1}/${this.config.maxRetries})`);
                    await this.sleep(this.config.retryDelay * (retryCount + 1));
                    return this.makeRequest(endpoint, method, body, retryCount + 1);
                }
                
                throw new Error(`API request failed: ${response.status} ${response.statusText} - ${JSON.stringify(errorDetails)}`);
            }

            const contentType = response.headers.get('content-type');
            let result;
            if (contentType && contentType.includes('application/json')) {
                result = await response.json();
            } else {
                result = await response.text();
            }
            
            if (this.config.debug) {
                console.log('[KnowledgeAPI] Response:', result);
            }
            
            return result;
        } catch (error) {
            if (error.name === 'TypeError' && error.message.includes('Failed to fetch')) {
                console.error('[KnowledgeAPI] Network error - is the server running?');
                throw new Error('Network error: Unable to connect to server');
            }
            throw error;
        }
    },

    /**
     * Create a new knowledge base with verification
     */
    createKnowledgeBase: async function(name, description, data = null, access_control = null) {
        console.log('[KnowledgeAPI] Creating knowledge base:', name);
        try {
            const payload = {
                name: name,
                description: description
            };
            
            if (data) {
                payload.data = data;
            }
            if (access_control) {
                payload.access_control = access_control;
            }
            
            // Create the knowledge base
            const response = await this.makeRequest('/api/v1/knowledge/create', 'POST', payload);
            
            console.log('[KnowledgeAPI] Knowledge base created with ID:', response.id);
            
            // Wait before verification
            console.log(`[KnowledgeAPI] Waiting ${this.config.verificationDelay}ms before verification...`);
            await this.sleep(this.config.verificationDelay);
            
            // Verify the knowledge base exists
            const verified = await this.verifyKnowledgeBase(response.id);
            
            if (!verified) {
                throw new Error('Failed to verify newly created knowledge base');
            }
            
            return {
                success: true,
                knowledgeId: response.id,
                knowledge: response
            };
        } catch (error) {
            console.error('[KnowledgeAPI] Failed to create knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Verify knowledge base exists with retry logic
     */
    verifyKnowledgeBase: async function(knowledgeId, maxAttempts = 5) {
        console.log(`[KnowledgeAPI] Verifying knowledge base: ${knowledgeId}`);
        
        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Try to get the knowledge base
                const response = await this.makeRequest(`/api/v1/knowledge/${knowledgeId}`, 'GET');
                
                if (response && response.id === knowledgeId) {
                    console.log(`[KnowledgeAPI] Knowledge base verified on attempt ${attempt}`);
                    return true;
                }
            } catch (error) {
                console.log(`[KnowledgeAPI] Verification attempt ${attempt} failed:`, error.message);
                
                if (attempt < maxAttempts) {
                    // Exponential backoff
                    const delay = this.config.retryDelay * Math.pow(2, attempt - 1);
                    console.log(`[KnowledgeAPI] Waiting ${delay}ms before retry...`);
                    await this.sleep(delay);
                } else {
                    // Try alternative verification method
                    console.log('[KnowledgeAPI] Trying alternative verification via list endpoint...');
                    try {
                        const list = await this.makeRequest('/api/v1/knowledge/list', 'GET');
                        const found = list.find(k => k.id === knowledgeId);
                        if (found) {
                            console.log('[KnowledgeAPI] Knowledge base found in list');
                            return true;
                        }
                    } catch (listError) {
                        console.error('[KnowledgeAPI] List verification also failed:', listError);
                    }
                }
            }
        }
        
        return false;
    },

    /**
     * Get knowledge collection details with diagnostics
     */
    getKnowledgeCollection: async function(knowledgeId) {
        console.log(`[KnowledgeAPI] Getting knowledge collection: ${knowledgeId}`);
        try {
            const response = await this.makeRequest(`/api/v1/knowledge/${knowledgeId}`, 'GET');
            
            return {
                success: true,
                collection: response
            };
        } catch (error) {
            console.error('[KnowledgeAPI] Failed to get knowledge collection:', error);
            
            // Try list endpoint as fallback
            try {
                console.log('[KnowledgeAPI] Trying fallback via list endpoint...');
                const list = await this.makeRequest('/api/v1/knowledge/list', 'GET');
                const found = list.find(k => k.id === knowledgeId);
                
                if (found) {
                    console.log('[KnowledgeAPI] Found via list endpoint');
                    return {
                        success: true,
                        collection: found
                    };
                }
            } catch (listError) {
                console.error('[KnowledgeAPI] Fallback also failed:', listError);
            }
            
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * List all knowledge bases
     */
    listKnowledgeBases: async function() {
        console.log('[KnowledgeAPI] Listing all knowledge bases');
        try {
            const response = await this.makeRequest('/api/v1/knowledge/list', 'GET');
            
            console.log(`[KnowledgeAPI] Found ${response.length} knowledge bases`);
            
            return {
                success: true,
                knowledgeBases: response
            };
        } catch (error) {
            console.error('[KnowledgeAPI] Failed to list knowledge bases:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Upload a file with progress tracking
     */
    uploadFile: async function(fileName, content, metadata = {}) {
        console.log(`[KnowledgeAPI] Uploading file: ${fileName} (${content.length} bytes)`);
        try {
            const authToken = this.getAuthToken();
            if (!authToken) {
                throw new Error('No authentication token found');
            }

            const formData = new FormData();
            
            // Determine MIME type
            let mimeType = 'text/plain';
            if (fileName.endsWith('.md')) {
                mimeType = 'text/markdown';
            } else if (fileName.endsWith('.txt')) {
                mimeType = 'text/plain';
            } else if (fileName.endsWith('.pdf')) {
                mimeType = 'application/pdf';
            }
            
            const blob = new Blob([content], { type: mimeType });
            formData.append('file', blob, fileName);
            
            if (Object.keys(metadata).length > 0) {
                formData.append('file_metadata', JSON.stringify(metadata));
            }

            const response = await fetch(`${this.getBaseUrl()}/api/v1/files/`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${authToken}`
                },
                body: formData
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`Upload failed: ${response.status} - ${errorText}`);
            }

            const result = await response.json();
            console.log('[KnowledgeAPI] File uploaded successfully:', result.id);
            
            return {
                success: true,
                fileId: result.id,
                file: result
            };
        } catch (error) {
            console.error('[KnowledgeAPI] Failed to upload file:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Add file to knowledge base with verification
     */
    addFileToKnowledge: async function(knowledgeId, fileId) {
        console.log(`[KnowledgeAPI] Adding file ${fileId} to knowledge base ${knowledgeId}`);
        try {
            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/file/add`, 
                'POST', 
                { file_id: fileId }
            );
            
            console.log('[KnowledgeAPI] File added successfully');
            
            // Verify the file was added
            await this.sleep(500);
            const verification = await this.getKnowledgeCollection(knowledgeId);
            if (verification.success && verification.collection.files) {
                const fileFound = verification.collection.files.some(f => f.id === fileId);
                if (fileFound) {
                    console.log('[KnowledgeAPI] File addition verified');
                } else {
                    console.warn('[KnowledgeAPI] File not found in knowledge base after addition');
                }
            }
            
            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('[KnowledgeAPI] Failed to add file to knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Debug helper - test connection and list available endpoints
     */
    testConnection: async function() {
        console.log('[KnowledgeAPI] Testing connection...');
        
        // Test auth token
        const token = this.getAuthToken();
        console.log('[KnowledgeAPI] Auth token present:', !!token);
        
        if (!token) {
            console.error('[KnowledgeAPI] No auth token found!');
            return false;
        }
        
        // Test basic connectivity
        try {
            const response = await fetch(`${this.getBaseUrl()}/api/v1/knowledge/list`, {
                method: 'GET',
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'Content-Type': 'application/json'
                }
            });
            
            console.log('[KnowledgeAPI] Connection test response:', response.status, response.statusText);
            
            if (response.ok) {
                const data = await response.json();
                console.log('[KnowledgeAPI] Connection successful. Found', data.length, 'knowledge bases');
                return true;
            } else {
                const errorText = await response.text();
                console.error('[KnowledgeAPI] Connection failed:', errorText);
                return false;
            }
        } catch (error) {
            console.error('[KnowledgeAPI] Connection test error:', error);
            return false;
        }
    },

    /**
     * Enable/disable debug mode
     */
    setDebugMode: function(enabled) {
        this.config.debug = enabled;
        console.log('[KnowledgeAPI] Debug mode:', enabled ? 'ENABLED' : 'DISABLED');
    }
};

// Make it globally accessible
window.KnowledgeAPI = KnowledgeAPI;

// Run connection test on load
console.log('[KnowledgeAPI] Diagnostic client loaded. Running connection test...');
KnowledgeAPI.testConnection();
