/**
 * Knowledge API Client (Corrected Version)
 * Handles knowledge base operations through the OpenWebUI API
 */

const KnowledgeAPI = {
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
     * Make authenticated API request
     */
    makeRequest: async function(endpoint, method = 'GET', body = null) {
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

        console.log(`Knowledge API Request: ${method} ${url}`);
        if (body) {
            console.log('Request body:', body);
        }

        const response = await fetch(url, options);

        if (!response.ok) {
            const errorText = await response.text();
            console.error(`Knowledge API Error: ${response.status} - ${errorText}`);
            throw new Error(`API request failed: ${response.status} ${response.statusText}`);
        }

        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
            return await response.json();
        }

        return await response.text();
    },

    /**
     * Create a new knowledge base
     * Correct endpoint: POST /api/v1/knowledge/create
     */
    createKnowledgeBase: async function(name, description, data = null, access_control = null) {
        console.log('Creating knowledge base:', name, description);
        try {
            const payload = {
                name: name,
                description: description
            };

            // Add optional fields if provided
            if (data) {
                payload.data = data;
            }
            if (access_control) {
                payload.access_control = access_control;
            }

            const response = await this.makeRequest('/api/v1/knowledge/create', 'POST', payload);

            console.log('Create knowledge base response:', response);

            return {
                success: true,
                knowledgeId: response.id,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to create knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Get list of all knowledge bases
     * Correct endpoint: GET /api/v1/knowledge/list
     */
    listKnowledgeBases: async function() {
        try {
            const response = await this.makeRequest('/api/v1/knowledge/list', 'GET');

            return {
                success: true,
                knowledgeBases: response
            };
        } catch (error) {
            console.error('Failed to list knowledge bases:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Get knowledge collection details by ID
     * Correct endpoint: GET /api/v1/knowledge/{id}
     */
    getKnowledgeCollection: async function(knowledgeId) {
        try {
            const response = await this.makeRequest(`/api/v1/knowledge/${knowledgeId}`, 'GET');

            return {
                success: true,
                collection: response
            };
        } catch (error) {
            console.error('Failed to get knowledge collection:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Upload a file (multipart form data)
     * Correct endpoint: POST /api/v1/files/
     */
    uploadFile: async function(fileName, content, metadata = {}) {
        try {
            const authToken = this.getAuthToken();
            if (!authToken) {
                throw new Error('No authentication token found');
            }

            const formData = new FormData();

            // Determine MIME type based on file extension
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

            // Add metadata if provided
            if (Object.keys(metadata).length > 0) {
                formData.append('file_metadata', JSON.stringify(metadata));
            }

            const response = await fetch(`${this.getBaseUrl()}/api/v1/files/`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${authToken}`
                    // Don't set Content-Type for multipart/form-data
                },
                body: formData
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`Upload failed: ${response.status} - ${errorText}`);
            }

            const result = await response.json();
            console.log('File upload response:', result);

            return {
                success: true,
                fileId: result.id,
                file: result
            };
        } catch (error) {
            console.error('Failed to upload file:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Add file to knowledge base
     * Correct endpoint: POST /api/v1/knowledge/{id}/file/add
     */
    addFileToKnowledge: async function(knowledgeId, fileId) {
        try {
            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/file/add`,
                'POST',
                { file_id: fileId }
            );

            console.log('Add file to knowledge response:', response);

            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to add file to knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Add multiple files to knowledge base (batch operation)
     * Correct endpoint: POST /api/v1/knowledge/{id}/files/batch/add
     */
    addFilesToKnowledgeBatch: async function(knowledgeId, fileIds) {
        try {
            const fileIdForms = fileIds.map(fileId => ({ file_id: fileId }));

            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/files/batch/add`,
                'POST',
                fileIdForms // Array of KnowledgeFileIdForm objects
            );

            console.log('Batch add files response:', response);

            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to batch add files to knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Remove file from knowledge base
     * Correct endpoint: POST /api/v1/knowledge/{id}/file/remove
     */
    removeFileFromKnowledge: async function(knowledgeId, fileId) {
        try {
            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/file/remove`,
                'POST',
                { file_id: fileId }
            );

            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to remove file from knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Update knowledge base
     * Correct endpoint: POST /api/v1/knowledge/{id}/update
     */
    updateKnowledge: async function(knowledgeId, name, description, data = null, access_control = null) {
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

            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/update`,
                'POST',
                payload
            );

            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to update knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Delete knowledge base
     * Correct endpoint: DELETE /api/v1/knowledge/{id}/delete
     */
    deleteKnowledge: async function(knowledgeId) {
        try {
            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/delete`,
                'DELETE'
            );

            return {
                success: true
            };
        } catch (error) {
            console.error('Failed to delete knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Reset knowledge base
     * Correct endpoint: POST /api/v1/knowledge/{id}/reset
     */
    resetKnowledge: async function(knowledgeId) {
        try {
            const response = await this.makeRequest(
                `/api/v1/knowledge/${knowledgeId}/reset`,
                'POST'
            );

            return {
                success: true,
                knowledge: response
            };
        } catch (error) {
            console.error('Failed to reset knowledge base:', error);
            return {
                success: false,
                error: error.message
            };
        }
    },

    /**
     * Check if knowledge base exists
     */
    knowledgeExists: async function(knowledgeId) {
        try {
            const result = await this.getKnowledgeCollection(knowledgeId);
            return result.success;
        } catch (error) {
            return false;
        }
    },

    /**
     * Reindex all knowledge files
     * Correct endpoint: POST /api/v1/knowledge/reindex
     */
    reindexKnowledgeFiles: async function() {
        try {
            const response = await this.makeRequest('/api/v1/knowledge/reindex', 'POST');

            return {
                success: true
            };
        } catch (error) {
            console.error('Failed to reindex knowledge files:', error);
            return {
                success: false,
                error: error.message
            };
        }
    }
};

// Make it globally accessible
window.KnowledgeAPI = KnowledgeAPI;

console.log('Knowledge API client (corrected) loaded successfully');
